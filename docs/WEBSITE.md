# Website, accounts and local game files

The website uses one fixed dark FTL design: the official website's space banner,
logo and honeycomb texture, combined with angular game-style panels and controls.
There is no appearance picker, color-mode choice or separate Retro interface.
Legacy theme preferences have no effect. Artwork and the Chakra Petch display font
are self-hosted; the original game's private fonts/assets are not bundled.

The full banner appears on sign-in and Play; utility screens use the compact
header and gameplay keeps only its toolbar and canvas. Existing URLs, account
permissions, game controls and save behavior are preserved.

## Account permissions

| Role | Play, cloud saves, local tests | Server game assets | Server tests | Users administration |
| --- | --- | --- | --- | --- |
| Admin (`Admin`, API `admin`) | Yes | Yes | Yes | Yes |
| Normal (`User`, API `user`) | Yes | Yes | No | No |
| External user (`ExternalUser`, API `external-user`) | Yes | No | No | No |

Accounts must have exactly one recognized application role. Ambiguous assignments
fail closed. Existing assignments are preserved; the extra Identity role is
seeded idempotently. Administrators choose a role when creating an account (the
form defaults to External user); visitors can also register themselves as External
users (see below).

Users includes search, filters, sorting, a details drawer, security actions and
per-account errors. Temporary credentials appear only in a dismissible panel.
Role changes, disablement, password resets and revocation invalidate the account's
sessions and play leases. Losing administrator access cancels owned server jobs.
Self-disablement, self-demotion and self-deletion are forbidden. Transactional
checks preserve the final enabled administrator and reject stale account versions.

Deletion requires the exact username and first disables access. Cleanup intent
is persisted in Settings, so failed or interrupted cleanup retries after restart.
The account remains **Deletion pending** until saves, snapshots, diagnostics,
sessions, leases, job state and Identity records are removed. Minimal security
audit records remain. Remote browser caches and operational backups are not erased.

## First visit, registration and local-only mode

**First visit.** A new browser sees one screen: an unofficial-fan-project notice
(not made, endorsed or supported by Subset Games; FTL belongs to Subset Games) with
one **Agree** button. After that it chooses **Cloud saves** or **Local only**. The
server renders the right first screen from two cookies, so nothing flashes:

| Cookie (Production / Development) | Meaning |
| --- | --- |
| `__Host-ftl-consent` / `ftl-consent-local-<port>` | The notice was accepted |
| `__Host-ftl-mode` / `ftl-mode-local-<port>` | `cloud` or `local` |

Both are written by the server (a script-written cookie is capped at seven days in
Safari), last 400 days, are HttpOnly, SameSite=Lax and Secure over HTTPS, and are not
secrets. Later visits go to sign-in (cloud) or the local launcher (local). Signing in
or registering sets the mode to cloud. The sign-in screen offers **Play local only**;
`/?view=about` is reachable before consent.

**Instances and accounts.** A new instance creates only `admin`; its temporary
password is in `bootstrap-credentials.txt` in the data directory. Existing databases
keep their accounts. `dotnet Ftl.Server.dll create-accounts name:role ...` (roles
`admin`, `user`, `external-user`) adds accounts offline for disposable fixtures and
appends their temporary passwords to the same file.

**Registration.** `POST /api/auth/register` creates an External user and signs the
account in. It uses the administrator username rules and the Identity password policy
(15 to 128 characters), requires the antiforgery token and a same-origin request,
refuses a request that still carries a session cookie, writes a `user-registered`
audit entry and answers every taken name with the same message. Limits: five
registrations per address per ten minutes (`FTL_REGISTER_LIMIT`) and sixty per hour
for the instance (`FTL_REGISTRATION_HOURLY_LIMIT`). Sign-in allows twenty attempts per address per five minutes; the public endpoints (consent, mode, engine metadata) allow 240 per address per minute; the test runner raises both (`FTL_LOGIN_LIMIT`, `FTL_PUBLIC_LIMIT`) for its disposable fixtures only. Public registration is open by
default; an administrator closes it in **Users**, or `FTL_PUBLIC_REGISTRATION=false`
pins it closed (the panel then shows it as fixed). While closed the screens say so.

**Sessions.** Sign-in is persistent: the cookie slides forward 30 days while the
account is used and ends 180 days after sign-in at the latest. Rows are purged
server-side, so there is no session list. Data Protection keys live in the data
directory, so restarts keep sessions. A password change, role change, disablement or
revocation still ends them, and Sign out clears the cookie.

**Account deletion.** **Account** offers a permanent, irreversible deletion that
needs the current password, can export a save backup first and uses the same
deletion pipeline as an administrator (the account is signed out at once and shows
as deletion pending until its data is removed). The final enabled administrator
cannot delete their own account.

