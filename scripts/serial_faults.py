"""Test-only PPP wire fault injection. Never repair or re-encode a received frame."""

import json
import struct
import threading
from pathlib import Path


FLAG = 0x7e
MAX_WIRE_FRAME = 131_090
DIRECTIONS = ("to_dosbox", "to_yame")


def fcs(data):
    value = 0xffff
    for byte in data:
        value ^= byte
        for _ in range(8):
            value = (value >> 1) ^ (0x8408 if value & 1 else 0)
    return value


def decode_tcp(wire):
    """Inspect unescaped PPP/IPv4/TCP and retain decoded-to-wire positions."""
    data, positions = bytearray(), []
    escaped = False
    for index, byte in enumerate(wire):
        if escaped:
            data.append(byte ^ 0x20)
            positions.append(index)
            escaped = False
        elif byte == 0x7d:
            escaped = True
        else:
            data.append(byte)
            positions.append(index)
    if escaped or len(data) < 4 or fcs(data) != 0xf0b8:
        return None
    ip = 2 if data[:2] == b"\xff\x03" else 0
    if data[ip] & 1:
        protocol = data[ip]
        ip += 1
    else:
        protocol = int.from_bytes(data[ip:ip + 2], "big")
        ip += 2
    if protocol != 0x21 or len(data) < ip + 22 or data[ip] >> 4 != 4:
        return None
    ihl = (data[ip] & 15) * 4
    length = int.from_bytes(data[ip + 2:ip + 4], "big")
    fragment = int.from_bytes(data[ip + 6:ip + 8], "big")
    if (ihl < 20 or length < ihl + 20 or len(data) != ip + length + 2
            or data[ip + 9] != 6 or fragment & 0x3fff):
        return None
    tcp = ip + ihl
    source, destination, seq, ack = struct.unpack_from("!HHII", data, tcp)
    tcp_size = (data[tcp + 12] >> 4) * 4
    if tcp_size < 20 or ihl + tcp_size > length:
        return None
    return dict(source=source, destination=destination, seq=seq, ack=ack,
                source_ip=bytes(data[ip + 12:ip + 16]).hex(),
                destination_ip=bytes(data[ip + 16:ip + 20]).hex(),
                flags=data[tcp + 13], payload_size=length - ihl - tcp_size,
                payload_index=tcp + tcp_size, ack_index=tcp + 8,
                positions=positions)


def fault_plan():
    """One-shot faults per download; offsets refer to the HTTP TCP byte stream."""
    plan = []
    for flow in range(1, 4):
        for direction, offset, action, amount in (
            ("to_dosbox", 32_768, "delete", 1),
            ("to_dosbox", 98_304, "delete", 3),
            ("to_dosbox", 163_840, "flip", 1),
            ("to_dosbox", 229_376, "drop", 0),
            ("to_dosbox", 294_912, "pause", 2),
            ("to_dosbox", 393_216, "delete", 64),
            ("to_yame", 65_536, "delete", 3),
            ("to_yame", 131_072, "flip", 1),
            ("to_yame", 196_608, "drop", 0),
            ("to_yame", 327_680, "pause", 2),
        ):
            plan.append(dict(id=f"{flow}-{direction}-{offset}-{action}", flow=flow,
                             direction=direction, offset=offset, action=action, amount=amount))
    return plan


