# YameEvents v1 design proposal and implementation inventory

Status: **design proposal; not implemented**, for
[#97](https://github.com/skasti/yame/issues/97), part of
[#92](https://github.com/skasti/yame/issues/92). The implementation inventory below
describes `main` at
[`a37ac65`](https://github.com/skasti/yame/commit/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19).
It is a historical snapshot, not authoritative documentation of later revisions.
The proposed v1 contract is separate from that inventory; normative wording within
the proposal describes intended behavior, not existing implementation.

**Open decision requiring approval:** terminal `FAULTED` behavior on saturation
or oversize payload, including restart-only recovery, must be explicitly approved
before implementation in [#98](https://github.com/skasti/yame/issues/98). Merging
this documentation does not approve that policy. The overload section retains it
as one candidate alongside alternatives for review.

Once the event system is implemented, introduce `docs/yame-events.md` based on
the verified implementation, including deviations from this proposal. Update this
document's status and link to that current architecture documentation; retain the
design only as historical context.

## Implementation inventory at revision a37ac65

The current-behavior column describes the existing codebase at the revision linked
above. The final column explicitly separates proposed migration changes. Source
links in this inventory are pinned to that revision; other repository links are
relative to this document. Class and method names identify the relevant boundary
without depending on shifting line numbers.

| Current path | Owner and current behavior at `a37ac65` | Proposed v1 change (not implemented) |
| --- | --- | --- |
| `eventSink: (YameEvent) -> Unit` | [PppSession](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/session/PppSession.kt) emits DNS, transfer and unhandled-packet observations. [SystemRoutingTcpProxy](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/SystemRoutingTcpProxy.kt) and [SystemHttpCompatibilityHandler](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/CompatibilityHttpHandler.kt) emit `HttpProxyAction`. HayesModem and RetroPppHandler pass the sink to children. | Keep the existing observation types, publish through the broker, and add a connection/session identity where needed to prevent ID reuse across reconnect. |
| Runtime event fan-out | [YameRuntime.startConnection](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/runtime/YameRuntime.kt) synchronously calls `logManager.eventSink(event)` and then `observer.onEvent(event)`. Producer wrappers commonly use `runCatching`, so throwing from a sink is not a reliable failure signal. | Replace observer fan-out with broker subscriptions. Preserve the independent diagnostic route described below; admission failure is an explicit result and sticky status, not an exception hidden by `runCatching`. |
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

## Proposed v1 contract (not implemented)

The following sections specify the proposed design. The overload policy remains
an open decision requiring explicit approval before #98. Existing implementation
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
subscriber watchdog or broker-managed per-subscriber queue in its baseline
candidate; bounded isolation is an alternative still under discussion below.

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
returns an explicit result. It never invokes subscribers, renders, performs I/O,
waits for capacity, or runs a rejected task on the caller. It is not an absolute
wait-free guarantee. Illustrative results are `Accepted(sequence)`,
`RejectedClosed`, `RejectedOverload`, `RejectedOversize`, and `RejectedFaulted`.
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

### Proposed bounded overload policy — approval required

**Decision status: open, not accepted.** The terminal-fault policy below is a
candidate for review before #98, not an implementation requirement accepted by
merging this document. References to `FAULTED`, restart-only recovery, fault-aware
drain/close, and related verification elsewhere in this proposal are conditional
on this decision. If another policy is approved, update those sections and the
implementation checks accordingly.

The requirements remain bounded memory usage, non-blocking publishers, and no
silent loss of lifecycle-critical events. One slow or blocked consumer on the
shared worker can fill the queue; under this candidate, that indirectly makes the
shared event stream unavailable to every consumer until application restart.
Protocol progress and independent diagnostics continue, but live observations do
not. This availability cost needs explicit approval.

Reasonable alternatives to evaluate in #98 include:

| Candidate | Trade-off and design work required |
| --- | --- |
| Terminal shared-stream fault (detailed below) | Simple bounded failure semantics and a valid accepted prefix, but one overloaded consumer can stop observations for all consumers until restart. |
| Bounded per-consumer isolation | Isolate an overflowing consumer and explicitly mark its view incomplete while healthy consumers continue. Requires bounded dispatch/queues, consumer lifecycle rules, and an explicit recovery or resubscription contract; isolation alone cannot guarantee that the shared ingress never saturates. |
| Bounded resynchronization | Mark an affected view incomplete, then recover through an authoritative snapshot plus revision-aligned stream attachment. Requires a proven bounded snapshot/buffering boundary and explicit behavior if recovery cannot keep up. |
| Separate critical delivery from coalescible observations | Reserve bounded capacity for lifecycle-critical events and coalesce only values proven safe to replace. Requires event classification and ordering semantics; reserved capacity can still fill, so an explicit exhaustion/recovery policy remains necessary. |

None of these alternatives is selected here. Blocking publishers, unbounded
queues, or silently dropping lifecycle-critical transitions do not satisfy the
requirements.

Finite memory, non-blocking publishers and lossless delivery cannot all be
guaranteed under sustained overload or an indefinitely blocked subscriber. The
terminal-fault candidate prioritizes finite backlog and protocol progress, and
makes loss of stream completeness an explicit terminal fault. This is a deliberate limit of
#92's lifecycle correctness guarantee: a healthy uninterrupted stream is complete;
a faulted stream supplies only a valid accepted prefix and must not be presented
as a complete current view. No recovery of the missing transitions is promised.

Proposed production defaults, overridable in an isolated broker's constructor:

| Budget | Default |
| --- | --- |
| Pending event count | 4,096, plus at most one event currently being dispatched |
| Pending accounted payload | 16 MiB, plus at most one in-flight event |
| Single event accounted payload | 128 KiB |
| Active registrations | 64; excess registration fails explicitly without affecting existing registrations |

Account strings conservatively by UTF-16 storage plus a fixed value/entry allowance,
byte arrays by length, and every collection item and envelope by an explicit
overhead allowance. Check the budget before making defensive copies. #98 must
document and test its accounting function; the byte limit is an accounted payload
budget, not a claim about exact JVM heap consumption. Fixed event shapes and
collection/registration limits must also bound object counts. Never truncate an
identity or lifecycle payload to fit. A packet fits within the existing 65,535-byte
capture bound; HTTP bodies and mutable transport objects do not enter this queue.

This candidate uses one queue and no coalescing. In particular, `TransferBytes`
is currently a delta, and dropping it would corrupt totals. Graph usage, add/remove
and intermediate resource states are likewise not silently replaceable telemetry.

Under the terminal-fault candidate, at the first count/byte saturation or oversize
payload:

1. Atomically reject that publication, preserve the already accepted FIFO prefix,
   and latch `FAULTED` status with reason and last accepted sequence. Later
   publications return `RejectedFaulted`. There is no retry/spin/block path.
2. Store one fault notification outside queue capacity. It is broker delivery
   metadata, not a recursively published domain event, and cannot be crowded out.
   Once the worker finishes the accepted prefix, notify registrations of the fault
   in registration order through their fast delivery-status callback. Do not
   automatically unregister consumers. The worker then waits for close.
3. Make the sticky status readable independently of worker progress. A permanently
   blocked handler can delay its fault callback; a frontend's own refresh loop must
   still be able to see `FAULTED` and mark the projection stale. Neither status reads
   nor fault reporting acquire a rendering lock or perform I/O on the publisher.
4. Let protocol operation and the independent diagnostic route continue. Consumers
   must retain/label their last view as stale or clear it with an explicit failure
   indication. They must not apply later observations to a hidden gap.

A full application restart is this candidate's recovery path for a faulted stream.
Serial reconnect alone does not repair it. There is no live broker replacement, automatic
consumer cancellation, slow-subscriber timing/watchdog, or silent reset of graph
state. This capacity-triggered broker fault is distinct from automatically disabling
a slow subscriber. Consumer isolation and bounded resynchronization are alternatives
for the overload decision, not part of this candidate. Review must explicitly
select a policy before implementing it in #98; an unbounded queue is not the fallback.

TUI processing uses its own bounded queue with the same proposed count/byte limits
and a sticky *local* failure indicator. Its admission must never take the lock used
for projection/rendering. On local overflow it stops applying further events and
marks its view stale; it does not fault the shared broker or affect other consumers.
The frontend refresh path checks broker/local health without waiting for queue space.
Consumers may share immutable event values, but their queues and local state have
separate documented bounds. Moving the backlog to an unbounded executor is unsafe.
The TUI may apply an ordered batch of events and coalesce render requests; this
avoids a full terminal redraw per event without discarding observation values.

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
Drain results include fault status. Each consumer exposes its own drain boundary.

`close()` is idempotent and requests graceful stop: atomically stop admission, wake
an idle worker, deliver the accepted prefix and any latched fault, then terminate.
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

All rows below are future implementation checks. This documentation PR changes no
runtime behavior and does not claim these tests already exist. Overload-specific
checks are conditional on the policy explicitly approved before #98; terminal
fault behavior and restart-only recovery are not accepted by this map.

| Issue / independently reviewable PR | Dependencies | Required evidence |
| --- | --- | --- |
| [#97: inventory and contract](https://github.com/skasti/yame/issues/97) | None | Source-grounded inventory; explicit overload trade-off, startup/bootstrap restriction and diagnostic independence; valid documentation links. |
| [#98: broker and facade](https://github.com/skasti/yame/issues/98) | #97; explicit overload-policy approval | Record the selected policy and trade-offs before implementing overload behavior. Verify bounded memory, non-blocking publishers and no silent loss of lifecycle-critical events; adapt overload/recovery tests to that decision. Remaining checks: FIFO with multiple producers, blocked subscriber and full byte/count capacity, oversize payload, safe status reads, filtering/failure/reentrant publication, concurrent subscribe/remove, idle wakeup, drain high-water mark, publish/close race and close from worker. If the terminal-fault candidate is approved, also verify fault notification without a queue slot and restart-only recovery. |
| [#99: registry lifetime](https://github.com/skasti/yame/issues/99) | None | Runtime-level serial/handler replacement retains graph identity and LRU limits; stale fetch attempts remain fenced. Define limit changes without session-teardown eviction. |
| [#100: proxy event contract and publication](https://github.com/skasti/yame/issues/100) | #97 | Immutable snapshots, root-before-resource, mutation/publication order, full add/update/remove/reuse/eviction coverage, shared resources, fetch attempts, prefetch and transformations. Reconstruct retained state from a healthy stream. Hooks remain temporarily usable. |
| [#101: TUI processing boundary](https://github.com/skasti/yame/issues/101) | #97 | Blocking terminal/render work cannot hold up admission; bounded local backlog, local/broker fault display, consistent observation order, drain and close behavior. Works with existing events/hooks before broker wiring. |
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
