using System.Text.Json;
using Ftl.Server;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

// Save model 3: the shape limits of a profile and the startup migrations from save models 1 and 2 (docs/SAVES.md "Migration").
static class SavesV2Tests {
    public static async Task Run(string scratch, Action<bool, string> check) {
        Validation(check);
        await Migration(Path.Combine(scratch, "saves2"), check);
    }

    // A profile's cost is bounded by its shape: over the limits is refused.
    static void Validation(Action<bool, string> check) {
        static bool Refused(string xml) { try { SaveValidation.Profile(xml); return false; } catch (Exception e) when (e is InvalidDataException or System.Xml.XmlException) { return true; } }
        var attributes = string.Concat(Enumerable.Range(0, SaveValidation.ProfileAttributes).Select(i => $"a{i}=\"\" "));
        check(!Refused("<xftl-profile " + attributes + "><a/></xftl-profile>"), "Profile validation takes a profile at the limits");
        check(Refused("<xftl-profile " + attributes + "b=\"\" />"), "Profile validation refuses too many attributes on an element");
        check(Refused("<xftl-profile>" + string.Concat(Enumerable.Repeat("<a/>", SaveValidation.ProfileElements)) + "</xftl-profile>"), "Profile validation refuses too many elements");
        check(Refused("<xftl-profile>" + new string('x', SaveValidation.ProfileCharacters) + "</xftl-profile>"), "Profile validation refuses an oversized profile");
        check(Refused("<other/>"), "Profile validation refuses another root");
        check(Refused("<!DOCTYPE xftl-profile [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><xftl-profile>&e;</xftl-profile>"), "Profile validation refuses DTDs");
    }

    record Envelope(int Schema, string EngineBuild, string AssetVersion, long Revision, string? Run, string? Profile, int? Slot);

