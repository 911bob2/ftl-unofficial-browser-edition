using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;
public static class Secrets {
    public static string Random(int bytes=32) => Convert.ToHexString(RandomNumberGenerator.GetBytes(bytes)).ToLowerInvariant();
    public static string Digest(string value)=>Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value)));
    public static void Restrict(string path) {if(!OperatingSystem.IsWindows())File.SetUnixFileMode(path,UnixFileMode.UserRead|UnixFileMode.UserWrite);}
    public static string UserId(this HttpContext c)=>c.User.FindFirstValue(ClaimTypes.NameIdentifier) ?? throw new InvalidOperationException("No user");
    public static string SessionId(this HttpContext c)=>c.User.FindFirstValue("ftl:session") ?? throw new InvalidOperationException("No session");
}
// Persistent sign-in: the cookie and its server-side row slide forward while the account is used, within a hard cap.
public static class SessionPolicy {
    public static readonly TimeSpan Sliding = TimeSpan.FromDays(30);
    public static readonly TimeSpan Absolute = TimeSpan.FromDays(180);
    public static readonly TimeSpan PurgeInterval = TimeSpan.FromMinutes(15);
}
public sealed class DatabaseTicketStore(IServiceScopeFactory scopes) : ITicketStore {
    // Rows are otherwise only removed when their own cookie is presented again, so abandoned sessions accumulated.
    public static async Task<int> PurgeAsync(GameDatabase db,CancellationToken cancellationToken=default) {
        var now=DateTime.UtcNow;var cutoff=now-SessionPolicy.Absolute;
        var sessions=await db.Sessions.Where(s=>s.ExpiresUtc<=now||s.CreatedUtc<=cutoff).ExecuteDeleteAsync(cancellationToken);
        await db.Leases.Where(l=>l.ExpiresUtc<=now.AddDays(-1)).ExecuteDeleteAsync(cancellationToken);
        return sessions;
    }
    public Task<string> StoreAsync(AuthenticationTicket ticket)=>StoreAsync(ticket,CancellationToken.None);
    public Task<string> StoreAsync(AuthenticationTicket ticket,HttpContext context,CancellationToken cancellationToken)=>StoreAsync(ticket,cancellationToken);
    public async Task<string> StoreAsync(AuthenticationTicket ticket,CancellationToken cancellationToken) {
        using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();var key=Secrets.Random();var now=DateTime.UtcNow;
        db.Sessions.Add(new(){Id=Secrets.Digest(key),UserId=ticket.Principal.FindFirstValue(ClaimTypes.NameIdentifier)!,Ticket=TicketSerializer.Default.Serialize(ticket),CreatedUtc=now,LastSeenUtc=now,ExpiresUtc=Expiry(ticket,now,now)});
        await db.SaveChangesAsync(cancellationToken);return key;
    }
    // The cookie handler stamps the ticket with its own expiry; the row follows it but never outlives the absolute cap.
    static DateTime Expiry(AuthenticationTicket ticket,DateTime now,DateTime created) {
        var expiry=ticket.Properties.ExpiresUtc?.UtcDateTime??now+SessionPolicy.Sliding;
        var cap=created+SessionPolicy.Absolute;return expiry>cap?cap:expiry;
    }
    public Task RenewAsync(string key,AuthenticationTicket ticket)=>RenewAsync(key,ticket,CancellationToken.None);
    public Task RenewAsync(string key,AuthenticationTicket ticket,HttpContext context,CancellationToken cancellationToken)=>RenewAsync(key,ticket,cancellationToken);
    public async Task RenewAsync(string key,AuthenticationTicket ticket,CancellationToken cancellationToken) {
        using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();
        var id=Secrets.Digest(key);var now=DateTime.UtcNow;var absoluteCutoff=now-SessionPolicy.Absolute;
        var created=await db.Sessions.Where(s=>s.Id==id).Select(s=>(DateTime?)s.CreatedUtc).SingleOrDefaultAsync(cancellationToken);
        if(created==null)return;
        // Calculate in .NET: SQLite date functions round fractional milliseconds.
        var expiry=Expiry(ticket,now,created.Value);
        var payload=TicketSerializer.Default.Serialize(ticket);
        // A revoked/expired row stays absent. Older refreshes cannot replace newer ones.
        await db.Sessions.Where(s=>s.Id==id&&s.ExpiresUtc>now&&s.CreatedUtc>absoluteCutoff&&s.LastSeenUtc<=now)
            .ExecuteUpdateAsync(update=>update.SetProperty(s=>s.Ticket,payload).SetProperty(s=>s.LastSeenUtc,now)
                .SetProperty(s=>s.ExpiresUtc,expiry),cancellationToken);
    }
    public Task<AuthenticationTicket?> RetrieveAsync(string key)=>RetrieveAsync(key,CancellationToken.None);
    public Task<AuthenticationTicket?> RetrieveAsync(string key,HttpContext context,CancellationToken cancellationToken)=>RetrieveAsync(key,cancellationToken);
    public async Task<AuthenticationTicket?> RetrieveAsync(string key,CancellationToken cancellationToken) {
        using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();
        var id=Secrets.Digest(key);var session=await db.Sessions.AsNoTracking().SingleOrDefaultAsync(s=>s.Id==id,cancellationToken);if(session==null)return null;
        var now=DateTime.UtcNow;var absoluteCutoff=now-SessionPolicy.Absolute;
        if(session.ExpiresUtc<=now||session.CreatedUtc<=absoluteCutoff){
            // Recheck persisted expiry: a refresh may have won since the read.
            await db.Sessions.Where(s=>s.Id==id&&(s.ExpiresUtc<=now||s.CreatedUtc<=absoluteCutoff)).ExecuteDeleteAsync(cancellationToken);
            return null;
        }
        var ticket=TicketSerializer.Default.Deserialize(session.Ticket);if(ticket?.Principal.Identity is ClaimsIdentity identity){foreach(var claim in identity.FindAll("ftl:session").ToArray())identity.RemoveClaim(claim);identity.AddClaim(new Claim("ftl:session",session.Id));}return ticket;
    }
    public Task RemoveAsync(string key)=>RemoveAsync(key,CancellationToken.None);
    public Task RemoveAsync(string key,HttpContext context,CancellationToken cancellationToken)=>RemoveAsync(key,cancellationToken);
    public async Task RemoveAsync(string key,CancellationToken cancellationToken) {using var scope=scopes.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteDeleteAsync(cancellationToken);}
}
public sealed class CookieValidation(UserManager<Player> users) : CookieAuthenticationEvents {
    public override async Task ValidatePrincipal(CookieValidatePrincipalContext c) {
        var user=await users.GetUserAsync(c.Principal!);
        var stamp=c.Principal!.FindFirstValue("AspNet.Identity.SecurityStamp");
        // Failed password attempts block new sign-ins, not established sessions.
        if(user==null||user.Disabled||stamp!=user.SecurityStamp){c.RejectPrincipal();await c.HttpContext.SignOutAsync(IdentityConstants.ApplicationScheme);return;}
        c.HttpContext.Items["Player"]=user;
        c.HttpContext.Items["AccountRole"]=AccountRoles.Canonical(await users.GetRolesAsync(user));
    }
    public override Task RedirectToLogin(RedirectContext<CookieAuthenticationOptions> c){c.Response.StatusCode=401;return Task.CompletedTask;}
    public override Task RedirectToAccessDenied(RedirectContext<CookieAuthenticationOptions> c){c.Response.StatusCode=403;return Task.CompletedTask;}
}
public static class Bootstrap {
    public static async Task Initialize(IServiceProvider services,string dataDir) {
        using var scope=services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();
        await db.Database.EnsureCreatedAsync();await db.Database.ExecuteSqlRawAsync("PRAGMA journal_mode=WAL;");
        // EnsureCreated does not add columns to an existing database. The snapshot kind (2026-10-21) is optional, so older
        // builds still read the table and the schema version stays 1.
        if(await db.Database.SqlQueryRaw<int>("SELECT COUNT(*) AS Value FROM pragma_table_info('Snapshots') WHERE name='Kind'").SingleAsync()==0)
            await db.Database.ExecuteSqlRawAsync("ALTER TABLE Snapshots ADD COLUMN Kind TEXT NULL;");
        var schema=await db.Settings.FindAsync("schema");if(schema!=null&&schema.Value!="1")throw new InvalidOperationException("Unsupported database schema; restore a compatible build.");
        if(schema==null){db.Settings.Add(new(){Key="schema",Value="1"});await db.SaveChangesAsync();}
        var roles=scope.ServiceProvider.GetRequiredService<RoleManager<IdentityRole>>();foreach(var name in AccountRoles.Names)if(!await roles.RoleExistsAsync(name))Check(await roles.CreateAsync(new(name)));
        if(await db.Settings.FindAsync("bootstrapped")!=null)return;
        var manager=scope.ServiceProvider.GetRequiredService<UserManager<Player>>();var credentials=new List<string>{"FTL local bootstrap credentials. Change the password at first sign-in. Remove this file after retrieval.",""};
        await using var transaction=await db.Database.BeginTransactionAsync();
        // Only the administrator is created; everyone else registers or is added from the Users panel.
        if(await manager.FindByNameAsync("admin")==null){var password=Secrets.Random(12);var player=new Player{UserName="admin"};Check(await manager.CreateAsync(player,password));Check(await manager.AddToRoleAsync(player,"Admin"));credentials.Add($"admin\t{password}");}
        db.Settings.Add(new(){Key="bootstrapped",Value=DateTime.UtcNow.ToString("O")});await db.SaveChangesAsync();
        var path=Path.Combine(dataDir,"bootstrap-credentials.txt");if(File.Exists(path))throw new IOException("Bootstrap credential file already exists; refusing to overwrite it.");
        await File.WriteAllLinesAsync(path,credentials);Secrets.Restrict(path);await transaction.CommitAsync();
    }
    public static void Check(IdentityResult r){if(!r.Succeeded)throw new InvalidOperationException(string.Join("; ",r.Errors.Select(e=>e.Description)));}
}
