uniform sampler2D uSrc;
uniform vec2 uRes;
uniform float uBloom, uExpo;
void main(){
  vec2 uv = gl_FragCoord.xy/uRes;
  vec3 c = texture(uSrc, uv).rgb;
  c += (textureLod(uSrc, uv, 3.).rgb*0.45 + textureLod(uSrc, uv, 5.).rgb*0.6)*uBloom;
  c = max(c - 0.04, 0.)*1.04;
  vec3 ex = max(c - 0.85, 0.); c = min(c, 0.85) + ex/(1. + ex*1.8);
  c *= 1. - 0.5*pow(length(uv - .5)*1.3, 2.);
  c *= uExpo;
  o = vec4(c, 1.);
}
