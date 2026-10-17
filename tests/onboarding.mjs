// Server-side behaviour of first-visit onboarding, public registration, persistent sessions, account deletion and the
// single bootstrap administrator. Disposable instances only: the main fixture (ACCOUNT_TEST_ORIGIN) plus two instances
// this script starts itself for settings that must be fixed at start-up (forced-closed registration, an hourly cap).
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,readFile,rm} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';
import {randomBytes} from 'node:crypto';

const origin=process.env.ACCOUNT_TEST_ORIGIN??'http://localhost:8084',port=new URL(origin).port,fixture=process.env.DEBUG_FIXTURE_DIR;
if(origin!=='http://localhost:8084')throw Error('This test mutates accounts; use the disposable 8084 fixture.');
assert.ok(fixture,'DEBUG_FIXTURE_DIR is required');
const dll=process.env.FTL_SERVER_DLL??'/app/Ftl.Server.dll',assets=process.env.FTL_ASSETS_DIR??'/assets',scratch=resolve(process.env.ONBOARDING_SCRATCH??'.build/onboarding');
const checks=[],check=name=>{checks.push(name);console.log('PASS '+name);};
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const suffix=randomBytes(3).toString('hex'),strong='registration-test-password-'+suffix;

class Client{
 cookies=new Map();csrf=null;setCookies=[];
 constructor(base=origin){this.base=base;}
 async request(path,method='GET',body,extra={},csrf=true){
  const r=await fetch(this.base+path,{method,redirect:'manual',headers:{Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...(body===undefined?{}:{'Content-Type':'application/json'}),...(method==='GET'||!csrf?{}:{'X-CSRF-TOKEN':this.csrf}),Origin:this.base,...extra},body:body===undefined?undefined:JSON.stringify(body)});
  this.setCookies=r.headers.getSetCookie();
  for(const cookie of this.setCookies){const pair=cookie.split(';')[0],at=pair.indexOf('=');if(/;\s*max-age=0/i.test(cookie)||/expires=Thu, 01 Jan 1970/i.test(cookie))this.cookies.delete(pair.slice(0,at));else this.cookies.set(pair.slice(0,at),pair.slice(at+1));}
  return r;
 }
 async token(){const r=await this.request('/api/auth/csrf');assert.equal(r.status,200);this.csrf=(await r.json()).token;}
 async login(user,password){await this.token();const r=await this.request('/api/auth/login','POST',{username:user,password});assert.equal(r.status,200,'login '+user);await this.token();}
 async json(path,method='GET',body,extra={}){const r=await this.request(path,method,body,extra);assert.ok(r.ok,`${path}: HTTP ${r.status} ${await r.clone().text()}`);return r.status===204?null:r.json();}
 setCookie(name){return this.setCookies.find(c=>c.startsWith(name+'='));}
 page(path='/'){return this.request(path).then(r=>r.text());}
}
const tag=(html,id)=>(html.match(new RegExp('<[a-z]+ id="'+id+'"[^>]*>'))??[''])[0];
const hidden=(html,id)=>/\shidden(=|\s|>)/.test(tag(html,id));
const state=html=>({panel:html.match(/data-panel="([^"]*)"/)?.[1],auth:html.match(/data-auth-state="([^"]*)"/)?.[1],consent:html.match(/data-consent="([^"]*)"/)?.[1],mode:html.match(/data-mode="([^"]*)"/)?.[1]});
const cookieNames={consent:'ftl-consent-local-'+port,mode:'ftl-mode-local-'+port,auth:'ftl-local-'+port};
const withCookies=(extra)=>{const c=new Client();for(const [k,v] of Object.entries(extra))c.cookies.set(k,v);return c;};

// ---- Fixture accounts: the administrator and its first sign-in --------------------------------------------------------------
const credentials=new Map((await readFile(join(fixture,'bootstrap-credentials.txt'),'utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')));
const adminPassword='onboarding-admin-password-'+suffix;
const admin=new Client();await admin.login('admin',credentials.get('admin'));await admin.json('/api/auth/password','POST',{currentPassword:credentials.get('admin'),newPassword:adminPassword});await admin.login('admin',adminPassword);

