# ADR 0003: Document decided design across the documentation

- Status: Accepted
- Decision date: 2026-10-19
- Decision owner: repository maintainer
- Related work: [PR #106](https://github.com/skasti/yame/pull/106),
  [ADR 0002](0002-architecture-decision-records.md)

## Context

The YameEvents design previously mixed selected design with unresolved proposals,
rejected alternatives and a revision-specific implementation inventory. Readers
could not readily tell what YAME had decided to build, what had actually been
implemented, or what remained open. The maintainer clarified that the expectation
to reflect decided design applies throughout the documentation, not only under
`docs/design/`.

## Decision

- All project documentation that describes design or intended behavior must
  reflect the decisions currently made for that subject. This includes every
  relevant `docs/**/*.md` file, topic documents outside `docs/design/`, and
  relevant root documentation. Update
  affected documents when a decision changes; do not leave conflicting proposals
  presented as the selected design.
- Distinguish decided design from verified implemented behavior. A selected design
  can be documented before implementation, with its implementation status stated
  explicitly. Descriptions of current architecture must be verified against code.
- An undecided or ongoing point may be mentioned where relevant, clearly marked
  as open or in progress. Keep that mention concise and, when useful, link to one
  specific GitHub issue or an appropriate issue label. Do not present an open
  option as a decision or duplicate its full discussion throughout the docs.
- Keep rationale, alternatives and decision history in ADRs; link to them from
  relevant documentation. Put revision-specific implementation inventories under
  `docs/.history/`, label the exact revision and identify them as historical.
- When a design is implemented, update descriptions of current architecture from
  the verified implementation, including deviations from the selected design.
  Update implementation status and links accordingly.

## Alternatives

Restricting this rule to `docs/design/` would leave other topic documents free
to contradict the selected design. Removing every mention of unfinished work
would also hide useful context and make it harder to find its issue or label.
Keeping a full decision debate in every affected document would duplicate
rationale and allow copies to drift.

## Consequences

Readers can distinguish what is implemented, what has been decided, and what
remains unresolved from any relevant project document. Maintaining this
clarity requires updating all affected documentation when decisions or
implementation status change. ADRs retain the rationale and historical
inventories retain their pinned snapshots; neither is a claim about current code.
