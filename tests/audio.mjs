import assert from 'node:assert/strict';
import {audioClockChecks} from '../web/self-tests.mjs';
import {createAudio} from '../web/audio.mjs';
console.log(await audioClockChecks());

// Decoded sounds are held up to 192 MiB as the Android app counts them (16-bit samples at the file's rate), dropped
// least recently heard first, music before effects, never one that a source plays, fades or is paused on; a dropped
// track is decoded again when it comes back. The held total is summed here from the buffers themselves.
async function musicMemoryChecks(){
  const LIMIT=192*1048576,seconds=300,size=seconds*44100*2*2,tick=async()=>{for(let i=0;i<6;i++)await new Promise(resolve=>setTimeout(resolve,0));};
  // An Ogg Vorbis page with the identification header (44.1 kHz stereo); the decode outputs 48 kHz float samples.
  const ogg=name=>{const b=new Uint8Array(44);b.set([0x4f,0x67,0x67,0x53]);b[26]=1;b[27]=30;b[28]=1;b.set([...'vorbis'].map(c=>c.charCodeAt(0)),29);b[39]=2;new DataView(b.buffer).setUint32(40,44100,true);return Object.assign(b.buffer,{name});};
  const decoded=[];let touched=0;
  const context={currentTime:0,state:'running',resume:async()=>{},
    decodeAudioData:async bytes=>{decoded.push(bytes.name);const s=bytes.name.endsWith('zap.ogg')?1:seconds;return {name:bytes.name,duration:s,sampleRate:48000,length:48000*s,numberOfChannels:2,getChannelData(){touched++;return new Float32Array(1);}};},
    createGain:()=>({gain:{value:1,setValueAtTime(value){this.value=value;}},connect(){},disconnect(){}}),
    createBufferSource:()=>({playbackRate:{value:1},connect(){},disconnect(){},start(){},stop(){}})};
  const names=[];for(let i=1;i<=6;i++)names.push('audio/music/t'+i+'_explore.ogg','audio/music/t'+i+'_battle.ogg');
  const audio=createAudio({audio:names.map(name=>({name})).concat([{name:'audio/waves/zap.ogg'}])},async a=>ogg(a.name),context);
  const heldBytes=()=>{let total=0;for(const b of audio.buffers.values())if(b.decoded)total+=b.decoded.name.endsWith('zap.ogg')?44100*4:size;return total;};
  const playing=source=>audio.sources.get(source).node?.buffer?.name;
  const a=audio.call('source'),b=audio.call('source'),fx=audio.call('source'),track=(i,battle)=>'audio/music/t'+i+(battle?'_battle':'_explore')+'.ogg';
  const zap=audio.buffer('audio/waves/zap.ogg');audio.call('sourcei',fx,0x1009,zap);audio.call('play',fx);await tick();audio.call('stop',fx);
  // SoundManager's cross-fade: the new track on one music source while the other fades out, then it stops.
  let on=a,off=b;
  const cross=async path=>{[on,off]=[off,on];audio.music(on,path);await tick();
    assert.equal(playing(on),path,'The new track plays');
    const fading=audio.sources.get(off);if(fading.state===0x1012)assert.ok(audio.buffers.get(fading.buffer)?.decoded,'A fading track stays decoded');
    assert.ok(heldBytes()<=LIMIT,'At most 192 MiB of decoded sound is held: '+(heldBytes()/1048576).toFixed(1)+' MiB');
    audio.call('stop',off);};
  for(let i=1;i<=6;i++){const before=decoded.length;await cross(track(i,false));await cross(track(i,true));await cross(track(i,false));
    assert.equal(decoded.length,before+2,'Sector '+i+': each version decodes once, the combat toggles keep the pair');}
  assert.ok(audio.buffers.get(zap).decoded,'Music is dropped before an effect');
  assert.equal(touched,decoded.length*2,'Every decoded channel is handed to script (so a dropped one can be collected)');
  // A paused track is not dropped while other tracks come.
  audio.call('pause',on);const paused=audio.buffers.get(audio.sources.get(on).buffer);
  for(const i of [1,2,3]){audio.music(off,track(i,true));await tick();}
  assert.ok(paused.decoded,'A paused track stays decoded');audio.call('stop',off);audio.call('play',on);await tick();
  // The first sector's explore track was dropped: it is decoded again and plays.
  const again=decoded.length;await cross(track(1,false));
  assert.equal(decoded.length,again+1,'A dropped track is decoded again');assert.equal(playing(on),track(1,false),'The track decoded again plays');
  assert.ok(audio.held().drops>0&&audio.held().again>0&&audio.held().bytes<=LIMIT,'held(): '+JSON.stringify(audio.held()));
  // Track changes within one engine step decode only the track the step ends on.
  const burst=decoded.length;audio.music(on,track(4,false));audio.music(on,track(5,false));audio.music(on,track(2,false));await tick();
  assert.deepEqual(decoded.slice(burst),[track(2,false)],'Only the last track of a burst is decoded');assert.equal(playing(on),track(2,false));
  return 'PASS music memory: 192 MiB held as Android counts it, oldest first, music before effects, playing, fading and paused tracks kept, a dropped track decoded again, a burst decodes its last track';
}
console.log(await musicMemoryChecks());
