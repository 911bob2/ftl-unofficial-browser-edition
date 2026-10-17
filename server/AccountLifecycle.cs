using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;

// API mutations acquire this gate before authentication. Queued writes validate
// current account/session state. SQLite already serializes writers; reads and
// diagnostic exports remain parallel.
public sealed class AccountLifecycle(IServiceScopeFactory scopes, DebugLogs logs, LauncherTests tests, ILogger<AccountLifecycle> logger) : BackgroundService {
    public const string DeletePrefix = "delete-account:";
    public SemaphoreSlim Writes { get; } = new(1, 1);
    protected override async Task ExecuteAsync(CancellationToken stoppingToken) {
        var nextPurge = DateTime.MinValue;
        while (!stoppingToken.IsCancellationRequested) {
            try {
                await Writes.WaitAsync(stoppingToken);
                try {
                    using var scope = scopes.CreateScope();
                    var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
                    var users = scope.ServiceProvider.GetRequiredService<UserManager<Player>>();
                    // Expired sign-in sessions are otherwise only deleted when their own cookie returns.
                    if (DateTime.UtcNow >= nextPurge) { var purged = await DatabaseTicketStore.PurgeAsync(db, stoppingToken); nextPurge = DateTime.UtcNow + SessionPolicy.PurgeInterval; if (purged > 0) logger.LogInformation("Removed {Count} expired sign-in sessions", purged); }
                    foreach (var intent in await db.Settings.Where(s => s.Key.StartsWith(DeletePrefix)).ToListAsync(stoppingToken)) {
                        var id = intent.Key[DeletePrefix.Length..];
                        if (!tests.RemoveOwner(id) || !await logs.DeleteAccount(id)) continue;
                        await using var tx = await db.Database.BeginTransactionAsync(stoppingToken);
                        await db.Sessions.Where(s => s.UserId == id).ExecuteDeleteAsync(stoppingToken);
                        await db.Leases.Where(s => s.UserId == id).ExecuteDeleteAsync(stoppingToken);
                        await db.Saves.Where(s => s.UserId == id).ExecuteDeleteAsync(stoppingToken);
                        await db.Snapshots.Where(s => s.UserId == id).ExecuteDeleteAsync(stoppingToken);
                        var player = await users.FindByIdAsync(id);
                        if (player != null) Bootstrap.Check(await users.DeleteAsync(player));
                        db.Settings.Remove(intent); db.Audits.Add(new() { SubjectId = id, Action = "user-deleted" });
                        await db.SaveChangesAsync(stoppingToken); await tx.CommitAsync(stoppingToken);
                    }
                } finally { Writes.Release(); }
            } catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception e) { logger.LogError(e, "Account deletion cleanup will retry"); }
            try { await Task.Delay(TimeSpan.FromSeconds(2), stoppingToken); }
            catch (OperationCanceledException) { break; }
        }
    }
}
