#!/usr/bin/env python3
"""Minimal deterministic DNS server used by the DOSBox integration test."""

import os
import socket
import struct
import sys

LISTEN_ADDRESS = "127.0.0.1"
LISTEN_PORT = 53
EXPECTED_NAME = "ci.yame.test"
ANSWER_ADDRESS = "203.0.113.42"
TCP_NAME = "tcp.yame.test"
TCP_ANSWER_ADDRESS = os.environ.get("YAME_TCP_FIXTURE_ADDRESS", "127.0.0.1")


def question_end(packet: bytes) -> int:
    offset = 12
    while True:
        if offset >= len(packet):
            raise ValueError("truncated DNS question")
        length = packet[offset]
        offset += 1
        if length == 0:
            break
        if length & 0xC0:
            raise ValueError("compressed question name is not supported")
        offset += length
    if offset + 4 > len(packet):
        raise ValueError("truncated DNS question type/class")
    return offset + 4


def question_name(packet: bytes) -> str:
    labels = []
    offset = 12
    while packet[offset] != 0:
        length = packet[offset]
        offset += 1
        labels.append(packet[offset:offset + length].decode("ascii"))
        offset += length
    return ".".join(labels).lower()


with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind((LISTEN_ADDRESS, LISTEN_PORT))
    print(f"DNS fixture listening on {LISTEN_ADDRESS}:{LISTEN_PORT}", flush=True)

    while True:
        packet, peer = sock.recvfrom(4096)
        try:
            end = question_end(packet)
            name = question_name(packet)
            qtype, qclass = struct.unpack("!HH", packet[end - 4:end])
            print(f"query {name} type={qtype} class={qclass} from {peer[0]}:{peer[1]}", flush=True)

            if qtype == 1 and qclass == 1 and name in (EXPECTED_NAME, TCP_NAME):
                flags = 0x8180
                answer_count = 1
                address = ANSWER_ADDRESS if name == EXPECTED_NAME else TCP_ANSWER_ADDRESS
                answer = (
                    bytes((0xC0, 0x0C))
                    + struct.pack("!HHIH", 1, 1, 60, 4)
                    + socket.inet_aton(address)
                )
            else:
                flags = 0x8183
                answer_count = 0
                answer = b""

            response = (
                packet[:2]
                + struct.pack("!HHHHH", flags, 1, answer_count, 0, 0)
                + packet[12:end]
                + answer
            )
            sock.sendto(response, peer)
        except Exception as exc:
            print(f"ignored malformed query: {exc}", file=sys.stderr, flush=True)
