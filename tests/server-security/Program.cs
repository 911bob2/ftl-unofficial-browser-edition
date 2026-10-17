using System.IO.Compression;
using System.Net;
using System.Security.Claims;
using System.Text;
using System.Text.Json;
using System.Data.Common;
using Ftl.Server;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

if(args.FirstOrDefault()=="expire-user-sessions") {
    if(args.Length!=3)throw new ArgumentException("Expected fixture database and user ID");
    var options=new DbContextOptionsBuilder<GameDatabase>().UseSqlite($"Data Source={args[1]}").Options;
    await using var db=new GameDatabase(options);
    if(await db.Sessions.Where(s=>s.UserId==args[2]).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddMinutes(-1)))==0)throw new Exception("Fixture has no sessions to expire");
    return;
}
var scratch=Path.Combine(Path.GetTempPath(),"ftl-security-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(scratch);
var checks=0;
void Check(bool condition,string name){if(!condition)throw new Exception(name);checks++;Console.WriteLine("PASS "+name);}
async Task Throws<T>(Func<Task> action,string name) where T:Exception {try{await action();}catch(T){Check(true,name);return;}throw new Exception(name);}
try {
    var blocker=new CommandBlocker();
    var services=new ServiceCollection();services.AddLogging();
    services.AddDbContext<GameDatabase>(o=>o.UseSqlite($"Data Source={Path.Combine(scratch,"sessions.sqlite")};Default Timeout=1").AddInterceptors(blocker));
    using var provider=services.BuildServiceProvider();
    using(var scope=provider.CreateScope()) {var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Database.EnsureCreatedAsync();await db.Database.ExecuteSqlRawAsync("PRAGMA journal_mode=WAL;");}
    var store=new DatabaseTicketStore(provider.GetRequiredService<IServiceScopeFactory>());
    AuthenticationTicket Ticket()=>new(new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier,"fixture")],"test")),new AuthenticationProperties{IssuedUtc=DateTimeOffset.UtcNow,ExpiresUtc=DateTimeOffset.UtcNow.AddHours(8)},IdentityConstants.ApplicationScheme);
    var key=await store.StoreAsync(Ticket());
    blocker.Arm("UPDATE \"Sessions\"");var renew=store.RenewAsync(key,Ticket());await blocker.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
    await store.RemoveAsync(key);blocker.Resume.TrySetResult();await renew;
    Check(await store.RetrieveAsync(key)==null,"Revocation racing with renewal stays revoked without exceptions");
    key=await store.StoreAsync(Ticket());
    var older=Ticket();older.Principal.Identities.First().AddClaim(new("probe-version","older"));
    var newer=Ticket();newer.Principal.Identities.First().AddClaim(new("probe-version","newer"));
    blocker.Arm("UPDATE \"Sessions\"");var staleRenew=store.RenewAsync(key,older);await blocker.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
    await store.RenewAsync(key,newer);blocker.Resume.TrySetResult();await staleRenew;
    Check((await store.RetrieveAsync(key))?.Principal.FindFirstValue("probe-version")=="newer","Delayed older renewal cannot overwrite a newer ticket");
    using(var aborted=new CancellationTokenSource()){aborted.Cancel();await Throws<OperationCanceledException>(()=>store.RetrieveAsync(key,new DefaultHttpContext(),aborted.Token),"Cookie-store context overload honors cancellation");}

    key=await store.StoreAsync(Ticket());
    using(var scope=provider.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddMinutes(-1)));}
    var retrievals=await Task.WhenAll(Enumerable.Range(0,20).Select(_=>store.RetrieveAsync(key)));
    Check(retrievals.All(t=>t==null),"Parallel retrievals of a real expired ticket return null");
    await store.RenewAsync(key,Ticket());Check(await store.RetrieveAsync(key)==null,"Renewal cannot recreate an expired ticket");

    key=await store.StoreAsync(Ticket());
    using(var scope=provider.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddMinutes(-1)));}
    blocker.Arm("DELETE FROM \"Sessions\"");var retrieving=store.RetrieveAsync(key);await blocker.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
    using(var scope=provider.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddHours(1)));}
    blocker.Resume.TrySetResult();await retrieving;Check(await store.RetrieveAsync(key)!=null,"Expired-ticket cleanup rechecks persisted expiry");

    // Persistent sign-in: a session slides forward while used, but never past the absolute cap.
    AuthenticationTicket Persistent(){var ticket=Ticket();ticket.Properties.ExpiresUtc=DateTimeOffset.UtcNow+SessionPolicy.Sliding;return ticket;}
    key=await store.StoreAsync(Persistent());
    using(var scope=provider.CreateScope()){var row=await scope.ServiceProvider.GetRequiredService<GameDatabase>().Sessions.SingleAsync(s=>s.Id==Secrets.Digest(key));Check(row.ExpiresUtc>DateTime.UtcNow.AddDays(29)&&row.ExpiresUtc<=DateTime.UtcNow.AddDays(31),"A new persistent session lasts the sliding period");}
    using(var scope=provider.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.CreatedUtc,DateTime.UtcNow-SessionPolicy.Absolute+TimeSpan.FromDays(1)).SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddDays(2)));}
    await store.RenewAsync(key,Persistent());
    using(var scope=provider.CreateScope()){var row=await scope.ServiceProvider.GetRequiredService<GameDatabase>().Sessions.SingleAsync(s=>s.Id==Secrets.Digest(key));Check(row.ExpiresUtc<=row.CreatedUtc+SessionPolicy.Absolute&&row.ExpiresUtc>DateTime.UtcNow,"Session renewal preserves the absolute 180-day limit");}
    key=await store.StoreAsync(Persistent());
    using(var scope=provider.CreateScope()){var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.Where(s=>s.Id==Secrets.Digest(key)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.CreatedUtc,DateTime.UtcNow-SessionPolicy.Absolute-TimeSpan.FromDays(1)));}
    Check(await store.RetrieveAsync(key)==null,"A session older than the absolute limit is refused although its expiry is still in the future");

    // Abandoned sessions are removed by the periodic purge, not only when their own cookie returns.
    using(var scope=provider.CreateScope()) {
        var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();await db.Sessions.ExecuteDeleteAsync();
        var keepKey=await store.StoreAsync(Persistent());var staleKey=await store.StoreAsync(Persistent());var oldKey=await store.StoreAsync(Persistent());
        await db.Sessions.Where(s=>s.Id==Secrets.Digest(staleKey)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.ExpiresUtc,DateTime.UtcNow.AddDays(-10)));
        await db.Sessions.Where(s=>s.Id==Secrets.Digest(oldKey)).ExecuteUpdateAsync(u=>u.SetProperty(s=>s.CreatedUtc,DateTime.UtcNow-SessionPolicy.Absolute-TimeSpan.FromDays(1)));
        db.Leases.Add(new(){UserId="old-lease",Token="t",SessionId="s",ExpiresUtc=DateTime.UtcNow.AddDays(-3)});db.Leases.Add(new(){UserId="live-lease",Token="t",SessionId="s",ExpiresUtc=DateTime.UtcNow.AddSeconds(60)});await db.SaveChangesAsync();
        var removed=await DatabaseTicketStore.PurgeAsync(db);
        Check(removed==2&&await store.RetrieveAsync(keepKey)!=null&&await db.Sessions.CountAsync()==1,"Purge removes expired and over-age sessions and keeps live ones");
        Check(await db.Leases.AnyAsync(l=>l.UserId=="live-lease")&&!await db.Leases.AnyAsync(l=>l.UserId=="old-lease"),"Purge removes long-expired play leases only");
    }

    var busyContext=new DefaultHttpContext();var busy=await StateWrites.Run(busyContext,NullLogger.Instance,()=>throw new SqliteException("busy",5));
    Check(busy is IStatusCodeHttpResult{StatusCode:503}&&busyContext.Response.Headers.RetryAfter=="1","SQLite contention returns retryable 503");
    await Throws<DbUpdateException>(()=>StateWrites.Run(new DefaultHttpContext(),NullLogger.Instance,()=>throw new DbUpdateException("full",new SqliteException("full",13))),"Disk-full is not mislabeled as a revision conflict");
    using(var one=provider.CreateScope())using(var two=provider.CreateScope()) {
        await using var tx=await one.ServiceProvider.GetRequiredService<GameDatabase>().Database.BeginTransactionAsync();
        await Throws<SqliteException>(async()=>{await using var second=await two.ServiceProvider.GetRequiredService<GameDatabase>().Database.BeginTransactionAsync();},"SQLite serializes initial-save writer transactions");
    }

    async Task<(string Scheme,string? Ip)> Forward(string[] trust,string remote) {
        var o=new ForwardedHeadersOptions();ProxyTrust.Configure(o,trust);
        var h=new DefaultHttpContext();h.Connection.RemoteIpAddress=IPAddress.Parse(remote);h.Request.Scheme="http";h.Request.Headers["X-Forwarded-For"]="198.51.100.99";h.Request.Headers["X-Forwarded-Proto"]="https";
        await new ForwardedHeadersMiddleware(_=>Task.CompletedTask,NullLoggerFactory.Instance,Options.Create(o)).Invoke(h);return(h.Request.Scheme,h.Connection.RemoteIpAddress?.ToString());
    }
    Check((await Forward([],"203.0.113.9")).Scheme=="http","Empty proxy configuration ignores forwarded headers");
    Check((await Forward(["172.18.0.0/16"],"::ffff:172.18.0.9")).Scheme=="https","IPv4 CIDR accepts its IPv4-mapped IPv6 proxy");
    Check((await Forward(["172.18.0.0/16"],"203.0.113.9")).Scheme=="http","Unknown proxy cannot spoof HTTPS or client address");
    Check((await Forward(["127.0.0.1"],"::1")).Scheme=="http"&&(await Forward(["127.0.0.1","::1"],"::1")).Scheme=="https","IPv4 and IPv6 loopback trust is explicit");
    foreach(var entry in new[]{"10.0.0.0/33","invalid","0.0.0.0/0","::/0"})await Throws<InvalidOperationException>(()=>{ProxyTrust.Configure(new(),[entry]);return Task.CompletedTask;},"Reject invalid or universal proxy trust: "+entry);

    SaveValidation.Xml("<xftlSaveGame><ship><name>A &amp; B</name></ship></xftlSaveGame>","xftlSaveGame");Check(true,"Ordinary game XML and predefined entities remain valid");
    foreach(var xml in new[]{"<!DOCTYPE xftlSaveGame [<!ENTITY x SYSTEM 'http://127.0.0.1:1/'>]><xftlSaveGame>&x;</xftlSaveGame>","<!DOCTYPE xftlSaveGame [<!ENTITY a 'x'><!ENTITY b '&a;&a;&a;'>]><xftlSaveGame>&b;</xftlSaveGame>"})await Throws<System.Xml.XmlException>(()=>{SaveValidation.Xml(xml,"xftlSaveGame");return Task.CompletedTask;},"DTD external/expanded entities are rejected");
    await Throws<InvalidDataException>(()=>{SaveValidation.Xml("<xftlSaveGame>"+string.Concat(Enumerable.Repeat("<a>",70))+string.Concat(Enumerable.Repeat("</a>",70))+"</xftlSaveGame>","xftlSaveGame");return Task.CompletedTask;},"Excessive XML nesting is rejected");
    await Throws<InvalidDataException>(()=>{SaveValidation.Xml("<xftlSaveGame xmlns='urn:wrong'/>","xftlSaveGame");return Task.CompletedTask;},"Unexpected root namespaces are rejected");

    async Task<string> Session(DebugLogs logs,string owner="owner") {var s=JsonSerializer.SerializeToElement(await logs.Create(owner,new DebugStart("build","device")));return s.GetProperty("Id").GetString()!;}
    async Task Record(DebugLogs logs,string id,long sequence,string text="record")=>_ = await logs.Ingest("owner",id,new([JsonSerializer.SerializeToElement(new{sequence,eventName="probe",payload=new{text}})]));
    await using(var logs=new DebugLogs(Path.Combine(scratch,"export"))) {
        var id=await Session(logs);await Record(logs,id,1);
        using var body=new PausingStream();var h=new DefaultHttpContext();h.Response.Body=body;
        var export=logs.Export("owner",id,h.Response,CancellationToken.None);await body.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Check(!await logs.DeleteAccount("owner"),"Account deletion waits for pinned exports before completing");
        var usage=JsonSerializer.SerializeToElement(await logs.List("owner"));Check(usage.GetProperty("usedBytes").GetInt64()>0,"Deleted export chunks stay charged to quota until release");
        var other=await Session(logs);await Record(logs,other,1);Check(true,"Paused export does not block ingestion or session creation");
        await logs.Server("owner",other,"probe",new{password="private",nested=new{authorization="Bearer private"}});
        var end=DateTime.UtcNow.AddSeconds(5);JsonElement serverRecord=default;
        while(DateTime.UtcNow<end) {
            var rows=JsonSerializer.SerializeToElement(await logs.Records("owner",other,0,null,null,null,null,null,false));
            serverRecord=rows.GetProperty("records").EnumerateArray().Select(r=>r.GetProperty("record")).FirstOrDefault(r=>r.GetProperty("source").GetString()=="server");
            if(serverRecord.ValueKind==JsonValueKind.Object)break;await Task.Delay(10);
        }
        Check(serverRecord.ValueKind==JsonValueKind.Object&&serverRecord.GetProperty("payload").GetProperty("password").GetString()=="[redacted]"&&serverRecord.GetProperty("payload").GetProperty("nested").GetProperty("authorization").GetString()=="[redacted]","Server diagnostic payloads redact nested credentials");
        body.Resume.TrySetResult();Check(await export,"Export completes after concurrent deletion");body.Position=0;
        using(var zip=new ZipArchive(body,ZipArchiveMode.Read,true))Check(zip.Entries.Count(e=>e.FullName.EndsWith(".gz"))==1,"Export retains the complete pinned chunk snapshot");
        Check(Directory.GetFiles(Path.Combine(scratch,"export","debug-logs"),id+"-*.gz").Length==0,"Deferred chunks are removed after export");

        using var first=new PausingStream();using var second=new PausingStream();var c1=new DefaultHttpContext();c1.Response.Body=first;var c2=new DefaultHttpContext();c2.Response.Body=second;
        using var cancel=new CancellationTokenSource();var e1=logs.Export("owner",other,c1.Response,cancel.Token);var e2=logs.Export("owner",other,c2.Response,cancel.Token);
        await Task.WhenAll(first.Started.Task,second.Started.Task).WaitAsync(TimeSpan.FromSeconds(5));
        await Throws<DiagnosticExportBusyException>(()=>logs.Export("owner",other,new DefaultHttpContext().Response,CancellationToken.None),"Global export concurrency is bounded");
        cancel.Cancel();await Throws<OperationCanceledException>(async()=>await e1,"Cancelled export releases resources");await Throws<OperationCanceledException>(async()=>await e2,"Second cancelled export releases resources");
        using var final=new MemoryStream();var finalContext=new DefaultHttpContext();finalContext.Response.Body=final;Check(await logs.Export("owner",other,finalContext.Response,CancellationToken.None),"Export slots are reusable after cancellation");
    }
    await using(var logs=new DebugLogs(Path.Combine(scratch,"delete-failure"))) {
        var id=await Session(logs);await Record(logs,id,1);
        var metadata=Path.Combine(scratch,"delete-failure","debug-logs",id+".json");
        var retainedMetadata=await File.ReadAllTextAsync(metadata);File.Delete(metadata);Directory.CreateDirectory(metadata);
        await Throws<UnauthorizedAccessException>(()=>logs.DeleteAccount("owner"),"Filesystem cleanup failure does not report account deletion complete");
        Directory.Delete(metadata);await File.WriteAllTextAsync(metadata,retainedMetadata);
        Check(await logs.DeleteAccount("owner"),"Account cleanup retries successfully after storage recovers");
    }
    Check(AccountRoles.Canonical(["ExternalUser"])=="external-user"&&AccountRoles.Canonical(["Admin","User"])==null&&AccountRoles.Canonical(["Owner"])==null,"Unknown and conflicting application roles fail closed");
    await using(var logs=new DebugLogs(Path.Combine(scratch,"eviction"),instanceLimit:1024*1024,segmentLimit:524288)) {
        var id=await Session(logs);var random=new Random(17);var bytes=new byte[200000];random.NextBytes(bytes);var text=Convert.ToBase64String(bytes);
        await Record(logs,id,1,text);await logs.Finish("owner",id);
        using var body=new PausingStream();var h=new DefaultHttpContext();h.Response.Body=body;var export=logs.Export("owner",id,h.Response,CancellationToken.None);await body.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        for(var i=0;i<4;i++){var next=await Session(logs);await Record(logs,next,1,text);}
        var listing=JsonSerializer.SerializeToElement(await logs.List("owner"));
        Check(listing.GetProperty("usedBytes").GetInt64()<=1024*1024&&!listing.GetProperty("sessions").EnumerateArray().Any(s=>s.GetProperty("Id").GetString()==id),"Quota eviction keeps pinned bytes accounted and removes session visibility");
        body.Resume.TrySetResult();await export;body.Position=0;using var zip=new ZipArchive(body,ZipArchiveMode.Read);
        Check(zip.Entries.Count(e=>e.FullName.EndsWith(".gz"))==1,"Quota eviction cannot truncate a concurrent export");
    }
    await using(var logs=new DebugLogs(Path.Combine(scratch,"limited"),checkpointScanLimit:1)) {
        var id=await Session(logs);await Record(logs,id,1,new string('a',10000));using var body=new MemoryStream();var h=new DefaultHttpContext();h.Response.Body=body;await logs.Export("owner",id,h.Response,CancellationToken.None);body.Position=0;using var zip=new ZipArchive(body,ZipArchiveMode.Read);
        Check(zip.GetEntry("export-status.json")!=null&&zip.Entries.Any(e=>e.FullName.EndsWith(".gz")),"Checkpoint scan limit preserves raw chunks and reports partial reconstruction");
    }
    var assetRoot=Path.Combine(scratch,"assets");Directory.CreateDirectory(assetRoot);File.WriteAllText(Path.Combine(assetRoot,"boot.pack"),"fixture");
    File.WriteAllText(Path.Combine(assetRoot,"manifest.json"),JsonSerializer.Serialize(new{assetVersion="unchanged",engineBuild="test",audio=Array.Empty<object>()}));
    var firstAssets=new AssetCatalog(assetRoot);
    Check(firstAssets.BootHash==Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes("fixture"))).ToLowerInvariant(),"Boot hash identifies the actual uncompressed bundle");
    File.WriteAllText(Path.Combine(assetRoot,"boot.pack"),"changed engine resource");
    var changedAssets=new AssetCatalog(assetRoot);
    Check(firstAssets.Version==changedAssets.Version&&firstAssets.BootHash!=changedAssets.BootHash,"Boot changes invalidate the cache without changing DAT/save compatibility");
    foreach(var hash in new[]{"../escape","%2fescape",new string('a',64)+":stream","bad\0name"}) {
        File.WriteAllText(Path.Combine(assetRoot,"manifest.json"),JsonSerializer.Serialize(new{assetVersion="test",engineBuild="test",audio=new[]{new{hash,extension=".ogg"}}}));
        await Throws<InvalidDataException>(()=>{_ = new AssetCatalog(assetRoot);return Task.CompletedTask;},"Audio manifest rejects unsafe path component");
    }
    Console.WriteLine($"{checks} server security checks passed");
} finally {
    SqliteConnection.ClearAllPools();
    if(Path.GetDirectoryName(Path.GetFullPath(scratch))!=Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar)||!Path.GetFileName(scratch).StartsWith("ftl-security-",StringComparison.Ordinal))throw new Exception("Unsafe fixture cleanup path");
    Directory.Delete(scratch,true);
}

