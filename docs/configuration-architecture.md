# Configuration and runtime functions

Each YAME process owns one `ConfigStore` and one `FunctionStore`. `YameRuntime`
owns serial/modem behavior, registers its operations, and observes committed
configuration. Both plain mode and the TUI compose this same runtime. Stores,
keys, functions and runtime have no presentation imports. A second frontend can
use the same instances; each TUI owns its own command palette. An external client
needs a separate transport/authentication bridge module, not a different core API.

## Typed settings and ownership

`SettingKey<T>` is invariant and has a member `to(T)` operator. Consequently
`store.set(SerialSettings.Baud to 9600, ModemSettings.Tone to false)` compiles,
whereas passing `SerialSettings.Baud to "9600"` to `set` is rejected. `ConfigChanges[key]` returns a
typed `ConfigChange<T>?`, including for nullable settings, in average O(1) time.
Iteration preserves the caller's batch order. Keys use identity; registering or
writing duplicate paths is rejected, including an unrelated key with the same path.

Concrete keys and groups live in their owning serial, modem, PPP, DNS, proxy,
transformer and logging packages. Baud belongs to serial, not modem. Credentials
are one modem-owned `LoginCredentials` setting because username/password form one
validated concept; the existing two INI properties remain compatible. Generic
store contracts contain no feature imports. The `persistence` package owns `YameConfiguration`, its file store,
`ConfigurationBinding`, `YameConfigurationBindings`, and `applicationConfigStore`.
All persistence mappings point from this application adapter toward feature keys;
feature `*Settings` objects contain no bindings or application-schema imports.
`YameConfiguration` is used only during startup/CLI parsing and file persistence.
It reuses immutable feature values as sibling fields without putting PPP, DNS, or
HTTP configuration inside the modem config. The existing `yame.ini` property names,
version, defaults, CLI precedence, credential permissions, and safe replacement
semantics remain unchanged.

## Transactions, persistence and CLI precedence

`set(vararg changes)` validates all registered keys and the complete candidate
configuration, then saves the persistent candidate once using
`YameConfigurationStore`'s prepared, permission-restricted temporary file and safe
replacement. Only after a successful save are both snapshots published. Validation
or persistence exceptions reach the caller; neither in-memory state nor observers
are updated on rejection. The TUI reports the failure without displaying input
values. No-op batches skip both saving and notifications.

Persistent defaults/file values and effective runtime values are separate layers
inside the same store. CLI values override only the effective startup snapshot.
Writing an unrelated setting cannot persist CLI credentials, ports, baud or other
overrides. An explicit `set` promotes only its named settings to the persistent
baseline; selecting an already effective CLI value can therefore save it without
an effective-state event. `setTransient` changes only the effective layer and
never saves. Automatic single-port discovery uses this path.

The commit lock serializes validation, persistence and publication. A slower older
save cannot overwrite a newer commit. File IO is synchronous and settings updates
are not intended for protocol hot paths. The existing filesystem fallback when
atomic moves are unsupported remains unchanged; crash durability is subject to
the existing filesystem/store guarantees.

## Notifications and lifecycle

Subscribe with `store.onChange[group].subscribe { changes -> ... }` and close the
returned handle on shutdown. `+= handler` / `-= handler` are also supported.
Global listeners use `store.onChange.subscribe`. A cross-group transaction is
fully committed before delivery; each group receives one precomputed indexed view
containing only its changed keys. Global listeners receive the whole batch.

Delivery follows commit order, with listeners in registration order. One drainer
processes notifications outside the commit lock. Reentrant writes append their
events after the current batch. Concurrent writers may return while an existing
drainer still delivers their events; store reads already see the committed state.
A listener should use its event values to advance local state and should not
assume a later store read still represents precisely that event's transaction.
Slow listeners delay subsequent notifications but do not hold the commit lock.
Exceptions are reported to the configured failure sink and cannot roll back a
saved transaction or prevent other listeners from receiving it.

Use `subscribe(initial = true)` to initialize **from the callback**, rather than
reading before subscribing. Registration and enqueueing the initial immutable
snapshot occur under the commit lock, so later events cannot overtake it. Initial
delivery may be queued behind an existing drainer. Closing a subscription suppresses
queued deliveries; an already executing callback may finish. Modules own their
subscriptions and function registration handles.

Sensitive credentials are available only to trusted in-process consumers.
`ConfigChange.toString` and `LoginCredentials.toString` omit values; bridges and
diagnostic adapters must respect `key.sensitive` and explicitly filter secrets
before exporting configuration. Neither the TUI nor subscriber error logging prints
credential values.

## Current setting effects and remaining startup policy

| Owner / settings | Runtime effect |
| --- | --- |
| Serial port, baud, flow control | Restart serial/modem connection, once per batch |
| PPP subnet, DNS upstream, proxy enabled | Restart connection to rebuild session dependencies |
| Modem pickup/dial-tone durations and handshake profile | Restart connection with the new modem configuration |
| Modem tones | Update the active modem immediately |
| Modem login credentials | Apply to the next call; an established call keeps its credentials |
| HTML transformer switches | Read one consistent snapshot per request; reload the page, keep PPP connected |
| Log levels and capture limits | Apply immediately |
| `element-simplifier.ini` rules | Existing separate startup file; edit file and restart |
| Config/log paths, UI mode, tone-test mode | CLI startup policy, not runtime persistent settings |
| Internal proxy bounds/timeouts and transformation implementation policy | Constructor policy; no new runtime configuration surface |