    static async Task Migration(string data, Action<bool, string> check) {
        Directory.CreateDirectory(data);
        var database = Path.Combine(data, "ftl.sqlite");
        var services = new ServiceCollection(); services.AddLogging();
        services.AddDbContext<GameDatabase>(o => o.UseSqlite($"Data Source={database}"));
        services.AddIdentityCore<Player>().AddRoles<IdentityRole>().AddEntityFrameworkStores<GameDatabase>();
        await using var provider = services.BuildServiceProvider();
        string runA = "<xftlSaveGame><playerShip shipId=\"PLAYER_SHIP_HARD\"/></xftlSaveGame>", runB = "<xftlSaveGame><playerShip shipId=\"PLAYER_SHIP_FED\"/></xftlSaveGame>", oldProfile = "<xftl-profile achPopups=\"true\" language=\"de\" listTutorial=\"1\"><hotkey action=\"a\" key=\"b\" /><soundVolume sfx=\"1\" music=\"1\" /><ach id=\"X\" diff=\"EASY\" /></xftl-profile>", profile = "<xftl-profile listTutorial=\"1\"><ach id=\"X\" diff=\"EASY\" /></xftl-profile>";
        var t0 = new DateTime(2026, 10, 1, 12, 0, 0);
        // A save model 1 database: the new tables do not exist yet.
        using (var scope = provider.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
            await db.Database.EnsureCreatedAsync();
            foreach (var table in new[] { "CloudSaves", "CloudSnapshots", "SaveTombstones", "LockOverrides", "Profiles" }) await db.Database.ExecuteSqlRawAsync($"DROP TABLE \"{table}\";");
            db.Settings.AddRange(new AppSetting { Key = "schema", Value = "1" }, new AppSetting { Key = "bootstrapped", Value = "fixture" });
            // u1 plays slot 2 (renamed), slot 1 is parked with a run, slot 3 is an empty named slot; twelve snapshots of slot 2, two of slot 1.
            db.Saves.Add(new() { UserId = "u1", Revision = 14, Run = runA, Profile = oldProfile, EngineBuild = "build-1", AssetVersion = "dat-1", UpdatedUtc = t0.AddDays(3), ActiveSlot = 2 });
            db.SaveSlots.AddRange(new SaveSlot { UserId = "u1", Slot = 1, Name = "First run", Run = runB, EngineBuild = "build-0", AssetVersion = "dat-0", CreatedUtc = t0, PlayedUtc = t0.AddDays(1) },
                new SaveSlot { UserId = "u1", Slot = 2, Name = "Second run", EngineBuild = "build-1", AssetVersion = "dat-1", CreatedUtc = t0.AddDays(2), PlayedUtc = t0.AddDays(2) },
                new SaveSlot { UserId = "u1", Slot = 3, Name = "Empty slot", EngineBuild = "build-1", AssetVersion = "dat-1", CreatedUtc = t0.AddDays(2), PlayedUtc = t0.AddDays(2) });
            string Payload(long revision, string run, int? slot) => JsonSerializer.Serialize(new Envelope(1, "build-1", "dat-1", revision, run, oldProfile, slot), new JsonSerializerOptions(JsonSerializerDefaults.Web));
            for (var r = 1; r <= 12; r++) db.Snapshots.Add(new() { UserId = "u1", Revision = r, Payload = Payload(r, r == 12 ? runA : $"<xftlSaveGame r=\"{r}\"/>", 2), CreatedUtc = t0.AddHours(r), Kind = r % 2 == 0 ? "auto" : "manual", Slot = 2 });
            for (var r = 13; r <= 14; r++) db.Snapshots.Add(new() { UserId = "u1", Revision = r, Payload = Payload(r, runB, null), CreatedUtc = t0.AddHours(r), Kind = "auto", Slot = null });
            // u2 has only a profile; u3 a run in slot 1 and no snapshots.
            db.Saves.Add(new() { UserId = "u2", Revision = 3, Profile = oldProfile, EngineBuild = "build-1", AssetVersion = "dat-1", UpdatedUtc = t0 });
            db.Saves.Add(new() { UserId = "u3", Revision = 1, Run = runB, EngineBuild = "build-1", AssetVersion = "dat-1", UpdatedUtc = t0 });
            await db.SaveChangesAsync();
        }
        await Bootstrap.Initialize(provider, data);

        async Task<(int Saves, int Snapshots, int Profiles)> Counts() {
            using var scope = provider.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
            return (await db.CloudSaves.CountAsync(), await db.CloudSnapshots.CountAsync(), await db.Profiles.CountAsync());
        }
        using (var scope = provider.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
            var u1 = await db.CloudSaves.AsNoTracking().Where(s => s.UserId == "u1").ToListAsync();
            var active = u1.SingleOrDefault(s => s.Name == "Second run"); var parked = u1.SingleOrDefault(s => s.Name == "First run"); var empty = u1.SingleOrDefault(s => s.Name == "Empty slot");
            check(u1.Count == 3 && active != null && parked != null && empty != null, "Migration: the active slot, a parked slot and an empty named slot become three saves with their names");
            check(active!.Run == runA && active.Revision == 11 && active.Profile == profile && active.SetupKey == "vanilla" && active.Setup == "{\"key\":\"vanilla\",\"mods\":[]}" && active.CreatedUtc == t0.AddDays(2) && active.UpdatedUtc == t0.AddDays(3), "Migration: the active slot keeps its run and times");
            using (var meta = JsonDocument.Parse(active.Meta)) check(meta.RootElement.GetProperty("migratedFrom").GetString() == "slot 2" && meta.RootElement.GetProperty("engineBuild").GetString() == "build-1" && meta.RootElement.GetProperty("gameData").GetString() == "dat-1", "Migration: meta names the slot, engine build and game data");
            check(parked!.Run == runB && parked.Revision == 2 && parked.Profile == profile && parked.Bytes == parked.RunBytes + parked.ProfileBytes + System.Text.Encoding.UTF8.GetByteCount(parked.Meta) && parked.UpdatedUtc == t0.AddDays(1) && parked.Meta.Contains("\"build-0\"") && empty!.Run == null && empty.RunBytes == 0, "Migration: parked runs and empty slots keep their own data");
            var snapshots = await db.CloudSnapshots.AsNoTracking().Where(s => s.SaveId == active.Id).OrderBy(s => s.Revision).ToListAsync();
            check(snapshots.Count == 10 && snapshots.Select(s => s.Revision).SequenceEqual(Enumerable.Range(1, 10).Select(r => (long)r)) && snapshots[0].CreatedUtc == t0.AddHours(3) && snapshots[^1].Kind == "auto" && snapshots[0].Kind == "manual", "Migration: the ten newest snapshots of the active slot, renumbered below the save's revision");
            using (var payload = JsonDocument.Parse(snapshots[^1].Payload)) check(payload.RootElement.GetProperty("run").GetString() == runA && payload.RootElement.GetProperty("revision").GetInt64() == 10 && payload.RootElement.GetProperty("name").GetString() == "Second run" && payload.RootElement.GetProperty("setup").GetProperty("key").GetString() == "vanilla", "Migration: snapshots use the save model 2 payload");
            check(!await db.CloudSnapshots.AnyAsync(s => s.SaveId != active.Id), "Migration: snapshots of other slots are not copied");
            var profiles = await db.Profiles.AsNoTracking().OrderBy(p => p.UserId).ToListAsync();
            check(profiles.Count == 2 && profiles.All(p => p.SetupKey == "vanilla" && p.Xml == oldProfile), "Migration: the old table stays untouched");
            check(empty!.Profile == profile && empty.Revision == 2, "Migration: every save of a setup gets its own copy of the profile without the settings, and a new revision the devices fetch");
            using (var payload = JsonDocument.Parse(snapshots[^1].Payload)) check(payload.RootElement.GetProperty("profile").GetString() == profile, "Migration: a snapshot keeps the profile of its time");
            var u3 = await db.CloudSaves.AsNoTracking().SingleAsync(s => s.UserId == "u3");
            check(u3.Name == "Save 1" && u3.Revision == 1 && u3.Profile == null, "Migration: an account without slots gets Save 1; without a profile it starts fresh");
            var u2 = await db.CloudSaves.AsNoTracking().SingleAsync(s => s.UserId == "u2");
            check(u2.Name == "Save 1" && u2.Run == null && u2.Profile == profile && u2.SetupKey == "vanilla", "Migration: a profile without a run or save becomes a save, so its unlocks stay");
            check(await db.Settings.FindAsync(SavesMigration.Flag) != null && await db.Saves.CountAsync() == 3 && await db.SaveSlots.CountAsync() == 3 && await db.Snapshots.CountAsync() == 14, "Migration: the flag is set and the old tables are untouched");
        }
        check(Directory.GetDirectories(Path.Combine(data, "backups"), "pre-saves3-*").Length == 1, "Migration: a backup is made before the profiles move into the saves");
        var backups = Directory.GetDirectories(Path.Combine(data, "backups"), "pre-saves2-*");
        if (backups.Length == 1 && File.Exists(Path.Combine(backups[0], "ftl.sqlite"))) {
            await using var copy = new SqliteConnection($"Data Source={Path.Combine(backups[0], "ftl.sqlite")};Mode=ReadOnly;Pooling=False"); await copy.OpenAsync();
            await using var command = copy.CreateCommand(); command.CommandText = "SELECT (SELECT COUNT(*) FROM Saves) * 1000 + (SELECT COUNT(*) FROM CloudSaves)";
            check(Convert.ToInt64(await command.ExecuteScalarAsync()) == 3000, "Migration: a backup of the database is made before anything changes");
        } else check(false, "Migration: a backup of the database is made before anything changes");

        var before = await Counts();
        await Bootstrap.Initialize(provider, data);
        check(await Counts() == before, "Migration: a second start migrates nothing again");
        using (var scope = provider.CreateScope()) { var db = scope.ServiceProvider.GetRequiredService<GameDatabase>(); db.Settings.Remove((await db.Settings.FindAsync(SavesMigration.Flag))!); await db.SaveChangesAsync(); }
        await Bootstrap.Initialize(provider, data);
        check(await Counts() == before, "Migration: an interrupted migration skips the accounts it already moved");

        // Save model 2 data: modded profiles, a save that already holds a profile, and an interrupted run.
        string modded = new('a', 64), other = new('b', 64), moddedProfile = "<xftl-profile><hyperspace modSet=\"" + modded + "\" /></xftl-profile>";
        string Setup(string key) => "{\"key\":\"" + key + "\",\"mods\":[{\"name\":\"M\",\"version\":\"\",\"sha256\":\"" + key + "\",\"size\":1,\"verified\":true}]}";
        Guid m1 = Guid.NewGuid(), m2 = Guid.NewGuid(), held = Guid.NewGuid();
        using (var scope = provider.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
            CloudSave Save(Guid id, string? kept) => new() { UserId = "u4", Id = id, Name = "M", SetupKey = modded, Setup = Setup(modded), Run = runA, RunBytes = 10, Profile = kept, ProfileBytes = kept == null ? 0 : 3, Revision = 5, Bytes = 12, CreatedUtc = t0, UpdatedUtc = t0, ChangedUtc = t0 };
            db.CloudSaves.AddRange(Save(m1, null), Save(m2, null), Save(held, "<xftl-profile v=\"mine\"/>"));
            db.Profiles.AddRange(new SetupProfile { UserId = "u4", SetupKey = modded, Xml = moddedProfile, Bytes = 1, Revision = 7, UpdatedUtc = t0 }, new SetupProfile { UserId = "u4", SetupKey = other, Xml = "<xftl-profile/>", Bytes = 1, Revision = 7, UpdatedUtc = t0 });
            db.Settings.Remove((await db.Settings.FindAsync(SavesMigration.ProfilesFlag))!); await db.SaveChangesAsync();
        }
        await Bootstrap.Initialize(provider, data);
        using (var scope = provider.CreateScope()) {
            var db = scope.ServiceProvider.GetRequiredService<GameDatabase>();
            var u4 = await db.CloudSaves.AsNoTracking().Where(s => s.UserId == "u4").ToDictionaryAsync(s => s.Id);
            check(u4.Count == 3 && u4[m1].Profile == moddedProfile && u4[m2].Profile == moddedProfile && u4[m1].Revision == 6, "Migration: both saves of a mod set get a copy of its profile; a mod set without a save makes no save");
            check(u4[m1].Bytes == 12 + System.Text.Encoding.UTF8.GetByteCount(moddedProfile), "Migration: the copy counts towards the account's storage");
            check(u4[held].Profile == "<xftl-profile v=\"mine\"/>" && u4[held].Revision == 5, "Migration: a save that already holds a profile keeps it");
            check(await db.CloudSaves.CountAsync(s => s.UserId == "u1") == 3 && (await db.CloudSaves.AsNoTracking().SingleAsync(s => s.UserId == "u1" && s.Name == "Second run")).Revision == 11, "Migration: moved accounts are not moved again");
        }
    }
}
