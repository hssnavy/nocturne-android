#version 300 es
precision highp float;
#define TAU 6.2831853
out vec4 o;
float hash(vec2 p){ p = fract(p*vec2(123.34, 456.21)); p += dot(p, p+45.32); return fract(p.x*p.y); }
float noise(vec2 p){ vec2 i=floor(p), f=fract(p); vec2 u=f*f*(3.-2.*f);
  return mix(mix(hash(i),hash(i+vec2(1,0)),u.x), mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),u.x), u.y); }
float fbm(vec2 p){ float v=0., a=.5; for(int i=0;i<3;i++){ v+=a*noise(p); p=p*2.03+vec2(1.7,9.2); a*=.5; } return v; }
float luma(vec3 c){ return dot(c, vec3(.299,.587,.114)); }
vec3 hueRot(vec3 c, float a){ const vec3 k=vec3(0.57735); float ca=cos(a); return c*ca + cross(k,c)*sin(a) + k*dot(k,c)*(1.-ca); }
