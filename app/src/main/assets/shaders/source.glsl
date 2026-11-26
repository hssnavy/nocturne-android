
uniform sampler2D uSpec;
uniform vec2 uRes;
uniform float uTime, uPhase, uBass, uHigh, uLevel, uKick, uGen;
void main(){
  vec2 frag = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec2 q = (frag-uRes*0.5)/min(uRes.x,uRes.y);
  float t = uTime;
  vec3 col = vec3(0.);
  float xs = uRes.x/min(uRes.x,uRes.y);
  float xn = clamp(q.x/xs + 0.5, 0., 1.);
  float sp = texture(uSpec, vec2(xn, 0.5)).r;
  if(uGen < 1.5){ // Ember Line
    for(int i=0;i<3;i++){
      float fi = float(i);
      float y = (fbm(vec2(q.x*1.4 + uPhase*0.08 + fi*3.1, fi*2.))-0.5)*0.55
              + sp*0.22*sin(q.x*7. + t*1.7 + fi*2.)*(1.-fi*0.3) - 0.05;
      float dd = abs(-q.y - y + fi*0.035);
      float g = 0.0014/(dd+0.0018)*(uLevel*1.2 + uKick*0.6)*(1.-fi*0.3);
      vec3 c = (i==2) ? vec3(0.18,0.32,1.0) : mix(vec3(1.,0.48,0.14), vec3(1.,0.3,0.08), fi);
      col += c*g*0.55;
    }
    col *= 1.-smoothstep(0.25, 0.95, abs(q.x/xs)*2.);
  } else if(uGen < 2.5){ // Ink Smoke
    vec2 p = q*1.7 + vec2(0.4,0.);
    float tt = uPhase*0.05;
    vec2 w = vec2(fbm(p+tt), fbm(p+vec2(3.1,1.7)-tt));
    float f = fbm(p + w*2.2 + vec2(tt*0.5,-tt));
    float v = pow(clamp((f-0.46)*3.2, 0., 1.), 2.2);
    v *= uLevel*0.5 + uBass*0.9 + uKick*0.4;
    v *= smoothstep(-0.9, 0.3, q.x);
    col = mix(vec3(0.12,0.26,0.5), vec3(0.82,0.9,1.0), clamp(v,0.,1.))*v;
  } else { // Halo
    vec2 cc = q - vec2(0.12, 0.);
    float r = length(cc), a = atan(cc.y, cc.x);
    float Rr = 0.32 + uBass*0.03 + uKick*0.02;
    float n = noise(vec2(a*3. + 10., t*0.4 + uPhase*0.05));
    float ring = 0.0016/(abs(r-Rr)+0.002)*(0.35+n)*uLevel*1.2;
    float side = 0.5 + 0.5*cos(a - 2.3);
    col = mix(vec3(0.08,0.55,0.75), vec3(1.0,0.3,0.12), side)*ring*0.6;
    vec2 cell = floor(frag/3.);
    float h = hash(cell);
    float near = exp(-pow((r-Rr-0.03)/0.05, 2.));
    col += vec3(0.9)*step(0.992, h)*near*(0.3+uHigh*2.)*noise(cell*0.3+t*5.);
  }
  o = vec4(clamp(col, 0., 1.), 1.);
}