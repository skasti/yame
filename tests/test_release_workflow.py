"""Run the real publishing shell against a fake GitHub CLI, without publishing."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text())
PUBLISH = WORKFLOW['jobs']['publish']['steps'][-1]['run']
FAKE_GH = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
p = Path(os.environ['STATE'])
s = json.loads(p.read_text())
a = sys.argv[1:]
s['calls'].append(a)
p.write_text(json.dumps(s))
if a[0] == 'api':
    if s.get('forbidden'):
        print('HTTP 403: Resource not accessible by integration', file=sys.stderr)
        sys.exit(1)
    if s['exists']: print(os.environ['TAG'])
elif a[:2] == ['release', 'create']:
    s.update(exists=True, draft=True)
elif a[:2] == ['release', 'view']:
    if a[a.index('--json') + 1] == 'assets': print('\\n'.join(s['assets']))
    else: print(str(s['draft']).lower())
elif a[:2] == ['release', 'upload']:
    if s.get('upload_failure'):
        sys.exit(1)
    s['assets'].append(Path(a[3]).name)
elif a[:2] == ['release', 'edit']:
    s['draft'] = False
else:
    raise RuntimeError(a)
p.write_text(json.dumps(s))
'''


class ReleaseWorkflowTest(unittest.TestCase):
    def publish(self, *, exists=False, draft=False, assets=(), forbidden=False,
                upload_failure=False, latest='v0.1.30'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gh').write_text(FAKE_GH)
            (root / 'gh').chmod(0o755)
            state = root / 'state.json'
            state.write_text(json.dumps(dict(exists=exists, draft=draft, assets=list(assets),
                forbidden=forbidden, upload_failure=upload_failure, calls=[])))
            asset_dir = root / 'release-assets'
            asset_dir.mkdir()
            for name in ('yame-0.1.30.zip', 'yame-0.1.30-linux-arm64.zip'):
                (asset_dir / name).write_bytes(b'tested archive')
            env = dict(os.environ, PATH=f"{root}:{os.environ['PATH']}", STATE=str(state),
                GH_TOKEN='fake-token', TAG='v0.1.30', SHA='abc123', LATEST_TAG=latest,
                ASSET_NAME='yame-0.1.30.zip', RUNNER_TEMP=str(root), GITHUB_REPOSITORY='test/yame')
            result = subprocess.run(['bash', '-c', PUBLISH], env=env, capture_output=True, text=True)
            return result, json.loads(state.read_text())

    def test_fresh_release_uploads_both_assets_before_publication(self):
        result, state = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(state['assets']), 2)
        self.assertFalse(state['draft'])
        edit = next(c for c in state['calls'] if c[:2] == ['release', 'edit'])
        self.assertIn('--latest=true', edit)
        self.assertIn('--verify-tag', next(c for c in state['calls'] if c[:2] == ['release', 'create']))

    def test_partial_draft_recovers_only_missing_arm64_asset(self):
        result, state = self.publish(exists=True, draft=True, assets=['yame-0.1.30.zip'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([c for c in state['calls'] if c[:2] == ['release', 'upload']]), 1)
        self.assertFalse(state['draft'])

    def test_healthy_release_is_untouched(self):
        result, state = self.publish(exists=True, assets=['yame-0.1.30.zip', 'yame-0.1.30-linux-arm64.zip'])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any(c[1] in ('create', 'upload', 'edit') for c in state['calls'] if c[0] == 'release'))

    def test_api_error_is_preserved_without_attempting_creation(self):
        result, state = self.publish(forbidden=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('HTTP 403', result.stderr)
        self.assertEqual(len(state['calls']), 1)

    def test_failed_upload_leaves_recoverable_draft(self):
        result, state = self.publish(upload_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(state['draft'])
        self.assertFalse(any(c[:2] == ['release', 'edit'] for c in state['calls']))

    def test_old_recovery_does_not_become_latest(self):
        result, state = self.publish(latest='v0.1.31')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('--latest=false', next(c for c in state['calls'] if c[:2] == ['release', 'edit']))

    def test_build_has_no_release_secret_and_publisher_executes_no_source(self):
        jobs = WORKFLOW['jobs']
        self.assertNotIn('RELEASE_TOKEN', json.dumps(jobs['build']))
        self.assertEqual(WORKFLOW['permissions']['contents'], 'read')
        self.assertNotIn('gradlew', json.dumps(jobs['publish']))
        self.assertFalse(any('checkout' in s.get('uses', '') for s in jobs['publish']['steps']))
        for job in ('plan', 'build'):
            checkout = next(s for s in jobs[job]['steps'] if 'checkout' in s.get('uses', ''))
            self.assertFalse(checkout['with']['persist-credentials'])

    def test_all_shell_steps_parse(self):
        for name, job in WORKFLOW['jobs'].items():
            for step in job['steps']:
                if 'run' in step:
                    result = subprocess.run(['bash', '-n'], input=step['run'], text=True, capture_output=True)
                    self.assertEqual(result.returncode, 0, f"{name}: {result.stderr}")


if __name__ == '__main__':
    unittest.main()
