import {indexDat,sha256,unpackResources} from './dat.mjs';
self.onmessage=async({data})=>{
 const {id,action,file,config,index,support}=data;
 const progress=text=>self.postMessage({id,progress:text});
 try{
  if(action==='validate'){
   if(file.size>1024*1024*1024||config.datBytes&&file.size!==config.datBytes)throw Error('This file does not match the supported FTL 1.6.14 data file.');
   progress('Reading the DAT index…');const entries=await indexDat(file);
   progress('Verifying your file…');let bytes=await file.arrayBuffer();const hash=await sha256(bytes);bytes=null;
   if(hash!==config.assetVersion)throw Error('This DAT does not match this deployment. Choose the same unmodified FTL 1.6.14 data file.');
   const audio=[];progress('Preparing the audio index…');
   for(const e of entries.filter(e=>/\.(ogg|wav)$/.test(e.name))){const hash=await sha256(await file.slice(e.offset,e.offset+e.length).arrayBuffer());audio.push({...e,hash,extension:e.name.endsWith('.ogg')?'.ogg':'.wav'});}
   self.postMessage({id,result:{entries,audio,assetVersion:hash,size:file.size,parserVersion:1}});
  }else if(action==='boot'){
   progress('Preparing local game resources…');
   const engine=unpackResources(support),entries=[],parts=[];let offset=0;
   for(const e of index.entries.filter(e=>!/\.(ogg|wav)$/.test(e.name))){const bytes=await file.slice(e.offset,e.offset+e.length).arrayBuffer();entries.push({name:e.name,hash:await sha256(bytes),offset,length:bytes.byteLength});parts.push(bytes);offset+=bytes.byteLength;}
   for(const e of engine.entries){const bytes=support.slice(engine.base+e.offset,engine.base+e.offset+e.length);entries.push({...e,offset});parts.push(bytes);offset+=bytes.byteLength;}
   const json=new TextEncoder().encode(JSON.stringify(entries)),packed=new Uint8Array(8+json.length+offset),view=new DataView(packed.buffer);view.setUint32(0,0x46544c42);view.setUint32(4,json.length);packed.set(json,8);offset=8+json.length;
   for(const bytes of parts){packed.set(new Uint8Array(bytes),offset);offset+=bytes.byteLength;}
   self.postMessage({id,result:packed.buffer},[packed.buffer]);
  }else throw Error('Unknown local resource operation.');
 }catch(e){self.postMessage({id,error:e.message});}
};
