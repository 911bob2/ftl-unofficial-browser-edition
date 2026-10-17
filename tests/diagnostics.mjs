import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {randomBytes,randomUUID} from 'node:crypto';
import {chromium} from 'playwright';
import {unzipSync,strFromU8} from '../web/vendor/fflate-0.8.3.mjs';
const origin='http://localhost:8084',fixture=process.env.DEBUG_FIXTURE_DIR??'.build/debug-data',checks=[];
const pass=name=>{checks.push(name);console.log('PASS '+name);};
async function waitUntil(check,label,timeout=30000){const end=Date.now()+timeout;while(!await check()){assert.ok(Date.now()<end,'Timed out waiting for '+label);await new Promise(r=>setTimeout(r,250));}}
const initial=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const licenses=await fetch(origin+'/Licenses');assert.equal(licenses.status,200);const notices=await licenses.text();
for(const name of ['GNU GENERAL PUBLIC LICENSE','GNU AFFERO GENERAL PUBLIC LICENSE','daisyUI-LICENSE.txt','Tailwind-LICENSE.txt','fflate-LICENSE.txt','Roboto-LICENSE.txt'])assert.ok(notices.includes(name));
assert.equal((await fetch(origin+'/api/assets/manifest')).status,401);pass('License notices are readable without exposing private game assets');
class Client {
  cookies=new Map();csrf='';
  async request(path,method='GET',body,headers={}){const r=await fetch(origin+'/api'+path,{method,headers:{Origin:origin,Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...(method!=='GET'?{'X-CSRF-TOKEN':this.csrf}:{}),...(body?{'Content-Type':'application/json'}:{}),...headers},body:body?JSON.stringify(body):undefined});for(const cookie of r.headers.getSetCookie()){const pair=cookie.split(';')[0],index=pair.indexOf('=');this.cookies.set(pair.slice(0,index),pair.slice(index+1));}return r;}
  async json(...args){const r=await this.request(...args);assert.ok(r.ok,`${args[0]} returned ${r.status}: ${await r.clone().text()}`);return r.status===204?null:r.json();}
  async token(){this.csrf=(await this.json('/auth/csrf')).token;}
  async login(name){await this.token();let password='diagnostic-fixture-'+name+'-password';let r=await this.request('/auth/login','POST',{username:name,password});if(r.status===401)r=await this.request('/auth/login','POST',{username:name,password:initial.get(name)});assert.equal(r.status,200);await this.token();if((await this.json('/auth/me')).mustChangePassword){await this.json('/auth/password','POST',{currentPassword:initial.get(name),newPassword:password});await this.token();await this.json('/auth/login','POST',{username:name,password});await this.token();}return password;}
}
const user=new Client(),other=new Client();const password=await user.login('user01');await other.login('user02');
await user.json('/debug/sessions','DELETE');await other.json('/debug/sessions','DELETE');
assert.equal((await new Client().request('/debug/sessions')).status,401);
const s=await user.json('/debug/sessions','POST',{build:'test',device:'fixture'});
const r={sequence:1,eventName:'issue.mark',category:'diagnostic',severity:'error',timestamp:'2026-10-02T12:00:00Z',payload:{password:'secret',leaseToken:'hidden',weapon:'LASER_BURST_3'}};
assert.equal((await user.json('/debug/sessions/'+s.id+'/batches','POST',{records:[r]})).acknowledgedSequence,1);
await user.json('/debug/sessions/'+s.id+'/batches','POST',{records:[r]});
const records=await user.json('/debug/sessions/'+s.id+'/records');assert.equal(records.records.length,1);assert.equal(records.records[0].record.payload.password,'[redacted]');assert.equal(records.records[0].record.payload.leaseToken,'[redacted]');pass('Ordered ingestion deduplicates retries and redacts credential fields');
for(const [path,method,body] of [['/records','GET'],['/export','GET'],['/batches','POST',{records:[r]}],['/finish','POST'],['','DELETE']])assert.equal((await other.request('/debug/sessions/'+s.id+path,method,body)).status,404);
assert.equal((await other.json('/debug/sessions')).sessions.length,0);pass('All diagnostic access is scoped to the authenticated account');
assert.equal((await other.request('/debug/sessions','POST',{build:'foreign',continuationOf:s.id})).status,400);
const checkpoint=randomUUID(),xml='<xftl-save><test>checkpoint</test></xftl-save>';
await user.json('/debug/sessions/'+s.id+'/batches','POST',{records:[
  {sequence:2,eventName:'checkpoint.start',payload:{id:checkpoint}},
  {sequence:3,eventName:'checkpoint.part',payload:{id:checkpoint,name:'run',part:0,parts:1,data:xml}},
  {sequence:4,eventName:'checkpoint.end',payload:{id:checkpoint}}
]});
assert.equal((await user.json('/debug/sessions/'+s.id+'/records?markers=true&severity=error&category=diagnostic&from=2026-10-02T11:00:00Z&to=2026-10-02T13:00:00Z')).records.length,1);
assert.equal((await user.json('/debug/sessions/'+s.id+'/records?markers=true&from=2026-10-02T13:00:00Z')).records.length,0);pass('Time, severity, subsystem and issue-marker filters select only matching records');
const archive=await user.request('/debug/sessions/'+s.id+'/export');assert.equal(archive.status,200);const files=unzipSync(new Uint8Array(await archive.arrayBuffer()));assert.equal(JSON.parse(strFromU8(files['metadata.json'])).id,s.id);assert.equal(strFromU8(files['checkpoints/'+checkpoint.replaceAll('-','')+'/run.xml']),xml);assert.ok(files['summary.txt']);pass('Diagnostic ZIP includes metadata, structured records and complete checkpoint files');
await user.json('/debug/sessions/'+s.id+'/finish','POST');
const segment=await user.json('/debug/sessions','POST',{build:'segment',device:'fixture'});
for(let sequence=1;sequence<=2;sequence++)await user.json('/debug/sessions/'+segment.id+'/batches','POST',{records:[{sequence,eventName:'segment',payload:{text:randomBytes(180000).toString('base64')}}]});
const full=await user.request('/debug/sessions/'+segment.id+'/batches','POST',{records:[{sequence:3,eventName:'segment',payload:{text:randomBytes(180000).toString('base64')}}]});assert.equal(full.status,409);assert.equal((await full.json()).code,'session_full');
const continuation=await user.json('/debug/sessions','POST',{build:'segment',device:'fixture',continuationOf:segment.id});
await user.json('/debug/sessions/'+continuation.id+'/batches','POST',{records:[{sequence:3,eventName:'continued'}]});await user.json('/debug/sessions/'+segment.id+'/finish','POST');
const rotated=await user.json('/debug/sessions');assert.equal(rotated.sessions.find(x=>x.id===continuation.id).continuationOf,segment.id);assert.ok(rotated.sessions.every(x=>x.bytes<=524288));pass('A full session rejects unacknowledged records and continues within its segment limit');
const sessions=await Promise.all(Array.from({length:8},()=>user.json('/debug/sessions','POST',{build:'quota',device:'fixture'})));
await Promise.all(sessions.map(async(s,i)=>{const text=randomBytes(180000).toString('base64');await user.json('/debug/sessions/'+s.id+'/batches','POST',{records:[{sequence:1,eventName:'quota',payload:{text}}]});}));
const usage=await user.json('/debug/sessions');assert.ok(usage.usedBytes<=2097152);pass('Concurrent ingestion enforces the configured instance ceiling');
await user.json('/debug/sessions','DELETE');
const browser=withOnboarding(await chromium.launch({headless:true}),origin);const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];page.setDefaultTimeout(15000);page.on('pageerror',e=>{errors.push(e.message);console.log('PAGE ERROR',e.message);});page.on('console',e=>{if(e.type()==='error')console.log('BROWSER ERROR',e.text());});
try {
console.log('UI sign-in');
await page.goto(origin);await page.locator('#login-form [name=username]').fill('user01');await page.locator('#login-form [name=password]').fill(password);await page.locator('#login-form button').click();await page.locator('#launch-panel').waitFor({state:'visible'});
await page.locator('#logs-button').click();await page.locator('#debug-enabled').check();await page.waitForFunction(()=>document.getElementById('debug-status').textContent.includes('Recording'));
console.log('UI launch');
await page.locator('#play-button').click();await page.locator('#launch').click();
await page.waitForFunction(()=>!!window.ftlEngine||!document.getElementById('takeover').hidden,{},{timeout:90000});
if(!await page.evaluate(()=>!!window.ftlEngine))await page.locator('#takeover').click();
await page.waitForFunction(()=>!!window.ftlEngine,{},{timeout:90000});
await page.evaluate(()=>window.ftlEngine.newRun('PLAYER_SHIP_HARD',1));await page.locator('#console').click();await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).console===true);
console.log('UI console typing');
await page.locator('#game').press('r');await page.locator('#game').press('i');await page.locator('#game').press('c');await page.locator('#game').press('h');await page.locator('#game').press('Enter');
await page.waitForFunction(()=>JSON.parse(window.ftlEngine.debugSnapshot()).scrap===5000,{},{timeout:5000});const state=await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()));assert.equal(state.scrap,5000);assert.equal(state.console,true);
await page.evaluate(()=>window.ftlEngine.consoleExecute('save roundtrip'));
await page.evaluate(()=>window.ftlEngine.consoleExecute('poor'));
assert.equal((await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()))).scrap,0);
await page.evaluate(()=>window.ftlEngine.consoleExecute('load roundtrip'));
assert.equal((await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()))).scrap,5000);
if(!await page.evaluate(()=>JSON.parse(window.ftlEngine.debugSnapshot()).console))await page.locator('#console').click();
pass('Named browser console checkpoints restore the run without filesystem commands');
const consoleIs=value=>page.waitForFunction(v=>(JSON.parse(window.ftlEngine.debugSnapshot()).console===true)===v,value);
await page.locator('#console').click();await consoleIs(false);await page.locator('#game').press('Backquote');await consoleIs(true);await page.locator('#console').click();await consoleIs(false);await page.locator('#console').click();await consoleIs(true);await page.locator('#game').press('Backquote');await consoleIs(false);pass('Console button toggles the console and agrees with the physical backquote; console text executes without gameplay hotkeys');
const browserSessionIds=async()=>page.evaluate(async()=>{const r=await fetch('/api/debug/sessions');return (await r.json()).sessions;});
const beforeRotation=await browserSessionIds();
let interruptedFinish=false;
await page.route('**/api/debug/sessions/*/finish',route=>{if(!interruptedFinish){interruptedFinish=true;return route.abort('internetdisconnected');}return route.continue();});
await page.evaluate(texts=>{for(const text of texts)window.ftlDebugEvent('diagnostic','rotation.test',{text});},Array.from({length:5},()=>randomBytes(120000).toString('base64')));
await waitUntil(async()=>(await browserSessionIds()).some(s=>s.continuationOf&&!s.finishedUtc),'active continuation');
const afterRotation=await browserSessionIds();assert.ok(afterRotation.some(s=>s.continuationOf&&!s.finishedUtc),JSON.stringify(afterRotation));assert.ok(afterRotation.every(s=>s.bytes<=524288));assert.ok(beforeRotation.length>0);pass('The browser rotates an active recording into a bounded continuation');
// Keep the test paused so offline diagnostics cannot consume game progression. Opening the Logs panel pauses the game
// and lists the recording; the row's own Export button then exports it while the browser is offline.
await page.locator('#logs-button').click();
const recording=(await browserSessionIds()).filter(s=>s.continuationOf&&!s.finishedUtc).sort((a,b)=>a.createdUtc<b.createdUtc?-1:1).at(-1);
await page.waitForFunction(id=>!!document.querySelector('[data-session="'+id+'"]'),recording.id);
await page.context().setOffline(true);await page.evaluate(()=>window.ftlDebugEvent('diagnostic','offline.test',{marker:'offline-recovery'}));await page.locator('#logs-mark').click();
const download=page.waitForEvent('download');await page.locator('[data-session="'+recording.id+'"]').locator('xpath=ancestor::tr').getByRole('button',{name:'Export'}).click();const offlineExport=await download;
assert.ok(offlineExport.suggestedFilename().endsWith('-offline.zip'));
const offlineFiles=unzipSync(new Uint8Array(await readFile(await offlineExport.path())));
const localRecords=strFromU8(offlineFiles['browser/pending.jsonl']).trim().split('\n').map(line=>JSON.parse(line));
assert.ok(localRecords.some(r=>r.payload?.marker==='offline-recovery'));
assert.equal(JSON.parse(strFromU8(offlineFiles['browser/metadata.json'])).serverIncluded,false);
assert.ok(Object.keys(offlineFiles).some(name=>/browser\/checkpoints\/.*\/run\.xml$/.test(name)));
assert.ok(strFromU8(offlineFiles['browser/summary.txt']).includes('Partial offline export'));pass('Offline export includes pending records and complete current checkpoints with explicit partial-history metadata');
await page.locator('#debug-stop').click();
await page.waitForFunction(()=>!document.getElementById('debug-enabled').checked);
await waitUntil(()=>page.evaluate(()=>new Promise(resolve=>{const r=indexedDB.open('ftl-diagnostics',1);r.onsuccess=()=>{const db=r.result,q=db.transaction('records').objectStore('records').getAll();q.onsuccess=()=>{resolve(q.result.some(x=>x.record.eventName==='offline.test'));db.close();};};})),'queued offline marker',10000);
await page.context().setOffline(false);
await page.evaluate(()=>window.dispatchEvent(new Event('online')));
await page.waitForFunction(()=>document.getElementById('debug-status').textContent==='Stopped',{},{timeout:30000});
await waitUntil(async()=>(await browserSessionIds()).every(s=>s.finishedUtc),'offline session finish');
let retained=await browserSessionIds();assert.ok(retained.every(s=>s.finishedUtc));
assert.ok(interruptedFinish,'The old-session completion request must be interrupted at least once');
let offlineFound=false;for(const s of retained){const data=await user.json('/debug/sessions/'+s.id+'/records?search=offline-recovery');offlineFound ||= data.records.length>0;}assert.ok(offlineFound);pass('Offline records recover in order and a stopped session finishes after acknowledgements');
await page.locator('#debug-enabled').check();await page.waitForFunction(()=>document.getElementById('debug-status').textContent.includes('Recording'));
const activeBeforeReload=(await browserSessionIds()).find(s=>!s.finishedUtc);
await page.reload();await page.locator('#logs-panel').waitFor({state:'visible'});await page.waitForFunction(()=>document.getElementById('debug-enabled').checked);
await waitUntil(async()=>{const data=await user.json('/debug/sessions/'+activeBeforeReload.id+'/records?search=session.resume');return data.records.length>0;},'recording resume',15000);pass('Recording survives a page reload without losing its session or sequence');
await page.locator('#logs-mark').click();await page.locator('#debug-stop').click();await page.waitForFunction(()=>document.getElementById('debug-status').textContent==='Stopped');
await page.locator('#logs-refresh').click();await page.locator('#log-sessions tr').first().waitFor();assert.deepEqual(errors,[]);await page.screenshot({path:'.build/debug-logs.png',fullPage:true});pass('daisyUI navigation, pre-launch recording, issue marker and stop complete without browser errors');
await page.locator('#play-button').click();await page.locator('#logout').click();
await writeFile('.build/debug-results.json',JSON.stringify({at:new Date().toISOString(),checks},null,2));
} finally { await browser.close(); }
