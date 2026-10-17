export function createGL(canvas) {
  const gl=canvas.getContext('webgl2',{alpha:false,depth:true,stencil:true,antialias:false,preserveDrawingBuffer:true});
  if(!gl)throw new Error('WebGL2 is required. Enable hardware acceleration in your browser.');
  gl.viewport(0,0,1280,720);gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
  const objects=[null],uniforms=new Map(),recent=[];
  const obj=id=>id>0?objects[id]:null;
  const add=value=>{if(value===null)return -1;objects.push(value);return objects.length-1;};
  const resolveFirst=new Set(['compileShader','getShaderParameter','getShaderInfoLog','deleteShader','linkProgram','getProgramParameter','getProgramInfoLog','deleteProgram','useProgram','deleteTexture','deleteBuffer','deleteVertexArray','bindVertexArray']);
  return {gl,obj,objects,
    call(op,a,b,c,d,e,f){
      if(op==='getError'){const error=gl.getError();if(error)console.error('WebGL error',error,recent.slice(-12));return error;}
      recent.push([op,a,b,c,d,e,f]);if(recent.length>12)recent.shift();
      let args=[a,b,c,d,e,f];
      if(op==='createProgram'||op==='createTexture'||op==='createBuffer'||op==='createVertexArray')return add(gl[op]());
      if(op==='createShader')return add(gl.createShader(a));
      if(op==='getShaderParameter'||op==='getProgramParameter'){
        if(b===0x8B84)return (gl[op==='getShaderParameter'?'getShaderInfoLog':'getProgramInfoLog'](obj(a))||'').length;
        return Number(gl[op](obj(a),b));
      }
      if(resolveFirst.has(op))args[0]=obj(a);
      if(op==='attachShader'){args[0]=obj(a);args[1]=obj(b);}
      if(op==='bindTexture'||op==='bindBuffer')args[1]=obj(b);
      if(op==='uniform1i')args[0]=obj(a);
      if(op==='vertexAttribPointer')args[3]=Boolean(d);
      const result=gl[op](...args);return Number(result)||0;
    },
    text(op,id,text){if(op==='shaderSource'){gl.shaderSource(obj(id),text);return 0;}if(op==='getUniformLocation'){const key=id+':'+text;if(!uniforms.has(key))uniforms.set(key,add(gl.getUniformLocation(obj(id),text)));return uniforms.get(key);}return gl.getAttribLocation(obj(id),text);},
    log(op,id){return gl[op](obj(id))||'';}
  };
}