// ---- First visit: the notice, then the choice, driven by two long-lived cookies --------------------------------------------
{
 const visitor=new Client(),first=await visitor.page();
 assert.deepEqual(state(first),{panel:'consent-panel',auth:'signed-out',consent:'0',mode:''});
 assert.equal(hidden(first,'consent-panel'),false);for(const id of ['mode-panel','register-panel','login-panel','launch-panel','account-panel'])assert.equal(hidden(first,id),true,id+' is hidden on the first visit');
 const text=first.slice(first.indexOf('id="consent-panel"'),first.indexOf('id="mode-panel"')).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ');
 assert.match(text,/unofficial fan project/i);assert.match(text,/not made, endorsed or supported by Subset Games/i);assert.match(text,/FTL: Faster Than Light belongs to Subset Games/);
 assert.equal((first.match(/id="consent-agree"/g)??[]).length,1);assert.equal((first.slice(first.indexOf('id="consent-panel"'),first.indexOf('id="mode-panel"')).match(/<button/g)??[]).length,1);
 check('The first visit renders only the fan-project notice, with one Agree button, on the server');
 assert.deepEqual(state(await new Client().page('/?view=about')),{panel:'about-panel',auth:'signed-out',consent:'0',mode:''});
 check('About and licenses stay reachable before consent');

 await visitor.token();
 assert.equal((await visitor.request('/api/onboarding/consent','POST',{},{},false)).status,400);
 assert.equal((await visitor.request('/api/onboarding/consent','POST',{},{Origin:'https://other.example'})).status,403);
 assert.equal((await visitor.request('/api/onboarding/mode','POST',{mode:'cloud'})).status,400);
 const agreed=await visitor.request('/api/onboarding/consent','POST',{});assert.equal(agreed.status,200);
 const cookie=visitor.setCookie(cookieNames.consent);assert.ok(cookie,'consent cookie is set by the server');
 assert.match(cookie,/max-age=34560000/i);assert.match(cookie,/samesite=lax/i);assert.match(cookie,/httponly/i);assert.doesNotMatch(cookie,/;\s*secure/i);
 assert.ok(Math.abs(Date.parse(cookie.match(/expires=([^;]+)/i)[1])-(Date.now()+400*864e5))<864e5,'consent cookie expires in about 400 days');
 check('Consent is stored in a long-lived Lax cookie set by the server, with CSRF and origin checks');
 assert.equal((await visitor.request('/api/onboarding/mode','POST',{mode:'sideways'})).status,400);
 assert.deepEqual(state(await visitor.page()),{panel:'mode-panel',auth:'signed-out',consent:'1',mode:''});
 assert.equal(hidden(await visitor.page(),'mode-panel'),false);
 const cloud=await visitor.request('/api/onboarding/mode','POST',{mode:'cloud'});assert.equal(cloud.status,200);assert.match(visitor.setCookie(cookieNames.mode),/max-age=34560000.*samesite=lax|samesite=lax.*max-age=34560000/i);
 assert.deepEqual(state(await visitor.page()),{panel:'login-panel',auth:'signed-out',consent:'1',mode:'cloud'});
 assert.deepEqual(state(await visitor.page('/?view=register')),{panel:'register-panel',auth:'signed-out',consent:'1',mode:'cloud'});
 const local=await visitor.request('/api/onboarding/mode','POST',{mode:'local'});assert.equal(local.status,200);
 const localHtml=await visitor.page();assert.deepEqual(state(localHtml),{panel:'launch-panel',auth:'local',consent:'1',mode:'local'});
 assert.equal(hidden(localHtml,'account-button'),true);assert.equal(hidden(localHtml,'tests-button'),true);assert.equal(hidden(localHtml,'admin-button'),true);assert.equal(hidden(localHtml,'logout'),true);assert.equal(hidden(localHtml,'enable-cloud'),false);assert.equal(hidden(localHtml,'launch-tests'),true);
 assert.equal(state(await visitor.page('/?view=account')).panel,'launch-panel');assert.equal(state(await visitor.page('/?view=saves')).panel,'saves-panel');assert.equal(state(await visitor.page('/?view=register')).panel,'register-panel');
 check('The mode cookie selects the sign-in screen, the register screen or the local launcher without an account');
}

