import assert from 'node:assert/strict';
import {withOnboarding} from './lib/onboarding.mjs';
import {readFile,writeFile} from 'node:fs/promises';
import {chromium,firefox} from 'playwright';
const origin=process.env.BASELINE_ORIGIN??'http://localhost:8083';if(origin!=='http://localhost:8083'&&origin!=='http://localhost:8084')throw Error('Use an isolated baseline or debug fixture');
const fixture=process.env.DEBUG_FIXTURE_DIR??(origin.endsWith('8083')?'.build/fork-baseline-data':'.build/debug-data'),name='user05',password='baseline-fixture-password-2026';
const initial=new Map((await readFile(fixture+'/bootstrap-credentials.txt','utf8')).trim().split(/\r?\n/).map(l=>l.split('\t')));
const readyDeadline=Date.now()+20000;
while(!await fetch(origin+'/health/ready').then(r=>r.ok).catch(()=>false)){
  if(Date.now()>readyDeadline)throw Error('Isolated diagnostic instance did not become ready');
  await new Promise(resolve=>setTimeout(resolve,250));
}
const browserName=process.env.BASELINE_BROWSER??'chromium';
const resultsPath=process.env.BASELINE_RESULTS??('.build/'+(origin.endsWith('8083')?'fork-baseline':'fork-current')+'-results.json');
if(!['chromium','chrome','edge','firefox'].includes(browserName))throw Error('Unknown diagnostic browser '+browserName);
const type=browserName==='firefox'?firefox:chromium;
const options={headless:true};if(browserName==='chrome'||browserName==='edge')options.channel=browserName==='edge'?'msedge':'chrome';
const browser=withOnboarding(await type.launch(options),origin),page=await browser.newPage();page.setDefaultTimeout(90000);
page.on('console',event=>{if(event.type()==='error')console.log('BROWSER ERROR',event.text());else if(event.text().startsWith('TEST '))console.log(event.text());});
page.on('pageerror',error=>console.log('PAGE ERROR',error.stack));
try {
  const ready=page.waitForResponse(r=>r.url().endsWith('/api/auth/me'));
  await page.goto(origin);await ready;await page.waitForFunction(()=>document.body.dataset.sessionReady==='true');
  await page.evaluate(async({initial,password,name})=>{const token=async()=> (await (await fetch('/api/auth/csrf')).json()).token;let csrf=await token();const post=(path,body)=>fetch('/api/auth/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf},body:JSON.stringify(body)});let r=await post('login',{username:name,password});if(r.status===401)r=await post('login',{username:name,password:initial});if(!r.ok)throw Error('Fixture login failed '+r.status);csrf=await token();const me=await (await fetch('/api/auth/me')).json();if(me.mustChangePassword){r=await post('password',{currentPassword:initial,newPassword:password});if(!r.ok)throw Error('Rotation failed');csrf=await token();await post('login',{username:name,password});}}, {initial:initial.get(name),name,password});
  await page.goto(origin+'/Diagnostics');await page.locator('#individual-tests').evaluate(e=>e.parentElement.open=true);const results=[];
  for(const mode of (process.env.BASELINE_MODES??'vanilla,ae,encounters,mechanics,warnings,gameplay-options,gameplay-parity,progress,combat,campaign,tutorial,tutorial-isolation,tutorial-gates,tutorial-adapter,tutorial-overlay,tutorial-entry,tutorial-encounters,tutorial-play').split(',')){await page.locator('#test-'+mode).click();await page.waitForFunction(()=>/DONE (passed|failed|cancelled)\s*$/.test(document.getElementById('results').textContent)&&document.getElementById('stop-tests').disabled,{},{timeout:180000});const output=await page.locator('#results').textContent();results.push({mode,output});await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));assert.ok(!/FAIL|FATAL|[1-9]\d* failed/.test(output),output);console.log('PASS '+output.slice(output.lastIndexOf('DONE')).trim());}
  await writeFile(resultsPath,JSON.stringify({at:new Date().toISOString(),browser:browserName,results},null,2));
} catch(e) { console.log('Diagnostic output:',await page.locator('#results').textContent().catch(()=>'')); console.log('Diagnostic state:',await page.evaluate(()=>{try{return window.debugSnapshot?.();}catch(e){return String(e);}})); throw e; }
finally {await browser.close();}
