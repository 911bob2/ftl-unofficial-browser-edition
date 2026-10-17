using System.Security.Cryptography;
using System.Text;

namespace Ftl.Server;

// Client cache identity is distinct from the native save-format version.
public sealed class ClientAssets {
    public string Version {get;}
    public ClientAssets(string root) {
        using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        foreach(var file in System.IO.Directory.EnumerateFiles(root,"*",SearchOption.AllDirectories)
            .Where(p=>p.EndsWith(".mjs")||p.EndsWith(".js")||p.EndsWith(".css"))
            .OrderBy(p=>p,StringComparer.Ordinal)) {
            hash.AppendData(Encoding.UTF8.GetBytes(Path.GetRelativePath(root,file)));
            hash.AppendData(File.ReadAllBytes(file));
        }
        Version=Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
    }
}
