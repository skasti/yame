# YameEvents broker infrastructure

Implemented in PR #106 in `no.skasti.yame.observer`. **Production runtime still
uses existing event sinks.** Installation, application fatal supervision and
runtime/frontend migration belong to [#102](https://github.com/skasti/yame/issues/102)
and [#101](https://github.com/skasti/yame/issues/101). The
[v1 design](design/yame-events-v1.md) describes the wider migration; the
[historical inventory](.history/yame-events-a37ac65.md) records its starting point.
[ADR 0001](adrs/0001-fatal-event-queue-saturation.md) decides overload handling;
[ADR 0004](adrs/0004-extensible-event-contract.md) decides event ownership and access.

## Event contract

`YameEvent` is an open, independently implementable `Map<String, Any?>` with
`typeName` and `version` metadata. `VersionNumber` has `major`, `minor` and `patch`
components. Existing PPP observations are defined near `PppSession`; HTTP proxy
actions are defined in the proxy package. New types require no broker change.

The optional `AbstractYameEvent` base discovers public properties declared on the
concrete class through Kotlin reflection only on first map access. It caches
accessors by class; a key lookup reads only that property's getter. Iterating
reads the values. Metadata and private implementation fields are excluded. Typed
property access and broker publication do not use reflection. The map is read-only.
`UnhandledPacket` copies its byte array on construction and each typed or map
read. Future mutable payloads must make the same snapshot guarantee.

## Admission and delivery

Each `EventBroker` owns one daemon dispatcher. `YameEvents.current` exposes an
explicitly installed production instance; no hidden worker or test reset exists.
Register consumers before starting producers. `publish(event): Long` returns an
accepted sequence; it never invokes a subscriber, reads the payload or performs
I/O. The short admission lock orders concurrent producers and subscriptions.

The queue has 4,096 pending slots by default, plus at most one event being
dispatched. Each queued event occupies exactly one slot, regardless of size.
There is no payload-size accounting or strict queued-payload memory bound.
Producers own unusually large payload concerns. If full, a publisher waits on a
condition for at most `publishTimeout` (10 ms by default, configurable; zero
rejects immediately). The worker signals publishers when it dequeues an event.
Waiting publishers wake on graceful close or fatal failure. Interruption rejects
that attempt and restores the interrupt flag without failing the broker.

The worker snapshots eligible registrations in registration order, then runs
filters and matching handlers outside the queue lock. Typed subscriptions filter
by Kotlin type. Reentrant publication appends normally. A subscription begins at
the next accepted sequence, without replay. Its idempotent close removes future
snapshots, while an already-snapshotted callback may still run once. Names are
unique diagnostic identifiers of at most 80 characters. Subscriber exceptions
are isolated, with a bounded status counter and latest subscriber/phase summary.

### Event publication from subscribers

Subscribers may publish events in response to received events. Those events join
the queue for later delivery; they are never dispatched recursively. Take care
to avoid direct or indirect feedback loops. For example, a subscriber handling A
could publish B, whose subscriber publishes A again. Such a loop can keep the
queue occupied and eventually cause fatal overload.

- Publish only meaningful new state changes or observations. Do not republish an
  event simply because it was received.
- Check whether this subscriber or another subscriber could feed an emitted event
  back into the same chain. Use state-change detection, deduplication or another
  suitable guard when feedback is possible.
- Publishing on the broker's own dispatcher thread needs particular care: if the
  queue is full, the dispatcher cannot dequeue another event while its own
  `publish()` waits. The wait can time out and latch fatal broker failure.

The broker deliberately does not detect or suppress event loops. Producers and
subscribers own loop prevention.

## Fatal overload and shutdown

If the queue remains full at the deadline, `publish` latches one `BrokerFailure`,
clears queued work, wakes independent fatal waiters and throws
`QueueFullException`. Further publications and registrations throw
`BrokerFailedException`. Closed publication throws `BrokerClosedException`.
`awaitFatal(timeout)` exposes the sticky failure independently of the worker,
including when a producer catches the publication exception. It returns null on
timeout or healthy worker termination. Application composition in #102 must
supervise it, report diagnostics independently, stop producers, perform bounded
cleanup and exit nonzero. The broker never exits the process itself.

`drain()` captures a fixed accepted sequence and waits for callbacks through that
boundary, not later events or consumer-owned work. Fatal failure causes drain to
throw even if a prefix completed. `close()` stops admission and requests graceful
delivery while healthy; it never joins the worker or enqueues a poison pill.
`awaitClosed(timeout)` observes actual worker termination and returns
`WOULD_DEADLOCK` when called from that worker. A blocked user callback can delay
healthy close. Fatal shutdown must not depend on drains or final events.
