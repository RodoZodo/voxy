#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#define QUAD_BUFFER_BINDING 1
#define POSITION_SCRATCH_BINDING 5

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>

layout(location = 0) out flat uvec4 interData;

void main() {
    QuadData quad;
    uvec2 pos = positionBuffer[gl_BaseInstance];
    setupQuad(quad, quadData[uint(gl_VertexIndex)>>2], pos, (gl_VertexIndex&3)==1);
#ifdef DRAW_PARAM_PROBE
    atomicMax(positionBuffer[399996].x, uint(gl_BaseInstance));
    atomicMax(positionBuffer[399997].x, uint(gl_VertexIndex));
    atomicAdd(positionBuffer[399998].x, 1u);
#endif
    uint cornerId = gl_VertexIndex&3;
    gl_Position = getQuadCornerPos(quad, cornerId);
    interData = quad.attributeData;
}
