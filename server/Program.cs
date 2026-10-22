using System.Net;
using System.Security.Claims;
using System.Text.Json;
using System.Threading.RateLimiting;
using System.Xml;
using Ftl.Server;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Data.Sqlite;

if(args.FirstOrDefault()=="healthcheck") {
    try {using var client=new HttpClient{Timeout=TimeSpan.FromSeconds(3)};var response=await client.GetAsync("http://127.0.0.1:8080/health/ready");Environment.Exit(response.IsSuccessStatusCode?0:1);}catch{Environment.Exit(1);}return;
}
var builder=WebApplication.CreateBuilder(args);
var dataDir=Path.GetFullPath(builder.Configuration["FTL_DATA_DIR"]??"/data");Directory.CreateDirectory(dataDir);
var local=builder.Environment.IsDevelopment();
var publicOrigin=builder.Configuration["PUBLIC_ORIGIN"]??(local?"http://localhost:8080":throw new InvalidOperationException("PUBLIC_ORIGIN is required"));
var origin=new Uri(publicOrigin);if(!local&&origin.Scheme!="https")throw new InvalidOperationException("Production requires an HTTPS PUBLIC_ORIGIN");
// Cookies are host-scoped; a local fixture must not overwrite the real app's cookies.
var authCookie=local?$"ftl-local-{origin.Port}":"__Host-ftl";
var csrfCookie=local?$"ftl-csrf-local-{origin.Port}":"__Host-ftl-csrf";
var consentCookie=local?$"ftl-consent-local-{origin.Port}":"__Host-ftl-consent";
var modeCookie=local?$"ftl-mode-local-{origin.Port}":"__Host-ftl-mode";
var proxies=(builder.Configuration["TRUSTED_PROXIES"]??"").Split(',',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries);
if(!local&&proxies.Length==0)throw new InvalidOperationException("Production requires explicit TRUSTED_PROXIES");
var proxyOptions=new ForwardedHeadersOptions();ProxyTrust.Configure(proxyOptions,proxies);
builder.Services.Configure<ForwardedHeadersOptions>(o=>ProxyTrust.Configure(o,proxies));
builder.WebHost.ConfigureKestrel(o=>o.Limits.MaxRequestBodySize=20*1024*1024);
builder.Services.AddDbContext<GameDatabase>(o=>o.UseSqlite($"Data Source={Path.Combine(dataDir,"ftl.sqlite")};Default Timeout=15"));
builder.Services.AddDataProtection().SetApplicationName("FtlBrowserNative").PersistKeysToFileSystem(new DirectoryInfo(Path.Combine(dataDir,"keys")));
builder.Services.AddIdentity<Player,IdentityRole>(o=>{o.Password.RequiredLength=15;o.Password.RequiredUniqueChars=1;o.Password.RequireDigit=false;o.Password.RequireLowercase=false;o.Password.RequireUppercase=false;o.Password.RequireNonAlphanumeric=false;o.Lockout.MaxFailedAccessAttempts=5;o.Lockout.DefaultLockoutTimeSpan=TimeSpan.FromMinutes(15);o.User.RequireUniqueEmail=false;}).AddEntityFrameworkStores<GameDatabase>().AddDefaultTokenProviders();
builder.Services.Configure<PasswordHasherOptions>(o=>o.IterationCount=220000);
builder.Services.AddSingleton<DatabaseTicketStore>();builder.Services.AddScoped<CookieValidation>();
builder.Services.AddSingleton(new OnboardingCookies(consentCookie,modeCookie,local));builder.Services.AddSingleton<RegistrationPolicy>();
builder.Services.AddOptions<CookieAuthenticationOptions>(IdentityConstants.ApplicationScheme).Configure<DatabaseTicketStore>((o,store)=>{o.SessionStore=store;o.Cookie.Name=authCookie;o.Cookie.HttpOnly=true;o.Cookie.SecurePolicy=local?CookieSecurePolicy.None:CookieSecurePolicy.Always;o.Cookie.SameSite=SameSiteMode.Strict;o.Cookie.Path="/";o.ExpireTimeSpan=SessionPolicy.Sliding;o.SlidingExpiration=true;o.EventsType=typeof(CookieValidation);});
builder.Services.AddAntiforgery(o=>{o.HeaderName="X-CSRF-TOKEN";o.Cookie.Name=csrfCookie;o.Cookie.SecurePolicy=local?CookieSecurePolicy.None:CookieSecurePolicy.Always;o.Cookie.SameSite=SameSiteMode.Strict;});
builder.Services.AddAuthorization(AccountRoles.Configure);
builder.Services.AddRateLimiter(o=>{
    o.RejectionStatusCode=429;
    // Sign-in attempts per address in a five-minute window; only automated test fixtures raise it (FTL_LOGIN_LIMIT).
    var loginPermits=int.TryParse(builder.Configuration["FTL_LOGIN_LIMIT"],out var loginLimit)&&loginLimit>0?loginLimit:20;
    o.AddPolicy("login",h=>RateLimitPartition.GetSlidingWindowLimiter(h.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new(){PermitLimit=loginPermits,Window=TimeSpan.FromMinutes(5),SegmentsPerWindow=5,QueueLimit=0}));
    // Registration is stricter than sign-in: new accounts per address in a ten-minute window.
    var registerPermits=int.TryParse(builder.Configuration["FTL_REGISTER_LIMIT"],out var registerLimit)&&registerLimit>0?registerLimit:5;
    o.AddPolicy("register",h=>RateLimitPartition.GetSlidingWindowLimiter(h.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new(){PermitLimit=registerPermits,Window=TimeSpan.FromMinutes(10),SegmentsPerWindow=5,QueueLimit=0}));
    // Cheap public endpoints (consent, mode, engine metadata): one page load costs two or three of these, so a shared address can serve a classroom.
    var publicPermits=int.TryParse(builder.Configuration["FTL_PUBLIC_LIMIT"],out var publicLimit)&&publicLimit>0?publicLimit:240;
    o.AddPolicy("public",h=>RateLimitPartition.GetSlidingWindowLimiter(h.Connection.RemoteIpAddress?.ToString()??"unknown",_=>new(){PermitLimit=publicPermits,Window=TimeSpan.FromMinutes(1),SegmentsPerWindow=4,QueueLimit=0}));
    o.AddPolicy("tests",h=>RateLimitPartition.GetFixedWindowLimiter(h.User.FindFirstValue(ClaimTypes.NameIdentifier)??"anon",_=>new(){PermitLimit=5,Window=TimeSpan.FromMinutes(5),QueueLimit=0}));
    o.AddPolicy("debug-export",h=>RateLimitPartition.GetConcurrencyLimiter(h.User.FindFirstValue(ClaimTypes.NameIdentifier)??"anon",_=>new(){PermitLimit=1,QueueLimit=0}));
});
builder.Services.AddRazorPages();builder.Services.AddSingleton(new AssetCatalog(builder.Configuration["FTL_ASSETS_DIR"]??"/assets"));
builder.Services.AddSingleton<LauncherTests>();
builder.Services.AddSingleton<EngineResources>();
builder.Services.AddSingleton<AccountLifecycle>();
builder.Services.AddHostedService(p => p.GetRequiredService<AccountLifecycle>());
builder.Services.AddSingleton(new ClientAssets(builder.Environment.WebRootPath??Path.Combine(builder.Environment.ContentRootPath,"wwwroot")));
builder.Services.AddSingleton(new DebugLogs(dataDir, long.TryParse(builder.Configuration["FTL_DEBUG_TOTAL_BYTES"], out var debugLimit) ? debugLimit : 10L * DebugLogs.UserLimit,
    long.TryParse(builder.Configuration["FTL_DEBUG_SEGMENT_BYTES"], out var debugSegmentLimit) ? debugSegmentLimit : DebugLogs.SegmentLimit));
