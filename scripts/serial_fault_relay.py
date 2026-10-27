#!/usr/bin/env python3
"""Bridge DOSBox's raw serial TCP transport to socat with recorded wire faults."""

import argparse
import json
import socket
import threading
from pathlib import Path

from serial_faults import DIRECTIONS, FaultInjector, WireStream, fault_plan, verify_report


def relay(client, upstream, injector):
    stop = threading.Event()

    def forward(source, destination, direction):
        stream = WireStream(injector, direction)
        try:
            while not stop.is_set():
                data = source.recv(4096)
                if not data:
                    tail = stream.finish()
                    if tail:
                        destination.sendall(tail)
                    destination.shutdown(socket.SHUT_WR)
                    return
                for output, delay in stream.feed(data):
                    if delay and stop.wait(delay):
                        return
                    if output:
                        destination.sendall(output)
        except Exception as error:
            if not stop.is_set():
                injector.fail(error)
                stop.set()
                for sock in (client, upstream):
                    try:
                        sock.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass

    workers = [threading.Thread(target=forward, args=(upstream, client, DIRECTIONS[0])),
               threading.Thread(target=forward, args=(client, upstream, DIRECTIONS[1]))]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", type=int, default=50454)
    parser.add_argument("--upstream", type=int, default=50453)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--profile", choices=("none", "serial-faults"), default="serial-faults")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        problems = verify_report(json.loads(args.report.read_text()))
        for problem in problems:
            print(problem)
        if not problems:
            print("All 30 serial faults fired; every damaged download range was retransmitted")
        return int(bool(problems))
    injector = FaultInjector(fault_plan() if args.profile == "serial-faults" else [], args.report)
    try:
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", args.listen))
            listener.listen(1)
            print(f"Serial fault relay listening on {listener.getsockname()[1]}", flush=True)
            client, _ = listener.accept()
            with client, socket.create_connection(("127.0.0.1", args.upstream), timeout=5) as upstream:
                upstream.settimeout(None)
                for sock in (client, upstream):
                    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                relay(client, upstream, injector)
    except Exception as error:
        injector.fail(error)
        raise
    return int(injector.error is not None)


if __name__ == "__main__":
    raise SystemExit(main())
