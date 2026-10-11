#!/usr/bin/env python3
"""Safely extract the bounded JVM ZIP published for Raspberry Pi deployments."""

import shutil
import stat
import sys
import zipfile
from pathlib import Path

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_UNPACKED_BYTES = 256 * 1024 * 1024
MAX_ENTRIES = 4096
CHUNK_SIZE = 64 * 1024


def extract(archive: Path, destination: Path, version: str) -> None:
    if archive.stat().st_size > MAX_ARCHIVE_BYTES:
        raise ValueError("release download exceeds 64 MiB")

    prefix = f"yame-{version}"
    with zipfile.ZipFile(archive) as zf:
        entries = zf.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("release ZIP contains too many entries")

        total_size = 0
        seen = set()
        for entry in entries:
            name = entry.filename
            parts = name.rstrip("/").split("/")
            if (
                not name
                or name.startswith("/")
                or "\\" in name
                or any(part in ("", ".", "..") for part in parts)
                or parts[0] != prefix
            ):
                raise ValueError(f"invalid release ZIP entry: {name!r}")
            if name in seen:
                raise ValueError(f"duplicate release ZIP entry: {name!r}")
            seen.add(name)

            mode = (entry.external_attr >> 16) & 0xFFFF
            kind = stat.S_IFMT(mode)
            if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError(f"unsupported release ZIP entry type: {name!r}")
            if entry.is_dir() and kind == stat.S_IFREG:
                raise ValueError(f"file marked as directory: {name!r}")
            if not entry.is_dir() and kind == stat.S_IFDIR:
                raise ValueError(f"directory marked as file: {name!r}")

            if entry.flag_bits & 1:
                raise ValueError("encrypted release ZIP entries are not supported")
            if entry.file_size > MAX_UNPACKED_BYTES - total_size:
                raise ValueError("release ZIP expands beyond 256 MiB")
            total_size += entry.file_size

        for entry in entries:
            target = destination / entry.filename
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            extracted = 0
            with zf.open(entry) as source, target.open("xb") as output:
                while chunk := source.read(CHUNK_SIZE):
                    extracted += len(chunk)
                    if extracted > entry.file_size:
                        raise ValueError("release ZIP decompressed size differs from metadata")
                    output.write(chunk)
            if extracted != entry.file_size:
                raise ValueError("release ZIP decompressed size differs from metadata")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("Usage: extract-release.py ZIP DESTINATION VERSION")
    try:
        extract(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as exc:
        sys.exit(f"Invalid or oversized YAME release ZIP: {exc}")
