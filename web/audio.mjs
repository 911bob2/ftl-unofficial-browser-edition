// Call immediately from the user gesture, and consume Firefox's rejection when
// navigation closes a context before its pending resume completes.
export async function resumeAudio(context,onError=error=>{
  console.error('Audio resume failed',error);
  window.ftlDebugEvent?.('runtime','audio.resume.failed',{message:error.message},'error');
}) {
  try {await context.resume();return true;}
  catch(error) {if(context.state!=='closed')onError(error);return false;}
}

/** Decoded sounds held beyond the ones in use, as the Android app's mixer counts them (AudioMixer: 192 MiB). */
export const AUDIO_HELD_LIMIT=192*1048576;
// A sound's size as the Android app counts it: 16-bit samples at the file's own rate. The browser holds the same sound
// as 32-bit float samples at the output's rate (about 2.2 times that at 48 kHz), so both platforms drop the same sounds
// at the same moments.
export const androidBytes=(decoded,rate)=>Math.round(decoded.duration*(rate||decoded.sampleRate||decoded.length/decoded.duration))*(decoded.numberOfChannels||1)*2;
/** What the browser holds for a decoded sound, in bytes. */
export const browserBytes=decoded=>(decoded?.length||0)*(decoded?.numberOfChannels||1)*4;
// The sample rate an Ogg Vorbis or WAV file states (decodeAudioData resamples to the output's rate and takes the bytes).
function fileRate(bytes){try{const d=new DataView(bytes);if(d.getUint32(0)===0x4f676753){const p=27+d.getUint8(26);if(d.getUint8(p)===1)return d.getUint32(p+12,true);}
 if(d.getUint32(0)===0x52494646)for(let i=12;i+16<=d.byteLength;){const id=d.getUint32(i),size=d.getUint32(i+4,true);if(id===0x666d7420)return d.getUint32(i+12,true);i+=8+size+(size&1);}}catch{}return 0;}

