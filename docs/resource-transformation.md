# Resource transformation invariants

YAME's HTTP compatibility layer may transform upstream resources before sending them to a vintage client. The rules below document the invariants that keep routing, caching, validators and transformed representations consistent.

## Source and transformed representations are distinct

A fetched resource owns an immutable source representation: upstream status, headers and bytes as received after ordinary HTTP decoding. Transformations produce a separate client-facing representation.

Transformers must never mutate or replace the source representation. This lets later requests, cache validation and future transformation profiles reason about upstream state independently from the bytes emitted to Netscape.

## Upstream validators describe source state

`ETag` and `Last-Modified` remain upstream/source validators even when YAME emits a transformed representation. YAME deliberately preserves them through deterministic transforms.

Within one YAME process the transformation pipeline is treated as stable. Therefore, if an upstream resource reports the same validator (or returns `304 Not Modified`), YAME assumes the same source representation and consequently the same transformed representation for the same transformation profile.

Live transformer controls are an exception to the stable-profile assumption:
the TUI's HTTP handler marks HTML responses `Cache-Control: no-store` and omits
their source validators. It also suppresses upstream conditional GET headers
while live controls are active, so browser-cached HTML from a previous switch
combination cannot be reused through a `304` response. Static handlers retain
the existing source-validator behavior.

Byte-specific metadata is different. `Content-Length`, content digests and encodings that no longer describe the emitted representation must be removed or regenerated after transformation.

## Cached 304 responses reuse transformed output

When a conditional fetch returns `304`, the source cache entry supplies the previously fetched body and the transformation cache may supply the previously generated client representation. YAME does not send a bodyless 304 upstream response through the ordinary body transform path as if it were a new representation.

## Resource graph lifetime and ownership

Resource graph knowledge is owned above the PPP session boundary and injected
into each HTTP compatibility proxy instance. It therefore lives for the lifetime
of the YAME process, not for the lifetime of a TCP connection, PPP generation,
browser process, cookie session, or individual HTTP transaction.

Resources are grouped under a graph rooted at their legacy host and effective
port. The graph is created on the first request for that host and port, and
revisiting any resource there reuses it while preserving the relationships YAME
has already discovered. Opening or closing Netscape, creating new TCP
connections, or reconnecting PPP must therefore not discard resource knowledge.

Resource graph knowledge is process-scoped but bounded. The registry retains a
large working set of host resource graphs (512 by default) and uses least-recently-used
eviction when that global limit is reached. Reusing a root or one of its known
resources refreshes its recency. Each host graph has a separate hard node bound
(`maxNodesPerHost`, 4096 by default) so observability/history can retain ordinary
direct requests as well as discovered dependencies without allowing unbounded
growth. Prefetch scheduling and other speculative work should use their own,
stricter resource/concurrency/byte budgets rather than relying on this history
bound. Edge limits remain independently bounded per graph.

Eviction is a capacity policy, not a browser/TCP/PPP lifetime boundary: closing
Netscape, opening new TCP flows, or reconnecting PPP never clears the registry.

## Upstream acquisition and legacy delivery are separate phases

YAME fully acquires an eligible finite upstream response before compatibility transformation and client delivery. This separates modern upstream HTTP/TLS behavior from the slow HTTP/1.0 response sent across serial PPP.

The client-visible response must have finite framing appropriate for Netscape-class clients: no chunked transfer encoding, a correct `Content-Length`, and `Connection: close` where required by the compatibility boundary.

## Partial responses are not full representations

`206 Partial Content` bodies are byte ranges, not complete source representations. Ordinary full-body transforms and transformed-representation caching must bypass them unless an explicitly range-aware transform is implemented.

## Cache identity includes the transformation profile

A transformed representation is reusable only for an equivalent upstream source and transformation profile. The profile captures transformation semantics/configuration that affect client-visible bytes.

Source validators may survive deterministic transformation; transformed bytes and byte-specific metadata must not be confused with source identity.

## Cache and in-flight work invariants

Resource caching and in-flight coalescing have different identity requirements and should remain separate concepts.

