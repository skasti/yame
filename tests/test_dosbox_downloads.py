"""Exercise the real download script with generated ZIPs and an offline curl stub."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/dosbox-network-downloads.sh'
FAKE_CURL = '''#!/usr/bin/env python3
import json, os, shutil, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['CURL_CALLS'], 'a') as log:
    log.write(json.dumps(args) + '\\n')
name = args[-1].rsplit('/', 1)[-1]
output = Path(args[args.index('--output') + 1])
if os.environ.get('FAIL_DOWNLOAD') == name:
    output.write_bytes(b'partial download')
    print('curl: (22) The requested URL returned error: 503', file=sys.stderr)
    sys.exit(22)
if os.environ.get('BAD_DOWNLOAD') == name:
    output.write_bytes(b'wrong archive')
else:
    shutil.copyfile(Path(os.environ['UPSTREAM_DIR']) / name, output)
'''


class DosboxDownloadsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cache = self.root / 'cache with spaces'
        self.upstream = self.root / 'upstream'
        self.upstream.mkdir()
        source = SCRIPT.read_text()
        self.pins = {}
        for variable, name, executables in (
                ('LS_PPP_SHA1', 'lsppp.zip', ('LSPPP.EXE',)),
                ('MTCP_SHA1', 'mtcp.zip', ('DNSTEST.EXE', 'HTGET.EXE'))):
            with zipfile.ZipFile(self.upstream / name, 'w') as archive:
                for executable in executables:
                    archive.writestr('fixture/' + executable, b'non-executable test fixture')
            self.pins[name] = hashlib.sha1((self.upstream / name).read_bytes()).hexdigest()
            source = re.sub(fr'{variable}="[a-f0-9]+"',
                            f'{variable}="{self.pins[name]}"', source)
        self.script = self.root / 'downloads.sh'
        self.script.write_text(source)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        curl = self.bin / 'curl'
        curl.write_text(FAKE_CURL)
        curl.chmod(0o755)
        self.calls_file = self.root / 'curl-calls.jsonl'
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        CURL_CALLS=str(self.calls_file), UPSTREAM_DIR=str(self.upstream))

    def run_downloads(self, **env):
        return subprocess.run(['bash', str(self.script), str(self.cache)],
                              env=dict(self.env, **env), capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.calls_file.read_text().splitlines()]

    def seed(self, name):
        self.cache.mkdir(exist_ok=True)
        shutil.copyfile(self.upstream / name, self.cache / name)

    def test_miss_fills_cache_and_next_run_verifies_without_http(self):
        first = self.run_downloads()
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(len(self.calls()), 2)
        for call in self.calls():
            self.assertEqual(call[call.index('--retry') + 1], '3')
            self.assertIn('--fail', call)
            self.assertIn('--show-error', call)
        second = self.run_downloads()
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(len(self.calls()), 2)
        self.assertEqual(second.stdout.count('Using checksum-verified archive:'), 2)
        for name in self.pins:
            self.assertEqual((self.cache / name).read_bytes(), (self.upstream / name).read_bytes())

    def test_partial_cache_fetches_only_missing_archive(self):
        self.seed('lsppp.zip')
        result = self.run_downloads()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.calls()), 1)
        self.assertTrue(self.calls()[0][-1].endswith('/mtcp.zip'))

    def test_corruption_is_rejected_and_only_invalid_archive_is_replaced(self):
        for name in self.pins:
            with self.subTest(name=name):
                for cached in self.pins:
                    self.seed(cached)
                (self.cache / name).write_bytes(b'corrupt cache')
                self.calls_file.write_text('')
                result = self.run_downloads()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('Rejecting invalid cached archive:', result.stderr)
                self.assertEqual(len(self.calls()), 1)
                self.assertTrue(self.calls()[0][-1].endswith('/' + name))
                self.assertEqual((self.cache / name).read_bytes(), (self.upstream / name).read_bytes())

    def test_http_failure_reports_setup_error_and_removes_partial_download(self):
        result = self.run_downloads(FAIL_DOWNLOAD='lsppp.zip')
        self.assertEqual(result.returncode, 2)
        self.assertIn('503', result.stderr)
        self.assertIn('cache miss and upstream download failed', result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_corrupt_cache_and_http_failure_leave_no_usable_archive(self):
        self.seed('lsppp.zip')
        (self.cache / 'lsppp.zip').write_bytes(b'corrupt cache')
        result = self.run_downloads(FAIL_DOWNLOAD='lsppp.zip')
        self.assertEqual(result.returncode, 2)
        self.assertIn('invalid cached archive and upstream download failed', result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_download_with_wrong_digest_is_never_published(self):
        result = self.run_downloads(BAD_DOWNLOAD='lsppp.zip')
        self.assertEqual(result.returncode, 2)
        self.assertIn('SHA-1 mismatch', result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [])

    def test_changed_digest_invalidates_existing_archive(self):
        self.assertEqual(self.run_downloads().returncode, 0)
        with zipfile.ZipFile(self.upstream / 'mtcp.zip', 'a') as archive:
            archive.writestr('version.txt', 'next test version')
        new_digest = hashlib.sha1((self.upstream / 'mtcp.zip').read_bytes()).hexdigest()
        self.script.write_text(self.script.read_text().replace(self.pins['mtcp.zip'], new_digest))
        result = self.run_downloads()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.calls()), 3)
        self.assertTrue(self.calls()[-1][-1].endswith('/mtcp.zip'))
        self.assertEqual(hashlib.sha1((self.cache / 'mtcp.zip').read_bytes()).hexdigest(), new_digest)

    def run_suite(self, **overrides):
        scripts = self.root / 'scripts'
        scripts.mkdir()
        shutil.copytree(ROOT / 'scripts/dos', scripts / 'dos')
        suite = scripts / 'dosbox-network-integration-test.sh'
        shutil.copyfile(ROOT / 'scripts' / suite.name, suite)
        shutil.copyfile(self.script, scripts / SCRIPT.name)
        for command in ('dosbox', 'socat', 'xvfb-run'):
            stub = self.bin / command
            stub.write_text('#!/bin/sh\nexit 99\n')
            stub.chmod(0o755)
        gradle = self.root / 'gradlew'
        gradle.write_text('#!/bin/sh\ntouch "$BUILD_REACHED"\nexit 73\n')
        gradle.chmod(0o755)
        env = dict(self.env, BUILD_REACHED=str(self.root / 'build-reached'))
        env.pop('YAME_DOSBOX_DOWNLOAD_CACHE', None)
        env.update(overrides)
        return subprocess.run(['bash', str(suite)], env=env, capture_output=True, text=True)

    def test_local_suite_without_cache_downloads_and_extracts_before_build(self):
        result = self.run_suite()
        self.assertEqual(result.returncode, 73, result.stderr)
        self.assertTrue((self.root / 'build-reached').exists())
        self.assertEqual(len(self.calls()), 2)
        # The local fallback is cleaned with the suite, not retained as a cache.
        for call in self.calls():
            self.assertFalse(Path(call[call.index('--output') + 1]).parent.exists())

    def test_suite_cleanup_preserves_supplied_verified_cache(self):
        for name in self.pins:
            self.seed(name)
        result = self.run_suite(YAME_DOSBOX_DOWNLOAD_CACHE=str(self.cache))
        self.assertEqual(result.returncode, 73, result.stderr)
        self.assertTrue((self.root / 'build-reached').exists())
        self.assertFalse(self.calls_file.exists())
        for name in self.pins:
            self.assertEqual((self.cache / name).read_bytes(), (self.upstream / name).read_bytes())

    def test_suite_setup_failure_does_not_start_build_or_print_integration_logs(self):
        result = self.run_suite(FAIL_DOWNLOAD='lsppp.zip')
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('503', result.stderr)
        self.assertIn('integration suite has not started', result.stderr)
        self.assertFalse((self.root / 'build-reached').exists())
        self.assertNotIn('=== YAME log ===', result.stdout)
        for call in self.calls():
            self.assertFalse(Path(call[call.index('--output') + 1]).parent.exists())


class DosboxCacheWorkflowTest(unittest.TestCase):
    def test_exact_cache_key_covers_versions_and_digests_and_setup_runs_on_hits(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/ci.yml').read_text())
        job = workflow['jobs']['build-and-test']
        steps = job['steps']
        cache = next(step for step in steps if step.get('uses', '').startswith('actions/cache@'))
        self.assertEqual(cache['with']['path'], '${{ env.YAME_DOSBOX_DOWNLOAD_CACHE }}')
        self.assertNotIn('restore-keys', cache['with'])
        hashed_path = re.search(r"hashFiles\('([^']+)'\)", cache['with']['key'])[1]
        source = (ROOT / hashed_path).read_text()
        self.assertEqual(ROOT / hashed_path, SCRIPT)
        original_hash = hashlib.sha256(source.encode()).hexdigest()
        for name in ('LS_PPP_SHA1', 'MTCP_SHA1', 'LS_PPP_URL', 'MTCP_URL'):
            pin = re.search(fr'{name}="([^"]+)"', source)[1]
            changed = source.replace(pin, pin + '-changed')
            self.assertNotEqual(hashlib.sha256(changed.encode()).hexdigest(), original_hash)
        prepare = next(step for step in steps if step.get('run', '').startswith('bash scripts/' + SCRIPT.name))
        suite = next(step for step in steps if step.get('run') == 'bash scripts/dosbox-network-integration-test.sh')
        self.assertLess(steps.index(cache), steps.index(prepare))
        self.assertLess(steps.index(prepare), steps.index(suite))
        self.assertEqual(prepare['if'], suite['if'])
        self.assertEqual(cache['if'], suite['if'])
        self.assertIn('"$YAME_DOSBOX_DOWNLOAD_CACHE"', prepare['run'])
        self.assertNotIn('cache-hit', prepare['if'])


if __name__ == '__main__':
    unittest.main()
