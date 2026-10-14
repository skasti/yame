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
store contracts contain no feature imports. `ConfigurationBinding` and
`applicationConfigStore` adapt feature definitions to the existing INI schema at
the application composition boundary.

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

All existing TUI settings use the store. The specialized transformer control is
now only a read-through request snapshot adapter, with no second mutable state
and no palette lifecycle. Leaf transformers never construct palette items.
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
is the process store installed by the composition root; standalone use starts with
application defaults. Element-simplifier rules are a read-only startup setting
loaded from the existing separate INI file; runtime writes are rejected.