**Local-only mode.** No account, and nothing is written to the server. Anonymous
visitors read exactly what an External user reads, through `/api/public/game/config`
and `/api/public/game/resources` (engine metadata and the engine resource bundle);
saves, private assets and every other API still need sign-in. The visitor's own
`ftl.dat` is cached in this browser as before. Saves are kept in IndexedDB (current
save plus ten history entries, committed in one transaction with a revision
counter) with a copy in localStorage for when IndexedDB is cleared, blocked or the
tab closes before its commit finishes; the higher revision wins on load and the
other store is repaired. Cookies are not used for saves: about 4 KB each and sent
with every request. The browser is asked for persistent storage when the game is
launched and the Saves panel shows whether it was granted. **Saves** offers a local
backup (the same JSON as a cloud backup), import and recovery snapshots; **Logs**
records device-local diagnostics. Local navigation is Play, Saves, Logs and About.

**Enable cloud saves** (header, local mode) leads to register or sign in. If a local
save exists, a dialog moves it into the account: with no cloud save it is uploaded,
read back and removed from the browser; with an existing cloud save the visitor
chooses which one to keep after being offered a backup of either. Nothing is
overwritten without that choice, and the Saves page keeps a card for moving the
browser save later. The cached `ftl.dat` is copied to the new account.

**Saving from code.** `window.ftlSaveDurably()` captures the engine and resolves
after the cloud write or the local IndexedDB commit has finished (`{saved, backend,
revision}`); it throws when the save could not be stored.

## External users: choose your ftl.dat

External users choose their own unmodified DAT matching this deployment. File
hashing, index validation and preparation happen in a worker. The file and index
are cached in account-scoped IndexedDB. Replacement commits only after validation;
failed and cancelled replacements preserve the previous valid copy.

Play and Tests share the same asset provider. The browser assembles boot resources
from the local DAT and an independently versioned bundle of reviewed engine-owned
resources. Audio is read lazily from Blob slices. `tools/engine-resources.json`
is the explicit redistributable allowlist; no commercial DAT content enters the
Docker image or that bundle.

Local files survive logout and ordinary browser restarts for the same account.
Browsers can evict storage, and private browsing or quota restrictions can prevent
it. The UI reports storage errors and offers **Use for this session** explicitly.
That fallback lasts for the current page; it is not guaranteed across navigation.
Replace/Remove controls are available on Play and Account. Removing local files
does not alter cloud progress. Authorization is still required: this is not
offline play.

There is no asset upload endpoint. DAT bytes and extracted resources never enter
request bodies or diagnostics. External accounts never fall back to server assets,
including assets previously cached by a different account. Save compatibility and
import checks use metadata rather than the restricted manifest.

## Interfaces and security

- `/api/auth/me` includes canonical `role` and explicit `capabilities`, retaining
  the existing `admin` field.
- `/api/game/config` returns account-bound compatibility metadata, asset source
  and independent engine-resource identity; it contains no commercial bytes.
- `/api/game/resources` serves only the reviewed engine bundle to playable accounts.
- Every private `/api/assets` operation requires Admin or Normal.
- Every `/api/tests` operation requires Admin, including availability and polling.
- Account administration mutations require the current `version`; role changes
  use `PATCH /api/admin/users/{id}/role`. Deletion uses `DELETE` with
  `confirmUsername` and `version`. The details endpoint reports the live data removed.
- Website requests carry `X-FTL-Account` to reject operations from a stale account
  tab. Cookies, expiry, password-change requirements and CSRF protection still apply.

Authentication is server-validated before the initial Razor shell is displayed.
Client session checks distinguish signed-out, required password change and
retryable connection failure. Optional account tools cannot turn a valid session
into a sign-in screen. Game and diagnostics modules load when needed. Navigation
retains pending-save recovery, revision checks and lease protection.

## Verification and recovery

Mutating acceptance scripts require disposable fixtures. Focused coverage lives
in `tests/accounts.mjs`, `tests/account-recovery.mjs`, `tests/session-ui.mjs`,
`tests/dat.mjs` and `tests/website-ui.mjs`. The packaged seven groups and authenticated
launcher acceptance also exercise existing engine/save/diagnostic behavior.
Keep real DATs, browser profiles, screenshots, credentials and traces in ignored
`.build/`; do not commit them.

Recovery must preserve these role restrictions. An older image using the broad
Play policy is unsafe after External user accounts exist. The website recovery
image retains the current authorization and deletion services with the previous
website. External importing can be temporarily unavailable during recovery;
server assets remain denied. Use the same environment's persistent database,
encryption keys, saves, settings and matching private assets.

Stop the service before operator recovery. `dotnet Ftl.Server.dll recover-admin`
selects an existing canonical administrator that is not pending deletion; an
optional username chooses one explicitly. It never creates or promotes an account.
The one-time credential is written privately to `/data/admin-recovery.txt`.

Implementation references: [ASP.NET role authorization](https://learn.microsoft.com/en-us/aspnet/core/security/authorization/roles?view=aspnetcore-10.0),
[Web Workers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers),
[browser storage limits](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria).
