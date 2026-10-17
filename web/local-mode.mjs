// Local-only mode screens and the move of a browser save into an account.
//   launcher: storage status; Saves panel: where saves live and the local save card; the dialog that moves a local
//   save to the account the visitor just created or signed in to. A save is never replaced without a choice, and the
//   browser copy is only removed after the account copy has been read back and matches.
import * as saves from './local-saves.mjs';
import {getLocalAsset,persistLocalAsset} from './local-assets.mjs';
const $=id=>document.getElementById(id),MARKER='ftl-enable-cloud';
const when=value=>{const t=Date.parse(value);return Number.isNaN(t)?'':new Date(t).toLocaleString();};
const describe=(record,label)=>!record||!saves.hasProgress(record)?'No '+label+' save yet.':[record.run?'Run in progress':'Profile only (no run in progress)','revision '+record.revision,record.savedUtc?'saved '+when(record.savedUtc):''].filter(Boolean).join(' · ');
export function clearMarker(){try{localStorage.removeItem(MARKER);}catch{}}

export function mountLocalMode({api,getMe,message,download,isPlaying,acquireLease}){
 const dialog=$('migrate-dialog');
 let busy=false,current=null;
 const local=()=>getMe()?.local===true;

 async function storageText(){try{return saves.describeStorage(await saves.storageStatus());}catch{return '';}}
 async function paintStorage(){const text=await storageText();for(const id of ['storage-status']){const e=$(id);if(e)e.textContent=text;}}
 async function launcherStatus(){await paintStorage();}
 // Ask the browser not to evict this origin's storage. Must follow a user action, so it runs when the game is launched.
 async function requestPersistence(){await saves.requestPersistence();await paintStorage();}

 async function savesPanel(){
  const cloud=!local();
  $('storage-card').hidden=cloud;if(!cloud)paintStorage();
  const card=$('local-save-card');card.hidden=true;
  if(!cloud)return;
  try{
   const {record}=await saves.load();
   if(!saves.hasProgress(record))return;
   $('local-save-info').textContent=describe(record,'local')+' This is the save from when you played without an account.';card.hidden=false;
  }catch{}
 }

 // The game file cached for the local pseudo-account also works for the new account of an external user.
 async function adoptGameFile(me){
  if(me.role!=='external-user')return;
  try{if(await getLocalAsset(me.id))return;const record=await getLocalAsset(saves.LOCAL_ID);if(record)await persistLocalAsset(me.id,record);}catch{}
 }

 function reset(){
  for(const id of ['migrate-cloud-download','migrate-local-download','migrate-keep-cloud','migrate-use-local','migrate-confirm'])$(id).hidden=false;
  $('migrate-note').textContent='';
 }
 function setBusy(value){busy=value;for(const id of ['migrate-cloud-download','migrate-local-download','migrate-keep-cloud','migrate-use-local','migrate-confirm','migrate-close'])$(id).disabled=value;}
 function finish(text,{reload=false}={}){
  clearMarker();dialog.close();
  if(reload){try{sessionStorage.setItem('ftl-flash',text);}catch{}location.reload();return;}
  message(text);
 }

 async function open({fromCard=false}={}){
  if(busy)return;
  if(isPlaying()){message('Return to the hangar and close the running game before moving a save.');return;}
  let record,cloud;
  try{
   ({record}=await saves.load());
   if(!saves.hasProgress(record)){clearMarker();if(fromCard){message('There is no save in this browser to move.');$('local-save-card').hidden=true;}return;}
   cloud=await api('/saves/current');
  }catch(e){message('Could not read your saves. '+e.message);return;}
  current={record,cloud};reset();
  const cloudHas=saves.hasProgress(cloud);
  $('migrate-local').textContent=describe(record,'local');
  $('migrate-cloud').textContent=cloudHas?describe(cloud,'cloud'):'Your account has no cloud save yet.';
  $('migrate-cloud-download').hidden=!cloudHas;
  $('migrate-keep-cloud').hidden=!cloudHas;$('migrate-use-local').hidden=!cloudHas;$('migrate-confirm').hidden=cloudHas;
  $('migrate-intro').textContent=cloudHas
   ?'This browser has a local save and your account already has a cloud save. Choose which one to keep. Nothing changes until you choose, and you can download either one first.'
   :'Your account has no cloud save. The local save from this browser can move to the account and then be removed from this browser. Download a backup first if you want a copy.';
  if(!dialog.open)dialog.showModal();
 }

 async function upload(){
  const {record,cloud}=current;let held=null;
  setBusy(true);$('migrate-note').textContent='Moving your save…';
  try{
   const config=await api('/game/config');
   if(record.engineBuild!==config.engineBuild||record.assetVersion!==config.assetVersion)throw Error('The local save was made with a different game build and cannot be moved. Download a backup instead.');
   held=await acquireLease();
   await api('/saves/current',{method:'PUT',body:{lease:held,state:{...saves.backup(record),revision:cloud.revision}},headers:{'If-Match':`"${cloud.revision}"`}});
   const check=await api('/saves/current');
   if((check.run??null)!==(record.run??null)||(check.profile??null)!==(record.profile??null))throw Error('The account copy did not match the local save, so the local save was kept.');
   await saves.remove();
   const text=$('cloud-ready');if(text)text.textContent='Saved run ready · cloud revision '+check.revision;
   const card=$('local-save-card');if(card)card.hidden=true;
   finish('Your local save is now in your account and was removed from this browser.');
  }catch(e){$('migrate-note').textContent=e.status===409?'Another tab or device is playing this account, or a newer save exists. Close it and try again.':e.message;}
  finally{if(held)api('/play-lease/release',{method:'POST',body:{lease:held}}).catch(()=>{});setBusy(false);}
 }

 $('migrate-confirm').onclick=upload;
 $('migrate-use-local').onclick=()=>{if(confirm('Replace the cloud save with the local save? The replaced save stays available under recovery snapshots on the Saves page, and you can download it first.'))upload();};
 $('migrate-keep-cloud').onclick=()=>finish('Your cloud save was kept. The local save is still in this browser; you can move or remove it on the Saves page.');
 $('migrate-cloud-download').onclick=()=>download(current.cloud,'ftl-cloud-backup.json');
 $('migrate-local-download').onclick=()=>download(saves.backup(current.record),'ftl-local-backup.json');
 $('migrate-close').onclick=()=>{if(!busy)dialog.close();};
 dialog.addEventListener('close',()=>{if(!busy&&dialog.open===false)clearMarker();});
 dialog.addEventListener('cancel',e=>{if(busy)e.preventDefault();});

 $('local-save-move').onclick=()=>open({fromCard:true});
 $('local-save-download').onclick=async()=>{try{const {record}=await saves.load();if(!saves.hasProgress(record))return message('There is no save in this browser.');download(saves.backup(record),'ftl-local-backup.json');}catch(e){message(e.message);}};
 $('local-save-remove').onclick=async()=>{
  if(!confirm('Remove the save from this browser? Download a backup first if you may need it.'))return;
  try{await saves.remove();$('local-save-card').hidden=true;message('The save was removed from this browser.');}catch(e){message(e.message);}
 };

 // Offered after the visitor chose Enable cloud saves and then signed in or registered.
 async function offer(){await adoptGameFile(getMe());await open();}
 return {launcherStatus,requestPersistence,savesPanel,offer,open};
}
