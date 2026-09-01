# Potential issues

These are known compatibility edge cases that are deliberately deferred until
real-world testing shows that the additional routing complexity is worthwhile.

## Identical HTTP and HTTPS references

Absolute references such as `http://example.test/x` and
`https://example.test/x` collapse to the same browser-visible legacy URL,
`http://example.test/x`. If both occur in one response or PPP session, YAME
cannot determine which original reference the client followed because the
client sends the same request for both.

The current exact mapping may therefore depend on rewrite order. Possible
future strategies include preferring HTTPS or generating a disambiguated
compatibility URL. Both change the intentionally simple clean-URL model, so this
is not a correctness requirement for PR #20.
