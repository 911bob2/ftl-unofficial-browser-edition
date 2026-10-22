using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;
public sealed class Player : IdentityUser {
    public bool Disabled {get;set;}
    public bool MustChangePassword {get;set;} = true;
    public DateTime CreatedUtc {get;set;} = DateTime.UtcNow;
}
public sealed class StoredSession {
    [Key] public string Id {get;set;} = "";
    public string UserId {get;set;} = "";
    public byte[] Ticket {get;set;} = [];
    public DateTime CreatedUtc {get;set;}
    public DateTime LastSeenUtc {get;set;}
    public DateTime ExpiresUtc {get;set;}
}
public sealed class PlayerSave {
    [Key] public string UserId {get;set;} = "";
    [ConcurrencyCheck] public long Revision {get;set;}
    public string? Run {get;set;}
    public string? Profile {get;set;}
    public string EngineBuild {get;set;} = "";
    public string AssetVersion {get;set;} = "";
    public DateTime UpdatedUtc {get;set;}
}
public sealed class SaveSnapshot {
    public long Id {get;set;}
    public string UserId {get;set;} = "";
    public long Revision {get;set;}
    public string Payload {get;set;} = "";
    public DateTime CreatedUtc {get;set;}
    // "auto", "manual", "import" or "restore"; null for snapshots made before 2026-10-21.
    public string? Kind {get;set;}
}
public sealed class PlayLease {
    [Key] public string UserId {get;set;} = "";
    public string SessionId {get;set;} = "";
    public string Token {get;set;} = "";
    public DateTime ExpiresUtc {get;set;}
}
public sealed class SecurityAudit {
    public long Id {get;set;}
    public DateTime CreatedUtc {get;set;} = DateTime.UtcNow;
    public string? ActorId {get;set;}
    public string Action {get;set;} = "";
    public string? SubjectId {get;set;}
}
public sealed class AppSetting {
    [Key] public string Key {get;set;} = "";
    public string Value {get;set;} = "";
}
public sealed class GameDatabase(DbContextOptions<GameDatabase> options) : IdentityDbContext<Player>(options) {
    public DbSet<StoredSession> Sessions => Set<StoredSession>();
    public DbSet<PlayerSave> Saves => Set<PlayerSave>();
    public DbSet<SaveSnapshot> Snapshots => Set<SaveSnapshot>();
    public DbSet<PlayLease> Leases => Set<PlayLease>();
    public DbSet<SecurityAudit> Audits => Set<SecurityAudit>();
    public DbSet<AppSetting> Settings => Set<AppSetting>();
    protected override void OnModelCreating(ModelBuilder builder) {
        base.OnModelCreating(builder);
        builder.Entity<StoredSession>().HasIndex(s=>s.UserId);
        builder.Entity<SaveSnapshot>().HasIndex(s=>new{s.UserId,s.Revision}).IsUnique();
        builder.Entity<SecurityAudit>().HasIndex(a=>a.CreatedUtc);
    }
}
