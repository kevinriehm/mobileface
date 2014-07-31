#version 100

uniform mat4 u_camera;

attribute vec3 a_position;
attribute vec2 a_uv;

varying vec2 v_uv;

void main() {
	v_uv = a_uv;

	gl_Position = u_camera*vec4(a_position, 1);
}

