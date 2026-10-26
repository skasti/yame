# ADR 0009: Lazy global function store

- Status: Accepted
- Date: 2026-10-26

## Context

The in-memory typed operation registry has no external initialization requirements.
Requiring it at every runtime and presentation composition boundary adds wiring.
Issue #127 defines a shared default independent of configuration and event lifetimes.

## Decision

Expose `FunctionStore.current` through Kotlin's synchronized `lazy` delegate.
Runtime and presentation constructors default to this instance; explicit arguments
bypass it. Keep the public constructor for independent registries. Main uses the
constructor defaults in both plain and TUI mode. Registration owners continue to
close their handles; the shared registry itself has no installation or reset API.
`ConfigStore.current` retains its explicit installation semantics.

## Alternatives

Mandatory injection retains unnecessary production wiring. A replaceable global
or installation handle adds lifetime and parallel-test hazards without a use case.
Creating a store per frontend would disconnect its commands from the runtime.

## Consequences

Default components share operations without owning each other's registry.
Tests normally inject independent stores. First-access and default-composition
coverage runs in a fresh JVM, without resetting or replacing global state.
Concurrent runtimes must use independent stores to avoid duplicate operation paths.