// ---- Anonymous visitors get what an external user gets, nothing more --------------------------------------------------------
{
 const anonymous=new Client();
 const config=await anonymous.request('/api/public/game/config');assert.equal(config.status,200);const publicConfig=await config.json();
 assert.equal(publicConfig.accountId,null);assert.equal(publicConfig.assetSource,'local');assert.ok(publicConfig.engineBuild&&publicConfig.assetVersion&&publicConfig.resourcesUrl);
 const resources=await anonymous.request(publicConfig.resourcesUrl);assert.equal(resources.status,200);assert.equal((await resources.arrayBuffer()).byteLength,publicConfig.resourcesBytes);
 for(const path of ['/api/game/config','/api/assets/manifest','/api/assets/boot','/api/game/resources','/api/saves/current','/api/saves/snapshots','/api/debug/sessions','/api/admin/users','/api/admin/settings','/api/auth/me'])assert.equal((await anonymous.request(path)).status,401,path+' needs an account');
 check('Anonymous visitors can read only public game metadata and the engine resources; every account API still returns 401');
 globalThis.publicConfig=publicConfig;
}

// ---- Registration ---------------------------------------------------------------------------------------------------------
const username='player-'+suffix;
const player=new Client();
{
 await player.token();
 // CSRF and origin
 assert.equal((await player.request('/api/auth/register','POST',{username,password:strong},{},false)).status,400);
 assert.equal((await player.request('/api/auth/register','POST',{username,password:strong},{Origin:'https://other.example'})).status,403);
 check('Registration requires the antiforgery token and a same-origin request');
 // Validation: username rules are the administrator rules, passwords follow the Identity policy
 for(const bad of ['ab','a'.repeat(33),'has space','bad/name','ünïcode','semi;colon','']){const r=await player.request('/api/auth/register','POST',{username:bad,password:strong});assert.equal(r.status,400,'username '+JSON.stringify(bad));assert.match((await r.json()).error,/username/i);}
 for(const weak of ['short','a'.repeat(14),'a'.repeat(129),'',null]){const r=await player.request('/api/auth/register','POST',{username,password:weak});assert.equal(r.status,400,'password '+JSON.stringify(weak?.length??weak));assert.match((await r.json()).error,/15 to 128/);}
 assert.equal((await player.request('/api/auth/register','POST',{})).status,400);
 check('Registration enforces the account username rules and the password policy');
 // Success: external user, signed in with a persistent cookie
 const created=await player.request('/api/auth/register','POST',{username,password:strong});assert.equal(created.status,200);assert.deepEqual(await created.json(),{mustChangePassword:false});
 const auth=player.setCookie(cookieNames.auth);assert.ok(auth,'registration signs the new account in');
 const expires=Date.parse(auth.match(/expires=([^;]+)/i)?.[1]??'');assert.ok(expires>Date.now()+29*864e5&&expires<Date.now()+31*864e5,'the sign-in cookie is persistent for about 30 days: '+auth.split(';').slice(1).join(';'));
 assert.match(auth,/httponly/i);assert.match(auth,/samesite=strict/i);
 assert.match(player.setCookie(cookieNames.mode)??'',new RegExp(cookieNames.mode+'=cloud'));
 await player.token();const me=await player.json('/api/auth/me');assert.equal(me.username,username);assert.equal(me.role,'external-user');assert.equal(me.mustChangePassword,false);assert.equal(me.canDeleteAccount,true);
 assert.equal((await player.request('/api/assets/manifest')).status,403);
 check('Registration creates an external user who is signed in at once with a persistent session cookie');
 // The page the registered visitor receives
 const html=await player.page();assert.equal(state(html).auth,'authenticated');assert.equal(state(html).panel,'consent-panel','consent is still asked once per browser');
 await player.request('/api/onboarding/consent','POST',{});assert.equal(state(await player.page()).panel,'launch-panel');
 // Generic duplicate message and case-insensitive duplicates
 const taken=[];for(const name of [username,username.toUpperCase(),'admin']){const other=new Client();await other.token();const r=await other.request('/api/auth/register','POST',{username:name,password:strong});assert.equal(r.status,400,name);taken.push((await r.json()).error);assert.equal(other.setCookie(cookieNames.auth),undefined);}
 assert.ok(taken.every(m=>m===taken[0])&&/not available/i.test(taken[0]),'every taken name gets the same generic message');
 check('Taken usernames, including other-case spellings and administrators, get one generic message');
 // Fixation guard: a request that presents an old session cookie is refused after clearing it
 const reuse=new Client();await reuse.login(username,strong);
 assert.equal((await reuse.request('/api/auth/register','POST',{username:'other-'+suffix,password:strong})).status,409);
 check('Registration refuses a request that carries an existing session cookie');
 // Audit
 const audit=await admin.json('/api/admin/audit');assert.ok(audit.some(a=>a.action==='user-registered'&&a.subject===username),'registration is audited');
 check('Registration is written to the security audit');
 // External user gets the same game metadata as an anonymous visitor
 const own=await player.json('/api/game/config'),{accountId,...rest}=own;assert.equal(accountId,me.id);
 const publicView=globalThis.publicConfig;for(const key of Object.keys(publicView).filter(k=>!['accountId','resourcesUrl'].includes(k)))assert.deepEqual(rest[key],publicView[key],'public and external-user config agree on '+key);
 const privateBytes=Buffer.from(await (await player.request(own.resourcesUrl)).arrayBuffer()),publicBytes=Buffer.from(await (await new Client().request(publicView.resourcesUrl)).arrayBuffer());
 assert.ok(privateBytes.length>0&&privateBytes.equals(publicBytes),'both serve the same engine resource bundle');
 check('A local-only visitor and an external user load identical game metadata');
}

