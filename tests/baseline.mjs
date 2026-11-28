import {hangMs} from './lib/hang.mjs';
import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
import {noPixelsScript,PIXEL_MODES} from './lib/nopixels.mjs';
const origin=process.env.BASELINE_ORIGIN??'http://localhost:8083';if(origin!=='http://localhost:8083'&&origin!=='http://localhost:8084')throw Error('Use an isolated baseline or debug fixture');
const fixture=process.env.DEBUG_FIXTURE_DIR??(origin.endsWith('8083')?'.build/fork-baseline-data':'.build/debug-data'),name='user05',password='baseline-fixture-password-2026';
const initial=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const readyDeadline=Date.now()+hangMs(20000);
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){
  if(Date.now()>readyDeadline)throw Error('Isolated diagnostic instance did not become ready');
  await new Promise(resolve=>setTimeout(resolve,250));
}
const browserName=process.env.BASELINE_BROWSER??'chromium';
const resultsPath=process.env.BASELINE_RESULTS??('.build/'+(origin.endsWith('8083')?'fork-baseline':'fork-current')+'-results.json');
if(!['chromium','firefox'].includes(browserName))throw Error('Unknown diagnostic browser '+browserName);
const type=browserName==='firefox'?firefox:chromium;
const options={headless:!(browserName==='firefox'&&process.env.RUNNER_FIREFOX_WINDOWED==='1')};if(browserName==='chromium')options.channel='chromium';
const browser=withOnboarding(await type.launch(options),origin),page=await browser.newPage();page.setDefaultTimeout(90000);
// FTL_NOPIXELS=1: no rasterising and no texture pixels (tests/lib/nopixels.mjs) unless a selected mode reads the canvas.
const modeList=(process.env.BASELINE_MODES??'vanilla,ae,encounters,mechanics,warnings,gameplay-options,gameplay-parity,progress,combat,campaign,tutorial,tutorial-isolation,tutorial-gates,tutorial-adapter,tutorial-overlay,tutorial-entry,tutorial-encounters,tutorial-play').split(',');
if(process.env.FTL_NOPIXELS==='1'){const reads=modeList.filter(m=>PIXEL_MODES.has(m));if(reads.length&&process.env.FTL_NOPIXELS_FORCE!=='1')console.log('NOPIXELS off: '+reads.join(',')+' reads the canvas');else{await page.addInitScript(noPixelsScript);console.log('NOPIXELS on');}}
page.on('console',event=>{if(event.type()==='error')console.log('BROWSER ERROR',event.text());else if(event.text().startsWith('TEST '))console.log(event.text());});
page.on('pageerror',error=>console.log('PAGE ERROR',error.stack));
try {
  const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me'));
  await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
  await page.evaluate(async({initial,password,name})=>{const token=async()=> (await (await fetch('/api/auth/csrf')).json()).token;let csrf=await token();const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});let r=await post('login',{username:name,password});if(r.status===401)r=await post('login',{username:name,password:initial});if(!r.ok)throw Error('Fixture login failed '+r.status);csrf=await token();const me=await (await fetch('/api/auth/me')).json();if(me.mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('Rotation failed');csrf=await token();await post('login',{username:name,password});}}, {initial:initial.get(name),name,password});
  await page.goto(origin+'/Diagnostics');await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);const results=[];
  for(const mode of modeList){
    // A long list can run in slices, each on a fresh page: BASELINE_ENCOUNTER_BATCH cases at a time, from BASELINE_ENCOUNTER_RANGE ("first,end").
    // ship-sweep and unit-sweep the same way: BASELINE_SHIPSWEEP_BATCH / _RANGE (?shipsweep=<from>,<count>), BASELINE_UNITSWEEP_BATCH / _RANGE (?unitsweep=, its cases' indices).
    const slice={encounters:['ENCOUNTER','encounters'],'ship-sweep':['SHIPSWEEP','shipsweep'],'unit-sweep':['UNITSWEEP','unitsweep']}[mode]??['ENCOUNTER','encounters'];
    const failures=[],batch=mode in {encounters:1,'ship-sweep':1,'unit-sweep':1}?Number(process.env['BASELINE_'+slice[0]+'_BATCH']??0):0,[first,end]=(process.env['BASELINE_'+slice[0]+'_RANGE']??'0,Infinity').split(',').map(Number);
    for(let from=first;from<end;from+=batch){
      // Between slices the old document is left for a blank page and a collection is forced (Chromium): the next slice's
      // engine otherwise starts beside the previous one's garbage in the same process whenever the browser has not parked
      // the old page in its back/forward cache, and four slices then reach the container's memory limit.
      if(batch&&from>first&&browserName==='chromium'){await page.goto('about:blank');const cdp=await page.context().newCDPSession(page);await cdp.send('HeapProfiler.collectGarbage');await cdp.detach();}
      if(batch){await page.goto(origin+'/Diagnostics?'+slice[1]+'='+from+','+Math.min(batch,end-from)+(mode==='ship-sweep'&&process.env.BASELINE_SHIPSWEEP_TRACE?'&shipsweeptrace=1':''));await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);}
      // BASELINE_CAMPAIGN=ae or vanilla: one half of the campaign, on a fresh page (its own engine start).
      if(mode==='campaign'&&process.env.BASELINE_CAMPAIGN){await page.goto(origin+'/Diagnostics?campaign='+encodeURIComponent(process.env.BASELINE_CAMPAIGN));await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);}
      await page.locator('#test-'+mode).click();
      const finished=()=>/DONE (passed|failed|cancelled)\s*$/.test(document.getElementById('results').textContent)&&document.getElementById('stop-tests').disabled;
      if(mode==='ship-sweep'){
        // a line per finished case as it comes (a stall is visible from outside); no new case for BASELINE_STALL_MS fails
        // (a case that never returns blocks the page's thread, so the read has its own time limit)
        const limit=Number(process.env.BASELINE_MODE_TIMEOUT_MS??180000),stall=Number(process.env.BASELINE_STALL_MS??90000),t0=Date.now();let seen=0,last=Date.now(),running='none';
        // the debugger is on from the start (enabling it needs the page's thread; a pause later interrupts a busy one)
        const timed=(p,ms)=>Promise.race([p,new Promise(r=>setTimeout(()=>r(null),ms))]);
        let dbg=null;if(browserName==='chromium')try{dbg=await page.context().newCDPSession(page);await timed(dbg.send('Debugger.enable'),10000);}catch(e){dbg=null;}
        for(;;){
          // every read has an outer limit of its own: a page whose thread never returns may not answer Playwright either
          const done=await Promise.race([page.waitForFunction(finished,{},{timeout:15000}).then(()=>true,()=>false),new Promise(r=>setTimeout(()=>r(false),20000))]);
          const text=await Promise.race([page.locator('#results').textContent({timeout:20000}).catch(()=>null),new Promise(r=>setTimeout(()=>r(null),25000))]);
          if(text!==null){
            const all=text.split('\n'),lines=all.filter(l=>/ship sweep (enemy|player) /.test(l));
            for(const l of lines.slice(seen))console.log('CASE '+l.trim().replace(/https?:\/\/[^\s)]*\/(engine\/engine\.js|[\w.-]+\.m?js)(\?v=\w+)?/g,'$1').slice(0,2400));
            if(lines.length>seen){seen=lines.length;last=Date.now();}
            running=(all.filter(l=>l.startsWith('RUN ')).at(-1)??'none').trim().slice(0,200);
          }
          if(done){if(dbg)await timed(dbg.detach().catch(()=>{}),5000);break;}
          if(Date.now()-last>stall){
            // where the page's thread is: the debugger pauses the running script and lists its frames (engine.js line:column)
            console.log('STALL no case for '+Math.round((Date.now()-last)/1000)+' s, pausing the page');
            try{const cdp=dbg;if(!cdp)throw Error('no debugger session');const paused=new Promise(r=>cdp.once('Debugger.paused',r));timed(cdp.send('Debugger.pause'),10000);
              const ev=await timed(paused,20000);
              if(ev)for(const f of ev.callFrames.slice(0,40))console.log('STALL FRAME '+f.functionName+' '+f.url.replace(/\?.*/,'')+':'+(f.location.lineNumber+1)+':'+(f.location.columnNumber+1));
              else console.log('STALL FRAME none (no pause in 20 s)');}catch(e){console.log('STALL FRAME error '+e);}
            throw Error('STALL: no case finished for '+Math.round((Date.now()-last)/1000)+' s'+(text===null?' (the page does not answer)':'')+', running: '+running+' (case '+(from+seen)+', slice from '+from+')');}
          if(Date.now()-t0>limit)throw Error('timeout of the ship-sweep slice from '+from);
        }
      }else await page.waitForFunction(finished,{},{timeout:Number(process.env.BASELINE_MODE_TIMEOUT_MS??180000)});
      const output=await page.locator('#results').textContent();results.push({mode,from,output});await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));if(/^\s*(FAIL|FATAL)|[1-9]\d* failed/m.test(output)){if(!batch)assert.fail(output);failures.push(output);console.log('FAILED SLICE from '+from+'\n'+output.split('\n').filter(l=>/^FAILED/.test(l)).map(l=>l.slice(0,400)).join('\n'));}else{console.log('PASS '+(batch?'from '+from+' ':'')+output.slice(output.lastIndexOf('DONE')).trim());
        // hs-lua's 0.5 ms gate: its measured lines, read by the runner's performance phase as PERF rows
        if(mode==='hs-lua')for(const l of output.split('\n'))if(/^\s*(PASS|FAIL) (bridge \(|engine frame )/.test(l))console.log(l.trim());}
      if(!batch||output.includes('DONE '+mode+': 0 passed, 0 failed'))break;
    }
    assert.equal(failures.length,0,failures.length+' slices failed');
  }
  await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));
} catch(e) { console.log('Diagnostic output:',await page.locator('#results').textContent().catch(()=>'')); console.log('Diagnostic state:',await page.evaluate(()=>{try{return window.debugSnapshot?.();}catch(e){return String(e);}})); throw e; }
finally {await browser.close();}
