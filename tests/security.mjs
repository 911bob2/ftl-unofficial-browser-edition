import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {connect} from 'node:net';
import {once} from 'node:events';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
const origin=process.env.TEST_ORIGIN??'http://localhost:8081';
if(!['http://localhost:8081','http://localhost:8082'].includes(origin))throw Error('This test mutates disposable accounts; use an isolated localhost:8081 or :8082 instance.');
const fixture=process.env.TEST_FIXTURE_DIR??(origin.endsWith(':8082')?'.build/security-data':'.build/test-data');
const deadline=Date.now()+20000;
while(!(await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false))){if(Date.now()>deadline)throw Error('Disposable test instance did not become ready');await new Promise(r=>setTimeout(r,250));}
const credentials=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')));
class Client {
  cookies=new Map();csrf=null;
  async request(path,method='GET',body,extra={},csrf=true){const r=await fetch(origin+path,{method,headers:{Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; '),...(body===undefined?{}:{'Content-Type':'application/json'}),...(method==='GET'||!csrf?{}:{'X-CSRF-TOKEN':this.csrf}),Origin:origin,...extra},body:body===undefined?undefined:JSON.stringify(body)});for(const cookie of r.headers.getSetCookie()){const pair=cookie.split(';')[0];this.cookies.set(pair.slice(0,pair.indexOf('=')),pair.slice(pair.indexOf('=')+1));}return r;}
  async token(){const r=await this.request('/api/auth/csrf');assert.equal(r.status,200);this.csrf=(await r.json()).token;}
  async login(user,password){await this.token();const r=await this.request('/api/auth/login','POST',{username:user,password});assert.equal(r.status,200);await this.token();}
  async json(path,method='GET',body,extra={}){const r=await this.request(path,method,body,extra);assert.ok(r.ok,`${path}: HTTP ${r.status} ${await r.clone().text()}`);return r.status===204?null:r.json();}
}
const checks=[];const check=(name)=>{checks.push(name);console.log('PASS '+name);};
const anonymous=new Client();
for(const path of ['/api/assets/manifest','/api/assets/boot','/api/saves','/api/admin/users'])assert.equal((await anonymous.request(path)).status,401);
check('Anonymous requests cannot read original assets, saves, or admin data');
await anonymous.token();assert.equal((await anonymous.request('/api/auth/login','POST',{username:'admin',password:'invalid'}, {},false)).status,400);
assert.equal((await anonymous.request('/api/auth/login','POST',{username:'admin',password:'invalid'},{Origin:'https://other.example'})).status,403);
check('CSRF and cross-origin mutations are rejected');
const admin=new Client();await admin.login('admin',credentials.get('admin'));
assert.equal((await admin.request('/api/assets/manifest')).status,403);
assert.equal((await admin.json('/api/auth/me')).mustChangePassword,true);
await admin.json('/api/auth/password','POST',{currentPassword:credentials.get('admin'),newPassword:'isolated-admin-test-password-2026'});await admin.login('admin','isolated-admin-test-password-2026');
assert.equal((await admin.json('/api/admin/users')).length,11);
check('Eleven bootstrap accounts exist and initial passwords cannot launch a game');
const player=new Client();await player.login('user10',credentials.get('user10'));
await player.json('/api/auth/password','POST',{currentPassword:credentials.get('user10'),newPassword:'isolated-player-test-password-2026'});await player.login('user10','isolated-player-test-password-2026');
assert.ok(player.cookies.has('ftl-local-'+new URL(origin).port));
assert.ok(player.cookies.has('ftl-csrf-local-'+new URL(origin).port));
const otherPort=new Client();otherPort.cookies.set('ftl-local-8080',player.cookies.get('ftl-local-'+new URL(origin).port));
assert.equal((await otherPort.request('/api/auth/me')).status,401);
assert.equal((await player.request('/api/admin/users')).status,403);
assert.equal((await player.json('/api/auth/me')).mustChangePassword,false);
check('Password rotation and role enforcement');
const fixation=new Client();await fixation.login('user01',credentials.get('user01'));
const stolen=new Client();stolen.cookies=new Map(fixation.cookies);
assert.equal((await fixation.request('/api/auth/login','POST',{username:'user10',password:'isolated-player-test-password-2026'})).status,409);
assert.equal((await stolen.request('/api/auth/me')).status,401);
await fixation.login('user10','isolated-player-test-password-2026');
assert.equal((await fixation.json('/api/auth/me')).username,'user10');
assert.equal((await admin.request('/api/admin/users','POST',{username:null})).status,400);
const adminId=(await admin.json('/api/admin/users')).find(u=>u.userName==='admin').id;
assert.equal((await admin.request('/api/admin/users/'+adminId+'/disable','POST',{disabled:true,version:(await admin.json('/api/admin/users')).find(u=>u.id===adminId).version})).status,400);
check('Session replacement revokes old cookies, malformed accounts are rejected, and the last administrator is protected');
assert.equal((await admin.json('/api/tests')).available,false,'Security fixture must disable process jobs');
for(let i=0;i<5;i++)assert.equal((await admin.request('/api/tests','POST',{suites:['unit']})).status,503);
assert.equal((await admin.request('/api/tests','POST',{suites:['unit']})).status,429);
assert.equal((await player.request('/api/tests','POST',{suites:['unit']})).status,403);
check('Test-start rate limits are enforced per account without spawning fixture jobs');
const manifest=await player.json('/api/assets/manifest');
// Server game files must not stay in the browser's HTTP cache after the account signs out or loses access.
{const audio=await player.request('/api/assets/audio/'+manifest.audio[0].hash);assert.equal(audio.status,200);assert.equal(audio.headers.get('cache-control'),'private, no-store');await audio.arrayBuffer();}
// Save model 2 (docs/SAVES.md): per-save revisions and locks; tests/saves-v2.mjs covers the full API.
const saveId=crypto.randomUUID(),tab=instance=>({'X-FTL-Instance':instance,'X-FTL-Device':'browser;security-fixture;Security fixture'});
const save={name:'Security',setup:{key:'vanilla',mods:[]},meta:{},run:'<xftlSaveGame test="security-fixture"/>'};
const simultaneous=await Promise.all(Array.from({length:6},()=>player.request('/api/saves/'+saveId,'PUT',save,{...tab('tab-a'),'If-Match':'"0"'})));
assert.deepEqual(simultaneous.map(r=>r.status).sort(),[200,409,409,409,409,409]);
assert.equal((await simultaneous.find(r=>r.status===200).json()).revision,1);
assert.equal((await player.request('/api/saves/'+saveId,'PUT',save,{...tab('tab-a'),'If-Match':'"0"'})).status,409);
assert.equal((await player.request('/api/saves/'+saveId,'PUT',{...save,run:'<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><xftlSaveGame>&e;</xftlSaveGame>'},{...tab('tab-a'),'If-Match':'"1"'})).status,400);
const deepXml = '<xftlSaveGame>' + '<a>'.repeat(70) + '</a>'.repeat(70) + '</xftlSaveGame>';
assert.equal((await player.request('/api/saves/'+saveId,'PUT',{...save,run:deepXml},{...tab('tab-a'),'If-Match':'"1"'})).status,400);
assert.equal((await player.json('/api/saves/'+saveId+'/snapshots/1')).revision,1);
assert.equal((await player.json('/api/saves/'+saveId+'/snapshots')).length,1);
check('Save revision conflicts, XML entity and depth rejection, and recovery snapshots');
const held=await player.json('/api/saves/'+saveId+'/lock','POST',{override:false},tab('tab-a'));
const sameCookieTab=new Client();sameCookieTab.cookies=new Map(player.cookies);await sameCookieTab.token();
assert.equal((await sameCookieTab.request('/api/saves/'+saveId+'/lock','POST',{override:false},tab('tab-b'))).status,409);
assert.equal((await player.json('/api/saves/'+saveId+'/lock','POST',{override:false},tab('tab-a'))).since,held.since);
check('Shared-cookie tabs need an override while the holding tab relocks idempotently');
const second=new Client();await second.login('user10','isolated-player-test-password-2026');
assert.equal((await second.request('/api/saves/'+saveId+'/lock','POST',{override:false},tab('tab-c'))).status,409);
await second.json('/api/saves/'+saveId+'/lock','POST',{override:true},tab('tab-c'));
for(const r of [await player.request('/api/saves/'+saveId+'/lock','POST',{override:false},tab('tab-a')),await player.request('/api/saves/'+saveId,'PUT',save,{...tab('tab-a'),'If-Match':'"1"'})])assert.equal((await r.json()).code,'lock_overridden');
assert.equal((await player.request('/api/saves/'+saveId+'/unlock','POST',undefined,tab('tab-a'))).status,409);
await second.json('/api/saves/'+saveId,'PUT',{...save,run:null},{...tab('tab-c'),'If-Match':'"1"'});
check('A second session cannot silently overwrite an active flight');
const other=new Client();await other.login('user09',credentials.get('user09'));await other.json('/api/auth/password','POST',{currentPassword:credentials.get('user09'),newPassword:'isolated-other-test-password-2026'});await other.login('user09','isolated-other-test-password-2026');
assert.deepEqual((await other.json('/api/saves')).saves,[]);assert.equal((await other.request('/api/saves/'+saveId)).status,404);assert.equal((await other.request('/api/saves/'+saveId+'/snapshots/1')).status,404);
assert.equal((await other.request('/api/saves/'+saveId,'PUT',save,{...tab('tab-x'),'If-Match':'"2"'})).status,404);
const users=await admin.json('/api/admin/users');const victim=users.find(u=>u.userName==='user09');await admin.json('/api/admin/users/'+victim.id+'/disable','POST',{disabled:true,version:victim.version});assert.equal((await other.request('/api/auth/me')).status,401);
check('Account save isolation and immediate session revocation on disable');
const lock=new Client();await lock.token();for(let i=0;i<5;i++)assert.equal((await lock.request('/api/auth/login','POST',{username:'user10',password:'wrong-password'})).status,401);
assert.equal((await lock.request('/api/auth/login','POST',{username:'user10',password:'isolated-player-test-password-2026'})).status,401);
assert.equal((await player.request('/api/auth/me')).status,200);
assert.equal((await second.request('/api/auth/me')).status,200);
check('Failed passwords block new sign-ins while established sessions remain usable');
const userId=(await player.json('/api/auth/me')).id;
const assembly=process.env.SECURITY_TEST_ASSEMBLY??(process.platform==='win32'
  ?fileURLToPath(new URL('./server-security/bin/Debug/net10.0/Ftl.SecurityTests.dll',import.meta.url)):'/app/security-tests/Ftl.SecurityTests.dll');
await promisify(execFile)('dotnet',[assembly,'expire-user-sessions',resolve(fixture,'ftl.sqlite'),userId]);
const expired=await Promise.all(Array.from({length:20},()=>player.request('/api/auth/me')));
assert.ok(expired.every(r=>r.status===401));
assert.equal((await admin.request('/api/auth/me')).status,200); // Administrator remains independent.
// The session list was removed from the product: abandoned sessions are purged server-side instead.
assert.equal((await admin.request('/api/auth/sessions')).status,404);assert.equal((await admin.request('/api/auth/sessions/'+userId,'DELETE')).status,404);
check('Real expired session cookies return 401 across concurrent requests without affecting other accounts');
// An anonymous upload that trickles in must not hold the write gate shared by every account's saves and sign-ins.
const slow=connect(Number(new URL(origin).port),'127.0.0.1');await once(slow,'connect');
slow.write(`POST /api/onboarding/consent HTTP/1.1\r\nHost: ${new URL(origin).host}\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 60000\r\n\r\n`);
const drip=setInterval(()=>slow.write('a'.repeat(300)),1000);
try{await new Promise(r=>setTimeout(r,1500));const write=await Promise.race([admin.request('/api/onboarding/consent','POST'),new Promise(r=>setTimeout(()=>r({status:'still queued after 5 s'}),5000))]);
 assert.equal(write.status,200,'A write behind a slowly sent body');}
finally{clearInterval(drip);slow.destroy();}
assert.equal((await anonymous.request('/api/onboarding/consent','POST',{padding:'x'.repeat(70000)})).status,413);
assert.equal((await admin.request('/api/admin/users','POST',{username:'x'.repeat(70000)})).status,413);
check('Slow or oversized request bodies are refused or received before they queue for the shared write gate');
const headers=(await anonymous.request('/')).headers;assert.ok(headers.get('content-security-policy').includes("frame-ancestors 'none'"));assert.equal(headers.get('x-content-type-options'),'nosniff');
check('Browser security headers');
await writeFile('.build/security-results.json',JSON.stringify({at:new Date().toISOString(),origin,checks,assetVersion:manifest.assetVersion},null,2));
console.log(`${checks.length} security checks passed. Browser test account: user10 (isolated instance only).`);
