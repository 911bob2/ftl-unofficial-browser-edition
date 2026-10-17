using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;
public static class AccountAdmin {
    public static void Map(RouteGroupBuilder api) {
        api.MapGet("/users/{id}/data", async (string id, GameDatabase db, DebugLogs logs) => {
            if (!await db.Users.AnyAsync(p => p.Id == id)) return Results.NotFound();
            return Results.Ok(new { currentSave = await db.Saves.AnyAsync(s => s.UserId == id), snapshots = await db.Snapshots.CountAsync(s => s.UserId == id), diagnostics = await logs.AccountData(id) });
        });
        api.MapGet("/users", async (UserManager<Player> users, GameDatabase db) => {
            var now = DateTime.UtcNow;
            var counts = await db.Sessions.Where(s => s.ExpiresUtc > now && s.CreatedUtc > now.AddHours(-24)).GroupBy(s => s.UserId).Select(g => new { Id = g.Key, Count = g.Count() }).ToDictionaryAsync(g => g.Id, g => g.Count);
            var deleting = await db.Settings.Where(s => s.Key.StartsWith(AccountLifecycle.DeletePrefix)).Select(s => s.Key).ToListAsync();
            var result = new List<object>();
            foreach (var p in await users.Users.OrderBy(p => p.UserName).ToListAsync()) result.Add(new {
                p.Id, p.UserName, p.Disabled, p.MustChangePassword, p.CreatedUtc,
                role = AccountRoles.Canonical(await users.GetRolesAsync(p)), version = p.ConcurrencyStamp,
                activeSessions = counts.GetValueOrDefault(p.Id), lifecycle = deleting.Contains(AccountLifecycle.DeletePrefix + p.Id) ? "deleting" : p.Disabled ? "disabled" : "active"
            });
            return Results.Ok(result);
        });
        api.MapPost("/users", async (NewUserRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, ILoggerFactory logger) => {
            if(!AccountRules.ValidUsername(body.Username)) return Results.BadRequest(new { error = AccountRules.UsernameHelp });
            var role = AccountRoles.Parse(body.Role); if(role == null) return Results.BadRequest(new { error = "Choose Admin, Normal, or External user" });
            return await StateWrites.Run(h, logger.CreateLogger("AccountAdmin"), async () => {
                await using var tx = await db.Database.BeginTransactionAsync();
                var password = Secrets.Random(12); var p = new Player { UserName = body.Username };
                var created = await users.CreateAsync(p, password); if(!created.Succeeded) return Results.BadRequest(new { error = "Username unavailable" });
                Bootstrap.Check(await users.AddToRoleAsync(p, role)); Audit(db, h, p.Id, "user-created:" + body.Role);
                await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.Ok(new { username = p.UserName, temporaryPassword = password });
            });
        });
        api.MapPatch("/users/{id}/role", async (string id, RoleRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, LauncherTests tests, ILoggerFactory logger) => {
            var role = AccountRoles.Parse(body.Role); if(role == null) return Results.BadRequest(new { error = "Unknown account role" });
            return await Change(id, body.Version, true, h, users, db, logger, async p => {
                var previous = AccountRoles.Canonical(await users.GetRolesAsync(p));
                if(previous == body.Role) return Results.NoContent();
                if(body.Role != "admin" && await Protected(p, h, users)) return Results.BadRequest(new { error = "You cannot demote yourself or the final enabled administrator" });
                Bootstrap.Check(await users.RemoveFromRolesAsync(p, await users.GetRolesAsync(p)));
                Bootstrap.Check(await users.AddToRoleAsync(p, role)); await Revoke(p, users, db, tests);
                Audit(db, h, id, "role-changed:" + previous + ":" + body.Role); return Results.NoContent();
            });
        });
        api.MapPost("/users/{id}/disable", async (string id, DisableRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, LauncherTests tests, ILoggerFactory logger) =>
            await Change(id, body.Version, true, h, users, db, logger, async p => {
                if(body.Disabled && await Protected(p, h, users)) return Results.BadRequest(new { error = "You cannot disable yourself or the final enabled administrator" });
                p.Disabled = body.Disabled; Bootstrap.Check(await users.UpdateAsync(p)); await Revoke(p, users, db, tests);
                Audit(db, h, id, body.Disabled ? "user-disabled" : "user-enabled"); return Results.NoContent();
            }));
        api.MapPost("/users/{id}/reset-password", async (string id, VersionRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, LauncherTests tests, ILoggerFactory logger) =>
            await Change(id, body.Version, true, h, users, db, logger, async p => {
                var password = Secrets.Random(12); Bootstrap.Check(await users.ResetPasswordAsync(p, await users.GeneratePasswordResetTokenAsync(p), password));
                p.MustChangePassword = true; p.LockoutEnd = null; p.AccessFailedCount = 0; Bootstrap.Check(await users.UpdateAsync(p)); await Revoke(p, users, db, tests);
                Audit(db, h, id, "password-reset"); return Results.Ok(new { temporaryPassword = password });
            }));
        api.MapPost("/users/{id}/revoke", async (string id, VersionRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, LauncherTests tests, ILoggerFactory logger) =>
            await Change(id, body.Version, true, h, users, db, logger, async p => { await Revoke(p, users, db, tests); Audit(db, h, id, "sessions-revoked"); return Results.NoContent(); }));
        api.MapDelete("/users/{id}", async (string id, [Microsoft.AspNetCore.Mvc.FromBody] DeleteUserRequest body, HttpContext h, UserManager<Player> users, GameDatabase db, LauncherTests tests, ILoggerFactory logger) =>
            await Change(id, body.Version, true, h, users, db, logger, async p => {
                if(body.ConfirmUsername != p.UserName) return Results.BadRequest(new { error = "Type the exact username to delete this account" });
                if(await Protected(p, h, users)) return Results.BadRequest(new { error = "You cannot delete yourself or the final enabled administrator" });
                p.Disabled = true; Bootstrap.Check(await users.UpdateAsync(p)); await Revoke(p, users, db, tests);
                db.Settings.Add(new() { Key = AccountLifecycle.DeletePrefix + id, Value = DateTime.UtcNow.ToString("O") }); Audit(db, h, id, "user-deletion-requested");
                return Results.Json(new { status = "deleting" }, statusCode: 202);
            }));
        api.MapGet("/settings", async (GameDatabase db, RegistrationPolicy registration) => Results.Ok(new { registrationOpen = await registration.OpenAsync(db), registrationLocked = registration.Forced != null }));
        api.MapPut("/settings/registration", async (RegistrationRequest body, HttpContext h, GameDatabase db, RegistrationPolicy registration, ILoggerFactory logger) => {
            if(registration.Forced != null) return Results.Conflict(new { error = "Registration is fixed by the server configuration (FTL_PUBLIC_REGISTRATION)" });
            return await StateWrites.Run(h, logger.CreateLogger("AccountAdmin"), async () => {
                var row = await db.Settings.FindAsync(RegistrationPolicy.Key); if(row == null) db.Settings.Add(row = new() { Key = RegistrationPolicy.Key });
                var value = body.Open ? "open" : "closed";
                if(row.Value != value) { row.Value = value; db.Audits.Add(new() { ActorId = h.UserId(), Action = body.Open ? "registration-opened" : "registration-closed" }); }
                await db.SaveChangesAsync(); return Results.Ok(new { registrationOpen = body.Open });
            });
        });
        api.MapGet("/audit", async (GameDatabase db) => {
            var names = await db.Users.ToDictionaryAsync(p => p.Id, p => p.UserName);
            return Results.Ok((await db.Audits.OrderByDescending(a => a.Id).Take(200).ToListAsync()).Select(a => new { a.Id, a.CreatedUtc, a.Action, a.ActorId, a.SubjectId, actor = a.ActorId == null ? null : names.GetValueOrDefault(a.ActorId), subject = a.SubjectId == null ? null : names.GetValueOrDefault(a.SubjectId) }));
        });
    }
    private static void Audit(GameDatabase db, HttpContext h, string id, string action) => db.Audits.Add(new() { ActorId = h.UserId(), SubjectId = id, Action = action });
    internal static async Task<bool> FinalAdministrator(Player p, UserManager<Player> users) => !p.Disabled && await users.IsInRoleAsync(p, "Admin") && (await users.GetUsersInRoleAsync("Admin")).Count(u => !u.Disabled) <= 1;
    private static async Task<bool> Protected(Player p, HttpContext h, UserManager<Player> users) => p.Id == h.UserId() || await FinalAdministrator(p, users);
    internal static async Task Revoke(Player p, UserManager<Player> users, GameDatabase db, LauncherTests tests) {
        Bootstrap.Check(await users.UpdateSecurityStampAsync(p));
        await db.Sessions.Where(s => s.UserId == p.Id).ExecuteDeleteAsync(); await db.Leases.Where(s => s.UserId == p.Id).ExecuteDeleteAsync(); tests.RemoveOwner(p.Id);
    }
    private static async Task<IResult> Change(string id, string? version, bool required, HttpContext h, UserManager<Player> users, GameDatabase db, ILoggerFactory logger, Func<Player, Task<IResult>> change) =>
        await StateWrites.Run(h, logger.CreateLogger("AccountAdmin"), async () => {
            await using var tx = await db.Database.BeginTransactionAsync();
            var p = await users.FindByIdAsync(id); if(p == null) return Results.NotFound();
            if(await db.Settings.FindAsync(AccountLifecycle.DeletePrefix + id) != null) return Results.Conflict(new { error = "Account deletion is pending" });
            if(required && version == null || version != null && version != p.ConcurrencyStamp) return Results.Conflict(new { error = "This account changed. Refresh before trying again", code = "stale_account" });
            var result = await change(p);
            if(result is IStatusCodeHttpResult { StatusCode: >= 400 }) return result;
            await db.SaveChangesAsync(); await tx.CommitAsync(); return result;
        });
}
public record NewUserRequest(string Username, string? Role);
public record RoleRequest(string? Role, string? Version);
public record DisableRequest(bool Disabled, string? Version);
public record VersionRequest(string? Version);
public record DeleteUserRequest(string? ConfirmUsername, string? Version);
