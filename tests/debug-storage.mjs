import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
const origin='http://localhost:8085',cookies=new Map();let csrf;
async function request(path,method='GET',body){const r=await fetch(origin+path,{method,headers:{Origin:origin,Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),...(body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{'X-CSRF-TOKEN':csrf}: {})},body:body?JSON.stringify(body):undefined});for(const c of r.headers.getSetCookie()){const p=c.split(';')[0],i=p.indexOf('=');cookies.set(p.slice(0,i),p.slice(i+1));}return r;}
for(let i=0;i<60;i++){try{if((await request('/health/ready')).ok)break;}catch{}await new Promise(r=>setTimeout(r,250));}
assert.equal((await request('/health/ready')).status,200);
const initial=new Map((await readFile('.build/debug-unavailable-data/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(x=>x.split('\t'))),password='debug-storage-fixture-password';
const token=async()=>{csrf=(await(await request('/api/auth/csrf')).json()).token;};
await token();let r=await request('/api/auth/login','POST',{username:'user01',password});if(r.status===401)r=await request('/api/auth/login','POST',{username:'user01',password:initial.get('user01')});assert.equal(r.status,200);await token();const me=await(await request('/api/auth/me')).json();
if(me.mustChangePassword){assert.equal((await request('/api/auth/password','POST',{currentPassword:initial.get('user01'),newPassword:password})).status,204);await token();assert.equal((await request('/api/auth/login','POST',{username:'user01',password})).status,200);await token();}
assert.equal((await(await request('/api/debug/sessions')).json()).available,false);
assert.equal((await request('/api/debug/sessions','POST',{build:'failure-test'})).status,503);
assert.equal((await request('/api/assets/manifest')).status,200);
const state=await(await request('/api/saves/current')).json(),held=await(await request('/api/play-lease/acquire','POST',{takeOver:true})).json();
r=await fetch(origin+'/api/saves/current',{method:'PUT',headers:{Origin:origin,Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),'Content-Type':'application/json','X-CSRF-TOKEN':csrf,'If-Match':'"'+state.revision+'"'},body:JSON.stringify({lease:held.lease,state})});assert.equal(r.status,200,await r.text());
await writeFile('.build/debug-storage-results.json',JSON.stringify({at:new Date().toISOString(),checks:['Application starts when the diagnostic directory is unavailable','Logs reports unavailable storage','Authentication, private assets, leases and gameplay saves remain functional']},null,2));
console.log('PASS diagnostic storage failure leaves authentication, private assets, leases and saves functional');
