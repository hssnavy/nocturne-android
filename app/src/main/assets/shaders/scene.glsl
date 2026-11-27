
uniform sampler2D uTex, uSpec, uPeak, uWave, uTone, uToneS;
uniform vec3 uPal0, uPal1, uPal2, uPal3;
uniform vec2 uRes, uShake;
// lows / mids / highs: smoothed level (1 ~ typical for this song), hits (0-1, transients), and their own clocks
uniform float uBgT, uE;
uniform float uLowA, uWaveK, uNStr, uCore, uKAge, uTravel, uSpeed, uSpin, uHyE; uniform float uStr[7]; uniform float uFlow[7]; uniform float uSHit[7]; uniform float uSAge[7]; uniform float uSWK[7]; uniform float uSGlow[7]; uniform float uSY[7];
uniform float uLow, uMid, uHigh, uLowHit, uMidHit, uHighHit, uLowT, uMidT, uHighT;
// 10-band equaliser channels (31 Hz ... 16 kHz) + the .1 sub channel (index 10)
uniform float uCh[11]; uniform float uChHit[11];
uniform float uEnergy, uInt, uTime, uAssemble, uAggr;
uniform float uMode, uBars, uLayout, uBassMid, uSeg, uColor, uPeakOn, uImgAsp;

float tn(float x){ return texture(uTone, vec2(clamp(x,0.002,0.998),.5)).r*1.3; }
float tnS(float x){ return texture(uToneS, vec2(clamp(x,0.002,0.998),.5)).r*1.3; }
// string position (0-1, a third each for lows, mids, highs) -> place on the 20 Hz-20 kHz tone map; borders at 250 Hz and 4 kHz
float bandPos(float u, bool w2){ u = clamp(u, 0., 1.);
  vec4 P = w2 ? vec4(0.1, 0.3656, 0.767, 0.967) : vec4(0., 0.3656, 0.767, 1.);
  float t = u*3.; return t < 1. ? mix(P.x, P.y, t) : t < 2. ? mix(P.y, P.z, t - 1.) : mix(P.z, P.w, t - 2.); }
float sp(float x){ return texture(uSpec, vec2(clamp(x,0.004,0.996),.5)).r; }
float pk(float x){ return texture(uPeak, vec2(clamp(x,0.004,0.996),.5)).r; }
vec3 pal(float t){ t = clamp(t + uAggr*0.45, 0., 1.)*3.;
  if(t < 1.) return mix(uPal0,uPal1,t); if(t < 2.) return mix(uPal1,uPal2,t-1.); return mix(uPal2,uPal3,t-2.); }
vec3 tex(vec2 uv){ return texture(uTex, uv).rgb; }
vec3 shade(vec2 uv, float t, float e){
  vec3 p = pal(t);
  vec3 tc = tex(uv); float lm = smoothstep(0.05, 0.3, luma(tc));
  if(uColor < 0.5) return tc*lm*2.4 + p*0.15*lm*e;
  if(uColor < 1.5) return p*(0.35+e*0.8);
  if(uColor < 2.5) return p*lm*(0.6+e*1.2);
  return hueRot(p, uMidT*0.5 + t*4.)*(0.45+e*0.9);
}
vec2 cover(vec2 q){ float a=uRes.x/uRes.y; float s=max(a/uImgAsp,1.); return vec2(q.x/(uImgAsp*s)+0.5, 0.5 - q.y/s); }
float gapm(float w){ return smoothstep(0.06,0.16,w)*(1.-smoothstep(0.84,0.94,w)); }
float height(float f){ return min(pow(f,0.9)*uInt*0.72, 1.); }
mat2 rot(float a){ return mat2(cos(a),-sin(a),sin(a),cos(a)); }
// position on the log spectrum axis (0-1, 30 Hz-16 kHz) -> channel 0-9, blended smoothly between neighbours
float chPos(float fx){ float f = 30.*pow(533.333, clamp(fx,0.,1.)); return clamp(log2(f/22.) - 0.5, 0., 9.); }
float chMix(float b, bool hitv){ int i = int(floor(b)); int j = min(i+1, 9); float t = smoothstep(0.,1.,fract(b));
  return hitv ? mix(uChHit[i], uChHit[j], t) : mix(uCh[i], uCh[j], t); }