var app=builder.Build();await Bootstrap.Initialize(app.Services,dataDir);

if(args.FirstOrDefault()=="reset-game-progress") {
    using var scope=app.Services.CreateScope();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();
    await using var tx=await db.Database.BeginTransactionAsync();
    await db.Saves.ExecuteDeleteAsync();await db.Snapshots.ExecuteDeleteAsync();await db.Leases.ExecuteDeleteAsync();
    db.Audits.Add(new(){Action="game-progress-reset"});await db.SaveChangesAsync();await tx.CommitAsync();
    Console.WriteLine("Game progress reset; accounts and identity settings retained.");return;
}

if(args.FirstOrDefault()=="create-accounts") {
    // create-accounts <name:role> ...  Offline provisioning for disposable fixtures: each account gets a temporary password
    // (changed at first sign-in) appended to bootstrap-credentials.txt. Roles: admin, user, external-user.
    using var scope=app.Services.CreateScope();var users=scope.ServiceProvider.GetRequiredService<UserManager<Player>>();
    var file=Path.Combine(dataDir,"bootstrap-credentials.txt");var lines=new List<string>();
    foreach(var spec in args.Skip(1)) {
        var parts=spec.Split(':');var role=AccountRoles.Parse(parts.ElementAtOrDefault(1)??"user");
        if(!AccountRules.ValidUsername(parts[0])||role==null)throw new ArgumentException("Expected name:role with role admin, user or external-user: "+spec);
        if(await users.FindByNameAsync(parts[0])!=null)continue;
        var password=Secrets.Random(12);var created=new Player{UserName=parts[0]};Bootstrap.Check(await users.CreateAsync(created,password));Bootstrap.Check(await users.AddToRoleAsync(created,role));lines.Add(parts[0]+"\t"+password);
    }
    await File.AppendAllLinesAsync(file,lines);Secrets.Restrict(file);Console.WriteLine($"Created {lines.Count} account(s); temporary passwords are in {file}");return;
}
if(args.FirstOrDefault()=="recover-admin") {
    using var scope=app.Services.CreateScope();var users=scope.ServiceProvider.GetRequiredService<UserManager<Player>>();var db=scope.ServiceProvider.GetRequiredService<GameDatabase>();
    var requested=args.ElementAtOrDefault(1);var candidates=requested==null?(await users.GetUsersInRoleAsync("Admin")).OrderBy(p=>p.UserName=="admin"?0:1).ThenBy(p=>p.Disabled).ToList():new List<Player>();
    if(requested!=null&&await users.FindByNameAsync(requested) is {} selected)candidates.Add(selected);
    Player? admin=null;foreach(var candidate in candidates)if(AccountRoles.Canonical(await users.GetRolesAsync(candidate))=="admin"&&await db.Settings.FindAsync(AccountLifecycle.DeletePrefix+candidate.Id)==null){admin=candidate;break;}
    if(admin==null)throw new InvalidOperationException("No recoverable administrator; check account roles and pending deletion locally");var password=Secrets.Random(12);
    admin.Disabled=false;admin.MustChangePassword=true;admin.LockoutEnd=null;admin.AccessFailedCount=0;Bootstrap.Check(await users.ResetPasswordAsync(admin,await users.GeneratePasswordResetTokenAsync(admin),password));await db.Sessions.Where(s=>s.UserId==admin.Id).ExecuteDeleteAsync();await db.Leases.Where(s=>s.UserId==admin.Id).ExecuteDeleteAsync();db.Audits.Add(new(){SubjectId=admin.Id,Action="admin-recovered"});await db.SaveChangesAsync();var file=Path.Combine(dataDir,"admin-recovery.txt");await File.WriteAllTextAsync(file,$"{admin.UserName}\t{password}\n");Secrets.Restrict(file);Console.WriteLine("Recovery credential written to /data/admin-recovery.txt");return;
}
if(args.FirstOrDefault()=="backup") {
    var dest=Path.Combine(dataDir,"backups",DateTime.UtcNow.ToString("yyyyMMdd-HHmmss"));Directory.CreateDirectory(dest);
    using var source=new SqliteConnection($"Data Source={Path.Combine(dataDir,"ftl.sqlite")}");using var target=new SqliteConnection($"Data Source={Path.Combine(dest,"ftl.sqlite")}");source.Open();target.Open();source.BackupDatabase(target);
    Directory.CreateDirectory(Path.Combine(dest,"keys"));foreach(var file in Directory.GetFiles(Path.Combine(dataDir,"keys")))File.Copy(file,Path.Combine(dest,"keys",Path.GetFileName(file)));Console.WriteLine($"Backup created: {dest}");return;
}
app.UseForwardedHeaders();
app.Use(async(h,next)=>{try{await next();}catch(Exception e){app.Logger.LogError(e,"Request failed: {Path}",h.Request.Path);if(h.Response.HasStarted)throw;h.Response.Clear();h.Response.StatusCode=500;await h.Response.WriteAsJsonAsync(new{error="The request could not be completed"});}});
app.Use(async(h,next)=>{
    h.Response.Headers["X-Content-Type-Options"]="nosniff";h.Response.Headers["Referrer-Policy"]="same-origin";h.Response.Headers["X-Frame-Options"]="DENY";
    h.Response.Headers["Content-Security-Policy"]="default-src 'self'; script-src 'self'; worker-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; media-src 'self' blob:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";
    h.Response.Headers["Permissions-Policy"]="camera=(), microphone=(), geolocation=()";
    if(!local)h.Response.Headers["Strict-Transport-Security"]="max-age=31536000";
    if(!local&&!h.Request.Path.StartsWithSegments("/health")&&!h.Request.IsHttps){h.Response.StatusCode=403;return;}
    if(!h.Request.Path.StartsWithSegments("/health")&&h.Request.Host.Value!=origin.Authority){h.Response.StatusCode=400;return;}
    h.Response.Headers.CacheControl="no-store";
    if(HttpMethods.IsPost(h.Request.Method)||HttpMethods.IsPut(h.Request.Method)||HttpMethods.IsDelete(h.Request.Method)||HttpMethods.IsPatch(h.Request.Method)){
        if(h.Request.Headers.Origin.Count>0&&h.Request.Headers.Origin.ToString()!=publicOrigin){h.Response.StatusCode=403;return;}
    }
    await next();
});
app.Use(async(h,next)=>{
    var mutation = h.Request.Path.StartsWithSegments("/api") && h.Request.Method is "POST" or "PUT" or "PATCH" or "DELETE";
    if(!mutation){await next();return;}
    // Receive the whole body before queueing: one slow or oversized upload must not hold the gate every account's writes share.
    if(!await RequestBodies.Receive(h))return;
    var lifecycle=h.RequestServices.GetRequiredService<AccountLifecycle>();
    await lifecycle.Writes.WaitAsync(h.RequestAborted);
    try{await next();}finally{lifecycle.Writes.Release();}
});
app.UseStaticFiles(new StaticFileOptions{OnPrepareResponse=c=>c.Context.Response.Headers.CacheControl="no-cache, max-age=0, must-revalidate"});app.UseAuthentication();
app.Use(async(h,next)=>{
    var expected=h.Request.Headers["X-FTL-Account"].ToString();
    if(expected.Length>0 && h.User.Identity?.IsAuthenticated==true && expected!=h.UserId()) {h.Response.StatusCode=409;await h.Response.WriteAsJsonAsync(new{error="The active account changed. Reload or sign in with the account that owns this page",code="account_changed"});return;}
    await next();
});
app.Use(async(h,next)=>{
    var session=h.Request.Headers["X-FTL-Debug-Session"].ToString();
    var started=System.Diagnostics.Stopwatch.GetTimestamp();
    try { await next(); }
    catch(Exception e) {
        if(h.User.Identity?.IsAuthenticated==true&&Guid.TryParse(session,out _)) await h.RequestServices.GetRequiredService<DebugLogs>().Server(h.UserId(),session,"request.exception",new{path=h.Request.Path.Value,type=e.GetType().Name},true);
        throw;
    }
    finally {
        if(h.User.Identity?.IsAuthenticated==true&&Guid.TryParse(session,out _)&&!h.Request.Path.StartsWithSegments("/api/debug")) await h.RequestServices.GetRequiredService<DebugLogs>().Server(h.UserId(),session,"request.completed",new{method=h.Request.Method,path=h.Request.Path.Value,status=h.Response.StatusCode,durationMs=System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds},h.Response.StatusCode>=500);
    }
});
app.Use(async(h,next)=>{
    if(HttpMethods.IsPost(h.Request.Method)||HttpMethods.IsPut(h.Request.Method)||HttpMethods.IsDelete(h.Request.Method)||HttpMethods.IsPatch(h.Request.Method)){
        try{await h.RequestServices.GetRequiredService<IAntiforgery>().ValidateRequestAsync(h);}catch(AntiforgeryValidationException){h.Response.StatusCode=400;await h.Response.WriteAsJsonAsync(new{error="Invalid CSRF token"});return;}
    }
    await next();
});
app.UseAuthorization();app.UseRateLimiter();app.MapRazorPages();
app.MapGet("/health/live",()=>Results.Ok(new{status="ok"}));
app.MapGet("/health/ready",async(GameDatabase db)=>await db.Database.CanConnectAsync()?Results.Ok(new{status="ready"}):Results.StatusCode(503));
app.MapGet("/api/auth/csrf",(HttpContext h,IAntiforgery a)=>Results.Ok(new{token=a.GetAndStoreTokens(h).RequestToken}));
Onboarding.Map(app);AccountSelf.Map(app,authCookie);
app.MapPost("/api/auth/login",async(LoginRequest body,HttpContext h,UserManager<Player> users,SignInManager<Player> signIn,GameDatabase db,OnboardingCookies onboarding)=>{
    if(h.Request.Cookies.TryGetValue(authCookie,out var oldCookie)&&!string.IsNullOrEmpty(oldCookie)){await signIn.SignOutAsync();return Results.Conflict(new{error="Your previous session was cleared. Please sign in again."});}
    if(string.IsNullOrWhiteSpace(body.Username)||body.Username.Length>64||body.Password==null||body.Password.Length>128)return Results.Unauthorized();
    var player=await users.FindByNameAsync(body.Username);if(player==null){new PasswordHasher<Player>().VerifyHashedPassword(new Player(),DummyPassword.Hash,body.Password);return Results.Unauthorized();}
    var result=await signIn.CheckPasswordSignInAsync(player,body.Password,true);
    if(!result.Succeeded||player.Disabled){db.Audits.Add(new(){Action="login-failed",SubjectId=player.Id});await db.SaveChangesAsync();return Results.Unauthorized();}
    await signIn.SignInAsync(player,true);onboarding.SetMode(h.Response,"cloud");db.Audits.Add(new(){ActorId=player.Id,SubjectId=player.Id,Action="login"});await db.SaveChangesAsync();return Results.Ok(new{mustChangePassword=player.MustChangePassword});
}).RequireRateLimiting("login");
app.MapGet("/api/auth/me",async(HttpContext h,UserManager<Player> users)=>{var p=(Player)h.Items["Player"]!;return Results.Ok(new{id=p.Id,username=p.UserName,admin=AccountRoles.Admin(h),role=AccountRoles.Role(h),capabilities=AccountRoles.Capabilities(h),mustChangePassword=p.MustChangePassword,canDeleteAccount=!await AccountAdmin.FinalAdministrator(p,users)});}).RequireAuthorization();
app.MapPost("/api/auth/logout",async(HttpContext h,SignInManager<Player> signIn,GameDatabase db)=>{await db.Leases.Where(l=>l.SessionId==h.SessionId()).ExecuteDeleteAsync();await signIn.SignOutAsync();return Results.NoContent();}).RequireAuthorization();
app.MapPost("/api/auth/password",async(PasswordRequest body,HttpContext h,UserManager<Player> users,SignInManager<Player> signIn,GameDatabase db)=>{
    if(body.CurrentPassword==null||body.CurrentPassword.Length>128||body.NewPassword==null||body.NewPassword.Length is <15 or >128)return Results.BadRequest(new{error="Use 15 to 128 characters"});
    var p=(Player)h.Items["Player"]!;var result=await users.ChangePasswordAsync(p,body.CurrentPassword,body.NewPassword);if(!result.Succeeded)return Results.BadRequest(new{error="The current password is incorrect or the new password is invalid"});
    p.MustChangePassword=false;Bootstrap.Check(await users.UpdateAsync(p));await db.Sessions.Where(s=>s.UserId==p.Id).ExecuteDeleteAsync();await db.Leases.Where(s=>s.UserId==p.Id).ExecuteDeleteAsync();await signIn.SignOutAsync();db.Audits.Add(new(){ActorId=p.Id,SubjectId=p.Id,Action="password-changed"});await db.SaveChangesAsync();return Results.NoContent();
}).RequireAuthorization().RequireRateLimiting("login");

