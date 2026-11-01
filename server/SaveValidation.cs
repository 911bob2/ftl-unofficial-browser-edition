using System.Xml;

namespace Ftl.Server;
public static class SaveValidation {
    public static void Xml(string? xml,string root) {
        if(xml==null)return;
        if(xml.Length>16*1024*1024)throw new InvalidDataException("Save exceeds size limit");
        using var reader=XmlReader.Create(new StringReader(xml),new XmlReaderSettings {
            DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null,
            MaxCharactersInDocument=16*1024*1024,MaxCharactersFromEntities=1024
        });
        reader.MoveToContent();
        if(reader.LocalName!=root||reader.NamespaceURI.Length!=0)throw new InvalidDataException("Unexpected save root");
        while(reader.Read())if(reader.Depth>64)throw new InvalidDataException("XML exceeds nesting limit");
    }
    public const string ProfileRoot="xftl-profile";
    public const int ProfileCharacters=4*1024*1024,ProfileElements=100_000,ProfileAttributes=256;
    /// <summary>The save rules plus the shape limits of a profile: size, elements and attributes per element.</summary>
    public static void Profile(string? xml) {
        if(xml==null)return;
        if(xml.Length>ProfileCharacters)throw new InvalidDataException("Profile exceeds size limit");
        Xml(xml,ProfileRoot);
        using var reader=XmlReader.Create(new StringReader(xml),new XmlReaderSettings {
            DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null,MaxCharactersInDocument=ProfileCharacters,MaxCharactersFromEntities=1024
        });
        var elements=0;
        while(reader.Read()){
            if(reader.NodeType!=XmlNodeType.Element)continue;
            if(++elements>ProfileElements)throw new InvalidDataException("Profile has too many elements");
            if(reader.AttributeCount>ProfileAttributes)throw new InvalidDataException("Profile element has too many attributes");
        }
    }
}