sealed class CommandBlocker:DbCommandInterceptor {
    string? match;
    public TaskCompletionSource Started=new(TaskCreationOptions.RunContinuationsAsynchronously),Resume=new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void Arm(string text){match=text;Started=new(TaskCreationOptions.RunContinuationsAsynchronously);Resume=new(TaskCreationOptions.RunContinuationsAsynchronously);}
    public override async ValueTask<InterceptionResult<int>> NonQueryExecutingAsync(DbCommand command,CommandEventData e,InterceptionResult<int> result,CancellationToken token=default) {
        if(match!=null&&command.CommandText.StartsWith(match,StringComparison.Ordinal)){match=null;Started.TrySetResult();await Resume.Task.WaitAsync(token);}return result;
    }
}
sealed class PausingStream:MemoryStream {
    int writes;
    public TaskCompletionSource Started=new(TaskCreationOptions.RunContinuationsAsynchronously),Resume=new(TaskCreationOptions.RunContinuationsAsynchronously);
    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,CancellationToken token=default){if(Interlocked.Increment(ref writes)==1){Started.TrySetResult();await Resume.Task.WaitAsync(token);}await base.WriteAsync(buffer,token);}
    public override Task WriteAsync(byte[] buffer,int offset,int count,CancellationToken token)=>WriteAsync(buffer.AsMemory(offset,count),token).AsTask();
}
