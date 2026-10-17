const decoder=new TextDecoder('utf-8',{fatal:true});
export async function sha256(bytes){return [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(b=>b.toString(16).padStart(2,'0')).join('');}
export async function indexDat(file){
 if(file.size<16)throw Error('The file is truncated. Choose an unmodified matching ftl.dat.');
 const header=new DataView(await file.slice(0,16).arrayBuffer());
 if(header.getUint32(0)!==0x504b470a||header.getUint16(4)!==16||header.getUint16(6)!==20)throw Error('Unsupported DAT format. Choose an unmodified matching ftl.dat.');
 const count=header.getUint32(8),nameSize=header.getUint32(12),end=16+count*20+nameSize;
 if(count<1||count>100000||nameSize<1||nameSize>10000000||end>file.size)throw Error('Invalid DAT index.');
 const data=await file.slice(16,end).arrayBuffer(),table=new DataView(data),names=new Uint8Array(data,count*20,nameSize),seen=new Set(),entries=[];
 for(let i=0;i<count;i++){
  const pos=i*20,flagName=table.getUint32(pos+4),offset=table.getUint32(pos+8),compressed=table.getUint32(pos+12),length=table.getUint32(pos+16),start=flagName&0xffffff;
  if(flagName>>>24!==0||compressed!==length)throw Error('Compressed DAT entries are unsupported.');
  if(offset<end||offset+length>file.size||start>=nameSize)throw Error('Invalid DAT resource bounds.');
  let stop=start;while(stop<nameSize&&names[stop]!==0)stop++;if(stop===nameSize)throw Error('Invalid DAT resource name.');
  const name=decoder.decode(names.subarray(start,stop));
  if(!name||name.startsWith('/')||name.includes('..')||name.includes('\\')||/[\u0000-\u001f]/.test(name)||seen.has(name))throw Error('Unsafe or duplicate DAT resource name.');
  seen.add(name);entries.push({name,offset,length});
 }
 return entries;
}
export function unpackResources(packed){
 const view=new DataView(packed);if(packed.byteLength<8||view.getUint32(0)!==0x46544c42)throw Error('Invalid engine resources.');
 const n=view.getUint32(4);if(n>packed.byteLength-8)throw Error('Truncated engine resources.');
 const entries=JSON.parse(decoder.decode(new Uint8Array(packed,8,n))),base=8+n;
 for(const e of entries)if(!e.name.startsWith('engine/')||!Number.isSafeInteger(e.offset)||!Number.isSafeInteger(e.length)||e.offset<0||e.length<0||base+e.offset+e.length>packed.byteLength)throw Error('Invalid engine resource bounds.');
 return {entries,base};
}
