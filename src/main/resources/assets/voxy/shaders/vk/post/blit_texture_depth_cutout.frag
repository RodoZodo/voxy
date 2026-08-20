#version 460
layout(set = 0, binding = 0) uniform sampler2D depthTex;
layout(set = 0, binding = 1) uniform sampler2D colourTex;
layout(set = 0, binding = 2, std140) uniform BlitParams {
    mat4 invProjMat;
    mat4 projMat;
    vec4 endParams;
    vec4 fogColour;
    vec4 fadeParams;
};
#import <voxy:util/depthutils.glsl>
in vec2 UV;
layout(location = 0) out vec4 colour;
vec3 rev3d(vec3 clip){ return (invProjMat*vec4(SCREEN2NDC(clip),1)).xyz/(invProjMat*vec4(SCREEN2NDC(clip),1)).w; }
void main(){
  float depth = texture(depthTex, UV).r;
  if(depth==0||depth==1) discard;
  vec3 point = rev3d(vec3(UV, depth));
  depth = (projMat*vec4(point,1)).z/(projMat*vec4(point,1)).w;
  depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0/((1<<24)-1)), depth);
  depth = NDC2SCREEN_DEPTH(depth);
  gl_FragDepth = depth;
  colour = texture(colourTex, UV);
  if(colour.a==0) discard;
  if(fogColour.a>0){
    float f=clamp(fma(length(point),endParams.x,endParams.y),0,endParams.z);
    colour.rgb=mix(colour.rgb,fogColour.rgb,f*fogColour.a);
  }
  if(fadeParams.x>0){
    float len=fadeParams.x>1.5?length(point):length(point.xz);
    colour.a*=1-clamp(fma(len,fadeParams.z,fadeParams.y),0,1);
  }
}