export function createAudio(manifest,fetchAsset,audioContext,now=()=>performance.now(),{limit=AUDIO_HELD_LIMIT,count=androidBytes}={}) {
  const context=audioContext??new AudioContext(),paths=new Map(manifest.audio.map(a=>[a.name,a])),buffers=new Map(),byPath=new Map(),sources=new Map();let next=1;
  // A file the game files lack (a mod's sound or track) is a buffer that never plays: the original logs it and goes on
  // silently. Such a source stays AL_INITIAL rather than AL_STOPPED, so a missing music track is not taken for one
  // that ended and the playlist stays silent there, as SoundControl's next-track function leaves it.
  // A file that is there but did not load (the request or the decode failed) is asked for again when the sound is next
  // played, not before a second has passed, doubling up to a minute: the engine keeps a sound's buffer id for the whole
  // game, so a failure remembered here would silence that sound until the page is reloaded.
  // Decoded sounds are held up to [limit] in total, as the Android app's mixer holds them (MixerCore.free): when a decode
  // brings the total above it, sounds that no source is playing, waiting for or paused on are dropped, music first (a
  // track is hundreds of effects' worth), each least recently heard first. A dropped sound keeps its id and is decoded
  // again when it is next played, as on its first play. A track is decoded only if a music source still wants it once
  // the engine's step is over, at most two at a time (the engine's two music sources), so a burst of track changes (a
  // test's fast traversal) decodes only the tracks it ends on.
  let held=0,decoding=0;const queue=[],stats={decodes:0,again:0,drops:0};
  const inUse=b=>{for(const s of sources.values())if(s.buffer===b.id&&(s.state===0x1012||s.state===0x1013))return true;return false;};
  function drop(b){held-=b.bytes;b.bytes=0;b.decoded=null;b.promise=null;b.dropped=true;stats.drops++;}
  function trim(keep){while(held>limit){let oldest=null;for(const b of buffers.values()){if(!b.bytes||b===keep||inUse(b))continue;if(!oldest||(b.music!==oldest.music?b.music:b.used<oldest.used))oldest=b;}if(!oldest)return;drop(oldest);}}
  function stored(state,decoded,rate){
    // V8 learns of the samples only once script has asked for them: without this a dropped track waits for a garbage
    // collection nothing else asks for (Chromium: twelve decodes of a 6-minute track kept 1.6 GB, none of it freed).
    for(let c=0;c<(decoded?.numberOfChannels||0);c++)decoded.getChannelData?.(c);
    state.pending=false;state.decoded=decoded;if(buffers.get(state.id)!==state)return decoded;
    state.bytes=count(decoded,rate);held+=state.bytes;state.used=now();stats.decodes++;if(state.dropped)stats.again++;trim(state);return decoded;}
  function pump(){while(decoding<2&&queue.length){const job=queue.shift();if(job.state.force||inUse(job.state)){decoding++;job.start();}else job.skip();}}
  function load(state,queued){state.failed=false;state.pending=true;let rate=0;
    const run=()=>fetchAsset(state.asset).then(b=>{rate=fileRate(b);return context.decodeAudioData(b);}).then(decoded=>stored(state,decoded,rate));
    const promise=state.promise=queued?new Promise((resolve,reject)=>{queue.push({state,start:()=>{state.force=false;run().then(resolve,reject).finally(()=>{decoding--;pump();});},
      skip:()=>{state.pending=false;if(state.promise===promise)state.promise=null;resolve(null);}});queueMicrotask(pump);}):run();
    promise.catch(e=>{state.pending=false;state.failed=true;state.tries++;state.retryAt=now()+Math.min(60000,1000*2**Math.min(state.tries-1,6));console.error('Audio decode failed',state.path,e);});}
  function entry(path){if(byPath.has(path))return byPath.get(path);const a=paths.get(path),id=next++;byPath.set(path,id);const state={id,path,asset:a,music:path.startsWith('audio/music/'),decoded:null,promise:null,pending:false,failed:false,tries:0,retryAt:0,bytes:0,used:0};if(!a){state.failed=true;state.promise=Promise.reject(Error('Missing audio '+path));state.promise.catch(e=>console.error('Audio decode failed',path,e));}buffers.set(id,state);return id;}
  // The engine's sound buffers (and the checks) load at once; a music source's track waits for its turn (play).
  function buffer(path){const id=entry(path),b=buffers.get(id);if(b.asset&&!b.decoded&&!b.failed){if(b.pending)b.force=true;else load(b,false);}return id;}
  function source(){const id=next++,gain=context.createGain();gain.connect(context.destination);sources.set(id,{gain,buffer:0,node:null,loop:false,music:false,pitch:1,state:0x1011,offset:0,startTime:0,epoch:0,asked:0});return id;}
  // An effect that has waited two seconds for its sound or for the audio context (suspended until a gesture, or
  // interrupted) is given up when its state is next asked for: it would come too late to mean anything, and a wait that
  // never ends must not hold one of the engine's 61 effect voices. Music and loops wait as long as it takes.
  function stale(s){return s.state===0x1012&&!s.node&&!s.loop&&!s.music&&now()-s.asked>2000;}
  function position(s){return s.state===0x1012&&s.node?s.offset+(context.currentTime-s.startTime)*s.pitch:s.offset;}
  function heard(s){const b=buffers.get(s.buffer);if(b)b.used=now();}
  function stopNode(s){if(s.node){heard(s);s.node.onended=null;s.node.stop();s.node.disconnect();s.node=null;}s.epoch++;}
  async function play(s){const epoch=++s.epoch;s.state=0x1012;s.asked=now();let audio,b;
    // A dropped sound is decoded again here; a track whose turn was skipped (no source wanted it then) is asked for again.
    for(let turn=0;;turn++){b=buffers.get(s.buffer);if(!b){s.state=0x1014;return;}
      if(!b.decoded&&!b.pending&&b.asset&&(!b.failed||now()>=b.retryAt))load(b,b.music);
      try{audio=await (b.decoded??b.promise);}catch{if(epoch===s.epoch)s.state=0x1011;return;}
      if(epoch!==s.epoch||s.state!==0x1012)return;if(audio||!b.asset||turn>2)break;}
    if(!audio)return;
    try{await context.resume();}catch(e){if(epoch!==s.epoch||context.state==='closed')return;s.state=0x1014;console.error('Audio resume failed',e);return;}if(epoch!==s.epoch)return;const node=context.createBufferSource();node.buffer=audio;node.loop=s.loop;node.playbackRate.value=s.pitch;node.connect(s.gain);s.node=node;s.offset=Math.max(0,s.offset)%audio.duration;s.startTime=context.currentTime;b.used=now();node.onended=()=>{if(s.node===node){heard(s);s.node=null;s.state=0x1014;s.offset=0;}};node.start(0,s.offset);}
  return {context,buffer,sources,buffers,resume:()=>resumeAudio(context),
    /** What is held now: the total counted against the limit, the browser's bytes for it, and the decodes and drops. */
    held(){let memory=0,n=0;for(const b of buffers.values())if(b.bytes){n++;memory+=browserBytes(b.decoded);}return {bytes:held,limit,memory,count:n,...stats};},
    music(id,path){const s=sources.get(id);stopNode(s);s.offset=0;s.buffer=entry(path);s.music=true;play(s);},
    call(op,a,b,c){const s=sources.get(a);
      switch(op){
        case 'buffer':{const id=next++;buffers.set(id,{id,decoded:null,promise:Promise.resolve(null),bytes:0});return id;}
        case 'source':return source();
        case 'deleteBuffer':{const gone=buffers.get(a);if(gone){buffers.delete(a);held-=gone.bytes;gone.bytes=0;if(gone.path&&byPath.get(gone.path)===a)byPath.delete(gone.path);}return 0;}
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
