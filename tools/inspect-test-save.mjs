// Reads a test account's save from a disposable fixture (save model 2, docs/SAVES.md): the save named by the first
// argument (its id), or the most recently changed one with a run. Writes .build/test-cloud-save.json and
// .build/engine-run.xml and prints a summary.
import {writeFile} from 'node:fs/promises';
const origin='http://localhost:8081';let cookies=new Map();
async function request(path,body,token){const r=await fetch(origin+path,{method:body?'POST':'GET',headers:{Cookie:[...cookies].map(([k,v])=>k+'='+v).join('; '),Origin:origin,...(body?{'Content-Type':'application/json','X-CSRF-TOKEN':token}:{})},body:body?JSON.stringify(body):undefined});for(const c of r.headers.getSetCookie()){const pair=c.split(';')[0],p=pair.indexOf('=');cookies.set(pair.slice(0,p),pair.slice(p+1));}if(!r.ok)throw Error(path+': '+r.status);return r.json();}
const {token}=await request('/api/auth/csrf');await request('/api/auth/login',{username:'user10',password:'isolated-player-test-password-2026'},token);
const {saves}=await request('/api/saves');
const chosen=process.argv[2]?saves.find(s=>s.id===process.argv[2]):saves.filter(s=>s.hasRun).sort((a,b)=>b.updatedUtc.localeCompare(a.updatedUtc))[0];
if(!chosen)throw Error(process.argv[2]?'No save '+process.argv[2]:'No save with a run');
const state=await request('/api/saves/'+chosen.id);
await writeFile('.build/test-cloud-save.json',JSON.stringify(state));await writeFile('.build/engine-run.xml',state.run??'');
console.log({id:state.id,name:state.name,setup:state.setup.key,revision:state.revision,runBytes:state.run?.length,ship:state.run?.match(/<playerShip[^>]+/)?.[0],crew:(state.run?.match(/<crewMember /g)??[]).length,rng:state.run?.match(/browserRngState="\d+"/)?.[0]});
