// Call immediately from the user gesture, and consume Firefox's rejection when
// navigation closes a context before its pending resume completes.
export async function resumeAudio(context,onError=error=>{
  console.error('Audio resume failed',error);
  window.ftlDebugEvent?.('runtime','audio.resume.failed',{message:error.message},'error');
}) {
  try {await context.resume();return true;}
  catch(error) {if(context.state!=='closed')onError(error);return false;}
}

export function createAudio(manifest,fetchAsset,audioContext) {
  const context=audioContext??new AudioContext(),paths=new Map(manifest.audio.map(a=>[a.name,a])),buffers=new Map(),byPath=new Map(),sources=new Map();let next=1;
  function buffer(path){if(byPath.has(path))return byPath.get(path);const a=paths.get(path);if(!a)throw Error('Missing audio '+path);const id=next++;byPath.set(path,id);const state={decoded:null,promise:null};state.promise=fetchAsset(a).then(b=>context.decodeAudioData(b)).then(decoded=>state.decoded=decoded);state.promise.catch(e=>console.error('Audio decode failed',path,e));buffers.set(id,state);return id;}
  function source(){const id=next++,gain=context.createGain();gain.connect(context.destination);sources.set(id,{gain,buffer:0,node:null,loop:false,pitch:1,state:0x1011,offset:0,startTime:0,epoch:0});return id;}
  function position(s){return s.state===0x1012&&s.node?s.offset+(context.currentTime-s.startTime)*s.pitch:s.offset;}
  function stopNode(s){if(s.node){s.node.onended=null;s.node.stop();s.node.disconnect();s.node=null;}s.epoch++;}
  async function play(s){const epoch=++s.epoch;s.state=0x1012;const b=buffers.get(s.buffer);if(!b){s.state=0x1014;return;}let audio;try{audio=await b.promise;}catch{s.state=0x1014;return;}if(epoch!==s.epoch||s.state!==0x1012)return;try{await context.resume();}catch(e){if(epoch!==s.epoch||context.state==='closed')return;s.state=0x1014;console.error('Audio resume failed',e);return;}if(epoch!==s.epoch)return;const node=context.createBufferSource();node.buffer=audio;node.loop=s.loop;node.playbackRate.value=s.pitch;node.connect(s.gain);s.node=node;s.offset=Math.max(0,s.offset)%audio.duration;s.startTime=context.currentTime;node.onended=()=>{if(s.node===node){s.node=null;s.state=0x1014;s.offset=0;}};node.start(0,s.offset);}
  return {context,buffer,sources,buffers,resume:()=>resumeAudio(context),
    music(id,path){const s=sources.get(id);stopNode(s);s.offset=0;s.buffer=buffer(path);play(s);},
    call(op,a,b,c){const s=sources.get(a);
      switch(op){
        case 'buffer':{const id=next++;buffers.set(id,{decoded:null,promise:Promise.resolve(null)});return id;}
        case 'source':return source();
        case 'deleteBuffer':buffers.delete(a);return 0;
        case 'deleteSource':if(s){stopNode(s);s.gain.disconnect();sources.delete(a);}return 0;
        case 'sourcei':if(!s)return 0;if(b===0x1009){stopNode(s);s.buffer=c;s.offset=0;s.state=0x1011;}if(b===0x1007){s.loop=!!c;if(s.node)s.node.loop=s.loop;}return 0;
        case 'sourcef':if(!s)return 0;if(b===0x100a)s.gain.gain.setValueAtTime(c,context.currentTime);if(b===0x1003){s.offset=position(s);s.startTime=context.currentTime;s.pitch=c;if(s.node)s.node.playbackRate.value=c;}if(b===0x1024){const playing=s.state===0x1012;stopNode(s);s.offset=c;if(playing)play(s);}return 0;
        case 'play':if(s){stopNode(s);play(s);}return 0;
        case 'pause':if(s){s.offset=position(s);stopNode(s);s.state=0x1013;}return 0;
        case 'stop':if(s){stopNode(s);s.offset=0;s.state=0x1014;}return 0;
        case 'getSource':if(!s)return 0;if(b===0x1010)return s.state;if(b===0x1024)return position(s);if(b===0x100a)return s.gain.gain.value;if(b===0x1003)return s.pitch;return 0;
        case 'getBuffer':return buffers.get(a)?.decoded?.length||0;
      }return 0;
    }
  };
}
