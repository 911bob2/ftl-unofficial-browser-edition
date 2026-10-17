import {createAssetCache} from './asset-cache.mjs';
import {getLocalAsset,workerTask} from './local-assets.mjs';
import {sha256} from './dat.mjs';

export async function createAssetProvider({api,accountId,fetchBytes,onProgress=()=>{},signal}){
 const config=await api('/game/config');
 if(config.accountId&&config.accountId!==accountId)throw Error('The active account changed. Reload before using this account’s game files.');
 if(config.assetSource==='server'){
  const manifest=await api('/assets/manifest');
  return {source:'server',manifest,...await createAssetCache(manifest,fetchBytes),dispose(){}};
 }
 const record=await getLocalAsset(accountId);
 if(!record||record.index.assetVersion!==config.assetVersion||record.index.parserVersion!==1)throw Object.assign(Error('Upload a matching ftl.dat in Local game files before launching FTL or running engine tests.'),{code:'LOCAL_ASSETS_REQUIRED'});
 if(!config.resourcesHash)throw Error('Engine resources are unavailable. Please contact the administrator.');
 const manifest={...config,schema:1,audio:record.index.audio};
 let bootPromise;
 return {
  source:'local',manifest,
  fetchBoot(){return bootPromise??=(async()=>{
   const support=await fetchBytes(config.resourcesUrl);
   if(support.byteLength!==config.resourcesBytes||await sha256(support)!==config.resourcesHash)throw Error('Engine resources changed. Reload and retry.');
   return workerTask('boot',{file:record.file,index:record.index,support},onProgress,signal);
  })().catch(e=>{bootPromise=null;throw e;});},
  async fetchAudio(asset){const entry=record.index.audio.find(a=>a.hash===asset.hash);if(!entry)throw Error('Audio is absent from your local game file.');return record.file.slice(entry.offset,entry.offset+entry.length).arrayBuffer();},
  dispose(){bootPromise=null;}
 };
}
