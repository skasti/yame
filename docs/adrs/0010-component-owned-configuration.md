# ADR 0010: Component-owned configuration without an application aggregate

- Status: Accepted; implemented
- Date: 2026-10-28

## Context

Feature-owned settings and the typed ConfigStore already define defaults,
validation and INI mappings. A legacy application DTO, binding projection and
older file API duplicate this model, especially in CLI startup and tests.

## Decision

ConfigStore is the sole authoritative value holder. Startup creates one INI-backed
store; CLI flags stage typed transient changes on it, with last-flag precedence,
and normal runtime and presentations share that store. Informational commands
avoid file reads; tone tests load only the features they use.

Each feature owns its keys, defaults, validation and immutable component values.
SerialConfig groups transport values independently of HayesModemConfig. LoggingConfig
carries logging startup values and defensively copies its level map. Individual
keys need no wrapper. Factories read their own keys without retaining the store.

Delete the application aggregate, binding/projection layer and older aggregate
file API. The generic INI repository retains lazy loading, composite and dynamic
mappings, bounded IO, permissions, unknown records and transactional writes.
First-run creation materializes only loaded persistent defaults through the same
repository, excluding transient CLI changes. Auxiliary files retain their owner.
Tests inject typed stores or component values rather than a replacement aggregate.

## Alternatives

Retaining renamed aggregate adapters would preserve duplicate ownership and
synchronization. Enumerating all features in a central registry would defeat lazy,
component-owned registration. Mandatory wrappers for every key add no useful boundary.

## Consequences

Existing current-format INI files require no migration. Historical key names that
were already unsupported by feature definitions remain unsupported. Older aggregate
APIs are removed, so embedders must use ConfigStore and feature keys. Independent
reads remain intentionally non-atomic; immutable configs are values, never a second
mutable source of truth. Cross-file replacement still reports partial disk writes.
