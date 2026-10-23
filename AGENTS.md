# YAME (Yet Another Modem Emulator) contributor and review scope

YAME is a compatibility layer for DOS and Windows 3.1-era clients connected over
a slow serial PPP link. The primary browser target is Netscape Navigator 4.08,
which mostly speaks HTTP/1.0 and cannot negotiate modern Internet TLS. Review
changes against that target rather than against the requirements of a modern
browser, forward proxy, or transparent TLS MITM.

## HTTP compatibility scope

The HTTP compatibility proxy has deliberately pragmatic goals:

- accept the legacy client's HTTP request on TCP port 80;
- use the host JVM's current HTTP/TLS stack upstream;
- return a finite HTTP/1.0 response with `Connection: close`, no chunked framing,
  and a correct content length;
- fully acquire the upstream response before emitting the corresponding response
  to the legacy client. The compatibility proxy is a store/transform/serve
  boundary: TCP/PPP/serial backpressure from the legacy side must never throttle
  an in-progress upstream HTTP body read;
- keep TCP byte-stream handling and HTTP semantics in separate layers:
  `HttpTcpProxy` owns incremental HTTP decoding/encoding and connection-level
  backpressure, while compatibility/resource code implements `HttpRequestHandler`
  and must not consume or emit `TcpProxyEvent` directly;
- retain the complete upstream source representation separately from any
  client-visible transformed representation. A resource is `READY` when the
  complete client representation exists locally, before downstream delivery begins;
- keep resource graph knowledge at process scope, owned above the PPP session
  boundary and injected into each HTTP proxy instance. It must survive TCP flow
  closure, PPP generation changes, and browser restarts; revisiting a navigation
  root reuses and extends its existing graph rather than replacing it.
  Bound the total working set with explicit LRU-style retention rather than tying
  eviction to browser, TCP, or PPP lifetimes;
- request `Accept-Encoding: identity` for text that may need rewriting;
- replace absolute `https://` references in supported text responses with clean
  `http://` references and remember the upstream HTTPS target for the PPP
  session;
- distinguish browser navigation targets from embedded/subresource targets when
  deciding how redirects are exposed;
- expose redirects for navigation-like requests when the clean HTTP URL changes,
  so Netscape owns the new host, path, query, and relative-URL base;
- keep redirects for rewritten subresources (for example scripts, stylesheets,
  images, and CSS resources) internal to YAME. Real Netscape Navigator 4.08
  testing showed that returning such a redirect can cause the browser to
  navigate the top-level window to the resource URL instead of merely following
  the subresource redirect;
- when an internally followed subresource redirect changes host or path, treat
  the final upstream URI as the resource's effective base URI. Relative
  references in supported rewritable content must therefore be resolved against
  that final URI and rewritten to clean absolute compatibility URLs before the
  response is returned to the legacy browser;
- still follow a redirect internally when rewriting HTTPS to HTTP would otherwise
  produce a redirect back to the exact URL the client just requested.

Prefer small, observable compatibility rules and tests with period-appropriate
clients. Observed legacy-client behavior takes precedence over modern-browser
redirect expectations when the two differ. Do not add semantic HTML/CSS/JavaScript
parsing merely to avoid changing visible URL text. Rewriting every literal
absolute `https://` URL in a supported text body is intentional.

## Architecture documentation

Documentation must distinguish verified implemented behavior, decided design and
open work. [ADR 0002](docs/adrs/0002-architecture-decision-records.md) defines
decision records; [ADR 0003](docs/adrs/0003-document-decided-design.md) defines
the documentation convention.
Event ownership and generic access follow
[ADR 0004](docs/adrs/0004-extensible-event-contract.md); overload handling follows
[ADR 0001](docs/adrs/0001-fatal-event-queue-saturation.md).

- Record architectural decisions in `docs/adrs/<sequence>-<topic>.md`, using the
  next repository-wide four-digit sequence and a lowercase hyphenated topic.
  Include status, date, context, decision, alternatives and consequences. Never
  reuse or renumber a published ADR; superseding decisions get a new ADR and links
  both ways.
- Across project documentation, descriptions of intended behavior must reflect
  decided design, including `docs/**/*.md` outside `docs/design/` and relevant
  root documents. State implementation
  status separately; a decision can be documented before it is implemented.
- Clearly mark relevant unresolved or ongoing work as such. A concise mention may
  link to one specific issue or an appropriate issue label. Keep extended discussion
  of open alternatives in issues or PRs.
