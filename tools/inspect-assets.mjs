import fs from 'node:fs';
const packed=fs.readFileSync('runtime/assets/boot.pack'),length=packed.readUInt32BE(4),entries=JSON.parse(packed.subarray(8,8+length));
for(const name of process.argv.slice(2)){
  const entry=entries.find(e=>e.name===name);if(!entry)throw Error('Missing asset '+name);
  const content=packed.subarray(8+length+entry.offset,8+length+entry.offset+entry.length).toString();
  fs.mkdirSync('.build/asset-inspect',{recursive:true});fs.writeFileSync('.build/asset-inspect/'+name.replaceAll('/','_'),content);
  console.log(name+' '+content.length+' chars');
}
