"""Check fault placement independently of socket read boundaries and the DOS stack."""

import contextlib
import io
import json
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from serial_faults import FaultInjector, MAX_WIRE_FRAME, WireStream, decode_tcp, fcs, fault_plan, verify_report
from serial_fault_relay import relay


def encode(data):
    checksum = fcs(data) ^ 0xffff
    data += struct.pack("<H", checksum)
    return bytes(b for byte in data for b in ((0x7d, byte ^ 0x20)
                 if byte < 32 or byte in (0x7d, 0x7e) else (byte,)))


def tcp_frame(seq=1001, ack=2001, payload=b"", down=True, flags=0x10,
              compressed=False, port=1234):
    addresses = (b"\x7f\x00\x00\x01", b"\x0a\x40\x00\x02")
    ports = (80, port)
    if not down:
        addresses, ports = addresses[::-1], ports[::-1]
    ip = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 40 + len(payload), 1, 0, 64, 6, 0, *addresses)
    tcp = struct.pack("!HHIIBBHHH", *ports, seq, ack, 0x50, flags, 8192, 0, 0)
    return encode((b"\x21" if compressed else b"\xff\x03\x00\x21") + ip + tcp + payload)


def rule(action, amount=1, direction="to_dosbox", offset=32, flow=1):
    return dict(id=f"test-{flow}-{direction}-{offset}", flow=flow, direction=direction,
                offset=offset, action=action, amount=amount)


def handshake(injector, port=1234, seq=1000):
    wire = tcp_frame(seq=seq, flags=0x12, port=port)
    assert injector.transform("to_dosbox", wire, 0) == (wire, 0)


