// Account panel: who is signed in, and permanent account deletion.
import {confirmAction} from './dialogs.mjs';
const $=id=>document.getElementById(id);
const labels={admin:'Administrator',user:'Normal user','external-user':'External user'};
export function mountAccount({api,getMe,message,download,backup:allSaves,beforeDeletion}){
 let deleting=false;
 // Every save of the account (from this browser and the account) in one file.
 async function backup(){try{download(await allSaves(),'ftl-saves-backup.json');}catch(e){message(e.message);}}
 $('delete-backup').onclick=backup;
 $('delete-account-form').addEventListener('submit',async e=>{
  e.preventDefault();if(deleting)return;
  const password=$('delete-password').value,button=$('delete-account');
  if(!await confirmAction({id:'delete-account-dialog',title:'Delete this account permanently?',text:'This cannot be undone. Your saves, recovery snapshots and logs on the server will be erased, and the saves of this account in this browser.',yes:'Delete account',danger:true}))return;
  deleting=true;button.disabled=true;
  try{
   await api('/auth/delete-account',{method:'POST',body:{password}});
   $('delete-password').value='';
   await beforeDeletion();
   try{sessionStorage.setItem('ftl-flash','Your account was deleted.');}catch{}
   try{new BroadcastChannel('ftl-session').postMessage('changed');}catch{}
   location.assign('/');
  }catch(error){message(error.message);button.disabled=false;deleting=false;}
 });
 // Refreshed whenever the panel opens, because the right to delete depends on the other administrators.
 async function refresh(){
  const me=getMe();if(!me||me.local)return;
  $('account-summary').textContent='Signed in as '+me.username+' · '+(labels[me.role]??me.role);
  let canDelete=true;
  try{const current=await api('/auth/me');canDelete=current.canDeleteAccount!==false;me.canDeleteAccount=canDelete;}catch{}
  $('delete-blocked').hidden=canDelete;$('delete-account-form').hidden=!canDelete;
 }
 return {refresh};
}
