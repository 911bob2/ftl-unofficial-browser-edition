import {mkdir,writeFile} from 'node:fs/promises';
const out=new URL('../docs/parity/',import.meta.url);await mkdir(out,{recursive:true});
const issues=[];
for(let page=1;page<=5;page++){
  const response=await fetch(`https://api.github.com/repos/adamkulik/pocket-wormhole/issues?state=all&per_page=100&page=${page}`,{headers:{'User-Agent':'FTL-browser-parity-audit','Accept':'application/vnd.github+json'}});
  if(!response.ok)throw Error('Upstream issue inventory returned '+response.status);
  const entries=await response.json();if(!entries.length)break;
  for(const i of entries)if(!i.pull_request)issues.push({id:i.number,title:i.title,source:i.html_url,upstreamState:i.state,verification:'requires local audit',expected:'Resolve the reported official desktop behavior if the local fork still differs',regression:'Reproduce the issue against the maintained sources before classifying it as missing or incorrect'});
}
const docs=[];let documentationError=null;
try{
  const response=await fetch('https://gitlab.com/api/v4/projects/znixian%2Fxftl/repository/tree?path=doc&recursive=true&per_page=100');
  if(!response.ok)throw Error('GitLab documentation inventory returned '+response.status);
  for(const f of await response.json())if(f.type==='blob')docs.push({path:f.path,source:'https://gitlab.com/znixian/xftl/-/blob/master/'+f.path,verification:'requires local audit'});
}catch(e){documentationError=e.message;}
await writeFile(new URL('upstream-inventory.json',out),JSON.stringify({schema:1,retrievedAt:new Date().toISOString(),pocketSource:'001b19d',issues:issues.sort((a,b)=>a.id-b.id),projectWormholeDocumentation:docs,documentationError},null,2)+'\n');
console.log(JSON.stringify({issues:issues.length,documents:docs.length,documentationError}));
