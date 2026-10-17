// Visitors without server game files (signed out, local only, external user, password change pending) remove any copy
// an earlier account cached in this browser profile.
const clearServerAssets=()=>import('./asset-cache.mjs').then(m=>m.clearServerAssets()).catch(()=>{});
{const page=document.body.dataset;if(!['admin','user'].includes(page.role)||page.authState&&page.authState!=='authenticated')clearServerAssets();}
if(!document.getElementById('login-form'))document.getElementById('logout')?.addEventListener('click',async()=>{try{const token=(await(await fetch('/api/auth/csrf',{cache:'no-store'})).json()).token;const r=await fetch('/api/auth/logout',{method:'POST',headers:{'X-CSRF-TOKEN':token}});if(r.ok||r.status===401){await clearServerAssets();try{new BroadcastChannel('ftl-session').postMessage('changed');}catch{}location.assign('/');}}catch{location.assign('/');}});

document.addEventListener('keydown',e=>{if(e.key==='Escape')for(const d of document.querySelectorAll('.user-actions details[open]')){d.open=false;d.querySelector('summary')?.focus();}});
document.addEventListener('click',e=>{for(const d of document.querySelectorAll('.user-actions details[open]'))if(!d.contains(e.target))d.open=false;});

if(!document.getElementById('login-form')){
 const originalAccount=document.body.dataset.account??'',originalRole=document.body.dataset.role??'';let checking=false;
 async function checkSession(){if(checking)return;checking=true;try{const r=await fetch('/api/auth/me',{cache:'no-store'});if(r.status===401){if(originalAccount){dispatchEvent(new Event('ftl-session-changed'));location.reload();}}else if(r.ok){const current=await r.json();if(current.id!==originalAccount||(current.role??'')!==originalRole){dispatchEvent(new Event('ftl-session-changed'));location.reload();}}}catch{}finally{checking=false;}}
 addEventListener('focus',checkSession);addEventListener('pageshow',e=>{if(e.persisted)checkSession();});try{new BroadcastChannel('ftl-session').onmessage=checkSession;}catch{}
}
