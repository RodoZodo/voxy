#version 460
layout(set = 0, binding = 0) uniform sampler2D text;
in vec2 UV;
layout(location = 0) out vec4 colour;
void main(){ colour=texture(text,UV); if(colour.a==0) discard; }