// ---- Persistent sessions: password change ends them; sign-out removes them --------------------------------------------------
{
 const second=new Client();await second.login(username,strong);assert.equal((await second.request('/api/auth/me')).status,200);
 const changed='registration-test-changed-'+suffix;
 assert.equal((await player.request('/api/auth/password','POST',{currentPassword:strong,newPassword:changed})).status,204);
 assert.equal((await second.request('/api/auth/me')).status,401,'another persistent session ends when the password changes');
 assert.equal((await player.request('/api/auth/me')).status,401,'and so does the one that changed it');
 const again=new Client();await again.login(username,changed);
 const sessionRows=Number((await admin.json('/api/admin/users')).find(u=>u.userName===username).activeSessions);assert.equal(sessionRows,1,'ended sessions are not counted as active');
 await again.request('/api/auth/logout','POST');assert.equal((await again.request('/api/auth/me')).status,401);assert.equal(again.cookies.has(cookieNames.auth),false,'sign out clears the cookie');
 check('A password change invalidates every persistent session; sign out clears the cookie');
 globalThis.playerPassword=changed;
}

// ---- Account deletion by its owner ----------------------------------------------------------------------------------------
{
 const owner=new Client();await owner.login(username,globalThis.playerPassword);
 assert.equal((await new Client().request('/api/auth/delete-account','POST',{password:'x'})).status,400);
 const noCsrf=await owner.request('/api/auth/delete-account','POST',{password:globalThis.playerPassword},{},false);assert.equal(noCsrf.status,400);
 const wrong=await owner.request('/api/auth/delete-account','POST',{password:'wrong-password-wrong'});assert.equal(wrong.status,400);assert.match((await wrong.json()).error,/incorrect/i);
 assert.equal((await owner.request('/api/auth/me')).status,200,'a wrong password does not sign the account out');
 const gone=await owner.request('/api/auth/delete-account','POST',{password:globalThis.playerPassword});assert.equal(gone.status,202);assert.deepEqual(await gone.json(),{status:'deleting'});
 assert.equal((await owner.request('/api/auth/me')).status,401,'the account is signed out immediately');
 const retry=new Client();await retry.token();assert.equal((await retry.request('/api/auth/login','POST',{username,password:globalThis.playerPassword})).status,401);
 let listed=true;for(let i=0;i<60&&listed;i++){listed=(await admin.json('/api/admin/users')).some(u=>u.userName===username);if(listed)await pause(500);}
 assert.equal(listed,false,'the deletion pipeline removes the account');
 assert.ok((await admin.json('/api/admin/audit')).some(a=>a.action==='user-deletion-requested'),'deletion is audited');
 const reused=new Client();await reused.token();assert.equal((await reused.request('/api/auth/register','POST',{username,password:strong})).status,200,'the username can be registered again after deletion');
 check('Account deletion needs the current password and CSRF, signs out at once and erases the account through the existing pipeline');
 // The final enabled administrator cannot delete themselves; a second administrator lifts the restriction.
 assert.equal((await admin.json('/api/auth/me')).canDeleteAccount,false);
 const refused=await admin.request('/api/auth/delete-account','POST',{password:adminPassword});assert.equal(refused.status,400);assert.match((await refused.json()).error,/final enabled administrator/i);
 assert.equal((await admin.request('/api/auth/me')).status,200);
 const spare=await admin.json('/api/admin/users','POST',{username:'spare-admin-'+suffix,role:'admin'});const second=new Client();await second.login(spare.username,spare.temporaryPassword);
 const spareNew='spare-admin-password-'+suffix;await second.json('/api/auth/password','POST',{currentPassword:spare.temporaryPassword,newPassword:spareNew});await second.login(spare.username,spareNew);
 assert.equal((await admin.json('/api/auth/me')).canDeleteAccount,true);assert.equal((await second.json('/api/auth/me')).canDeleteAccount,true);
 assert.equal((await second.request('/api/auth/delete-account','POST',{password:spareNew})).status,202);
 let spareListed=true;for(let i=0;i<60&&spareListed;i++){spareListed=(await admin.json('/api/admin/users')).some(u=>u.userName===spare.username);if(spareListed)await pause(500);}
 assert.equal(spareListed,false);assert.equal((await admin.json('/api/auth/me')).canDeleteAccount,false);
 check('The final enabled administrator cannot delete themselves; another administrator can');
}

