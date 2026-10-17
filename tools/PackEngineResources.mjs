import {readdir,readFile,mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
const root=path.resolve(process.argv[2]??'engine/resources'),out=path.resolve(process.argv[3]??'.build/website-roles-assets/resources.pack');
async function files(dir){const result=[];for(const item of await readdir(dir,{withFileTypes:true})){const file=path.join(dir,item.name);if(item.isDirectory())result.push(...await files(file));else if(item.isFile())result.push(file);}return result;}
const reviewed=JSON.parse(await readFile(new URL('./engine-resources.json',import.meta.url),'utf8')).files;const discovered=(await files(root)).sort();if(JSON.stringify(discovered.map(file=>path.relative(root,file).replaceAll('\\','/')).sort())!==JSON.stringify(reviewed.slice().sort()))throw Error('Review and update the redistributable engine resource allowlist before packaging.');
const entries=[],parts=[];let offset=0;
for(const file of discovered){
 const bytes=await readFile(file);entries.push({name:'engine/'+path.relative(root,file).replaceAll('\\','/'),hash:createHash('sha256').update(bytes).digest('hex'),offset,length:bytes.length});parts.push(bytes);offset+=bytes.length;
}
const json=Buffer.from(JSON.stringify(entries)),header=Buffer.alloc(8);header.writeUInt32BE(0x46544c42);header.writeUInt32BE(json.length,4);
await mkdir(path.dirname(out),{recursive:true});await writeFile(out,Buffer.concat([header,json,...parts]));
console.log('Packaged '+entries.length+' redistributable engine resources');
