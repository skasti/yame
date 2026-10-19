# YameEvents v1 design proposal and implementation inventory

Status: **partially implemented**: broker infrastructure is described in
[the current broker architecture](../yame-events.md); production migration remains
a proposal. Originally prepared for
[#97](https://github.com/skasti/yame/issues/97), part of
[#92](https://github.com/skasti/yame/issues/92). The implementation inventory below
describes `main` at
[`a37ac65`](https://github.com/skasti/yame/commit/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19).
It is a historical snapshot, not authoritative documentation of later revisions.
The proposed v1 contract is separate from that inventory; normative wording within
the proposal describes intended behavior, not existing implementation.

**Approved decision (2026-10-19):** full queue is a fatal application error.
`publish` throws synchronously and latches an independent fatal signal. Application
composition must terminate YAME with a nonzero exit code without depending on event
delivery. This explicitly replaces the former restart-only/stale-stream candidate.
See #98 and #102 for the recorded maintainer approval and integration boundary.

The broker implementation is now documented in [docs/yame-events.md](../yame-events.md).
As remaining migration work lands, extend that current architecture document from
verified code. Retain the inventory below as historical context rather than a
statement about the current runtime.

## Implementation inventory at revision a37ac65

The current-behavior column describes the existing codebase at the revision linked
above. The final column explicitly separates proposed migration changes. Source
links in this inventory are pinned to that revision; other repository links are
relative to this document. Class and method names identify the relevant boundary
without depending on shifting line numbers.

| Current path | Owner and current behavior at `a37ac65` | Proposed v1 change (not implemented) |
| --- | --- | --- |
| `eventSink: (YameEvent) -> Unit` | [PppSession](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/session/PppSession.kt) emits DNS, transfer and unhandled-packet observations. [SystemRoutingTcpProxy](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/SystemRoutingTcpProxy.kt) and [SystemHttpCompatibilityHandler](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/CompatibilityHttpHandler.kt) emit `HttpProxyAction`. HayesModem and RetroPppHandler pass the sink to children. | Keep the existing observation types, publish through the broker, and add a connection/session identity where needed to prevent ID reuse across reconnect. |
| Runtime event fan-out | [YameRuntime.startConnection](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/runtime/YameRuntime.kt) synchronously calls `logManager.eventSink(event)` and then `observer.onEvent(event)`. Producer wrappers commonly use `runCatching`, so throwing from a sink is not a reliable failure signal. | Replace observer fan-out with broker subscriptions. Preserve the independent diagnostic route described below; admission failure throws and latches an independent fatal signal, so `runCatching` cannot hide it from lifecycle supervision. |
| `ResourceRegistryHooks` | [NavigationResourceRegistry](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/ResourceGraph.kt) reports root add/use/remove and resource add/update/remove. Hook parameters pass through runtime, PPP, routing proxy, HTTP proxy and handler. [InteractiveYameApplication](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ui/InteractiveYameApplication.kt) dispatches them through `yame-resource-ui`. | Replace outbound hooks with immutable public proxy events. Retain hooks only during the incremental migration. |
| `RuntimeObserver.updateConnected` | Runtime reports connection state directly to the frontend. | Emit connection-state observations with runtime connection identity. Emit the initial disconnected state before starting connection attempts. |
| `RuntimeObserver.updateAvailablePorts` | Runtime reports enumeration results on refresh and monitor activity. | Emit an immutable port-list observation, including the initial list; suppress unchanged later lists. Enumeration and selection remain runtime operations. |
| `RuntimeObserver.closeActiveTransfers` | Runtime directly instructs the frontend to close projected transfers when a serial session stops. | Replace the UI instruction with a session-ended observation. The TUI decides how it projects affected transfers. |
| `RuntimeObserver.onLog`, module logger lambdas | Runtime sends text to the frontend and module loggers. Serial, modem, PPP and proxy components also produce diagnostic text. | Keep feature diagnostic callbacks. Replace their outbound frontend branch with a typed diagnostic-message observation carrying module/level/text. Logging adapters own severity mapping and file policy. Do not derive protocol state by parsing log strings. |
| Log files and packet captures | [YameLogManager](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/logging/YameLogging.kt) writes and flushes module logs synchronously. [UnhandledPacketRecorder](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/logging/UnhandledPacketRecorder.kt) already owns bounded asynchronous capture I/O (64 pending tasks), size/retention limits and a failure path outside event publication. | Preserve existing diagnostic/capture behavior independently of observation-stream overload. Do not put synchronous file I/O on the broker worker. |
| Modem tone progress | [HayesModem.logToneProgress](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/modem/HayesModem.kt) formats a local audio progress callback into diagnostic text. | Keep the audio callback local; its frontend diagnostic text follows the same outbound diagnostic-message path. No separate tone-event hierarchy is required by #92. |

The [YameEvent definition](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/observer/YameEvent.kt)
currently has no graph/resource variants. `HttpProxyAction` is request-oriented
diagnostic activity; it cannot reconstruct the registry. The
[TUI observer](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ui/TuiYameObserver.kt) ignores that
event and renders directly inside other `onEvent` branches. Its hook handlers
maintain `httpGraphs`, aggregate unique URLs by host, and calculate recency and
usage on delivery. The proposal moves those delivery-time calculations to
authoritative event data so consumers agree even when their queues run at
different speeds.

### Existing local callbacks and proposed scope

| Boundary | Why it stays local |
| --- | --- |
| [SerialTransport.startReading](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/serial/SerialTransport.kt): `onBytes` / `onStopped` | Transfers serial input into the modem and tells runtime that its reader stopped. Runtime may then publish a connection/session observation, but the callback itself is transport control. |
| HayesModem `attachOutput` / `attachCarrierPresent`; [PppFramer](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/PppFramer.kt) `onFrame` / `onInvalidFrame`; PppSession `sendFrame` | These callbacks perform protocol processing and carrier/output control, with local ordering requirements. Broadcasting them would change protocol semantics. |
| [TcpProxy](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/tcp/TcpProxy.kt) `onEvent: (TcpProxyEvent) -> Unit`; [TcpFlowTable](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/tcp/TcpFlowTable.kt) results | Connected/data/write-ready/failure/closed notifications drive TCP state and backpressure. PppSession converts relevant outcomes into public transfer observations. |
| [UdpProxy.send](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/udp/UdpProxy.kt) `onReply` and ICMP completion | These return request results to the owning protocol session. DNS observations are emitted by that owner, not by globally broadcasting payload callbacks. |
| [HttpRequestHandler](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/http/HttpTcpProxy.kt) request/response calls, `connectionClosed` and activity checks | HTTP acquisition, cancellation and response delivery belong to HTTP/TCP coordination. Registry owners emit resource observations at their actual transitions. |
| Transformation providers, rewrite functions, cache/in-flight-work producers | These compute values or coordinate shared work. The owner emits the resulting summary; the functions are not consumer subscriptions. |
| ConfigStore change subscriptions and FunctionStore registrations | Configuration transactions and commands have separate established contracts. They do not become a second set of YameEvent notifications. |
| Packet recorder `onReceived` / `onCaptured`, injected file writers and socket operations | These coordinate diagnostic work or provide test seams. They remain owned by their adapter. |

## v1 migration contract (broker implemented; runtime migration pending)

The following sections specify the overall migration design. The fail-fast overload policy was explicitly approved before #98. Existing implementation
facts mentioned for context do not imply that the proposed contract is in place.

### Responsibility and ownership

`YameEvent` describes an observation. An instantiable broker delivers observations
to trusted in-process consumers, and `YameEvents` exposes the installed production
broker. Each consumer owns its state, subscriptions and execution context.

The proxy's `NavigationResourceRegistry` owns actual resource knowledge. Runtime
composition must retain and inject it across serial reconnects. The TUI's
`httpGraphs` is a separate local projection of observations. It is never the
proxy's source of truth. Graph knowledge survives TCP flow closure, PPP generation
changes and serial reconnect; bounded registry retention controls eviction.

Configuration changes continue through `ConfigStore`; requests such as reconnect
continue through `FunctionStore`. Their transaction, command and notification
contracts are documented in [configuration architecture](../configuration-architecture.md).
This proposal introduces no global state store, durable event log, IPC transport,
subscriber watchdog or broker-managed per-subscriber queue in its approved baseline. Saturation instead requires fatal application termination.

### Event values and producer ordering

Event payloads must be usable without TUI imports or mutable/internal registry
objects. Define the externally visible resource enums and transformation summary
values at an appropriate public contract boundary; do not expose an internal
`ResourceRegistryResource` just by attaching it to a public event. Copy collections
at ownership boundaries. Kotlin's read-only `List` alone does not establish
immutability. `UnhandledPacket` already defensively copies its bytes.

Proxy events need the following semantic data. Names are illustrative; #100 owns
the concrete event types.

| Observation | Required information and interpretation |
| --- | --- |
| Graph/root added | Stable process-lifetime graph ID, canonical root legacy URL, initial root-resource values and initial usage data. A revisit retains the ID; an evicted root later recreated gets a fresh ID. |
| Graph/root used | Graph ID, cumulative use count and last-use order/time captured by the registry. Preserve existing explicit use semantics; incidental LRU touches need not count as user-visible uses. |
| Resource admitted/updated | Graph ID, canonical resource identity/legacy URL, complete state and kind, sticky prefetch flag, copied transformation summaries, and revision within the registry stream. Shared resources can occur in several graph IDs. |
| Resource removed | Graph ID, resource identity and revision; enough information to remove exactly the association involved. |
| Graph/root removed | Graph ID and revision. Treat it as removal of all remaining resource associations in that graph. If resource removals are also emitted, place them before root removal and define them as idempotent. |

Use the observable URL identity already defined by
[LegacyHttpUrl.requestObservableKey](../../src/main/kotlin/no/skasti/yame/ppp/proxy/LegacyHttpUrl.kt):
normalized scheme/host/default port, observable path/query and no fragment. Preserve
the legacy URL needed for display. Graph IDs are not TCP flow IDs. Connection and
PPP session identities must qualify transfer/DNS keys that can otherwise repeat.
Retained graph state is process-scoped, so legitimate work on a retained graph
must be checked using graph membership and fetch-attempt identity, not rejected
solely because a connection changed.

The broker supplies an increasing sequence number at successful admission. Its
FIFO order is enqueue order, not wall-clock creation time. Registry revisions and
usage values describe the producer's domain order; subscribers never use render
time as host activity time. They retain responsibility for the existing aggregation
and selection/expansion rules, including stable host-name tie-breaking.

The registry must enqueue causally dependent observations in mutation order under
its own synchronization, using only the broker's short admission operation. No
subscriber executes under that lock. The implementation may use an equivalent
bounded ordered hand-off, but must not create an unbounded pending-batch queue.

Two current hazards require regression tests in #100:

1. `getOrCreateGraph` can collect `ResourceAdded` before `RootAdded` for a new
   non-root associated resource. The new contract requires root creation first.
2. Pending hook batches are fired after releasing the registry lock. Concurrent
   mutations can therefore publish in a different order from their state changes.
   Broker FIFO cannot repair a batch already enqueued out of order.

No update may resurrect an evicted graph. Stale fetch attempts must not overwrite
a newer attempt. Full resource snapshots make updates unambiguous; they do not
permit arbitrary lifecycle-event loss.

### Broker admission, delivery and subscriptions

`publish` performs bounded validation/copying and short queue coordination, then
returns the accepted sequence or throws an explicit rejection. It never invokes subscribers, renders, performs I/O,
waits for capacity, or runs a rejected task on the caller. It is not an absolute
wait-free guarantee. Successful publication returns its sequence. Rejections throw explicit closed,
queue-full, oversize or previously-failed exceptions.
Only acceptance promises delivery under the healthy lifecycle contract below.

One worker takes accepted events in FIFO order and blocks on a condition/queue
while idle. For each event it snapshots eligible registrations in registration
order, releases broker synchronization, evaluates their filters and calls matching
handlers sequentially. Reentrant publication appends through ordinary admission;
it never dispatches recursively. Filters and handlers must return quickly.

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

### Approved bounded overload policy

The maintainer approved fatal application termination on 2026-10-19. Finite
memory and non-blocking publishers cannot promise lossless delivery during
sustained overload. YAME must stop rather than continue with an incomplete view.

Production defaults (constructor-overridable in isolated tests): 4,096 pending
events, 16 MiB accounted pending payload, 128 KiB per event, and 64 registrations.
One additional event may be in flight. Account fixed envelopes, UTF-16 strings,
and packet bytes before queue admission; the accounting is a conservative budget,
not an exact JVM heap measurement. Never truncate identities to fit.

On count or byte saturation, `publish` atomically rejects the event, latches a
bounded fatal record, wakes independent fatal waiters, and throws
`QueueFullException`. Its fields identify limits, occupancy, requested bytes,
last accepted sequence and active subscriber. The active subscriber is diagnostic
context, not proof of which subscriber is slowest. No timers or watchdogs are added.
An oversize event likewise causes fatal rejection with `EventTooLargeException`.
Further publication throws `BrokerFailedException`; there is no recovery or retry.

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
must subscribe during application startup. Adding a frontend after activity begins
with full-state expectations requires a separately designed registry-owned
snapshot-and-stream boundary: capture state and a revision atomically with stream
attachment, apply the snapshot before strictly newer revisions, and bound its
buffering. Calling today's `snapshots()` and then subscribing has a race and is
not a supported bootstrap. This restriction satisfies #92's conditional bootstrap
scope without prescribing a generic state store or durable replay.

### Diagnostics and logging

Keep the existing module log and capture paths independent of observation admission.
The runtime-owned diagnostic tee sends each existing YameEvent to
`YameLogManager.eventSink` once, then publishes it to observation consumers. Keep
existing configured severity, module routing, capture retention and capture queue
failure behavior. Avoid a second logging subscription that writes duplicates.
The tone-test path and plain mode use the same policy where applicable.

This deliberately preserves existing synchronous module-log I/O before broker
admission. `publish` and subscriber dispatch remain I/O-free; this proposal does
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
| [#97: inventory and contract](https://github.com/skasti/yame/issues/97) | None | Source-grounded inventory; explicit overload trade-off, startup/bootstrap restriction and diagnostic independence; valid documentation links. |
| [#98: broker and facade](https://github.com/skasti/yame/issues/98) | #97; explicit overload-policy approval | Record the selected policy and trade-offs before implementing overload behavior. Verify bounded memory, non-blocking publishers and no silent loss of lifecycle-critical events; adapt overload/recovery tests to that decision. Remaining checks: FIFO with multiple producers, blocked subscriber and full byte/count capacity, oversize payload, safe status reads, filtering/failure/reentrant publication, concurrent subscribe/remove, idle wakeup, drain high-water mark, publish/close race and close from worker. Verify fatal signalling independent of worker progress and caught exceptions, with no recovery. |
| [#99: registry lifetime](https://github.com/skasti/yame/issues/99) | None | Runtime-level serial/handler replacement retains graph identity and LRU limits; stale fetch attempts remain fenced. Define limit changes without session-teardown eviction. |
| [#100: proxy event contract and publication](https://github.com/skasti/yame/issues/100) | #97 | Immutable snapshots, root-before-resource, mutation/publication order, full add/update/remove/reuse/eviction coverage, shared resources, fetch attempts, prefetch and transformations. Reconstruct retained state from a healthy stream. Hooks remain temporarily usable. |
| [#101: TUI processing boundary](https://github.com/skasti/yame/issues/101) | #97 | Blocking terminal/render work cannot hold up admission; bounded local backlog, independent fatal escalation on overflow, consistent observation order, drain and close behavior. Works with existing events/hooks before broker wiring. |
| [#102: runtime composition and migration](https://github.com/skasti/yame/issues/102) | #97, #98, #101 | Plain/TUI startup receive initial observations, diagnostics/captures remain independent and are written once, no broker-worker I/O, old/new session ordering and full shutdown sequence. No unsupported late-consumer completeness claim. |
| [#103: proxy dashboard migration and hook removal](https://github.com/skasti/yame/issues/103) | #99, #100, #102 | Existing hosts/unique URLs/states/transforms/sort/selection/expansion remain correct; reconnect and eviction work; no outbound registry hook plumbing remains; an independent early consumer reconstructs an equivalent view. |

Use latches, barriers, controllable executors and explicit drain boundaries, with
timeouts only as failure guards. Do not use arbitrary sleeps or reset shared global
state between tests. FIFO/causality checks assert event values and order, not
elapsed-time coincidences. Test count and byte limits with small injected capacities.
Run the relevant JVM build/integration checks for implementation changes, following
[AGENTS.md](../../AGENTS.md).

The broader component reuse/reset design is
[#104](https://github.com/skasti/yame/issues/104). It classifies process-, connection-
and session-lived dependencies and defines safe reset/quiescence. #92 only needs
the narrow registry ownership correction in #99; the broader refactor does not
block this migration.
