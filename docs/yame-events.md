# YameEvents broker infrastructure

The broker lives in `no.skasti.yame.observer`. Runtime, PPP, HTTP and proxy
resource producers publish observations through `YameEvents.publish()`. Both
plain and TUI modes register consumers before starting runtime discovery or
connections. `RuntimeObserver` has been removed. Configuration transactions and
subscriptions remain in `ConfigStore`; typed commands/results remain in
`FunctionStore`; internal protocol/control callbacks remain local.

The TUI hands observations off to one count-bounded FIFO queue and one dedicated
`yame-tui-worker`. Configuration notifications, keyboard actions and header timer
requests use the same queue. Only that worker mutates presentation state and
renders. Admission order defines FIFO across sources; domain occurrence ordering
and stale/deferred event handling remain consumer-owned.

The TUI queue holds at most 256 pending actions. External admission waits up to
50 ms for capacity, without holding a rendering monitor. A synchronous callback
from the TUI worker cannot wait on its own queue and fails immediately if full.
Saturation and worker failure retain a fatal failure that
`ApplicationEventLifecycle` polls independently of broker/TUI progress. Raw queue
admission, like broker publication, rejects an interrupted wait and restores the
interrupt flag without failing the queue. TUI observation/action callbacks use
required admission: an interrupted rejection while still accepting is fatal,
because those callbacks cannot replay the lost input and their callers may isolate
exceptions. Once admission is closed, interrupted rejection is ordinary shutdown
and does not fail the queue. A rejected attempt is never reported as accepted.
Callback exception isolation cannot conceal a retained failure. There is no silent dropping or coalescing
while accepting work. Typed FunctionStore command results are consumed directly
by their command on the worker; `/port` refreshes before opening its chooser.

Explicit snapshot methods serialize reads through the worker. After termination,
state is frozen and can be inspected directly. Queue close rejects later callbacks,
drains accepted actions and tears down the terminal on the worker. Off-worker close
waits at most three seconds, capped by the remaining application shutdown budget
when closed by `ApplicationEventLifecycle`; it does not start a new three-second
budget after producer/broker drain. Worker-initiated close returns immediately and teardown
follows accepted work. The independent five-second application shutdown deadline still bounds
blocked rendering, command IO and teardown.

Configuration notifications retain FIFO admission even when delivered synchronously
inside a worker command's `ConfigStore.set()`. If the queue is full, required
reentrant admission fails immediately and independently triggers fatal supervision,
including when `ConfigStore` catches the callback exception. The committed setting
is not rolled back. Applying the notification inline would overtake already
accepted work, so the TUI does not bypass its queue or add a side backlog.

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
  transformation summaries, fetch-attempt ID and number, and an authoritative
  per-resource `resourceRevision`. The revision advances with observable resource
  state and association changes under the registry lock. It is independent of
  graph usage counts, delivery order and wall-clock resolution.

Each type has a stable `typeName`; the resource lifecycle event now uses
schema version 1.1.0 (additive `resourceRevision` field), while the graph lifecycle
event remains 1.0.0. Resource collections are copied
into unmodifiable snapshots. `occurredAt` is a UTC `Instant` captured by the
registry at the authoritative state transition; event publication and consumer
delivery happen afterward. A graph's usage count increments for graph creation,
reuse and accepted resource discovery, matching the registry's existing root-use
observations. Fetch attempts move the authoritative state back to `FETCHING`;
the attempt number distinguishes a legitimate retry from a delayed event from
an earlier attempt. The registry rejects stale attempt updates before emitting
an event. TUI and independent recording projections prefer the resource revision
when choosing between older and newer snapshots: equal revisions with different
resource contents are conflicting observations, even when their timestamps match.

The registry publishes through `YameEvents` outside its state lock. It does not
create a second queue or suppress publication failures. First publication
creates the default broker unless composition installed one earlier. Production
composition subscribes and starts independent supervision before producers.

## Event occurrence and ordering responsibilities

**Implementation status:** Proxy resource producers, runtime/frontend observations
and application lifecycle wiring, including the incremental proxy TUI projection,
are implemented. New domain transition
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
removes frontend subscriptions, drains accepted TUI work before terminal teardown, and closes the
frontend, configuration installation and logging/capture resources. Finally it
seals the facade. An incomplete shutdown is a failure; it never claims a
successful drain or closes a still-dispatching frontend.

Runtime shutdown attempts each subscription/function handle, monitor interruption,
serial/modem release, transition wait and monitor join independently. It retains
the first cleanup exception and suppresses later ones before signalling shared
close completion. A detached connection emits logical disconnection even when
physical cleanup fails, without claiming successful physical shutdown. Publication
failure is also retained, and transition accounting always finishes; stale reader
callbacks and later shutdown do not release or disconnect that detached IO again.

Producer and consumer cleanup exceptions are isolated per registration, in reverse
order. Broker close and wait failures are isolated too: confirmed dispatcher
termination still allows consumer cleanup. Consumer resources remain open when
termination is unconfirmed; facade sealing requires both successful producer
quiescence and confirmed broker termination, but does not require every consumer
close to succeed. Any cleanup failure remains latched and causes nonzero exit.
Diagnostics use one daemon writer with at most 16 pending messages. Reporting
exceptions or a full diagnostic backlog are best-effort losses, not cleanup
failures being cleared; a blocked writer cannot hold up cleanup. Cleanup and
diagnostic completion share the original global deadline. A cleanup operation
that never returns can leave later operations unattempted before forced exit.

## Proxy dashboard projection

