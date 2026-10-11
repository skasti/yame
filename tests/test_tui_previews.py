import importlib.util
import stat
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "extract_previews", Path(__file__).resolve().parents[1] / "scripts/ci/extract-tui-previews.py")
extractor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(extractor)


class PreviewArchiveTest(unittest.TestCase):
    def archive(self, directory, replacements=None, extra=None):
        archive = Path(directory) / "previews.zip"
        with warnings.catch_warnings(), zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
            warnings.simplefilter("ignore", UserWarning)
            for name in extractor.LIMITS:
                zipped.writestr(name, (replacements or {}).get(name, b"preview"))
            if extra:
                zipped.writestr(*extra)
        return archive

    def test_valid_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "out"
            extractor.extract(self.archive(directory), target)
            self.assertEqual({p.name for p in target.iterdir()}, set(extractor.LIMITS))

    def test_compressed_provenance_and_png_bombs_fail_before_extraction(self):
        for name in ("source.json", "30x100.png"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                archive = self.archive(directory, {name: b"a" * (extractor.LIMITS[name] + 1)})
                self.assertLess(archive.stat().st_size, extractor.ZIP_LIMIT)
                target = Path(directory) / "out"
                with self.assertRaisesRegex(ValueError, "extracted size"):
                    extractor.extract(archive, target)
                self.assertFalse(target.exists())

    def test_extra_paths_and_duplicates_fail_before_extraction(self):
        for name in ("../escape", "/tmp/escape", "source.json", "nested/unexpected"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "out"
                with self.assertRaisesRegex(ValueError, "approved preview files"):
                    extractor.extract(self.archive(directory, extra=(name, b"bad")), target)
                self.assertFalse(target.exists())

    def test_symlink_entry_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "previews.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                for name in extractor.LIMITS:
                    entry = zipfile.ZipInfo(name)
                    entry.create_system = 3
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                    zipped.writestr(entry, b"/tmp/target")
            with self.assertRaisesRegex(ValueError, "Invalid preview entry"):
                extractor.extract(archive, Path(directory) / "out")

    def test_existing_destination_is_not_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "out"
            target.mkdir()
            with self.assertRaises(FileExistsError):
                extractor.extract(self.archive(directory), target)


if __name__ == "__main__":
    unittest.main()
