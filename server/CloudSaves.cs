using System.Globalization;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Xml;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;

// Save model 3 (docs/SAVES.md): up to 30 saves per account, each with its own unlocks and run, one revision (If-Match),
// lock and last ten snapshots. Devices write to their own store first and sync here. A lock names the
// running game that holds it (X-FTL-Instance) and never expires; overriding one is recorded, so the displaced instance
// is told (409 lock_overridden) on its next contact instead of writing over the new holder.
public static partial class CloudSaves {
    public const int MaxSaves = 30, MaxSnapshots = 10, NameLength = 40, MetaBytes = 8 * 1024, MaxMods = 64;
    public const long BytesLimit = 256L * 1024 * 1024;
    public static readonly TimeSpan TombstoneLifetime = TimeSpan.FromDays(90);
    // Rows no byte limit counts: the newest tombstones of an account, the newest overrides of a save.
    public const int MaxTombstones = 500, MaxOverrides = 20;
    // What a lock shows of a holder other than the caller: the instance id is what acts as the holder.
    const string OtherInstance = "other";
    // Stored JSON is re-encoded when it is sent, so storage keeps it compact (no \u escapes for non-ASCII text).
    static readonly JsonSerializerOptions Stored = new(JsonSerializerDefaults.Web) { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    public static readonly string VanillaSetup = JsonSerializer.Serialize(new Setup("vanilla", []), Stored);
    [GeneratedRegex(@"^[A-Za-z0-9-]{1,64}\z", RegexOptions.CultureInvariant)] private static partial Regex IdPattern();
    [GeneratedRegex(@"^[0-9a-f]{64}\z", RegexOptions.CultureInvariant)] private static partial Regex HashPattern();

    public record Mod(string Name, string Version, string Sha256, long Size, bool Verified);
    public record Setup(string Key, Mod[] Mods);
    public record LockInfo(string Instance, string DeviceId, string Device, string Platform, DateTime Since, DateTime LastSeen);
    public record SaveInfo(Guid Id, string Name, JsonElement Setup, DateTime CreatedUtc, DateTime UpdatedUtc, long Revision, JsonElement Meta, bool HasRun, long RunBytes, bool HasProfile, long ProfileBytes, LockInfo? Lock);
    public record SaveWithData(Guid Id, string Name, JsonElement Setup, DateTime CreatedUtc, DateTime UpdatedUtc, long Revision, JsonElement Meta, bool HasRun, long RunBytes, bool HasProfile, long ProfileBytes, LockInfo? Lock, string? Run, string? Profile);
    public record SavePut(string? Name, JsonElement Setup, JsonElement Meta, string? Run, string? Profile, string? Kind);
    public record SaveRename(string? Name);
    public record LockRequest(bool Override, bool Acknowledge);
    record Caller(string Instance, string Platform, string DeviceId, string Name);

    public static string? CleanName(string? name) {
        var n = (name ?? "").Trim();
        return n.Length is < 1 or > NameLength || n.Any(char.IsControl) ? null : n;
    }
    /// <summary>The canonical setup JSON, or null when it breaks the rules (key "vanilla" exactly when there are no mods).</summary>
    public static (string Key, string Json)? ReadSetup(JsonElement e) {
        if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty("key", out var k) || k.ValueKind != JsonValueKind.String || !e.TryGetProperty("mods", out var list) || list.ValueKind != JsonValueKind.Array || list.GetArrayLength() > MaxMods) return null;
        var key = k.GetString()!; var mods = new List<Mod>();
        static string? Text(JsonElement m, string name, int min, int max) => m.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String && v.GetString() is { } s && s.Length >= min && s.Length <= max && !s.Any(char.IsControl) ? s : null;
        foreach (var m in list.EnumerateArray()) {
            if (m.ValueKind != JsonValueKind.Object || Text(m, "name", 1, 100) is not { } name || Text(m, "version", 0, 40) is not { } version || Text(m, "sha256", 64, 64)?.ToLowerInvariant() is not { } sha || !HashPattern().IsMatch(sha)
                || !m.TryGetProperty("size", out var size) || size.ValueKind != JsonValueKind.Number || !size.TryGetInt64(out var bytes) || bytes < 0
                || !m.TryGetProperty("verified", out var verified) || verified.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return null;
            mods.Add(new(name, version, sha, bytes, verified.GetBoolean()));
        }
        if (key == "vanilla" ? mods.Count != 0 : !HashPattern().IsMatch(key) || mods.Count == 0) return null;
        return (key, JsonSerializer.Serialize(new Setup(key, [.. mods]), Stored));
    }
    /// <summary>Compact meta JSON (an object of at most 8 KiB; absent is {}), or null.</summary>
    public static string? ReadMeta(JsonElement e) {
        if (e.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null) return "{}";
        if (e.ValueKind != JsonValueKind.Object) return null;
        var text = JsonSerializer.Serialize(e, Stored);
        return Encoding.UTF8.GetByteCount(text) <= MetaBytes ? text : null;
    }
    /// <summary>A snapshot: everything PUT stores, so a restore needs nothing else.</summary>
    public static string Payload(string name, string setup, string meta, string? run, string? profile, long revision) {
        using var stream = new MemoryStream();
        using (var w = new Utf8JsonWriter(stream, new JsonWriterOptions { Encoder = Stored.Encoder })) {
            w.WriteStartObject(); w.WriteString("name", name);
            w.WritePropertyName("setup"); w.WriteRawValue(setup, true); w.WritePropertyName("meta"); w.WriteRawValue(meta, true);
            if (run == null) w.WriteNull("run"); else w.WriteString("run", run);
            if (profile != null) w.WriteString("profile", profile);
            w.WriteNumber("revision", revision); w.WriteEndObject();
        }
        return Encoding.UTF8.GetString(stream.GetBuffer(), 0, (int)stream.Length);
    }
    public static long Bytes(string? text) => text == null ? 0 : Encoding.UTF8.GetByteCount(text);
    static JsonElement Json(string text) { using var d = JsonDocument.Parse(text); return d.RootElement.Clone(); }
    static string Shown(string instance, HttpContext h) => instance == h.Request.Headers["X-FTL-Instance"].ToString() ? instance : OtherInstance;
    static LockInfo? Lock(CloudSave s, HttpContext h) => s.LockInstance == null ? null : new(Shown(s.LockInstance, h), s.LockDeviceId ?? "", s.LockDevice ?? "", s.LockPlatform ?? "", s.LockSinceUtc ?? default, s.LockSeenUtc ?? default);

    // X-FTL-Instance names the running game; X-FTL-Device is "platform;deviceId;name". The name may be percent-encoded
    // (encodeURIComponent), since HTTP header values are ASCII.
    static Caller? Identify(HttpContext h) {
        var instance = h.Request.Headers["X-FTL-Instance"]; var device = h.Request.Headers["X-FTL-Device"];
        if (instance.Count != 1 || device.Count != 1 || !IdPattern().IsMatch(instance.ToString())) return null;
        var parts = device.ToString().Split(';', 3);
        if (parts.Length != 3 || parts[0] is not ("browser" or "android") || !IdPattern().IsMatch(parts[1])) return null;
        var name = Uri.UnescapeDataString(parts[2]).Trim();
        return name.Length is < 1 or > 60 || name.Any(char.IsControl) ? null : new(instance.ToString(), parts[0], parts[1], name);
    }
    static long? IfMatch(HttpContext h) {
        var v = h.Request.Headers.IfMatch.ToString();
        return v.Length > 2 && v[0] == '"' && v[^1] == '"' && long.TryParse(v.AsSpan(1, v.Length - 2), NumberStyles.None, CultureInfo.InvariantCulture, out var r) ? r : null;
    }
    static IResult Bad(string error, string code) => Results.BadRequest(new { error, code });
    static IResult BadCaller() => Bad("Send X-FTL-Instance (1 to 64 letters, digits or dashes) and X-FTL-Device (platform;deviceId;name)", "bad_device");
    static IResult BadIfMatch() => Bad("Send If-Match with the revision the save is based on (\"0\" to create it)", "bad_if_match");
    static IResult Gone() => Results.Json(new { code = "saves_v2", error = "Saves changed. Reload the page." }, statusCode: 410);
    static async Task<IResult> Missing(GameDatabase db, string user, Guid id) => await db.SaveTombstones.AsNoTracking().SingleOrDefaultAsync(t => t.UserId == user && t.Id == id) is { } gone
        ? Results.Json(new { error = "This save was deleted.", code = "deleted", deletedUtc = gone.DeletedUtc }, statusCode: 410)
        : Results.NotFound(new { error = "That save does not exist.", code = "not_found" });
    // An overridden instance hears lock_overridden until it takes the lock again (lock with override or acknowledge),
    // also once the save is free: the override wins completely, so its stale progress never lands on the newer one.
    static async Task<IResult?> LockConflict(GameDatabase db, CloudSave save, Caller caller, HttpContext h) {
        if (save.LockInstance == caller.Instance) return null;
        var overridden = await db.LockOverrides.AsNoTracking().Where(o => o.UserId == save.UserId && o.SaveId == save.Id && o.Instance == caller.Instance && o.AckUtc == null).OrderByDescending(o => o.Id).FirstOrDefaultAsync();
        if (overridden != null) return Results.Conflict(new { error = $"This save was taken over on {overridden.ByDevice}.", code = "lock_overridden", @lock = Lock(save, h), overriddenUtc = overridden.AtUtc });
        return save.LockInstance == null ? null : Results.Conflict(new { error = $"This save is in use on {save.LockDevice}.", code = "locked", @lock = Lock(save, h) });
    }
    static async Task<long> Used(GameDatabase db, string user) =>
        await db.CloudSaves.Where(s => s.UserId == user).SumAsync(s => s.Bytes) + await db.CloudSnapshots.Where(s => s.UserId == user).SumAsync(s => s.Bytes);
    static async Task Prune(GameDatabase db, string user, Guid save) {
        var oldest = await db.CloudSnapshots.Where(s => s.UserId == user && s.SaveId == save).OrderByDescending(s => s.Revision).Skip(MaxSnapshots - 1).Select(s => (long?)s.Revision).FirstOrDefaultAsync();
        if (oldest != null) await db.CloudSnapshots.Where(s => s.UserId == user && s.SaveId == save && s.Revision < oldest).ExecuteDeleteAsync();
    }
    static IResult StorageFull(long limit) => Results.Conflict(new { error = $"Your cloud saves would exceed {limit / 1024 / 1024} MiB. Delete saves you no longer need.", code = "storage_full" });
    public static Task<int> PurgeTombstones(GameDatabase db, CancellationToken token = default) { var cutoff = DateTime.UtcNow - TombstoneLifetime; return db.SaveTombstones.Where(t => t.DeletedUtc < cutoff).ExecuteDeleteAsync(token); }
    static SaveInfo Info(Guid id, string name, string setup, DateTime created, DateTime updated, long revision, string meta, long runBytes, long profileBytes, LockInfo? holder) => new(id, name, Json(setup), created, updated, revision, Json(meta), runBytes > 0, runBytes, profileBytes > 0, profileBytes, holder);

    public static void Map(RouteGroupBuilder game, ILogger logger, long limit) {
        // Save model 1 and the shared profiles are gone; an open page of the old site learns it here.
        string[] any = ["GET", "POST", "PUT", "PATCH", "DELETE"];
        foreach (var path in new[] { "/saves/current", "/saves/slots", "/saves/slots/{**rest}", "/saves/snapshots", "/saves/snapshots/{**rest}", "/play-lease/{**rest}", "/profiles/{**rest}" }) game.MapMethods(path, any, Gone);

        // Per account: the writes all wait on one gate, and a lock or delete leaves rows behind.
        var saveWrites = game.MapGroup("").RequireRateLimiting("save-writes");
        game.MapGet("/saves", async (string? since, HttpContext h, GameDatabase db) => {
            DateTime? after = null;
            if (since != null) { if (!DateTime.TryParse(since, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var parsed)) return Bad("since must be an ISO time", "bad_since"); after = DateTime.SpecifyKind(parsed, DateTimeKind.Utc); }
            // A write stamped just before this read can commit after it; the next delta repeats the last minute instead of missing it.
            var next = DateTime.UtcNow.AddMinutes(-1); var user = h.UserId();
            var rows = await db.CloudSaves.AsNoTracking().Where(s => s.UserId == user && (after == null || s.ChangedUtc > after)).OrderBy(s => s.CreatedUtc)
                .Select(s => new { s.Id, s.Name, s.Setup, s.CreatedUtc, s.UpdatedUtc, s.Revision, s.Meta, s.RunBytes, s.ProfileBytes, s.LockInstance, s.LockDeviceId, s.LockDevice, s.LockPlatform, s.LockSinceUtc, s.LockSeenUtc }).ToListAsync();
            var deleted = await db.SaveTombstones.AsNoTracking().Where(t => t.UserId == user && (after == null || t.DeletedUtc > after)).OrderBy(t => t.DeletedUtc).Select(t => new { t.Id, t.DeletedUtc }).ToListAsync();
            return Results.Ok(new {
                saves = rows.Select(s => Info(s.Id, s.Name, s.Setup, s.CreatedUtc, s.UpdatedUtc, s.Revision, s.Meta, s.RunBytes, s.ProfileBytes, s.LockInstance == null ? null : new(Shown(s.LockInstance, h), s.LockDeviceId ?? "", s.LockDevice ?? "", s.LockPlatform ?? "", s.LockSinceUtc ?? default, s.LockSeenUtc ?? default))),
                deleted, limits = new { saves = MaxSaves, snapshots = MaxSnapshots, bytes = limit }, usedBytes = await Used(db, user), nextSince = next
            });
        });
        game.MapGet("/saves/{id:guid}", async (Guid id, HttpContext h, GameDatabase db) => {
            var user = h.UserId(); var s = await db.CloudSaves.AsNoTracking().SingleOrDefaultAsync(s => s.UserId == user && s.Id == id);
            if (s == null) return await Missing(db, user, id);
            h.Response.Headers.ETag = $"\"{s.Revision}\"";
            return Results.Ok(new SaveWithData(s.Id, s.Name, Json(s.Setup), s.CreatedUtc, s.UpdatedUtc, s.Revision, Json(s.Meta), s.RunBytes > 0, s.RunBytes, s.ProfileBytes > 0, s.ProfileBytes, Lock(s, h), s.Run, s.Profile));
        });
        saveWrites.MapPut("/saves/{id:guid}", async (Guid id, SavePut body, HttpContext h, GameDatabase db) => {
            if (Identify(h) is not { } caller) return BadCaller();
            if (IfMatch(h) is not { } expected) return BadIfMatch();
            if (CleanName(body.Name) is not { } name) return Bad($"Use a name of 1 to {NameLength} characters", "bad_name");
            if (ReadSetup(body.Setup) is not { } setup) return Bad("Invalid setup", "bad_setup");
            if (ReadMeta(body.Meta) is not { } meta) return Bad($"meta must be a JSON object of at most {MetaBytes} bytes", "bad_meta");
            try { SaveValidation.Xml(body.Run, "xftlSaveGame"); } catch (Exception e) when (e is XmlException or InvalidDataException) { return Bad("Invalid save XML", "bad_run"); }
            try { SaveValidation.Profile(body.Profile); } catch (Exception e) when (e is XmlException or InvalidDataException) { return Bad("Invalid profile XML", "bad_profile"); }
            var kind = body.Kind is "auto" or "manual" or "import" ? body.Kind : null; var runBytes = Bytes(body.Run);
            // Serialized outside SQLite's writer lock; the transaction checks that this revision is still the next one.
            // No profile sent: the stored one stays (a save the engine has not started yet, an old page).
            var payload = body.Profile == null ? null : Payload(name, setup.Json, meta, body.Run, body.Profile, expected + 1);
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId(); var now = DateTime.UtcNow;
                var save = await db.CloudSaves.FindAsync(user, id);
                if (save == null) {
                    if (await db.SaveTombstones.FindAsync(user, id) is { } gone) return Results.Conflict(new { error = "This save was deleted.", code = "deleted", deletedUtc = gone.DeletedUtc });
                    if (expected != 0) return Results.NotFound(new { error = "That save does not exist.", code = "not_found" });
                    if (await db.CloudSaves.CountAsync(s => s.UserId == user) >= MaxSaves) return Results.Conflict(new { error = $"An account has at most {MaxSaves} saves. Delete one first.", code = "saves_full" });
                    save = new() { UserId = user, Id = id, CreatedUtc = now }; db.CloudSaves.Add(save);
                } else {
                    if (await LockConflict(db, save, caller, h) is { } conflict) return conflict;
                    if (save.Revision != expected) return Results.Conflict(new { error = "A newer save exists", code = "revision_conflict", revision = save.Revision });
                    if (save.LockInstance != null) save.LockSeenUtc = now;
                }
                payload ??= Payload(name, setup.Json, meta, body.Run, save.Profile, expected + 1);
                save.Name = name; save.SetupKey = setup.Key; save.Setup = setup.Json; save.Meta = meta; save.Run = body.Run; save.RunBytes = runBytes;
                if (body.Profile != null) { save.Profile = body.Profile; save.ProfileBytes = Bytes(body.Profile); }
                save.Bytes = runBytes + save.ProfileBytes + Bytes(meta);
                save.Revision = expected + 1; save.UpdatedUtc = save.ChangedUtc = now;
                db.CloudSnapshots.Add(new() { UserId = user, SaveId = id, Revision = save.Revision, Payload = payload, Bytes = Bytes(payload), Kind = kind, CreatedUtc = now });
                try { await db.SaveChangesAsync(); } catch (DbUpdateConcurrencyException) { return Results.Conflict(new { error = "A newer save exists", code = "revision_conflict", revision = expected }); }
                await Prune(db, user, id);
                if (await Used(db, user) > limit) return StorageFull(limit);
                await tx.CommitAsync();
                h.Response.Headers.ETag = $"\"{save.Revision}\""; return Results.Ok(new { revision = save.Revision, updatedUtc = save.UpdatedUtc });
            });
        });
        saveWrites.MapPatch("/saves/{id:guid}", async (Guid id, SaveRename body, HttpContext h, GameDatabase db) => {
            if (CleanName(body.Name) is not { } name) return Bad($"Use a name of 1 to {NameLength} characters", "bad_name");
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId();
                var save = await db.CloudSaves.FindAsync(user, id); if (save == null) return await Missing(db, user, id);
                save.Name = name; save.UpdatedUtc = save.ChangedUtc = DateTime.UtcNow;
                await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.NoContent();
            });
        });
        saveWrites.MapDelete("/saves/{id:guid}", async (Guid id, [FromQuery(Name = "override")] bool? force, HttpContext h, GameDatabase db) => {
            if (Identify(h) is not { } caller) return BadCaller();
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId(); var now = DateTime.UtcNow;
                var save = await db.CloudSaves.FindAsync(user, id);
                if (save == null) return await db.SaveTombstones.FindAsync(user, id) != null ? Results.NoContent() : Results.NotFound(new { error = "That save does not exist.", code = "not_found" });
                if (save.LockInstance != null && save.LockInstance != caller.Instance && force != true) return Results.Conflict(new { error = $"This save is in use on {save.LockDevice}.", code = "locked", @lock = Lock(save, h) });
                await db.CloudSnapshots.Where(s => s.UserId == user && s.SaveId == id).ExecuteDeleteAsync();
                await db.LockOverrides.Where(o => o.UserId == user && o.SaveId == id).ExecuteDeleteAsync();
                var cutoff = now - TombstoneLifetime; await db.SaveTombstones.Where(t => t.UserId == user && t.DeletedUtc < cutoff).ExecuteDeleteAsync();
                db.CloudSaves.Remove(save); db.SaveTombstones.Add(new() { UserId = user, Id = id, DeletedUtc = now });
                await db.SaveChangesAsync();
                var kept = await db.SaveTombstones.Where(t => t.UserId == user).OrderByDescending(t => t.DeletedUtc).Skip(MaxTombstones - 1).Select(t => (DateTime?)t.DeletedUtc).FirstOrDefaultAsync();
                if (kept != null) await db.SaveTombstones.Where(t => t.UserId == user && t.DeletedUtc < kept).ExecuteDeleteAsync();
                await tx.CommitAsync(); return Results.NoContent();
            });
        });
        saveWrites.MapPost("/saves/{id:guid}/lock", async (Guid id, LockRequest? body, HttpContext h, GameDatabase db) => {
            if (Identify(h) is not { } caller) return BadCaller();
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId(); var now = DateTime.UtcNow;
                var save = await db.CloudSaves.FindAsync(user, id); if (save == null) return await Missing(db, user, id);
                if (save.LockInstance != null && save.LockInstance != caller.Instance) {
                    if (body?.Override != true) return (await LockConflict(db, save, caller, h))!;
                    var displaced = save.LockInstance;
                    await db.LockOverrides.Where(o => o.UserId == user && o.SaveId == id && o.Instance == displaced).ExecuteDeleteAsync();
                    db.LockOverrides.Add(new() { UserId = user, SaveId = id, Instance = displaced, ByInstance = caller.Instance, ByDevice = caller.Name, ByPlatform = caller.Platform, AtUtc = now });
                    save.LockInstance = null;
                } else if (save.LockInstance == null && body?.Override != true && body?.Acknowledge != true && await LockConflict(db, save, caller, h) is { } told) {
                    // Free, but this instance was overridden: it learns that first (the player then acknowledges it).
                    return told;
                }
                if (save.LockInstance == null) save.LockSinceUtc = now;
                save.LockInstance = caller.Instance; save.LockDeviceId = caller.DeviceId; save.LockDevice = caller.Name; save.LockPlatform = caller.Platform; save.LockSeenUtc = save.ChangedUtc = now;
                // Holding the lock again answers any earlier override of this instance.
                await db.LockOverrides.Where(o => o.UserId == user && o.SaveId == id && o.Instance == caller.Instance).ExecuteDeleteAsync();
                await db.SaveChangesAsync();
                var newest = await db.LockOverrides.Where(o => o.UserId == user && o.SaveId == id).OrderByDescending(o => o.Id).Skip(MaxOverrides - 1).Select(o => (long?)o.Id).FirstOrDefaultAsync();
                if (newest != null) await db.LockOverrides.Where(o => o.UserId == user && o.SaveId == id && o.Id < newest).ExecuteDeleteAsync();
                await tx.CommitAsync(); return Results.Ok(Lock(save, h));
            });
        });
        saveWrites.MapPost("/saves/{id:guid}/unlock", async (Guid id, HttpContext h, GameDatabase db) => {
            if (Identify(h) is not { } caller) return BadCaller();
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId();
                var save = await db.CloudSaves.FindAsync(user, id); if (save == null) return await Missing(db, user, id);
                if (save.LockInstance == null) return Results.NoContent();
                if (save.LockInstance != caller.Instance) return Results.Conflict(new { error = "Only the game that holds the lock can release it.", code = "not_holder", @lock = Lock(save, h) });
                save.LockInstance = save.LockDeviceId = save.LockDevice = save.LockPlatform = null; save.LockSinceUtc = save.LockSeenUtc = null; save.ChangedUtc = DateTime.UtcNow;
                await db.SaveChangesAsync(); await tx.CommitAsync(); return Results.NoContent();
            });
        });
        game.MapGet("/saves/{id:guid}/snapshots", async (Guid id, HttpContext h, GameDatabase db) => {
            var user = h.UserId(); if (!await db.CloudSaves.AnyAsync(s => s.UserId == user && s.Id == id)) return await Missing(db, user, id);
            return Results.Ok(await db.CloudSnapshots.AsNoTracking().Where(s => s.UserId == user && s.SaveId == id).OrderByDescending(s => s.Revision).Select(s => new { s.Revision, s.CreatedUtc, s.Kind }).ToListAsync());
        });
        game.MapGet("/saves/{id:guid}/snapshots/{revision:long}", async (Guid id, long revision, HttpContext h, GameDatabase db) => {
            var user = h.UserId(); var payload = await db.CloudSnapshots.AsNoTracking().Where(s => s.UserId == user && s.SaveId == id && s.Revision == revision).Select(s => s.Payload).SingleOrDefaultAsync();
            return payload == null ? Results.NotFound(new { error = "That recovery snapshot no longer exists.", code = "not_found" }) : Results.Text(payload, "application/json");
        });
        // Restoring makes the snapshot the newest revision; the snapshots after it stay, so a restore can itself be undone.
        saveWrites.MapPost("/saves/{id:guid}/snapshots/{revision:long}/restore", async (Guid id, long revision, HttpContext h, GameDatabase db) => {
            if (Identify(h) is not { } caller) return BadCaller();
            if (IfMatch(h) is not { } expected) return BadIfMatch();
            return await StateWrites.Run(h, logger, async () => {
                await using var tx = await db.Database.BeginTransactionAsync(); var user = h.UserId(); var now = DateTime.UtcNow;
                var save = await db.CloudSaves.FindAsync(user, id); if (save == null) return await Missing(db, user, id);
                if (await LockConflict(db, save, caller, h) is { } conflict) return conflict;
                if (save.Revision != expected) return Results.Conflict(new { error = "A newer save exists", code = "revision_conflict", revision = save.Revision });
                var snapshot = await db.CloudSnapshots.AsNoTracking().SingleOrDefaultAsync(s => s.UserId == user && s.SaveId == id && s.Revision == revision);
                if (snapshot == null) return Results.NotFound(new { error = "That recovery snapshot no longer exists.", code = "not_found" });
                if (revision == save.Revision) return Bad("That snapshot is already the current save.", "already_current");
                string? name, setupJson, setupKey, meta, run, profile = save.Profile;
                try {
                    using var doc = JsonDocument.Parse(snapshot.Payload); var root = doc.RootElement;
                    name = CleanName(root.GetProperty("name").GetString()); (setupKey, setupJson) = ReadSetup(root.GetProperty("setup")) ?? default; meta = ReadMeta(root.GetProperty("meta"));
                    var r = root.GetProperty("run"); run = r.ValueKind == JsonValueKind.Null ? null : r.GetString();
                    // A snapshot from before saves held their profile keeps the current one.
                    if (root.TryGetProperty("profile", out var p) && p.ValueKind == JsonValueKind.String) profile = p.GetString();
                } catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException) { name = setupJson = setupKey = meta = run = null; }
                if (name == null || setupJson == null || setupKey == null || meta == null) return Bad("That snapshot cannot be restored.", "bad_snapshot");
                var runBytes = Bytes(run); var profileBytes = Bytes(profile);
                save.Name = name; save.SetupKey = setupKey; save.Setup = setupJson; save.Meta = meta; save.Run = run; save.RunBytes = runBytes; save.Profile = profile; save.ProfileBytes = profileBytes; save.Bytes = runBytes + profileBytes + Bytes(meta);
                save.Revision = expected + 1; save.UpdatedUtc = save.ChangedUtc = now; if (save.LockInstance != null) save.LockSeenUtc = now;
                var payload = Payload(name, setupJson, meta, run, profile, save.Revision);
                db.CloudSnapshots.Add(new() { UserId = user, SaveId = id, Revision = save.Revision, Payload = payload, Bytes = Bytes(payload), Kind = "restore", CreatedUtc = now });
                try { await db.SaveChangesAsync(); } catch (DbUpdateConcurrencyException) { return Results.Conflict(new { error = "A newer save exists", code = "revision_conflict", revision = expected }); }
                await Prune(db, user, id);
                if (await Used(db, user) > limit) return StorageFull(limit);
                await tx.CommitAsync();
                h.Response.Headers.ETag = $"\"{save.Revision}\""; return Results.Ok(new { revision = save.Revision, updatedUtc = save.UpdatedUtc });
            });
        });
    }
}
