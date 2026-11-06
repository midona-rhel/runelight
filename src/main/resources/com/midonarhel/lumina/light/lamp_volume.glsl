// The lamps' volume (light/LampVolume.java, occlusion/flood-volume/math.md): the sum of every lamp's light, which went round
// everything solid before it was summed. Everything lit by lamps reads this: one or two filtered fetches, however many lamps.
//
// Three levels, toroidal but the last: fine cells of LAMP_VOLUME_FINE round the player, near of LAMP_VOLUME_CELL further out, far of
// LAMP_VOLUME_FAR_CELL over the scene, with no walls in it, for the fog and past the near window. In the first two a cell a surface
// passes through holds next to no air (a) and every field is held times the air, so dividing a filtered read by its air takes only free
// cells, and two free cells that share a corner are on the same side of every surface. A solid cell's trace of air and light is
// for a read with no free cell in reach: a surface in a nook.
//
// A: rgb the light (colour times falloff times visibility, summed); a the air (far level: the luminance that may fall on surfaces). B: rgb H, the sum of g P, a G, the sum of g, for
// g the luminance over the distance. F(p) = H - G p is the irradiance vector at any point p: N.L is taken at the shading point.

// one texture, the composite's sixteen samplers being spent: A in the first half of its depth, B in the second
uniform sampler3D lampVolumeA;
#define LAMP_VOLUME_B vec3(0.0, 0.0, 0.5)
uniform vec4 lampVolumeFine;   // world x, z, y of the fine window's corner
uniform vec4 lampVolumeNear;   // the same of the near window's; w 0 for no volume yet
uniform vec4 lampVolumeFar;    // the same of the far window's
uniform vec4 lampVolumeOrigin; // the world place H's lamps are relative to

// the share of a window, at its edge, that hands over to the next level
#define LAMP_VOLUME_FADE 512.0
#define LAMP_VOLUME_FADE_FINE 192.0
// less air than this in a read and a surface looks further off for more
#define LAMP_VOLUME_FEW 0.06

vec3 lampVolumeFineAt(vec3 w) {
  // a fine cell's edges are on multiples of its size, so a wall on a tile's edge takes the layers either side of it
  vec3 c = vec3(w.x, w.z, w.y - lampVolumeFine.z) / LAMP_VOLUME_FINE;
  c.z = clamp(c.z, 0.5, LAMP_VOLUME_FINE_Y - 0.5);
  return c / vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, 2.0 * LAMP_VOLUME_LAYERS);
}

vec3 lampVolumeNearAt(vec3 w) {
  // toroidal in x and z: a world cell's place modulo the size; the far level's place is relative to its window
  // a cell is centred on a multiple of LAMP_VOLUME_CELL in x and z: a tile's edge, a wall, runs down the middle of one layer of them
  vec3 c = vec3(w.x, w.z, w.y - lampVolumeNear.z) / LAMP_VOLUME_CELL + vec3(0.5, 0.5, LAMP_VOLUME_Y_SHIFT);
  c.z = clamp(c.z, 0.5, LAMP_VOLUME_NEAR_Y - 0.5) + LAMP_VOLUME_NEAR_AT;
  return c / vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, 2.0 * LAMP_VOLUME_LAYERS);
}

vec3 lampVolumeFarAt(vec3 w) {
  vec3 c = (w.xzy - lampVolumeFar.xyz + vec3(0.5, 0.5, LAMP_VOLUME_Y_SHIFT) * LAMP_VOLUME_CELL) / LAMP_VOLUME_FAR_CELL;
  c.xy = clamp(c.xy, vec2(0.5), vec2(LAMP_VOLUME_SIZE - 0.5));
  c.z = clamp(c.z, 0.5, LAMP_VOLUME_FAR_Y - 0.5) + LAMP_VOLUME_FAR_AT;
  return c / vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, 2.0 * LAMP_VOLUME_LAYERS);
}

// 1 well inside a window of a cell count and size, 0 at and past its edge
float lampVolumeShare(vec3 w, vec4 window, float size, float layers, float fade) {
  vec3 span = vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, layers) * size;
  vec3 c = w.xzy - window.xyz;
  vec3 e = min(c, span - c) / vec3(fade, fade, fade * 0.25);
  return clamp(min(e.x, min(e.y, e.z)), 0.0, 1.0);
}

// The flicker at world point w: a factor on what the volume holds, from the map in a gutter layer of A (LampVolume.updateGain)
float lampVolumeGain(vec3 w) {
  vec2 c = (w.xz - lampVolumeNear.xy) / LAMP_VOLUME_GAIN_CELL
    + 0.5 * (LAMP_VOLUME_GAIN_SIZE - LAMP_VOLUME_SIZE * LAMP_VOLUME_CELL / LAMP_VOLUME_GAIN_CELL);
  c = clamp(c, vec2(0.5), vec2(LAMP_VOLUME_GAIN_SIZE - 0.5)) / LAMP_VOLUME_SIZE;
  return 1.0 + textureLod(lampVolumeA, vec3(c, (LAMP_VOLUME_GAIN_AT + 0.5) / (2.0 * LAMP_VOLUME_LAYERS)), 0.0).r;
}

struct LampVolumeRead {
  vec3 light;  // the sum of the lamps' light where it was read, W
  vec3 h;      // H
  float g;     // G
  vec3 at;     // where it was read
};

LampVolumeRead lampVolumeNone(vec3 w) {
  return LampVolumeRead(vec3(0.0), vec3(0.0), 0.0, w);
}