- Verify descriptions of current architecture against the code. Link to ADRs for
  rationale rather than repeating decision history or rejected alternatives.
- Keep revision-specific historical implementation inventories under
  `docs/.history/`, labeled with the exact revision. They are neither current
  architecture nor selected design.
- When a design is implemented, update current architecture documentation from
  the verified implementation, including deviations from the design. Update
  implementation status and links in the affected documents.

## Development workflow

Before pushing implementation changes or declaring a task complete:

- run the relevant build and test suite locally when the environment allows it;
- do not push known uncompilable or failing code;
- do not describe work as "done", "finished", or equivalent until the build and relevant tests are green;
- for changes that affect the normal JVM build, the default verification is `./gradlew clean build` unless a narrower command is clearly sufficient;
- run additional integration or platform-specific tests when the changed area requires them;
- if the current environment cannot execute the required build/tests, state that limitation explicitly and treat the work as unverified until CI has completed successfully;
- when CI is the only available verification path, inspect failures, fix them, and continue until CI is green before declaring completion.

## Code quality and design principles

Prefer code that is easy to reason about, test, review, and extend.

- follow separation of concerns: a type or function should have one clear responsibility and should not accumulate unrelated policy, IO, state management, formatting, and protocol behavior;
- prefer introducing a dedicated type/file when adding a new domain concept, policy, protocol component, transformer, cache, codec, scheduler, or other independently meaningful responsibility;
- avoid growing large catch-all files or classes when new behavior can live behind a focused abstraction with a clear interface;
- keep package structure aligned with responsibilities so related production and test code are easy to locate;
- make invariants and ownership explicit in types and APIs rather than relying on call-order assumptions or duplicated implicit knowledge;
- avoid multiple independent code paths that answer the same semantic question; prefer one source of truth for state transitions, applicability, identity, cacheability, validation, and similar decisions;
- treat shared/cached state as immutable from the caller's perspective and use defensive copies or immutable structures at ownership boundaries when needed;
- use deterministic, canonical representations for identifiers, fingerprints, protocol/header normalization, ordering, and other values that participate in equality or caching;
- keep concurrency boundaries explicit: publish state before signalling completion, avoid races between shared work and caches, and do not let observers/hooks block critical request or protocol paths;
- when an event subscriber publishes another event, check for direct or indirect feedback loops; publish only meaningful changes and use deduplication or state-change detection where necessary. The dispatcher can time out and fail fatally if it publishes into its own full queue. See [event publication from subscribers](docs/yame-events.md#event-publication-from-subscribers);
- treat external input as untrusted: bound memory, CPU, decoded sizes, recursion/depth, queues, and IO; avoid unnecessary parsing or implicit filesystem/network side effects;
- prefer fail-safe behavior and explicit fallback paths over partially mutated state;
- design new behavior so important success, boundary, failure, passthrough, and concurrency paths can be tested deterministically;
- when fixing a bug, add a regression test when practical, especially for invariants or edge cases likely to recur;
- avoid speculative abstractions, but refactor when a file/type starts carrying multiple distinct responsibilities or when similar logic is being duplicated.

## Explicit non-goals for this layer

Do not request or add these as correctness requirements for HTTP URL/TLS
compatibility work unless a separate feature explicitly scopes them in:

- WebSocket, `wss://`, or Server-Sent Events support;
- modern JavaScript transpilation or complete script sanitization;
- HTML5-to-HTML3 conversion or general page re-rendering;
- full CSS compatibility rewriting;
- image conversion for PNG, WebP, AVIF, video, or other unsupported media;
- exhaustive RFC handling for unusual URL spellings, every content type, or
  every character encoding;
- direct `https://` navigation on TCP port 443. That requires a separate,
  explicitly designed legacy-TLS termination fallback.

Cookie simplification, user-agent substitution, script/style stripping, and
image transcoding may be useful future compatibility stages. They are not part
of the HTTP-to-HTTPS routing and URL-rewrite feature.

Security review is still in scope: never weaken TLS toward the modern upstream,
leak credentials or cookies across origins, corrupt HTTP framing, or permit
unbounded buffering.

## Component configuration pattern

- Keep a component's immutable configuration type beside its owning component.
- Provide `ConfigType.fromConfigStore(store: ConfigStore = ConfigStore.current)`
  on its companion object. Read owned `SettingKey` values directly through
  `store[key]`; these reads trigger lazy loading. Pass an available store
  explicitly to `fromConfigStore(store)`. The `ConfigStore.current` default is
  convenient when constructing a component without an explicit store.
- Accept an explicitly constructed immutable config independently of any store.
  A non-null constructor default such as
  `config: ConfigType = ConfigType.fromConfigStore()` is useful when appropriate,
  but is not the only construction pattern. Composition with an explicit store
  can inject `ConfigType.fromConfigStore(store)` instead. Do not use a nullable
  config argument merely to implement a fallback.
- Explicit config must allow isolated tests without installing or modifying a
  global store. Config data-class defaults remain ordinary standalone defaults.
- Factories produce immutable component configuration values without retaining
  a `ConfigStore` reference. Live updates require a separately owned subscription;
  never silently overwrite explicit constructor configuration.
- `ConfigStore.current` fails fast until `Main` explicitly installs the process store.
  Hold the returned installation handle for the application's lifetime and close it
  after runtime/frontends stop. A second installation is rejected. Multiple
  frontends share the existing store; they never install a replacement.
- Runtime and presentation constructors receive the shared stores, not duplicate
  initial setting values or the persistent application configuration tree.
- `ConfigStore` is the single authoritative source of configuration values.
  Components read settings directly through typed keys; multi-key read snapshots
  are intentionally unsupported. YAME accepts the negligible possibility that a
  setting changes between individual reads. Build immutable component configs
  when needed and use subscriptions to handle relevant runtime changes. Apply
  notification values in delivery order; request-scoped providers may construct
  immutable settings for each HTTP request. Do not add alternative configuration
  read mechanisms or reintroduce snapshots.
- Immutable configs contain values only; subscriptions, providers, IO, registries,
  and other runtime dependencies belong in component constructors.
- Local config types do not require a nested application-wide config tree.

## Configuration ownership and dependency direction

- `no.skasti.yame.config` and `.function` contain generic infrastructure only.
  They must not import concrete features, persistence adapters, or frontends.
- Each feature owns its keys, groups, immutable values, validation, and component
  factories. Modem config contains modem behavior/credentials only; PPP addressing,
  DNS, and HTTP compatibility are independent component configs.
- HTML transformation settings, rules, and their separate startup INI reader live
  in `ppp.proxy.transform`, alongside the transformation implementation.
- Feature-owned `Settings` definitions own their identity, optional logical
  `file` name, static groups, typed keys, and optional dynamic-section mapping.
  A group's ID is its canonical INI section. Keys declare relative names and
  derive paths as `group.id + "." + name`; do not add an `iniSection`, binding
  registry, or per-key codecs. One lazy reflection pass discovers public keys,
  including key collections. Ordinary scalar values use `kotlinx.serialization`;
  multi-property values use a reusable `CompositeSetting<T>` companion.
  `ConfigStore` owns the current values; immutable component `*Config` objects are
  ordinary values and do not inherit from `Settings`.
  `file = null` uses the configured primary file; `SeparateSettings(id)` uses a
  separate file named after its ID. Use a bare name such as `shared-settings`,
  never an extension or path. `IniConfigurationRepository` resolves it to
  `shared-settings.ini`. Definitions may share a logical file and must not claim
  the same property. The generic ConfigStore registers only definitions requested
  by components; it never enumerates all features.
- INI reads accept `[serial] baud=115200` and the equivalent root property
  `serial.baud=115200`. A conflict is an error. Writes emit only the section
  form and remove the owned flat variant; unknown settings and comments survive.
  Durations use readable Kotlin syntax such as `500ms` or `2s`. Older names and
  nanosecond values are not aliases; update old files when adopting this format.
- `YameConfiguration` and `YameConfigurationBindings` remain compatibility and CLI
  adapters. New persistence mappings live in their owning feature definitions.
- Application persistence may reuse immutable feature config values. This does not
  authorize a feature to import the application schema or nest unrelated configs.
- `YameRuntime` composes serial, modem, PPP, DNS, proxy, and logging behavior. TUI
  and plain mode use that same runtime, ConfigStore, and FunctionStore.
- A user selecting unchanged serial settings expresses a reconnect action through
  FunctionStore; do not manufacture a configuration event for a no-op write.
- Protocol aggregators (`HayesModem`'s default PPP handler and `RetroPppHandler`)
  have ordinary standalone child defaults. Application composition explicitly
  injects each configured dependency. Supplying an explicit modem config must not
  indirectly consult a process store for unrelated PPP configuration.
