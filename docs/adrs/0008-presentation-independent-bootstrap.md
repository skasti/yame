# ADR 0008: Presentation-independent application bootstrap

- Status: Accepted
- Decision date: 2026-10-26
- Decision owner: repository maintainer
- Related work: [#125](https://github.com/skasti/yame/issues/125)
- Compatible with [ADR 0007](0007-lazy-global-event-broker.md)

## Context

The TUI application constructed and ran `YameRuntime`, while plain mode had its
own runtime lifecycle in `Main`. A process registry passed through the TUI solely
to reach its runtime. This coupled optional presentation to core ownership and
made event projection tests construct a runtime. Non-replayed observations also
require subscribers to be ready before runtime construction can publish.

## Decision

Application bootstrap owns runtime composition independently of presentation.
After shared configuration, functions, logging and event supervision are ready,
bootstrap constructs the selected presentation and its subscriptions, constructs
the runtime, starts presentation workers without blocking, and runs the runtime.
Core functions exist before input is enabled. A presentation is optional.

Presentations use ConfigStore, FunctionStore and typed YameEvents as appropriate.
They own subscriptions and presentation workers, and have no runtime or resource
registry dependency. Registry resolution and explicit test injection remain in
runtime/core components; the existing lazy process-wide production registry and
retention/reconnection semantics are unchanged.

One application lifecycle coordinates normal and partial-startup cleanup: stop
producers, close/drain the broker, release presentation subscriptions/workers,
then shared infrastructure. Repeated shutdown does not close components twice.
Factories clean up partial construction; rejected lifecycle registrations close
their unowned component if shutdown won the registration race.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Keep runtime ownership in each frontend | Duplicates lifecycle and makes optional UI responsible for core behavior. |
| Start runtime before constructing presentation | Loses early non-replayed observations. |
| Run a blocking UI loop before runtime startup | Prevents runtime startup and leaves core functions unavailable to input. |
| Convert internal core collaboration to events | Does not address bootstrap ownership and unnecessarily changes core contracts. |

## Consequences

TUI projections can be tested using real typed observations without a runtime.
Both modes share startup/shutdown ordering, and the runtime remains usable with
no presentation. Presentations must implement nonblocking startup and release
their own workers/subscriptions. This introduces no broker replay, UI FIFO worker
or new registry retention policy.