// A walled level at world point w, off a surface along faceN: a cell off it, two where next to nothing of that is free.
LampVolumeRead lampVolumeWalled(vec3 w, vec3 faceN, bool fine) {
  float size = fine ? LAMP_VOLUME_FINE : LAMP_VOLUME_CELL;
  vec3 q = w + faceN * size;
  vec3 c = fine ? lampVolumeFineAt(q) : lampVolumeNearAt(q);
  vec4 a = textureLod(lampVolumeA, c, 0.0);
  if (a.a < LAMP_VOLUME_FEW && faceN != vec3(0.0)) {
    vec3 q2 = w + faceN * (2.0 * size);
    vec3 c2 = fine ? lampVolumeFineAt(q2) : lampVolumeNearAt(q2);
    vec4 a2 = textureLod(lampVolumeA, c2, 0.0);
    if (a2.a > a.a) {
      q = q2;
      c = c2;
      a = a2;
    }
  }
  if (a.a <= 0.0 || dot(a.rgb, vec3(1.0)) <= 0.0) {
    return lampVolumeNone(q);
  }
  vec4 b = textureLod(lampVolumeA, c + LAMP_VOLUME_B, 0.0);
  float air = 1.0 / a.a;
  return LampVolumeRead(a.rgb * air, b.rgb * air, b.a * air, q);
}

LampVolumeRead lampVolumeMix(LampVolumeRead a, LampVolumeRead b, float t) {
  return LampVolumeRead(mix(a.light, b.light, t), mix(a.h, b.h, t), mix(a.g, b.g, t), mix(a.at, b.at, t));
}

// The volume at world point w, off a surface along faceN (zero in the air): the finest level there is, hands over between them.
LampVolumeRead lampVolumeRead(vec3 w, vec3 faceN) {
  LampVolumeRead r;
  float fine = lampVolumeShare(w, lampVolumeFine, LAMP_VOLUME_FINE, LAMP_VOLUME_FINE_Y, LAMP_VOLUME_FADE_FINE);
  if (fine >= 1.0) {
    r = lampVolumeWalled(w, faceN, true);
  } else {
    float near = lampVolumeShare(w, lampVolumeNear, LAMP_VOLUME_CELL, LAMP_VOLUME_NEAR_Y, LAMP_VOLUME_FADE);
    if (near >= 1.0) {
      r = lampVolumeWalled(w, faceN, false);
    } else {
      r = lampVolumeNone(w);
      vec3 q = w + faceN * (0.5 * LAMP_VOLUME_FAR_CELL);
      vec3 c = lampVolumeFarAt(q);
      vec4 a = textureLod(lampVolumeA, c, 0.0);
      // alpha is the share of its luminance that may fall on a surface
      float lum = dot(a.rgb, vec3(0.2126, 0.7152, 0.0722));
      if (a.a > 0.0 && lum > 0.0) {
        vec4 b = textureLod(lampVolumeA, c + LAMP_VOLUME_B, 0.0);
        float share = min(a.a / lum, 1.0);
        r = LampVolumeRead(a.rgb * share, b.rgb * share, b.a * share, q);
      }
      if (near > 0.0) {
        r = lampVolumeMix(r, lampVolumeWalled(w, faceN, false), near);
      }
    }
    if (fine > 0.0) {
      r = lampVolumeMix(r, lampVolumeWalled(w, faceN, true), fine);
    }
  }
  if (r.g > 0.0) {
    float gain = lampVolumeGain(w);
    r.light *= gain;
    r.h *= gain;
    r.g *= gain;
  }
  return r;
}

// The lamps' light in the air at world point w, for the fog: the near level, with its walls, in its window, the far level past it. The near
// level has no lamp on a storey the client hides, and the air is lit by those too: the far level's share of them, a step in four, for four steps.
vec3 lampVolumeAir(vec3 w, int step) {
  float near = lampVolumeShare(w, lampVolumeNear, LAMP_VOLUME_CELL, LAMP_VOLUME_NEAR_Y, LAMP_VOLUME_FADE);
  vec3 c = vec3(0.0);
  if (near > 0.0) {
    c = textureLod(lampVolumeA, lampVolumeNearAt(w), 0.0).rgb * near;
  }
  if (near < 1.0) {
    c += textureLod(lampVolumeA, lampVolumeFarAt(w), 0.0).rgb * (1.0 - near);
  } else if (lampVolumeFar.w > 0.0 && (step & 3) == 0) {
    vec4 f = textureLod(lampVolumeA, lampVolumeFarAt(w), 0.0);
    float lum = dot(f.rgb, vec3(0.2126, 0.7152, 0.0722));
    if (lum > 0.0) {
      c += f.rgb * (4.0 * max(1.0 - f.a / lum, 0.0));
    }
  }
  return c;
}

// What a surface at world point w, with smooth normal n, takes from a read: light, the lamps' sum, times how much of it falls on
// the surface (out: the cosine for any transmission, and the direction the light comes from).
float lampVolumeOnSurface(LampVolumeRead r, vec3 w, vec3 n, out float ndl, out vec3 ld) {
  float lum = dot(r.light, vec3(0.2126, 0.7152, 0.0722));
  ndl = 0.0;
  ld = vec3(0.0);
  if (lum <= 0.0) {
    return 0.0;
  }
  vec3 fp = r.h - (w - lampVolumeOrigin.xyz) * r.g;
  float lp = length(fp);
  if (lp <= 0.0) {
    return 0.0;
  }
  ld = fp / lp;
  ndl = dot(n, ld);
  // the vector's length where it was read: the luminance itself for one lamp, less where lamps pull apart
  float strength = min(length(r.h - (r.at - lampVolumeOrigin.xyz) * r.g), lum);
  return strength * max(ndl, 0.0) / lum;
}
