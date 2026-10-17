using System.Security.Cryptography;
namespace Ftl.Server;
public sealed class EngineResources {
    public string Path { get; }
    public string Hash { get; }
    public long Bytes { get; }
    public EngineResources(IWebHostEnvironment host, IConfiguration config) {
        Path = System.IO.Path.GetFullPath(config["FTL_ENGINE_RESOURCES"] ?? System.IO.Path.Combine(host.ContentRootPath, "resources.pack"));
        if(!File.Exists(Path)) { Hash = ""; return; }
        using var file = File.OpenRead(Path); Bytes = file.Length;
        Span<byte> header=stackalloc byte[8];file.ReadExactly(header);var length=System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header[4..]);
        if(System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(header)!=0x46544c42||length>512*1024||length>Bytes-8)throw new InvalidDataException("Invalid engine resource bundle");
        var index=new byte[length];file.ReadExactly(index);using var table=System.Text.Json.JsonDocument.Parse(index);
        var names=new HashSet<string>();foreach(var entry in table.RootElement.EnumerateArray()){
            var name=entry.GetProperty("name").GetString()!;var offset=entry.GetProperty("offset").GetInt64();var size=entry.GetProperty("length").GetInt64();
            if(!name.StartsWith("engine/",StringComparison.Ordinal)||name.Contains("..")||name.Contains('\\')||!names.Add(name)||offset<0||size<0||offset>Bytes-8-length||size>Bytes-8-length-offset)throw new InvalidDataException("Engine bundle contains invalid or non-engine resources");
        }
        if(names.Count==0)throw new InvalidDataException("Engine resource bundle is empty");
        file.Position=0;Hash = Convert.ToHexString(SHA256.HashData(file)).ToLowerInvariant();
    }
}
