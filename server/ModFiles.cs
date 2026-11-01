using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Ftl.Server;

// Pinned mod files (web/mods/catalogue.json) the server may hand out. An operator places them in FTL_MODS_DIR (outside
// Git and the image, e.g. with tools/mods/fetch-mods.mjs); each is offered only after its size and SHA-256 match the
// catalogue, checked once in the background at startup. Who may download is decided here, never by the page:
//   policy "hyperspace": every signed-in role (GPL-3.0-or-later redistribution, notices in third_party/notices);
//   policy "multiverse": nobody. We do not host Multiverse; players get it from its own MEGA release or a local file.
public sealed partial class ModCatalog {
    public enum State { Absent, Verifying, Ready, Mismatch }
    public sealed record Entry(string Id, string File, long Size, string Sha256, string Policy, JsonObject Raw);
    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\z", RegexOptions.CultureInvariant)] private static partial Regex FileName();
    [GeneratedRegex(@"^[a-z0-9][a-z0-9.-]{0,63}\z", RegexOptions.CultureInvariant)] private static partial Regex IdPattern();
    readonly Dictionary<string, State> states = [];
    readonly object gate = new();
    public string Directory { get; }
    public IReadOnlyList<Entry> Files { get; }
    public JsonArray Presets { get; }
    public Task Verified { get; }

    public ModCatalog(IConfiguration config, IWebHostEnvironment host, ILogger<ModCatalog> logger) {
        Directory = Path.GetFullPath(config["FTL_MODS_DIR"] ?? "/mods");
        var path = config["FTL_MODS_CATALOGUE"] ?? Path.Combine(host.WebRootPath ?? Path.Combine(host.ContentRootPath, "wwwroot"), "web", "mods", "catalogue.json");
        var files = new List<Entry>(); Presets = [];
        if (File.Exists(path)) {
            var root = JsonNode.Parse(File.ReadAllText(path))!.AsObject();
            foreach (var node in root["files"]?.AsArray() ?? []) {
                var o = node!.AsObject();
                var e = new Entry((string)o["id"]!, (string)o["file"]!, (long)o["size"]!, ((string)o["sha256"]!).ToLowerInvariant(), (string?)o["policy"] ?? "", o);
                if (!IdPattern().IsMatch(e.Id) || !FileName().IsMatch(e.File) || e.File.Contains("..") || e.Size <= 0 || e.Sha256.Length != 64 || files.Any(f => f.Id == e.Id))
                    throw new InvalidDataException("Invalid mod catalogue entry " + e.Id);
                files.Add(e);
            }
            Presets = root["presets"]?.DeepClone().AsArray() ?? [];
        } else logger.LogWarning("Mod catalogue {Path} is missing; no mod files are offered", path);
        Files = files;
        foreach (var e in files) states[e.Id] = File.Exists(Path.Combine(Directory, e.File)) ? State.Verifying : State.Absent;
        // Hashing 712 MB takes a few seconds; the server is ready meanwhile and lists those files as "verifying".
        Verified = Task.Run(async () => {
            foreach (var e in files.Where(f => StateOf(f) == State.Verifying)) {
                var ok = false;
                try {
                    await using var stream = new FileStream(Path.Combine(Directory, e.File), FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20, FileOptions.SequentialScan);
                    ok = stream.Length == e.Size && Convert.ToHexString(await SHA256.HashDataAsync(stream)).ToLowerInvariant() == e.Sha256;
                } catch (Exception x) { logger.LogWarning(x, "Could not read mod file {File}", e.File); }
                lock (gate) states[e.Id] = ok ? State.Ready : State.Mismatch;
                if (!ok) logger.LogWarning("Mod file {File} does not match the catalogue (size or SHA-256); it is not offered", e.File);
            }
        });
    }
    public State StateOf(Entry e) { lock (gate) return states[e.Id]; }
    public Entry? Find(string id) => Files.FirstOrDefault(f => f.Id == id);

    /// <summary>Whether this request's account may download the entry from this server (independent of presence).</summary>
    public bool Permitted(HttpContext h, Entry e) => AccountRoles.CanPlay(h) && e.Policy switch {
        "hyperspace" => true,
        _ => false,
    };

    JsonObject Describe(Entry e, bool? permitted) {
        var o = e.Raw.DeepClone().AsObject();
        var state = StateOf(e);
        var reason = permitted == null ? "sign-in" : permitted == false ? "not-permitted" : state switch { State.Ready => "ready", State.Verifying => "verifying", State.Mismatch => "mismatch", _ => "absent" };
        o["server"] = new JsonObject { ["available"] = reason == "ready", ["reason"] = reason };
        return o;
    }
    public JsonObject Catalogue(HttpContext? h) => new() {
        ["schema"] = 1,
        ["files"] = new JsonArray(Files.Select(e => (JsonNode)Describe(e, h == null ? null : Permitted(h, e))).ToArray()),
        ["presets"] = Presets.DeepClone(),
    };

    public static void Map(RouteGroupBuilder game, WebApplication app) {
        game.MapGet("/mods/catalogue", (HttpContext h, ModCatalog c) => Results.Json(c.Catalogue(h)));
        game.MapGet("/mods/files/{id}", (string id, HttpContext h, ModCatalog c) => {
            var e = c.Find(id);
            if (e == null) return Results.NotFound(new { error = "Unknown mod file" });
            if (!c.Permitted(h, e)) return Results.Json(new { error = "Your account may not download this file from this server. Use its official source or a file you already have.", code = "mod_not_permitted" }, statusCode: 403);
            if (c.StateOf(e) != State.Ready) return Results.NotFound(new { error = "This server does not offer this file.", code = "mod_absent" });
            h.Response.Headers.CacheControl = "private, no-store";
            return Results.File(Path.Combine(c.Directory, e.File), "application/octet-stream", e.File, enableRangeProcessing: true);
        }).RequireRateLimiting("mod-download");
        // Local-only visitors see the same catalogue without server downloads (they have no account).
        app.MapGet("/api/public/mods/catalogue", (ModCatalog c) => Results.Json(c.Catalogue(null))).RequireRateLimiting("public");
    }
}
