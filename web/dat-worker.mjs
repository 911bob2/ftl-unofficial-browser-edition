import {indexDat,sha256,unpackResources} from './dat.mjs';
self.onmessage=async({data})=>{
 const {id,action,file,config,index,support}=data;
 // detail = {done, total} bytes for the progress bar; the text stays the step's name.
 const progress=(text,detail=null)=>self.postMessage({id,progress:text,detail});
 try{
  if(action==='validate'){
   if(file.size>1024*1024*1024||config.datBytes&&file.size!==config.datBytes)throw Error('This file does not match the supported FTL 1.6.14 data file.');
   progress('Reading the DAT index…');const entries=await indexDat(file);
   // Read in slices (with progress) into one buffer, then hash it whole with Web Crypto.
   let bytes=new Uint8Array(file.size);const slice=16<<20;
   for(let o=0;o<file.size;o+=slice){bytes.set(new Uint8Array(await file.slice(o,Math.min(file.size,o+slice)).arrayBuffer()),o);progress('Verifying your file…',{done:Math.min(file.size,o+slice),total:file.size});}
   const hash=await sha256(bytes);bytes=null;
   if(hash!==config.assetVersion)throw Error('This DAT does not match this deployment. Choose the same unmodified FTL 1.6.14 data file.');
   const audio=[],sounds=entries.filter(e=>/\.(ogg|wav)$/.test(e.name)),soundBytes=sounds.reduce((n,e)=>n+e.length,0);let done=0;progress('Preparing the audio index…',{done:0,total:soundBytes});
   for(const e of sounds){const hash=await sha256(await file.slice(e.offset,e.offset+e.length).arrayBuffer());audio.push({...e,hash,extension:e.name.endsWith('.ogg')?'.ogg':'.wav'});done+=e.length;progress('Preparing the audio index…',{done,total:soundBytes});}
   self.postMessage({id,result:{entries,audio,assetVersion:hash,size:file.size,parserVersion:1}});
  }else if(action==='boot'){
   const engine=unpackResources(support),entries=[],parts=[],files=index.entries.filter(e=>!/\.(ogg|wav)$/.test(e.name)),total=files.reduce((n,e)=>n+e.length,0);let offset=0,last=0;
   progress('Preparing local game resources…',{done:0,total});
   for(const e of files){const bytes=await file.slice(e.offset,e.offset+e.length).arrayBuffer();entries.push({name:e.name,hash:await sha256(bytes),offset,length:bytes.byteLength});parts.push(bytes);offset+=bytes.byteLength;if(offset-last>=4<<20||offset===total){last=offset;progress('Preparing local game resources…',{done:offset,total});}}
   for(const e of engine.entries){const bytes=support.slice(engine.base+e.offset,engine.base+e.offset+e.length);entries.push({...e,offset});parts.push(bytes);offset+=bytes.byteLength;}
   const json=new TextEncoder().encode(JSON.stringify(entries)),packed=new Uint8Array(8+json.length+offset),view=new DataView(packed.buffer);view.setUint32(0,0x46544c42);view.setUint32(4,json.length);packed.set(json,8);offset=8+json.length;
   for(const bytes of parts){packed.set(new Uint8Array(bytes),offset);offset+=bytes.byteLength;}
   self.postMessage({id,result:packed.buffer},[packed.buffer]);
  }else throw Error('Unknown local resource operation.');
 }catch(e){self.postMessage({id,error:e.message});}
};
