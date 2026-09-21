# Contributing to YAME

Thanks for taking an interest in YAME.

YAME is a compatibility layer for DOS and Windows 3.1-era clients connected over
a slow serial PPP link. Period-correct client behavior is an important part of
the project's design constraints, so changes that would be conventional for a
modern browser or proxy are not always appropriate here.

## Before starting

For substantial changes, consider opening an issue first so the intended
behavior and scope can be discussed before a large implementation is built.

Read [AGENTS.md](AGENTS.md) before making architectural or behavioral changes.
It documents the project's compatibility scope, development workflow, design
principles, and explicit non-goals. These guidelines apply to human and
AI-assisted contributions alike.

Security vulnerabilities should be reported according to
[SECURITY.md](SECURITY.md), not as public issues.

## Building and testing

YAME requires JDK 21 and includes the Gradle Wrapper.

Run the normal build and test suite with:

```shell
./gradlew clean build
```

Changes that affect PPP, networking, HTTP compatibility, or integration behavior
may also require the DOSBox compatibility suite:

```shell
bash scripts/dosbox-network-integration-test.sh
```

The integration test has additional system dependencies; see the CI workflow for
the current Linux setup.

Please do not submit changes that are knowingly uncompilable or leave relevant
tests failing. Bug fixes should include a regression test when practical.

## Pull requests

Keep pull requests focused enough that their behavior and invariants can be
reviewed independently. In the PR description, explain:

- what problem is being solved;
- any important compatibility or design decisions;
- how the change was tested; and
- any known limitations or follow-up work.

Prefer small, observable compatibility rules backed by tests over broad
heuristics. Observed behavior from period-appropriate clients such as Netscape
Navigator 4.08 and Trumpet Winsock is especially useful evidence.

## License

By intentionally submitting a contribution for inclusion in YAME, you agree
that it may be distributed under the terms of the
[Apache License 2.0](LICENSE), as described by Section 5 of that license.
