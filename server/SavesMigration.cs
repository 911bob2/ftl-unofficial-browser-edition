using System.Text.Json;
using System.Text.Json.Nodes;
using System.Xml;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;

// Save model 1 to 2 (docs/SAVES.md "Migration"), once at startup: a backup of the database first, then per account (one
// transaction each) the active slot's run (PlayerSave) and every SaveSlots row become saves with setup vanilla, the
// account profile becomes the vanilla profile, and the snapshots of the active slot stay with its save. The old tables
// are only read. An account that already has save model 2 data is skipped, so an interrupted run can simply run again.
// Save model 2 to 3 (MergeProfiles) then gives every save a copy of its setup's profile.
public static class SavesMigration {
    public const string Flag = "saves-v2-migrated", ProfilesFlag = "saves-profiles-merged";
    record SaveEnvelope(int Schema, string? EngineBuild, string? AssetVersion, long Revision, string? Run, string? Profile, int? Slot = null);

    public static async Task Run(GameDatabase db, string dataDir) {
        if (await db.Settings.FindAsync(Flag) != null) return;
        if (await db.Saves.AnyAsync() || await db.SaveSlots.AnyAsync()) Backup(db, dataDir, "pre-saves2-");
        var users = (await db.Saves.Select(s => s.UserId).ToListAsync()).Concat(await db.SaveSlots.Select(s => s.UserId).ToListAsync()).Distinct().Order(StringComparer.Ordinal).ToList();
        foreach (var user in users) {
            await using var tx = await db.Database.BeginTransactionAsync();
            if (await db.CloudSaves.AnyAsync(s => s.UserId == user) || await db.Profiles.AnyAsync(p => p.UserId == user) || await db.SaveTombstones.AnyAsync(t => t.UserId == user)) continue;
            await Account(db, user);
            await db.SaveChangesAsync(); await tx.CommitAsync(); db.ChangeTracker.Clear();
        }
        db.Settings.Add(new() { Key = Flag, Value = DateTime.UtcNow.ToString("O") }); await db.SaveChangesAsync();
    }

    static readonly string[] SettingAttributes = ["achPopups", "hangarAE", "fpsLimit", "touchAutoPause", "autoPause", "beaconPaths", "dialogKeys", "language"];
    /// <summary>The profile without the options, hotkeys and volumes it used to hold: they are the device's now and do not sync.</summary>
    public static string WithoutSettings(string xml) {
        using var reader = XmlReader.Create(new StringReader(xml), new XmlReaderSettings { DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null });
        var doc = XDocument.Load(reader); var root = doc.Root!;
        foreach (var name in SettingAttributes) root.Attribute(name)?.Remove();
        root.Elements("hotkey").Remove(); root.Elements("soundVolume").Remove();
        return (doc.Declaration == null ? "" : doc.Declaration + "\n") + root.ToString(SaveOptions.DisableFormatting);
    }

    /// <summary>
    /// A save holds its own profile now: each save gets a copy of its setup's, and a new revision, so the devices fetch
    /// it (a device with progress the account lacks is asked). A vanilla profile with no save becomes "Save 1"; a modded
    /// one with no save has no mod list to make a save from and stays in the old table, like the backup.
    /// </summary>
    public static async Task MergeProfiles(GameDatabase db, string dataDir) {
        if (await db.Settings.FindAsync(ProfilesFlag) != null) return;
        if (await db.Profiles.AnyAsync()) Backup(db, dataDir, "pre-saves3-");
        foreach (var user in (await db.Profiles.Select(p => p.UserId).Distinct().ToListAsync()).Order(StringComparer.Ordinal)) {
            await using var tx = await db.Database.BeginTransactionAsync();
            var now = DateTime.UtcNow;
            var profiles = await db.Profiles.AsNoTracking().Where(p => p.UserId == user).ToDictionaryAsync(p => p.SetupKey, p => WithoutSettings(p.Xml));
            var saves = await db.CloudSaves.Where(s => s.UserId == user).ToListAsync();
            void Hold(CloudSave save, string xml) { save.Profile = xml; save.ProfileBytes = CloudSaves.Bytes(xml); save.Bytes += save.ProfileBytes; save.ChangedUtc = now; }
            foreach (var save in saves) {
                if (save.Profile != null || !profiles.TryGetValue(save.SetupKey, out var xml)) continue;
                Hold(save, xml); save.Revision++;
            }
            if (profiles.TryGetValue("vanilla", out var vanilla) && !saves.Any(s => s.SetupKey == "vanilla") && saves.Count < CloudSaves.MaxSaves) {
                const string meta = "{}";
                var save = new CloudSave { UserId = user, Id = Guid.NewGuid(), Name = "Save 1", SetupKey = "vanilla", Setup = CloudSaves.VanillaSetup, Meta = meta, Revision = 1, CreatedUtc = now, UpdatedUtc = now, Bytes = CloudSaves.Bytes(meta) };
                Hold(save, vanilla); db.CloudSaves.Add(save);
            }
            await db.SaveChangesAsync(); await tx.CommitAsync(); db.ChangeTracker.Clear();
        }
        db.Settings.Add(new() { Key = ProfilesFlag, Value = DateTime.UtcNow.ToString("O") }); await db.SaveChangesAsync();
    }

