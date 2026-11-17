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
    const failures=[],batch=mode==='encounters'?Number(process.env.BASELINE_ENCOUNTER_BATCH??0):0,[first,end]=(process.env.BASELINE_ENCOUNTER_RANGE??'0,Infinity').split(',').map(Number);
    for(let from=first;from<end;from+=batch){
      // Between slices the old document is left for a blank page and a collection is forced (Chromium): the next slice's
      // engine otherwise starts beside the previous one's garbage in the same process whenever the browser has not parked
      // the old page in its back/forward cache, and four slices then reach the container's memory limit.
      if(batch&&from>first&&browserName==='chromium'){await page.goto('about:blank');const cdp=await page.context().newCDPSession(page);await cdp.send('HeapProfiler.collectGarbage');await cdp.detach();}
      if(batch){await page.goto(origin+'/Diagnostics?encounters='+from+','+Math.min(batch,end-from));await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);}
      // BASELINE_CAMPAIGN=ae or vanilla: one half of the campaign, on a fresh page (its own engine start).
      if(mode==='campaign'&&process.env.BASELINE_CAMPAIGN){await page.goto(origin+'/Diagnostics?campaign='+encodeURIComponent(process.env.BASELINE_CAMPAIGN));await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);}
      await page.locator('#test-'+mode).click();await page.waitForFunction(()=>/DONE (passed|failed|cancelled)\s*$/.test(document.getElementById('results').textContent)&&document.getElementById('stop-tests').disabled,{},{timeout:Number(process.env.BASELINE_MODE_TIMEOUT_MS??180000)});
      const output=await page.locator('#results').textContent();results.push({mode,from,output});await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));if(/^\s*(FAIL|FATAL)|[1-9]\d* failed/m.test(output)){if(!batch)assert.fail(output);failures.push(output);console.log('FAILED SLICE from '+from+'\n'+output.split('\n').filter(l=>/^FAILED/.test(l)).map(l=>l.slice(0,400)).join('\n'));}else{console.log('PASS '+(batch?'from '+from+' ':'')+output.slice(output.lastIndexOf('DONE')).trim());
        // hs-lua's 0.5 ms gate: its measured lines, read by the runner's performance phase as PERF rows
        if(mode==='hs-lua')for(const l of output.split('\n'))if(/^\s*(PASS|FAIL) (bridge \(|engine frame )/.test(l))console.log(l.trim());}
      if(!batch||/DONE encounters: 0 passed, 0 failed/.test(output))break;
    }
    assert.equal(failures.length,0,failures.length+' slices failed');
  }
  await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));
} catch(e) { console.log('Diagnostic output:',await page.locator('#results').textContent().catch(()=>'')); console.log('Diagnostic state:',await page.evaluate(()=>{try{return window.debugSnapshot?.();}catch(e){return String(e);}})); throw e; }
finally {await browser.close();}
