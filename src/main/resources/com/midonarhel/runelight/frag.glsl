/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

//#define FRAG_UVS
//#define ZBUF_DEBUG

#include colorblind_mode

uniform sampler2DArray textures;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;

in vec4 fColor;
noperspective centroid in float fHsl;
flat in int fTextureId;
in vec2 fUv;
in float fFogAmount;
in vec3 fWorldPos;
in vec3 fNormal;
in float fNormalValid;
in vec4 fBorderColor;
in vec3 fInnerColor;
flat in int fMaterial;
#ifdef ZBUF_DEBUG
in float fDepth;
#endif

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

layout(location = 0) out vec4 FragColor;
layout(location = 1) out vec4 FragNormal;
layout(location = 2) out vec4 FragAlpha;  // transparency layer, see SceneTargets
layout(location = 3) out vec4 FragMaterial;  // emissive, transmissive, water (water.glsl), snow; see materials.glsl

// Transparent faces are drawn in a separate pass into their own layer and lit right here, because the composite
// pass only sees the opaque surface behind them. Same lighting as the composite, without shadows.
uniform int alphaPass;
uniform int outdoors;
uniform int proceduralSky;
uniform vec3 casterDir;
uniform int sunIsCaster;
uniform float sunAltitude;

#include "sky.glsl"
#include "materials.glsl"
#include "environment.glsl"
#include "edge_fade.glsl"

#include "hsl_to_rgb.glsl"

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

#ifdef ZBUF_DEBUG
float linear_depth(float depth) {
  // depth is computed as 100/z, solve for z
  float z = 100 / depth;
  return 1 - z / 10000;  // we don't have a far plane, but the client uses 10000
}
#endif

void main() {
  vec4 c;

  if (fTextureId > 0) {
    int textureIdx = fTextureId - 1;

    vec4 textureColor = texture(textures, vec3(fUv, float(textureIdx)));
    vec4 textureColor0 = textureLod(textures, vec3(fUv, float(textureIdx)), 0.f);

    if (textureColor0.a < 1.f)
      discard;

    textureColor = vec4(textureColor.rgb, 1.f);

    textureColor = pow(textureColor, vec4(brightness, brightness, brightness, 1.f));

    // textured triangles hsl is a 7 bit lightness 2-126
    float light = fHsl / 127.f;
    vec3 mul = (1.f - textureLightMode) * vec3(light) + textureLightMode * fColor.rgb;
    c = textureColor * vec4(mul, fColor.a);
  } else {
    // pick interpolated hsl or rgb depending on smooth banding setting
    vec3 hsl = vec3(int(fHsl) >> 10 & 63, int(fHsl) >> 7 & 7, int(fHsl) & 127);
    vec3 rgb = mix(fColor.rgb, hslToRgb(hsl), smoothBanding);
    // Ground blending, 117 HD's overlay mix: where a triangle has vertices both on and off an overlay/underlay
    // border, interpolate each group's colors separately and switch between them over the middle half of the
    // way, so paths keep a soft but defined edge instead of smearing across the neighboring tile.
    float w = fBorderColor.a;
    if (w > 0.001 && w < 0.999 && smoothBanding < 0.5) {
      vec3 borderRgb = fBorderColor.rgb / w;
      vec3 innerRgb = fInnerColor / (1.0 - w);
      rgb = mix(innerRgb, borderRgb, clamp(w * 2.0 - 1.0, 0.0, 1.0));
    }
    c = vec4(rgb, fColor.a);
  }

#if COLORBLIND_MODE > 0
  c.rgb = colorblind(c.rgb);
#endif

  // opaque surfaces are lit and fogged later by the composite pass
  FragColor = c;

  // The triangle's own normal from screen-space derivatives, facing the camera. Used for faces the game marks
  // as flat shaded, and as the reference that orients the smooth vertex normal.
  vec3 n = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
  if (dot(n, vec3(cameraX, cameraY, cameraZ) - fWorldPos) < 0.0)
    n = -n;
  if (fNormalValid > 0.5 && dot(fNormal, fNormal) > 1e-8) {
    vec3 sn = normalize(fNormal);
    n = dot(sn, n) < 0.0 ? -sn : sn;
  }
  FragNormal = vec4(n * 0.5 + 0.5, c.a);
  // alpha 1: the coverage the composite divides by (SceneTargets.clear); the opaque pass writes without blending
  // water: 1 bed (always under the water), 0 land terrain (never), 0.5 anything else (if below the surface)
  float water = (fMaterial & 8) != 0 ? 1.0 : (fMaterial & 16) != 0 ? 0.0 : 0.5;
  FragMaterial = vec4(materialEmissive(fMaterial), materialTransmissive(fMaterial), water, materialSnow(fMaterial));

  if (alphaPass == 1) {
    vec3 light;
    if (outdoors == 1) {
      vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
      float ndl = dot(n, casterDir);
      vec3 viewDir = normalize(vec3(cameraX, cameraY, cameraZ) - fWorldPos);
      light = (ambientLight(sunAltitude) + skyLight(sunAltitude) * max(-n.y, 0.0)) * envAmbient
        + caster * envSun * (max(ndl, 0.0) + transmission(ndl, dot(-casterDir, viewDir), materialTransmissive(fMaterial)));
    } else {
      light = envCaveLight(n);
    }
    light = emissiveLight(light, materialEmissive(fMaterial));
    vec3 fog = envFog(outdoors == 1 && proceduralSky == 1 ? skyHorizon(sunAltitude) : fogColor.rgb, outdoors == 1,
      sunAltitude);
    float fogAmount = max(fFogAmount, envFogAmount(fWorldPos - vec3(cameraX, cameraY, cameraZ), fWorldPos.xz));
    FragAlpha = vec4(mix(shade(c.rgb, light), fog, fogAmount), c.a);
    // transparent faces vanish toward the edge of the world, where the composite shows the sky behind them
    FragAlpha.a *= 1.0 - edgeFadeAmount(fWorldPos);
  }

#ifdef FRAG_UVS
  if (fTextureId > 0) {
    FragColor = vec4(fUv.x, 0, fUv.y, 1);
  }
#endif

#ifdef ZBUF_DEBUG
  float dc = linear_depth(fDepth);
  if (dc > 1.0) {
    FragColor = vec4(1, 0, 0, 1);
  } else if (dc < -1.0) {
    FragColor = vec4(0, 0, 1, 1);
  } else if (dc < 0.0) {
    FragColor = vec4(0, 1, 0, 1);
  } else {
    FragColor = vec4(dc, dc, dc, 1);
  }
#endif
}
