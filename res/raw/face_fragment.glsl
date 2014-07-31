#version 100

precision mediump float;

uniform sampler2D u_texture;

varying vec2 v_uv;

void main() {
	gl_FragColor = vec4(texture2D(u_texture,v_uv).rgb, 1);
}

