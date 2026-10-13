"""Extract only bounded preview files, without trusting ZIP paths or sizes."""
import stat
import sys
import zipfile
from pathlib import Path

PNG_NAMES = (
    "30x100.png", "30x100-proxy.png", "30x100-dns.png", "30x100-transfers.png",
    "45x135.png", "45x135-proxy.png", "45x135-dns.png", "45x135-transfers.png",
    "50x150.png", "50x150-proxy.png",
)
FRAME_NAMES = tuple(name.removesuffix(".png") + ".ansi.txt" for name in PNG_NAMES)
LIMITS = {
    **dict.fromkeys(PNG_NAMES, 1024 * 1024),
    **dict.fromkeys(FRAME_NAMES, 256 * 1024),
    "render-info.json": 4096,
    "source.json": 4096,
}
ZIP_LIMIT = 5 * 1024 * 1024
TOTAL_LIMIT = sum(LIMITS.values())


def extract(archive, destination):
    archive, destination = Path(archive), Path(destination)
    if not archive.is_file() or archive.stat().st_size > ZIP_LIMIT:
        raise ValueError("Preview ZIP exceeds limit")
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        if len(entries) != len(LIMITS) or {e.filename for e in entries} != set(LIMITS):
            raise ValueError("Expected exactly the approved preview files")
        for entry in entries:
            mode = entry.external_attr >> 16
            if (entry.is_dir() or (stat.S_IFMT(mode) and not stat.S_ISREG(mode))
                    or entry.flag_bits & 1
                    or entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    or entry.file_size > LIMITS[entry.filename]):
                raise ValueError("Invalid preview entry or extracted size")
        if sum(e.file_size for e in entries) > TOTAL_LIMIT:
            raise ValueError("Extracted preview total exceeds limit")
        # A new directory prevents existing files or symlinks from being followed.
        destination.mkdir()
        total = 0
        for entry in entries:
            size = 0
            with zipped.open(entry) as source, (destination / entry.filename).open("xb") as target:
                while chunk := source.read(64 * 1024):
                    size += len(chunk)
                    total += len(chunk)
                    if size > LIMITS[entry.filename] or total > TOTAL_LIMIT:
                        raise ValueError("Actual extracted size exceeds limit")
                    target.write(chunk)


if __name__ == "__main__":
    extract(*sys.argv[1:])
