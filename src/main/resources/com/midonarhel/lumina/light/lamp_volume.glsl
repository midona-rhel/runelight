// The lamps' volume (light/LampVolume.java, occlusion/flood-volume/math.md): the sum of every fixed lamp's light, which went round
// everything solid before it was summed. Everything lit by lamps reads this: one or two filtered fetches, however many lamps.
//
// Four levels, of LAMP_VOLUME_FINE, _CELL, _MID and _FAR_CELL units a cell. All but the last round the player, toroidal in x and z, every level
// toroidal in y. In the first three a cell a surface passes through holds next to no air (a) and every field is held times the air, so
// dividing a filtered read by its air takes only free cells, and two free cells that share a corner are on the same side of every
// surface. A solid cell's trace of air and light is for a read with no free cell in reach: a surface in a nook. The far level has no walls.
//
// A: rgb the light (colour times falloff times visibility, summed); a the air (far level: the luminance that may fall on surfaces). B: rgb H, the sum of g P, a G, the sum of g, for
// g the luminance over the distance, P the lamp's place from the level's origin. F(p) = H - G p is the irradiance vector at any point p: N.L is taken at the shading point.

// one texture, the composite's sixteen samplers being spent: A, then B, then the fog's field
uniform sampler3D lampVolumeA;
#define LAMP_VOLUME_B vec3(0.0, 0.0, LAMP_VOLUME_B_AT / LAMP_VOLUME_DEPTH)
// per level, the rectangle it is shown in: min, max in world x y z (LampVolume.drain, which keeps it off every cell it is writing); the far level's y alone
uniform vec4 lampVolumeShown[8];
// per level the world place its H is relative to
uniform vec4 lampVolumeOrigin[4];
// what a point of this scene is in the scene the volume shows, while a walking swap's new volume is made: the old one stays up, moved with the world
uniform vec3 lampVolumeShift;
uniform vec4 lampVolumeFar;    // world x, z of the far window's corner, w: any lamp on a hidden storey
uniform vec2 lampVolumeGainAt; // the flicker map's world corner
// the lamps that move: 2 vec4 each, x y z reach (under 0: lights no surface), r g b
uniform int lampMoverCount;
uniform vec4 lampMovers[LAMP_MOVERS * 2];

// the band at a level's edge that hands over to the next
#define LAMP_VOLUME_FADE_FINE 128.0
#define LAMP_VOLUME_FADE_NEAR 256.0
#define LAMP_VOLUME_FADE_MID 384.0
// less air than this in a read and a surface looks further off for more
#define LAMP_VOLUME_FEW 0.06

vec3 lampVolumeTex(vec3 c, float layers, float at) {
  return vec3(c.xy / LAMP_VOLUME_SIZE, (mod(c.z, layers) + at) / LAMP_VOLUME_DEPTH);
}

// a fine cell's edges are on multiples of its size, so a wall on a tile's edge takes the layers either side of it
vec3 lampVolumeFineAt(vec3 w) {
  return lampVolumeTex(w.xzy / LAMP_VOLUME_FINE, LAMP_VOLUME_FINE_Y, LAMP_VOLUME_FINE_AT);
}

// the others' cells are centred on multiples of theirs: a tile's edge, a wall, runs down the middle of one layer of them
vec3 lampVolumeNearAt(vec3 w) {
  return lampVolumeTex(w.xzy / LAMP_VOLUME_CELL + 0.5, LAMP_VOLUME_NEAR_Y, LAMP_VOLUME_NEAR_AT);
}

vec3 lampVolumeMidAt(vec3 w) {
  return lampVolumeTex(w.xzy / LAMP_VOLUME_MID + 0.5, LAMP_VOLUME_MID_Y, LAMP_VOLUME_MID_AT);
}

vec3 lampVolumeFarAt(vec3 w) {
  vec3 c = vec3(w.x - lampVolumeFar.x, w.z - lampVolumeFar.y, clamp(w.y, lampVolumeShown[6].y, lampVolumeShown[7].y)) + LAMP_VOLUME_CELL * 0.5;
  c /= LAMP_VOLUME_FAR_CELL;
  c.xy = clamp(c.xy, vec2(0.5), vec2(LAMP_VOLUME_SIZE - 0.5));
  return lampVolumeTex(c, LAMP_VOLUME_FAR_Y, LAMP_VOLUME_FAR_AT);
}

vec3 lampVolumeAtLevel(vec3 w, int level) {
  return level == 0 ? lampVolumeFineAt(w) : level == 1 ? lampVolumeNearAt(w) : lampVolumeMidAt(w);
}

