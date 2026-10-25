#!/usr/bin/env python3
"""Prepare independent HTTP sources and verify files saved by the DOS client."""

import argparse
import hashlib
import json
from pathlib import Path


SOURCES = (("BINA.BIN", 628_991), ("BINB.BIN", 1_259_448))
CASES = (
    ("http-small", "BINA.BIN", "BINA.OUT"),
    ("http-large", "BINB.BIN", "BINB.OUT"),
    ("tcp-large", "BINB.BIN", "BINRAW.OUT"),
)
MAX_SOURCE_BYTES = 16 * 1024 * 1024


def prepare(directory, sources=()):
    if len(sources) not in (0, 2):
        raise ValueError("provide either no source files or exactly two source files")
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    manifest = []
    for index, (name, size) in enumerate(SOURCES):
        if sources:
            with Path(sources[index]).open("rb") as source:
                data = source.read(MAX_SOURCE_BYTES + 1)
            origin = str(Path(sources[index]).resolve())
        else:
            # Every byte value, PPP escapes, XON/XOFF and text-rewrite candidates;
            # a nonrepeating tail also detects duplicate/reordered TCP blocks.
            prefix = bytes(range(256)) + b"\x00https://binary.yame.test/unchanged\r\n"
            data = prefix + hashlib.shake_256(name.encode("ascii")).digest(size - len(prefix))
            origin = "generated"
        if not 0 < len(data) <= MAX_SOURCE_BYTES:
            raise ValueError(f"{origin}: source must contain 1..{MAX_SOURCE_BYTES} bytes")
        (directory / name).write_bytes(data)
        manifest.append({"file": name, "source": origin, "bytes": len(data),
                         "sha256": hashlib.sha256(data).hexdigest()})
    (directory / "sources.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def verify(directory, received_directory):
    directory, received_directory = Path(directory), Path(received_directory)
    results = []
    for case, source_name, received_name in CASES:
        expected = (directory / source_name).read_bytes()
        received_path = received_directory / received_name
        actual = received_path.read_bytes() if received_path.is_file() else None
        matches = actual == expected
        result = {"case": case, "received_file": received_name, "ok": matches,
                  "expected_bytes": len(expected),
                  "expected_sha256": hashlib.sha256(expected).hexdigest(),
                  "actual_bytes": len(actual) if actual is not None else None,
                  "actual_sha256": hashlib.sha256(actual).hexdigest() if actual is not None else None}
        if actual is None:
            result["error"] = "DOS client did not save the file"
        elif not matches:
            offset = next((i for i, (a, b) in enumerate(zip(expected, actual)) if a != b),
                          min(len(expected), len(actual)))
            result.update(first_difference=offset,
                          expected_byte=expected[offset] if offset < len(expected) else None,
                          actual_byte=actual[offset] if offset < len(actual) else None)
        results.append(result)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    preparation = commands.add_parser("prepare")
    preparation.add_argument("directory", type=Path)
    preparation.add_argument("sources", type=Path, nargs="*")
    verification = commands.add_parser("verify")
    verification.add_argument("directory", type=Path)
    verification.add_argument("received_directory", type=Path)
    verification.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        for source in prepare(args.directory, args.sources):
            print(f"Source {source['file']}: {source['bytes']} bytes, SHA-256 {source['sha256']}")
        return 0
    results = verify(args.directory, args.received_directory)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(results, indent=2) + "\n")
    for result in results:
        if result["ok"]:
            print(f"PASS {result['case']}: {result['actual_bytes']} bytes, SHA-256 {result['actual_sha256']}")
        else:
            print(f"FAIL {result['case']}: {json.dumps(result, sort_keys=True)}")
    return 0 if all(result["ok"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
