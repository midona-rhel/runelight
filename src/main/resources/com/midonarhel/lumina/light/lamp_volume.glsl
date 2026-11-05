// The lamps' volume (light/LampVolume.java, occlusion/flood-volume/math.md): the sum of every lamp's light, which went round
// everything solid before it was summed. Everything lit by lamps reads this: one or two filtered fetches, however many lamps.
//
// Near level: cells of LAMP_VOLUME_CELL. A cell a surface passes through holds nothing and has air 0 (a), and every field is
// held times the air, so dividing a filtered read by its air takes only free cells, and two free cells that share a corner
// are on the same side of every surface. Far level: LAMP_VOLUME_FAR_CELL, all air, past the near window and for the fog.
//
// A: rgb the light (colour times falloff times visibility, summed); a the air. B: rgb H, the sum of g P, a G, the sum of g, for
// g the luminance over the distance. F(p) = H - G p is the irradiance vector at any point p: N.L is taken at the shading point.

// one texture, the composite's sixteen samplers being spent: A in the first half of its depth, B in the second
uniform sampler3D lampVolumeA;
#define LAMP_VOLUME_B vec3(0.0, 0.0, 0.5)
uniform vec4 lampVolumeNear;   // world x, z, y of the near window's corner; w 0 for no volume yet
uniform vec4 lampVolumeFar;    // the same of the far window's
uniform vec4 lampVolumeOrigin; // the world place H's lamps are relative to

// the share of the window, at its edge, that hands over to the far level
#define LAMP_VOLUME_FADE 512.0

vec3 lampVolumeNearAt(vec3 w) {
  vec3 c = (w.xzy - lampVolumeNear.xyz) / LAMP_VOLUME_CELL;
  c.z = clamp(c.z, 0.5, LAMP_VOLUME_NEAR_Y - 0.5);
  return c / vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, 2.0 * LAMP_VOLUME_LAYERS);
}

vec3 lampVolumeFarAt(vec3 w) {
  vec3 c = (w.xzy - lampVolumeFar.xyz) / LAMP_VOLUME_FAR_CELL;
  c.z = clamp(c.z, 0.5, LAMP_VOLUME_FAR_Y - 0.5) + LAMP_VOLUME_NEAR_Y + LAMP_VOLUME_GUTTER;
  return c / vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, 2.0 * LAMP_VOLUME_LAYERS);
}

// 1 well inside the near window, 0 at and past its edge
float lampVolumeNearShare(vec3 w) {
  vec3 span = vec3(LAMP_VOLUME_SIZE, LAMP_VOLUME_SIZE, LAMP_VOLUME_NEAR_Y) * LAMP_VOLUME_CELL;
  vec3 c = w.xzy - lampVolumeNear.xyz;
  vec3 e = min(c, span - c) / vec3(LAMP_VOLUME_FADE, LAMP_VOLUME_FADE, LAMP_VOLUME_FADE * 0.25);
  return clamp(min(e.x, min(e.y, e.z)), 0.0, 1.0);
}

// The flicker at world point w: a factor on what the volume holds, from the map in a gutter layer of A (LampVolume.updateGain)
float lampVolumeGain(vec3 w) {
  vec2 c = (w.xz - lampVolumeNear.xy) / LAMP_VOLUME_GAIN_CELL
    + 0.5 * (LAMP_VOLUME_GAIN_SIZE - LAMP_VOLUME_SIZE * LAMP_VOLUME_CELL / LAMP_VOLUME_GAIN_CELL);
  c = clamp(c, vec2(0.5), vec2(LAMP_VOLUME_GAIN_SIZE - 0.5)) / LAMP_VOLUME_SIZE;
  return 1.0 + textureLod(lampVolumeA, vec3(c, (LAMP_VOLUME_NEAR_Y + 0.5) / (2.0 * LAMP_VOLUME_LAYERS)), 0.0).r;
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

// The volume at world point w, off a surface along faceN (zero in the air): the near level one cell off it, two where all
// of that is solid, the far level past the window.
LampVolumeRead lampVolumeRead(vec3 w, vec3 faceN) {
  LampVolumeRead r = lampVolumeNone(w);
  float share = lampVolumeNearShare(w);
  if (share > 0.0) {
    vec3 q = w + faceN * LAMP_VOLUME_CELL;
    vec4 a = textureLod(lampVolumeA, lampVolumeNearAt(q), 0.0);
    if (a.a < 0.02) {
      q = w + faceN * (2.0 * LAMP_VOLUME_CELL);
      a = textureLod(lampVolumeA, lampVolumeNearAt(q), 0.0);
    }
    if (a.a >= 0.02 && dot(a.rgb, vec3(1.0)) > 0.0) {
      vec4 b = textureLod(lampVolumeA, lampVolumeNearAt(q) + LAMP_VOLUME_B, 0.0);
      float air = 1.0 / a.a;
      r = LampVolumeRead(a.rgb * air, b.rgb * air, b.a * air, q);
    }
    r.at = q;
  }
  if (share < 1.0) {
    vec3 q = w + faceN * (0.5 * LAMP_VOLUME_FAR_CELL);
    vec4 a = textureLod(lampVolumeA, lampVolumeFarAt(q), 0.0);
    if (dot(a.rgb, vec3(1.0)) > 0.0) {
      vec4 b = textureLod(lampVolumeA, lampVolumeFarAt(q) + LAMP_VOLUME_B, 0.0);
      r = LampVolumeRead(mix(a.rgb, r.light, share), mix(b.rgb, r.h, share), mix(b.a, r.g, share), mix(q, r.at, share));
    } else {
      r.light *= share;
      r.h *= share;
      r.g *= share;
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
