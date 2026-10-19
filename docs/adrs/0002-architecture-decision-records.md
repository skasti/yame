# ADR 0002: Record architectural decisions as ADRs

- Status: Accepted
- Decision date: 2026-10-19
- Decision owner: repository maintainer
- Related work: [PR #106](https://github.com/skasti/yame/pull/106),
  [ADR 0003](0003-document-decided-design.md)

## Context

The YameEvents design mixed selected design, decision history, alternatives and
implementation details. Finding why a choice was made required reading a design
document that also served other purposes. The maintainer requested numbered
architecture decision records (ADRs) to preserve decisions and their rationale.

## Decision

- Record architectural decisions in `docs/adrs/<sequence>-<topic>.md`. Use one
  repository-wide increasing sequence, zero-padded to four digits, and a short
  lowercase hyphenated topic. Never reuse or renumber a published ADR.
- Each ADR records status, decision date, context, the decision, relevant
  alternatives and consequences. Link related work and resulting documentation.
- Record a changed decision in a new ADR. Mark the prior ADR as superseded and
  link the old and new records in both directions. Preserve the original rationale.

## Alternatives

Continuing to embed decision history and rejected alternatives in design
documents would keep fewer files, but obscure which text records the decision
and which text describes the selected design.

## Consequences

Decisions have stable references and retain their reasoning when design changes.
A design document can link to the relevant ADR without repeating its debate.
The scope and interpretation of design documentation are decided separately in
[ADR 0003](0003-document-decided-design.md).

Existing decisions can be recorded as their documentation is updated; this ADR
does not claim to reconstruct every past repository decision.
