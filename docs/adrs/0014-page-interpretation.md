# ADR 0014: Page interpretation before legacy HTML rendering

- Status: Accepted
- Date: 2026-10-31

## Context

Removing CSS and scripts preserves source structure but often leaves modern pages
hard to read in Netscape-class browsers. Existing content extraction libraries
can identify meaningful content, but their output is not a legacy HTML contract.
Different libraries should be usable without coupling the HTTP proxy to their APIs.

## Decision

Add a small `PageInterpreter` boundary that accepts original HTML and its final
upstream URI and returns optional library-independent extracted content.
Start with Readability4J. Keep interpretation separate from `LegacyHtmlRenderer`,
which owns the supported HTML vocabulary, safe attributes, escaping and URLs.
Run this optional structural stage before existing cleanup, URL rewriting and
image optimization. Preserve source bytes and fall back to ordinary cleanup if
interpretation cannot supply bounded, useful output.

Use the existing feature-owned transformer setting and palette mechanism. Reader
mode is off by default because article extraction can discard navigation and
application controls. Request-scoped settings remain part of the transformation
profile and cache identity. Bound parser input, DOM traversal and output; do not
perform implicit network requests or execute page scripts.

## Alternatives

- CSS removal alone remains available but cannot identify the primary content.
- Sending a library's HTML directly would couple browser compatibility to that
  library and bypass YAME's output contract.
- A dynamic plugin system is unnecessary for the first adapter; ordinary interface
  implementations suffice.
- General layout/menu detection and site-specific GitHub rendering require their
  own interpretation policy and are not implemented by an article extractor.

## Consequences

A second library can reuse the renderer. Readability4J and its parser dependency
are confined to interpretation/rendering code. Article-oriented pages can become
simpler, while non-article pages may require the user to turn reader mode off.
Synthetic news, documentation and GitHub-shaped README fixtures exercise the
initial implementation; real legacy-browser visual validation remains separate.
