# YameEvents broker infrastructure

Implemented by #98 in `no.skasti.yame.observer`. This documents the broker API and
its tests. **Production runtime still uses the existing event sinks**; installation,
fatal process termination and runtime/frontend migration belong to #102/#101.
The [decided design](design/yame-events-v1.md) describes that wider migration;
the [historical inventory](inventories/yame-events-a37ac65.md) records its starting
point. [ADR 0001](adrs/0001-fatal-event-queue-saturation.md) records the overload
decision and rationale. No complete-state bootstrap, replay or global state store is implemented.

## Ownership and API

Each `EventBroker` owns one daemon dispatcher. Modules can receive an isolated
instance. `YameEvents.current` exposes the explicitly installed production broker;
access before installation and duplicate installation fail. Closing the installation
handle only removes access; composition owns broker shutdown. No hidden lazy worker
or test reset exists. Register consumers before starting producers.

`publish(event): Long` returns the accepted sequence. A short lock serializes
admission, subscription registration and shutdown. It never invokes callbacks,
waits for capacity or does I/O. Concurrent producers are ordered by admission,
not event creation time. Reentrant publication appends normally. Producers remain
responsible for domain causal ordering before publishing.

The worker waits on a condition while idle. For each event it snapshots eligible
registrations in registration order, then runs filters and matching handlers outside
the lock. A subscription starts after the latest accepted sequence, so it never
receives older queued observations. `subscribeTyped<T>` is a typed convenience.
Subscription names are unique among live registrations and bounded to 80 diagnostic
identifier characters. Names must not contain user data or credentials.

Registration handles close idempotently and never wait for a callback. A callback
already snapshotted may still execute once. Consumers own their state, execution
contexts and fencing of already queued local work. Handler/filter exceptions are
isolated; status records a saturating counter and the latest subscriber/phase only,
never exception messages, stack traces or payloads. No error is republished.

## Bounds and failure

Defaults: 4,096 pending events, 16 MiB accounted pending payload, 128 KiB per event,
64 active subscriptions. At most one extra event and one registration snapshot are
in flight. Constructor configuration allows smaller deterministic test budgets.

`EventPayloadBudget` accounts 128 bytes per event/envelope, 40 + 2 × string length
per non-null string, and 24 + packet length for a packet array. This is a budget,
not an exact JVM heap measurement. Existing event shapes have a fixed number of
fields. Exhaustive matching requires newly introduced event types to specify their
budget. Current events are immutable; packet arrays are copied on construction and
access. `byteCount` avoids making a copy simply to check admission. Event construction
and memory retained by callers are outside the queue budget. Future collection
payloads must be defensively copied and bounded at their ownership boundary.

Count or aggregate byte saturation throws `QueueFullException` synchronously from
`publish`. An oversized event throws `EventTooLargeException`. Both expose a
`BrokerFailure` containing limits, occupancy, requested size, last accepted sequence
and the active subscriber (possibly null). This is context, not a slowest-subscriber
measurement or proof of fault. No watchdog or latency monitoring is used.

Before throwing, admission latches the first failure, closes admission, clears
pending work and signals independent waiters. Subsequent publication/registration
throws `BrokerFailedException`; normal closed publication throws
`BrokerClosedException`. Failure takes precedence over close. Registration capacity
errors do not poison the event stream because no event has been lost.

**Full queue is an approved fatal application error.** `awaitFatal(timeout)` reads
and waits for the sticky failure independently of dispatch. Catching a publish
exception cannot clear it. The method returns null on timeout or healthy worker
termination and rejects calls from the dispatcher. It invokes no user code.
Composition in #102 must supervise this signal independently, report through an
independent diagnostic route, stop producers, attempt bounded cleanup and exit
nonzero even if a subscriber never returns. Merely installing the facade does not
implement that supervision. No recovery or continuing with a stale view is supported.
A fatal shutdown abandons pending observations; a callback already selected may
still execute. The daemon worker cannot prevent JVM termination on its own.

## Drain and close

`drain()` captures the last accepted sequence in a constant-size handle. `await`
waits only for callbacks through that boundary, not later events or consumer-owned
work. It returns `COMPLETED`, `TIMED_OUT` or `WOULD_DEADLOCK`. Fatal failure throws
`BrokerFailedException`, even for an already completed prefix; no successful drain
is promised on the fatal path. No unbounded future/listener list is retained.

`close()` stops admission, wakes the worker and requests graceful prefix delivery
while healthy. It never joins or inserts a poison pill. `awaitClosed(timeout)`
waits for actual worker termination and returns `WOULD_DEADLOCK` when called from
that worker. It can time out if a callback is blocked, including after a fatal
failure. Completed worker shutdown alone does not imply successful delivery; read
status or drain for failure. Waits are interruptible; negative timeouts are rejected.

Healthy shutdown quiesces producers, closes/drains the broker, then drains consumer
work before releasing resources. Fatal shutdown must not rely on any of those
drains completing or on sending final events. This application orchestration is
explicitly deferred to #102.

`EventBrokerTest` uses barriers, latches, fixed drain boundaries and a package-local
idle wait to verify ordering, admission bounds, failure isolation/signalling,
subscription races, reentrant publication, and shutdown without arbitrary sleeps.
