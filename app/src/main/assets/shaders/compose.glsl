uniform sampler2D uScene, uPrev;
uniform vec2 uRes, uShift;
uniform float uZoom, uRot, uDecay, uAberr, uFlash, uAggr;
void main(){
  vec2 uv = gl_FragCoord.xy/uRes, c = uv - .5;
  float a = uRes.x/uRes.y;
  vec3 s = vec3(texture(uScene, uv - c*uAberr).r, texture(uScene, uv).g, texture(uScene, uv + c*uAberr).b);
  s = max(hueRot(s, uAggr*uAggr*0.9), 0.)*(1. + uAggr*0.3);              // colours shift and deepen with intensity
  float sl = luma(s); s = max(mix(vec3(sl), s, 1. + uAggr*0.4), 0.);
  vec2 pc = c*vec2(a, 1.);
  pc = rot(uRot)*pc/uZoom; pc.x /= a;
  vec2 puv = pc + .5 - uShift;
  float inb = step(0.,puv.x)*step(puv.x,1.)*step(0.,puv.y)*step(puv.y,1.);
  vec3 pv = texture(uPrev, puv).rgb*uDecay*inb;
  vec3 col = max(s, pv);
  col *= 1. + uFlash*3.;                                                  // flashes only brighten what is lit
  o = vec4(col, 1.);
}
