uniform sampler2D uTex, uSpec;
uniform vec3 uPal0, uPal1, uPal2, uPal3;
uniform vec2 uRes, uShake;
uniform float uImgAsp;
uniform float uLow, uMid, uHigh, uLowHit, uMidHit, uHighHit, uLowT, uMidT, uHighT;
uniform float uCh[11];
uniform float uChHit[11];
uniform float uEnergy, uGate, uInt, uTime, uBeat, uTravel, uSpeed, uSpin, uHyT, uHyS, uHyE, uLines;
uniform int uMode;

float sp(float x){ return texture(uSpec, vec2(clamp(x,0.004,0.996), .5)).r; }
vec3 pal(float t){ t = clamp(t,0.,1.)*3.;
  if(t < 1.) return mix(uPal0,uPal1,t); if(t < 2.) return mix(uPal1,uPal2,t-1.); return mix(uPal2,uPal3,t-2.); }
vec3 tex(vec2 uv){ return texture(uTex, uv).rgb; }
vec3 shade(vec2 uv, float t, float e){ vec3 tc = tex(uv); float lm = smoothstep(0.05, 0.3, luma(tc)); return tc*lm*2.4 + pal(t)*0.15*lm*e; }
// fixed detail level: no seam where the angle wraps round
vec3 shadeL(vec2 uv, float t, float e, float lod){ vec3 tc = textureLod(uTex, uv, lod).rgb; float lm = smoothstep(0.05, 0.3, luma(tc)); return tc*lm*2.4 + pal(t)*0.15*lm*e; }
vec2 cover(vec2 q){ float a = uRes.x/uRes.y; float s = max(a/uImgAsp, 1.); return vec2(q.x/(uImgAsp*s) + 0.5, 0.5 - q.y/s); }
float chMix(float b, bool hitv){ int i = int(floor(b)); int j = min(i+1, 9); float t = smoothstep(0.,1.,fract(b));
  return hitv ? mix(uChHit[i], uChHit[j], t) : mix(uCh[i], uCh[j], t); }
float chPos(float fx){ float f = 30.*pow(533.333, clamp(fx,0.,1.)); return clamp(log2(f/22.) - 0.5, 0., 9.); }
float chL(float fx){ return chMix(chPos(fx), false); }
float chH(float fx){ return chMix(chPos(fx), true); }

