// Optional development measurement; no instrumentation runs during ordinary play.
export function createBenchmark() {
  let active;
  const percentile=(values,fraction)=>values.length?values.slice().sort((a,b)=>a-b)[Math.min(values.length-1,Math.floor(values.length*fraction))]:null;
  return {
    start(durationMs=10000,warmupMs=2000){
      if(active)throw Error('A benchmark is already running');
      return new Promise((resolve,reject)=>{active={start:performance.now()+warmupMs,durationMs,resolve,reject,samples:[],frames:[],updates:[],renders:[],callbacks:[]};});
    },
    // Called on every animation callback, including those skipped by the FPS cap.
    callback(now,interval){if(active&&now>=active.start)active.callbacks.push(interval);},
    record(now,interval,update,render){
      const run=active;if(!run||now<run.start)return;
      run.frames.push(interval);run.updates.push(update);run.renders.push(render);run.samples.push(update+render);
      if(now-run.start>=run.durationMs){
        active=null;
        run.resolve({frames:run.frames.length,fps:1000/(run.frames.reduce((a,b)=>a+b,0)/run.frames.length),browserCallbacks:run.callbacks.length,browserFps:run.callbacks.length?1000/(run.callbacks.reduce((a,b)=>a+b,0)/run.callbacks.length):null,browserIntervalP95:percentile(run.callbacks,.95),frameP50:percentile(run.frames,.5),frameP95:percentile(run.frames,.95),workP50:percentile(run.samples,.5),workP95:percentile(run.samples,.95),updateP95:percentile(run.updates,.95),renderP95:percentile(run.renders,.95),framesOver33ms:run.frames.filter(v=>v>33.34).length,heapMiB:performance.memory?Math.round(performance.memory.usedJSHeapSize/1048576):null});
      }
    },
    cancel(reason){if(active){const run=active;active=null;run.reject(Error(reason));}}
  };
}

// An unloaded-page baseline. This measures callbacks, not physical presentation
// or a monitor's advertised refresh rate. Hidden pages invalidate the sample.
export function measureBrowserRate(durationMs=3000,{signal}={}) {
  return new Promise((resolve,reject)=>{
    let id,last,start;const intervals=[];
    const cleanup=()=>{cancelAnimationFrame(id);document.removeEventListener('visibilitychange',visibility);signal?.removeEventListener('abort',abort);};
    const fail=message=>{cleanup();reject(new DOMException(message,'AbortError'));};
    const abort=()=>fail(signal?.reason?.message??'Browser timing stopped');
    const visibility=()=>{if(document.hidden)fail('Keep this page visible while measuring browser timing');};
    if(signal?.aborted||document.hidden){reject(new DOMException('Keep this page visible while measuring browser timing','AbortError'));return;}
    document.addEventListener('visibilitychange',visibility);signal?.addEventListener('abort',abort,{once:true});
    const frame=now=>{
      if(start===undefined){start=last=now;}
      else{intervals.push(now-last);last=now;}
      if(now-start>=durationMs){cleanup();const sorted=intervals.slice().sort((a,b)=>a-b);resolve({callbacks:intervals.length,durationMs:now-start,fps:1000*intervals.length/(now-start),intervalP95:sorted[Math.min(sorted.length-1,Math.floor(sorted.length*.95))],screen:{width:screen.width,height:screen.height,devicePixelRatio},window:{x:window.screenX,y:window.screenY,width:outerWidth,height:outerHeight}});}
      else id=requestAnimationFrame(frame);
    };
    id=requestAnimationFrame(frame);
  });
}
