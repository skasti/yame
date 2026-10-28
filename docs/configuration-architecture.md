# Configuration and runtime functions

Each YAME process owns one `ConfigStore` and one `FunctionStore`. `YameRuntime`
owns serial/modem behavior, registers its operations, and observes committed
configuration. `Main` composes this same runtime for plain mode
and the TUI. Stores, keys, functions and runtime have no presentation imports.
A second frontend can use the same instances; each TUI owns its own command palette. An external client
needs a separate transport/authentication bridge module, not a different core API.

## Application startup and shutdown

`Main` loads configuration, applies CLI overrides, explicitly installs the process
`ConfigStore`, and creates shared logging and event supervision. It selects the
presentation using the existing `--ui auto|tui|plain` terminal policy. Both normal
modes follow the same direct sequence in `Main`, without an additional bootstrap
abstraction:

1. Construct the selected `Presentation`, installing its event subscriptions, and
   register it as a lifecycle consumer before calling its nonblocking `start()`.
   The TUI owns its bounded FIFO worker, render timer and input thread; plain diagnostics need no loop.
2. Construct `YameRuntime`, which observes configuration but exposes no runtime
   functions, and immediately register it as a lifecycle producer.
3. Call `run()`, which claims startup once and registers the runtime functions
   before port discovery, connection attempts and monitoring. Repeated or
   concurrent `run()` calls fail without disturbing the first invocation.
   Core components also remain usable
   without a presentation.
4. On return or failure, use `ApplicationEventLifecycle` to stop runtime producers,
   close and drain the broker, then close presentation subscriptions and drain
   accepted TUI work before terminal teardown,
   release the configuration installation and close logging. A shutdown hook and
   fatal-event supervisor use that same idempotent owner.

Production presentation/runtime construction omits both stores. Constructors for
`InteractiveYameApplication`, `TuiYameObserver` and `YameRuntime` default to
`ConfigStore.current` (the explicitly installed process store) and
`FunctionStore.current` (the lazy process-owned registry from
[ADR 0009](adrs/0009-lazy-global-function-store.md)). Main creates no function
store. Explicit constructor arguments bypass the defaults independently; a
component retains those instances for its operations and passes them to children
when composing them. It never replaces an injected store with a global lookup.

`InteractiveYameApplication` owns only `TuiYameObserver` and its subscription.
Neither the TUI nor plain diagnostics owns a runtime or resource registry.
Presentation initialization cannot start runtime producers. Input is enabled
before runtime construction, with no readiness barrier. Unavailable functions
still throw from `FunctionStore.invoke`; the TUI catches command failures for
palette actions and shortcuts, reports them locally, and keeps accepting input.
A failed quit leaves input enabled so a later user action can succeed. Palette
population remains unchanged; dynamic store-driven population is future work.
Core construction/startup failures release already
created components. A component whose lifecycle registration loses a shutdown
race is closed immediately, rather than escaping ownership. See
[ADR 0008](adrs/0008-presentation-independent-bootstrap.md).
`run()` closes the runtime on return or failure. Partial function registration
is rolled back by that same cleanup, including the configuration subscription.
Concurrent startup/shutdown serializes registration with closing, so no handles
can be added after shutdown has removed them.

Runtime commands delegate directly to their implementations. Those methods
validate component state and reject external calls when runtime is stopping or
closed. `runtimeOperation` is removed. Internal monitoring, stale callbacks and
cancelled connection work instead stop quietly during normal shutdown.

`pendingTransitions` counts resource work: OS port enumeration, connection
initialization and detached serial/modem cleanup. These owners claim work under
the runtime lock, perform IO outside it, and release their count after cleanup and
publication. A connection stays local to its initializer through reader startup;
only a current generation may hand it to the runtime. Reader callbacks record
early termination during initialization or take ownership of active cleanup,
and stale callbacks cannot reclaim already detached resources. Serial readers
are stopped before their modem is released.

Close rejects new commands, removes function/configuration registrations, fences
connection generations, detaches active IO, and waits for resource work and the
monitor to quiesce. Concurrent external close callers await the same completion.
A tracked IO callback cannot initiate close and wait for itself; that invocation
fails explicitly. Reentrant cleanup callbacks during an existing close return
without blocking its owner. The application supervisor's existing deadline still
bounds shutdown if external IO never completes; shared consumers are not released
while runtime cleanup is still waiting.

