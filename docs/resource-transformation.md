# Resource transformation invariants

YAME materializes upstream HTTP responses as a `Resource` before running the compatibility transformation pipeline. The source representation is retained separately from the client-visible transformed representation.

## HTTP transport boundary

Compatibility logic does not speak TCP directly.

The intended layering is:

```text
PPP/TCP
   |
   v
HttpTcpProxy
   |  decodes request bytes
   |  encodes response bytes
   v
HttpRequestHandler
   |
   v
compatibility routing / resource manager
   |
   v
upstream fetch + source resource + transforms
```

`HttpTcpProxy` owns connection-level HTTP transport concerns such as incremental
request decoding, `Expect: 100-continue`, HTTP/1.0 response framing,
`Connection: close`, and downstream read backpressure.

The compatibility handler receives a complete `HttpRequest` and returns a complete
`HttpResponse`. It must not consume or emit `TcpProxyEvent` values directly.
That keeps TCP flow control and eventual downstream scheduling separate from
resource acquisition and transformation.

## Resource graph lifetime

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
resources refreshes its recency. Per-graph node and edge limits still bound
discovery within each graph.

Eviction is a capacity policy, not a browser/TCP/PPP lifetime boundary: closing
Netscape, opening new TCP flows, or reconnecting PPP never clears the registry.

## Upstream acquisition and legacy delivery are separate phases

The compatibility proxy must fully acquire the finite upstream HTTP response before it emits the corresponding response to the legacy client.

The intended boundary is:

```text
modern upstream
    |
    v
complete source representation
    |
    v
transformation / discovery / prefetch
    |
    v
complete client representation
    |
    v
TCP / PPP / serial delivery
    |
    v
legacy client
```

This is an architectural invariant, not an implementation detail. Backpressure from the legacy TCP peer, PPP framing, or the serial link must never determine how quickly the upstream HTTP body is consumed.

Accordingly:

- compatibility responses are represented as owned, buffered bytes before delivery;
- a resource reaches `SOURCE_READY` only after the complete upstream response has been acquired;
- it reaches `TRANSFORMING` while the complete source representation is being processed;
- it reaches `READY` once the complete client representation is available locally, **before** the first response byte is emitted toward the legacy client;
- downstream delivery failures do not invalidate an already-`READY` resource;
- reintroducing a streaming upstream-response body into the compatibility path requires an explicit architecture change, because it would couple modern upstream I/O to the slow legacy boundary.

## Deterministic transformations

For the lifetime of a YAME process, the configured resource transformation pipeline is effectively static. A transformer may inspect source bytes, status, headers, request context, graph metadata, and the configured transformation profile, but the set and implementation of transformers do not mutate while the process is running.

The resource layer therefore relies on this invariant:

```text
same upstream source state
+ same running YAME transformation pipeline
= same client-visible transformed representation
```

This is important for HTTP validators.

### ETag and Last-Modified

Upstream `ETag` and `Last-Modified` are intentionally preserved when a deterministic transform rewrites the body.

They validate the upstream source state. If upstream returns the same validator, or answers a conditional request with `304 Not Modified`, the source representation is unchanged. Given the invariant above, the transformed representation exposed to the legacy client is unchanged as well.

YAME therefore treats these validators as source-state validators that remain valid for the corresponding deterministic transformed representation.

This is a deliberate compatibility/cache contract, not an accidental forwarding of upstream metadata.

### Representation-byte metadata

Headers that describe the concrete payload bytes rather than source state must not be forwarded unchanged after a body transform. Examples include:

- `Content-Length`
- `Content-MD5`
- `Digest`, `Content-Digest`, and `Repr-Digest`
- `Content-Range`
- `Accept-Ranges`

Transformers are responsible for removing or regenerating such metadata when they replace the representation body.

## Changing transformation behavior

If transformation behavior changes between YAME versions or configurations, cached transformed resources must not be reused across incompatible transformation profiles. The transformation profile is therefore part of transformed/cache identity.

Within one running process, however, the pipeline is assumed stable. Code that introduces runtime mutation of the transformer set or semantics must revisit this validator invariant explicitly.


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

## Untrusted binary input

Image and other binary transformers process untrusted Internet data. They must therefore make expensive work deliberate and bounded:

- enforce encoded-size and decoded-work limits before full decode where possible;
- inspect dimensions/metadata cheaply before materializing large decoded buffers;
- disable unnecessary metadata parsing and backward seeking when the decoder API supports it;
- avoid implicit filesystem/temp-file caches when an explicit bounded in-memory stream is available;
- keep failure behavior safe: malformed/unsupported data should normally remain untransformed rather than destabilizing the proxy;
- optimization transforms should not replace the source when the candidate representation is larger unless compatibility requires transcoding regardless of size.

Tests should cover the boundary/negative paths explicitly, not only the happy path.
