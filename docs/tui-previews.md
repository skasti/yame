# TUI previews in pull requests

The JVM test suite renders the production `YameDashboardRenderer` into ten
styled Mordant ANSI fixtures under `build/reports/tui-frames/`. The read-only CI job stores each ANSI fixture as a diffable JSON-escaped
`*.ansi.txt` snapshot, and feeds changed frames into **Termless + headless
xterm.js** to render PNG with resvg into `build/reports/tui-screenshots/`.
Unlike the previous Java AWT
implementation, ANSI escape sequences are interpreted by a real VT terminal
emulator before rasterization. The PNG renderer uses DejaVu Sans Mono, 9×18
pixel cells and a fixed font install in CI. These are snapshots of the
production dashboard renderer, not captures of a running serial modem or Pi.

The tested terminal sizes (rows × columns) are **30×100**, **45×135**
and **50×150**. Both compact layouts include startup, Proxy, DNS and Transfers
views; the 150-column wide layout includes startup and the combined active
dashboard. The wide-layout threshold is 150 columns (149 remains compact).
Synthetic traffic and elapsed time zero keep fixtures stable.

Regenerate locally after installing Node.js 24 and the test-only npm packages:

```bash
./gradlew test --tests '*DashboardScreenshotTest'
npm install --ignore-scripts --no-audit --no-fund --package-lock=false
npm run render:tui-previews
```

Each `assets/screenshots/<size>-<panel>.ansi.txt` contains one JSON-escaped
terminal row per line, retaining ANSI styles while avoiding raw ESC control
characters that would make GitHub diffs hard to read. The generated text snapshots
are committed next to the PNGs, so TUI changes are reviewable as text diffs.

Before rendering, CI compares each ANSI snapshot and the `render-info.json`
renderer identity against the checked-in versions. The identity covers renderer
source, package dependencies, Node major version, terminal options, and the
actual selected font file checksum. The same file also records the SHA-256
checksum of each PNG. CI copies a checked-in PNG without starting xterm.js
only when its ANSI input, renderer identity, and actual PNG bytes all match
their recorded values. If the font cannot be identified
reliably, the cache is disabled. Set `YAME_FORCE_TUI_RENDER=1` to force rendering.
Changed or tampered PNG bytes force rendering, and the privileged publisher
checks every output PNG against the SHA-256 digest in the generated metadata
before creating Git objects. Changed input is rendered, but a pixel-for-pixel
comparison still reuses the old PNG bytes if the image itself did not change (including compression/metadata
differences). The first PR after adoption may bootstrap the new text snapshots
and renderer identity even if its own change is unrelated to the TUI.
The privileged publisher validates bounded text snapshots and metadata, but
keeps PNGs opaque and never decodes untrusted image content.

CI uploads `yame-tui-screenshots-v3`. The older default-branch publisher ignores
this new artifact while the migration PR is open; after merge the publisher
recognizes v3 and publishes both PNG and text snapshots. The source
commit/version in the header helps identify stale previews.

## Publication and activation

`Publish TUI previews` uses `workflow_run` after successful pull-request CI.
GitHub activates this workflow only once it exists on the default branch.
Consequently, the introducing PR gets artifacts; automatic commits/comments
start after this feature has been merged into `main`. If repository policy disables Actions write access, publication fails visibly;
normal read-only CI and its artifacts still work.

The publishing job executes only scripts from the default branch. It never
checks out or executes PR code, and never publishes into fork branches. It
checks the live PR head and base, downloads the artifact from that exact CI run,
validates a ZIP of at most 5 MiB before extraction, and permits exactly ten
PNGs, ten ANSI text snapshots, a renderer identity JSON, and a provenance JSON
file. Each PNG is bounded to 1 MiB, each text snapshot to 256 KiB, and each
JSON file to 4 KiB before reading. Extraction rejects extra paths, duplicate names and symlinks
and enforces both declared and actual output limits. It then checks PNG
signatures and dimensions, canonical JSON-escaped ANSI rows and renderer
identity structure, then creates a single-parent commit touching only
approved files under `assets/screenshots/`.
A non-forced ref update refuses concurrent changes to the branch. If post-push
validation, status publication or comment listing/creation/update fails, the publisher restores the source head
with Git's explicit `--force-with-lease=<ref>:<screenshot-sha>`. This atomic
condition prevents rollback from overwriting a concurrent developer push.
Rollback failures surface as job failures; no unconditional force push is used. PNG files
are treated only as bytes in this privileged job, not decoded or executed.
Only the publishing job has contents/statuses/pull-request write permissions.

The job updates one bot-owned PR comment with immutable image URLs and links
to the corresponding diffable ANSI inputs. If all PNGs, ANSI snapshots and the
renderer identity are unchanged it makes no commit. The workflow records the successful source
run, PR number and base SHA in the screenshot commit message and verifies the new head again before reporting a successful `build-and-test`
commit status through the GitHub API. This avoids relying on recursive events
from `GITHUB_TOKEN`. A `workflow_dispatch` job check would not satisfy PR rulesets. Fork PRs retain downloadable artifacts.

## Required checks and avoiding rebuilds

Keep the required check named `build-and-test`. Do **not** add workflow-level
`paths-ignore` or `[skip ci]`: the latest screenshot SHA still needs a check.
The gate implementation is checked out from the immutable PR base SHA, never
from the PR tree. If the base does not yet contain the gate (as in this
introducing PR), CI always runs the full suite after validating the immutable
merge parents. Workflow paths with GitHub's `@ref` suffix are accepted.
A cheap gate checks that the new head:

- has exactly one parent and changes only the ten PNGs, ten ANSI text snapshots,
  and renderer identity file under `assets/screenshots/`;
- references a successful **pull_request** run of `.github/workflows/ci.yml`
  whose head SHA is that parent and whose `build-and-test` job succeeded;
- belongs to the same PR and uses the same base SHA as that successful run.

Only then can `build-and-test` finish without running Gradle or DOSBox again.
Release-workflow tests also skip this verified successor. ARM64 skips only if its own parent run also succeeded; otherwise it runs normally. A missing
proof, changed base, ordinary screenshot edit, code edit, failed parent run or
incompatible run forces normal CI. If the gate fails, the required job fails.
Publication accepts only successful **pull_request** runs with a screenshot artifact. A redundant native PR event, if GitHub
creates one, uses the same gate and cannot cause a screenshot loop.

The publisher activates after merge; test it with the next same-repository PR.
Confirm the PNG commit is followed by green `build-and-test`, with a commit status linking to the successful source CI and
without another Gradle/DOSBox build, and that a subsequent code edit triggers the full suite again.
