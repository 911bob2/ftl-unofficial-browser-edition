const version=new URL(import.meta.url).search;
const [{createGL},{createAudio},{Unzlib},{createFramePacer}]=await Promise.all([import('./gl.mjs'+version),import('./audio.mjs'+version),import('./vendor/fflate-0.8.3.mjs'+version),import('./frame-pacer.mjs'+version)]);
export async function loadGame({canvas,manifest,fetchBoot,fetchAudio,onProgress,onSave,run=null,profile=null,ae=true,audioContext,closeAudioContext=true,accountId="diagnostics"}){
  onProgress('Loading game resources…');const packed=await fetchBoot();const view=new DataView(packed);if(view.getUint32(0)!==0x46544c42)throw Error('Invalid asset bundle');const n=view.getUint32(4);const entries=JSON.parse(new TextDecoder().decode(new Uint8Array(packed,8,n)));const base=8+n;
  window.ftlFiles=new Map(entries.map(e=>[e.name,new Uint8Array(packed,base+e.offset,e.length)]));window.ftlUnzlib=Unzlib;
  window.ftlGL=createGL(canvas);window.ftlAudio=createAudio(manifest,fetchAudio,audioContext);window.ftlOnSave=onSave;
  // Cursor keys are main[,overlay][#frame]: the original draws the image at the mouse with an optional overlay on top
  // (the armed weapon's numbered crosshair), and '#n' picks one square frame of a strip (the door cursor).
  const cursors=new Map(),blobs=new Map();let cursorKey=null;
  const cursorBlob=name=>{if(!blobs.has(name)){const bytes=window.ftlFiles.get(name);blobs.set(name,bytes?URL.createObjectURL(new Blob([bytes],{type:'image/png'})):null);}return blobs.get(name);};
  const cursorImage=url=>new Promise((resolve,reject)=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=reject;img.src=url;});
  const composeCursor=async key=>{const [names,frame]=key.split('#');const urls=names.split(',').map(cursorBlob);if(urls.some(u=>!u))return null;if(urls.length===1&&frame===undefined)return urls[0];
   const imgs=await Promise.all(urls.map(cursorImage)),size=frame===undefined?imgs[0].width:imgs[0].height,out=document.createElement('canvas');out.width=size;out.height=imgs[0].height;
   const ctx=out.getContext('2d');ctx.drawImage(imgs[0],frame===undefined?0:-Number(frame)*size,0);for(const img of imgs.slice(1))ctx.drawImage(img,0,0);return out.toDataURL('image/png');};
  window.ftlSetCursor=key=>{cursorKey=key;if(!key){canvas.style.cursor='default';return;}const apply=url=>{if(url&&cursorKey===key)canvas.style.cursor=`url("${url}") 0 0, auto`;};
   if(cursors.has(key)){apply(cursors.get(key));return;}composeCursor(key).then(url=>{cursors.set(key,url);apply(url);}).catch(()=>{});};
  onProgress('Starting engine…');await new Promise(requestAnimationFrame);await new Promise((resolve,reject)=>{const s=document.createElement('script');s.src='/engine/engine.js?v='+encodeURIComponent(manifest.clientVersion??manifest.engineBuild);s.onload=()=>{s.remove();resolve();};s.onerror=()=>{s.remove();reject(Error('Engine could not be downloaded'));};document.head.append(s);});
  const engine=Object.fromEntries(['start','step','render','renderFrame','mouse','key','pause','focusChanged','save','newRun','restore','runSave','profileSave','inFlight','resetInput','shutdown','debugConfig','debugSnapshot','diagnosticRun','diagnosticProfile','consoleOpen','consoleToggle','consoleExecute','menuCancel','saveKind','saveSettle','frameRateLimit'].map(name=>[name,window[name]]));engine.start(ae,run,profile);
  const log=(category,event,payload,severity='info')=>window.ftlDebugEvent?.(category,event,payload,severity);
  const diagnosticContext=()=>({focus:document.activeElement===canvas,fullscreen:!!document.fullscreenElement,pointerCapture:null});
  window.ftlCheckpointPut=(name,xml)=>{if(!/^[\w-]{1,64}$/.test(name))throw Error('Use a checkpoint name of 1 to 64 letters, digits, hyphens or underscores');const key='ftl-checkpoints:'+accountId+':'+manifest.engineBuild;const stored=JSON.parse(localStorage.getItem(key)??'{}');stored[name]=xml;if(Object.keys(stored).length>10)throw Error('At most ten named checkpoints are supported');localStorage.setItem(key,JSON.stringify(stored));};
  window.ftlCheckpointGet=name=>JSON.parse(localStorage.getItem('ftl-checkpoints:'+accountId+':'+manifest.engineBuild)??'{}')[name]??null;
  window.ftlCheckpointDownload=xml=>{const a=document.createElement('a'),url=URL.createObjectURL(new Blob([xml],{type:'application/xml'}));a.href=url;a.download='ftl-checkpoint.xml';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
  window.ftlCheckpointImport=()=>{const input=document.createElement('input');input.type='file';input.accept='.xml,application/xml';input.onchange=async()=>{try{if(input.files[0].size>16*1024*1024)throw Error('Checkpoint exceeds 16 MiB');engine.restore(await input.files[0].text());}catch(e){window.dispatchEvent(new CustomEvent('ftl-engine-error',{detail:e}));}};input.click();};
  const audio=window.ftlAudio,events=[],listeners=new AbortController();
  const listen=(target,name,handler,options={})=>target.addEventListener(name,handler,{...options,signal:listeners.signal});
  const position=e=>{const r=canvas.getBoundingClientRect(),scale=Math.min(r.width/1280,r.height/720),left=r.left+(r.width-1280*scale)/2,top=r.top+(r.height-720*scale)/2;return [Math.round((e.clientX-left)/scale),Math.round((e.clientY-top)/scale)];};
  listen(canvas,'contextmenu',e=>e.preventDefault());
  listen(canvas,'pointermove',e=>{const [x,y]=position(e);events.push(()=>engine.mouse(x,y,0,0));});
  listen(canvas,'pointerdown',e=>{audio.resume();canvas.focus({preventScroll:true});canvas.setPointerCapture(e.pointerId);const [x,y]=position(e);events.push(()=>engine.mouse(x,y,0,0),()=>engine.mouse(x,y,1,[0,2,1][e.button]));});
  listen(canvas,'pointerup',e=>{const [x,y]=position(e);events.push(()=>engine.mouse(x,y,2,[0,2,1][e.button]));});
  listen(canvas,'wheel',e=>{e.preventDefault();const [x,y]=position(e);events.push(()=>engine.mouse(x,y,3,Math.sign(e.deltaY)*-120));},{passive:false});
  const special={Space:32,Quote:39,Comma:44,Minus:45,Period:46,Slash:47,Semicolon:59,Equal:61,BracketLeft:91,Backslash:92,BracketRight:93,Backquote:96,Escape:256,Enter:257,Tab:258,Backspace:259,Insert:260,Delete:261,ArrowRight:262,ArrowLeft:263,ArrowDown:264,ArrowUp:265,PageUp:266,PageDown:267,Home:268,End:269,CapsLock:280,ScrollLock:281,NumLock:282,PrintScreen:283,Pause:284,Numpad0:320,Numpad1:321,Numpad2:322,Numpad3:323,Numpad4:324,Numpad5:325,Numpad6:326,Numpad7:327,Numpad8:328,Numpad9:329,NumpadDecimal:330,NumpadDivide:331,NumpadMultiply:332,NumpadSubtract:333,NumpadAdd:334,NumpadEnter:335,NumpadEqual:336,ShiftLeft:340,ControlLeft:341,AltLeft:342,MetaLeft:343,ShiftRight:344,ControlRight:345,AltRight:346,MetaRight:347};
  function key(e,down){let k=special[e.code]??(/^Key[A-Z]$/.test(e.code)?e.code.charCodeAt(3):/^Digit\d$/.test(e.code)?e.code.charCodeAt(5):/^F\d+$/.test(e.code)?289+Number(e.code.slice(1)):e.key.length===1?e.key.toUpperCase().charCodeAt(0):0);log('input',down?'key.down':'key.up',{physicalKey:e.code,character:e.key,keyId:k,modifiers:{ctrl:e.ctrlKey,shift:e.shiftKey,alt:e.altKey,meta:e.metaKey},...diagnosticContext()});if(k){e.preventDefault();events.push(()=>engine.key(k,e.key.length===1?e.key.charCodeAt(0):0,down));}}
  listen(canvas,'keydown',e=>{audio.resume();if(!e.repeat)key(e,true);});listen(canvas,'keyup',e=>key(e,false));
  let last=performance.now(),lastRendered=last,stopped=false,suspended=false,benchmark,updateWork=0,renderDelta=0;
  const pacer=createFramePacer();
  function frame(now){
    if(stopped)return;
    try{
      const interval=now-last;
      benchmark?.callback(now,interval);
      const dt=Math.min(Math.max(0,interval)/1000,.05);last=now;
      if(!document.hidden){
        renderDelta+=dt;
        const started=benchmark?performance.now():0;
        if(!suspended){for(const event of events.splice(0)){event();engine.step(0);}engine.step(dt);}else events.length=0;
        const updated=benchmark?performance.now():0;
        if(benchmark)updateWork+=updated-started;
        if(pacer.shouldRender(now,engine.frameRateLimit())){
          engine.renderFrame(renderDelta);
          if(benchmark)benchmark.record(now,now-lastRendered,updateWork,performance.now()-updated);
          lastRendered=now;updateWork=0;renderDelta=0;
        }
      }
      requestAnimationFrame(frame);
    }catch(e){stopped=true;benchmark?.cancel('Engine stopped: '+e);console.error(e);window.dispatchEvent(new CustomEvent('ftl-engine-error',{detail:e}));}
  }
  const release=()=>{events.length=0;engine.resetInput();};
  const updateFocus=()=>engine.focusChanged(!document.hidden&&document.hasFocus()&&document.activeElement===canvas);
  listen(canvas,'blur',()=>{release();updateFocus();engine.save();});
  listen(canvas,'focus',updateFocus);
  listen(window,'blur',()=>{release();engine.focusChanged(false);engine.save();});
  listen(window,'focus',updateFocus);
  listen(canvas,'pointercancel',release);
  for(const name of ['pointerdown','pointerup','pointermove','pointercancel','wheel'])listen(canvas,name,e=>log('input',name,{position:{x:e.clientX,y:e.clientY},gamePosition:position(e),button:e.button,pointerId:e.pointerId,...diagnosticContext(),pointerCapture:canvas.hasPointerCapture(e.pointerId)}));
  listen(document,'fullscreenchange',()=>{release();log('input','fullscreen.changed',diagnosticContext());});
  listen(canvas,'webglcontextlost',e=>{e.preventDefault();release();engine.pause();log('runtime','webgl.context.lost',{},'error');});
  listen(canvas,'webglcontextrestored',()=>{log('runtime','webgl.context.restored',{},'warn');window.dispatchEvent(new CustomEvent('ftl-engine-error',{detail:Error('Graphics context restored. Reload to recreate game resources.')}));});
  listen(document,'visibilitychange',()=>{if(document.hidden){benchmark?.cancel('Keep the game visible while benchmarking');release();}updateFocus();if(document.hidden)engine.save();last=lastRendered=performance.now();updateWork=renderDelta=0;pacer.reset();});
  engine.render();requestAnimationFrame(frame);canvas.focus();onProgress('Ready');window.ftlEngine={pause:engine.pause,save:engine.save,newRun:engine.newRun,restore:engine.restore,runSave:engine.runSave,profileSave:engine.profileSave,inFlight:engine.inFlight,debugConfig:engine.debugConfig,debugSnapshot:engine.debugSnapshot,diagnosticRun:engine.diagnosticRun,diagnosticProfile:engine.diagnosticProfile,consoleOpen:engine.consoleOpen,consoleToggle:engine.consoleToggle,consoleExecute:engine.consoleExecute,menuCancel:engine.menuCancel,saveKind:engine.saveKind,saveSettle:engine.saveSettle,
    frameRateLimit:engine.frameRateLimit,
    async benchmark(durationMs,warmupMs=2000){benchmark??=(await import('./benchmark.mjs'+version)).createBenchmark();updateWork=0;try{return await benchmark.start(durationMs,warmupMs);}finally{benchmark=null;updateWork=0;}},
    suspend:value=>{suspended=value;release();engine.pause();},freeze:value=>{suspended=value;release();},stop:()=>{stopped=true;benchmark?.cancel('Game stopped');listeners.abort();release();engine.pause();engine.save();engine.shutdown();for(const id of audio.sources.keys())audio.call('deleteSource',id);if(closeAudioContext)audio.context.close();canvas.style.cursor='default';for(const url of cursors.values())URL.revokeObjectURL(url);}};return window.ftlEngine;
}
