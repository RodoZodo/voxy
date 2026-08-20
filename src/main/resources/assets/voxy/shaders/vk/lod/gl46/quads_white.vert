#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define POSITION_SCRATCH_BINDING 5

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;

void main() {
    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    setupQuad(quad, quadData[uint(gl_VertexIndex)>>2], pos, (gl_VertexIndex&3)==1);
    uint cornerId = gl_VertexIndex&3;
    gl_Position = getQuadCornerPos(quad, cornerId);
    interData = quad.attributeData;
}
