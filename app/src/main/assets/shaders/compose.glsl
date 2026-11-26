
uniform sampler2D uScene, uPrev;
uniform vec2 uRes, uShift;
uniform float uZoom, uRot, uDecay, uAberr, uFlash;
uniform vec3 uFlashCol;
uniform float uRed, uWarp, uHue, uTime, uAggr;
void main(){
  vec2 uv = gl_FragCoord.xy/uRes, c = uv-.5;
  float a = uRes.x/uRes.y;
  vec3 s = vec3(texture(uScene, uv - c*uAberr).r, texture(uScene, uv).g, texture(uScene, uv + c*uAberr).b);
  s = max(hueRot(s, uAggr*uAggr*0.9), 0.)*(1. + uAggr*0.25);
  float sl = luma(s); s = max(mix(vec3(sl), s, 1. + uAggr*0.35), 0.);
  vec2 pc = c*vec2(a,1.);
  float cr = cos(uRot), sr = sin(uRot);
  pc = mat2(cr,-sr,sr,cr)*pc/uZoom;
  pc += uWarp*0.004*vec2(sin(pc.y*5.+uTime*0.3), sin(pc.x*5.-uTime*0.25));
  pc.x /= a;
  vec2 puv = pc + .5 - uShift;
  float inb = step(0.,puv.x)*step(puv.x,1.)*step(0.,puv.y)*step(puv.y,1.);
  vec3 pv = hueRot(texture(uPrev, puv).rgb, uHue)*uDecay*inb;
  vec3 col = max(s, pv) + uFlashCol*uFlash;
  col = mix(col, vec3(1.,0.06,0.02)*luma(col)*2.4, uRed*0.8);
  o = vec4(col, 1.);
}