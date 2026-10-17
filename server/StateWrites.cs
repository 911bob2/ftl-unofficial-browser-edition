using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace Ftl.Server;
public static class StateWrites {
    public static async Task<IResult> Run(HttpContext context,ILogger logger,Func<Task<IResult>> write) {
        try {return await write();}
        catch(Exception e) when (e is SqliteException {SqliteErrorCode:5 or 6}
            || e is DbUpdateException {InnerException:SqliteException {SqliteErrorCode:5 or 6}}) {
            // BEGIN can fail too. Scope disposal rolls back before this handler runs.
            logger.LogWarning("State write delayed by SQLite contention: {Path}",context.Request.Path);
            context.Response.Headers.RetryAfter="1";
            return Results.Json(new{error="Storage is busy. Try saving again.",code="storage_busy"},statusCode:503);
        }
    }
}
