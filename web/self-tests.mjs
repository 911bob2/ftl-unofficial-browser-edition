import {createAudio,resumeAudio} from './audio.mjs';
import {createFramePacer} from './frame-pacer.mjs';
import {createBenchmark} from './benchmark.mjs';
import {zipSync,unzipSync,strToU8,strFromU8} from './vendor/fflate-0.8.3.mjs';
import {diagnosticArchive,diagnosticCheckpointFiles} from './diagnostic-export.mjs';
const assert={
 equal(a,b,message='Values differ'){if(a!==b)throw Error(message+': '+a+' != '+b);},
 deepEqual(a,b){if(JSON.stringify(a)!==JSON.stringify(b))throw Error('Structured values differ');},
 ok(value,message='Assertion failed'){if(!value)throw Error(message);},
 async rejects(action,pattern){try{await action();}catch(e){if(pattern.test(e.message))return;throw e;}throw Error('Expected rejection');}
};

export async function audioClockChecks(){
  // A controllable Web Audio clock catches position discontinuities that are
  // difficult to hear reliably during browser testing.
  const context={currentTime:0,state:'running',resume:async()=>{},decodeAudioData:async()=>({duration:60,length:48000*60}),
    createGain:()=>({gain:{value:1,setValueAtTime(value){this.value=value;}},connect(){},disconnect(){}}),
    createBufferSource:()=>({playbackRate:{value:1},connect(){},disconnect(){},start(){},stop(){}})};
  const audio=createAudio({audio:[{name:'test.ogg'}]},async()=>new ArrayBuffer(1),context);
  const source=audio.call('source'),buffer=audio.buffer('test.ogg');
  await audio.buffers.get(buffer).promise;
  audio.call('sourcei',source,0x1009,buffer);audio.call('play',source);
  await new Promise(resolve=>setTimeout(resolve,0));
  context.currentTime=10;
  assert.equal(audio.call('getSource',source,0x1024),10);
  audio.call('sourcef',source,0x1003,2);
  assert.equal(audio.call('getSource',source,0x1024),10,'Changing pitch must preserve the current position');
  context.currentTime=12;
  assert.equal(audio.call('getSource',source,0x1024),14);
  audio.call('pause',source);context.currentTime=20;
  assert.equal(audio.call('getSource',source,0x1024),14,'Paused time must not advance playback');
  audio.call('play',source);await new Promise(resolve=>setTimeout(resolve,0));context.currentTime=21;
  assert.equal(audio.call('getSource',source,0x1024),16);
  audio.call('sourcef',source,0x1024,30);await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(audio.call('getSource',source,0x1024),30);
  audio.call('stop',source);await new Promise(resolve=>setTimeout(resolve,0));
  assert.equal(audio.call('getSource',source,0x1010),0x1014);
  assert.equal(audio.sources.get(source).node,null);
  audio.call('deleteSource',source);assert.equal(audio.sources.has(source),false);
  // Firefox rejects a pending resume when a test/game closes its AudioContext.
  // Deleting the waiting source must consume that rejection and never start it.
  let rejectResume;
  const waiting=createAudio({audio:[{name:'test.ogg'}]},async()=>new ArrayBuffer(1),{
    ...context,state:'suspended',resume:()=>new Promise((resolve,reject)=>{rejectResume=reject;}),
    createBufferSource(){throw Error('Cancelled audio started playing');}
  });
  const waitingSource=waiting.call('source'),waitingBuffer=waiting.buffer('test.ogg');
  await waiting.buffers.get(waitingBuffer).promise;
  waiting.call('sourcei',waitingSource,0x1009,waitingBuffer);waiting.call('play',waitingSource);
  await new Promise(resolve=>setTimeout(resolve,0));assert.ok(rejectResume);
  waiting.call('deleteSource',waitingSource);rejectResume(Error('Closed before resume completed'));
  await new Promise(resolve=>setTimeout(resolve,0));assert.equal(waiting.sources.size,0);
  // Launch/pointer/keyboard resumes have no source epoch. Their pending promise
  // must also be consumed on close; a live failure is reported and can retry.
  const gestureContext={state:'suspended',resume:()=>new Promise((resolve,reject)=>{rejectResume=reject;})};
  const failures=[],gesture=resumeAudio(gestureContext,error=>failures.push(error.message));
  gestureContext.state='closed';rejectResume(Error('Closed before resume completed'));
  assert.equal(await gesture,false);assert.deepEqual(failures,[]);
  gestureContext.state='suspended';gestureContext.resume=async()=>{throw Error('Device unavailable');};
  assert.equal(await resumeAudio(gestureContext,error=>failures.push(error.message)),false);
  assert.deepEqual(failures,['Device unavailable']);
  gestureContext.resume=async()=>{gestureContext.state='running';};
  assert.equal(await resumeAudio(gestureContext,error=>failures.push(error.message)),true);
  return 'PASS audio clock, pitch changes, pause/resume, seek, stop, pending source/gesture resume cancellation, retry and cleanup';
}