    /// <summary>A consistent copy of the whole database (as the backup command makes) before anything changes.</summary>
    static void Backup(GameDatabase db, string dataDir, string prefix) {
        var dest = Path.Combine(dataDir, "backups", prefix + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")); Directory.CreateDirectory(dest);
        var file = Path.Combine(dest, "ftl.sqlite");
        using (var source = new SqliteConnection(db.Database.GetConnectionString())) using (var target = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = file, Pooling = false }.ToString())) {
            source.Open(); target.Open(); source.BackupDatabase(target);
        }
        Secrets.Restrict(file);
    }

    static string Meta(int slot, string? engineBuild, string? gameData) => new JsonObject { ["migratedFrom"] = "slot " + slot, ["engineBuild"] = engineBuild, ["gameData"] = gameData }.ToJsonString();

    static async Task Account(GameDatabase db, string user) {
        var state = await db.Saves.AsNoTracking().SingleOrDefaultAsync(s => s.UserId == user);
        var rows = await db.SaveSlots.AsNoTracking().Where(s => s.UserId == user).OrderBy(s => s.Slot).ToListAsync();
        var active = state?.ActiveSlot ?? 1; var activeRow = rows.SingleOrDefault(r => r.Slot == active);
        CloudSave Save(int slot, string? name, string? run, string meta, DateTime created, DateTime updated, long revision) {
            var save = new CloudSave {
                UserId = user, Id = Guid.NewGuid(), Name = CloudSaves.CleanName(name) ?? $"Save {slot}", SetupKey = "vanilla", Setup = CloudSaves.VanillaSetup, Meta = meta,
                Run = run, RunBytes = CloudSaves.Bytes(run), Revision = revision, CreatedUtc = created, UpdatedUtc = updated, ChangedUtc = DateTime.UtcNow };
            save.Bytes = save.RunBytes + CloudSaves.Bytes(meta); db.CloudSaves.Add(save); return save;
        }
        // The active slot: its run is in PlayerSave; a row exists only when it was renamed. No run and no name: nothing to keep.
        if (state != null && (state.Run != null || activeRow != null)) {
            var meta = Meta(active, state.EngineBuild, state.AssetVersion);
            var name = CloudSaves.CleanName(activeRow?.Name) ?? $"Save {active}";
            // The ten newest snapshots of this slot, renumbered 1..n so that they stay older than the save's revision.
            var old = await db.Snapshots.AsNoTracking().Where(s => s.UserId == user && (s.Slot ?? 1) == active).OrderByDescending(s => s.Revision).Take(CloudSaves.MaxSnapshots).ToListAsync();
            var kept = new List<(SaveSnapshot Row, SaveEnvelope Envelope)>();
            foreach (var s in Enumerable.Reverse(old)) {
                try { if (JsonSerializer.Deserialize<SaveEnvelope>(s.Payload, new JsonSerializerOptions(JsonSerializerDefaults.Web)) is { } e) kept.Add((s, e)); } catch (JsonException) { }
            }
            // The newest snapshot is normally the current run; otherwise the current run gets a revision of its own.
            long revision = kept.Count == 0 || kept[^1].Envelope.Run != state.Run ? kept.Count + 1 : kept.Count;
            var save = Save(active, name, state.Run, meta, activeRow?.CreatedUtc ?? state.UpdatedUtc, state.UpdatedUtc, revision);
            for (var i = 0; i < kept.Count; i++) {
                var (row, envelope) = kept[i];
                var payload = CloudSaves.Payload(save.Name, CloudSaves.VanillaSetup, Meta(active, envelope.EngineBuild, envelope.AssetVersion), envelope.Run, envelope.Profile == null ? null : WithoutSettings(envelope.Profile), i + 1);
                db.CloudSnapshots.Add(new() { UserId = user, SaveId = save.Id, Revision = i + 1, Payload = payload, Bytes = CloudSaves.Bytes(payload), Kind = row.Kind, CreatedUtc = row.CreatedUtc });
            }
        }
        // Parked slots, with or without a run (every row has a name).
        foreach (var row in rows.Where(r => r.Slot != active || state == null))
            Save(row.Slot, row.Name, row.Run, Meta(row.Slot, row.EngineBuild, row.AssetVersion), row.CreatedUtc, row.PlayedUtc, 1);
        if (state?.Profile != null)
            db.Profiles.Add(new() { UserId = user, SetupKey = "vanilla", Xml = state.Profile, Bytes = CloudSaves.Bytes(state.Profile), Revision = 1, UpdatedUtc = state.UpdatedUtc });
    }
}
