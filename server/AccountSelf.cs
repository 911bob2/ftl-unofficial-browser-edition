using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;

// Endpoints an account uses on itself: public registration and deleting the account.
public static class AccountSelf {
    public static void Map(WebApplication app, string authCookie) {
        app.MapPost("/api/auth/register", async (RegisterRequest body, HttpContext h, UserManager<Player> users, SignInManager<Player> signIn, GameDatabase db, RegistrationPolicy policy, OnboardingCookies cookies, ILoggerFactory logger) => {
            // Same fixation rule as sign-in: a request that presents an old session cookie clears it and asks the client to retry.
            if (h.Request.Cookies.TryGetValue(authCookie, out var old) && !string.IsNullOrEmpty(old)) { await signIn.SignOutAsync(); return Results.Conflict(new { error = "Your previous session was cleared. Please try again." }); }
            if (!await policy.OpenAsync(db)) return Results.Json(new { error = "Registration is closed on this server.", code = "registration_closed" }, statusCode: 403);
            if (!AccountRules.ValidUsername(body.Username)) return Results.BadRequest(new { error = AccountRules.UsernameHelp });
            if (!AccountRules.ValidPasswordLength(body.Password)) return Results.BadRequest(new { error = AccountRules.PasswordHelp });
            // Instance-wide ceiling: the per-address limiter alone does not stop account flooding from many addresses.
            var since = DateTime.UtcNow.AddHours(-1);
            if (await db.Audits.CountAsync(a => a.Action == "user-registered" && a.CreatedUtc > since) >= policy.HourlyLimit) return Results.Json(new { error = "Too many accounts were created recently. Try again later." }, statusCode: 429);
            Player? created = null;
            var result = await StateWrites.Run(h, logger.CreateLogger("AccountSelf"), async () => {
                await using var tx = await db.Database.BeginTransactionAsync();
                // Password hashing happens before the uniqueness check, so taken and free names take the same time.
                var player = new Player { UserName = body.Username, MustChangePassword = false };
                var made = await users.CreateAsync(player, body.Password!);
                if (!made.Succeeded) return Results.BadRequest(new { error = made.Errors.Any(e => e.Code.StartsWith("Password")) ? AccountRules.PasswordHelp : "That username is not available. Choose a different one." });
                Bootstrap.Check(await users.AddToRoleAsync(player, "ExternalUser"));
                db.Audits.Add(new() { ActorId = player.Id, SubjectId = player.Id, Action = "user-registered" });
                await db.SaveChangesAsync(); await tx.CommitAsync(); created = player;
                return Results.Ok(new { mustChangePassword = false });
            });
            if (created == null) return result;
            await signIn.SignInAsync(created, true); cookies.SetMode(h.Response, "cloud");
            return result;
        }).RequireRateLimiting("register");

        app.MapPost("/api/auth/delete-account", async (DeleteAccountRequest body, HttpContext h, UserManager<Player> users, SignInManager<Player> signIn, GameDatabase db, LauncherTests tests, ILoggerFactory logger) => {
            var p = (Player)h.Items["Player"]!;
            if (string.IsNullOrEmpty(body.Password) || body.Password.Length > 128) return Results.BadRequest(new { error = "Enter your current password" });
            var check = await signIn.CheckPasswordSignInAsync(p, body.Password, true);
            if (!check.Succeeded) return Results.BadRequest(new { error = check.IsLockedOut ? "Too many failed attempts. Try again later." : "The password is incorrect" });
            var result = await StateWrites.Run(h, logger.CreateLogger("AccountSelf"), async () => {
                await using var tx = await db.Database.BeginTransactionAsync();
                var player = await users.FindByIdAsync(p.Id); if (player == null) return Results.NotFound();
                if (await db.Settings.FindAsync(AccountLifecycle.DeletePrefix + player.Id) != null) return Results.Json(new { status = "deleting" }, statusCode: 202);
                if (await AccountAdmin.FinalAdministrator(player, users)) return Results.BadRequest(new { error = "You are the final enabled administrator. Create or enable another administrator before deleting this account." });
                // The same pipeline as an administrator deleting the account: disable and revoke now, AccountLifecycle erases the data.
                player.Disabled = true; Bootstrap.Check(await users.UpdateAsync(player)); await AccountAdmin.Revoke(player, users, db, tests);
                db.Settings.Add(new() { Key = AccountLifecycle.DeletePrefix + player.Id, Value = DateTime.UtcNow.ToString("O") });
                db.Audits.Add(new() { ActorId = player.Id, SubjectId = player.Id, Action = "user-deletion-requested" });
                await db.SaveChangesAsync(); await tx.CommitAsync();
                return Results.Json(new { status = "deleting" }, statusCode: 202);
            });
            if (result is IStatusCodeHttpResult { StatusCode: 202 }) await signIn.SignOutAsync();
            return result;
        }).RequireAuthorization().RequireRateLimiting("login");
    }
}
public record RegisterRequest(string? Username, string? Password);
public record DeleteAccountRequest(string? Password);
public record RegistrationRequest(bool Open);
