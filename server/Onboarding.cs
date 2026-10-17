using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;

// Username and password rules shared by admin-created accounts and public registration.
public static partial class AccountRules {
    [GeneratedRegex(@"^[a-zA-Z0-9_-]{3,32}\z", RegexOptions.CultureInvariant)] private static partial Regex UsernamePattern();
    public const string UsernameHelp = "Use a username of 3–32 letters, digits, underscores or hyphens";
    public const string PasswordHelp = "Use 15 to 128 characters";
    public static bool ValidUsername(string? value) => value != null && UsernamePattern().IsMatch(value);
    public static bool ValidPasswordLength(string? value) => value != null && value.Length is >= 15 and <= 128;
}

// The disclaimer consent and the cloud/local choice live in long-lived cookies so Razor can render the right
// first screen on the server. They are written by the server (a Set-Cookie header keeps its full lifetime in
// Safari, which caps cookies written through document.cookie at seven days) and are not secrets.
public sealed class OnboardingCookies(string consentName, string modeName, bool insecure) {
    public const string ConsentValue = "1";
    public static readonly TimeSpan Lifetime = TimeSpan.FromDays(400); // The longest lifetime Chromium honours.
    public bool Consented(HttpRequest request) => request.Cookies[consentName] == ConsentValue;
    public string? Mode(HttpRequest request) => request.Cookies[modeName] is "cloud" or "local" ? request.Cookies[modeName] : null;
    CookieOptions Options() => new() { HttpOnly = true, Secure = !insecure, SameSite = SameSiteMode.Lax, Path = "/", IsEssential = true, MaxAge = Lifetime, Expires = DateTimeOffset.UtcNow + Lifetime };
    public void SetConsent(HttpResponse response) => response.Cookies.Append(consentName, ConsentValue, Options());
    public void SetMode(HttpResponse response, string mode) => response.Cookies.Append(modeName, mode, Options());
}

// Public registration is open unless an administrator closes it (Users panel) or FTL_PUBLIC_REGISTRATION=false pins it.
public sealed class RegistrationPolicy(IConfiguration config) {
    public const string Key = "registration";
    public bool? Forced => bool.TryParse(config["FTL_PUBLIC_REGISTRATION"], out var value) ? value : null;
    public int HourlyLimit => int.TryParse(config["FTL_REGISTRATION_HOURLY_LIMIT"], out var n) && n > 0 ? n : 60;
    public async Task<bool> OpenAsync(GameDatabase db) => Forced ?? (await db.Settings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == Key))?.Value != "closed";
}

public static class Onboarding {
    public static void Map(WebApplication app) {
        app.MapPost("/api/onboarding/consent", (HttpContext h, OnboardingCookies cookies) => {
            cookies.SetConsent(h.Response);
            // Signed-in accounts are cloud accounts, whatever this browser chose earlier.
            if (h.User.Identity?.IsAuthenticated == true) cookies.SetMode(h.Response, "cloud");
            return Results.Ok(new { consent = true });
        }).RequireRateLimiting("public");
        app.MapPost("/api/onboarding/mode", (OnboardingMode body, HttpContext h, OnboardingCookies cookies) => {
            if (!cookies.Consented(h.Request)) return Results.BadRequest(new { error = "Agree to the notice first" });
            if (body.Mode is not ("cloud" or "local")) return Results.BadRequest(new { error = "Choose cloud saves or local only" });
            cookies.SetMode(h.Response, body.Mode);
            return Results.Ok(new { mode = body.Mode });
        }).RequireRateLimiting("public");
        // Local-only visitors have no account. They receive exactly what an external user receives: engine metadata
        // and the engine-authored resource bundle. Server game assets, saves and every other API stay behind sign-in.
        var local = app.MapGroup("/api/public").RequireRateLimiting("public");
        local.MapGet("/game/config", (AssetCatalog a, ClientAssets client, EngineResources resources) => Results.Ok(new {
            accountId = (string?)null, assetSource = "local", a.EngineBuild, assetVersion = a.Version, clientVersion = client.Version,
            datBytes = a.Manifest.RootElement.TryGetProperty("datBytes", out var size) ? size.GetInt64() : (long?)null,
            resourcesHash = resources.Hash, resourcesBytes = resources.Bytes, resourcesUrl = "/api/public/game/resources"
        }));
        local.MapGet("/game/resources", (EngineResources resources) => resources.Bytes == 0 ? Results.StatusCode(503) : Results.File(resources.Path, "application/octet-stream"));
    }
}
public record OnboardingMode(string? Mode);