export async function framePacingChecks(){
  // Use synthetic refresh rates: CI's display cannot establish high-refresh pacing.
  for (const refresh of [60, 75, 120, 144, 165, 240, 360, 500]) {
    for (const cap of [0, 30, 60, 90, 120, 144, 165, 240, 360, 500]) {
      const pacer = createFramePacer();
      let frames = 0;
      for (let tick = 0; tick < refresh * 10; tick++)
        if (pacer.shouldRender(tick * 1000 / refresh, cap)) frames++;
      assert.ok(Math.abs(frames - (cap === 0 ? refresh : Math.min(cap, refresh)) * 10) <= 1,
        `${refresh} Hz / ${cap || 'Unlimited'}: ${frames} drawings`);
    }
  }
  const pacer = createFramePacer();
  assert.equal(pacer.shouldRender(0, 30), true);
  assert.equal(pacer.shouldRender(10, 30), false);
  assert.equal(pacer.shouldRender(10, 0), true, 'Unlimited applies immediately');
  assert.equal(pacer.shouldRender(11, 0), true);
  assert.equal(pacer.shouldRender(12, 60), true, 'Changed cap applies immediately');
  assert.equal(pacer.shouldRender(13, 60), false);
  assert.equal(pacer.shouldRender(10000, 60), true, 'A stall does not build a drawing backlog');
  assert.equal(pacer.shouldRender(10001, 60), false);
  pacer.reset();
  assert.equal(pacer.shouldRender(10001, 60), true, 'Visibility restoration resets pacing');
  // Sampling must distinguish a drawing cap from the clock supplied by Firefox.
  for(const refresh of [60,500])for(const cap of [30,0]){
    const sample=createBenchmark(),pacing=createFramePacer(),result=sample.start(1000,1000),base=performance.now();let lastDraw=base;
    for(let tick=1;tick<=refresh*3;tick++){
      const now=base+tick*1000/refresh;sample.callback(now,1000/refresh);
      if(pacing.shouldRender(now,cap)){sample.record(now,now-lastDraw,.2,.5);lastDraw=now;}
    }
    const measured=await result;
    assert.ok(Math.abs(measured.browserFps-refresh)<.01,'Browser rate must include skipped drawings');
    assert.ok(Math.abs(measured.fps-(cap||refresh))<1,'Drawing rate uses actual drawing intervals');
    assert.ok(Math.abs(measured.browserCallbacks-refresh)<=2,'Warmup callbacks are excluded');
    if(cap===0)assert.equal(measured.frames,measured.browserCallbacks,'Unlimited draws once per callback');
    else assert.ok(measured.browserCallbacks>measured.frames,'Finite cap keeps independent callback counts');
  }
  const cancelled=createBenchmark(),waiting=cancelled.start();cancelled.cancel('Stopped');
  await assert.rejects(()=>waiting,/Stopped/);
  return 'PASS frame pacing: caps and Unlimited at eight refresh rates through 500 Hz, changes, stalls, reset and independent benchmark callback/drawing rates with warmup/cancellation';
}

export async function diagnosticExportChecks(){
  const source={
    'metadata.json':strToU8('{"session":"fixture"}'),
    'summary.txt':strToU8('Server history'),
    'records.jsonl':strToU8('record\n'.repeat(20000)),
  };
  const server=new Blob([zipSync(source)]),extra={'browser/pending.jsonl':'{"sequence":4}\n','browser/summary.txt':'Pending history – ž'};
  const merged=unzipSync(new Uint8Array(await (await diagnosticArchive(server,extra)).arrayBuffer()));
  for(const [name,data] of Object.entries(source))assert.deepEqual(merged[name],data);
  for(const [name,text] of Object.entries(extra))assert.equal(strFromU8(merged[name]),text);
  assert.deepEqual(Object.keys(merged).sort(),[...Object.keys(source),...Object.keys(extra)].sort());
  const offline=unzipSync(new Uint8Array(await (await diagnosticArchive(null,extra)).arrayBuffer()));
  assert.equal(strFromU8(offline['browser/pending.jsonl']),extra['browser/pending.jsonl']);
  await assert.rejects(()=>diagnosticArchive(new Blob(['invalid ZIP']),extra),/Missing diagnostic ZIP/);
  const id='3baccc96-dbbe-4ef1-b889-8da343fd875d',record=(part,data,parts=2)=>({eventName:'checkpoint.part',payload:{id,name:'run',part,parts,data}});
  const checkpoints=diagnosticCheckpointFiles([record(1,'</save>'),record(0,'<save>'),record(0,'<save>')]);
  assert.equal(checkpoints['browser/checkpoints/'+id.replaceAll('-','')+'/run.xml'],'<save></save>');
  assert.deepEqual(diagnosticCheckpointFiles([record(0,'<incomplete>')]),{});
  assert.deepEqual(diagnosticCheckpointFiles([record(-1,'bad'),record(0,'bad',513),{eventName:'checkpoint.part',payload:{id:'../invalid',name:'profile',part:0,parts:1,data:'bad'}}]),{});
  return 'PASS ZIP supplement preserves compressed server entries, offline archive, Unicode, invalid archives and complete/incomplete checkpoint assembly';
}
