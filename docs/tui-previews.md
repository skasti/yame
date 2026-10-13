# TUI previews in pull requests

The JVM test suite renders the production `YameDashboardRenderer` into ten
styled Mordant ANSI fixtures under `build/reports/tui-frames/`. The read-only CI job stores each ANSI fixture as a diffable JSON-escaped
`*.ansi.txt` snapshot, and feeds changed frames into **Termless + headless
xterm.js** to render PNG with resvg into `build/reports/tui-screenshots/`.
Unlike the previous Java AWT
implementation, ANSI escape sequences are interpreted by a real VT terminal
emulator before rasterization. The PNG renderer uses **JetBrainsMono Nerd Font
Mono** at 14 px, installed from the checksum-pinned Nerd Fonts v3.5.1 release.
The terminal cell width and height are measured from the actual font's fixed
glyph advance and hhea line metrics, rounded up to whole pixels; they are not
hardcoded to the previous DejaVu 9×18 dimensions. These are snapshots of the
production dashboard renderer, not captures of a running serial modem or Pi.

The tested terminal sizes (rows × columns) are **30×100**, **45×135**
and **50×150**. Both compact layouts include startup, Proxy, DNS and Transfers
views; the 150-column wide layout includes startup and the combined active
dashboard. The wide-layout threshold is 150 columns (149 remains compact).
Synthetic traffic and elapsed time zero keep fixtures stable.
The dashboard's production header still shows the real Git commit. In screenshot
fixtures only, the revision inside `v<version> (<commit>)` is replaced with
same-length zeroes before saving ANSI frames, so GitHub's per-PR synthetic
merge commit does not make otherwise identical preview output appear changed.
The release version remains intact, and ANSI styling and terminal cell layout
are unaffected.

Regenerate locally after installing Node.js 24 and the test-only npm packages:

```bash
./gradlew test --tests '*DashboardScreenshotTest'
npm install --ignore-scripts --no-audit --no-fund --package-lock=false
# First install JetBrainsMono Nerd Font Mono v3.5.1 (Regular/Bold/Italic/Bold Italic),
# as done by .github/workflows/ci.yml (checksum-verified archive).
npm run render:tui-previews
```

Each `assets/screenshots/<size>-<panel>.ansi.txt` contains one JSON-escaped
terminal row per line, retaining ANSI styles while avoiding raw ESC control
characters that would make GitHub diffs hard to read. The generated text snapshots
are committed next to the PNGs, so TUI changes are reviewable as text diffs.

Before rendering, CI checks out the PR's exact immutable base SHA in a separate
read-only directory and compares each generated ANSI snapshot and `render-info.json`
renderer identity against **that base's** files—not editable PNGs or metadata
from the PR checkout. This also prevents a PR from bypassing the cache guard by
modifying an image and its matching digest together. The identity covers renderer
source, the resolved installed npm dependency tree (including transitive versions),
Node major version, measured terminal cell dimensions, and checksums for
regular, bold, italic, and bold-italic font faces. If npm dependencies or any font face cannot be
resolved, cache reuse is disabled. The same file also records the SHA-256
checksum of each PNG. CI copies a checked-in PNG without starting xterm.js
only when its ANSI input, renderer identity, and actual PNG bytes all match
their recorded values. If the font cannot be identified
reliably, the cache is disabled. Set `YAME_FORCE_TUI_RENDER=1` to force rendering.
Changed or tampered PNG bytes force rendering, and the privileged publisher
checks every output PNG against the SHA-256 digest in the generated metadata
before creating Git objects. Changed input is rendered, but a pixel-for-pixel
comparison still reuses the old PNG bytes if the image itself did not change (including compression/metadata
differences). The first regular PR after adoption bootstraps the diffable text snapshots and
renderer identity. Before those files are merged into `main`, Dependabot
cannot bootstrap snapshots. Once the baseline exists, dependency PRs get
the same result comparison and publication behavior as all other PRs.
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
checks out or executes PR code, and never publishes into fork branches.
Dependabot PRs keep their read-only CI artifacts. The publisher skips a
Dependabot PR only if the immutable base does not yet contain the complete
snapshot baseline; once bootstrapped, Mordant and other dependency upgrades
can publish actual ANSI or rendered pixel changes. It checks the live PR head
and base, downloads the artifact from that exact CI run,
validates a ZIP of at most 5 MiB before extraction, and permits exactly ten
PNGs, ten ANSI text snapshots, a renderer identity JSON, and a provenance JSON
file. Each PNG is bounded to 1 MiB and validated against the cell dimensions stored
in the bounded renderer metadata, each text snapshot to 256 KiB, and each
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

Publication compares the **generated ANSI and PNG files** to the immutable
base commit, using their Git blob hashes. The unprivileged renderer already
reuses the checked-in PNG bytes whenever pixels match, so compression-only
changes do not count as visual changes. A change solely to `render-info.json`
(for example a renderer dependency upgrade that produces identical output)
does not cause a generated commit or update a PR comment. The publisher retains
the base's metadata until a genuine PNG/ANSI change is published, when it
refreshes the metadata as well. This can cost an extra render on later runs but
prevents pointless snapshot churn. If the PR has manually modified generated
snapshots with no corresponding output change, the publisher restores those
files from the trusted base instead of accepting a self-authored diff.

For a changed preview, the job updates one bot-owned PR comment with immutable
image URLs and links to the corresponding diffable ANSI inputs. The workflow records the successful source
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
