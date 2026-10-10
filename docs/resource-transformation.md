# Resource transformation invariants

YAME's HTTP compatibility layer may transform upstream resources before sending them to a vintage client. The rules below document the invariants that keep routing, caching, validators and transformed representations consistent.

## Source and transformed representations are distinct

A fetched resource owns an immutable source representation: upstream status, headers and bytes as received after ordinary HTTP decoding. Transformations produce a separate client-facing representation.

Transformers must never mutate or replace the source representation. This lets later requests, cache validation and future transformation profiles reason about upstream state independently from the bytes emitted to Netscape.

## Upstream validators describe source state

`ETag` and `Last-Modified` remain upstream/source validators even when YAME emits a transformed representation. YAME deliberately preserves them through deterministic transforms.

Within one YAME process the transformation pipeline is treated as stable. Therefore, if an upstream resource reports the same validator (or returns `304 Not Modified`), YAME assumes the same source representation and consequently the same transformed representation for the same transformation profile.

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
optimization stages in the resource pipeline and share `LegacyImageCodec` for
in-memory ImageIO decoding and resizing. They use the same `600x400` canvas,
no upscaling and the 16-million decoded-pixel limit. An encoded-input limit is checked before
parsing/decoding: the proxy supplies its existing `maxResponseBytes` budget
(default 16 MiB); standalone image policies default to 32 MiB. Output uses the
existing source/transformed representation cache and preserves source validators.
JPEG optimization runs before PNG optimization so PNG-to-JPEG candidates are
not recompressed by the JPEG stage.

Changing format updates `Content-Type` and removes source byte lengths/digests;
the HTTP boundary calculates the final `Content-Length`.

Netscape Navigator 4.08 can display PNG, but ignores PNG transparency
([PNG: The Definitive Guide, browser support table](https://www.libpng.org/pub/png/book/chapter02.html)).
The deterministic policy is:

- Images fitting an exact 256-entry palette become a non-interlaced GIF. No
  color quantization is applied. Binary alpha uses one palette entry, allowing
  up to 255 opaque colors. RGB values of fully transparent pixels are ignored;
  an opaque black pixel stays distinct from a transparent black pixel.
- Palette artwork and binary-transparent images use nearest-neighbor reduction,
  preserving colors and binary alpha. Opaque high-color images use bilinear
  reduction. All resizing preserves aspect ratio within the shared canvas.
- Opaque high-color PNGs may become non-progressive baseline JPEG at the shared
  JPEG quality (default 0.55). A round-trip comparison against the resized RGB
  image requires channel RMS error at most 12 on the 0–255 scale and at most
  1% of pixels with any channel error above 48. This is a conservative numeric
  guard against compression damage, not a guarantee of perceptual equivalence.
- Partial alpha and binary-transparent images exceeding GIF palette capacity
  pass through unchanged. There is no alpha threshold, lossy palette reduction
  or guessed page-background matte. APNG also passes through; the stock PNG
  decoder would otherwise silently discard its animation.
- Malformed data, a mismatch between declared format and decoder format,
  encoded bodies and images exceeding the resource limits remain unchanged.
- Every candidate must be strictly smaller than the current representation.
  The original headers and bytes remain intact on all fallback paths.

Passthrough is deliberate: partial-alpha images and other unsupported cases
retain Netscape's original PNG transparency limitation. Full-alpha rendering on
an arbitrary page background is outside this policy. Even an exact transparent
GIF is declined if it would increase transfer size. Tests generate deterministic
fixtures and verify format, pixels/alpha, size, limits, HTTP framing and cached
identical-source reuse without relying on external image downloads.
