#ifdef GL_ES
  precision mediump float;
#endif

uniform vec3 color;

void main(void) {
  gl_FragColor = vec4(color, 1.0);
}