# ADR 0005: PNG legacy-format compatibility mode

- Status: Accepted
- Decision date: 2026-10-20
- Decision owner: repository maintainer
- Related work: [#107](https://github.com/skasti/yame/issues/107),
  [#21](https://github.com/skasti/yame/issues/21),
  [#37](https://github.com/skasti/yame/issues/37), [PR #64](https://github.com/skasti/yame/pull/64)

## Context

The existing PNG transformer is a bandwidth optimizer. Its size and fidelity
gates can keep a valid PNG as `image/png`, which is not usable by every legacy
browser. A compatibility request needs a separate policy that can prefer a
decodable legacy format even when the replacement is larger, while retaining
bounded processing and the established default behavior.

## Decision

- Expose `optimize` and `require-legacy-format` through the transformer-owned
  `png-compatibility-mode` setting in the `[transformers]` section of
  `yame.ini`. Keep `optimize` as the default.
- `optimize` retains the strictly-smaller rule, exact GIF palette behavior and
  JPEG fidelity guard.
- `require-legacy-format` converts decodable static PNGs without requiring a
  smaller result. Exact GIF palettes remain lossless. Opaque high-color images
  use baseline JPEG at the existing quality even if the optimizer's fidelity
  guard rejects it.
- Preserve binary transparency with GIF when the exact palette fits. If it
  does not, use a fixed 6x7x6 RGB palette and a separate transparent entry.
  Composite partial alpha onto white; encode an exact GIF when its colors fit,
  otherwise use that fixed palette. If GIF encoding fails, try baseline JPEG
  after compositing transparency onto white.
- Keep the encoded-input, pixel and raster limits. Bound compatibility output
  bytes by the same encoded-byte limit. Malformed, animated, encoded,
  over-limit and decoder/encoder-failure cases retain the PNG and log why the
  conversion was skipped.
- Build one immutable transformation-settings value at the start of each HTTP
  request from feature-owned keys read through the installed global `ConfigStore` facade (`ConfigStore[key]`). Production code does not inject or retain a store reference.
  `ConfigStore` intentionally has no atomic multi-key snapshot API, so a
  concurrent commit may land between reads. Include the captured mode in the
  transformation profile/cache key. Preserve upstream source validators and
  remove byte-specific source digests and lengths after conversion.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Make forced conversion the default | Would change existing bandwidth behavior for every client. |
| Keep partial alpha as PNG | Does not meet compatibility mode's purpose for clients that cannot decode PNG. |
| Guess the page background | YAME has no reliable page-background context for an image response. |
| Allow unbounded decoder or output work | Exposes the proxy to excessive memory and CPU use from untrusted responses. |

## Consequences and verification

Compatibility mode may increase transfer size, flatten transparency against
white, quantize artwork, or use a lossy JPEG. The default remains size-first.
When safe conversion cannot be produced, the source PNG remains intact and the
diagnostic records the reason. Tests cover deterministic conversion, fallback,
configuration reload, HTTP metadata and mode-specific cached representations.
