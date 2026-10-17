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
}