class FaultInjector:
    def __init__(self, plan, report=None):
        self.plan = plan
        self.report_path = Path(report) if report else None
        self.flows = {}
        self.events = []
        self.fired = set()
        self.lock = threading.Lock()
        self.error = None
        self.save()

    def save(self):
        if self.report_path:
            # Atomic snapshots remain usable if a transfer hangs or a process dies.
            temporary = self.report_path.with_suffix(".tmp")
            temporary.write_text(json.dumps(dict(plan=self.plan, events=self.events,
                                                error=self.error), indent=2) + "\n")
            temporary.replace(self.report_path)

    def fail(self, error):
        with self.lock:
            self.error = str(error)
            self.save()

    def transform(self, direction, wire, stream_offset):
        packet = decode_tcp(wire)
        if packet is None:
            return wire, 0
        with self.lock:
            return self._transform(direction, wire, stream_offset, packet)

    def _transform(self, direction, wire, stream_offset, packet):
        down = direction == "to_dosbox"
        if down:
            key = (packet["destination_ip"], packet["destination"],
                   packet["source_ip"], packet["source"])
        else:
            key = (packet["source_ip"], packet["source"],
                   packet["destination_ip"], packet["destination"])
        if key[3] not in (80, 18080):
            return wire, 0
        if down and packet["flags"] & 0x12 == 0x12 and key not in self.flows:
            self.flows[key] = dict(number=len(self.flows) + 1,
                                   origin=(packet["seq"] + 1) & 0xffffffff)
        flow = self.flows.get(key)
        if flow is None or packet["flags"] & 0x07:
            return wire, 0  # Leave connection setup/teardown and PPP control alone.
        start = ((packet["seq"] if down else packet["ack"]) - flow["origin"]) & 0xffffffff
        size = packet["payload_size"]
        if down and size:
            # Observe retransmission of the exact damaged TCP range, not just a
            # generic retransmission elsewhere in the session.
            changed = False
            for event in self.events:
                if (event["flow"] == flow["number"] and event["direction"] == direction
                        and event["action"] != "pause" and not event.get("retransmitted")
                        and start <= event["offset"] < start + size):
                    event["retransmitted"] = True
                    changed = True
            if changed:
                self.save()
        if (down and not size) or (not down and (size or packet["flags"] != 0x10)):
            return wire, 0
        for rule in self.plan:
            if (rule["id"] in self.fired or rule["flow"] != flow["number"]
                    or rule["direction"] != direction):
                continue
            target = rule["offset"]
            if not (start <= target < start + size if down else start >= target):
                continue
            decoded_index = packet["payload_index"] + target - start if down else packet["ack_index"]
            index = packet["positions"][decoded_index]
            action, amount = rule["action"], rule["amount"]
            output, delay, removed = wire, 0, b""
            if action == "delete":
                # Delete raw serial octets, possibly including an escape byte.
                # Retain the original FCS bytes, even if deletion hits the payload end.
                fcs_start = packet["positions"][-2]
                if wire[fcs_start - 1] == 0x7d:
                    fcs_start -= 1
                end = min(index + amount, fcs_start)
                removed = wire[index:end]
                if not removed:
                    raise ValueError("fault target has no removable wire bytes")
                output = wire[:index] + wire[end:]
            elif action == "flip":
                output = wire[:index] + bytes([wire[index] ^ amount]) + wire[index + 1:]
            elif action == "drop":
                removed, output = wire, b""
            elif action == "pause":
                delay = amount
            else:
                raise ValueError(f"unknown fault action: {action}")
            self.fired.add(rule["id"])
            event = dict(rule, stream_start=start, tcp_seq=packet["seq"], tcp_ack=packet["ack"],
                         wire_stream_offset=stream_offset, wire_index=index,
                         input_bytes=len(wire), output_bytes=len(output),
                         removed_hex=removed.hex(), original_byte=wire[index],
                         changed_byte=output[index] if action == "flip" else None,
                         retransmitted=False)
            self.events.append(event)
            self.save()
            print(f"Injected {rule['id']}: wire offset {stream_offset + index}, "
                  f"TCP seq={packet['seq']} ack={packet['ack']}", flush=True)
            return output, delay
        return wire, 0


class WireStream:
    """Forward modem text immediately; buffer at most one encoded PPP frame."""
    def __init__(self, injector, direction):
        self.injector, self.direction = injector, direction
        self.buffer = bytearray()
        self.framed = False
        self.offset = 0
        self.frame_start = 0

    def feed(self, data):
        for byte in data:
            self.offset += 1
            if byte == FLAG:
                if self.framed and self.buffer:
                    output, delay = self.injector.transform(
                        self.direction, bytes(self.buffer), self.frame_start)
                    yield output + b"\x7e", delay
                else:
                    yield bytes(self.buffer) + b"\x7e", 0
                self.buffer.clear()
                self.framed = True
                self.frame_start = self.offset
            else:
                self.buffer.append(byte)
                if len(self.buffer) > MAX_WIRE_FRAME:
                    raise ValueError("unterminated/oversized PPP wire frame")
        if not self.framed:
            yield bytes(self.buffer), 0
            self.buffer.clear()

    def finish(self):
        return bytes(self.buffer)


def verify_report(report):
    expected = fault_plan()
    events = report.get("events", [])
    problems = []
    if report.get("error"):
        problems.append(f"relay error: {report['error']}")
    if report.get("plan") != expected:
        problems.append("fault plan differs from the required serial-faults plan")
    if sorted(e["id"] for e in events) != sorted(r["id"] for r in expected):
        problems.append("not every planned fault was injected exactly once")
    for event in events:
        if (event["direction"] == "to_dosbox" and event["action"] != "pause"
                and not event.get("retransmitted")):
            problems.append(f"no retransmission observed for {event['id']}")
    return problems
