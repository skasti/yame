# Security Policy

YAME is an experimental compatibility project that accepts untrusted serial and
network input. Security issues that could affect the host running YAME, expose
credentials, cross security boundaries, or enable unintended network access are
in scope for responsible disclosure.

## Reporting a vulnerability

Please do **not** report security vulnerabilities in a public GitHub issue.

Use GitHub's private vulnerability reporting / Security Advisory flow for this
repository when it is available. If private reporting is not available, contact
the repository owner through GitHub before sharing vulnerability details
publicly.

When reporting a vulnerability, include enough information to reproduce and
understand the issue, such as:

- the affected YAME version or commit;
- the relevant configuration and client environment;
- reproduction steps or a minimal proof of concept;
- the expected and observed behavior; and
- any known security impact or mitigations.

Please avoid including real credentials, private network details, or unrelated
personal data in reports.

## Supported versions

YAME is currently pre-1.0 and under active development. Security fixes are made
against the current development line rather than maintained release branches.
Users should reproduce issues against the latest release or current `main`
where practical.