`Main` constructs, registers and starts the selected presentation before
constructing or running the runtime. `InteractiveYameApplication` registers
`TuiYameObserver.onEvent` during that presentation initialization, and its
nonblocking `start()` enables rendering/input independently of core function
registration. The TUI reports unavailable operations as command failures and
keeps accepting input; the function store still throws for unavailable functions.
Runtime functions appear during `run()` startup, not construction. Successful
lookup does not guarantee executable component state; selected commands may fail
while runtime stops, and the TUI handles that failure at the input boundary.
It owns no runtime or registry. The same subscriber handles runtime and proxy observations; there is
no registry hook, snapshot synchronization, per-event registry read, or proxy UI
executor. Registry-instance injection stays at runtime/core boundaries; the lazy
shared production registry is resolved there and survives reconnects. Immutable observations are staged while holding registry locks and
published after releasing them; broker admission/fatal failures still propagate.
The plain diagnostic presentation installs its subscription at the same bootstrap
stage. Shutdown stops core producers and drains events before releasing either
presentation and its subscriptions. See
[application composition](configuration-architecture.md#application-startup-and-shutdown).

The TUI-owned `HttpGraphProjection` incrementally applies public graph/resource
payloads. `CREATED.initialResources` seeds only that lifetime's initial members;
subsequent events change individual members. Canonical resource keys deduplicate
URLs within a graph and across the displayed hosts. Per-graph memberships and
incident relationship edges remain separate, including shared resources and
multiple parents. Host usage sums each contributing graph's authoritative use
count once. Its recency comes from domain last-use times. Counts, not receipt
time, select the latest use even if the clock moves backwards. Cross-graph
representations of a shared URL use the latest occurrence time, with graph ID as
a stable tie breaker. Selection and expansion remain presentation-owned.

The projection uses fetch-attempt number/identity and within-attempt state
progress before occurrence time when rejecting delayed full resource observations.
A retry can legitimately return to `FETCHING`. Associations and prefetch/navigation
promotion resolve some equal-time cases; conflicting terminal states, identities,
or equal-time metadata without causal evidence expose a local failure instead of
using last-delivery-wins. Graph removal dominates all delayed events for that
lifetime; a new graph at the same URL has its own identity. Removed membership
cannot be resurrected within that lifetime. Exact retired IDs are compacted into
contiguous ranges, never an assumed high-water mark; at most 1024 disjoint ranges
are retained. Exhaustion is an explicit local failure.

A resource or use observation before `CREATED` is deferred privately (at most 256
distinct dependencies), without another queue worker or polling. Repeated
observations of a pending member/use coalesce using the same domain semantics. While unresolved, the
proxy panel shows an **incomplete** status; it does not invent a graph from a
resource payload. Creation resolves that graph's dependencies, and removal discards
them. If the bound is exceeded or observations are ambiguous, the proxy view is
cleared and marked **unavailable** until restart. `observationFailure` and independent fatal
supervision retain the failure. A terminal projection failure also fails the TUI worker and triggers fatal supervision.
An intentionally late subscriber receives no replay or bootstrap.

Broker callbacks only admit TUI observations. A blocked terminal does not hold an
observer monitor needed by broker/config/input admission. Queue saturation remains
fatal and is detected without TUI worker progress. Latch-based tests exercise
FIFO, overload, worker failure and drain/teardown boundaries.

The integration tests register an independent `RecordingResourceView` before the
fixture registry publishes. Controlled clocks and broker drain checkpoints compare
both consumers' graph identity/root, resource metadata, incident associations,
cumulative usage, last-use time and host sorting. The trace covers repeated
discovery, usage-only graph reuse, shared resources, duplicate delivery, eviction
and recreation at the same URL. The recorder uses only public typed payloads;
read-only TUI inspection methods supply the comparison side, without registry reads.

The current registry emits resource `REMOVED` only as part of graph eviction; it
has no individual membership-removal operation. A separate explicit public-payload
sequence, published through the real broker, verifies individual removal cleans
incoming/outgoing associations while preserving other members. The recorder is a
small controlled-trace test consumer, not a second general ordering engine. The
public contract still provides no total ordering for conflicting equal-time
metadata and no replay/bootstrap for late subscribers.

## Runtime observations and queries

- `ConnectionStateChanged` carries a process-monotonic connection ID, state and
  occurrence time. `PppSessionEnded` and PPP observations carry a process-unique
  `PppSessionIdentity` containing that connection ID. Serial reconnect counters
  and repeated TCP endpoints are not global identities. Transfer flow IDs also
  include the IPCP generation, so a renegotiation does not reuse its old flow key.
- TUI projection keys qualify DNS/transfer IDs with their connection/session
  identity, preserving bounded closed-transfer and DNS history across reconnects.
  Connection/session high-water marks reject old observations. Terminal
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
- `YameLogManager` is the sole publisher of `LogEntry` observations
  (`yame.logging.entry` v1), carrying module, severity and message. Module
  filtering happens **before** file output and event publication, so DEBUG
  packet traces are not delivered to the TUI at the default INFO level.
  Accepted entries are published outside the file-writer lock, even when the
  underlying log file cannot be written. The previous `DiagnosticMessage`
  (`yame.diagnostic.message`) contract is replaced, not aliased.
- PPP/HTTP domain event diagnostics still go through the independent
  `diagnosticSink` tee exactly once before direct facade publication.
  Packet capture retains its own bounded executor. The sink ignores `LogEntry`,
  preventing recursive log publication. A failed diagnostic tee is reported
  independently and does not suppress publication of the domain event.

The tone-test path uses the same early subscription, diagnostic tee, supervision
and shutdown composition as plain mode.
