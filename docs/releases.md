# Automatic releases

Each merged PR on `main` receives a SemVer tag in first-parent history order.
The default bump is patch; `release:minor` and `release:major` select larger bumps.
After a failed run, the next push or manual **Release** workflow run catches up
on unversioned merged PRs and recovers reserved tags with missing releases,
drafts, or missing distribution assets. A backlog therefore intentionally
produces several tags. Existing tags are reused, never moved or deleted.

## Authentication

Create a repository Actions secret named `RELEASE_TOKEN` containing a PAT for
this repository. A fine-grained PAT needs **Contents: read and write**,
**Workflows: read and write**, and **Pull requests: read**. A classic PAT needs
`repo` and `workflow`. Workflow permission is needed for tags/releases pointing
to commits that change workflow files; `contents: write` on `GITHUB_TOKEN` alone
is insufficient for that operation.

The workflow separates three jobs:

- **Plan:** inspect release state and reserve version tags using the PAT.
- **Build:** check out each reserved SHA, run tests, build ZIPs, and smoke-test
  the CLI on native ARM64. This job has read-only `GITHUB_TOKEN` permissions and
  no PAT. Checkout credentials are not persisted.
- **Publish:** download tested ZIP artifacts on a fresh runner, then create a
  draft, upload missing assets, and publish using the PAT. This job never checks
  out or executes repository code, release assets, or Gradle caches.

Runs are serialized. API/authentication errors fail visibly instead of being
reported as missing releases. Missing ARM64 ZIPs are recoverable independently
of the generic JVM ZIP. Only the highest reserved SemVer is marked latest;
recovering an older draft does not replace the latest release.

Run the workflow regression tests with:

```sh
python3 -m pip install PyYAML
python3 -m unittest discover -s tests -p 'test_release_workflow.py'
```
