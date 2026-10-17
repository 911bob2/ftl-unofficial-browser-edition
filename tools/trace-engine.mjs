import fs from 'node:fs';
const map=JSON.parse(fs.readFileSync('.build/current-engine/engine.js.map','utf8'));
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/',positions=[];
let source=0,line=0,column=0,name=0;
for(const [generatedLine,row] of map.mappings.split(';').entries()){
  let generatedColumn=0;
  for(const segment of row.split(',')){
    if(!segment)continue;
    const values=[];let value=0,shift=0;
    for(const char of segment){const digit=alphabet.indexOf(char);value|=(digit&31)<<shift;if(digit&32){shift+=5;continue;}values.push(value&1?-(value>>>1):value>>>1);value=0;shift=0;}
    generatedColumn+=values[0];if(values.length<4)continue;
    source+=values[1];line+=values[2];column+=values[3];if(values.length>4)name+=values[4];
    positions.push({generatedLine,generatedColumn,source,line,column,name});
  }
}
for(const argument of process.argv.slice(2)){
  const [gl,gc]=argument.split(':').map(Number);const point=positions.filter(p=>p.generatedLine===gl-1&&p.generatedColumn<=gc-1).at(-1);
  console.log(argument,point?`${map.sources[point.source]}:${point.line+1}:${point.column+1} ${map.names?.[point.name]??''}`:'unmapped');
}