`YameRuntime` and lower-level core components retain explicit registry injection.
Their production defaults resolve the lazy, thread-safe process registry through
`YameRegistries`; reconnects retain that instance and its contents. No registry
resolution passes through presentation. Tone test remains a standalone modem
path with plain diagnostics, and plain mode without a port still prints usage.

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
validated concept; its two child INI properties live under `[modem]` as `login.username` and `login.password`. Generic
store contracts contain no feature imports. Each feature's `Settings` owns its
identity, optional logical file name, static sections, lazily discovered typed keys, and optional
custom mapping. `Settings` defines ownership, while `ConfigStore` holds current
values and immutable component `*Config` objects carry values into components;
component configs do not inherit `Settings`. The generic store contains no central
list of keys.
A direct `store[key]` read requests the definition attached to the key's group,
loading that definition on first use. Component `fromConfigStore()` factories
read only their owned keys through the supplied store.
The `persistence` package supplies the generic INI repository and a store factory.
Startup and CLI parsing use the same typed store as runtime and presentation.
INI ownership lives in `IniConfigurationRepository`, scoped to its store. Each
logical `(file, section, property)` belongs to at most one `SettingKey`, including
conventional keys, composite children and dynamic rule attributes. Two keys may
share a section when their properties differ. A section property and its equivalent
root-flat property are one logical location. Missing optional properties can also
be reserved by their key, so a later definition cannot claim them accidentally.
Reads stage claims until the entire definition and typed candidate pass validation;
failed loads can be retried without partial ownership. `compose(key, section)`
exposes attribute names without claiming them, claims only attributes accessed by
`get`, and discards those claims if it returns null or throws. Writes are key-scoped:
`writeAttributes` checks each mutation, while `removeSection` refuses to erase
unknown or differently owned properties. Dynamic rule sections are removed only
when `Rules` had previously claimed their attributes. Each affected document is
prepared and validated before the first replacement; an actual filesystem failure
in a later replacement still reports partial disk state while the typed store
remains uncommitted.
`ConfigStore` is the sole configuration state holder. CLI flags become typed
transient changes, validated and applied as one batch after parsing. Invocation
options contain only mode, tone-test number, port-listing choice and log directory.
`SerialConfig` groups transport values; `LoggingConfig` groups initial log levels
and capture limits. Both are immutable feature-owned values read from keys, with
no retained store or subscriptions. Hayes, PPP, DNS and proxy values remain
independent. Section-based `yame.ini` names are owned by feature definitions.
Version checks, CLI precedence, credential permissions, and safe replacement remain.
The repository restricts every existing INI file to owner-only POSIX permissions
when reading it and writes replacements with the same permissions. A file may
contain a sensitive setting from a feature that has not been lazy-loaded, so
permission decisions do not depend on enumerating loaded keys or on hardcoded
credential paths. `SettingKey.sensitive` remains metadata for redacting values
in trusted in-process adapters and diagnostics.
`ElementSimplifierSettings.Enabled` in `[element-simplifier.general]` is a general
gate for the rule file. The element simplifier runs only when this and the live
`TransformerSettings.Elements` switch in `[transformers]` are both enabled.
The effective switch is read for each new HTTP request and participates in its
transformation profile. External edits to either INI file still require restart.

## Transactions, persistence and CLI precedence

`set(vararg changes)` validates registered keys and the internal candidate state,
then persists changed keys through the repository's prepared, permission-restricted
temporary file and safe replacement. Only after a successful save are the persistent
and effective states published. Validation
or persistence exceptions reach the caller; neither in-memory state nor observers
are updated on rejection. The TUI reports the failure without displaying input
values. No-op batches skip both saving and notifications.

Persistent defaults/file values and effective runtime values are separate layers
inside the same store. CLI values override only the effective startup values.
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
reading before subscribing. Registration and enqueueing the initial notification
occur under the commit lock, so later events cannot overtake it. Initial
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
| HTML transformer switches | Construct immutable settings for each request; reload the page, keep PPP connected |
| Log levels and capture limits | Apply immediately |
| `element-simplifier.ini` rules | Can be saved through the store; external file edits take effect after restart |
| Config/log paths, UI mode, tone-test mode | CLI startup policy, not runtime persistent settings |
| Internal proxy bounds/timeouts and transformation implementation policy | Constructor policy; no new runtime configuration surface |