var game=app.MapGroup("/api").RequireAuthorization("Play");
DebugLogs.Map(game);
var serverAssets=game.MapGroup("/assets").RequireAuthorization("ServerAssets");
game.MapGet("/game/config",(HttpContext h,AssetCatalog a,ClientAssets client,EngineResources resources)=>Results.Ok(new{
    accountId=h.UserId(),
    assetSource=AccountRoles.Role(h)=="external-user"?"local":"server",a.EngineBuild,assetVersion=a.Version,clientVersion=client.Version,
    datBytes=a.Manifest.RootElement.TryGetProperty("datBytes",out var size)?size.GetInt64():(long?)null,
    resourcesHash=resources.Hash,resourcesBytes=resources.Bytes,resourcesUrl="/api/game/resources"
}));
game.MapGet("/game/resources",(EngineResources resources)=>resources.Bytes==0?Results.StatusCode(503):Results.File(resources.Path,"application/octet-stream"));
serverAssets.MapGet("/manifest",(AssetCatalog a,ClientAssets client)=>{var result=System.Text.Json.Nodes.JsonNode.Parse(a.Manifest.RootElement.GetRawText())!;result["clientVersion"]=client.Version;result["bootHash"]=a.BootHash;return Results.Json(result);});
serverAssets.MapGet("/boot",(HttpContext h,AssetCatalog a)=>{var gzip=h.Request.Headers.AcceptEncoding.ToString().Contains("gzip");if(gzip)h.Response.Headers.ContentEncoding="gzip";h.Response.Headers.CacheControl="private, no-store";return Results.File(Path.Combine(a.Directory,gzip?"boot.pack.gz":"boot.pack"),"application/octet-stream",enableRangeProcessing:!gzip);});
serverAssets.MapGet("/audio/{hash}",(string hash,HttpContext h,AssetCatalog a)=>{if(!a.Audio.TryGetValue(hash,out var path))return Results.NotFound();h.Response.Headers.CacheControl="private, no-store";return Results.File(path,path.EndsWith(".ogg")?"audio/ogg":"audio/wav",enableRangeProcessing:true);});
game.MapPost("/play-lease/acquire",async(LeaseRequest body,HttpContext h,GameDatabase db)=>{
    return await StateWrites.Run(h,app.Logger,async()=>{
    await using var tx=await db.Database.BeginTransactionAsync();
    var now=DateTime.UtcNow;var id=h.UserId();var lease=await db.Leases.FindAsync(id);
    var owns=lease!=null&&lease.SessionId==h.SessionId()&&lease.Token==body.Lease;
    if(lease!=null&&lease.ExpiresUtc>now&&!owns&&!body.TakeOver)return Results.Conflict(new{error="This account is playing in another tab or device"});
    if(lease==null){lease=new(){UserId=id};db.Leases.Add(lease);}
    if(!owns||body.TakeOver||lease.ExpiresUtc<=now)lease.Token=Secrets.Random();
    lease.SessionId=h.SessionId();lease.ExpiresUtc=now.AddSeconds(90);await db.SaveChangesAsync();await tx.CommitAsync();return Results.Ok(new{lease=lease.Token,expiresUtc=lease.ExpiresUtc});
    });
});
game.MapPost("/play-lease/heartbeat",async(HeartbeatRequest body,HttpContext h,GameDatabase db)=>{
    return await StateWrites.Run(h,app.Logger,async()=>{
    var now=DateTime.UtcNow;var changed=await db.Leases.Where(l=>l.UserId==h.UserId()&&l.Token==body.Lease&&l.SessionId==h.SessionId()&&l.ExpiresUtc>now).ExecuteUpdateAsync(s=>s.SetProperty(l=>l.ExpiresUtc,now.AddSeconds(90)));return changed==1?Results.NoContent():Results.Conflict(new{error="Play lease expired or taken over"});
    });
});
// Save and close, and moving a browser save into the account, hand the lease back so the account can launch again at once
// (another device would otherwise wait up to 90 seconds for it to expire).
game.MapPost("/play-lease/release",async(HeartbeatRequest body,HttpContext h,GameDatabase db)=>{
    return await StateWrites.Run(h,app.Logger,async()=>{
    await db.Leases.Where(l=>l.UserId==h.UserId()&&l.Token==body.Lease&&l.SessionId==h.SessionId()).ExecuteDeleteAsync();return Results.NoContent();
    });
});
game.MapGet("/saves/current",async(HttpContext h,GameDatabase db,AssetCatalog a)=>{var save=await db.Saves.FindAsync(h.UserId());h.Response.Headers.ETag=$"\"{save?.Revision??0}\"";return Results.Ok(save==null?new SaveEnvelope(1,a.EngineBuild,a.Version,0,null,null):new SaveEnvelope(1,save.EngineBuild,save.AssetVersion,save.Revision,save.Run,save.Profile));});
game.MapPut("/saves/current",async(SaveRequest body,HttpContext h,GameDatabase db,AssetCatalog a)=>{
    if(body.State==null||body.State.Schema!=1||body.State.EngineBuild!=a.EngineBuild||body.State.AssetVersion!=a.Version)return Results.BadRequest(new{error="Incompatible save format or assets"});
    try{SaveValidation.Xml(body.State.Run,"xftlSaveGame");SaveValidation.Xml(body.State.Profile,"xftl-profile");}catch(Exception e) when(e is XmlException or InvalidDataException){return Results.BadRequest(new{error="Invalid save XML"});}
    // Serialize outside SQLite's writer lock; the transaction verifies this revision.
    var snapshotPayload=JsonSerializer.Serialize(new SaveEnvelope(1,a.EngineBuild,a.Version,body.State.Revision+1,body.State.Run,body.State.Profile),new JsonSerializerOptions(JsonSerializerDefaults.Web));
    return await StateWrites.Run(h,app.Logger,async()=>{
    await using var tx=await db.Database.BeginTransactionAsync();var lease=await db.Leases.FindAsync(h.UserId());if(lease==null||lease.Token!=body.Lease||lease.SessionId!=h.SessionId()||lease.ExpiresUtc<=DateTime.UtcNow)return Results.Conflict(new{error="Play lease is no longer valid"});
    var state=await db.Saves.FindAsync(h.UserId());var revision=state?.Revision??0;
    if(h.Request.Headers.IfMatch.ToString()!=$"\"{revision}\""||body.State.Revision!=revision)return Results.Conflict(new{error="A newer save exists",revision});
    if(state==null){state=new(){UserId=h.UserId()};db.Saves.Add(state);}state.Revision=revision+1;state.Run=body.State.Run;state.Profile=body.State.Profile;state.EngineBuild=a.EngineBuild;state.AssetVersion=a.Version;state.UpdatedUtc=DateTime.UtcNow;
    db.Snapshots.Add(new(){UserId=state.UserId,Revision=state.Revision,Payload=snapshotPayload,CreatedUtc=DateTime.UtcNow,Kind=body.Kind is "auto" or "manual" or "import"?body.Kind:null});
    try{await db.SaveChangesAsync();await db.Snapshots.Where(s=>s.UserId==state.UserId&&s.Revision<=state.Revision-10).ExecuteDeleteAsync();await tx.CommitAsync();}catch(DbUpdateConcurrencyException){return Results.Conflict(new{error="A newer save exists"});}
    h.Response.Headers.ETag=$"\"{state.Revision}\"";return Results.Ok(new{revision=state.Revision});
    });
});
game.MapGet("/saves/snapshots",async(HttpContext h,GameDatabase db)=>Results.Ok(await db.Snapshots.Where(s=>s.UserId==h.UserId()).OrderByDescending(s=>s.Revision).Select(s=>new{s.Revision,s.CreatedUtc,s.Kind}).ToListAsync()));
// Restoring a snapshot makes it the current save as a new revision and deletes the newer snapshots: the progress after it is
// discarded rather than kept as a second branch of history.
game.MapPost("/saves/snapshots/{revision:long}/restore",async(long revision,HeartbeatRequest body,HttpContext h,GameDatabase db,AssetCatalog a)=>{
    return await StateWrites.Run(h,app.Logger,async()=>{
    await using var tx=await db.Database.BeginTransactionAsync();var lease=await db.Leases.FindAsync(h.UserId());if(lease==null||lease.Token!=body.Lease||lease.SessionId!=h.SessionId()||lease.ExpiresUtc<=DateTime.UtcNow)return Results.Conflict(new{error="Play lease is no longer valid"});
    var state=await db.Saves.FindAsync(h.UserId());var current=state?.Revision??0;
    if(h.Request.Headers.IfMatch.ToString()!=$"\"{current}\"")return Results.Conflict(new{error="A newer save exists",revision=current});
    var snapshot=await db.Snapshots.SingleOrDefaultAsync(s=>s.UserId==h.UserId()&&s.Revision==revision);if(snapshot==null||state==null)return Results.NotFound(new{error="That recovery snapshot no longer exists."});
    if(revision==current)return Results.BadRequest(new{error="That snapshot is already the current save."});
    var saved=JsonSerializer.Deserialize<SaveEnvelope>(snapshot.Payload,new JsonSerializerOptions(JsonSerializerDefaults.Web));
    if(saved==null||saved.EngineBuild!=a.EngineBuild||saved.AssetVersion!=a.Version)return Results.BadRequest(new{error="That snapshot was made with other game files or another engine version."});
    state.Revision=current+1;state.Run=saved.Run;state.Profile=saved.Profile;state.EngineBuild=a.EngineBuild;state.AssetVersion=a.Version;state.UpdatedUtc=DateTime.UtcNow;
    var payload=JsonSerializer.Serialize(new SaveEnvelope(1,a.EngineBuild,a.Version,state.Revision,saved.Run,saved.Profile),new JsonSerializerOptions(JsonSerializerDefaults.Web));
    await db.Snapshots.Where(s=>s.UserId==state.UserId&&s.Revision>revision).ExecuteDeleteAsync();
    db.Snapshots.Add(new(){UserId=state.UserId,Revision=state.Revision,Payload=payload,CreatedUtc=DateTime.UtcNow,Kind="restore"});
    try{await db.SaveChangesAsync();await db.Snapshots.Where(s=>s.UserId==state.UserId&&s.Revision<=state.Revision-10).ExecuteDeleteAsync();await tx.CommitAsync();}catch(DbUpdateConcurrencyException){return Results.Conflict(new{error="A newer save exists"});}
    h.Response.Headers.ETag=$"\"{state.Revision}\"";return Results.Ok(new{revision=state.Revision});
    });
});
game.MapGet("/saves/snapshots/{revision:long}",async(long revision,HttpContext h,GameDatabase db)=>{var s=await db.Snapshots.SingleOrDefaultAsync(s=>s.UserId==h.UserId()&&s.Revision==revision);return s==null?Results.NotFound():Results.Text(s.Payload,"application/json");});
var adminApi=app.MapGroup("/api/admin").RequireAuthorization("ManageUsers");
var serverTests=game.MapGroup("/tests").RequireAuthorization("ServerTests");
serverTests.MapGet("",(HttpContext h,LauncherTests tests)=>Results.Ok(new { available=tests.Available, suites=LauncherTests.Suites, runs=tests.List(h.UserId()) }));
serverTests.MapPost("",(LauncherTestRequest body,HttpContext h,LauncherTests tests)=> {
    try { return Results.Ok(new { id=tests.Start(h.UserId(),body.Suites) }); }
    catch(ArgumentException e) { return Results.BadRequest(new { error=e.Message }); }
    catch(InvalidOperationException e) { return Results.Conflict(new { error=e.Message }); }
    catch(NotSupportedException e) { return Results.Json(new { error=e.Message },statusCode:503); }
}).RequireRateLimiting("tests");
serverTests.MapGet("/{id}",(string id,HttpContext h,LauncherTests tests)=>tests.Get(h.UserId(),id) is {} run ? Results.Ok(run) : Results.NotFound());
serverTests.MapPost("/{id}/stop",(string id,HttpContext h,LauncherTests tests)=>tests.Cancel(h.UserId(),id) ? Results.NoContent() : Results.NotFound());
AccountAdmin.Map(adminApi);
await app.RunAsync();

record LoginRequest(string Username,string Password);
record PasswordRequest(string CurrentPassword,string NewPassword);
record LeaseRequest(bool TakeOver,string? Lease=null);
record HeartbeatRequest(string Lease);
record SaveEnvelope(int Schema,string EngineBuild,string AssetVersion,long Revision,string? Run,string? Profile);
record SaveRequest(string Lease,SaveEnvelope State,string? Kind=null);
static class DummyPassword { public static readonly string Hash=new PasswordHasher<Player>(Microsoft.Extensions.Options.Options.Create(new PasswordHasherOptions{IterationCount=220000})).HashPassword(new Player(),Secrets.Random()); }