// 1 well inside a level's rectangle, 0 at and past its edge
float lampVolumeShare(vec3 w, int level, float fade) {
  vec3 e = min(w - lampVolumeShown[level * 2].xyz, lampVolumeShown[level * 2 + 1].xyz - w) / vec3(fade, fade * 0.25, fade);
  return clamp(min(e.x, min(e.y, e.z)), 0.0, 1.0);
}

// The flicker at world point w: a factor on what the volume holds, from the map in a layer of A (LampVolume.updateGain)
float lampVolumeGain(vec3 w) {
  vec2 c = clamp((w.xz + lampVolumeShift.xz - lampVolumeGainAt) / LAMP_VOLUME_GAIN_CELL, vec2(0.5), vec2(LAMP_VOLUME_GAIN_SIZE - 0.5)) / LAMP_VOLUME_SIZE;
  return 1.0 + textureLod(lampVolumeA, vec3(c, (LAMP_VOLUME_GAIN_AT + 0.5) / LAMP_VOLUME_DEPTH), 0.0).r;
}

struct LampVolumeRead {
  vec3 light;  // the sum of the lamps' light where it was read, W
  vec3 h;      // H, from the world's corner
  float g;     // G
  vec3 at;     // where it was read
};

LampVolumeRead lampVolumeNone(vec3 w) {
  return LampVolumeRead(vec3(0.0), vec3(0.0), 0.0, w);
}

