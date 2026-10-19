# ADR 0002: Separate decision records, decided design and implemented architecture

- Status: Accepted
- Decision date: 2026-10-19
- Decision owner: repository maintainer
- Related work: [PR #106](https://github.com/skasti/yame/pull/106)

## Context

The YameEvents design mixed the selected design, decision history, alternatives and
a revision-specific implementation inventory. This made it harder to distinguish
what had been decided from why it was chosen and what had actually been implemented.
The maintainer requested numbered ADRs and specified that design documents contain
only decided design.

## Decision

- Record architectural decisions in `docs/adrs/<sequence>-<topic>.md`, using a
  repository-wide increasing sequence, zero-padded to four digits, and a short
  lowercase hyphenated topic. Never reuse or renumber an existing ADR.
- Each ADR records status, decision date, context, the decision, relevant alternatives
  and consequences, with links to related work and the resulting design.
- `docs/design/` describes decided design only. State implementation status separately;
  a decided design may still await implementation. Link to ADRs rather than embedding
  approval history, rejected alternatives or unresolved proposals.
- Discuss unresolved proposals in issues or PRs until decided. Do not present them as
  part of the selected design.
- Regular architecture documents describe verified implemented behavior. Put historical
  inventories under `docs/inventories/`, with the exact revision and a historical label.
- If a decision changes, add a new ADR and mark the prior record superseded with a
  link. Update the design to reflect the new decision; retain the historical rationale.

## Consequences

Readers can find the selected design without following a decision debate, while ADRs
preserve rationale and trade-offs. Changes may require coordinated updates to the ADR,
design and current architecture documentation. These roles are documented in
[AGENTS.md](../../AGENTS.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md).

The alternative of continuing to embed decision history in the design is superseded
by this organization. Existing decisions are migrated when their documentation is
updated; this change does not claim to reconstruct every past repository decision.