float chL(float fx){ return chMix(chPos(fx), false); }
float chH(float fx){ return chMix(chPos(fx), true); }

vec3 barShade(float y, float h, float pv, float g, float ft, vec2 tuv, float glint){
  vec3 col = vec3(0.);
  float inside = g*step(0.,y)*step(y,h);
  if(uSeg > 0.5) inside *= step(0.3, fract(y*26.));
  vec3 c = shade(tuv, ft, h)*(0.4 + 1.2*clamp(y/max(h,0.02),0.,1.));
  col += inside*c;
  float cap = g*(1.-smoothstep(0.,0.016,abs(y-h)))*step(0.015,h);
  col += cap*mix(c,vec3(1.),0.55)*(1. + glint*2.5);          // highs make the caps glint
  if(uPeakOn > 0.5) col += g*(1.-smoothstep(0.,0.012,abs(y-pv-0.025)))*step(0.03,pv)*pal(ft)*1.7;
  col += g*step(h,y)*exp(-(y-h)*14.)*c*0.3;
  return col;
}

void main(){
  vec2 q = (gl_FragCoord.xy - uRes*0.5)/uRes.y + uShake;
  float a = uRes.x/uRes.y;
  vec3 col = vec3(0.);
  float x01 = q.x/a + 0.5;
  float lvl = uLow*0.5 + uMid*0.3 + uHigh*0.2;

  if(uMode < 0.5){ // SPECTRUM BARS  lows: punch · mids: texture flow · highs: cap glints
    float n = uBars, fi = floor(x01*n), xc = (fi+.5)/n, w = fract(x01*n);
    float fx = uBassMid > 0.5 ? abs(xc-.5)*2. : xc;
    float g = gapm(w)*step(0.,x01)*step(x01,1.);
    float punch = 1. + uLowHit*0.18*uInt;
    float hb = chH(fx);
    float h = min(height(sp(fx))*punch*(1. + hb*0.3*uInt), 1.), pv = height(pk(fx));
    float y, refl = 0.;
    if(uLayout < 0.5){ y = (q.y+0.4)/0.86; if(y < 0.){ refl = 1.; y = -y*3.; } }
    else if(uLayout < 1.5){ y = abs(q.y)/0.47; }
    else { y = (0.4-q.y)/0.86; if(y < 0.){ refl = 1.; y = -y*3.; } }
    float glint = max(uHighHit*step(0.55, hash(vec2(fi, floor(uHighT*6.)))), hb);
    col = barShade(y, h, pv, g, fx, vec2(xc*0.9+0.05, 1.-y*0.85 + uMidT*0.25), glint);
    if(refl > 0.5) col *= 0.2*(1.-smoothstep(0.,1.,y));
  }
  else if(uMode < 1.5){ // RADIAL  lows: core size + shockwave · mids: rotation · highs: sparks
    float r = length(q), ang = atan(q.y,q.x);
    float a01 = fract(ang/TAU + 0.25 + uMidT*0.04);
    float n = uBars*2., fi = floor(a01*n), ac = (fi+.5)/n, w = fract(a01*n);
    float fs = abs(ac*2.-1.); if(uBassMid > 0.5) fs = 1.-fs;
    float g = gapm(w);
    // SOLID RING = the beat: jumps out on each kick, then keeps shrinking (nothing else moves it)
    // FRAGMENTED RING = the spectrum: segments on a fixed base circle, each one's length = its frequency
    float R0 = 0.07 + uCore*0.12;
    float Rb = 0.215;                                                   // fixed base of the fragments (doesn't pump with the kick)
    float hb = chH(fs);
    float h = min(height(sp(fs))*(1. + hb*0.3*uInt), 1.), pv = height(pk(fs));
    float y = (r-Rb)/0.33;
    col = barShade(y, h, pv, g, fs, vec2(ac, 1.-y + uMidT*0.2), max(uHighHit*step(0.6, hash(vec2(fi, floor(uHighT*6.)))), hb));
    if(uLayout > 0.5) col += barShade((Rb-r)/0.1, h, pv, g, fs, vec2(ac, y), 0.)*0.6*step(R0 + 0.01, r);
    float rn = r/max(R0,0.01);
    vec2 cuv = vec2(a01*2. + rn*0.7, rn*0.45 - uMidT*0.15);
    col += max(shade(cuv, rn, lvl), pal(rn)*0.12*uCore)*(uCore*1.2)*(1.-smoothstep(R0-0.012,R0,r));
    float ringW = 0.004 + uCore*0.006;                                  // the solid ring itself: a clear, unbroken circle
    col += pal(0.)*(1. - smoothstep(ringW*0.5, ringW, abs(r-R0)))*(0.15 + uCore*1.6);
    col += pal(0.)*exp(-abs(r-R0)*70.)*uCore*0.6;
    float sw = R0 + (1.-uLowHit)*0.7;                                     // shockwave on every kick
    col += pal(0.2)*exp(-abs(r-sw)*60.)*uLowHit*uLowHit*0.9*min(1., uCore*1.5);   // shockwave only with a real kick
    float bin = floor(fract(ang/TAU)*180.), h1 = hash(vec2(bin,3.1));
    float pr = R0 + fract(h1*5. + uHighT*(0.25+h1*0.4))*0.8;
    col += step(0.45,h1)*(1.-smoothstep(0.,0.008,abs(r-pr)))*(1.-smoothstep(0.1,0.4,abs(fract(fract(ang/TAU)*180.)-0.5)))*pal(h1)*(uHigh*2.2 + uHighHit*1.5);
  }
  else if(uMode < 2.5 || uMode > 9.5){ // WAVE (and WAVE 2)  ten sections, one per channel: 31 Hz on the left ... 16 kHz on the right; .1 sub lifts the whole line
    // BACKGROUND (the strings are untouched): living smoke whose amount, drift, colour and shadows follow the energy.
    // Calm: a faint, slow, cool haze. Rising energy: thicker smoke that drifts faster, warms in hue and casts deeper
    // moving shadows; each kick puffs it a little.
    float E = uEnergy;
    float smoke = 0.16 + 1.3*E*E + uCore*0.3*E;
    vec2 wv = vec2(fbm(q*1.6 + vec2(uBgT*0.5, 0.)), fbm(q*1.6 + vec2(4., uBgT*0.37))) - 0.5;
    vec2 wv2 = vec2(fbm(q*0.8 - vec2(uBgT*0.23, 1.3)), fbm(q*0.8 + vec2(2.1, -uBgT*0.19))) - 0.5;   // a slower, deeper layer
    vec3 sm = textureLod(uTex, cover(q*0.6 + wv*(0.8 + E*0.6)), 5.).rgb;
    vec3 sm2 = textureLod(uTex, cover(q*0.35 + wv2*(0.6 + E*0.5) + 0.3), 6.).rgb;
    vec3 bg = sm*smoothstep(0.02, 0.12, luma(sm))*0.7 + sm2*smoothstep(0.02, 0.12, luma(sm2))*0.35*(0.4 + E);
    bg = hueRot(bg, sin(uBgT*0.11)*0.22 + E*0.3);                                // hue drifts, and shifts as energy rises
    float sh = smoothstep(0.42, 0.72, fbm(q*1.2 + vec2(-uBgT*0.3, uBgT*0.12)));  // moving shadows
    bg *= 1. - sh*(0.25 + 0.55*E);
    col += bg*smoke;
    float X = q.x;
    float xe = clamp((x01 - 0.06)/0.88, 0., 1.);
    float bp = clamp(xe*10. - 0.5, 0., 9.);                       // channel under this point
    float cl = chMix(bp, false), chh = chMix(bp, true);
    float env = 1. - smoothstep(0.42, 0.5, abs(x01-0.5));
    // 1, 3, 5 or 7 strings; together they cover 20 Hz-20 kHz, each string its own slice (lowest at the bottom string).
    //  - each string's height comes from its own slice; the mids make them flow, each at its own pace;
    //    the highs shorten the wavelength (low strings long waves, high strings tight ones)
    //  - waves start at the RIGHT edge and travel left; a steady sound freezes a string into a standing wave
    //  - every tone in a string's slice shapes that string along its length
    //  - BEAT: every hit in a string's slice punches it and sends a pulse running from the right along it
    float Xr = (0.5 - x01)*uRes.x/uRes.y;                          // distance from the right edge
    float envR = smoothstep(0., 0.05, 1. - x01)*(1. - smoothstep(0.9, 1., 1. - x01));
    float capE = 0.10 + 0.26*uEnergy*uEnergy;
    float n = uNStr;
    bool W2 = uMode > 9.5;
    for(int k=0;k<7;k++){
      float fk = float(k);
      if(fk >= n) break;
      float fr = n > 1.5 ? fk/(n - 1.) : 0.5;                       // 0 = lowest string, 1 = highest
      float hit = uSHit[k], age = uSAge[k];
      float kick = k == 0 ? uCore : 0.;                              // the kick drum lives in the lowest slice: only that string takes it
      float amp = 0.85*min(min(uStr[k]*(0.06 + 0.14*uEnergy), capE)*(1. + 0.7*hit + 0.6*kick), 0.34);   // every string drawn to the same scale   // never off the screen (overall 15% lower)
      float s0 = max(0., (fk - 0.075)/n), s1 = min(1., (fk + 1.075)/n);   // its part of the spectrum (overlaps a little)
      float tp = bandPos(s0 + (1. - x01)*(s1 - s0), W2);          // lows 20-250 Hz, mids 250 Hz-4 kHz, highs 4-20 kHz (Wave 2: 40 Hz-16 kHz)
      float tl = tnS(tp);
      float w = uSWK[k];                                            // its own wavelength (only its own frequencies change it)
      float flow = uFlow[k];
      float y = sin(Xr*w - flow + fk*2.1)*0.7 + sin(Xr*w*0.43 - flow*0.6 + fk*1.3)*0.3;
      y *= 0.3 + 0.95*tl;
      if(W2) y = sign(y)*pow(abs(y), 1.7)*1.6;                      // Wave 2: accented: sharp, tall crests, quieter in between
      else y += sin(Xr*(18. + fr*28.) - flow*1.7 + fk)*tl*0.22;    // (Wave 2 drops this fine ripple, for clarity)
      float crest = W2 ? abs(y) : 0.;
      y *= amp*envR;
      // every tone as its own peak: a note that stands out from its neighbours lifts the string right at its frequency.
      // Added on top (not multiplied by the wave), so a note can't vanish where the wave happens to cross the middle.
      float dl = 2.5/256.;
      float tc = W2 ? 0. : max(0., tl - 0.5*(tnS(tp - dl) + tnS(tp + dl)));   // Wave 2: no per-note bumps, cleaner line
      y += (tc*2.2 + tl*(W2 ? 0. : 0.3))*(0.03 + 0.05*uEnergy)*(1. + 0.7*fr)*envR;
      float calm = 0.15 + 0.85*smoothstep(0.1, 0.75, uE);          // low energy: the body of the strings calms down a lot
      y *= calm;
      // ...but a single hit in a calm moment still shows clearly, even MORE when the string is resting:
      // every drum hit in its part throws the whole string and sends a pulse in from the right
      float boost = 1. + 1.6*(1. - calm);
      float pd = (Xr - age*2.2)/0.09;                               // the beat pulse, running in from the right
      float pulse = hit*exp(-pd*pd)*(0.05 + 0.06*uEnergy)*boost*envR;
      float ring = W2 ? cos(age*TAU*4.5) : 1.;                     // Wave 2: the hit rings: the shape flips up, down, up...
      if(W2) y += pulse*sin(pd*2.4)*1.6;                            // a pulse that swings both sides of the line
      else y += pulse*(mod(fk, 2.) < 0.5 ? 1. : -1.);
      y += hit*ring*(sin(Xr*w*1.3 - flow*1.4 + fk)*0.7 + sin(Xr*w*2.7 - flow + fk*0.7)*0.3)*(0.025 + 0.03*uEnergy)*boost*envR*(W2 ? 1.6 : 1.);
      float kd = (Xr - uKAge*2.6)/0.14;                             // the kick drum's own wave, rolling in from the right
      if(W2) y += kick*sin(kd*2.2)*exp(-kd*kd*0.6)*(0.07 + 0.10*uEnergy)*(0.6 + 0.4*boost)*envR;   // swings down and up
      else y -= kick*exp(-kd*kd)*(0.05 + 0.08*uEnergy)*(0.6 + 0.4*boost)*envR;
      y += (fk - (n - 1.)*0.5)*0.012*min(1., 3./n);                 // strings sit just apart
      float d = abs(q.y - y);
      float thick = 0.0013 + tl*0.0022 + hit*0.0022 + tc*0.002 + crest*0.003;   // Wave 2: crests drawn bolder
      vec3 c = mix(pal(0.08 + 0.84*fr), vec3(1.), clamp(tl*0.4 + hit*0.35, 0., 0.7));
      col += c*(thick/(d+0.0022))*0.34*(uSGlow[k] + tl*0.5 + crest*0.8)*envR;        // glow: its own loudness and hits, lifted by the song's energy
    }
  }
  else if(uMode < 3.5){ // TUNNEL  lows: forward travel + ring pulses · mids: twist · highs: shimmer stripes
    float r = length(q), ang = atan(q.y,q.x);
    float depth = 0.3/(r+0.015);
    ang += uMidT*0.12 + sin(depth*0.4 + uMidT*0.5)*uMid*0.25;
    vec2 tu = vec2(ang/TAU*2., depth*0.3 + uLowT*0.6);
    vec3 c = shade(tu, fract(depth*0.08), lvl)*(uLow*0.6 + uMid*0.4);
    float ringf = fract(depth*0.6 + uLowT*0.9);
    c += pal(fract(depth*0.05))*(1.-smoothstep(0.,0.06,abs(ringf-0.5)))*(sp(fract(depth*0.05))*uInt*1.4 + chH(fract(depth*0.05))*1.4 + uChHit[10]*0.6);
    float stripes = pow(abs(sin(ang*24. + uHighT*0.6)), 40.);
    c += pal(0.9)*stripes*(uHigh*0.5 + uHighHit*0.6)*smoothstep(0.1,0.5,r);
    col = c*smoothstep(0.05,0.35,r)*uInt*0.9;
  }
  else if(uMode < 4.5){ // KALEIDOSCOPE  lows: zoom pulse · mids: spin · highs: glitter
    vec2 p = rot(uMidT*0.25)*q*(1.15 - uLowHit*0.08*uInt - uLow*0.05);
    float r = length(p), an = atan(p.y,p.x), N = 8., sg = TAU/N;
    an = mod(an, sg); an = abs(an - sg*0.5);
    vec2 fp = r*vec2(cos(an), sin(an));
    vec2 uv = fp*0.8 + vec2(0.3 + uMidT*0.03, 0.35);
    col = shade(uv, fract(r*1.2), lvl)*(uLow*0.55 + uMid*0.45)*uInt*0.9;
    float rr = 0.22 + height(sp(an/sg*2.))*0.25 + chH(an/sg*2.)*0.06;
    col += pal(fract(r*1.5))*(1.-smoothstep(0.,0.012,abs(r-rr)))*1.3*sp(an/sg*2.);
    float gl = step(0.985, hash(floor(fp*90.) + floor(uHighT*6.)));
    col += vec3(1.)*gl*(uHigh*1.2 + uHighHit)*smoothstep(0.03,0.2,luma(col));
  }
  else if(uMode < 5.5){ // SHATTER (unchanged behaviour)
    float uKick = uLowHit, uLevel = lvl;
    float sc = clamp(1. - uAssemble + uKick*0.3*(1.-uAssemble), 0., 1.);
    vec2 grid = vec2(30.,17.);
    vec2 p0 = cover(q);
    vec2 pw = p0 + (vec2(noise(p0*4.+uTime*0.15), noise(p0*4.+7.-uTime*0.15))-0.5)*0.05*sc;
    vec2 cell = floor(pw*grid), lf = fract(pw*grid);
    float tri0 = step(lf.x, lf.y);
    vec2 id = cell*2. + vec2(tri0, 0.);
    vec2 h = vec2(hash(id), hash(id+17.3));
    float h3 = hash(id+5.1);
    float spd = 0.25 + h.x*0.9;
    vec2 drift = vec2(noise(id*0.37 + uTime*spd), noise(id*0.53 + 11. - uTime*spd)) - 0.5;
    float shrink = 1. - sc*(0.25 + 0.55*h3);
    float sa = ((h.y-0.5)*6.2832 + (noise(id*0.71 + uTime*(0.2+h3*0.8))-0.5)*3.)*sc;
    vec2 lfs = rot(sa)*((lf - 0.5 - drift*sc*0.6)/shrink) + 0.5;
    float tri = step(lfs.x, lfs.y);
    float inside = step(0.,lfs.x)*step(lfs.x,1.)*step(0.,lfs.y)*step(lfs.y,1.)*(1.-abs(tri-tri0));
    vec2 srcCell = cell + floor((h-0.5)*grid*sc + drift*grid*0.35*sc + 0.5);
    float ang = (h3-0.5)*6.2832*sc + (noise(id + uTime*(0.3+h.y*1.2))-0.5)*4.*sc;
    vec2 l = rot(ang)*(lfs-0.5);
    float alive = step(h3, 1. - sc*0.55);
    vec2 suv = (srcCell + l + 0.5)/grid;
    float edge = min(min(lfs.x, 1.-lfs.x), min(lfs.y, 1.-lfs.y));
    edge = min(edge, abs(lfs.x-lfs.y)*0.7);
    vec3 c = shade(suv, clamp(luma(tex(suv))*3.,0.,1.), uLevel)*(0.6 + uLevel*0.5*uInt + uAssemble*0.6);
    c += c*(1.-smoothstep(0.,0.05,edge))*sc*uHigh*1.5;
    col = c*inside*alive;
  }
  else if(uMode < 6.5){ // RISING SPARKS  each column rises on its own band's clock · lows: horizon flash
    vec2 uv = cover(q - vec2(sin(q.y*6.+uMidT)*0.02*uLow*uInt, uMidT*0.05));
    col = textureLod(uTex, uv*0.7 + 0.15, 5.).rgb*uMid*0.4;
    float y01 = q.y + 0.5, hz = 0.12;
    for(int L=0; L<3; L++){
      float fL = float(L), cols = 50. + fL*35.;
      float id = floor(x01*cols), fx = fract(x01*cols);
      float hh = hash(vec2(id, fL+2.));
      float fq = (id+.5)/cols; if(uBassMid > 0.5) fq = abs(fq*2.-1.);
      float f = height(sp(fq));
      float tc = fq < 0.33 ? uLowT : (fq < 0.66 ? uMidT : uHighT);
      float yy = fract(y01*(0.8+fL*0.5) - tc*(0.5+hh*0.6)*(1.+fL*0.3) + hh*13.);
      float trail = smoothstep(0.3-f*0.25, 0.75, yy)*(1.-smoothstep(0.75, 0.77, yy));
      float wm = 1. - smoothstep(0.05, 0.35+f*0.1, abs(fx-0.5));
      float cb = chH(fq);
      float amp = smoothstep(0.06, 0.45, f)*step(0.35, hh + f*0.5)*(1. + cb*1.5);
      float top = hz + 0.12 + f*0.62;
      amp *= smoothstep(hz-0.02, hz+0.04, y01)*(1.-smoothstep(top-0.12, top, y01));
      col += max(shade(vec2(fq, 1.-y01 + uMidT*0.1), fq, f), pal(fq)*(0.35+f*0.6))*trail*wm*amp*(1.3-fL*0.3);
    }
    col += pal(x01)*exp(-abs(y01-hz)*40.)*(sp(x01)*uInt*0.35 + chH(clamp(x01,0.,1.))*0.9 + uChHit[10]*0.4);
  }
  else if(uMode < 7.5){ // WARP  lows: ring size + trail zoom · mids: rotation · highs: ripple + glints
    float r = length(q), ang = atan(q.y,q.x), a01 = fract(ang/TAU + 0.25 + uMidT*0.05);
    float fs = abs(a01*2.-1.); if(uBassMid > 0.5) fs = 1.-fs;
    float R = 0.15 + height(sp(fs))*0.17 + uCh[10]*0.04 + uChHit[10]*0.03 + chH(fs)*0.05;
    R += sin(ang*12. + uHighT*2.)*0.006*uHigh;
    float d = abs(r-R);
    vec3 c = max(shade(vec2(a01, uMidT*0.1), fs, lvl), pal(fs)*0.55);
    col += c*(0.0014/(d+0.0016))*(uLow*0.7 + uMid*0.4);
    for(int i=0;i<3;i++){
      float fi = float(i);
      vec2 bp = 0.32*vec2(sin(uMidT*0.5+fi*2.1), cos(uMidT*0.4+fi*1.7));
      col += pal(fi/2.)*0.003/(length(q-bp)+0.012)*uLowHit;
    }
  }
  else if(uMode < 8.5){ // FRACTAL  lows: breathing fold · mids: rotation · highs: fine-detail sparkle
    vec2 p = rot(uMidT*0.1)*q*(1.5 - uLow*0.15*uInt);
    float k = 0.8 + 0.07*sin(uMidT*0.2) + uLowHit*0.02*uInt;
    vec3 acc = vec3(0.); float w = 0.;
    for(int i=0;i<7;i++){
      p = abs(p)/max(dot(p,p), 0.02) - k;
      float fi = float(i), wt = exp(-fi*0.3)*(1. + uChHit[min(9, 1 + int(fi*1.4))]*1.6)*(i > 3 ? 1. + uHigh*1.2 : 1.);
      acc += max(shade(fract(p*0.22+0.5), fi/6., lvl), pal(fi/6.)*0.16*lvl)*wt; w += wt;
    }
    col = acc/w;
    col *= smoothstep(0.04, 0.4, luma(col))*(uLow*0.6 + uMid*0.5 + uHigh*0.3)*1.2*uInt;
  }
  else { // LIGHT SPEED  your own ship: starts at rest, speeds up with the song, eases off when it calms
         // the kick drum surges it forward and lights the wormhole · each star belongs to a frequency
    vec2 cq = q - vec2(sin(uSpin*0.7), cos(uSpin*0.5))*0.03;
    float r = length(cq), ang = atan(cq.y,cq.x) + uSpin*0.15;
    vec2 wuv = vec2(ang/TAU*2. + uSpin*0.1, 0.08/(r+0.05) + uTravel*0.15);   // x2 keeps the mirrored texture seamless all the way round
    vec3 core = textureLod(uTex, wuv, 3.).rgb*2.2 + pal(0.2)*0.12;
    col += core*exp(-r*5.)*(0.12 + uHyE*0.55 + uCore*0.6*uHyE);
    for(int L=0; L<4; L++){
      float fL = float(L), N = 70. + fL*55.;
      float a01 = fract(ang/TAU + fL*0.137);
      float cid = floor(a01*N);
      float h = hash(vec2(cid, fL*7.1)), h2 = hash(vec2(cid+13., fL));
      float z = fract(h + uTravel*(0.12 + h2*0.1)*(1. + fL*0.3));        // 0 = far away, 1 = flying past
      float rs = z*z*1.25;
      float len = 0.005 + uSpeed*0.11*z*z;                                // dots when parked, long streaks at full speed
      float da = abs(fract(a01*N) - 0.5 - (h2-0.5)*0.6)/N*TAU*r;
      float wd = 0.0012 + z*0.0025;
      float radial = smoothstep(rs - len, rs, r)*(1. - smoothstep(rs, rs + 0.004, r));
      float star = radial*(1. - smoothstep(0., wd, da));
      float fx = fract(h*3.7);
      float bright = (0.25 + z*1.4)*(0.25 + uHyE*0.8 + tn(fx)*1.1*uHyE);
      col += mix(pal(fx), vec3(1.), 0.35 + z*0.3)*star*bright*step(0.3, h2 + uHigh*0.35);
    }
    col += pal(0.8)*smoothstep(0.35, 1., r)*uSpeed*0.035;
  }
  if(uMode < 4.5 || uMode > 5.5) col *= smoothstep(0.02, 0.15, (uMode > 8.5 && uMode < 9.5) ? max(uHyE, uEnergy*0.5) : uEnergy);
  o = vec4(col, 1.);
}
