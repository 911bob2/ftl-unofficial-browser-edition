using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.WebUtilities;

namespace Ftl.Server;

// API writes are received completely, within a per-route size, before they queue for the shared write gate.
// Only saves and diagnostic batches carry large bodies; every other write is a small JSON document.
public static class RequestBodies {
    public const long SaveLimit = 20L * 1024 * 1024, DiagnosticLimit = 4L * 1024 * 1024, DefaultLimit = 64 * 1024;
    public static long Limit(HttpRequest r) {
        if (HttpMethods.IsPut(r.Method) && r.Path.Equals("/api/saves/current", StringComparison.OrdinalIgnoreCase)) return SaveLimit;
        if (HttpMethods.IsPost(r.Method) && r.Path.StartsWithSegments("/api/debug/sessions", StringComparison.OrdinalIgnoreCase, out var rest)
            && rest.Value is { } tail && tail.EndsWith("/batches", StringComparison.OrdinalIgnoreCase)) return DiagnosticLimit;
        return DefaultLimit;
    }
    public static async Task<bool> Receive(HttpContext h) {
        var limit = Limit(h.Request);
        if (h.Request.ContentLength > limit) { await Reject(h, StatusCodes.Status413PayloadTooLarge); return false; }
        if (h.Features.Get<IHttpMaxRequestBodySizeFeature>() is { IsReadOnly: false } size) size.MaxRequestBodySize = limit;
        // Small bodies stay in memory; larger saves spill to the temporary directory.
        h.Request.EnableBuffering(64 * 1024, limit);
        try { await h.Request.Body.DrainAsync(h.RequestAborted); }
        catch (BadHttpRequestException e) { await Reject(h, e.StatusCode); return false; }
        catch (IOException) when (h.RequestAborted.IsCancellationRequested) { return false; }
        catch (IOException) { await Reject(h, StatusCodes.Status413PayloadTooLarge); return false; }
        catch (OperationCanceledException) when (h.RequestAborted.IsCancellationRequested) { return false; }
        h.Request.Body.Position = 0;
        return true;
    }
    static async Task Reject(HttpContext h, int status) {
        if (h.Response.HasStarted) return;
        h.Response.StatusCode = status;
        if (status == StatusCodes.Status413PayloadTooLarge) await h.Response.WriteAsJsonAsync(new { error = "The request is too large" });
    }
}