All existing TUI settings use the store. `HtmlTransformerControl` has been removed.
The runtime injects a `() -> HtmlTransformerSettings` provider through the PPP/proxy
composition into the HTTP handler. The handler invokes it once at request entry,
retaining that immutable component settings object for redirects, resource work, and profile-specific
cache identity. Live HTML responses disable browser validator reuse. Without a
provider, explicitly supplied `PppHttpCompatibilityConfig.htmlTransformers` remains
fixed and retains the ordinary conditional-request behavior. Providers never live
inside config values and do not own mutable settings. Leaf transformers never
construct palette items.
Runtime observations remain separate from settings and functions. The proxy
resource registry now emits its producer-owned lifecycle contract; production
consumer registration, fatal supervision and broker shutdown are implemented
by application composition. Runtime connection, transfer and log observations
also use YameEvents. The proxy dashboard incrementally consumes public lifecycle
payloads through that same subscriber, with no registry hooks or snapshot reads;
see [proxy projection](yame-events.md#proxy-dashboard-projection).

## Functions

`FunctionStore.current` is a shared process default, created on first access using
Kotlin's synchronized `lazy`. Runtime and TUI constructors use it when no store is
supplied; `Main` does not create or thread a function store through startup.
Explicitly supplied stores bypass the global default, and `FunctionStore()` creates
an independent registry for tests or isolated callers. There is no installation,
replacement, or reset API. Components still own and close their registrations.
Unlike the explicitly installed `ConfigStore.current`, this in-memory default needs
no external initialization. Configuration and event lifecycles are unchanged.
See [ADR 0009](adrs/0009-lazy-global-function-store.md).

`FunctionKey<A, R>` gives a stable path with typed arguments/results.
`register(key) { argument -> result }` returns an idempotent cleanup handle and
rejects duplicate paths. `invoke` checks key identity, selects the operation under
the registry lock, and executes outside it. Function exceptions propagate to the
adapter. A function may call `ConfigStore.set` for a multi-setting transaction.
Unregistering removes future invocations; an already selected operation may finish.

Function registration exposes an operation; it does not guarantee execution in
every lifecycle state. The owning component validates its state, and the caller
handles failures. A selected runtime command can therefore throw
`IllegalStateException` while shutdown proceeds, even though lookup succeeded.
An unregistered function retains the ordinary `FunctionStore` lookup exception.

At the beginning of `run()`, the runtime registers disconnect, reconnect,
refresh-ports and quit. Construction registers none. Refresh-ports
returns an unmodifiable `List<SerialPortDescriptor>` snapshot used directly by the
existing port selector. Changed automatic observations independently publish
`AvailablePortsChanged`; unchanged lists are suppressed. Quit requests producer
stop, while application lifecycle cleanup runs outside the invoking UI/dispatcher.
Palette
navigation and clearing presentation history remain presentation responsibilities.
No core component knows about a palette, GUI toolkit or network transport.

## Component configuration values

Component configuration follows the pattern in `AGENTS.md`: a local immutable
config type exposes `fromConfigStore()`, and its component accepts that type as a
non-null constructor argument defaulting to the factory call. Explicit arguments
bypass global configuration entirely. Factories read typed keys directly from
`ConfigStore`; they do not subscribe or introduce another mutable authority.
Multi-key read snapshots are intentionally unsupported. YAME accepts the negligible
possibility that a value changes between individual reads. `ConfigStore.current`
fails fast until `Main` installs a process store. `install` returns an idempotent
lifetime handle, rejects an existing owner, and releases the store on close.
`Main` closes that handle after the runtime and frontend. Multiple frontends take
shared store instances; they do not replace global state. Explicit constructor
configurations never require installation, including the modem's standalone PPP
child defaults. Element-simplifier rules are a typed setting in a separate INI file
loaded from its separate INI file; runtime writes use the repository.

## Ownership and composition boundaries

| Package / component | Owns / depends on |
| --- | --- |
| `config`, `function` | Typed generic stores, changes, subscriptions and operations; no concrete features or persistence |
| `serial`, `modem`, `ppp`, `ppp.dns`, `ppp.proxy`, `logging` | Their own keys, groups, value validation and immutable component configs |
| `ppp.proxy.transform` | HTML switches, element rules/INI, transformation policy and implementation |
| `persistence` | Generic INI repository and store factory; feature definitions own mappings |
| `Main` | Load file, apply CLI precedence, install configuration, construct/register/start presentation before constructing/registering/running runtime; close the lifecycle owner in its outer finally |
| `ApplicationEventLifecycle` | Supervise fatal broker failure, own registered producers/consumers and order shutdown |
| `YameRuntime` | Construct component configs from direct store reads, observe committed batches, own connections and registered functions |
| Presentations | Own subscriptions/output/input workers; TUI derives caches from the store, projects event payloads and invokes core functions |

The CLI reads serial, modem, PPP, DNS, proxy enabled, and logging defaults for
normal startup; tone test uses serial, modem, and logging. Neither mode requests
HTML transformer or element rule definitions merely to parse options. The runtime
loads only the definitions it needs through typed reads. Runtime and TUI subscribe
with initial delivery to close the read/subscribe race. Runtime constructs immutable
component configurations from direct store reads when a connection is established;
the connection generation prevents an older attempt from replacing a newer one.
No persistence bindings or legacy DTOs enter this path. A cross-feature batch
restarts the connection once. Logging values and modem tones/credentials update
incrementally. TUI keeps presentation state, while configuration values remain
authoritative in `ConfigStore`.

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
including HTTP proxy diagnostics, writes its module file independently before
publishing `DiagnosticMessage` for the early plain/TUI subscriber. Producer-owned
domain events likewise retain an independent single-write diagnostic tee before
`YameEvents.publish`. `RuntimeObserver` has been removed. Both modes and tone-test
startup share application event supervision and bounded shutdown;
see [YameEvents](yame-events.md).
Reselecting unchanged port, baud, or flow-control settings commits normally and
invokes `Reconnect` separately when the effective value is unchanged. This preserves
ConfigStore's no-op save/event semantics and the existing selection interaction.

`SerialTransport` is the serial IO boundary implemented by `SerialConnection`.
The runtime owns and closes it, including after failed opens; tests can inject a
port without requiring serial hardware or process-global configuration.

## Lazy configuration definitions (issue #96)

A typed read such as `store[SerialSettings.Baud]` loads only the owning
`Settings` definition on first use. `Settings` discovers public `SettingKey`
properties (including keys in collections) once, lazily and thread-safely.
`settingKeys` and `compositeSettingKeys` partition that discovery. There is no
central feature registry, `IniBinding`, or codec registry. The group ID is the
canonical INI section; a key declares only its relative name and derives its
external path as `group.id + "." + name`.

`Settings.file = null` selects the primary file (`yame.ini` or `--config`).
`SeparateSettings("element-simplifier")` selects `element-simplifier.ini` beside
it. A logical file name has no extension, path separator, traversal, or non-ASCII
characters. Multiple definitions can share a file or section if they claim
different properties. The repository resolves files and performs all IO.

For each key, reads accept either `[serial]` with `baud=115200` or root-level
`serial.baud=115200`. Equal duplicates are accepted; different values fail with
a file and property diagnostic. Writes normalize only the affected owned
properties into sections and remove their flat equivalents. Unknown and
unregistered properties and comments remain untouched. Existing root properties
retain Java `Properties` escaping, separators, Unicode escapes, continuations,
comments, and last-declaration-wins decoding. Named sections use `key=value`
and reject duplicate sections/properties with file and line diagnostics. Modified
lines are rendered canonically; untouched lines retain their spelling and line
endings. Saving after an external edit merges the fresh file, while already
loaded values in memory are refreshed only on restart.

The parser never trims a section property's value: everything after its first
unescaped `=` is data, including leading/trailing spaces, tabs, additional `=`,
backslashes, `#`, and `;`. An empty value differs from a missing property.
Root-level properties intentionally follow Java `Properties` syntax: an
unescaped leading space after `=` or `:` is skipped, while `\\ ` preserves it;
backslash escapes and continuations are decoded. Section values are literal
and do not apply Java escape decoding. For example, `[modem]` with
`login.password= secret ` yields the exact password `" secret "`; the equivalent
root property is `modem.login.password=\\ secret `. No inline comments are
recognized in either representation. String deserialization preserves the
logical value exactly; numeric, boolean, enum, and duration deserialization
may trim surrounding whitespace. The writer keeps unrelated raw lines intact
and writes changed owned values in section form without losing whitespace.
ElementSimplifier's structural rule mapper trims its `target` and `style`
fields as domain-specific normalization, preserving its established rule syntax.

Ordinary scalar values use `kotlinx.serialization` with type metadata retained
for nullable and collection-declared keys. Custom scalar types supply standard
serializers. Durations specifically use `Duration.toString()` and
`Duration.parse()`, so `500ms` and `2s` are valid. A companion implementing
`CompositeSetting<T>` maps one logical key to child properties relative to that
key; `LoginCredentials` is `[modem] login.username/password`. ElementSimplifier
delegates static settings to `Settings` and maps dynamically named rule sections
through its override. A missing rule file uses bundled defaults; an existing
empty file has no rules. It preserves rule ordering and validation.

Historical names such as `http-https-proxy.enabled`, `ppp.dns.upstream`, and
`modem.pickup-time-nanos`, along with numeric nanosecond durations, are not
aliases for the new runtime format. Existing files using them need manual
updates. The only dual representation is the section and equivalent flat form
of a *new* key path.

A first request validates and installs a definition atomically without writing
or notifying. Concurrent readers load it once; a failed load installs nothing
and retries on the next read. `ConfigStore.set` validates a transaction, asks
the repository to save changed keys, and publishes it only after successful
persistence. Subscribers receive ordered changes outside the store lock.
Transactions spanning multiple files are not atomic across the filesystem:
`PartialIniWriteException` reports replaced files and the process should
restart to reconcile disk and memory.

### Startup and standalone stores

The aggregate configuration DTO and its binding/projection layer have been
removed; [ADR 0010](adrs/0010-component-owned-configuration.md) records the boundary.
Adding a key requires only its feature definition and consumers.
`lazyApplicationConfigStore(path)` constructs an empty store with an INI repository;
it contains no feature registry. Startup reads only the features needed by the
selected mode. Help and port listing use an in-memory store and do not open INI
files. Tone tests do not load PPP, DNS or proxy definitions unless a CLI flag
explicitly addresses one of those features. HTTP transformations load when their
owner requests them.

A store without a repository lazily supplies feature-key defaults. Explicitly
seeded values take precedence over these defaults, so tests and embedded callers
can inject just the settings they need without installing a process store. Writes
also request the owning definition before validation.

On first normal startup, `saveLoadedValues()` persists the requested features'
saved baseline, excluding CLI overrides. Unrequested defaults need no file entries;
features supply them when first read. The primary INI version marker and existing
section/key names are retained. Existing files are not rewritten on startup.
`ConfigStore.createFileIfMissing(definition)` delegates first-run file creation to
`IniConfigurationRepository`. The owner may provide an `initialIni` template;
otherwise its default values are serialized through the regular feature mapping.
Templates are bounded and validated through that mapping before writing. Creation
uses a permission-restricted temporary file and a move without replacement;
existing files, including empty or malformed files, remain untouched. This does
not register the definition, change live values or publish notifications.
`ElementSimplifierSettings` owns the bundled initial template. All user-file reads,
runtime edits and first-run creation use the common store/repository; the remaining
bundled-resource reader supplies standalone immutable defaults only.

Production bootstrap installs the store before CLI parsing and component
construction, including tone-test mode. Main uses `ConfigStore.current` for its
own reads; component constructors resolve optional config arguments from the
installed store. For example, `YameLogManager(logDirectory = path)` defaults its
`config` argument to `LoggingConfig.fromConfigStore()`. Explicit immutable configs
remain available for isolated tests. The installation is released on parse/load
failure and after lifecycle cleanup, with logging and presentation stopped first.
ToneTest owns the standalone modem and its forced tone-enabled configuration.