// ---- Administrators can turn public registration off -----------------------------------------------------------------------
{
 const settings=await admin.json('/api/admin/settings');assert.deepEqual(settings,{registrationOpen:true,registrationLocked:false});
 assert.equal((await admin.request('/api/admin/settings/registration','PUT',{open:false})).status,200);
 assert.equal((await admin.json('/api/admin/settings')).registrationOpen,false);
 const closed=new Client();await closed.token();const r=await closed.request('/api/auth/register','POST',{username:'late-'+suffix,password:strong});assert.equal(r.status,403);const body=await r.json();assert.equal(body.code,'registration_closed');assert.match(body.error,/closed/i);
 await closed.request('/api/onboarding/consent','POST',{});await closed.request('/api/onboarding/mode','POST',{mode:'cloud'});
 const page=await closed.page('/?view=register');assert.equal(hidden(page,'register-closed'),false);assert.equal(hidden(page,'register-open'),true);assert.equal(hidden(page,'login-register'),true);assert.equal(hidden(page,'login-closed'),false);
 assert.equal((await new Client().request('/api/admin/settings')).status,401);
 assert.equal((await admin.request('/api/admin/settings/registration','PUT',{open:true})).status,200);
 const reopened=new Client();await reopened.token();assert.equal((await reopened.request('/api/auth/register','POST',{username:'reopened-'+suffix,password:strong})).status,200);
 assert.equal(hidden(await new Client().page('/?view=register'),'register-panel'),true,'consent is still required first');
 const audit=await admin.json('/api/admin/audit');assert.ok(audit.some(a=>a.action==='registration-closed')&&audit.some(a=>a.action==='registration-opened'));
 // An external user cannot read or change the setting.
 await reopened.token();assert.equal((await reopened.request('/api/admin/settings')).status,403);assert.equal((await reopened.request('/api/admin/settings/registration','PUT',{open:false})).status,403);
 check('Administrators can close and reopen public registration; the screens and endpoint say so clearly');
}

