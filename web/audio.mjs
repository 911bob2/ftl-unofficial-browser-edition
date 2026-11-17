// Call immediately from the user gesture, and consume Firefox's rejection when
// navigation closes a context before its pending resume completes.
export async function resumeAudio(context,onError=error=>{
  console.error('Audio resume failed',error);
  window.ftlDebugEvent?.('runtime','audio.resume.failed',{message:error.message},'error');
}) {
  try {await context.resume();return true;}
  catch(error) {if(context.state!=='closed')onError(error);return false;}
}

export function createAudio(manifest,fetchAsset,audioContext,now=()=>performance.now()) {
  const context=audioContext??new AudioContext(),paths=new Map(manifest.audio.map(a=>[a.name,a])),buffers=new Map(),byPath=new Map(),sources=new Map();let next=1;
  // A file the game files lack (a mod's sound or track) is a buffer that never plays: the original logs it and goes on
  // silently. Such a source stays AL_INITIAL rather than AL_STOPPED, so a missing music track is not taken for one
  // that ended and the playlist stays silent there, as SoundControl's next-track function leaves it.
  // A file that is there but did not load (the request or the decode failed) is asked for again when the sound is next
  // played, not before a second has passed, doubling up to a minute: the engine keeps a sound's buffer id for the whole
  // game, so a failure remembered here would silence that sound until the page is reloaded.
  function load(state){state.failed=false;state.promise=fetchAsset(state.asset).then(b=>context.decodeAudioData(b)).then(decoded=>state.decoded=decoded);state.promise.catch(e=>{state.failed=true;state.tries++;state.retryAt=now()+Math.min(60000,1000*2**Math.min(state.tries-1,6));console.error('Audio decode failed',state.path,e);});}
  function buffer(path){if(byPath.has(path))return byPath.get(path);const a=paths.get(path),id=next++;byPath.set(path,id);const state={path,asset:a,decoded:null,promise:null,failed:false,tries:0,retryAt:0};if(a)load(state);else{state.failed=true;state.promise=Promise.reject(Error('Missing audio '+path));state.promise.catch(e=>console.error('Audio decode failed',path,e));}buffers.set(id,state);return id;}
  function source(){const id=next++,gain=context.createGain();gain.connect(context.destination);sources.set(id,{gain,buffer:0,node:null,loop:false,music:false,pitch:1,state:0x1011,offset:0,startTime:0,epoch:0,asked:0});return id;}
  // An effect that has waited two seconds for its sound or for the audio context (suspended until a gesture, or
  // interrupted) is given up when its state is next asked for: it would come too late to mean anything, and a wait that
  // never ends must not hold one of the engine's 61 effect voices. Music and loops wait as long as it takes.
  function stale(s){return s.state===0x1012&&!s.node&&!s.loop&&!s.music&&now()-s.asked>2000;}
  function position(s){return s.state===0x1012&&s.node?s.offset+(context.currentTime-s.startTime)*s.pitch:s.offset;}
  function stopNode(s){if(s.node){s.node.onended=null;s.node.stop();s.node.disconnect();s.node=null;}s.epoch++;}
  async function play(s){const epoch=++s.epoch;s.state=0x1012;s.asked=now();const b=buffers.get(s.buffer);if(!b){s.state=0x1014;return;}if(b.failed&&b.asset&&now()>=b.retryAt)load(b);let audio;try{audio=await b.promise;}catch{if(epoch===s.epoch)s.state=0x1011;return;}if(epoch!==s.epoch||s.state!==0x1012)return;try{await context.resume();}catch(e){if(epoch!==s.epoch||context.state==='closed')return;s.state=0x1014;console.error('Audio resume failed',e);return;}if(epoch!==s.epoch)return;const node=context.createBufferSource();node.buffer=audio;node.loop=s.loop;node.playbackRate.value=s.pitch;node.connect(s.gain);s.node=node;s.offset=Math.max(0,s.offset)%audio.duration;s.startTime=context.currentTime;node.onended=()=>{if(s.node===node){s.node=null;s.state=0x1014;s.offset=0;}};node.start(0,s.offset);}
  return {context,buffer,sources,buffers,resume:()=>resumeAudio(context),
    music(id,path){const s=sources.get(id);stopNode(s);s.offset=0;s.buffer=buffer(path);s.music=true;play(s);},
    call(op,a,b,c){const s=sources.get(a);
      switch(op){
        case 'buffer':{const id=next++;buffers.set(id,{decoded:null,promise:Promise.resolve(null)});return id;}
        case 'source':return source();
        case 'deleteBuffer':buffers.delete(a);return 0;
        case 'deleteSource':if(s){stopNode(s);s.gain.disconnect();sources.delete(a);}return 0;
        case 'sourcei':if(!s)return 0;if(b===0x1009){stopNode(s);s.buffer=c;s.music=false;s.offset=0;s.state=0x1011;}if(b===0x1007){s.loop=!!c;if(s.node)s.node.loop=s.loop;}return 0;
        case 'sourcef':if(!s)return 0;if(b===0x100a)s.gain.gain.setValueAtTime(c,context.currentTime);if(b===0x1003){s.offset=position(s);s.startTime=context.currentTime;s.pitch=c;if(s.node)s.node.playbackRate.value=c;}if(b===0x1024){const playing=s.state===0x1012;stopNode(s);s.offset=c;if(playing)play(s);}return 0;
        case 'play':if(s){stopNode(s);play(s);}return 0;
        case 'pause':if(s){s.offset=position(s);stopNode(s);s.state=0x1013;}return 0;
        case 'stop':if(s){stopNode(s);s.offset=0;s.state=0x1014;}return 0;
        case 'getSource':if(!s)return 0;if(b===0x1010){if(stale(s)){stopNode(s);s.offset=0;s.state=0x1014;}return s.state;}if(b===0x1024)return position(s);if(b===0x100a)return s.gain.gain.value;if(b===0x1003)return s.pitch;return 0;
        case 'getBuffer':return buffers.get(a)?.decoded?.length||0;
      }return 0;
    }
  };
}
