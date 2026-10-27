# ADR 0006: Consumer-owned event ordering and occurrence time

- Status: Accepted
- Decision date: 2026-10-24
- Decision owner: repository maintainer
- Extends (does not supersede): [ADR 0004: Extensible event contract](0004-extensible-event-contract.md)
- Related work: [#92](https://github.com/skasti/yame/issues/92), [#100](https://github.com/skasti/yame/issues/100), [#101](https://github.com/skasti/yame/issues/101), [#102](https://github.com/skasti/yame/issues/102), [#103](https://github.com/skasti/yame/issues/103)

## Context

ADR 0004 establishes producer-owned, immutable, extensible `YameEvent` values.
The asynchronous broker delivers accepted events in FIFO **enqueue order**, not
necessarily the order in which independent domain transitions occurred. For
example, a consumer may receive resource event D before the related graph event
C. One consumer might need C immediately and have no interest in D; another
might need D but require information from C to interpret it.

Enforcing every consumer's causal or projection-specific dependencies in
producers or the broker introduces coupling and unnecessary synchronization.
Resource updates also have domain-specific rules: `READY` normally succeeds
`FETCHING` within one fetch attempt, but `FETCHING` after `READY` can be
legitimate when a new fetch attempt starts.

## Decision

1. **Producer responsibility:** Publish accurate, immutable domain observations
   of authoritative transitions, with stable identities and enough
   domain-specific context for independent consumers. Reject obsolete operations
   at the authoritative state owner; do not publish a transition that did not
   occur. Producers need not serialize or rearrange independent publications to
   satisfy an individual consumer's processing order.

2. **Occurrence time:** New state-transition event contracts expose an
   occurrence timestamp, preferably named `occurredAt`, recorded where the
   authoritative transition happened. This is **not** event-object construction,
   broker admission, dispatch, logging, or receipt time. Existing event schemas
   may adopt it separately as their contracts evolve. An appropriate timestamp
   representation and precision should be documented by each event contract.

3. **Semantic interpretation first:** Consumers use the domain's lifecycle
   semantics to decide whether an observation is new, stale, redundant or
   dependent on another. Where a semantic rule alone does not decide relative
   age, consumers may use occurrence timestamps. Equal timestamps and wall-clock
   adjustments mean timestamps are **not** an infallible total-order key; an
   ambiguous case remains the consumer's responsibility to resolve or expose.

4. **Correlation over generic sequencing:** Producers include the identities
   needed to distinguish entity lifetimes, relationships and repeated
   operations. For resource fetches, the event contract must distinguish a
   legitimate refetch from a delayed observation of the previous attempt;
   existing fetch-attempt identity, a fetch counter, a distinct domain status
   or a similarly explicit domain representation may be appropriate. This ADR
   does not prescribe any particular representation or a global sequence.

5. **Consumer-owned dependency handling:** A consumer may process unrelated
   observations immediately and defer dependent ones in its **own bounded
   private queue/projection** until prerequisites arrive. It decides how to
   handle obsolete events, unresolved dependencies and ambiguity. Subscriber
   callbacks must return promptly; buffering or reconciliation must not block
   the shared broker worker or introduce unbounded memory growth.

6. **Broker remains domain-agnostic:** `EventBroker` keeps its existing FIFO
   guarantee by successful enqueue order and bounded/fatal admission policy.
   Broker admission sequence numbers remain transport/drain metadata; they do
   not establish occurrence order. No universal producer-side ordering,
   registry-wide revision scheme, broker dependency engine, event replay or
   late-subscriber snapshot/bootstrap protocol is introduced by this decision.

Production consumers requiring a complete lifetime view register before
producers start publishing during application startup. That is an application
composition requirement, not an ordering service provided by the broker.

## Example: resource fetch lifecycle

Suppose the consumer receives:

- `READY` for resource R, attempt A;
- then `FETCHING` for R, attempt A.

It can discard the older `FETCHING` using domain semantics alone.

If it instead receives `FETCHING` for R, attempt B, it can recognize a new
fetch and accept it. If event D depends on a separate event C not yet observed,
that particular consumer may privately defer D. Another consumer interested
only in C need not do so.

Graph/resource identity must likewise make it possible for a consumer to
handle resource-before-root observations or reject an obsolete update after
graph eviction, without making root-first enqueue order a producer requirement.

## Alternatives considered

| Alternative | Reason not selected |
| --- | --- |
| Producers enforce root-before-resource and mutation-order enqueue | Couples authoritative producers to consumer projection needs and can introduce cross-thread coordination. |
| Broker reorders by causal relationships or timestamps | Requires knowledge of every domain and adds complexity for consumers that do not need ordering. |
| Universal registry revision or global sequence for domain state | Imposes a generic mechanism where identities, domain lifecycle rules and occurrence time usually suffice. |
| Event timestamps as a guaranteed global total order | Clock adjustments and ties make this guarantee unsound. |
| Full replay/snapshot bootstrap for late subscribers | Not needed for the startup-subscribed, in-process v1 architecture. |

## Consequences and verification

- The event contract must provide enough identity, lifecycle/correlation data and
  occurrence-time context for each consumer to interpret observations.
- Consumers needing causal processing must implement and test their own bounded
  deferral, stale-event rejection and ambiguity policy. Consumers uninterested
  in ordering incur no such requirement.
- Tests should deliberately deliver semantically related events out of
  occurrence order, including resource-before-root, delayed fetch observations,
  legitimate refetch and updates after eviction. Producer tests independently
  verify that emitted observations describe real authoritative transitions.
- The broker remains fast and generic; its FIFO/admission/failure guarantees,
  and ADR 0004's event ownership, payload immutability and schema-versioning
  decisions remain unchanged.
- Producer events (#100), startup wiring (#102), and the incremental consumer
  projection (#103) and unified TUI worker (#101) implement this design;
  [current projection behavior](../yame-events.md#proxy-dashboard-projection)
  documents the bounded handoff and consumer failure policy.
