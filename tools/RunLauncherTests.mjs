import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
const suites=process.argv[2]?.split(',')??[];
// Optional third argument: run only these scripts of the chosen suites, e.g. `menus local-mode`.
const only=process.argv[3]?.split(',');
const scripts={unit:['audio','frame-pacer','diagnostic-export','resource-cache','dat'],security:['server-security','security','accounts','account-recovery','onboarding'],menus:['session-ui','local-mode','menus','tutorial-ui','launcher','diagnostics-ui','diagnostics-assets-ui','resource-cache-ui'],diagnostics:['diagnostics'],storage:['debug-storage'],production:['production'],engine:['baseline']};
if(!suites.length||suites.some(s=>!Object.hasOwn(scripts,s)))throw Error('Invalid test suite');
const root=process.cwd(),children=new Set();
const pause=ms=>new Promise(r=>setTimeout(r,ms));
// A new instance has exactly one account, `admin`. Fixtures that need ordinary players create them offline through the
// server's own provisioning command (temporary passwords land in the fixture's bootstrap-credentials.txt).
const fixtureAccounts=Array.from({length:10},(_,i)=>'user'+String(i+1).padStart(2,'0')+':user');
async function fixture(port,directory,production=false,unavailable=false) {
 const data=join(root,directory);await mkdir(data,{recursive:true});
 if(unavailable)await writeFile(join(data,'debug-logs'),'Deliberately unavailable diagnostic storage');
 const env={...process.env,ASPNETCORE_URLS:'http://127.0.0.1:'+port,ASPNETCORE_ENVIRONMENT:production?'Production':'Development',FTL_DATA_DIR:data,FTL_ASSETS_DIR:'/assets',PUBLIC_ORIGIN:production?'https://ftl-test.example':'http://localhost:'+port,TRUSTED_PROXIES:'127.0.0.1',FTL_DEBUG_TOTAL_BYTES:'2097152',FTL_DEBUG_SEGMENT_BYTES:'524288',FTL_TEST_RUNNER_ENABLED:'false',FTL_REGISTER_LIMIT:'200',FTL_LOGIN_LIMIT:'500',FTL_PUBLIC_LIMIT:'5000'};
 if(!production){
  const code=await new Promise(resolve=>{const provisioning=spawn('dotnet',['/app/Ftl.Server.dll','create-accounts',...fixtureAccounts],{cwd:'/app',stdio:'ignore',env});provisioning.on('error',()=>resolve(1));provisioning.on('exit',resolve);});
  if(code!==0)throw Error('Could not create the fixture accounts');
 }
 const child=spawn('dotnet',['/app/Ftl.Server.dll'],{cwd:'/app',stdio:'ignore',env});
 children.add(child);child.on('exit',()=>children.delete(child));
 const deadline=Date.now()+30000;
 while(!await fetch('http://localhost:'+port+'/health/ready').then(r=>r.ok).catch(()=>false)) {if(child.exitCode!==null||Date.now()>deadline)throw Error('Disposable fixture did not start on '+port);await pause(250);}
 return {data,child};
}
async function stop(child){if(child.exitCode!==null)return;child.kill('SIGTERM');await new Promise(resolve=>{child.once('exit',resolve);setTimeout(()=>{child.kill('SIGKILL');resolve();},2000).unref();});}
async function display(){
 const child=spawn('Xvfb',['-displayfd','1','-screen','0','1440x1000x24','-nolisten','tcp'],{stdio:['ignore','pipe','ignore']});children.add(child);child.on('exit',()=>children.delete(child));
 return new Promise((resolve,reject)=>{const timeout=setTimeout(()=>{child.kill();reject(Error('Test display did not start'));},10000);child.once('error',reject);child.stdout.once('data',data=>{clearTimeout(timeout);const n=data.toString().trim();if(!/^\d+$/.test(n))return reject(Error('Invalid test display'));resolve(':'+n);});});
}
async function audioSink(){
 // Firefox needs an output device even when Tests run without host audio.
 // A private Unix socket and null sink keep playback clocks real and isolated.
 const directory='/tmp/ftl-audio-'+process.pid;await mkdir(directory,{recursive:true,mode:0o700});
 const socket=join(directory,'native');
 const child=spawn('pulseaudio',['--daemonize=no','--exit-idle-time=-1','--log-target=stderr','-n','--load=module-native-protocol-unix socket='+socket,'--load=module-null-sink sink_name=ftl_tests'],{stdio:'ignore',env:{...process.env,XDG_RUNTIME_DIR:directory}});
 children.add(child);child.on('exit',()=>children.delete(child));
 const deadline=Date.now()+10000;
 while(!await import('node:fs/promises').then(fs=>fs.access(socket).then(()=>true,()=>false))) {
  if(child.exitCode!==null||Date.now()>deadline)throw Error('Isolated test audio sink did not start');await pause(100);
 }
 return 'unix:'+socket;
}
function command(script,env) {return new Promise((resolve,reject)=>{const child=spawn(script==='server-security'?'dotnet':'/usr/local/bin/node',script==='server-security'?['/app/security-tests/Ftl.SecurityTests.dll']:['/app/tests/'+script+'.mjs'],{cwd:root,env:{...process.env,...env},stdio:['ignore','pipe','pipe']});children.add(child);child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);child.on('error',reject);child.on('exit',code=>{children.delete(child);resolve(code??1);});});}
try {
 await mkdir('.build',{recursive:true});
 if(suites.includes('security'))await fixture(8081,'.build/test-data');
 if(suites.includes('storage'))await fixture(8085,'.build/debug-unavailable-data',false,true);
 const productionData=suites.includes('production')?await fixture(8086,'.build/production-data',true):null;
 const screen=suites.includes('menus')?await display():null;
 const pulse=suites.includes('menus')?await audioSink():null;
 let failures=0;
 for(const suite of suites) {
  console.log('BEGIN '+suite);const started=Date.now();let failed=false;
  for(const script of scripts[suite].filter(name=>!only||only.includes(name))){
   const testFixture=['session-ui','accounts','onboarding','local-mode','menus','tutorial-ui','launcher','diagnostics-ui','diagnostics-assets-ui','resource-cache-ui','diagnostics','baseline'].includes(script)?await fixture(8084,'.build/fixture-'+script):null;
   try{if(await command(script,{ACCOUNT_TEST_ORIGIN:'http://localhost:8084',PRODUCTION_TEST_TARGET:'http://127.0.0.1:8086',PRODUCTION_FIXTURE_DIR:productionData?.data??'',PRODUCTION_RESULTS_DIR:join(root,'.build'),DEBUG_FIXTURE_DIR:testFixture?.data??'',LAUNCHER_TEST_JOBS:'0',RUNNER_FIREFOX_WINDOWED:screen?'1':'0',...(screen?{DISPLAY:screen,PULSE_SERVER:pulse}:{})}))failed=true;}
   finally{if(testFixture)await stop(testFixture.child);}
  }
  if(failed)failures++;
  console.log('FTL_TEST '+JSON.stringify({suite,status:failed?'failed':'passed',durationMs:Date.now()-started}));
 }
 process.exitCode=failures?1:0;
}finally {
 for(const child of children)child.kill('SIGTERM');
 await Promise.all([...children].map(child=>new Promise(resolve=>{if(child.exitCode!==null)return resolve();child.once('exit',resolve);setTimeout(()=>{child.kill('SIGKILL');resolve();},2000).unref();})));
}