All existing TUI settings use the store. `HtmlTransformerControl` has been removed.
The runtime injects a `() -> HtmlTransformerSettings` provider through the PPP/proxy
composition into the HTTP handler. The handler invokes it once at request entry,
retaining that immutable snapshot for redirects, resource work, and profile-specific
cache identity. Live HTML responses disable browser validator reuse. Without a
provider, explicitly supplied `PppHttpCompatibilityConfig.htmlTransformers` remains
fixed and retains the ordinary conditional-request behavior. Providers never live
inside config values and do not own mutable settings. Leaf transformers never
construct palette items.
Runtime observations (connections, transfers, logs and resource graphs) remain
separate from settings and functions; their event unification is separate work.

## Functions

`FunctionKey<A, R>` gives a stable path with typed arguments/results.
`register(key) { argument -> result }` returns an idempotent cleanup handle and
rejects duplicate paths. `invoke` checks key identity, selects the operation under
the registry lock, and executes outside it. Function exceptions propagate to the
adapter. A function may call `ConfigStore.set` for a multi-setting transaction.
Unregistering removes future invocations; an already selected operation may finish.

The runtime registers disconnect, reconnect, refresh-ports and quit. Palette
navigation and clearing presentation history remain presentation responsibilities.
No core component knows about a palette, GUI toolkit or network transport.

## Component configuration snapshots

Component configuration follows the pattern in `AGENTS.md`: a local immutable
config type exposes `fromConfigStore()`, and its component accepts that type as a
non-null constructor argument defaulting to the factory call. Explicit arguments
bypass global configuration entirely. A factory maps a single committed snapshot;
it does not subscribe or introduce another mutable authority. `ConfigStore.current`
fails fast until `Main` installs a process store. `install` returns an idempotent
lifetime handle, rejects an existing owner, and releases the store on close.
`Main` closes that handle after the runtime and frontend. Multiple frontends take
shared store instances; they do not replace global state. Explicit constructor
configurations never require installation, including the modem's standalone PPP
child defaults. Element-simplifier rules are a read-only startup setting
loaded from the existing separate INI file; runtime writes are rejected.

## Ownership and composition boundaries

| Package / component | Owns / depends on |
| --- | --- |
| `config`, `function` | Typed generic stores, changes, subscriptions and operations; no concrete features or persistence |
| `serial`, `modem`, `ppp`, `ppp.dns`, `ppp.proxy`, `logging` | Their own keys, groups, value validation and immutable config snapshots |
| `ppp.proxy.transform` | HTML switches, element rules/INI, transformation policy and implementation |
| `persistence` | Legacy application file representation, all key bindings, autosave adapter and file IO |
| `Main` | Load file, apply transient CLI precedence, create/install shared stores, choose frontend and startup retry policy |
| `YameRuntime` | Compose component configs from a consistent snapshot, observe committed batches, own connections and registered functions |
| TUI | Derive presentation caches from the store; display runtime observations separately; invoke core functions |

Runtime and TUI initialization read one store snapshot and subscribe with initial
delivery to close the read/subscribe race. Runtime accumulates ordered event values
in a derived immutable snapshot, then uses each feature's `fromSnapshot` mapper to
construct connection dependencies. No persistence bindings or legacy DTOs enter
this path. A cross-feature batch restarts the connection once. Logging values and
modem tones/credentials update incrementally. Runtime caches are derived views;
configuration writes always go through ConfigStore.

The intentional cross-feature dependencies are composition relationships:
`YameRuntime` orchestrates independent features; `RetroPppHandler` composes addressing,
DNS and TCP/HTTP services; `PppSession` consumes DNS config because it currently
implements local DNS forwarding and DNS TCP routing; the HTTP proxy owns child
transformation configuration;
`HayesModem` depends on a `PppHandler`, whose standalone default uses ordinary child
config defaults. `HayesModem` also receives the serial baud value explicitly to report the correct
CONNECT rate. These do not make modem settings an application config tree.
`PppHttpCompatibilityConfig` retains immutable transformation values because the
HTTP handler owns their use in response preparation. Application persistence reuses
feature value types rather than introducing duplicate file-only versions of them.
There are no feature-to-persistence dependencies or obsolete compatibility wrappers.

Plain mode propagates initial connection construction/open errors so scripts exit with a
failure. TUI mode logs failed attempts and permits retry. Every core logger,
including HTTP proxy diagnostics, reaches the runtime observer as well as the
module file logger. Both modes share this routing and the same runtime functions.
Reselecting unchanged port, baud, or flow-control settings commits normally and
invokes `Reconnect` separately when the effective value is unchanged. This preserves
ConfigStore's no-op save/event semantics and the existing selection interaction.

`SerialTransport` is the serial IO boundary implemented by `SerialConnection`.
The runtime owns and closes it, including after failed opens; tests can inject a
port without requiring serial hardware or process-global configuration.
