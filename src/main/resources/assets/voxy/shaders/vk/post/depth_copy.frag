#version 460
layout(set = 0, binding = 0) uniform sampler2D depthTex;
layout(set = 0, binding = 1, std140) uniform Params { vec2 scaleFactor; };
in vec2 UV;
void main(){ gl_FragDepth = texture(depthTex, UV*scaleFactor).r; }
