#version 330

// The sky cache as shown: the capture before the latest blended into the latest (SkyCache.java)

uniform sampler2D cacheFrom;
uniform sampler2D cacheTo;
uniform float blend;

out vec4 FragColor;

void main() {
  ivec2 p = ivec2(gl_FragCoord.xy);
  FragColor = mix(texelFetch(cacheFrom, p, 0), texelFetch(cacheTo, p, 0), blend);
}