void main(){
  vec2 q = (gl_FragCoord.xy - uRes*0.5)/uRes.y + uShake;
  float a = uRes.x/uRes.y;
  vec3 col = vec3(0.);
  float x01 = q.x/a + 0.5;
  float lvl = uLow*0.5 + uMid*0.3 + uHigh*0.2;
  float E = uEnergy;

  if(uMode == 0){ // WAVE: each section of the line is one equaliser band (31 Hz left .. 16 kHz right)
    float smoke = E*E*0.9 + uLowHit*0.15;                                  // smoke behind grows with the song's energy
    vec2 wv = vec2(fbm(q*1.6 + vec2(uMidT*0.3,0.)), fbm(q*1.6 + vec2(4.,uMidT*0.22))) - 0.5;
    vec3 sm = textureLod(uTex, cover(q*0.6 + wv*(0.9 + E*0.4)), 5.).rgb;
    col += sm*smoothstep(0.02, 0.12, luma(sm))*smoke*0.7;
    float X = q.x;
    float xe = clamp((x01 - 0.06)/0.88, 0., 1.);
    float bp = clamp(xe*10. - 0.5, 0., 9.);
    float cl = chMix(bp, false), chh = chMix(bp, true);
    float env = 1. - smoothstep(0.42, 0.5, abs(x01 - 0.5));
    float cap = 0.07 + 0.4*E*E;                                            // small and calm when quiet, tall when full
    float ampS = min(cl*0.13*uInt, cap);
    float amp = min((chh*0.18 + uChHit[10]*0.06)*uInt, cap);
    float kf = 4. + bp*1.3*(1. + E*0.8);
    for(int k=0; k<8; k++){
      if(float(k) >= uLines) break;
      float fk = float(k);
      float body = sin(X*kf*0.9 + fk*0.8)*0.7 + sin(X*kf*0.45 + fk*1.9)*0.3;   // standing wave: fixed shape, height = the band
      float t = X*(6. + bp*0.8) + fk*0.37;
      float hs = hash(vec2(floor(t), 7. + fk));
      float tri = pow(1. - abs(fract(t)*2. - 1.), 3.);
      float spike = tri*step(0.86 - chh*(0.16 + 0.3*E), hs)*min(chh, 1.)*(0.6 + 1.3*E)*(hs > 0.95 ? -1. : 1.);
      float y = (body*(ampS + amp*0.6) + spike*amp*0.9)*env*(1. - fk*0.12);
      float d = abs(q.y - y);
      float thick = 0.0013 + cl*0.0016 + chh*0.0028*(0.5 + E) + uChHit[10]*0.0015;
      vec3 c = mix(pal(bp/9.), vec3(1.), clamp(chh*0.55 + abs(spike)*0.3, 0., 0.7));
      col += c*(thick/(d + 0.0022))*0.32*(0.4 + cl*0.6 + E*0.4)*(1. - fk*0.1)*env;
    }
  }
  else if(uMode == 1){ // TUNNEL: forward travel with the ship, continuous spin, bends harder when the song is intense
    vec2 cq = q - 0.14*vec2(sin(uTravel*0.21), cos(uTravel*0.16))*(0.2 + E*1.2);
    float r = length(cq), ang = atan(cq.y, cq.x);
    float depth = 0.3/(r + 0.015);
    ang += uSpin*0.6 + depth*0.05*(0.3 + uMid + E);
    vec2 tu = vec2(ang/TAU*2., depth*0.3 + uTravel*0.55);
    vec3 c = shadeL(tu, fract(depth*0.08), lvl, clamp(log2(depth*2.), 0.5, 4.))*(uLow*0.6 + uMid*0.4);
    float ringf = fract(depth*0.6 + uTravel*0.8);
    c += pal(fract(depth*0.05))*(1. - smoothstep(0., 0.06, abs(ringf - 0.5)))
         *(sp(fract(depth*0.05))*min(uInt,1.6)*0.7 + chH(fract(depth*0.05))*0.9 + uChHit[10]*0.4 + uBeat*0.6*E);
    float stripes = pow(abs(sin(ang*24. + uHighT*0.6)), 40.);
    c += pal(0.9)*stripes*(uHigh*0.22 + uHighHit*0.35)*smoothstep(0.1, 0.5, r);
    col = c*smoothstep(0.05, 0.35, r)*min(uInt, 1.8)*0.75;
  }
  else { // LIGHT SPEED: stars stream past; each star belongs to a frequency
    vec2 cq = q - vec2(sin(uSpin*0.7), cos(uSpin*0.5))*0.03;
    float r = length(cq), ang = atan(cq.y, cq.x) + uSpin*0.15;
    vec2 wuv = vec2(ang/TAU*2. + uSpin*0.1, 0.08/(r + 0.05) + uHyT*0.15);   // x2: seamless all the way round
    vec3 core = textureLod(uTex, wuv, 3.).rgb*2.2 + pal(0.2)*0.12;
    col += core*exp(-r*5.)*(0.12 + uHyE*0.55 + uChHit[10]*0.5*uHyE);
    for(int L=0; L<4; L++){
      float fL = float(L), N = 70. + fL*55.;
      float a01 = fract(ang/TAU + fL*0.137);
      float cid = floor(a01*N);
      float h = hash(vec2(cid, fL*7.1)), h2 = hash(vec2(cid + 13., fL));
      float z = fract(h + uHyT*(0.12 + h2*0.1)*(1. + fL*0.3));
      float rs = z*z*1.25;
      float len = 0.005 + uHyS*0.11*z*z;
      float da = abs(fract(a01*N) - 0.5 - (h2 - 0.5)*0.6)/N*TAU*r;
      float wd = 0.0012 + z*0.0025;
      float radial = smoothstep(rs - len, rs, r)*(1. - smoothstep(rs, rs + 0.004, r));
      float star = radial*(1. - smoothstep(0., wd, da));
      float fx = fract(h*3.7);
      float bright = (0.25 + z*1.4)*(0.25 + uHyE*0.8 + chH(fx)*1.1*uHyE);
      col += mix(pal(fx), vec3(1.), 0.35 + z*0.3)*star*bright*step(0.3, h2 + uHigh*0.35);
    }
    col += pal(0.8)*smoothstep(0.35, 1., r)*uHyS*0.035;
  }
  col *= smoothstep(0.02, 0.15, uMode == 2 ? max(uHyE, uGate*0.5) : uGate);   // silence is black
  o = vec4(col, 1.);
}
