import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import javax.xml.parsers.*;

/** Private preparation of an owner-supplied DAT; never run during image builds. */
public class PrepareAssets {
    record Entry(String name,byte[] bytes,String hash){}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    static void validateXml(String name,byte[] bytes)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        try{f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));}catch(Exception e){throw new IOException("Invalid game XML: "+name,e);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("Usage: PrepareAssets <ftl.dat> <output> <engine-resources>");
        Path input=Path.of(args[0]),out=Path.of(args[1]),resources=Path.of(args[2]);Files.createDirectories(out.resolve("audio"));
        List<Entry> boot=new ArrayList<>(),audio=new ArrayList<>();String datHash;long datBytes=Files.size(input);
        try(InputStream in=Files.newInputStream(input)){datHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));}
        try(RandomAccessFile f=new RandomAccessFile(input.toFile(),"r")){
            if(f.readInt()!=0x504b470a||f.readUnsignedShort()!=16||f.readUnsignedShort()!=20)throw new IOException("Unsupported DAT header");
            int count=f.readInt(),nameSize=f.readInt();if(count<1||count>100000||nameSize<1||nameSize>10000000)throw new IOException("Invalid DAT index");
            byte[] index=new byte[Math.multiplyExact(count,20)],names=new byte[nameSize];f.readFully(index);f.readFully(names);ByteBuffer table=ByteBuffer.wrap(index);
            for(int i=0;i<count;i++){
                table.getInt();int flagName=table.getInt(),offset=table.getInt(),compressed=table.getInt(),length=table.getInt();
                if(flagName>>>24!=0||compressed!=length)throw new IOException("Compressed DAT entries are unsupported");
                if(offset<16||length<0||(long)offset+length>f.length())throw new IOException("Invalid DAT extent");
                int start=flagName&0xffffff,end=start;while(end<names.length&&names[end]!=0)end++;if(start>=names.length||end==names.length)throw new IOException("Invalid DAT name");
                String name=new String(names,start,end-start,StandardCharsets.UTF_8);if(name.startsWith("/")||name.contains("..")||name.contains("\\"))throw new IOException("Unsafe asset name");
                byte[] bytes=new byte[length];f.seek(offset);f.readFully(bytes);if(name.endsWith(".xml"))validateXml(name,bytes);
                Entry e=new Entry(name,bytes,hash(bytes));if(name.endsWith(".ogg")||name.endsWith(".wav"))audio.add(e);else boot.add(e);
            }
        }
        try(var walk=Files.walk(resources)){
            for(Path p:walk.filter(Files::isRegularFile).sorted().toList()){
                String name="engine/"+resources.relativize(p).toString().replace('\\','/');byte[] bytes=Files.readAllBytes(p);if(name.endsWith(".xml"))validateXml(name,bytes);boot.add(new Entry(name,bytes,hash(bytes)));
            }
        }
        long offset=0;StringJoiner entries=new StringJoiner(",","[","]");
        for(Entry e:boot){entries.add("{\"name\":"+quote(e.name)+",\"hash\":"+quote(e.hash)+",\"offset\":"+offset+",\"length\":"+e.bytes.length+"}");offset+=e.bytes.length;}
        byte[] json=entries.toString().getBytes(StandardCharsets.UTF_8);
        Path temporary=out.resolve("boot.pack.tmp");
        try(DataOutputStream pack=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))){
            pack.writeInt(0x46544c42);pack.writeInt(json.length);pack.write(json);for(Entry e:boot)pack.write(e.bytes);
        }
        Files.move(temporary,out.resolve("boot.pack"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        try(InputStream in=Files.newInputStream(out.resolve("boot.pack"));OutputStream gz=new GZIPOutputStream(Files.newOutputStream(out.resolve("boot.pack.gz")))){in.transferTo(gz);}
        StringJoiner sounds=new StringJoiner(",","[","]");for(Entry e:audio){String ext=e.name.endsWith(".ogg")?".ogg":".wav";Files.write(out.resolve("audio").resolve(e.hash+ext),e.bytes);sounds.add("{\"name\":"+quote(e.name)+",\"hash\":"+quote(e.hash)+",\"length\":"+e.bytes.length+",\"extension\":"+quote(ext)+"}");}
        String manifest="{\"schema\":1,\"datBytes\":"+datBytes+",\"assetVersion\":"+quote(datHash)+",\"engineBuild\":\"pocket-001b19d-browser-1\",\"bootBytes\":"+Files.size(out.resolve("boot.pack"))+",\"entries\":"+boot.size()+",\"audio\":"+sounds+"}";
        Files.writeString(out.resolve("manifest.json.tmp"),manifest);Files.move(out.resolve("manifest.json.tmp"),out.resolve("manifest.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        System.out.printf("Prepared %d boot resources and %d audio resources. Asset SHA-256: %s%n",boot.size(),audio.size(),datHash);
    }
}
