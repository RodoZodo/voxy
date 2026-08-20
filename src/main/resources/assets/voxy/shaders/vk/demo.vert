#version 450

layout(set = 0, binding = 0) uniform FrameData {
    mat4 mvp;
} frame;

layout(location = 0) in vec3 inPosition;

void main() {
    gl_Position = frame.mvp * vec4(inPosition, 1.0);
}
