using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Collections.Concurrent;
using System.Threading.Channels;

namespace Ftl.Server;

// A separate, bounded store: gameplay saves and identity never depend on logging.
public sealed class DebugLogs : IAsyncDisposable {
    public const long UserLimit = 1024L * 1024 * 1024;
    public const long SegmentLimit = 128L * 1024 * 1024;
    private readonly string root;
    private readonly long instanceLimit;
    private readonly long segmentLimit;
    private readonly bool available;
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim exportSlots = new(2, 2);
    private readonly Dictionary<string, int> exportPins = new();
    private readonly Dictionary<string, (string User, long Bytes)> retiredChunks = new();
    private readonly long checkpointScanLimit;
    private readonly ConcurrentDictionary<string, DebugSession> sessions = new();
    private readonly Channel<DebugServerEvent> serverEvents = Channel.CreateBounded<DebugServerEvent>(new BoundedChannelOptions(1024) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private readonly Task serverWorker;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public DebugLogs(string dataDir, long instanceLimit = 10L * UserLimit, long segmentLimit = SegmentLimit, long checkpointScanLimit = 256L * 1024 * 1024) {
        root = Path.Combine(dataDir, "debug-logs");
        this.instanceLimit = Math.Max(1024 * 1024, instanceLimit);
        this.segmentLimit = Math.Clamp(segmentLimit, 65536, SegmentLimit);
        this.checkpointScanLimit = Math.Max(1,checkpointScanLimit);
        try {
        Directory.CreateDirectory(root);
        foreach (var file in Directory.EnumerateFiles(root, "*.json")) {
            try { var s = JsonSerializer.Deserialize<DebugSession>(File.ReadAllText(file), Json)!;
                if (Path.GetFileName(file) != s.Id + ".json" || !Guid.TryParse(s.Id, out _)) { File.Delete(file); continue; }
                var missing = s.Chunks.Where(c => !ValidChunkName(c.Name,s.Id) || !File.Exists(Path.Combine(root, c.Name))).ToArray();
                s.DroppedRecords += missing.Sum(c => c.Records);
                foreach (var c in missing) s.Chunks.Remove(c);
                foreach (var c in s.Chunks) c.Bytes = new FileInfo(Path.Combine(root, c.Name)).Length;
                sessions[s.Id] = s; Persist(s); }
            catch { File.Delete(file); }
        }
        var retained = sessions.Values.SelectMany(s => s.Chunks).Select(c => c.Name).ToHashSet();
        foreach (var file in Directory.EnumerateFiles(root))
            if (file.EndsWith(".tmp") || (file.EndsWith(".gz") && !retained.Contains(Path.GetFileName(file)))) File.Delete(file);
        available = true;
        } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
            // A diagnostic volume can fail independently of the identity/save
            // database. Report it through the Logs API without breaking play.
            sessions.Clear(); available = false;
        }
        serverWorker = Task.Run(DrainServerEvents);
    }
    private DebugSession? Owned(string user, string id) => sessions.TryGetValue(id, out var s) && s.UserId == user ? s : null;
    private static bool ValidChunkName(string name,string id) => name.StartsWith(id+"-",StringComparison.Ordinal)
        && System.Text.RegularExpressions.Regex.IsMatch(name,@"^[a-f0-9]{32}-[0-9]+-[0-9]+\.jsonl\.gz$",System.Text.RegularExpressions.RegexOptions.CultureInvariant);
    private long Bytes(DebugSession s) => s.Chunks.Sum(c => c.Bytes) + (File.Exists(Path.Combine(root, s.Id + ".json")) ? new FileInfo(Path.Combine(root, s.Id + ".json")).Length : 0);
    private void Persist(DebugSession s) { var file = Path.Combine(root, s.Id + ".json"); File.WriteAllText(file + ".tmp", JsonSerializer.Serialize(s, Json)); File.Move(file + ".tmp", file, true); }
    private void Retire(string user,DebugChunk chunk) {
        if(exportPins.ContainsKey(chunk.Name))retiredChunks.TryAdd(chunk.Name,(user,chunk.Bytes));
        else File.Delete(Path.Combine(root,chunk.Name));
    }
    private void Remove(DebugSession s) { foreach (var c in s.Chunks) Retire(s.UserId,c); File.Delete(Path.Combine(root, s.Id + ".json")); sessions.TryRemove(s.Id, out _); }
    private long RetainedBytes(string? user=null) => sessions.Values.Where(s=>user==null||s.UserId==user).Sum(Bytes)
        +retiredChunks.Values.Where(c=>user==null||c.User==user).Sum(c=>c.Bytes);
    private void ReapRetired() {
        foreach(var name in retiredChunks.Keys.Where(n=>!exportPins.ContainsKey(n)).ToArray()) {
            try {File.Delete(Path.Combine(root,name));retiredChunks.Remove(name);}
            catch(Exception e) when(e is IOException or UnauthorizedAccessException) { /* Keep failed deletions in quota accounting. */ }
        }
    }
    public async Task<bool> DeleteAccount(string user) {
        await gate.WaitAsync(); try {
            if (!available && Directory.Exists(root)) throw new IOException("Diagnostic storage must recover before account cleanup");
            foreach(var s in sessions.Values.Where(s => s.UserId == user).ToArray()) Remove(s);
            ReapRetired();
            return !retiredChunks.Values.Any(c => c.User == user);
        } finally { gate.Release(); }
    }
    public async Task<object> AccountData(string user) { await gate.WaitAsync(); try { return new { sessions = sessions.Values.Count(s => s.UserId == user), bytes = RetainedBytes(user), available }; } finally { gate.Release(); } }
    private void Reserve(string user, long incoming, DebugSession? active) {
        // Reserve metadata overhead and hold one lock across quota checks and writes.
        ReapRetired();
        while (RetainedBytes(user) + incoming + 65536 > UserLimit || RetainedBytes() + incoming + 65536 > instanceLimit) {
            var userOver = RetainedBytes(user) + incoming + 65536 > UserLimit;
            var candidates = sessions.Values.Where(s => !userOver || s.UserId == user).OrderBy(s => s.FinishedUtc == null ? 1 : 0).ThenBy(s => s.CreatedUtc).ToList();
            var old = candidates.FirstOrDefault(s => s != active);
            if (old != null) { Remove(old); continue; }
            if (active == null || active.Chunks.Count == 0) throw new IOException("Diagnostic storage limit reached");
            var chunk = active.Chunks[0]; Retire(active.UserId,chunk); active.Chunks.RemoveAt(0); active.DroppedRecords += chunk.Records; Persist(active);
        }
    }
    public async Task<object> Create(string user, DebugStart body) {
        if (!available) throw new DiagnosticUnavailableException();
        await gate.WaitAsync(); try {
            if (body.ContinuationOf != null && Owned(user, body.ContinuationOf) == null) throw new ArgumentException("Unknown previous diagnostic session");
            if (sessions.Values.Count(s => s.UserId == user) >= 1000) Remove(sessions.Values.Where(s => s.UserId == user).OrderBy(s => s.CreatedUtc).First());
            Reserve(user, 4096, null);
            var s = new DebugSession { Id = Guid.NewGuid().ToString("N"), UserId = user, Build = (body.Build ?? "")[..Math.Min(body.Build?.Length ?? 0, 128)], Device = (body.Device ?? "")[..Math.Min(body.Device?.Length ?? 0, 256)], ContinuationOf = body.ContinuationOf };
            sessions[s.Id] = s; Persist(s); return new { s.Id, s.CreatedUtc, lastClientSequence = s.LastClientSequence };
        } finally { gate.Release(); }
    }
    private void Append(DebugSession s, IEnumerable<JsonElement> records, int count, long? acknowledgedSequence = null, int errors = 0) {
        using var output = new MemoryStream();
        using (var gzip = new GZipStream(output, CompressionLevel.Fastest, true))
        using (var writer = new StreamWriter(gzip, Encoding.UTF8)) foreach (var record in records) writer.WriteLine(record.GetRawText());
        var bytes = output.ToArray();
        if (s.Chunks.Sum(c => c.Bytes) + bytes.Length + 4096 > segmentLimit) throw new DiagnosticSegmentFullException();
        Reserve(s.UserId, bytes.Length + Bytes(s) - s.Chunks.Sum(c => c.Bytes) + 4096, s);
        var name = $"{s.Id}-{s.Segment}-{s.NextChunk++}.jsonl.gz";
        File.WriteAllBytes(Path.Combine(root, name + ".tmp"), bytes); File.Move(Path.Combine(root, name + ".tmp"), Path.Combine(root, name));
        s.Chunks.Add(new DebugChunk { Name = name, Bytes = bytes.Length, Records = count }); s.SegmentBytes += bytes.Length; s.Records += count; s.Errors += errors; s.UpdatedUtc = DateTime.UtcNow;
        if (acknowledgedSequence.HasValue) s.LastClientSequence = acknowledgedSequence.Value;
        Persist(s);
    }
    public async Task<object?> Ingest(string user, string id, DebugBatch batch) {
        if (batch.Records == null || batch.Records.Length > 512) throw new ArgumentException("At most 512 records per batch");
        await gate.WaitAsync(); try {
            var s = Owned(user, id); if (s == null) return null;
            var records = new List<JsonElement>(); long last = s.LastClientSequence;
            foreach (var record in batch.Records) {
                if (record.ValueKind != JsonValueKind.Object || !record.TryGetProperty("sequence", out var seq) || !seq.TryGetInt64(out var number) || number < 1) throw new ArgumentException("Invalid sequence");
                if (number <= s.LastClientSequence) continue;
                if (number <= last || record.GetRawText().Length > 1024 * 1024) throw new ArgumentException("Invalid record order or size");
                last = number;
                var safe = JsonSerializer.SerializeToElement(new { sessionId = s.Id, sequence = number, source = "client", receivedUtc = DateTime.UtcNow,
                    severity = Field(record, "severity", 16), category = Field(record, "category", 64), timestamp = Field(record, "timestamp", 64), tick = record.TryGetProperty("tick", out var tick) ? tick : default(JsonElement?),
                    eventName = Field(record, "eventName", 128), payload = record.TryGetProperty("payload", out var payload) ? Redact(payload) : null }, Json);
                records.Add(safe);
                if (Field(record, "eventName", 128) == "checkpoint.state" && record.TryGetProperty("payload", out var state) && state.ValueKind == JsonValueKind.Object) {
                    s.Ship = Field(state, "ship", 128);
                    if (state.TryGetProperty("ae", out var ae) && ae.ValueKind is JsonValueKind.True or JsonValueKind.False) s.AdvancedEdition = ae.GetBoolean();
                }
                if (Field(record, "eventName", 128) == "build.loaded" && record.TryGetProperty("payload", out var build) && build.ValueKind == JsonValueKind.Object) s.Build = Field(build, "build", 128);
            }
            if (records.Count > 0) Append(s, records, records.Count, last, records.Count(r => Field(r, "severity", 16) == "error"));
            return new { acknowledgedSequence = s.LastClientSequence, s.DroppedRecords, bytes = Bytes(s) };
        } finally { gate.Release(); }
    }
    private static string Field(JsonElement e, string key, int max) => e.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String ? (v.GetString() ?? "")[..Math.Min(v.GetString()!.Length, max)] : "";
    private static object? Redact(JsonElement e, int depth = 0) {
        if (depth > 20) return "[depth limit]";
        if (e.ValueKind == JsonValueKind.Object) return e.EnumerateObject().ToDictionary(p => p.Name, p => System.Text.RegularExpressions.Regex.IsMatch(p.Name, "password|cookie|token|authorization|lease|credential", System.Text.RegularExpressions.RegexOptions.IgnoreCase) ? "[redacted]" : Redact(p.Value, depth + 1));
        if (e.ValueKind == JsonValueKind.Array) return e.EnumerateArray().Select(v => Redact(v, depth + 1)).ToArray();
        return e.Clone();
    }
    public Task Server(string user, string id, string name, object payload, bool error = false) {
        var s = Owned(user, id);
        if (s != null && !serverEvents.Writer.TryWrite(new DebugServerEvent(user, id, name, payload, error, DateTime.UtcNow)))
            Interlocked.Increment(ref s.PendingServerDrops);
        // Save and gameplay responses must not wait for disk I/O, an export,
        // or a diagnostic quota lock. The queue is bounded independently.
        return Task.CompletedTask;
    }
    private async Task DrainServerEvents() {
        await foreach (var e in serverEvents.Reader.ReadAllAsync()) {
            await gate.WaitAsync();
            try {
                var s = Owned(e.User, e.Session); if (s == null) continue;
                s.DroppedServerRecords += Interlocked.Exchange(ref s.PendingServerDrops, 0);
                var safePayload = Redact(JsonSerializer.SerializeToElement(e.Payload, Json));
                var r = JsonSerializer.SerializeToElement(new { sessionId = s.Id, sequence = ++s.ServerSequence, source = "server", timestamp = e.Timestamp, severity = e.Error ? "error" : "info", category = "http", eventName = e.Name, payload = safePayload }, Json);
                try { Append(s, [r], 1, errors: e.Error ? 1 : 0); }
                catch (DiagnosticSegmentFullException) { s.DroppedServerRecords++; Persist(s); }
                catch { Interlocked.Increment(ref s.PendingServerDrops); }
            } catch { /* Diagnostic failures never terminate the writer or gameplay. */ }
            finally { gate.Release(); }
        }
    }
    public async ValueTask DisposeAsync() {
        serverEvents.Writer.TryComplete();
        await Task.WhenAny(serverWorker, Task.Delay(5000));
    }
    public async Task<object> List(string user) { await gate.WaitAsync(); try { return new { available, usedBytes = RetainedBytes(user), quotaBytes = UserLimit,
        sessions = sessions.Values.Where(s => s.UserId == user).OrderByDescending(s => s.CreatedUtc).Select(s => new { s.Id, s.CreatedUtc, s.UpdatedUtc, s.FinishedUtc, s.Build, s.Device, s.Ship, s.AdvancedEdition, s.ContinuationOf, s.Records, s.Errors, s.DroppedRecords, s.DroppedServerRecords, bytes = Bytes(s) }).ToArray() }; } finally { gate.Release(); } }
    public async Task<bool> Finish(string user, string id) { await gate.WaitAsync(); try { var s = Owned(user, id); if (s == null) return false; s.FinishedUtc ??= DateTime.UtcNow; Persist(s); return true; } finally { gate.Release(); } }
    public async Task<bool> Delete(string user, string? id) { await gate.WaitAsync(); try { if (id != null) { var s = Owned(user, id); if (s == null) return false; Remove(s); } else foreach (var s in sessions.Values.Where(s => s.UserId == user).ToArray()) Remove(s); return true; } finally { gate.Release(); } }
    private IEnumerable<JsonElement> Read(DebugSession s) { foreach (var c in s.Chunks) { using var gzip = new GZipStream(File.OpenRead(Path.Combine(root, c.Name)), CompressionMode.Decompress); using var reader = new StreamReader(gzip); string? line; while ((line = reader.ReadLine()) != null) { using var doc = JsonDocument.Parse(line); yield return doc.RootElement.Clone(); } } }
    public async Task<object?> Records(string user, string id, long after, string? category, string? severity, string? search, DateTime? from, DateTime? to, bool markers) {
        await gate.WaitAsync(); try { var s = Owned(user, id); if (s == null) return null;
            return new { s.Id, s.DroppedRecords, s.DroppedServerRecords, records = Read(s).Select((r, i) => new { index = (long)i + 1 + s.DroppedRecords, record = r }).Where(r => r.index > after && (string.IsNullOrEmpty(category) || Field(r.record, "category", 64) == category) && (string.IsNullOrEmpty(severity) || Field(r.record, "severity", 16) == severity) && (string.IsNullOrEmpty(search) || r.record.GetRawText().Contains(search, StringComparison.OrdinalIgnoreCase)) && (!markers || Field(r.record, "eventName", 128) == "issue.mark") && WithinTime(r.record, from, to)).Take(200).ToArray() };
        } finally { gate.Release(); }
    }
    private static bool WithinTime(JsonElement r, DateTime? from, DateTime? to) {
        if (from == null && to == null) return true;
        var timestamp = Field(r, "timestamp", 64);
        if (string.IsNullOrEmpty(timestamp)) timestamp = Field(r, "receivedUtc", 64);
        return DateTime.TryParse(timestamp, System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.RoundtripKind, out var time)
            && (from == null || time.ToUniversalTime() >= from.Value.ToUniversalTime()) && (to == null || time.ToUniversalTime() <= to.Value.ToUniversalTime());
    }
    public async Task<bool> Export(string user, string id, HttpResponse response, CancellationToken cancellation) {
        if(!await exportSlots.WaitAsync(0,cancellation))throw new DiagnosticExportBusyException();
        using var deadline=CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        deadline.CancelAfter(TimeSpan.FromMinutes(5));cancellation=deadline.Token;
        List<string> chunkNames=[];
        try {
        string sessionId, build, device, ship;
        bool? ae;
        string? continuationOf;
        DateTime createdUtc;
        DateTime? finishedUtc;
        long droppedRecords, droppedServerRecords, records, errors;

        await gate.WaitAsync(cancellation);
        try {
            var s = Owned(user, id);
            if (s == null) return false;
            sessionId = s.Id; build = s.Build; device = s.Device; ship = s.Ship;
            ae = s.AdvancedEdition; continuationOf = s.ContinuationOf;
            createdUtc = s.CreatedUtc; finishedUtc = s.FinishedUtc;
            droppedRecords = s.DroppedRecords; droppedServerRecords = s.DroppedServerRecords;
            records = s.Records; errors = s.Errors;
            chunkNames = s.Chunks.Select(c => c.Name).ToList();
            foreach(var name in chunkNames)exportPins[name]=exportPins.GetValueOrDefault(name)+1;
        } finally {
            gate.Release();
        }

        response.ContentType = "application/zip";
        response.Headers.ContentDisposition = $"attachment; filename=ftl-debug-{sessionId}.zip";
        await using var zip = await ZipArchive.CreateAsync(response.BodyWriter.AsStream(), ZipArchiveMode.Create, true, null, cancellation);
        await using (var target = await zip.CreateEntry("metadata.json").OpenAsync(cancellation))
            await JsonSerializer.SerializeAsync(target, new { Id = sessionId, Build = build, Device = device, Ship = ship, AdvancedEdition = ae, ContinuationOf = continuationOf, CreatedUtc = createdUtc, FinishedUtc = finishedUtc, DroppedRecords = droppedRecords, DroppedServerRecords = droppedServerRecords, Records = records, Errors = errors }, Json, cancellation);
        await using (var target = await zip.CreateEntry("summary.txt").OpenAsync(cancellation))
            await target.WriteAsync(Encoding.UTF8.GetBytes($"FTL debug session {sessionId}\nBuild: {build}\nRecords: {records}\nErrors: {errors}\nRotated records: {droppedRecords}\nDropped server records: {droppedServerRecords}\nContinuation of: {continuationOf}\n"), cancellation);

        foreach (var chunkName in chunkNames) {
            var chunkPath = Path.Combine(root, chunkName);
            await using var target = await zip.CreateEntry(chunkName, CompressionLevel.NoCompression).OpenAsync(cancellation);
            await using var input = File.OpenRead(chunkPath);
            await input.CopyToAsync(target, cancellation);
        }

        // Assemble complete checkpoints as ordinary files
        string? checkpoint = null;
        var pieces = new Dictionary<(string Name, int Part), string>();
        var totals = new Dictionary<string, int>();
        var buffered = 0;
        long scanned=0;bool scanLimited=false;
        foreach (var chunkName in chunkNames) {
            var chunkPath = Path.Combine(root, chunkName);
            using var gzip = new GZipStream(File.OpenRead(chunkPath), CompressionMode.Decompress);
            using var reader = new StreamReader(gzip);
            string? line;
            while ((line = await reader.ReadLineAsync(cancellation)) != null) {
                cancellation.ThrowIfCancellationRequested();
                scanned+=line.Length;
                if(scanned>checkpointScanLimit){scanLimited=true;break;}
                using var doc = JsonDocument.Parse(line);
                var r = doc.RootElement;
                if (!r.TryGetProperty("payload", out var p) || p.ValueKind != JsonValueKind.Object) continue;
                var idValue = Field(p, "id", 40);
                if (!Guid.TryParse(idValue, out var checkpointId)) continue;
                var eventName = Field(r, "eventName", 128);
                if (eventName == "checkpoint.start") { checkpoint = idValue; pieces.Clear(); totals.Clear(); buffered = 0; }
                else if (idValue == checkpoint && eventName == "checkpoint.part") {
                    var name = Field(p, "name", 16);
                    if (name is not ("run" or "profile") || !p.TryGetProperty("part", out var partValue) || !partValue.TryGetInt32(out var part)
                        || !p.TryGetProperty("parts", out var totalValue) || !totalValue.TryGetInt32(out var total)
                        || part < 0 || total is < 1 or > 512 || part >= total || !p.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.String) continue;
                    var text = data.GetString()!;
                    if (text.Length > 32768 || buffered + text.Length > 32 * 1024 * 1024 || pieces.ContainsKey((name, part))) continue;
                    if (totals.TryGetValue(name, out var previousTotal) && previousTotal != total) continue;
                    totals[name] = total; pieces[(name, part)] = text; buffered += text.Length;
                } else if (idValue == checkpoint && eventName == "checkpoint.end") {
                    foreach (var (name, total) in totals) {
                        if (!Enumerable.Range(0, total).All(part => pieces.ContainsKey((name, part)))) continue;
                        await using var target = await zip.CreateEntry($"checkpoints/{checkpointId:N}/{name}.xml").OpenAsync(cancellation);
                        await using var writer = new StreamWriter(target, new UTF8Encoding(false), leaveOpen: true);
                        for (var part = 0; part < total; part++) await writer.WriteAsync(pieces[(name, part)].AsMemory(), cancellation);
                    }
                    checkpoint = null; pieces.Clear(); totals.Clear(); buffered = 0;
                }
            }
            if(scanLimited)break;
        }
        if(scanLimited) {
            await using var status=await zip.CreateEntry("export-status.json").OpenAsync(cancellation);
            await JsonSerializer.SerializeAsync(status,new{checkpointScanComplete=false,reason="Checkpoint scan limit reached. All compressed log chunks are included."},Json,cancellation);
        }
        return true;
        } finally {
            try {
                await gate.WaitAsync();
                try {
                    foreach(var name in chunkNames) {
                        if(exportPins.TryGetValue(name,out var count)) {
                            if(count==1)exportPins.Remove(name);else exportPins[name]=count-1;
                        }
                    }
                    ReapRetired();
                } finally {gate.Release();}
            } finally {exportSlots.Release();}
        }
    }
    public static void Map(RouteGroupBuilder api) {
        api.MapPost("/debug/sessions", async (DebugStart body, HttpContext h, DebugLogs logs) => {
            try { return Results.Ok(await logs.Create(h.UserId(), body)); }
            catch (ArgumentException e) { return Results.BadRequest(new { error = e.Message }); }
            catch (DiagnosticUnavailableException) { return Results.Json(new { error = "Diagnostic storage is unavailable. Gameplay and saves remain available." }, statusCode: 503); }
        });
        api.MapGet("/debug/sessions", async (HttpContext h, DebugLogs logs) => Results.Ok(await logs.List(h.UserId())));
        api.MapPost("/debug/sessions/{id}/batches", async (string id, HttpContext h, DebugLogs logs) => {
            if (h.Request.ContentLength > 4 * 1024 * 1024) return Results.StatusCode(413);
            var limit = h.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();
            if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = 4 * 1024 * 1024;
            try { var body = await h.Request.ReadFromJsonAsync<DebugBatch>(h.RequestAborted); if (body == null) return Results.BadRequest(); var result = await logs.Ingest(h.UserId(), id, body); return result == null ? Results.NotFound() : Results.Ok(result); }
            catch (ArgumentException e) { return Results.BadRequest(new { error = e.Message }); }
            catch (DiagnosticSegmentFullException) { return Results.Conflict(new { code = "session_full", error = "Start a continuation session before appending more records" }); }
            catch (JsonException) { return Results.BadRequest(new { error = "Invalid diagnostic batch" }); }
        });
        api.MapPost("/debug/sessions/{id}/finish", async (string id, HttpContext h, DebugLogs logs) => await logs.Finish(h.UserId(), id) ? Results.NoContent() : Results.NotFound());
        api.MapGet("/debug/sessions/{id}/records", async (string id, long? after, string? category, string? severity, string? search, DateTime? from, DateTime? to, bool? markers, HttpContext h, DebugLogs logs) => { var r = await logs.Records(h.UserId(), id, after ?? 0, category, severity, search, from, to, markers ?? false); return r == null ? Results.NotFound() : Results.Ok(r); });
        api.MapGet("/debug/sessions/{id}/export", async (string id, HttpContext h, DebugLogs logs) => {
            try {if (!await logs.Export(h.UserId(), id, h.Response, h.RequestAborted)) h.Response.StatusCode = 404;}
            catch(DiagnosticExportBusyException){h.Response.StatusCode=429;h.Response.Headers.RetryAfter="5";}
        }).RequireRateLimiting("debug-export");
        api.MapDelete("/debug/sessions/{id}", async (string id, HttpContext h, DebugLogs logs) => await logs.Delete(h.UserId(), id) ? Results.NoContent() : Results.NotFound());
        api.MapDelete("/debug/sessions", async (HttpContext h, DebugLogs logs) => { await logs.Delete(h.UserId(), null); return Results.NoContent(); });
    }
}
public sealed class DebugSession {
    public string Id { get; set; } = "";
    public string UserId { get; set; } = "";
    public string Build { get; set; } = "";
    public string Device { get; set; } = "";
    public string Ship { get; set; } = "";
    public bool? AdvancedEdition { get; set; }
    public string? ContinuationOf { get; set; }
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedUtc { get; set; } = DateTime.UtcNow;
    public DateTime? FinishedUtc { get; set; }
    public long LastClientSequence { get; set; }
    public long ServerSequence { get; set; }
    public long Records { get; set; }
    public long Errors { get; set; }
    public long DroppedRecords { get; set; }
    public long DroppedServerRecords { get; set; }
    internal long PendingServerDrops;
    public int Segment { get; set; }
    public long SegmentBytes { get; set; }
    public int NextChunk { get; set; }
    public List<DebugChunk> Chunks { get; set; } = [];
}
public sealed class DebugChunk { public string Name { get; set; } = ""; public long Bytes { get; set; } public int Records { get; set; } }
public sealed class DiagnosticSegmentFullException : Exception { }
public sealed class DiagnosticUnavailableException : Exception { }
public sealed class DiagnosticExportBusyException : Exception { }
internal sealed record DebugServerEvent(string User, string Session, string Name, object Payload, bool Error, DateTime Timestamp);
public record DebugStart(string? Build, string? Device, string? ContinuationOf = null);
public record DebugBatch(JsonElement[] Records);
