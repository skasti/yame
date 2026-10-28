# ADR 0013: Exclusive SettingGroup ownership within a logical file

- Status: Accepted; implemented in PR #132
- Date: 2026-10-28

## Context

Feature-owned Settings definitions load lazily and may share a configuration file.
Allowing independent groups to declare the same section creates ambiguous ownership,
even when their keys have different names. Section documentation needs one owner.
Physical INI mapping claims alone cannot establish this logical metadata invariant.

## Decision

Within `(Settings.file, SettingGroup.id)`, exactly one SettingGroup instance may
own the declared section. Null is the primary logical file identity; named files
are independent. Multiple keys may reference the same group, and different files
may reuse group IDs and property paths. Distinct instances with equal IDs in the
same definition also conflict.

ConfigStore checks the candidate's allSettingKeys against its already loaded
Settings definitions in ensureLoaded, under the existing transaction lock and
before repository IO. No separate mutable ownership registry is maintained.
Conflicts identify the logical file, section and both Settings owners. Failed
loads install neither definitions, values nor repository claims and emit no events.

The INI repository and mapping contexts continue validating actual physical claims.
Structural keys may map into dynamically named sections that do not match the
key's declared group ID. ConfigStore neither parses INI nor predicts those names.

## Alternatives

Property-only collision checks allow ambiguous declared section owners. Enforcing
the rule only in the INI repository would couple logical metadata validation to
one persistence adapter. Another mutable ownership index duplicates loaded state
and adds rollback requirements without providing necessary information.

## Consequences

A file can contain multiple features, while declared groups and their generated
section documentation remain unambiguous. Conflicts fail before persistence IO,
and concurrent loading cannot register competing groups. Logical validation and
physical mapping validation remain separate and both are required.
