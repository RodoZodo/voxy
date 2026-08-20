#version 460
layout(set = 0, binding = 0) uniform sampler2D depthTex;
layout(set = 0, binding = 1, std140) uniform Params { vec2 scaleFactor; };
#import <voxy:util/depthutils.glsl>
in vec2 UV;
void main(){ gl_FragDepth = NEAR; if(texture(depthTex, UV*scaleFactor).r==FAR) discard; }
