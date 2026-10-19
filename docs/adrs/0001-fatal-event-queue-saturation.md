# ADR 0001: Treat event-queue saturation as a fatal application error

- Status: Accepted
- Decision date: 2026-10-19
- Decision owner: repository maintainer
- Related work: [#92](https://github.com/skasti/yame/issues/92),
  [#98](https://github.com/skasti/yame/issues/98),
  [#102](https://github.com/skasti/yame/issues/102),
  [PR #106](https://github.com/skasti/yame/pull/106)

## Context

The event broker must bound memory and let publishers return without waiting for
subscribers. One blocked subscriber can fill its shared FIFO. Under sustained
overload, finite memory, non-blocking publishers and lossless delivery cannot all
be guaranteed. Missing graph/resource lifecycle transitions would leave consumers
with incomplete state. Producer wrappers can catch exceptions with `runCatching`,
so throwing alone does not reliably stop the application.

The original design document listed alternatives without approving an overload
policy. During the #98 implementation discussion, the maintainer chose a synchronous
exception from `publish` on full capacity, then explicitly required YAME to abort
and exit. Recovery through the same event-delivery infrastructure was considered
unreliable once that infrastructure could no longer keep up. The maintainer also
approved an independent fatal signal and time-bounded application shutdown.

## Decision

- Count or aggregate payload saturation rejects publication synchronously with
  `QueueFullException`. An oversized event follows the same fatal path with
  `EventTooLargeException`.
- Diagnostic metadata contains limits, occupancy and the active subscriber name.
  The active subscriber is context, not proof of the slowest or responsible consumer;
  v1 does not introduce timing or a watchdog.
- Before throwing, the broker latches an independent fatal signal. Catching the
  exception cannot clear it or suppress application-level supervision.
- Further publication is rejected. There is no live recovery, retry, or continued
  operation with an incomplete observation stream. Pending work may be abandoned;
  fatal shutdown never claims successful drain.
- Application composition owns independent diagnostics, producer shutdown,
  time-bounded cleanup and nonzero process exit. It must not depend on delivery,
  final events, or a blocked subscriber returning. The broker never exits the
  process directly, allowing isolated tests.
- Healthy shutdown retains graceful drain semantics. Consumer execution contexts
  remain consumer-owned; there are no broker-managed per-subscriber queues.

## Alternatives considered

| Alternative | Reason it was not selected for v1 |
| --- | --- |
| Keep YAME running with a terminally faulted observation stream until restart | Leaves protocol operation running with stale consumer views; the maintainer requires application termination. |
| Throw only from `publish` | A caught exception can hide lost transitions while the application continues. |
| Bounded per-consumer isolation | Adds queue ownership, failure and recovery contracts; shared ingress can still saturate. |
| Snapshot-based resynchronization | Requires an authoritative snapshot/stream boundary and bounded recovery work; recovery is not the chosen response to stalled delivery. |
| Coalescing or reserved lifecycle capacity | Only semantically replaceable observations can be coalesced; reserved capacity can still fill. `TransferBytes` is a delta and cannot simply be dropped. |
| Blocking publishers, unbounded queues or silent lifecycle-event loss | Violates the required admission, memory or correctness constraints. |

## Consequences and verification

A short burst can terminate YAME if configured capacity is exhausted. This favors
bounded resource use and explicit failure over availability; no lossless-delivery
claim applies during fatal shutdown. Diagnostic information identifies what was
active, without asserting causation. Arbitrary blocked user callbacks cannot be
forcibly stopped by the broker.

#98 implements and tests FIFO/budgets, synchronous rejection, first-failure latching,
independent signalling while a subscriber is blocked or an exception is caught,
subsequent rejection, failed drain and bounded close waits. #102 must wire and test
actual process termination in plain and TUI modes, including blocked cleanup.

The resulting contract is in the [v1 design](../design/yame-events-v1.md).
The [current broker architecture](../yame-events.md) identifies what is implemented.
