# ADR 0010: Component-owned configuration without an application aggregate

- Status: Accepted; implemented
- Date: 2026-10-28

## Context

The application configuration DTO duplicated feature-owned settings and required
bindings to project values into ConfigStore. Startup, CLI parsing and an older
file API kept this parallel model alive after runtime consumers migrated to keys.
Issue #130 calls for removing the aggregate and its projections while retaining
INI layout, transactional writes, secret handling and CLI precedence.

## Decision

ConfigStore is the sole authoritative configuration state holder. Features own
keys, defaults, validation and INI mappings. Components read their own keys and
may construct immutable local config values. SerialConfig owns serial transport
values; LoggingConfig supplies initial levels and capture limits. Hayes modem
configuration remains independent of transport, PPP, DNS and proxy configuration.

Main installs ConfigStore before CLI parsing or constructing components. Configured
components accept an optional immutable config constructor parameter whose default
reads the installed store; Main does not assemble component configs. Explicit
config values and injected test stores remain supported. The installation survives
through component shutdown and is released on failed startup as well.

CLI parsing stages typed changes and applies them in one transient batch to the
same store used by runtime and presentation. Invocation options carry execution
choices only. No global configuration DTO, projection bindings or replacement
application-wide settings registry is permitted.

The generic INI repository retains file ownership, bounded loading, permissions,
fresh-read merging and staged replacement. First-run creation persists only the
saved baseline of requested definitions, never transient CLI overrides. Missing
entries use feature defaults. Standalone stores lazily load key defaults and
preserve explicit injected values. Both reads and writes request their owner.

The feature-local element-simplifier reader/default-template creator is retained
for its independent file-creation API. Runtime rule configuration remains in the
shared store, including dynamic edits and the separate-file enable switch.

## Alternatives

- Retaining or renaming the aggregate and bindings preserves the duplicated model.
- A central settings registry couples generic infrastructure to every feature.
- Requiring a config wrapper for every individual key adds no useful boundary.

## Consequences

Existing section-based INI files and equivalent root key paths load unchanged.
Unknown properties, comments, dynamic mappings and credentials retain the generic
repository's protections. Historical pre-section aliases remain outside the
supported format, as documented before this change. Existing installations using
the supported format require no migration.

Tests inject stores or immutable component values directly. The obsolete aggregate
API is intentionally removed. No-op writes remain no-ops; the explicit first-run
baseline save is separate from ordinary changes. Multi-file filesystem failures
retain the existing partial-write reporting contract.