// ---- Instances with start-up settings -------------------------------------------------------------------------------------
const children=new Set();
async function start(port,dir,extra={}){
 await mkdir(dir,{recursive:true});
 const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:'Development',FTL_DATA_DIR:dir,FTL_ASSETS_DIR:assets,PUBLIC_ORIGIN:'http://localhost:'+port,TRUSTED_PROXIES:'127.0.0.1',FTL_TEST_RUNNER_ENABLED:'false',...extra};
 for(const key of ['FTL_PUBLIC_LIMIT','FTL_LOGIN_LIMIT','FTL_REGISTER_LIMIT','FTL_PUBLIC_REGISTRATION','FTL_REGISTRATION_HOURLY_LIMIT'])if(!(key in extra))delete env[key];
 const child=spawn('dotnet',[dll],{cwd:dirname(dll),stdio:'ignore',env});children.add(child);child.on('exit',()=>children.delete(child));
 const deadline=Date.now()+40000;while(!await fetch('http://localhost:'+port+'/health/ready').then(r=>r.ok).catch(()=>false)){if(child.exitCode!==null||Date.now()>deadline)throw Error('Instance on '+port+' did not start');await pause(250);}
 return child;
}
async function stop(child){if(child.exitCode!==null)return;child.kill('SIGTERM');await new Promise(resolve=>{child.once('exit',resolve);setTimeout(()=>{child.kill('SIGKILL');resolve();},3000).unref();});}
function provision(dir,names,extra={}){return new Promise((resolve,reject)=>{const child=spawn('dotnet',[dll,'create-accounts',...names],{cwd:dirname(dll),stdio:'ignore',env:{...process.env,FTL_DATA_DIR:dir,FTL_ASSETS_DIR:assets,ASPNETCORE_ENVIRONMENT:'Development',PUBLIC_ORIGIN:'http://localhost:8087',...extra}});child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(Error('create-accounts failed')));});}
try{
 // Registration pinned closed by the environment: the default per-address limit applies, and restarts keep sessions and accounts.
 const closedDir=join(scratch,'closed'),closedPort=8087,closedBase='http://localhost:'+closedPort;await rm(closedDir,{recursive:true,force:true});
 let server=await start(closedPort,closedDir,{FTL_PUBLIC_REGISTRATION:'false'});
 const file=(await readFile(join(closedDir,'bootstrap-credentials.txt'),'utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t'));
 assert.deepEqual(file.map(l=>l[0]),['admin'],'a new instance has exactly one bootstrap account');
 const boss=new Client(closedBase),bossPassword='boss-password-'+suffix;await boss.login('admin',file[0][1]);await boss.json('/api/auth/password','POST',{currentPassword:file[0][1],newPassword:bossPassword});await boss.login('admin',bossPassword);
 const listing=await boss.json('/api/admin/users');assert.equal(listing.length,1);assert.equal(listing[0].userName,'admin');assert.equal(listing[0].role,'admin');
 check('A new instance creates only the admin account and lists only it in the credentials file');
 const locked=await boss.json('/api/admin/settings');assert.deepEqual(locked,{registrationOpen:false,registrationLocked:true});assert.equal((await boss.request('/api/admin/settings/registration','PUT',{open:true})).status,409);
 const guest=new Client(closedBase);await guest.token();await guest.request('/api/onboarding/consent','POST',{});await guest.request('/api/onboarding/mode','POST',{mode:'cloud'});
 assert.equal(hidden(await guest.page('/?view=register'),'register-closed'),false);
 // The register limiter is stricter than the sign-in limiter (20 per 5 minutes): it must trip within a handful of attempts.
 const statuses=[];for(let i=0;i<8;i++)statuses.push((await guest.request('/api/auth/register','POST',{username:'flood-'+i+'-'+suffix,password:strong})).status);
 assert.deepEqual(statuses.slice(0,5),[403,403,403,403,403]);assert.ok(statuses.slice(5).every(s=>s===429),'attempts beyond the limit are rate limited: '+statuses);
 check('Registration pinned closed by the environment cannot be reopened from the panel, and registration attempts are rate limited per address');
 // The default sign-in limit (20 per five minutes and address) still applies on an instance that does not raise it.
 const attempts=[];for(let i=0;i<30;i++)attempts.push((await guest.request('/api/auth/login','POST',{username:'nobody-'+i+'-'+suffix,password:strong})).status);
 assert.equal(attempts[0],401);assert.ok(attempts.includes(429),'sign-in attempts are rate limited: '+attempts);
 const firstLimited=attempts.indexOf(429);assert.ok(firstLimited>=10&&firstLimited<=20,'the limit is 20 per window, including the sign-ins above: '+firstLimited);assert.ok(attempts.slice(firstLimited).every(s=>s===429));
 check('Sign-in attempts are rate limited per address by default');
 // The public endpoints (consent, mode, engine metadata) have their own, generous per-address limit.
 const publicStatuses=[];for(let i=0;i<300;i++){const status=(await fetch(closedBase+'/api/public/game/config')).status;publicStatuses.push(status);if(status===429)break;}
 assert.equal(publicStatuses.at(-1),429,'public endpoints are rate limited');assert.ok(publicStatuses.length>=200,'but the limit allows an ordinary shared address: '+publicStatuses.length);
 check('Public onboarding endpoints are rate limited per address with a limit that fits a shared address');
 // Restart: accounts that exist are untouched, a persistent sign-in cookie still works (data protection keys and session rows survive).
 const cookie=boss.cookies.get(cookieNames.auth.replace(port,String(closedPort)));assert.ok(cookie,'admin cookie');
 await stop(server);await provision(closedDir,['user01:user','user02:external-user'],{FTL_PUBLIC_REGISTRATION:'false'});
 const createdFile=(await readFile(join(closedDir,'bootstrap-credentials.txt'),'utf8')).split(/\r?\n/).filter(l=>l.includes('\t')).map(l=>l.split('\t')[0]);assert.deepEqual(createdFile,['admin','user01','user02']);
 server=await start(closedPort,closedDir,{FTL_PUBLIC_REGISTRATION:'false'});
 const returning=new Client(closedBase);returning.cookies.set(cookieNames.auth.replace(port,String(closedPort)),cookie);
 assert.equal((await returning.request('/api/auth/me')).status,200,'the persistent cookie survives a server restart');
 const after=await returning.json('/api/admin/users');assert.deepEqual(after.map(u=>u.userName).sort(),['admin','user01','user02']);
 assert.equal(after.find(u=>u.userName==='user02').role,'external-user');
 await stop(server);server=await start(closedPort,closedDir,{FTL_PUBLIC_REGISTRATION:'false'});
 assert.equal((await returning.request('/api/auth/me')).status,200,'and a second restart');
 assert.deepEqual((await returning.json('/api/admin/users')).map(u=>u.userName).sort(),['admin','user01','user02'],'restarting never removes existing accounts');
 check('Restarts keep accounts and persistent sessions; offline provisioning adds accounts without touching existing ones');
 await stop(server);

 // The instance-wide hourly cap holds even when no single address trips the per-address limiter.
 const capDir=join(scratch,'cap'),capPort=8088,capBase='http://localhost:'+capPort;await rm(capDir,{recursive:true,force:true});
 server=await start(capPort,capDir,{FTL_REGISTRATION_HOURLY_LIMIT:'2',FTL_REGISTER_LIMIT:'50'});
 const crowd=[];for(let i=0;i<3;i++){const c=new Client(capBase);await c.token();const r=await c.request('/api/auth/register','POST',{username:'crowd-'+i+'-'+suffix,password:strong});crowd.push(r.status);if(r.status===429)assert.match((await r.json()).error,/recently/i);}
 assert.deepEqual(crowd,[200,200,429]);
 check('An instance-wide hourly registration cap limits account flooding');
 await stop(server);
}finally{for(const child of children)child.kill('SIGKILL');}
console.log(`${checks.length} onboarding checks passed.`);