class SerialFaultTest(unittest.TestCase):
    def setUp(self):
        # The injector's diagnostic lines are useful in integration, noisy in unit tests.
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)

    def test_ppp_checksum_reference_and_compressed_headers(self):
        self.assertEqual(fcs(b"123456789"), 0x6f91)
        for compressed in (False, True):
            packet = decode_tcp(tcp_frame(payload=bytes(range(256)), compressed=compressed))
            self.assertEqual(packet["payload_size"], 256)
            self.assertEqual(packet["seq"], 1001)

    def test_passthrough_preserves_modem_text_flags_escapes_and_partial_tail(self):
        wire = (b"ATDT1\r\nCONNECT 115200\r\n\x7e\x7e"
                + tcp_frame(payload=bytes(range(256))) + b"\x7e\x7epartial\x7d")
        for width in (1, 7, 4096):
            stream = WireStream(FaultInjector([]), "to_dosbox")
            output = b"".join(part for i in range(0, len(wire), width)
                              for part, delay in stream.feed(wire[i:i + width]))
            self.assertEqual(output + stream.finish(), wire)

    def test_modem_text_does_not_wait_for_ppp_flag(self):
        stream = WireStream(FaultInjector([]), "to_yame")
        self.assertEqual(list(stream.feed(b"AT\r")), [(b"AT\r", 0)])

    def test_corruption_keeps_original_fcs_and_is_not_repeated_on_retransmit(self):
        for action, amount in (("delete", 1), ("delete", 3), ("delete", 64), ("flip", 1)):
            with self.subTest(action=action, amount=amount):
                injector = FaultInjector([rule(action, amount)])
                handshake(injector)
                wire = tcp_frame(payload=bytes(range(256)))
                result, _ = injector.transform("to_dosbox", wire, 200)
                self.assertNotEqual(wire, result)
                self.assertIsNone(decode_tcp(result))
                self.assertEqual(result[-4:], wire[-4:])
                self.assertEqual(len(wire) - len(result), amount if action == "delete" else 0)
                self.assertFalse(injector.events[0]["retransmitted"])
                self.assertEqual(injector.transform("to_dosbox", wire, 1000), (wire, 0))
                self.assertTrue(injector.events[0]["retransmitted"])
                self.assertEqual(len(injector.events), 1)

    def test_faults_and_offsets_do_not_depend_on_read_chunking(self):
        wire = b"CONNECT\r\n\x7e" + tcp_frame(seq=1000, flags=0x12) + b"\x7e"
        data = tcp_frame(payload=bytes(range(256)))
        wire += data + b"\x7e" + data + b"\x7e"
        results = []
        for width in (1, 2, 13, 257, len(wire)):
            injector = FaultInjector([rule("delete", 3)])
            stream = WireStream(injector, "to_dosbox")
            output = b"".join(part for i in range(0, len(wire), width)
                              for part, _ in stream.feed(wire[i:i + width]))
            results.append((output, injector.events))
        self.assertTrue(all(result == results[0] for result in results))

    def test_deletion_at_last_payload_byte_keeps_fcs_without_aborting_relay(self):
        for tail in range(256):
            injector = FaultInjector([rule("delete", 64)])
            handshake(injector)
            wire = tcp_frame(payload=b"A" * 32 + bytes([tail]))
            output, delay = injector.transform("to_dosbox", wire, 0)
            self.assertEqual(len(wire) - len(output), 1)
            self.assertEqual(delay, 0)
            self.assertIsNone(decode_tcp(output))
            self.assertEqual(wire[-2:], output[-2:])

    def test_ack_damage_in_reverse_direction_and_sequence_wrap(self):
        injector = FaultInjector([rule("flip", direction="to_yame")])
        handshake(injector, seq=0xfffffff0)
        ack = tcp_frame(seq=2001, ack=(0xfffffff1 + 40) & 0xffffffff, down=False)
        damaged, _ = injector.transform("to_yame", ack, 0)
        self.assertNotEqual(ack, damaged)
        self.assertEqual(injector.events[0]["stream_start"], 40)
        self.assertIsNone(decode_tcp(damaged))

    def test_control_frames_bad_fcs_and_unrelated_ports_are_untouched(self):
        injector = FaultInjector([rule("drop")])
        handshake(injector)
        for wire in (encode(b"\xff\x03\xc0\x21\x01\x01\x00\x04"),
                     tcp_frame(flags=0x11), b"bad crc",
                     tcp_frame(seq=1, flags=0x12, down=False)):
            self.assertEqual(injector.transform("to_dosbox", wire, 0), (wire, 0))
        self.assertEqual(injector.events, [])

    def test_drop_and_pause_preserve_next_frame_and_byte_order(self):
        for action, amount in (("drop", 0), ("pause", 2)):
            injector = FaultInjector([rule(action, amount)])
            handshake(injector)
            stream = WireStream(injector, "to_dosbox")
            data = tcp_frame(payload=b"A" * 80)
            following = tcp_frame(seq=1081, payload=b"B" * 80)
            result = list(stream.feed(b"\x7e" + data + b"\x7e" + following + b"\x7e"))
            self.assertEqual(result[-1], (following + b"\x7e", 0))
            self.assertEqual(result[1], (b"\x7e", 0) if action == "drop" else (data + b"\x7e", 2))

    def test_frame_buffer_is_bounded(self):
        stream = WireStream(FaultInjector([]), "to_dosbox")
        with self.assertRaisesRegex(ValueError, "oversized"):
            list(stream.feed(b"\x7e" + b"a" * (MAX_WIRE_FRAME + 1)))

    def test_complete_plan_requires_actual_injections_and_retransmissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            injector = FaultInjector(fault_plan(), path)
            self.assertTrue(verify_report(json.loads(path.read_text())))
            for flow in range(1, 4):
                port = 1234 + flow
                handshake(injector, port=port)
                for fault in [r for r in fault_plan() if r["flow"] == flow]:
                    down = fault["direction"] == "to_dosbox"
                    wire = tcp_frame(seq=1001 + fault["offset"] if down else 2001,
                                     ack=1001 + fault["offset"], port=port, down=down,
                                     payload=b"A" * 256 if down else b"")
                    injector.transform(fault["direction"], wire, 0)
                    if down:
                        injector.transform(fault["direction"], wire, 1)
            report = json.loads(path.read_text())
            self.assertEqual(verify_report(report), [])
            self.assertEqual(len(report["events"]), 30)
            report["events"][0]["retransmitted"] = False
            self.assertTrue(verify_report(report))
            injector.fail("connection reset")
            self.assertTrue(verify_report(json.loads(path.read_text())))

    def test_socket_bridge_is_bidirectional_and_pause_does_not_block_reverse(self):
        # Real socket streams exercise forwarding, half-close and delayed writes.
        # socketpair stays isolated and does not need a host port.
        dos, client = socket.socketpair()
        upstream, yame = socket.socketpair()
        for sock in (dos, client, upstream, yame):
            self.addCleanup(sock.close)
        injector = FaultInjector([rule("pause", 0.15)])
        worker = threading.Thread(target=relay, args=(client, upstream, injector), daemon=True)
        worker.start()
        yame.sendall(b"CONNECT\r\n")
        dos.settimeout(2)
        self.assertEqual(dos.recv(64), b"CONNECT\r\n")
        handshake_wire = b"\x7e" + tcp_frame(seq=1000, flags=0x12) + b"\x7e"
        data_wire = tcp_frame(payload=b"A" * 80) + b"\x7e"
        yame.sendall(handshake_wire + data_wire)
        received = b""
        while len(received) < len(handshake_wire):
            received += dos.recv(len(handshake_wire) - len(received))
        dos.settimeout(0.03)
        with self.assertRaises(socket.timeout):
            dos.recv(1)
        dos.sendall(b"AT\r")
        yame.settimeout(0.05)
        self.assertEqual(yame.recv(64), b"AT\r")
        dos.settimeout(2)
        while len(received) < len(handshake_wire) + len(data_wire):
            received += dos.recv(4096)
        self.assertEqual(received, handshake_wire + data_wire)
        yame.shutdown(socket.SHUT_WR)
        self.assertEqual(dos.recv(1), b"")
        dos.shutdown(socket.SHUT_WR)
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertIsNone(injector.error)


if __name__ == "__main__":
    unittest.main()
