import {zipSync,strToU8} from './vendor/fflate-0.8.3.mjs';

// Supplement a streamed server ZIP without inflating/copying its log segments.
// Retained session limits keep these archives within ordinary ZIP32 bounds.
async function directory(blob){
  const tail=new Uint8Array(await blob.slice(Math.max(0,blob.size-65557)).arrayBuffer()),v=new DataView(tail.buffer);
  for(let p=tail.length-22;p>=0;p--){
    if(v.getUint32(p,true)!==0x06054b50||p+22+v.getUint16(p+20,true)!==tail.length)continue;
    const count=v.getUint16(p+10,true),size=v.getUint32(p+12,true),offset=v.getUint32(p+16,true);
    if(v.getUint16(p+4,true)||v.getUint16(p+6,true)||count===65535||size===0xffffffff||offset===0xffffffff)throw Error('Unsupported diagnostic ZIP directory');
    if(offset+size>blob.size-tail.length+p)throw Error('Invalid diagnostic ZIP directory');
    return {count,size,offset,data:new Uint8Array(await blob.slice(offset,offset+size).arrayBuffer())};
  }
  throw Error('Missing diagnostic ZIP directory');
}
export async function diagnosticArchive(server,files){
  const extra=new Blob([zipSync(Object.fromEntries(Object.entries(files).map(([name,text])=>[name,strToU8(text)])),{level:0})],{type:'application/zip'});
  if(!server)return extra;
  const [original,supplement]=await Promise.all([directory(server),directory(extra)]);
  const count=original.count+supplement.count,size=original.size+supplement.size,offset=original.offset+supplement.offset;
  if(count>=65535||offset+size>=0xffffffff)throw Error('Diagnostic ZIP exceeds export limits');
  const central=supplement.data,v=new DataView(central.buffer,central.byteOffset,central.byteLength);
  let p=0;
  for(let i=0;i<supplement.count;i++){
    if(p+46>central.length||v.getUint32(p,true)!==0x02014b50)throw Error('Invalid supplement ZIP directory');
    v.setUint32(p+42,v.getUint32(p+42,true)+original.offset,true);
    p+=46+v.getUint16(p+28,true)+v.getUint16(p+30,true)+v.getUint16(p+32,true);
  }
  const end=new Uint8Array(22),e=new DataView(end.buffer);e.setUint32(0,0x06054b50,true);e.setUint16(8,count,true);e.setUint16(10,count,true);e.setUint32(12,size,true);e.setUint32(16,offset,true);
  return new Blob([server.slice(0,original.offset),extra.slice(0,supplement.offset),original.data,central,end],{type:'application/zip'});
}
export function diagnosticCheckpointFiles(records){
  const files={},checkpoints=new Map();let retained=0;
  for(const record of records){
    const p=record.payload;
    if(record.eventName!=='checkpoint.part'||!p||!/^[a-f0-9-]{36}$/i.test(p.id)||!['run','profile'].includes(p.name)||
      !Number.isInteger(p.part)||!Number.isInteger(p.parts)||p.part<0||p.parts<1||p.part>=p.parts||p.parts>512||typeof p.data!=='string'||p.data.length>32768)continue;
    const key=p.id+'/'+p.name;let checkpoint=checkpoints.get(key);
    if(!checkpoint){checkpoint={parts:p.parts,values:new Map()};checkpoints.set(key,checkpoint);}
    if(checkpoint.parts!==p.parts)continue;
    retained+=p.data.length;if(retained>32*1024*1024)break;
    checkpoint.values.set(p.part,p.data);
  }
  for(const [key,c] of checkpoints)if(c.values.size===c.parts){
    const [id,name]=key.split('/');files['browser/checkpoints/'+id.replaceAll('-','')+'/'+name+'.xml']=Array.from({length:c.parts},(_,i)=>c.values.get(i)).join('');
  }
  return files;
}
