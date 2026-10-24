# YameEvents broker infrastructure

The broker lives in `no.skasti.yame.observer`. Runtime, PPP, HTTP and proxy
resource producers publish observations through `YameEvents.publish()`. Both
plain and TUI modes register consumers before starting runtime discovery or
connections. `RuntimeObserver` has been removed. Configuration transactions and
subscriptions remain in `ConfigStore`; typed commands/results remain in
`FunctionStore`; internal protocol/control callbacks remain local.

The TUI currently handles broker callbacks synchronously, including rendering.
This can block **broker dispatch**, while producers continue to enqueue until
capacity is exhausted. A full queue waits at most the configured publication
timeout (10 ms by default), then fails fatally. A dedicated TUI worker remains
an independent follow-up in [#101](https://github.com/skasti/yame/issues/101).
The proxy dashboard still uses its registry hooks and executor until
[#103](https://github.com/skasti/yame/issues/103).
The [v1 design](design/yame-events-v1.md) distinguishes these implemented boundaries
from the future TUI worker; the [historical inventory](.history/yame-events-a37ac65.md)
records the starting point.
[ADR 0001](adrs/0001-fatal-event-queue-saturation.md) decides overload handling;
[ADR 0004](adrs/0004-extensible-event-contract.md) decides event ownership and access;
[ADR 0006](adrs/0006-consumer-owned-event-ordering.md) adds occurrence-time
semantics and consumer-owned dependency handling; [ADR 0007](adrs/0007-lazy-global-event-broker.md)
decides lazy global broker ownership.

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

The proxy registry owns two immutable event contracts:

- `ResourceGraphLifecycleEvent` reports `CREATED`, `USED` and `REMOVED` graph
  lifetimes with the graph ID, canonical root legacy URL, cumulative registry
  use count and last-use `Instant`. Its `CREATED` event also includes immutable
  initial snapshots of the graph's root and any directly admitted resource.
- `ResourceLifecycleEvent` reports resource admission, discovery, association,
  state, kind, transformation and removal changes. It includes the graph ID and
  root, normalized resource key and display URL, upstream/content-base URLs,
  incident parent/relation associations, state, kind, sticky prefetch state,
  transformation summaries, and fetch-attempt ID and number.

Each type has a stable `typeName` and v1 schema. Resource collections are copied
into unmodifiable snapshots. `occurredAt` is a UTC `Instant` captured by the
registry at the authoritative state transition; event publication and consumer
delivery happen afterward. A graph's usage count increments for graph creation,
reuse and accepted resource discovery, matching the registry's existing root-use
observations. Fetch attempts move the authoritative state back to `FETCHING`;
the attempt number distinguishes a legitimate retry from a delayed event from
an earlier attempt. The registry rejects stale attempt updates before emitting
an event.

The registry publishes through `YameEvents` outside its state lock. It does not
create a second queue or suppress publication failures. First publication
creates the default broker unless composition installed one earlier. Production
composition subscribes and starts independent supervision before producers.

## Event occurrence and ordering responsibilities

**Implementation status:** Proxy resource producers, runtime/frontend observations
and application lifecycle wiring are implemented. Proxy TUI projection migration
remains tracked by #103. New domain transition
events expose an occurrence timestamp (normally `occurredAt`),
captured when authoritative state changes, not when they enter the broker.
Producers publish truthful immutable observations and sufficient entity,
lifecycle and operation-correlation data. Consumers interpret domain semantics
first (for example, distinguish an old fetch from a refetch), and may consult
occurrence time when useful. Timestamps are not a guaranteed total order.

Consumers own any dependency buffering, stale-event handling and projection
ordering. A consumer may defer a resource event until it knows the graph;
another may ignore the dependency entirely. Deferred work must be private and
bounded, and must not block the shared dispatcher. The broker still orders
**enqueue**, not domain occurrences; it does not reorder or replay events,
and does not require producer-side root-before-resource publication. See
[ADR 0006](adrs/0006-consumer-owned-event-ordering.md).

## Admission and delivery

Each `EventBroker` owns one daemon dispatcher. `YameEvents.current`, `publish`
and `subscribe` share a lazily created default unless an explicit broker was
installed before first access. Concurrent first access creates exactly one
broker. Active replacement is rejected; a rejected distinct candidate is closed.
Register consumers before producers when they need every event.
`publish(event): Long` returns an
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
timeout or healthy worker termination. `ApplicationEventLifecycle` supervises it
on a separate daemon thread. A caught producer exception cannot hide the sticky
failure. It reports through stderr without publishing another event, stops
producers and attempts cleanup within a five-second overall deadline. A blocked
dispatcher, producer close, diagnostic writer or JVM hook cannot prevent nonzero
termination: production uses `Runtime.halt(1)` after failure or deadline expiry.
The broker never exits the process itself.

`drain()` captures a fixed accepted sequence and waits for callbacks through that
boundary, not later events or consumer-owned work. Fatal failure causes drain to
throw even if a prefix completed. `close()` stops admission and requests graceful
delivery while healthy; it never joins the worker or enqueues a poison pill.
`awaitClosed(timeout)` observes actual worker termination and returns
`WOULD_DEADLOCK` when called from that worker. A blocked user callback can delay
healthy close. Fatal shutdown must not depend on drains or final events.

The facade owns its default broker. `YameEvents.shutdown()` seals the facade and
closes that default, which the owner can then await. A JVM shutdown hook delegates to the installed application shutdown coordinator;
without an application owner it closes and briefly awaits the default itself.
Delegation prevents competing hooks from closing admission before producers stop.
Explicitly installed brokers remain owned by their installers: close and await
the broker before releasing the installation handle. Release permits another
explicit installation in serial isolated tests, but does not restore lazy
creation. Publishing after final shutdown fails and cannot restart a worker.
Late subscribers receive only future events; they can query authoritative
components for current state when needed.

Application shutdown first stops runtime commands, port monitoring and serial
reads, and waits for connection attempts and proxy producers outside protocol
monitors. Session closure fences late UDP/ICMP callbacks. Proxy close remains
bounded, with a separate `awaitQuiescence` boundary used by the PPP owner to
verify actual worker/handler completion. It then closes and awaits the broker,
drains the existing resource UI executor, removes subscriptions and closes the
frontend, configuration installation and logging/capture resources. Finally it
seals the facade. An incomplete shutdown is a failure; it never claims a
successful drain or closes a still-dispatching frontend.

## Runtime observations and queries

- `ConnectionStateChanged` carries a process-monotonic connection ID, state and
  occurrence time. `PppSessionEnded` and PPP observations carry a process-unique
  `PppSessionIdentity` containing that connection ID. Serial reconnect counters
  and repeated TCP endpoints are not global identities. Transfer flow IDs also
  include the IPCP generation, so a renegotiation does not reuse its old flow key.
- TUI connection/session high-water marks reject old observations. Terminal
  states dominate delayed open notifications for the same identity. A new PPP
  observation can precede its connection notification; no producer-specific
  connection-before-PPP publication order is required. Transfer details arriving
  before their start are deferred locally (at most 128 events), then applied on
  start. Buffer exhaustion exposes a local projection failure in the log and
  `observationFailure`, and is also recorded by broker subscriber status.
- `RefreshPorts` is `FunctionKey<Unit, List<SerialPortDescriptor>>`. It returns an
  unmodifiable snapshot immediately. The existing port palette uses that result,
  without a request/result event protocol. Startup and the port monitor independently
  publish `AvailablePortsChanged` only when the observed list changes; its list
  is also an unmodifiable defensive copy. An open palette consumes those changes.
- `DiagnosticMessage` is a presentation observation. Module text logging happens
  first on the producer with its existing severity. PPP/HTTP event diagnostics go
  through the independent `diagnosticSink` tee exactly once before direct facade
  publication. Packet capture retains its own bounded executor. Subscriber failure
  or rejected observation admission cannot suppress or duplicate these writes;
  there is no module-file logging subscriber on the broker worker. A failed
  diagnostic tee is reported independently and does not suppress publication.

The tone-test path uses the same early subscription, diagnostic tee, supervision
and shutdown composition as plain mode.
