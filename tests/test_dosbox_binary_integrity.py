"""Ensure the serial integration verifier rejects plausible corruption patterns."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/dosbox_binary_integrity.py"
SPEC = importlib.util.spec_from_file_location("binary_integrity", SCRIPT)
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)


class BinaryIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sources = self.root / "sources"
        self.received = self.root / "dos"
        self.received.mkdir()
        FIXTURE.prepare(self.sources)
        for _, source, destination in FIXTURE.CASES:
            (self.received / destination).write_bytes((self.sources / source).read_bytes())

    def test_complete_binary_files_match_with_all_byte_values(self):
        data = (self.sources / "BINB.BIN").read_bytes()
        self.assertEqual(len(data), 1_259_448)
        self.assertEqual(set(data), set(range(256)))
        self.assertIn(b"https://", data)
        self.assertTrue(all(case["ok"] for case in FIXTURE.verify(self.sources, self.received)))

    def test_truncated_changed_duplicated_and_missing_files_are_rejected(self):
        path = self.received / "BINB.OUT"
        original = path.read_bytes()
        offset = 500_000
        mutations = (
            (original[:-4096], len(original) - 4096),
            (original[:offset] + bytes([original[offset] ^ 1]) + original[offset + 1:], offset),
            (original[:offset] + original[offset - 4096:offset] + original[offset:], offset),
            (original[:offset] + original[offset + 4096:], offset),
        )
        for data, difference in mutations:
            with self.subTest(bytes=len(data), difference=difference):
                path.write_bytes(data)
                result = FIXTURE.verify(self.sources, self.received)[1]
                self.assertFalse(result["ok"])
                self.assertEqual(result["first_difference"], difference)
        path.unlink()
        result = FIXTURE.verify(self.sources, self.received)[1]
        self.assertFalse(result["ok"])
        self.assertIsNone(result["actual_bytes"])

    def test_verification_command_fails_and_persists_diagnostic_report(self):
        (self.received / "BINRAW.OUT").write_bytes(b"wrong bytes")
        report = self.root / "report.json"
        result = subprocess.run([sys.executable, str(SCRIPT), "verify", str(self.sources),
                                 str(self.received), "--report", str(report)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertFalse(json.loads(report.read_text())[2]["ok"])

    def test_user_supplied_sources_are_copied_verbatim_outside_dos_drive(self):
        inputs = [self.root / "first file.exe", self.root / "second file.exe"]
        for index, path in enumerate(inputs):
            path.write_bytes(bytes(range(256)) + bytes([index]))
        FIXTURE.prepare(self.sources, inputs)
        for (name, _), source in zip(FIXTURE.SOURCES, inputs):
            self.assertEqual((self.sources / name).read_bytes(), source.read_bytes())
        with self.assertRaises(ValueError):
            FIXTURE.prepare(self.sources, inputs[:1])


if __name__ == "__main__":
    unittest.main()
