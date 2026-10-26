# ADR 0008: Presentation-independent application bootstrap

- Status: Accepted
- Decision date: 2026-10-26
- Decision owner: repository maintainer
- Related work: [#125](https://github.com/skasti/yame/issues/125)
- Compatible with [ADR 0007](0007-lazy-global-event-broker.md) and
  [ADR 0009](0009-lazy-global-function-store.md)

## Context

The TUI application constructed and ran `YameRuntime`, while plain mode had its
own runtime lifecycle in `Main`. A process registry passed through the TUI solely
to reach its runtime. This coupled optional presentation to core ownership and
made event projection tests construct a runtime. Non-replayed observations also
require subscribers to be ready before runtime construction can publish.

## Decision

`Main.kt` directly owns application startup independently of presentation.
After process configuration is installed and logging/event supervision are ready,
Main constructs the selected presentation and its subscriptions, registers it with
`ApplicationEventLifecycle`, and starts its workers without blocking. It then
constructs the runtime, registers it with the same lifecycle owner, and runs it.
The existing outer `try/finally` in Main closes the lifecycle owner. No additional
bootstrap abstraction is needed.

Presentation starts before runtime functions exist. An early unavailable operation
remains an exception from `FunctionStore.invoke`; the TUI handles it as a local
command failure without stopping input or preventing a later user action. There
is no readiness barrier, polling, automatic retry or registration synchronization.
Dynamic Command Palette population and new store notifications are outside this
decision; existing palette behavior is retained.

Production constructors use their defaults for store access:
`ConfigStore.current` resolves the explicitly installed process configuration,
while `FunctionStore.current` is the lazy process-owned registry defined by
ADR 0009. Main neither creates nor propagates a function store. Runtime and TUI
constructors still accept explicit stores, bypassing each default independently.
Internal operations and composed children retain the supplied instances; they
must not look up globals in place of injected dependencies. Function registration
handles belong to the runtime and are released on shutdown; the shared function
store itself is never installed, replaced or reset.

Presentations use ConfigStore, FunctionStore and typed YameEvents as appropriate.
They own subscriptions and presentation workers, and have no runtime or resource
registry dependency. Registry resolution and explicit test injection remain in
runtime/core components; the existing lazy process-wide production registry and
retention/reconnection semantics are unchanged.

`ApplicationEventLifecycle` coordinates normal and partial-startup cleanup: stop
producers, close/drain the broker, release presentation subscriptions/workers,
then shared infrastructure. Repeated shutdown does not close components twice.
Constructors clean up partial construction; rejected lifecycle registrations close
their unowned component if shutdown won the registration race.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Keep runtime ownership in each frontend | Duplicates lifecycle and makes optional UI responsible for core behavior. |
| Start runtime before constructing presentation | Loses early non-replayed observations. |
| Additional bootstrap class wrapping startup factories | Adds indirection to the sequence already owned by Main. |
| Require core functions before starting presentation | Couples presentation startup to runtime initialization without a necessary invariant. |
| Run a blocking UI loop before runtime startup | Prevents runtime startup; presentation starts its own workers and returns. |
| Convert internal core collaboration to events | Does not address bootstrap ownership and unnecessarily changes core contracts. |

## Consequences

TUI projections can be tested using real typed observations without a runtime.
Both modes share startup/shutdown ordering, and the runtime remains usable with
no presentation. Presentations must implement nonblocking startup and release
their own workers/subscriptions. This introduces no broker replay, UI FIFO worker
or new registry retention policy.
