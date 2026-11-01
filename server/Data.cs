using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

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
// Save model 1 (one account save, save slots, play lease): read-only since save model 2 (2026-10-29), kept for the
// migration (SavesMigration) until a later release drops the tables.
public sealed class PlayerSave {
    [Key] public string UserId {get;set;} = "";
    [ConcurrencyCheck] public long Revision {get;set;}
    public string? Run {get;set;}
    public string? Profile {get;set;}
    public string EngineBuild {get;set;} = "";
    public string AssetVersion {get;set;} = "";
    public DateTime UpdatedUtc {get;set;}
    // The save slot whose run is in Run (2026-10-24); null means slot 1, the save every account had before slots.
    public int? ActiveSlot {get;set;}
}
// A save slot other than the active one keeps its run here ("parked"); the active slot's run stays in PlayerSave, so the
// save, lease and snapshot code is the same with or without slots. The profile (unlocks) is shared by all slots.
public sealed class SaveSlot {
    public long Id {get;set;}
    public string UserId {get;set;} = "";
    public int Slot {get;set;}
    public string Name {get;set;} = "";
    public string? Run {get;set;}
    public string EngineBuild {get;set;} = "";
    public string AssetVersion {get;set;} = "";
    public DateTime CreatedUtc {get;set;}
    public DateTime PlayedUtc {get;set;}
}
public sealed class SaveSnapshot {
    public long Id {get;set;}
    public string UserId {get;set;} = "";
    public long Revision {get;set;}
    public string Payload {get;set;} = "";
    public DateTime CreatedUtc {get;set;}
    // "auto", "manual", "import" or "restore"; null for snapshots made before 2026-10-21.
    public string? Kind {get;set;}
    // Save slot of the run in Payload; null for slot 1 and snapshots made before save slots.
    public int? Slot {get;set;}
}
public sealed class PlayLease {
    [Key] public string UserId {get;set;} = "";
    public string SessionId {get;set;} = "";
    public string Token {get;set;} = "";
    public DateTime ExpiresUtc {get;set;}
}
// Save model 2 (docs/SAVES.md): up to 30 saves per account, each with its own revision, lock and snapshots. Keys include
// the account, so an id another account uses is simply absent here.
public sealed class CloudSave {
    public string UserId {get;set;} = "";
    public Guid Id {get;set;}
    public string Name {get;set;} = "";
    public string SetupKey {get;set;} = "";
    public string Setup {get;set;} = "";
    public string Meta {get;set;} = "{}";
    public string? Run {get;set;}
    public long RunBytes {get;set;}
    // The save's unlocks, achievements, scores, statistics and Hyperspace data (the engine's xftl-profile).
    public string? Profile {get;set;}
    public long ProfileBytes {get;set;}
    // UTF-8 bytes of run, profile and meta, charged to the account's storage limit.
    public long Bytes {get;set;}
    [ConcurrencyCheck] public long Revision {get;set;}
    public DateTime CreatedUtc {get;set;}
    public DateTime UpdatedUtc {get;set;}
    // Any change, including the lock and its last-seen time (GET /api/saves?since=).
    public DateTime ChangedUtc {get;set;}
    public string? LockInstance {get;set;}
    public string? LockDeviceId {get;set;}
    public string? LockDevice {get;set;}
    public string? LockPlatform {get;set;}
    public DateTime? LockSinceUtc {get;set;}
    public DateTime? LockSeenUtc {get;set;}
}
public sealed class CloudSnapshot {
    public long Id {get;set;}
    public string UserId {get;set;} = "";
    public Guid SaveId {get;set;}
    public long Revision {get;set;}
    public string Payload {get;set;} = "";
    public long Bytes {get;set;}
    public string? Kind {get;set;}
    public DateTime CreatedUtc {get;set;}
}
public sealed class SaveTombstone {
    public string UserId {get;set;} = "";
    public Guid Id {get;set;}
    public DateTime DeletedUtc {get;set;}
}
// Who took a lock from whom. The overridden instance hears of it on its next contact, until it holds the lock again and
// the row goes. One row per overridden instance, at most CloudSaves.MaxOverrides per save.
public sealed class LockOverride {
    public long Id {get;set;}
    public string UserId {get;set;} = "";
    public Guid SaveId {get;set;}
    public string Instance {get;set;} = "";
    public string ByInstance {get;set;} = "";
    public string ByDevice {get;set;} = "";
    public string ByPlatform {get;set;} = "";
    public DateTime AtUtc {get;set;}
    public DateTime? AckUtc {get;set;}
}
// Save model 2's one profile per account and setup key: read only by the migration until a later release drops the table.
public sealed class SetupProfile {
    public string UserId {get;set;} = "";
    public string SetupKey {get;set;} = "";
    public string Xml {get;set;} = "";
    public long Bytes {get;set;}
    [ConcurrencyCheck] public long Revision {get;set;}
    public DateTime UpdatedUtc {get;set;}
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
    public DbSet<SaveSlot> SaveSlots => Set<SaveSlot>();
    public DbSet<PlayLease> Leases => Set<PlayLease>();
    public DbSet<CloudSave> CloudSaves => Set<CloudSave>();
    public DbSet<CloudSnapshot> CloudSnapshots => Set<CloudSnapshot>();
    public DbSet<SaveTombstone> SaveTombstones => Set<SaveTombstone>();
    public DbSet<LockOverride> LockOverrides => Set<LockOverride>();
    public DbSet<SetupProfile> Profiles => Set<SetupProfile>();
    public DbSet<SecurityAudit> Audits => Set<SecurityAudit>();
    public DbSet<AppSetting> Settings => Set<AppSetting>();
    protected override void OnModelCreating(ModelBuilder builder) {
        base.OnModelCreating(builder);
        builder.Entity<StoredSession>().HasIndex(s=>s.UserId);
        builder.Entity<SaveSnapshot>().HasIndex(s=>new{s.UserId,s.Revision}).IsUnique();
        builder.Entity<SaveSlot>().HasIndex(s=>new{s.UserId,s.Slot}).IsUnique();
        builder.Entity<SecurityAudit>().HasIndex(a=>a.CreatedUtc);
        builder.Entity<CloudSave>().HasKey(s=>new{s.UserId,s.Id});
        builder.Entity<CloudSnapshot>().HasIndex(s=>new{s.UserId,s.SaveId,s.Revision}).IsUnique();
        builder.Entity<SaveTombstone>().HasKey(s=>new{s.UserId,s.Id});
        builder.Entity<LockOverride>().HasIndex(s=>new{s.UserId,s.SaveId,s.Instance,s.AckUtc});
        builder.Entity<SetupProfile>().HasKey(s=>new{s.UserId,s.SetupKey});
        // SQLite returns times without a kind; these are UTC, so the API writes them with a Z.
        var utc=new ValueConverter<DateTime,DateTime>(v=>v,v=>DateTime.SpecifyKind(v,DateTimeKind.Utc));
        var utcOrNull=new ValueConverter<DateTime?,DateTime?>(v=>v,v=>v==null?null:DateTime.SpecifyKind(v.Value,DateTimeKind.Utc));
        foreach(var type in new[]{typeof(CloudSave),typeof(CloudSnapshot),typeof(SaveTombstone),typeof(LockOverride),typeof(SetupProfile)})
            foreach(var property in builder.Entity(type).Metadata.GetProperties())
                if(property.ClrType==typeof(DateTime))property.SetValueConverter(utc);else if(property.ClrType==typeof(DateTime?))property.SetValueConverter(utcOrNull);
    }
}
