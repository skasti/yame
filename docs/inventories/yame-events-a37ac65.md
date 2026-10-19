# YameEvents implementation inventory at revision a37ac65

Historical snapshot of `main` at
[`a37ac65`](https://github.com/skasti/yame/commit/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19),
prepared for [#97](https://github.com/skasti/yame/issues/97). This is not current
architecture. See the [decided design](../design/yame-events-v1.md) and
[implemented broker](../yame-events.md) for their respective scopes.

The current-behavior column describes the existing codebase at the revision linked
above. The final column explicitly separates migration changes. Source
links in this inventory are pinned to that revision; other repository links are
relative to this document. Class and method names identify the relevant boundary
without depending on shifting line numbers.

| Current path | Owner and current behavior at `a37ac65` | Migration target (not behavior at this revision) |
| --- | --- | --- |
| `eventSink: (YameEvent) -> Unit` | [PppSession](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/session/PppSession.kt) emits DNS, transfer and unhandled-packet observations. [SystemRoutingTcpProxy](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/SystemRoutingTcpProxy.kt) and [SystemHttpCompatibilityHandler](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/ppp/proxy/CompatibilityHttpHandler.kt) emit `HttpProxyAction`. HayesModem and RetroPppHandler pass the sink to children. | Keep the existing observation types, publish through the broker, and add a connection/session identity where needed to prevent ID reuse across reconnect. |
| Runtime event fan-out | [YameRuntime.startConnection](https://github.com/skasti/yame/blob/a37ac65afa851fe0fca8f4a179b680d6c3b6bb19/src/main/kotlin/no/skasti/yame/runtime/YameRuntime.kt) synchronously calls `logManager.eventSink(event)` and then `observer.onEvent(event)`. Producer wrappers commonly use `runCatching`, so throwing from a sink is not a reliable failure signal. | Replace observer fan-out with broker subscriptions. Preserve the independent diagnostic route in the [migration design](../design/yame-events-v1.md#diagnostics-and-logging); admission failure throws and latches an independent fatal signal, so `runCatching` cannot hide it from lifecycle supervision. |
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
usage on delivery. The migration design moves those delivery-time calculations to
authoritative event data so consumers agree even when their queues run at
different speeds.

## Existing local callbacks and migration boundary

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

## Registry ordering hazards at this revision

1. `getOrCreateGraph` can collect `ResourceAdded` before `RootAdded` for a new
   non-root associated resource. The migration requires root creation first.
2. Pending hook batches are fired after releasing the registry lock. Concurrent
   mutations can publish in a different order from their state changes. Broker
   FIFO cannot repair a batch already enqueued out of order.

[#100](https://github.com/skasti/yame/issues/100) covers regression tests for these
hazards under the [decided ordering contract](../design/yame-events-v1.md#event-values-and-producer-ordering).