- Cache identity answers which client-ready representation may be reused for a resource/profile.
- In-flight identity answers whether two concurrent operations are processing the same effective source/request state.
- Fingerprints must include all semantically relevant status/header/body or request state and must normalize names with locale-independent rules.
- Header ordering or equivalent input ordering must not create accidental fingerprint differences.
- Cached/shared values are immutable snapshots. Mutable byte arrays, maps, and header lists must be defensively copied at API boundaries.
- A producer must publish the cacheable result before completing shared in-flight work. Otherwise a new request can fall into the gap between in-flight completion and cache insertion and duplicate the work.
- Waiters must not redundantly republish the same cache entry.

## Pipeline-state invariant

Transformer applicability and cacheability are evaluated against the representation as it evolves through the pipeline. Do not make a separate preflight decision against only the original representation when later transforms may change headers, bytes, or transformer applicability.

Bodyless responses may still participate in resource-level transforms when header/status-based transforms are valid. Conversely, partial-body responses such as `206 Partial Content` must bypass ordinary full-representation transformation/cache handling unless explicit range-aware semantics are implemented.

## HTML cleanup and element rules

The structural stages run in this order: `script-remover`, `style-remover`,
`attribute-cleaner`, then `element-simplifier`. Attribute cleanup removes
upstream inline styles before element rules add explicitly configured styles.
URL rewriting and image optimization run afterward.

`element-simplifier.ini` is loaded alongside `yame.ini` into an immutable rule
snapshot at startup, then passed through the HTTP configuration into the
pipeline. Neither request handling nor transformation reads the file. Restarting
YAME applies edits and creates fresh representation caches, preserving the
stable-pipeline assumption for source validators and cached transformed output.
The bundled INI file is the single source of default mappings. See the README
for the format and editing behavior.

Live on/off settings are separate from the immutable INI rules. A shared
runtime control survives PPP/TCP proxy creation, and each HTTP request captures
one immutable settings snapshot before fetching upstream. Its transformation
profile and transformed-cache/in-flight-work keys include the HTML switch
values and PNG compatibility mode; the same source bytes under different
settings never share transformed output. Source acquisition may still be
coalesced because it is independent of the transformation profile. Already-running
requests retain their snapshot.

Mappings affect only explicitly configured tags, including matching closing
tags. Generated style values are escaped as attributes. Expansion is limited
by `maxResponseBytes`; an oversized result leaves the representation from the
preceding stage intact. These transforms preserve the upstream source and
operate only on complete identity-encoded `200 text/html` representations.

## Image dimensions

The HTML compatibility stage applies the same proportional sizing policy as
JPEG/PNG optimization to literal `<img>` tags that contain numeric pixel
`width` and `height` attributes. The default maximum is `600x400`, so the
result stays within a `640x480` legacy display while preserving aspect ratio.
Tags with missing dimensions or non-pixel values are left unchanged. The
transformer does not inspect or fetch the referenced image; image-body
optimization is an independent later step.

## Untrusted binary input

Image and other binary transformers process untrusted Internet data. They must therefore make expensive work deliberate and bounded:

- enforce encoded-size and decoded-work limits before full decode where possible;
- inspect dimensions/metadata cheaply before materializing large decoded buffers;
- disable unnecessary metadata parsing and backward seeking when the decoder API supports it;
- avoid implicit filesystem/temp-file caches when an explicit bounded in-memory stream is available;
- keep failure behavior safe: malformed/unsupported data should normally remain untransformed rather than destabilizing the proxy;
- optimization transforms should not replace the source when the candidate representation is larger unless compatibility requires transcoding regardless of size.

Tests should cover the boundary/negative paths explicitly, not only the happy path.

## PNG output and transparency

