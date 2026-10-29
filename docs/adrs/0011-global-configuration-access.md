# ADR 0011: Global configuration access for production consumers

- Status: Accepted; implemented
- Date: 2026-10-28
- Supersedes: configuration-store injection described in ADR 0008 and ADR 0010

## Context

ConfigStore is the authoritative configuration state holder, but production
constructors and factories still accepted local store references. This allowed
runtime and frontend instances to observe different configuration stores and
required passing stores through composition. Production must always use the
single installed configuration instance.

## Decision

Production consumers use the global ConfigStore facade. Typed reads use
`ConfigStore[key]`; writes, transient overrides and subscriptions delegate through
the same facade. Runtime, presentation, CLI parsing and component config factories
have no ConfigStore arguments or retained store references.

Bootstrap constructs the one process store directly for installation and owns its
lifetime through shutdown. Its INI bootstrap helper returns an installation handle,
never a store instance. Access before installation fails fast. Replacing an active
installation is rejected.

Tests may construct local instances for generic store/repository tests. Tests
exercising production consumers install their selected store for the complete
scenario and close the installation after components stop. Immutable component
config values remain explicitly injectable without a store. FunctionStore's
independent injection and lazy-global lifecycle are unchanged.

## Alternatives

- Defaulted constructor/factory store parameters still permit local production
  configuration authorities and require carrying references through composition.
- Replacing an active global store would break subscriptions and lifecycle
  ownership, so installations remain exclusive.

## Consequences

All production consumers observe the installed configuration authority. Direct
key reads remain the default; immutable config types are optional value objects.
Tests requiring different stores use separate installation scopes and do not run
those scenarios concurrently in one process. Architecture tests enforce that
production consumers cannot accept or retain local configuration stores.
