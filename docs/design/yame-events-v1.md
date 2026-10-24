# YameEvents v1 design

This document describes the decided v1 design for
[#92](https://github.com/skasti/yame/issues/92). **Implementation status: partial.**
The broker infrastructure is implemented in [#106](https://github.com/skasti/yame/pull/106),
and the proxy resource event contract and producer are implemented in #100.
Production broker wiring remains tracked by #102 and frontend migration remains
tracked by #101 and #103. Decided design does not imply implemented behavior: the
[current broker architecture](../yame-events.md) documents verified code.

Decision rationale, alternatives and consequences belong in
[ADR 0001: fatal event-queue saturation](../adrs/0001-fatal-event-queue-saturation.md)
[ADR 0004: extensible event contract](../adrs/0004-extensible-event-contract.md)
and [ADR 0006: consumer-owned event ordering](../adrs/0006-consumer-owned-event-ordering.md).
The [historical inventory at a37ac65](../.history/yame-events-a37ac65.md) is
maintained separately from this design. Documentation responsibilities are defined in
[ADR 0003](../adrs/0003-document-decided-design.md); ADR numbering and lifecycle
are defined in [ADR 0002](../adrs/0002-architecture-decision-records.md).

## v1 contract

### Responsibility and ownership

`YameEvent` is an open event interface with stable `typeName`, semantic schema
`VersionNumber(major, minor, patch)` and a read-only payload map. Concrete types
belong near their producers. `AbstractYameEvent` offers lazy, cached Kotlin-property
reflection for generic map access; typed access and publication stay reflection-free.
Event payloads are immutable snapshots, with defensive copies for mutable values.
An instantiable broker delivers observations to trusted in-process consumers, and
`YameEvents` exposes the installed production broker. Each consumer owns its state,
subscriptions and execution context. See [ADR 0004](../adrs/0004-extensible-event-contract.md).

The proxy's `NavigationResourceRegistry` owns actual resource knowledge. Runtime
composition must retain and inject it across serial reconnects. The TUI's
`httpGraphs` is a separate local projection of observations. It is never the
proxy's source of truth. Graph knowledge survives TCP flow closure, PPP generation
changes and serial reconnect; bounded registry retention controls eviction.

Configuration changes continue through `ConfigStore`; requests such as reconnect
continue through `FunctionStore`. Their transaction, command and notification
contracts are documented in [configuration architecture](../configuration-architecture.md).
v1 has no global state store, durable event log, IPC transport, subscriber watchdog
or broker-managed per-subscriber queue. Saturation requires fatal application termination.

### Event values, occurrence time and consumer-owned ordering

Event payloads must be usable without TUI imports or mutable/internal registry
objects. Define externally visible resource values at an appropriate public
contract boundary; do not expose internal registry objects through a public
event. Copy collections at ownership boundaries. Kotlin's read-only `List`
alone does not establish immutability.

The proxy producer currently exposes ResourceGraphLifecycleEvent and
ResourceLifecycleEvent, both with stable v1 schemas. Their payloads carry the
following semantic data.

| Observation | Required information and interpretation |
| --- | --- |
| Graph/root created | Stable process-lifetime graph ID, canonical root legacy URL and initial snapshots for the root and any directly admitted resource. A revisit retains the ID; an evicted root recreated later gets a new ID. Resource transitions are also reported separately. |
| Graph/root used | Graph ID, cumulative use count and occurrence/last-use time from the registry, not the consumer's rendering time. |
| Resource admitted/updated | Graph ID, canonical resource identity/legacy URL, full state and kind, sticky prefetch flag, immutable transformation summaries and correlation for separate fetch attempts. Shared resources can occur in several graph IDs. |
| Resource removed | Graph ID and resource identity; enough information to remove precisely that association. |
| Graph/root removed | Graph identity and occurrence time; enough to invalidate all remaining associations and reject obsolete delayed observations. |

Use the observable URL identity already defined by
[LegacyHttpUrl.requestObservableKey](../../src/main/kotlin/no/skasti/yame/ppp/proxy/LegacyHttpUrl.kt):
normalized scheme/host/default port, observable path/query and no fragment.
Preserve the legacy URL for display. Graph IDs are not TCP flow IDs. Connection
and PPP session identities must qualify transfer/DNS keys that can otherwise
repeat. Retained graph state is process-scoped; legitimate work on a retained
graph must be checked using membership and fetch-attempt identity, not rejected
solely because a connection changed.

New lifecycle event contracts expose an occurredAt: Instant timestamp captured when
the **authoritative transition occurred**, not when the event was constructed,
enqueued or handled. Consumers prefer domain semantics for interpretation,
using occurrence times as supporting evidence where useful. Timestamps alone
are not an infallible total order because of clock changes and ties.

**Event ordering is consumer-owned** ([ADR 0006](../adrs/0006-consumer-owned-event-ordering.md)).
The broker's increasing accepted sequence and FIFO order represent *enqueue*
order, not a cross-producer domain order. Registry producers do not need to
publish root events before resource events or coordinate mutation-order
enqueue just to accommodate a projection. A consumer that needs a graph before
processing its resource observation privately defers it; another consumer
that does not care may process its own events immediately. Such deferral
must be bounded and must not block broker dispatch.

Event identities and domain correlation must allow consumers to reject stale
observations without imposing a universal revision counter. For example,
`READY` supersedes `FETCHING` within a single fetch attempt, but `FETCHING`
from a later attempt is valid; the proxy contract must distinguish the two.
A graph removed by eviction must not be resurrected by a late update. The
registry still rejects stale mutations, and accepted lifecycle observations
must describe actual transitions. Publication does not imply permission to
silently drop events.

The [historical inventory](../.history/yame-events-a37ac65.md) records the
migration starting point. Tests for #100 cover immutable payloads, real
transitions and sufficient correlation. A test-only consumer exercises bounded
buffering and stale-event handling under deliberately reordered delivery;
the production TUI projection migration remains in #103.

### Broker admission, delivery and subscriptions

`publish` coordinates admission without inspecting event payloads. It returns the
accepted sequence or throws an explicit rejection. It never invokes subscribers,
renders or performs I/O. If the queue is full, it waits for space for at most the
configured publication timeout (10 ms by default). Successful publication returns
its sequence; rejection reports closed, queue-full or previously-failed state.
Only acceptance promises delivery under the healthy lifecycle contract below.

One worker takes accepted events in FIFO order and blocks on a condition/queue
while idle. For each event it snapshots eligible registrations in registration
order, releases broker synchronization, evaluates their filters and calls matching
handlers sequentially. Reentrant publication appends through ordinary admission;
it never dispatches recursively. Filters and handlers must return quickly.
Subscribers that publish new observations must guard against direct or indirect
feedback loops, including A → B → A chains. Publish only meaningful state changes,
with deduplication or state-change detection where needed. A full queue cannot be
drained while the dispatcher itself waits in `publish()`, so a reentrant publication
may time out and trigger fatal overload. The broker does not detect event loops;
the [current broker guidance](../yame-events.md#event-publication-from-subscribers)
explains the producer/subscriber responsibility.

Registration is atomic with admission: a new subscription starts at the next
accepted sequence after registration and receives no older queued events. This
is live subscription, not replay. Filtering is typed/predicate-based and does not
reorder the stream. Closing a registration removes it from subsequent dispatch
snapshots; an already-snapshotted callback may still execute once. A handle's close
is idempotent and never waits for that callback. Owners fence already queued local
work during their own shutdown.

A filter/handler exception is isolated; later registrations and events continue.
Record a failure counter and one bounded, sanitized failure summary through a
non-blocking broker diagnostic/status facility. Never publish the error back into
this broker or invoke synchronous logging while holding broker synchronization.
A consumer whose own projection fails must expose that local failure rather than
claim its state is still complete. Exception isolation does not disable or
unsubscribe the handler automatically.

### Bounded overload behavior

Queue saturation is a fatal application error. YAME must terminate with a nonzero
exit code. The decision record is [ADR 0001](../adrs/0001-fatal-event-queue-saturation.md).

Production defaults (constructor-overridable in isolated tests): 4,096 pending
queue elements, a 10 ms publication timeout and 64 registrations. An event in
flight after dequeue does not occupy a pending slot. Every event counts equally;
there is no payload-size accounting. This deliberately does not place a strict
bound on retained payload memory. Producers own unusual payload-size concerns.

A full queue causes bounded condition-based waiting. If space opens, the event is
admitted in sequence order. If the deadline passes, `publish` atomically latches
a bounded fatal record, wakes independent fatal waiters and throws
`QueueFullException`. Its metadata identifies count capacity, occupancy, last
accepted sequence and active subscriber. The active subscriber is diagnostic
context, not proof of which subscriber is slowest. Zero timeout rejects immediately.
Close or fatal failure wakes waiting publishers. Interruption rejects the attempt
and restores the interrupt flag without poisoning the broker. Further publication
after fatal failure throws `BrokerFailedException`; there is no recovery or retry.
No subscriber watchdog is added.

The fatal signal is not a domain event, callback, or queue entry. It is independently
awaitable even when a subscriber is blocked and remains observable if producer code
catches the exception. Fatal shutdown abandons queued events and interrupts the
healthy delivery guarantee; drain reports failure rather than false success.
A callback already selected may still run; arbitrary user code cannot be forcibly
stopped by the broker. Callback/filter exceptions remain isolated unless they
trigger this already-latched fatal admission failure.

Application composition (#102) must observe the fatal signal from an independent
lifecycle context, report through independent diagnostics, stop producers, attempt
time-bounded cleanup, then exit nonzero even if a callback is permanently blocked.
It must not publish shutdown events or require broker/consumer drain to complete on
this fatal path. The broker does not exit the process itself. There are no broker
per-subscriber queues, subscriber cancellation, live replacement or resynchronization.
Consumer-owned queue overflow must also reach application fatal supervision when
those consumers are integrated; it must not silently leave a stale view in service.

### Startup, reconnect and late consumers

Composition creates the broker and required consumers before starting runtime
enumeration, serial reads, connection attempts or other event-producing work.
Register diagnostic/frontend subscriptions before publishing initial connection
state and port-list values. Apply the same rule in plain mode and the tone-test
startup path if it publishes observations. Tests construct fresh brokers and
explicit dependencies; they never replace a mutable production singleton.

Install the production facade explicitly at application composition and retain its
installation handle until producers/consumers stop. Access before installation
fails fast, a second installation fails, and extra frontends reuse the same broker.
The facade does not lazily create a hidden worker or own application projections.

The v1 TUI is an early subscriber and remains subscribed through reconnect. It
receives a session-ended event qualified by the old connection/session identity.
Quiesce or generation-fence old work, enqueue its final observations/session end,
then publish new-session startup. Closing projected transfers applies only to that
old session, so queued cleanup cannot close a newer connection's transfers. Late
work must not recreate old active transfers or overwrite newer registry attempts.

v1 does not offer complete-state bootstrap for a new late subscriber. A late
consumer may explicitly choose a view labeled as observations since attachment;
it cannot claim a complete current proxy graph. An equivalent complete consumer
must subscribe during application startup. Full-state attachment after activity begins is outside v1. Calling `snapshots()`
and then subscribing is not a supported bootstrap.

### Diagnostics and logging

Keep the existing module log and capture paths independent of observation admission.
The runtime-owned diagnostic tee sends each existing YameEvent to
`YameLogManager.eventSink` once, then publishes it to observation consumers. Keep
existing configured severity, module routing, capture retention and capture queue
failure behavior. Avoid a second logging subscription that writes duplicates.
The tone-test path and plain mode use the same policy where applicable.

This deliberately preserves existing synchronous module-log I/O before broker
admission. `publish` and subscriber dispatch remain I/O-free; this design does
not claim to remove existing file-system latency from all producer code. Packet
capture I/O already runs on the recorder's bounded executor. The recorder may
already reject captures at its own limits; the broker must not add a new source
of capture loss. Broader asynchronous module logging is separate work requiring
its own admission, reliability and drain contract.

Frontend diagnostic-message events are best-effort observations subject to broker
health; their loss never suppresses the original file diagnostic. New high-volume
resource events produce no general-log lines by default. Optional debug summaries
must hand off to a bounded diagnostic adapter, or use an independent producer-side
debug route; the broker worker never writes files. Broker/consumer failure reports
use bounded counters/status and an independent reporting path, without event
recursion or printing full event payloads/credentials.

### Drain and shutdown

`drain()` captures the highest accepted sequence and returns an awaitable boundary.
Completion means all matching callbacks for that prefix returned (including isolated
failures); it does not mean a TUI queue rendered or a diagnostic writer flushed.
New publication after the captured boundary cannot indefinitely extend that drain.
Drain reports the latched fatal failure immediately, even if its boundary was delivered. Each consumer exposes its own drain boundary.

`close()` is idempotent and requests graceful stop: atomically stop admission, wake
an idle worker, deliver the accepted prefix while healthy, then terminate. Fatal failure instead
abandons pending work and never requires a successful drain.
It never joins the worker. A worker callback can therefore call `close()` without
joining itself. Do not enqueue a blocking poison pill into a full data queue.
Publication racing close is either accepted before that boundary or explicitly
rejected after it; closed/faulted publication never starts a new worker.

An explicit `awaitClosed(timeout)` may be used outside the worker. It returns
`TimedOut` honestly if a callback is still blocked; calls from the worker return
a would-deadlock result immediately. No forced interruption or guaranteed
termination of arbitrary user handlers is promised. Normal consumers enqueue and
return, and tests release intentionally blocked handlers in cleanup.

Normal application shutdown follows this order:

1. Stop new commands/automatic reconnect and quiesce or fence all producers;
   publish final session/connection observations while admission is open.
2. Request broker close and await its accepted prefix/worker completion outside
   producer, broker and UI processing threads.
3. Drain consumer-owned processing while its state/render resources remain valid,
   close subscription handles and then stop frontend resources.
4. Finish independent diagnostic/capture work and close logging; release facade
   installation last. An early frontend close fences its own already queued work.

Report a timeout as incomplete shutdown; do not advertise successful drain or close
resources still in use. Shutdown orchestration must not wait for a queue/worker
while holding a lock that its callbacks or consumers need.

### Implementation and verification map

The following map tracks the overall migration. The approved overload policy is
fatal application termination; runtime wiring remains #102.

| Issue / independently reviewable PR | Dependencies | Required evidence |
| --- | --- | --- |
| [#97: inventory and contract](https://github.com/skasti/yame/issues/97) | None | Source-grounded historical inventory; startup/bootstrap restriction and diagnostic independence; valid documentation links. |
| [#98: broker and facade](https://github.com/skasti/yame/issues/98) | #97; [ADR 0001](../adrs/0001-fatal-event-queue-saturation.md) | Verify count-bounded queue admission, a 10 ms default timeout and independent fatal supervision. Checks: FIFO with multiple producers, blocked subscriber and full queue, bounded waiting and wakeup, safe status reads, filtering/failure/reentrant publication, concurrent subscribe/remove, idle wakeup, drain high-water mark, publish/close race and close from worker. Verify fatal signalling independent of worker progress and caught exceptions, with no recovery. |
| [#99: registry lifetime](https://github.com/skasti/yame/issues/99) | None | Runtime-level serial/handler replacement retains graph identity and LRU limits; stale fetch attempts remain fenced. Define limit changes without session-teardown eviction. |
| [#100: proxy event contract and publication](https://github.com/skasti/yame/issues/100) | #97, #98, #99 | Immutable event snapshots and occurrence time; sufficient graph/resource/fetch correlation, including eviction and legitimate refetch; no producer-imposed root-first or cross-producer ordering. Existing hooks remain usable until #103. |
| [#101: TUI processing boundary](https://github.com/skasti/yame/issues/101) | #97 | Blocking terminal/render work cannot hold up admission; bounded local backlog, independent fatal escalation on overflow, consistent observation order, drain and close behavior. Works with existing events/hooks before broker wiring. |
| [#102: runtime composition and migration](https://github.com/skasti/yame/issues/102) | #97, #98, #101 | Plain/TUI startup receive initial observations, diagnostics/captures remain independent and are written once, no broker-worker I/O, old/new session ordering and full shutdown sequence. No unsupported late-consumer completeness claim. |
| [#103: proxy dashboard migration and hook removal](https://github.com/skasti/yame/issues/103) | #99, #100, #102 | Existing hosts/unique URLs/states/transforms/sort/selection/expansion remain correct; reconnect and eviction work; no outbound registry hook plumbing remains; an independent early consumer reconstructs an equivalent view. |

Use latches, barriers, controllable executors and explicit drain boundaries, with
timeouts only as failure guards. Do not use arbitrary sleeps or reset shared global
state between tests. Broker FIFO tests assert enqueue order; domain/consumer tests deliberately
reorder observations and verify semantic correlation without timing coincidences. Test count limits and publication deadlines with small injected capacities.
Run the relevant JVM build/integration checks for implementation changes, following
[AGENTS.md](../../AGENTS.md).

The broader component reuse/reset design is
[#104](https://github.com/skasti/yame/issues/104). It classifies process-, connection-
and session-lived dependencies and defines safe reset/quiescence. #92 only needs
the narrow registry ownership correction in #99; the broader refactor does not
block this migration.
