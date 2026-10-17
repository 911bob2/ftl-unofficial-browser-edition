using Microsoft.AspNetCore.Authorization;

namespace Ftl.Server;
public static class AccountRoles {
    public static readonly string[] Names = ["Admin", "User", "ExternalUser"];
    public static string? Parse(string? value) => value switch { "admin" => "Admin", "user" => "User", "external-user" => "ExternalUser", _ => null };
    public static string? Canonical(IEnumerable<string> roles) {
        var list = roles.ToArray();
        return list.Length == 1 ? list[0] switch { "Admin" => "admin", "User" => "user", "ExternalUser" => "external-user", _ => null } : null;
    }
    public static string? Role(HttpContext h) => h.Items["AccountRole"] as string;
    public static bool CanPlay(HttpContext h) => h.User.Identity?.IsAuthenticated == true && Role(h) != null && h.Items["Player"] is Player { Disabled: false, MustChangePassword: false };
    public static bool Admin(HttpContext h) => CanPlay(h) && Role(h) == "admin";
    public static object Capabilities(HttpContext h) => new { play = CanPlay(h), serverAssets = CanPlay(h) && Role(h) is "admin" or "user", manageUsers = Admin(h), serverTests = Admin(h) };
    public static void Configure(AuthorizationOptions options) {
        options.AddPolicy("Play", p => p.RequireAuthenticatedUser().RequireRole(Names).RequireAssertion(c => c.Resource is HttpContext h && CanPlay(h)));
        options.AddPolicy("ServerAssets", p => p.RequireAuthenticatedUser().RequireRole("Admin", "User").RequireAssertion(c => c.Resource is HttpContext h && CanPlay(h) && Role(h) is "admin" or "user"));
        options.AddPolicy("ManageUsers", p => p.RequireAuthenticatedUser().RequireRole("Admin").RequireAssertion(c => c.Resource is HttpContext h && Admin(h)));
        options.AddPolicy("ServerTests", p => p.RequireAuthenticatedUser().RequireRole("Admin").RequireAssertion(c => c.Resource is HttpContext h && Admin(h)));
    }
}
