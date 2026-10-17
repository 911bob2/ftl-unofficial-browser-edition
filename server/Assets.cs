using System.Security.Cryptography;
using System.Text.Json;

namespace Ftl.Server;
public sealed class AssetCatalog {
    public string Directory {get;}
    public JsonDocument Manifest {get;}
    public string Version {get;}
    public string EngineBuild {get;}
    public string BootHash {get;}
    public Dictionary<string,string> Audio {get;} = [];
    public AssetCatalog(string directory) {
        Directory=Path.GetFullPath(directory);Manifest=JsonDocument.Parse(File.ReadAllText(Path.Combine(Directory,"manifest.json")));
        Version=Manifest.RootElement.GetProperty("assetVersion").GetString()!;EngineBuild=Manifest.RootElement.GetProperty("engineBuild").GetString()!;
        foreach(var item in Manifest.RootElement.GetProperty("audio").EnumerateArray()){
            var hash=item.GetProperty("hash").GetString()!;var ext=item.GetProperty("extension").GetString()!;
            if(hash.Length!=64||hash.Any(c=>!char.IsAsciiHexDigit(c))||ext is not (".ogg" or ".wav"))throw new InvalidDataException("Invalid audio manifest path");
            Audio[hash]=Path.Combine(Directory,"audio",hash+ext);
        }
        if(!File.Exists(Path.Combine(Directory,"boot.pack")))throw new IOException("Assets have not been prepared.");
        using var boot=File.OpenRead(Path.Combine(Directory,"boot.pack"));
        BootHash=Convert.ToHexString(SHA256.HashData(boot)).ToLowerInvariant();
    }
}
