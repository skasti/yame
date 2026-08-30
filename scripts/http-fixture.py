#!/usr/bin/env python3
"""Deterministic HTTP server used by the DOSBox TCP integration test."""

import socket

LISTEN_ADDRESS = "0.0.0.0"
LISTEN_PORT = 18080
EXPECTED_PATH = "/test.txt"
BODY = b"YAME TCP integration OK\r\n" + (b"x" * 20000) + b"\r\nYAME TCP window OK\r\n"

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_ADDRESS, LISTEN_PORT))
    server.listen(4)
    print(f"HTTP fixture listening on {LISTEN_ADDRESS}:{LISTEN_PORT}", flush=True)

    while True:
        connection, peer = server.accept()
        with connection:
            connection.settimeout(10)
            request = bytearray()
            while b"\r\n\r\n" not in request and len(request) < 16384:
                chunk = connection.recv(4096)
                if not chunk:
                    break
                request.extend(chunk)

            first_line = bytes(request).split(b"\r\n", 1)[0].decode("ascii", "replace")
            print(f"request {first_line} from {peer[0]}:{peer[1]}", flush=True)

            parts = first_line.split(" ")
            path = parts[1] if len(parts) >= 2 else ""
            if path == EXPECTED_PATH:
                status = b"HTTP/1.1 200 OK\r\n"
                body = BODY
            else:
                status = b"HTTP/1.1 404 Not Found\r\n"
                body = b"not found\r\n"

            response = (
                status
                + f"Content-Length: {len(body)}\r\n".encode("ascii")
                + b"Content-Type: text/plain\r\n"
                + b"Connection: close\r\n"
                + b"\r\n"
                + body
            )
            connection.sendall(response)
