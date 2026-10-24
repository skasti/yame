# ADR 0007: Lazy global event broker

- Status: Accepted
- Decision date: 2026-10-24
- Decision owner: repository maintainer
- Related work: [#100](https://github.com/skasti/yame/issues/100),
  [#102](https://github.com/skasti/yame/issues/102),
  [PR #116](https://github.com/skasti/yame/pull/116)
- Compatible with [ADR 0004](0004-extensible-event-contract.md) and
  [ADR 0006](0006-consumer-owned-event-ordering.md)

## Context

The original broker facade required explicit installation before first use.
That made ordinary producers accept publication callbacks solely to avoid
depending on application composition. The proxy resource registry could then
emit nothing when no callback was supplied. YAME has one process-wide observation
stream, so this ceremony made publication easy to omit without adding useful
isolation. Standalone `EventBroker` instances still serve isolated tests.

## Decision

- `YameEvents` is the normal process-wide publication and subscription entry
  point. Its first `current`, `publish` or `subscribe` access creates one default
  broker. A single synchronized boundary chooses between a default broker and
  an explicitly installed broker. Concurrent first callers share the winner;
  a losing lazy path creates no extra worker. Publication is never a no-op.
- An explicit `install(broker)` before first use selects that broker. Installing
  over an active broker, including a lazily created default, is rejected. A
  rejected distinct candidate is closed so its dispatcher cannot leak.
- The facade owns its default broker. `shutdown()` seals the facade, closes the
  default and leaves it available to its owner for `awaitClosed`. A JVM shutdown
  hook also closes and briefly awaits a default if application shutdown has not
  yet done so. An explicitly installed broker belongs to its installer: the
  installer closes and awaits it before releasing the installation handle.
  Release allows another explicit installation for serial isolated tests, but
  never enables lazy recreation. Final `shutdown()` rejects further access and
  installation. Publication racing shutdown either reaches the selected broker
  before close or fails explicitly; it cannot start a replacement worker.
- Production composition in #102 must stop producers, supervise fatal overload,
  drain and close the selected broker, then call `YameEvents.shutdown()` at final
  shutdown. This ADR does not implement that wider runtime migration. Existing
  queue saturation and failure propagation from [ADR 0001](0001-fatal-event-queue-saturation.md)
  remain unchanged.
- Subscriptions begin with events accepted after registration. No replay or
  broker-owned current-state store is added. A late consumer obtains current or
  historical state by querying the authoritative domain component through an
  appropriate API, or it subscribes before the relevant publications. A query
  followed by subscription is not automatically an atomic snapshot protocol.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Require explicit installation before all use | Forces producers to carry optional callbacks and permits silent publication omissions. This was the original v1 approach; its rationale was to keep worker ownership explicit. |
| Replace an active broker when a test installs another | Loses existing subscribers or queued events and creates ambiguous ownership. |
| One broker per registry or consumer | Splits the process observation stream and complicates startup and supervision. |
| Replay or bootstrap state in the broker | Makes transport responsible for domain-specific current state and ordering. |
| Reset the process facade for tests | Permits accidental replacement and hides lifetime bugs; tests use isolated brokers or serialized, forked facade tests. |

## Consequences

Ordinary producers can publish directly without application-specific callbacks.
The first access starts a daemon dispatcher; the facade owns and closes that
default, including on JVM termination. Early explicit installation remains
possible, while a late installation fails visibly. Test-installed brokers must
be closed and awaited before their handles are released, and tests using the
process facade must serialize or run in separate JVMs. The broker still carries
only transient event delivery, with no replay or state projection.