`PngResourceTransformer` handles `image/png`, independently of
`JpegResourceTransformer`, which handles JPEG. Both are registered as separate
image transformation stages and share `LegacyImageCodec` for in-memory ImageIO decoding
and resizing. They use the same `600x400` canvas, never upscale, and enforce a
16-million decoded-pixel limit and a 48 MiB estimated decoded-raster limit.
Before full decode, the image reader's default output sample layout (element
size and elements per pixel) estimates raster memory; images exceeding either
limit pass through. This bounds the expected main raster allocation, not every
temporary decoder buffer or aggregate memory across simultaneous requests. The
encoded-input limit is `maxResponseBytes` (16 MiB by default for the proxy and
32 MiB for standalone image policies); compatibility mode applies the same
limit to encoded output. Output uses the source/transformed representation
cache and preserves upstream source validators. JPEG optimization runs before
PNG conversion so PNG-to-JPEG candidates are not recompressed.

Changing format updates `Content-Type` and removes source byte lengths and
digests. The HTTP boundary calculates `Content-Length` from the client-visible
body and sends finite HTTP/1.0 framing.

PNG mode is a live setting persisted in `yame.ini` as
`transformers.png-compatibility.mode`. Its values are `optimize` (the default)
and `require-legacy-format`. Every request takes one settings snapshot, and the
transformation profile includes the PNG mode so cached output from one mode is
not reused in the other.

In `optimize` mode, every replacement must be strictly smaller than its source.
Exact palettes become non-interlaced GIF without quantization. Binary alpha
uses a transparent palette slot, allowing up to 255 opaque colors. Palette and
binary-transparent images use nearest-neighbor reduction; opaque high-color
images use bilinear reduction. Opaque high-color PNGs may become non-progressive
baseline JPEG at quality 0.55 only when a round-trip check passes: channel RMS
error at most 12 and at most 1% of pixels with any channel error over 48.
Partial alpha and images with overfull transparent palettes remain PNG in this
mode. It never guesses an arbitrary page background.

`require-legacy-format` prioritizes the client's supported formats over output
size and the optimizer's JPEG fidelity guard. Static, safely decoded PNGs use
these deterministic choices:

- An exact palette fitting GIF becomes GIF. Binary transparency is preserved
  exactly when the colors fit.
- Binary-transparent images that exceed GIF's 256-entry palette are quantized
  to a fixed 6x7x6 (252-color) RGB palette plus one transparent entry. Fully
  transparent pixels do not consume a color slot.
- Partial-alpha images are composited against white. The result becomes an
  exact GIF if it has at most 256 colors; otherwise it uses the same fixed GIF
  palette. Alpha is not retained after compositing.
- Opaque high-color images become baseline JPEG at quality 0.55. Unlike optimize
  mode, a failed fidelity guard does not send the source PNG back to a client
  that cannot decode it.
- Resizing preserves aspect ratio within the shared canvas and never enlarges.

Malformed or truncated PNGs, APNG, non-identity content encodings, decoder
failures, and inputs or outputs beyond byte, pixel or raster limits remain
unchanged. YAME logs a reason when the requested compatibility conversion cannot
be performed, so passthrough is visible rather than reported as success. The
stock PNG decoder would otherwise silently discard APNG animation. Upstream
`ETag` and `Last-Modified` remain source validators; byte-specific digests and
lengths do not survive a changed body. Tests generate deterministic fixtures
and verify output format, pixels/alpha, limits, headers, HTTP framing and cache
separation without external image downloads.

## Visual image transformation examples

The deterministic image transformer tests also generate an offline, side-by-side visual
gallery from synthetic PNG/JPEG fixtures using the **production** resource transformation
pipeline. It demonstrates a binary-transparent logo converted to GIF, exact-palette
artwork, PNG-to-JPEG, JPEG recompression, and a partially transparent PNG deliberately
left unchanged. Original and resulting files are shown against checkerboard and dark
backgrounds, alongside their dimensions, encoded sizes, and percentage saved.

Run `./gradlew test --tests '*ImageTransformationVisualReportTest'` to generate
`build/reports/image-transformations/index.html` with its referenced image files.
The pull-request CI uploads this directory as the
`yame-image-transformations` artifact, linked from the build job summary.
Download and extract the full archive, then open `index.html` locally.

These fixtures are repeatable behavioral examples, **not** a representative corpus
of real website logos/photos. Visual checks in Netscape 4.08 are still useful,
especially for browser-specific GIF transparency rendering.
