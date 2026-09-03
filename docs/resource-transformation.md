# Resource transformation invariants

YAME materializes upstream HTTP responses as a `Resource` before running the compatibility transformation pipeline. The source representation is retained separately from the client-visible transformed representation.

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
