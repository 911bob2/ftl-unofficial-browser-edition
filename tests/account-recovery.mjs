import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdtemp,mkdir,readFile,writeFile,rm,readdir} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {resolve,join,dirname} from 'node:path';
import {DatabaseSync} from 'node:sqlite';
const scratch=await mkdtemp(join(tmpdir(),'ftl-account-recovery-')),data=join(scratch,'data'),assets=join(scratch,'assets'),origin='http://localhost:8087';
const application=process.env.ACCOUNT_FIXTURE_DLL??'/app/Ftl.Server.dll',appDir=dirname(application),pause=ms=>new Promise(r=>setTimeout(r,ms));
let child,db;let output='';
async function start(){child=spawn('dotnet',[application],{cwd:appDir,env:{...process.env,ASPNETCORE_WEBROOT:join(scratch,'wwwroot'),ASPNETCORE_URLS:origin,ASPNETCORE_ENVIRONMENT:'Development',PUBLIC_ORIGIN:origin,FTL_DATA_DIR:data,FTL_ASSETS_DIR:assets,FTL_TEST_RUNNER_ENABLED:'false'},stdio:['ignore','pipe','pipe']});child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);for(let i=0;;i++){if(await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false))return;if(child.exitCode!==null||i===120)throw Error('Disposable account recovery fixture did not start: '+output.slice(-2000));await pause(250);}}
async function stop(){if(!child||child.exitCode!==null)return;child.kill('SIGTERM');await new Promise(r=>{child.once('exit',r);setTimeout(()=>{child.kill('SIGKILL');r();},5000).unref();});}
class Client{
 cookies=new Map();
 async request(path,method='GET',body){const csrf=method==='GET'?null:(await(await this.request('/auth/csrf')).json()).token;const r=await fetch(origin+'/api'+path,{method,headers:{Origin:origin,Cookie:[...this.cookies].map(([k,v])=>k+'='+v).join('; '),...(body?{'Content-Type':'application/json'}:{}),...(csrf?{'X-CSRF-TOKEN':csrf}:{})},body:body?JSON.stringify(body):undefined});for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0],i=pair.indexOf('=');this.cookies.set(pair.slice(0,i),pair.slice(i+1));}return r;}
 async json(...args){const r=await this.request(...args);assert.ok(r.ok,args[0]+' HTTP '+r.status);return r.status===204?null:r.json();}
 async login(username,initial){await this.json('/auth/login','POST',{username,password:initial});await this.json('/auth/password','POST',{currentPassword:initial,newPassword:'fixture-recovery-password-'+username});await this.json('/auth/login','POST',{username,password:'fixture-recovery-password-'+username});}
}
try{
 await mkdir(data);await mkdir(assets);await mkdir(join(scratch,'wwwroot'));await writeFile(join(assets,'boot.pack'),'synthetic fixture');await writeFile(join(assets,'manifest.json'),JSON.stringify({assetVersion:'synthetic-account-fixture',engineBuild:'test',audio:[]}));await start();
 const credentials=new Map((await readFile(join(data,'bootstrap-credentials.txt'),'utf8')).trim().split(/\r?\n/).map(l=>l.split('\t'))),admin=new Client();await admin.login('admin',credentials.get('admin'));
 db=new DatabaseSync(join(data,'ftl.sqlite'));db.exec('PRAGMA busy_timeout=5000;');
 const user=async name=>(await admin.json('/admin/users')).find(u=>u.userName===name);
 const created=await admin.json('/admin/users','POST',{username:'delete-fixture',role:'external-user'}),victim=new Client();await victim.login(created.username,created.temporaryPassword);
 const info=await user(created.username),state=await victim.json('/saves/current'),lease=await victim.json('/play-lease/acquire','POST',{takeOver:true});
 const csrf=(await victim.json('/auth/csrf')).token;const saved=await fetch(origin+'/api/saves/current',{method:'PUT',headers:{Origin:origin,Cookie:[...victim.cookies].map(([k,v])=>k+'='+v).join('; '),'Content-Type':'application/json','X-CSRF-TOKEN':csrf,'If-Match':'"0"'},body:JSON.stringify({lease:lease.lease,state:{...state,run:'<xftlSaveGame/>',profile:'<xftl-profile/>'}})});assert.ok(saved.ok);
 const log=await victim.json('/debug/sessions','POST',{build:'fixture'});await victim.json('/debug/sessions/'+log.id+'/batches','POST',{records:[{sequence:1,eventName:'synthetic.fixture',payload:{safe:true}}]});
 const metadata=join(data,'debug-logs',log.id+'.json'),content=await readFile(metadata);await rm(metadata);await mkdir(metadata);
 const deletion=await admin.request('/admin/users/'+info.id,'DELETE',{confirmUsername:info.userName,version:info.version});assert.equal(deletion.status,202);await pause(2300);assert.equal((await user(info.userName)).lifecycle,'deleting');assert.equal((await victim.request('/saves/current')).status,401);
 assert.equal(db.prepare('SELECT COUNT(*) AS n FROM Settings WHERE Key=?').get('delete-account:'+info.id).n,1);await stop();
 await rm(metadata,{recursive:true});await writeFile(metadata,content);await start();
 for(let i=0;db.prepare('SELECT COUNT(*) AS n FROM AspNetUsers WHERE Id=?').get(info.id).n;i++){if(i===80)throw Error('Restart cleanup did not complete');await pause(100);}
 for(const [table,column] of [['AspNetUsers','Id'],['AspNetUserRoles','UserId'],['Sessions','UserId'],['Leases','UserId'],['Saves','UserId'],['Snapshots','UserId']])assert.equal(db.prepare('SELECT COUNT(*) AS n FROM '+table+' WHERE '+column+'=?').get(info.id).n,0,table+' retained deleted account data');
 assert.equal((await readdir(join(data,'debug-logs'))).filter(n=>n.startsWith(log.id)).length,0);assert.ok(db.prepare('SELECT COUNT(*) AS n FROM Audits WHERE SubjectId=? AND Action=?').get(info.id,'user-deleted').n>0);
 console.log('PASS Interrupted deletion retries after restart, removes all live account data, and retains minimal audit');
 // A fresh user with conflicting roles cannot play or inherit administrator permissions.
 const conflict=await admin.json('/admin/users','POST',{username:'conflicting-fixture',role:'user'}),conflicting=new Client();await conflicting.login(conflict.username,conflict.temporaryPassword);const conflictInfo=await user(conflict.username);db.prepare('INSERT INTO AspNetUserRoles(UserId,RoleId) SELECT ?,Id FROM AspNetRoles WHERE Name=?').run(conflictInfo.id,'Admin');assert.equal((await conflicting.request('/game/config')).status,403);assert.equal((await conflicting.request('/admin/users')).status,403);
 db.prepare('DELETE FROM AspNetUserRoles WHERE UserId=? AND RoleId IN (SELECT Id FROM AspNetRoles WHERE Name=?)').run(conflictInfo.id,'Admin');
 // Reduce this disposable fixture to two enabled administrators and race demotions.
 const second=await admin.json('/admin/users','POST',{username:'second-admin',role:'admin'}),other=new Client();await other.login(second.username,second.temporaryPassword);
 const self=await user('admin'),secondInfo=await user(second.username);
 const results=await Promise.all([admin.request('/admin/users/'+secondInfo.id+'/role','PATCH',{role:'user',version:secondInfo.version}),other.request('/admin/users/'+self.id+'/role','PATCH',{role:'user',version:self.version})]);const statuses=results.map(r=>r.status);assert.equal(statuses.filter(s=>s===204).length,1);assert.ok(statuses.some(s=>[400,401,403].includes(s)));
 assert.equal(db.prepare('SELECT COUNT(*) AS n FROM AspNetUsers u JOIN AspNetUserRoles ur ON u.Id=ur.UserId JOIN AspNetRoles r ON ur.RoleId=r.Id WHERE r.Name=? AND u.Disabled=0').get('Admin').n,1);
 console.log('PASS Conflicting roles fail closed; concurrent demotions leave one enabled administrator');
}finally{await stop();db?.close();assert.equal(dirname(resolve(scratch)),resolve(tmpdir()));assert.ok(scratch.split(/[\\/]/).at(-1).startsWith('ftl-account-recovery-'));await rm(scratch,{recursive:true,force:true});}
