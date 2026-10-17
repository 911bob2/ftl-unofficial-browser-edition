using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace Ftl.Server;

// Fixed test scripts run only against fresh loopback fixtures, never the live database.
public sealed class LauncherTests : IDisposable {
    public static readonly string[] Suites = ["unit", "security", "menus", "diagnostics", "storage", "production", "engine"];
    private readonly object gate = new();
    private readonly List<Job> jobs = [];
    private readonly bool enabled;
    private Process? active;
    private Job? running;
    private bool stopping;
    public LauncherTests(IConfiguration config, IHostApplicationLifetime lifetime) {
        enabled = config["FTL_TEST_RUNNER_ENABLED"] != "false" && File.Exists("/app/tools/RunLauncherTests.mjs");
        lifetime.ApplicationStopping.Register(Dispose);
    }
    public bool Available => enabled;
    public bool RemoveOwner(string owner) {
        lock(gate) {
            if(running?.Owner == owner) { running.Cancelled = true; Kill(); return false; }
            jobs.RemoveAll(j => j.Owner == owner); return true;
        }
    }
    public object[] List(string owner) { lock(gate) return jobs.Where(j => j.Owner == owner).Select(Snapshot).ToArray(); }
    public object? Get(string owner, string id) { lock(gate) return jobs.FirstOrDefault(j => j.Owner == owner && j.Id == id) is {} j ? Snapshot(j) : null; }
    public string Start(string owner, string[]? suites) {
        if (!enabled) throw new NotSupportedException("The packaged test runner is unavailable");
        if (suites == null || suites.Length == 0 || suites.Length > Suites.Length || suites.Any(s => !Suites.Contains(s))) throw new ArgumentException("Choose valid test suites");
        lock(gate) {
            if (stopping || running != null) throw new InvalidOperationException("A server test run is already in progress");
            var job = new Job { Owner = owner, Suites = suites.Distinct().ToArray() };
            jobs.Add(job); while(jobs.Count > 16) jobs.RemoveAt(0);
            running = job;
            _ = Task.Run(() => Run(job));
            return job.Id;
        }
    }
    public bool Cancel(string owner, string id) {
        lock(gate) {
            var job = jobs.FirstOrDefault(j => j.Owner == owner && j.Id == id);
            if(job == null) return false;
            if(job == running) { job.Cancelled = true; Kill(); }
            return true;
        }
    }
    private static object Snapshot(Job j) => new { j.Id, j.Suites, j.StartedUtc, j.FinishedUtc, j.Status, j.ExitCode, j.CurrentSuite, results = j.Results.ToArray(), log = j.Log.ToString(), j.Truncated };
    private void Line(Job j, string line) {
        lock(gate) {
            if(line.StartsWith("BEGIN ")) j.CurrentSuite = line[6..];
            if(line.StartsWith("FTL_TEST ")) {
                try { using var doc = JsonDocument.Parse(line[9..]); j.Results.Add(doc.RootElement.Clone()); } catch(JsonException) {}
            }
            // Reports never include fixture credential files, cookies or request headers.
            line = line.Length > 8192 ? line[..8192] + " [line truncated]" : line;
            if(j.Log.Length + line.Length > 512 * 1024) { j.Log.Remove(0, Math.Min(j.Log.Length, 64 * 1024)); j.Truncated = true; }
            j.Log.AppendLine(line);
        }
    }
    private async Task Run(Job job) {
        var directory = Path.Combine(Path.GetTempPath(), "ftl-tests-" + job.Id);
        try {
            Directory.CreateDirectory(directory);
            using var process = new Process { StartInfo = new ProcessStartInfo("/usr/local/bin/node") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, WorkingDirectory = directory } };
            process.StartInfo.ArgumentList.Add("/app/tools/RunLauncherTests.mjs");
            process.StartInfo.ArgumentList.Add(string.Join(',', job.Suites));
            process.StartInfo.Environment["HOME"] = directory;
            // Browser profiles must be inside the job directory too, so killing
            // a process tree cannot leave profiles outside our cleanup boundary.
            foreach(var variable in new[] { "TMPDIR", "TMP", "TEMP" }) process.StartInfo.Environment[variable] = directory;
            process.StartInfo.Environment["BASELINE_ORIGIN"] = "http://localhost:8084";
            process.StartInfo.Environment["BASELINE_MODES"] = "achievements,unlocks,drones,interaction,warnings,gameplay-options,gameplay-parity,ae-mode,mechanics,progress,combat,vanilla,ae,encounters,campaign,tutorial,tutorial-isolation,tutorial-gates,tutorial-adapter,tutorial-overlay,tutorial-entry,tutorial-encounters,tutorial-play";
            lock(gate) {
                if(job.Cancelled || stopping) return;
                process.Start(); active = process;
            }
            async Task Drain(StreamReader stream) { while(await stream.ReadLineAsync() is {} line) Line(job, line); }
            var output = Drain(process.StandardOutput); var errors = Drain(process.StandardError);
            using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(20));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch(OperationCanceledException) { Line(job, "Test run exceeded the twenty-minute limit"); lock(gate) Kill(); await process.WaitForExitAsync(); }
            await Task.WhenAll(output, errors);
            lock(gate) { job.ExitCode = process.ExitCode; job.Status = job.Cancelled ? "cancelled" : process.ExitCode == 0 ? "passed" : "failed"; }
        } catch(Exception e) { Line(job, "Runner failed: " + e.Message); lock(gate) job.Status = job.Cancelled ? "cancelled" : "failed"; }
        finally {
            lock(gate) { if(job.Cancelled) job.Status = "cancelled"; job.FinishedUtc = DateTimeOffset.UtcNow; active = null; running = null; }
            // The directory contains only this generated job's disposable fixtures.
            try { Directory.Delete(directory, true); } catch(IOException) {} catch(UnauthorizedAccessException) {}
        }
    }
    private void Kill() { try { if(active is { HasExited: false }) active.Kill(entireProcessTree: true); } catch(InvalidOperationException) {} }
    public void Dispose() { lock(gate) { stopping = true; if(running != null) running.Cancelled = true; Kill(); } }
    private sealed class Job {
        public string Id {get;} = Guid.NewGuid().ToString("N");
        public required string Owner {get;init;}
        public required string[] Suites {get;init;}
        public DateTimeOffset StartedUtc {get;} = DateTimeOffset.UtcNow;
        public DateTimeOffset? FinishedUtc {get;set;}
        public string Status {get;set;} = "running";
        public int? ExitCode {get;set;}
        public string? CurrentSuite {get;set;}
        public bool Cancelled, Truncated;
        public StringBuilder Log {get;} = new();
        public List<JsonElement> Results {get;} = [];
    }
}
public record LauncherTestRequest(string[] Suites);
