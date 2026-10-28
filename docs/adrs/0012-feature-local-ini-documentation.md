# ADR 0012: Feature-local generated INI documentation

- Status: Accepted; implemented in PR #132
- Date: 2026-10-28

## Context

Feature-owned Settings definitions may share an INI file and are loaded lazily.
A shared generated file overview cannot be safely rebuilt from whichever features
have loaded: it can discard documentation belonging to unknown owners. Comments
also need to remain near their configuration as dynamic sections change.

## Decision

Keep descriptions in Settings, SettingGroup and SettingKey metadata. Give each
Settings overview its own `##### @yame <settings-id> settings:` block, including
common structural-key descriptions. Place it before the first owned section in
actual document order, ahead of that section's independently owned
`##### @yame <section-id>` block. Root-only mappings anchor before their first
owned root property. Use existing mapping claims to determine ownership.

Synchronize only relevant definitions, without enumerating other features or
storing additional documentation state. Replace or relocate blocks by their
explicit owner markers; never infer ownership from description text. Preserve
unknown, malformed and legacy shared-file blocks conservatively. Unmarked
comments and unrelated formatting remain user-owned.

Keep documentation-only synchronization independent of configuration changes and
notifications, using the shared transactional INI persistence pipeline.

## Alternatives

A single file-level block requires complete knowledge of all owners, which
conflicts with lazy loading. Separate templates or feature-specific writers would
duplicate the existing settings metadata and INI infrastructure. Per-property
ownership markers would make generated files noisy and complicate maintenance.

## Consequences

Sharing a file does not share overview ownership. An unloaded feature's blocks
survive another feature's edits, and dynamic sections can change without
repeating structural descriptions. Comments inside marked blocks are generated
content and may be replaced; users keep personal notes outside them. Legacy
`##### @yame file` blocks may coexist with new blocks because their original
ownership cannot safely be reconstructed.
