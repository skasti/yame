# ADR 0004: Extensible event contract

- Status: Accepted
- Decision date: 2026-10-20
- Decision owner: repository maintainer
- Related work: [#92](https://github.com/skasti/yame/issues/92),
  [#98](https://github.com/skasti/yame/issues/98),
  [#100](https://github.com/skasti/yame/issues/100),
  [PR #106](https://github.com/skasti/yame/pull/106)

## Context

A sealed central hierarchy and event-specific broker accounting made every new
observation depend on changes to shared infrastructure. Typed Kotlin consumers
need ordinary properties, while diagnostic and future bridge consumers need to
inspect payloads without knowing every concrete type. Normal publication should
not pay for generic inspection.

## Decision

- `YameEvent` is an open interface. Producers own concrete event types near their
  implementation. No registry or exhaustive broker matching is required.
- `typeName` is a stable, globally unique identity independent of Kotlin class
  names. `VersionNumber(major, minor, patch)` versions each type's schema: major
  for incompatible changes, minor for compatible additions and patch for
  compatible corrections. These are metadata, outside the payload map.
- Every event supports read-only `Map<String, Any?>` payload access as well as its
  strongly typed Kotlin properties. The optional `AbstractYameEvent` discovers
  public properties declared on the concrete class through Kotlin reflection,
  excluding common metadata. It initializes metadata on first map use and caches
  accessors per class. A single key lookup reads only that key's value; iteration
  may read all values. Direct implementations may supply their own map view.
- Published events represent immutable snapshots. Producers defensively copy
  mutable payloads on ownership transfer and return independent copies through
  typed and generic access. The broker treats event instances as opaque values.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Sealed central hierarchy or schema registry | Every new producer type would change shared infrastructure. |
| Hand-written payload maps for each type | Duplicates property declarations and risks drift. |
| Eager reflection or map materialization | Adds work to construction and publication even for typed-only consumers. |
| Code generation or serialization framework | Unnecessary for in-process generic inspection at this stage. |

## Consequences and verification

First generic access to a concrete class pays a reflection cost and requires
`kotlin-reflect` at runtime. Property getters still execute on each access;
mutable types need careful snapshot design. Public implementation properties on
a concrete event would enter its payload unless kept private. Current events are
owned by PPP session and proxy packages. Future event schemas can be added there
without modifying the broker. Contract and concurrency tests in PR #106 exercise
typed/generic access, nullable fields and defensive byte-array copying.
