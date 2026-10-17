import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import http from 'node:http';
// Run in a disposable container sharing the test application's network.
// The local client simulates only the headers sent by the trusted HTTPS proxy.
const target=process.env.PRODUCTION_TEST_TARGET??'http://127.0.0.1:8080',origin='https://ftl-test.example';
const headers={Host:'ftl-test.example','X-Forwarded-Proto':'https'};
const checks=[];
function send(path,options={}){return new Promise((resolve,reject)=>{const req=http.request(target+path,{method:options.method??'GET',headers:options.headers},res=>{const chunks=[];res.on('data',b=>chunks.push(b));res.on('end',()=>{const text=Buffer.concat(chunks).toString();resolve({status:res.statusCode,headers:{get:name=>res.headers[name.toLowerCase()],getSetCookie:()=>res.headers['set-cookie']??[]},text:async()=>text,json:async()=>JSON.parse(text)});});});req.on('error',reject);req.end(options.body);});}
const request=(path,options={})=>send(path,{...options,headers:{...headers,...options.headers}});
const ready=Date.now()+30000;
while(!(await fetch(target+'/health/ready').then(r=>r.ok).catch(()=>false))){if(Date.now()>ready)throw Error('Local production fixture did not start');await new Promise(r=>setTimeout(r,250));}
assert.equal((await send('/',{headers:{Host:'ftl-test.example'}})).status,403);
assert.equal((await request('/',{headers:{Host:'wrong.example'}})).status,400);
checks.push('Production rejects plain HTTP and unexpected hosts');
const page=await request('/');assert.equal(page.status,200);assert.equal(page.headers.get('strict-transport-security'),'max-age=31536000');assert.match(await page.text(),/app\.mjs\?v=[a-f0-9]{64}/);
assert.equal((await request('/Diagnostics')).status,403);
checks.push('Trusted forwarded HTTPS, HSTS, content-versioned modules, and authenticated production diagnostics');
assert.equal((await request('/api/assets/manifest')).status,401);
assert.equal((await request('/api/tests')).status,401);
const credentials=new Map((await readFile((process.env.PRODUCTION_FIXTURE_DIR??'/fixture')+'/bootstrap-credentials.txt','utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')));
const cookies=new Map();let token;
async function client(path,body){const r=await request(path,{method:body?'POST':'GET',headers:{Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),Origin:origin,...(body?{'Content-Type':'application/json','X-CSRF-TOKEN':token}:{})},body:body?JSON.stringify(body):undefined});for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0],p=pair.indexOf('=');cookies.set(pair.slice(0,p),pair.slice(p+1));}return r;}
const csrf=await client('/api/auth/csrf');assert.equal(csrf.status,200);token=(await csrf.json()).token;
const csrfCookie=csrf.headers.getSetCookie().find(c=>c.startsWith('__Host-ftl-csrf='));assert.ok(csrfCookie);assert.match(csrfCookie,/; secure/i);
const login=await client('/api/auth/login',{username:'admin',password:credentials.get('admin')});assert.equal(login.status,200);
const cookie=login.headers.getSetCookie().find(c=>c.startsWith('__Host-ftl='));assert.ok(cookie);for(const flag of [/; secure/i,/; httponly/i,/; samesite=strict/i,/; path=\//i])assert.match(cookie,flag);assert.doesNotMatch(cookie,/; domain=/i);
assert.equal((await client('/api/assets/manifest')).status,403);
assert.equal((await client('/Diagnostics')).status,403);
checks.push('Production authentication and CSRF cookies use __Host-, Secure, HttpOnly, Strict, and mandatory rotation');
token=(await(await client('/api/auth/csrf')).json()).token;
const changed=await client('/api/auth/password',{currentPassword:credentials.get('admin'),newPassword:'production-fixture-admin-password'});assert.equal(changed.status,204);
token=(await (await client('/api/auth/csrf')).json()).token;
assert.equal((await client('/api/auth/login',{username:'admin',password:'production-fixture-admin-password'})).status,200);
assert.equal((await client('/Diagnostics')).status,200);
assert.equal((await client('/api/tests')).status,200);
checks.push('Eligible accounts can open Tests and inspect the runner in Production');
// A new instance has exactly one account: the administrator.
assert.deepEqual([...credentials.keys()],['admin']);
const accounts=await (await client('/api/admin/users')).json();assert.equal(accounts.length,1);assert.equal(accounts[0].userName,'admin');assert.equal(accounts[0].role,'admin');
checks.push('A fresh production instance has only the bootstrap administrator');
// First visit, consent, mode choice and public registration use the same __Host- cookie rules.
const firstVisit=await (await request('/')).text();assert.match(firstVisit,/data-panel="consent-panel"/);assert.match(firstVisit,/data-consent="0"/);
const jar=new Map();let jarToken;
async function visitor(path,body){const r=await request(path,{method:body?'POST':'GET',headers:{Cookie:[...jar].map(([k,v])=>k+'='+v).join('; '),Origin:origin,...(body?{'Content-Type':'application/json','X-CSRF-TOKEN':jarToken}:{})},body:body?JSON.stringify(body):undefined});for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0],p=pair.indexOf('=');jar.set(pair.slice(0,p),pair.slice(p+1));}return r;}
jarToken=(await (await visitor('/api/auth/csrf')).json()).token;
const flags=[/; secure/i,/; httponly/i,/; path=\//i];
const consent=await visitor('/api/onboarding/consent',{});assert.equal(consent.status,200);
const consentCookie=consent.headers.getSetCookie().find(c=>c.startsWith('__Host-ftl-consent=1'));assert.ok(consentCookie);for(const flag of [...flags,/; samesite=lax/i,/max-age=34560000/i])assert.match(consentCookie,flag);assert.doesNotMatch(consentCookie,/; domain=/i);
const chosen=await visitor('/api/onboarding/mode',{mode:'cloud'});assert.equal(chosen.status,200);
const modeCookie=chosen.headers.getSetCookie().find(c=>c.startsWith('__Host-ftl-mode=cloud'));assert.ok(modeCookie);for(const flag of [...flags,/; samesite=lax/i,/max-age=34560000/i])assert.match(modeCookie,flag);
assert.match(await (await visitor('/')).text(),/data-panel="login-panel"/);
const registered=await visitor('/api/auth/register',{username:'prod-visitor',password:'production-registration-password'});assert.equal(registered.status,200);
const session=registered.headers.getSetCookie().find(c=>c.startsWith('__Host-ftl='));assert.ok(session);for(const flag of [...flags,/; samesite=strict/i])assert.match(session,flag);assert.doesNotMatch(session,/; domain=/i);
const expires=Date.parse(session.match(/expires=([^;]+)/i)?.[1]??'');assert.ok(expires>Date.now()+29*864e5&&expires<Date.now()+31*864e5,'persistent for about 30 days');
jarToken=(await (await visitor('/api/auth/csrf')).json()).token;assert.equal((await (await visitor('/api/auth/me')).json()).role,'external-user');
checks.push('Production consent, mode and registration cookies are __Host-, Secure and HttpOnly; the sign-in cookie is persistent');
assert.equal((await request('/api/auth/login',{method:'POST',headers:{Origin:'https://wrong.example','Content-Type':'application/json'},body:'{}'})).status,403);
checks.push('Cross-origin authentication mutation is rejected');
const result={at:new Date().toISOString(),checks};await writeFile((process.env.PRODUCTION_RESULTS_DIR??'/results')+'/production-results.json',JSON.stringify(result,null,2));console.log(checks.map(c=>'PASS '+c).join('\n'));
