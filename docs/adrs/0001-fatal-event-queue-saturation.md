# ADR 0001: Treat event-queue saturation as a fatal application error

- Status: Accepted
- Decision date: 2026-10-19; admission policy refined 2026-10-20 in this unmerged PR
- Decision owner: repository maintainer
- Related work: [#92](https://github.com/skasti/yame/issues/92),
  [#98](https://github.com/skasti/yame/issues/98),
  [#102](https://github.com/skasti/yame/issues/102),
  [PR #106](https://github.com/skasti/yame/pull/106)

## Context

The broker has one FIFO worker. A blocked subscriber can fill the pending queue.
Losing a lifecycle observation would leave consumers with incomplete state.
Producer wrappers may catch an exception, so throwing alone cannot reliably stop
the application. Brief queue saturation can resolve as the worker dequeues an event.

## Decision

- Capacity is the number of **queued** events, excluding one event in dispatch.
  Every event counts as one element. The broker never measures payload size.
- A full queue makes `publish` wait on a condition for space, for at most a
  configurable timeout (10 ms by default). A zero timeout rejects immediately.
  Successful admission defines sequence and FIFO order. Publishers wake on dequeue,
  broker close or fatal failure. An interrupted wait rejects that attempt without
  latching failure and restores the interrupt flag.
- If the queue remains full at the deadline, `publish` latches the fatal state,
  signals independent lifecycle supervision and throws `QueueFullException`.
  Catching it cannot clear the fatal signal. Later publications are rejected.
- There is no silent drop, retry, recovery or continued operation with an incomplete
  stream. Fatal shutdown may abandon pending events and must not claim drain success.
- Application composition owns diagnostics, producer shutdown, time-bounded cleanup
  and nonzero exit independently of the broker worker; the broker itself never exits
  the process. Healthy shutdown retains graceful drain semantics.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Immediate fatal failure on a full queue | A short burst can clear within the bounded wait. |
| Byte-budget accounting | Requires the broker to understand event payloads, yet cannot measure exact retained heap. |
| Throw only from `publish` | A caught exception could hide lost transitions. |
| Unbounded queues, drops, retries or live recovery | They either risk memory growth or require stream resynchronization that v1 does not provide. |
| Per-subscriber queues and watchdogs | They add separate ownership and failure contracts outside this broker's scope. |

## Consequences and verification

The queue holds at most the configured number of event references; this is **not**
a strict bound on payload memory. Producers own unusual payload-size concerns and
immutable snapshots. Publishers can wait up to the configured timeout when full;
normal non-full admission has no wait. A callback already selected may still run
after failure, and arbitrary user callbacks cannot be forcibly stopped.

[#98](https://github.com/skasti/yame/issues/98) tests count capacity, waiting,
timeout, fatal latching, independent signalling, FIFO and graceful shutdown.
[#102](https://github.com/skasti/yame/issues/102) must integrate and verify actual
process termination. The [v1 design](../design/yame-events-v1.md) and
[current implementation](../yame-events.md) describe their respective scopes.
