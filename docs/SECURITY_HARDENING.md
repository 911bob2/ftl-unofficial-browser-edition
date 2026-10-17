# Security hardening behavior

The corrected hardening retains separate EF contexts for cookie-store operations.
Renewal uses a conditional SQL update: revoked or expired sessions cannot be
recreated, an older refresh cannot overwrite a newer one, and the absolute lifetime
is 180 days (sign-in is persistent and slides forward 30 days while the account is
used). Expired-session cleanup rechecks the persisted expiry, and a periodic purge
(every 15 minutes) removes expired or over-age session rows and long-expired play
leases, so abandoned sessions never accumulate. Invalid
security stamps and disabled accounts clear cookies; password-attempt lockout blocks
new sign-ins while preserving established sessions.

`TRUSTED_PROXIES` accepts comma-separated IP addresses and CIDR networks. Empty
development configuration disables forwarded-header processing. Production still
requires explicit trust; invalid values and universal `/0` networks fail startup.
IPv4-mapped IPv6 addresses work with IPv4 trust entries. IPv4 and IPv6 loopback
addresses are distinct; configure each address actually used by the proxy.
Only the client IP and scheme are forwarded. The public Host check remains active.
Trust a subnet only when every peer able to reach the service on it is trusted.

Play lease acquisition requires explicit takeover whenever an unexpired lease is
held by a different tab or device, including tabs sharing the authentication cookie.
The current holder can supply its existing `lease` token for idempotent reacquisition.
Takeover rotates the token and fences stale heartbeats and saves. No database schema
change is required. Save revisions and snapshots remain transactional. SQLite
contention returns a retryable 503; disk and other storage failures retain error
logging instead of masquerading as revision conflicts.

Diagnostic exports pin immutable chunks before releasing the logging gate. Delete
and quota eviction remove session visibility but defer physical deletion of pinned
chunks until downloads finish. Those bytes remain charged against quotas. Streaming
never holds the logging gate. Limits are one export per account, two per process,
and five minutes per download. Checkpoint reconstruction scans at most 256 Mi
characters; if it reaches that limit, `export-status.json` explicitly reports partial
reconstruction while all retained compressed log chunks remain included. Cancellation
releases pins and export slots. Disk deletion failures stay charged and are retried.

Server diagnostic payloads use the same property redaction as browser records.
Callers must still avoid putting credentials in arbitrary message strings. XML save
validation prohibits DTDs and external resolution, limits size and nesting, and
requires the expected unqualified root. Audio manifests and persisted chunk names
accept fixed filename components, excluding traversal, alternate streams and encoded
path separators. HSTS retains its existing host scope.

The packaged Security suite includes `tests/server-security`, a standalone .NET
regression executable, followed by the HTTP security tests. Run backend checks with:

```powershell
dotnet run --project tests/server-security/Ftl.SecurityTests.csproj --no-launch-profile
```

The HTTP suite uses fresh disposable fixtures and real cookie/session expiry. It
does not weaken the login limiter to run its assertions. Test-run starts are limited
to five requests per account per five minutes, in addition to the existing single
active server-job guard. Fixture databases, credentials, exports and results remain
private under ignored `.build/` paths.
