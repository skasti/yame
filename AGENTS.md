# YAME contributor and review scope

YAME is a compatibility layer for DOS and Windows 3.1-era clients connected over
a slow serial PPP link. The primary browser target is Netscape Navigator 4.08,
which mostly speaks HTTP/1.0 and cannot negotiate modern Internet TLS. Review
changes against that target rather than against the requirements of a modern
browser, forward proxy, or transparent TLS MITM.

## HTTP compatibility scope

The HTTP compatibility proxy has deliberately pragmatic goals:

- accept the legacy client's HTTP request on TCP port 80;
- use the host JVM's current HTTP/TLS stack upstream;
- return a finite HTTP/1.0 response with `Connection: close`, no chunked framing,
  and a correct content length;
- fully acquire the upstream response before emitting the corresponding response
  to the legacy client. The compatibility proxy is a store/transform/serve
  boundary: TCP/PPP/serial backpressure from the legacy side must never throttle
  an in-progress upstream HTTP body read;
- keep TCP byte-stream handling and HTTP semantics in separate layers:
  `HttpTcpProxy` owns incremental HTTP decoding/encoding and connection-level
  backpressure, while compatibility/resource code implements `HttpRequestHandler`
  and must not consume or emit `TcpProxyEvent` directly;
- retain the complete upstream source representation separately from any
  client-visible transformed representation. A resource is `READY` when the
  complete client representation exists locally, before downstream delivery begins;
- keep resource graph knowledge at HTTP-proxy/process lifetime. It must survive
  TCP flow closure, PPP generation changes, and browser restarts; revisiting a
  navigation root reuses and extends its existing graph rather than replacing it;
- request `Accept-Encoding: identity` for text that may need rewriting;
- replace absolute `https://` references in supported text responses with clean
  `http://` references and remember the upstream HTTPS target for the PPP
  session;
- distinguish browser navigation targets from embedded/subresource targets when
  deciding how redirects are exposed;
- expose redirects for navigation-like requests when the clean HTTP URL changes,
  so Netscape owns the new host, path, query, and relative-URL base;
- keep redirects for rewritten subresources (for example scripts, stylesheets,
  images, and CSS resources) internal to YAME. Real Netscape Navigator 4.08
  testing showed that returning such a redirect can cause the browser to
  navigate the top-level window to the resource URL instead of merely following
  the subresource redirect;
- when an internally followed subresource redirect changes host or path, treat
  the final upstream URI as the resource's effective base URI. Relative
  references in supported rewritable content must therefore be resolved against
  that final URI and rewritten to clean absolute compatibility URLs before the
  response is returned to the legacy browser;
- still follow a redirect internally when rewriting HTTPS to HTTP would otherwise
  produce a redirect back to the exact URL the client just requested.

Prefer small, observable compatibility rules and tests with period-appropriate
clients. Observed legacy-client behavior takes precedence over modern-browser
redirect expectations when the two differ. Do not add semantic HTML/CSS/JavaScript
parsing merely to avoid changing visible URL text. Rewriting every literal
absolute `https://` URL in a supported text body is intentional.

## Explicit non-goals for this layer

Do not request or add these as correctness requirements for HTTP URL/TLS
compatibility work unless a separate feature explicitly scopes them in:

- WebSocket, `wss://`, or Server-Sent Events support;
- modern JavaScript transpilation or complete script sanitization;
- HTML5-to-HTML3 conversion or general page re-rendering;
- full CSS compatibility rewriting;
- image conversion for PNG, WebP, AVIF, video, or other unsupported media;
- exhaustive RFC handling for unusual URL spellings, every content type, or
  every character encoding;
- direct `https://` navigation on TCP port 443. That requires a separate,
  explicitly designed legacy-TLS termination fallback.

Cookie simplification, user-agent substitution, script/style stripping, and
image transcoding may be useful future compatibility stages. They are not part
of the HTTP-to-HTTPS routing and URL-rewrite feature.

Security review is still in scope: never weaken TLS toward the modern upstream,
leak credentials or cookies across origins, corrupt HTTP framing, or permit
unbounded buffering.
