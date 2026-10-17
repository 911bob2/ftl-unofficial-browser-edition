const $=id=>document.getElementById(id),labels={admin:'Admin',user:'Normal','external-user':'External user'};
const descriptions={admin:'Full account administration, server game files, and server integration tests.',user:'Server game files with cloud saves. Tests and benchmarks run locally in the browser.','external-user':'Requires a matching ftl.dat cached only in this browser. Cloud saves remain available.'};
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
export function createUsersUI({api,getMe,message}){
 let users=[],selected,poll,refreshGeneration=0;const busy=new Set();
 const dialog=$('user-dialog'),error=$('user-dialog-error');
 function credentials(username,password){$('credentials-value').textContent=username+'\t'+password;$('new-credentials').hidden=false;}
 $('credentials-dismiss').onclick=()=>{$('credentials-value').textContent='';$('new-credentials').hidden=true;};
 $('credentials-copy').onclick=async()=>{try{await navigator.clipboard.writeText($('credentials-value').textContent);$('credentials-copy').textContent='Copied';setTimeout(()=>$('credentials-copy').textContent='Copy details',2000);}catch{message('Select the temporary details and copy them manually.');}};
 function open(user){selected=user;error.hidden=true;$('user-dialog-title').textContent=user.userName;$('user-dialog-meta').textContent=(labels[user.role]??'Invalid role')+' · '+user.activeSessions+' active sessions';$('edit-role').value=user.role;$('role-description').textContent=descriptions[user.role];$('delete-user-confirm').value='';
  const self=user.id===getMe().id,deleting=user.lifecycle==='deleting';$('delete-user-form').querySelector('button').disabled=self||deleting;$('role-form').querySelector('button').disabled=deleting;$('edit-role').disabled=deleting;
  const actions=$('user-security-actions');actions.replaceChildren();for(const [label,path,body] of [[user.disabled?'Enable':'Disable','disable',{disabled:!user.disabled}],['Reset password','reset-password',{}],['Revoke sessions','revoke',{}]]){const b=el('button',label,'btn btn-sm');b.disabled=deleting||self&&['disable','reset-password'].includes(path);b.onclick=()=>action(user,path,body,b);actions.append(b);}
  $('user-delete-data').textContent='Loading account data…';$('delete-user-form').querySelector('button').disabled=true;api('/admin/users/'+user.id+'/data').then(data=>{if(selected?.id!==user.id)return;$('delete-user-form').querySelector('button').disabled=self||deleting;$('user-delete-data').textContent=(data.currentSave?'1 current save':'No current save')+' · '+data.snapshots+' snapshots · '+data.diagnostics.sessions+' diagnostic sessions ('+(data.diagnostics.bytes/1024/1024).toFixed(1)+' MiB)';}).catch(()=>{if(selected?.id===user.id)$('user-delete-data').textContent='Account data counts unavailable. Close and retry before deleting.';});if(!dialog.open)dialog.showModal();
 }
 dialog.addEventListener('close',()=>document.querySelector('[data-user="'+selected?.id+'"] .user-actions button')?.focus());$('user-dialog-close').onclick=()=>dialog.close();$('edit-role').onchange=()=>$('role-description').textContent=descriptions[$('edit-role').value];
 async function action(user,path,body,button,method='POST'){
  if(busy.has(user.id))return;busy.add(user.id);button.disabled=true;error.hidden=true;
  try{
   const result=await api('/admin/users/'+user.id+(path?'/'+path:''),{method,body:{...body,version:user.version}});
   if(result?.temporaryPassword)credentials(user.userName,result.temporaryPassword);
   if(method==='DELETE')dialog.close();await refresh();
   if(dialog.open&&selected?.id===user.id){const updated=users.find(u=>u.id===user.id);if(updated)open(updated);else dialog.close();}
  }catch(e){if(dialog.open&&selected?.id===user.id){error.textContent=e.message;error.hidden=false;}else{const row=document.querySelector('[data-user="'+user.id+'"]');if(row){let feedback=row.querySelector('.user-row-error');if(!feedback){feedback=el('p',undefined,'user-row-error');row.append(feedback);}feedback.textContent=e.message;}else message(e.message);}}
  finally{busy.delete(user.id);button.disabled=false;}
 }
 $('role-form').onsubmit=e=>{e.preventDefault();if(selected.id===getMe().id&&$('edit-role').value!=='admin'){error.textContent='You cannot demote your own account.';error.hidden=false;return;}action(selected,'role',{role:$('edit-role').value},e.submitter,'PATCH');};
 $('delete-user-form').onsubmit=e=>{e.preventDefault();action(selected,'',{confirmUsername:$('delete-user-confirm').value},e.submitter,'DELETE');};
 function render(){
  const search=$('users-search').value.toLowerCase(),role=$('users-role').value,status=$('users-status').value;
  const visible=users.filter(u=>u.userName.toLowerCase().includes(search)&&(!role||u.role===role)&&(!status||u.lifecycle===status)).sort((a,b)=>$('users-sort').value==='created'?b.createdUtc.localeCompare(a.createdUtc):a.userName.localeCompare(b.userName));
  $('users-count').textContent=visible.length+' of '+users.length+' accounts';const list=$('users');list.replaceChildren();
  if(!visible.length){list.append(el('p','No accounts match these filters.','muted'));return;}
  for(const u of visible){const row=el('article',undefined,'user-row');row.dataset.user=u.id;
   const name=el('div');name.append(el('strong',u.userName),el('small',u.id===getMe().id?'Your account':'Account'));const role=el('div');role.append(el('span',labels[u.role]??'Invalid role','badge badge-role'));
   const status=el('div');status.append(el('span',u.lifecycle==='deleting'?'Deletion pending':u.disabled?'Disabled':u.mustChangePassword?'Password change required':'Active','badge'),el('small',u.activeSessions+' active sessions'));
   const created=el('div');created.append(el('small','Created'),el('span',new Date(u.createdUtc).toLocaleDateString()));const actions=el('div',undefined,'user-actions');
   const manage=el('button','Manage','btn btn-sm');manage.disabled=u.lifecycle==='deleting';manage.onclick=()=>open(u);actions.append(manage);
   const menu=el('details'),summary=el('summary','Actions ⌄','btn btn-ghost btn-sm'),options=el('div',undefined,'user-action-menu');
   for(const [label,path,body] of [[u.disabled?'Enable':'Disable','disable',{disabled:!u.disabled}],['Reset password','reset-password',{}],['Revoke sessions','revoke',{}]]){const b=el('button',label);b.disabled=u.lifecycle==='deleting'||u.id===getMe().id&&['disable','reset-password'].includes(path);b.onclick=()=>{menu.open=false;action(u,path,body,b);};options.append(b);}
   menu.append(summary,options);actions.append(menu);row.append(name,role,status,created,actions);list.append(row);
  }
 }
 for(const id of ['users-search','users-role','users-status','users-sort'])$(id).addEventListener(id==='users-search'?'input':'change',render);
 async function refresh(){const generation=++refreshGeneration;
  if(!users.length){$('users').replaceChildren(...Array.from({length:3},()=>el('div',undefined,'skeleton')));$('users').setAttribute('aria-busy','true');}
  loadRegistration();
  try{const result=await api('/admin/users');if(generation!==refreshGeneration)return;users=result;render();clearTimeout(poll);if(users.some(u=>u.lifecycle==='deleting'))poll=setTimeout(()=>refresh().catch(e=>message(e.message)),2500);
   api('/admin/audit').then(audits=>{const list=$('audit');list.replaceChildren();for(const a of audits){const row=el('div',undefined,'audit-row');row.append(el('time',new Date(a.createdUtc).toLocaleString()),el('span',a.action.replaceAll('-', ' ')+' · '+(a.subject??a.subjectId??'Instance')));list.append(row);}if(!audits.length)list.append(el('p','No security events.','muted'));}).catch(()=>{$('audit').textContent='Audit temporarily unavailable. Refresh to retry.';});
  }catch(e){if(generation!==refreshGeneration)return;if(!users.length){$('users-count').textContent='Unavailable';const retry=el('button','Retry','btn btn-sm');retry.onclick=()=>refresh().catch(error=>message(error.message));$('users').replaceChildren(el('p',e.message,'muted'),retry);}throw e;}finally{$('users').removeAttribute('aria-busy');}
 }
 const registration=$('registration-toggle'),registrationState=$('registration-state');
 async function loadRegistration(){try{const settings=await api('/admin/settings');registration.checked=settings.registrationOpen;registration.disabled=settings.registrationLocked;registrationState.textContent=settings.registrationLocked?'Fixed by the server configuration (FTL_PUBLIC_REGISTRATION). Change it there.':settings.registrationOpen?'Anyone can create an external-user account from the sign-in screen.':'Registration is closed. Only administrators can create accounts.';}catch(e){registrationState.textContent=e.message;}}
 registration.onchange=async()=>{registration.disabled=true;try{await api('/admin/settings/registration',{method:'PUT',body:{open:registration.checked}});}catch(e){message(e.message);}await loadRegistration();};
 $('users-refresh').onclick=()=>refresh().catch(e=>message(e.message));
 $('create-user-form').onsubmit=async e=>{e.preventDefault();const b=e.submitter;b.disabled=true;try{const f=new FormData(e.target),result=await api('/admin/users',{method:'POST',body:{username:f.get('username'),role:f.get('role')}});credentials(result.username,result.temporaryPassword);e.target.reset();await refresh();}catch(e){message(e.message);}finally{b.disabled=false;}};
 return {refresh};
}