// A walled level at world point w, off a surface along faceN: a cell off it, two where next to nothing of that is free.
LampVolumeRead lampVolumeWalled(vec3 w, vec3 faceN, int level, float size) {
  vec3 q = w + faceN * size;
  vec3 c = lampVolumeAtLevel(q, level);
  vec4 a = textureLod(lampVolumeA, c, 0.0);
  if (a.a < LAMP_VOLUME_FEW && faceN != vec3(0.0)) {
    vec3 q2 = w + faceN * (2.0 * size);
    vec3 c2 = lampVolumeAtLevel(q2, level);
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
  float g = b.a * air;
  return LampVolumeRead(a.rgb * air, b.rgb * air + g * lampVolumeOrigin[level].xyz, g, q);
}

LampVolumeRead lampVolumeMix(LampVolumeRead a, LampVolumeRead b, float t) {
  return LampVolumeRead(mix(a.light, b.light, t), mix(a.h, b.h, t), mix(a.g, b.g, t), mix(a.at, b.at, t));
}

LampVolumeRead lampVolumeFarRead(vec3 w, vec3 faceN) {
  vec3 q = w + faceN * (0.5 * LAMP_VOLUME_FAR_CELL);
  vec3 c = lampVolumeFarAt(q);
  vec4 a = textureLod(lampVolumeA, c, 0.0);
  // alpha is the share of its luminance that may fall on a surface
  float lum = dot(a.rgb, vec3(0.2126, 0.7152, 0.0722));
  if (a.a > 0.0 && lum > 0.0) {
    vec4 b = textureLod(lampVolumeA, c + LAMP_VOLUME_B, 0.0);
    float share = min(a.a / lum, 1.0);
    float g = b.a * share;
    return LampVolumeRead(a.rgb * share, b.rgb * share + g * lampVolumeOrigin[3].xyz, g, q);
  }
  return lampVolumeNone(w);
}

// The volume at world point w, off a surface along faceN (zero in the air): the finest level there is, hands over between them.
LampVolumeRead lampVolumeRead(vec3 world, vec3 faceN) {
  LampVolumeRead r;
  vec3 w = world + lampVolumeShift;
  float fine = lampVolumeShare(w, 0, LAMP_VOLUME_FADE_FINE);
  if (fine >= 1.0) {
    r = lampVolumeWalled(w, faceN, 0, LAMP_VOLUME_FINE);
  } else {
    float near = lampVolumeShare(w, 1, LAMP_VOLUME_FADE_NEAR);
    if (near >= 1.0) {
      r = lampVolumeWalled(w, faceN, 1, LAMP_VOLUME_CELL);
    } else {
      float mid = lampVolumeShare(w, 2, LAMP_VOLUME_FADE_MID);
      if (mid >= 1.0) {
        r = lampVolumeWalled(w, faceN, 2, LAMP_VOLUME_MID);
      } else {
        r = lampVolumeFarRead(w, faceN);
        if (mid > 0.0) {
          r = lampVolumeMix(r, lampVolumeWalled(w, faceN, 2, LAMP_VOLUME_MID), mid);
        }
      }
      if (near > 0.0) {
        r = lampVolumeMix(r, lampVolumeWalled(w, faceN, 1, LAMP_VOLUME_CELL), near);
      }
    }
    if (fine > 0.0) {
      r = lampVolumeMix(r, lampVolumeWalled(w, faceN, 0, LAMP_VOLUME_FINE), fine);
    }
  }
  r.h -= r.g * lampVolumeShift;
  r.at -= lampVolumeShift;
  if (r.g > 0.0) {
    float gain = lampVolumeGain(world);
    r.light *= gain;
    r.h *= gain;
    r.g *= gain;
  }
  return r;
}

// The lamps' light in the air at world point w, for the fog: its own field, on the mid level's cells, each lamp times what the fog is at that lamp
// (LampVolume.addLamp), lamps on storeys the client hides too. Divided by the air, so a filtered read takes only free cells, and fades out in cells
// that are nearly all solid.
vec3 lampVolumeAir(vec3 world) {
  vec3 w = world + lampVolumeShift;
  float share = lampVolumeShare(w, 2, LAMP_VOLUME_FADE_MID);
  if (share <= 0.0) {
    return vec3(0.0);
  }
  vec4 a = textureLod(lampVolumeA, lampVolumeTex(w.xzy / LAMP_VOLUME_MID + 0.5, LAMP_VOLUME_MID_Y, LAMP_VOLUME_FOG_AT), 0.0);
  return a.rgb * (share / max(a.a, 0.25));
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
  vec3 fp = r.h - w * r.g;
  float lp = length(fp);
  if (lp <= 0.0) {
    return 0.0;
  }
  ld = fp / lp;
  ndl = dot(n, ld);
  // the vector's length where it was read: the luminance itself for one lamp, less where lamps pull apart
  float strength = min(length(r.h - r.at * r.g), lum);
  return strength * max(ndl, 0.0) / lum;
}

// How open the line from a to b is, through the volume's air: 0 past anything solid. Steps of a cell of the finest level that fits it into
// twelve (LampVolume.java, math.md 12): a flat solid layer has a step within half a cell of it.
float lampVolumeLine(vec3 from, vec3 to) {
  vec3 a = from + lampVolumeShift, b = to + lampVolumeShift;
  vec3 d = b - a;
  float len = length(d);
  int level = len <= 12.0 * LAMP_VOLUME_FINE && lampVolumeShare(a, 0, 1.0) >= 1.0 ? 0
    : len <= 12.0 * LAMP_VOLUME_CELL && lampVolumeShare(a, 1, 1.0) >= 1.0 ? 1 : 2;
  if (level == 2 && lampVolumeShare(a, 2, 1.0) < 1.0) {
    return 1.0;
  }
  float size = level == 0 ? LAMP_VOLUME_FINE : level == 1 ? LAMP_VOLUME_CELL : LAMP_VOLUME_MID;
  int steps = clamp(int(ceil(len / size)), 1, 12);
  float open = 1.0;
  for (int i = 0; i < steps; ++i) {
    vec3 q = a + d * ((float(i) + 0.5) / float(steps));
    open = min(open, textureLod(lampVolumeA, lampVolumeAtLevel(q, level), 0.0).a);
  }
  return smoothstep(0.5, 0.9, open);
}

// The moving lamps' light on a surface at world point w, off it along faceN; through: what comes through a thin leaf, per its factor
vec3 lampMoversOnSurface(vec3 w, vec3 n, vec3 faceN) {
  vec3 sum = vec3(0.0);
  for (int i = 0; i < lampMoverCount; ++i) {
    vec4 m = lampMovers[i * 2];
    vec3 l = m.xyz - w;
    float d2 = dot(l, l);
    if (m.w <= 0.0 || d2 >= m.w * m.w || dot(n, l) <= 0.0) {
      continue;
    }
    float d = sqrt(d2);
    float att = 1.0 - d / m.w;
    float open = lampVolumeLine(w + faceN * (1.5 * LAMP_VOLUME_FINE), m.xyz);
    sum += lampMovers[i * 2 + 1].rgb * (att * att * open * max(dot(n, l) / d, 0.0));
  }
  return sum;
}

// The same for a drop in the air
vec3 lampMoversInAir(vec3 w) {
  vec3 sum = vec3(0.0);
  for (int i = 0; i < lampMoverCount; ++i) {
    vec4 m = lampMovers[i * 2];
    float r = abs(m.w);
    vec3 l = m.xyz - w;
    float d2 = dot(l, l);
    if (d2 >= r * r) {
      continue;
    }
    float att = 1.0 - sqrt(d2) / r;
    sum += lampMovers[i * 2 + 1].rgb * (att * att * lampVolumeLine(w, m.xyz));
  }
  return sum;
}
