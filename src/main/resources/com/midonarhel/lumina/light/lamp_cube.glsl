// The lamps' shadow cubes (light/LampCubes.java): each lamp's depth on the six faces of a cube round it, drawn from
// what the frame draws (its levels, the roofs the client shows), in one atlas; a lamp's slot in it is in its texels
// (Lamp.cube, -1 for none). The fog's glow and the surfaces read them. Needs light/lamp.glsl and frame.glsl.

uniform sampler2DShadow lampCubeAtlas;
#define LAMP_CUBE_FACE 128
#define LAMP_CUBE_COLS 12
#define LAMP_CUBE_SIZE vec2(4608.0, 2560.0)
#define LAMP_CUBE_TAN_HALF (128.0 / 120.0)
#define LAMP_CUBE_NEAR 8.0
// the taps' circle, in texels: the shadow's edge soft over about twice it; the fog's (its denoise smooths the rest)
// and the surfaces' (tight: a wall's foot and a jamb's edge as sharp as the wall's line)
#define LAMP_CUBE_SOFT 2.0
#define LAMP_CUBE_SOFT_SURFACE 0.75

// How much of a lamp the cube in slot s lets reach camera-relative point q, the lamp's origin at its height o, its
// reach radius: four filtered taps on a circle of soft texels turned by turn
float lampCubeRead(int s, vec3 o, float radius, vec3 q, float turn, float soft) {
  vec3 v = q - o;
  vec3 a = abs(v);
  int face;
  vec2 xy;
  float z;
  if (a.x >= a.y && a.x >= a.z) {
    face = v.x > 0.0 ? 0 : 1;
    xy = v.zy;
    z = a.x;
  } else if (a.y >= a.z) {
    face = v.y > 0.0 ? 2 : 3;
    xy = v.xz;
    z = a.y;
  } else {
    face = v.z > 0.0 ? 4 : 5;
    xy = v.xy;
    z = a.z;
  }
  float far = max(radius, LAMP_CUBE_NEAR * 2.0);
  float zr = z - (4.0 + 0.02 * z);
  if (zr <= LAMP_CUBE_NEAR) {
    return 1.0;
  }
  vec2 origin = vec2(float((s % LAMP_CUBE_COLS) * 3 + face % 3), float((s / LAMP_CUBE_COLS) * 2 + face / 3))
    * float(LAMP_CUBE_FACE);
  vec2 texel = (0.5 + 0.5 * xy / (z * LAMP_CUBE_TAN_HALF)) * float(LAMP_CUBE_FACE);
  float n = LAMP_CUBE_NEAR;
  float d = depthZeroToOne == 1 ? far / (far - n) - far * n / ((far - n) * zr)
    : 0.5 * ((far + n) / (far - n) - 2.0 * far * n / ((far - n) * zr)) + 0.5;
  float t = turn * 6.28318531;
  vec2 off = soft * vec2(cos(t), sin(t));
  float sum = 0.0;
  for (int k = 0; k < 4; ++k) {
    vec2 p = clamp(texel + off, vec2(1.0), vec2(float(LAMP_CUBE_FACE) - 1.0));
    sum += texture(lampCubeAtlas, vec3((origin + p) / LAMP_CUBE_SIZE, d));
    off = vec2(-off.y, off.x);
  }
  return sum * 0.25;
}

// How much of the lamp its shadow cube lets reach camera-relative point q (taps turned by turn: 0 to 1, the fog's
// jitter, which its denoise smooths, or a fixed turn on a surface; soft texels apart); while a change's new cube
// takes over, the old one's crossed over to it. -1 where the lamp has no cube.
float lampCubeOpen(Lamp lamp, vec3 q, float turn, float soft) {
  if (lamp.cube < 0) {
    return -1.0;
  }
  // from its origin, at its height (LampCubes draws it from there)
  vec3 o = vec3(lamp.origin.x, lamp.pos.y, lamp.origin.y);
  float open = lampCubeRead(lamp.cube, o, lamp.radius, q, turn, soft);
  return lamp.cubeFrom >= 0 ? mix(lampCubeRead(lamp.cubeFrom, o, lamp.radius, q, turn, soft), open, lamp.cubeFade) : open;
}

// The size of a cube texel at distance dist from its lamp, world units: how far a surface point is moved off its
// surface before the cube is read, so the surface doesn't shadow itself
float lampCubeTexel(float dist) {
  return dist * (2.0 * LAMP_CUBE_TAN_HALF / float(LAMP_CUBE_FACE));
}
