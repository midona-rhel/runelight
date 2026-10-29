/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

//#define ZBUF_DEBUG

#include colorblind_mode
#include shading_config

// 117 HD's scene_frag.glsl (zone renderer) for Lumina: model and ground textures off (so materials have no normal,
// displacement, roughness or ambient occlusion maps), shadows off, no specular (Lumina's rule), no point lights, no
// caustics or water, daylight cycle on. See com.midonarhel.lumina.lighting.Lighting for the uniforms (117 HD's
// UBOGlobal and UBOSky entries) and com.midonarhel.lumina.scene.materials.MaterialManager for the material table.

uniform sampler2DArray textures;
uniform float brightness;       // vanilla brightness, for unlit (vanilla skybox) geometry
uniform float textureLightMode; // unlit geometry
uniform ivec4 entityTint;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

uniform vec3 ambientColor;
uniform float ambientStrength;
uniform vec3 lightColor;
uniform float lightStrength;
uniform vec3 lightDir;
uniform vec3 underglowColor;
uniform float underglowStrength;
uniform vec3 fogColor;
uniform float groundFogStart;
uniform float groundFogEnd;
uniform float groundFogOpacity;
uniform float lightningBrightness;

// 117 HD's material struct, three vec4 per material slot (see MaterialManager.fillSlots):
// (colour map layer, flow map layer, flags (4 overrideBaseColor, 2 unlit, 1 hasTransparency), brightness),
// (1 / texture scale xy, scroll speed xy), (flow map strength, flow map duration xy, subsurface)
#define MAX_MATERIALS 256
layout(std140) uniform materials {
  vec4 materialData[MAX_MATERIALS * 3];
};
// developer comparison views (::lumina debug): 0 off, 1 base colour (117 HD's DISPLAY_BASE_COLOR), 2 lighting
// (DISPLAY_LIGHTING), 3 normals (DISPLAY_NORMAL)
uniform int debugView;

flat in ivec3 fAlphaBiasHsl;
flat in int fFlags;
flat in int fTextureId;
flat in int fMaterial;
flat in int fLit;
centroid in vec3 fTexBlend;
in vec3 fPosition;
in vec3 fNormal;
in vec2 fUv;
#ifdef ZBUF_DEBUG
in float fDepth;
#endif

out vec4 FragColor;

#include "hsl_to_rgb.glsl"
#include "lighting.glsl"
#include "output.glsl"
#include "fog.glsl"
#include "sky.glsl"

#ifdef ZBUF_DEBUG
float linear_depth(float depth) {
  // depth is computed as 100/z, solve for z
  float z = 100 / depth;
  return 1 - z / 10000;  // we don't have a far plane, but the client uses 10000
}
#endif

// 117 HD's thinSheetTransmission (utils/lights.glsl)
float thinSheetTransmission(float incidence, float viewTowardLight, float subsurface) {
  if (subsurface <= 0.0 || incidence >= 0.0)
    return 0.0;
  return -incidence * subsurface * 1.5 * max(viewTowardLight, 0.0);
}

// 117 HD's animationFrame
vec2 animationFrame(vec2 animationDuration) {
  if (animationDuration == vec2(0))
    return vec2(0);
  return mod(vec2(elapsedTime), vec2(animationDuration)) / animationDuration;
}

// Geometry without normals (the vanilla skybox model) is drawn as the GPU plugin draws it.
vec4 vanillaColor() {
  vec3 hsl1 = unpackRawHsl(fAlphaBiasHsl[0]);
  vec3 hsl2 = unpackRawHsl(fAlphaBiasHsl[1]);
  vec3 hsl3 = unpackRawHsl(fAlphaBiasHsl[2]);
  if (entityTint.w > 0) {
    hsl1 += ((entityTint.xyz - hsl1) * entityTint.w) / 128;
    hsl2 += ((entityTint.xyz - hsl2) * entityTint.w) / 128;
    hsl3 += ((entityTint.xyz - hsl3) * entityTint.w) / 128;
  }
  vec3 rgb = hslToRgb(hsl1) * fTexBlend.x + hslToRgb(hsl2) * fTexBlend.y + hslToRgb(hsl3) * fTexBlend.z;
  float a = 1 - float(fAlphaBiasHsl[0] >> 24 & 0xff) / 255.;
  if (fTextureId > 0) {
    vec4 textureColor = texture(textures, vec3(fUv, float(fTextureId - 1)));
    if (textureLod(textures, vec3(fUv, float(fTextureId - 1)), 0.f).a < 1.f)
      discard;
    textureColor = pow(vec4(linearToSrgb(textureColor.rgb), 1), vec4(brightness, brightness, brightness, 1.f));
    float light = dot(fTexBlend, vec3(hsl1.z, hsl2.z, hsl3.z)) / 127.f;
    vec3 mul = (1.f - textureLightMode) * vec3(light) + textureLightMode * rgb;
    return vec4(textureColor.rgb * mul, a);
  }
  return vec4(rgb, a);
}

void main() {
  vec3 cameraPos = vec3(cameraX, cameraY, cameraZ);
  vec4 outputColor;

  if (fLit == 0) {
    outputColor = vanillaColor();
    outputColor.rgb = applyColorAdjustments(outputColor.rgb);
    FragColor = outputColor;
    return;
  }

  vec3 downDir = vec3(0, -1, 0);
  // View & light directions are from the fragment to the camera/light
  vec3 viewDir = normalize(cameraPos - fPosition);

  vec3 flatNormal = normalize(cross(dFdx(fPosition), dFdy(fPosition)));

  vec3 N;
  #if FLAT_SHADING
    N = flatNormal;
  #else
    N = normalize(fNormal);
  #endif

  if (debugView == 3) {
    FragColor = vec4(N * 0.5 + 0.5, 1.0);
    return;
  }

  vec3 hsl1 = unpackRawHsl(fAlphaBiasHsl[0]);
  vec3 hsl2 = unpackRawHsl(fAlphaBiasHsl[1]);
  vec3 hsl3 = unpackRawHsl(fAlphaBiasHsl[2]);

  // Apply entity tint to HSL
  if (entityTint.w > 0) {
    hsl1 += ((entityTint.xyz - hsl1) * entityTint.w) / 128;
    hsl2 += ((entityTint.xyz - hsl2) * entityTint.w) / 128;
    hsl3 += ((entityTint.xyz - hsl3) * entityTint.w) / 128;
  }

  // get vertex colors
  vec4 baseColor1 = vec4(convertHsl(hsl1), 1 - float(fAlphaBiasHsl[0] >> 24 & 0xff) / 255.);
  vec4 baseColor2 = vec4(convertHsl(hsl2), 1 - float(fAlphaBiasHsl[1] >> 24 & 0xff) / 255.);
  vec4 baseColor3 = vec4(convertHsl(hsl3), 1 - float(fAlphaBiasHsl[2] >> 24 & 0xff) / 255.);

  // Convert to linear RGB
  baseColor1.rgb = srgbToLinear(hslToSrgb(baseColor1.xyz));
  baseColor2.rgb = srgbToLinear(hslToSrgb(baseColor2.xyz));
  baseColor3.rgb = srgbToLinear(hslToSrgb(baseColor3.xyz));

  if (debugView == 1) {
    outputColor = baseColor1 * fTexBlend.x + baseColor2 * fTexBlend.y + baseColor3 * fTexBlend.z;
    outputColor.rgb = linearToSrgb(outputColor.rgb);
    FragColor = outputColor;
    return;
  }

  // the face's material (all three vertices of a face share it, so identicalMaterials holds)
  vec4 materialA = materialData[fMaterial * 3];
  vec4 materialB = materialData[fMaterial * 3 + 1];
  vec4 materialC = materialData[fMaterial * 3 + 2];
  int colorMap1 = int(materialA.x);
  int flowMap = int(materialA.y);
  int materialFlags = int(materialA.z);
  bool overrideBaseColor = (materialFlags >> 2 & 1) == 1;
  float unlit = float(materialFlags >> 1 & 1);
  bool hasTransparency = (materialFlags & 1) == 1;

  vec2 blendedUv = fUv;

  // Vanilla tree textures rely on UVs being clamped horizontally, which HD doesn't do at the texture level.
  // Instead we manually clamp vanilla textures with transparency here. Including the transparency check
  // allows texture wrapping to work correctly for the mirror shield.
  if ((fFlags >> 4 & 1) == 1 && hasTransparency)
    blendedUv.x = clamp(blendedUv.x, 0, .984375);

  vec2 uv1 = blendedUv;

  // Scroll UVs
  uv1 += materialB.zw * elapsedTime;

  // Scale from the center
  uv1 = (uv1 - .5) * materialB.xy + .5;

  // get flowMap map
  vec2 flowMapUv = uv1 - animationFrame(materialC.yz);
  float flowMapStrength = materialC.x;
  vec2 uvFlow = texture(textures, vec3(flowMapUv, flowMap)).xy;
  uv1 += uvFlow * flowMapStrength;

  // get diffuse textures
  vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textures, vec3(uv1, colorMap1));
  texColor1.rgb *= materialA.w;
  vec4 texColor2 = texColor1;
  vec4 texColor3 = texColor1;

  ivec3 isOverlay = ivec3(
    fFlags & 1,
    fFlags >> 1 & 1,
    fFlags >> 2 & 1
  );
  int overlayCount = isOverlay[0] + isOverlay[1] + isOverlay[2];
  ivec3 isUnderlay = ivec3(1) - isOverlay;
  int underlayCount = isUnderlay[0] + isUnderlay[1] + isUnderlay[2];

  // calculate blend amounts for overlay and underlay vertices
  vec3 underlayBlend = fTexBlend * isUnderlay;
  vec3 overlayBlend = fTexBlend * isOverlay;

  if (underlayCount == 0 || overlayCount == 0) {
    // if a tile has all overlay or underlay vertices,
    // use the default blend

    underlayBlend = fTexBlend;
    overlayBlend = fTexBlend;
  } else {
    // if there is a mix of overlay and underlay vertices,
    // calculate custom blends for each layer

    float underlayBlendMultiplier = 1.0 / (underlayBlend[0] + underlayBlend[1] + underlayBlend[2]);
    // adjust back to 1.0 total
    underlayBlend *= underlayBlendMultiplier;
    underlayBlend = clamp(underlayBlend, 0, 1);

    float overlayBlendMultiplier = 1.0 / (overlayBlend[0] + overlayBlend[1] + overlayBlend[2]);
    // adjust back to 1.0 total
    overlayBlend *= overlayBlendMultiplier;
    overlayBlend = clamp(overlayBlend, 0, 1);
  }

  // get fragment colors by combining vertex colors and texture samples
  vec4 texA = overrideBaseColor ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
  vec4 texB = overrideBaseColor ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
  vec4 texC = overrideBaseColor ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

  // combine fragment colors based on each blend, creating
  // one color for each overlay/underlay layer
  vec4 underlayColor = texA * underlayBlend.x + texB * underlayBlend.y + texC * underlayBlend.z;
  vec4 overlayColor = texA * overlayBlend.x + texB * overlayBlend.y + texC * overlayBlend.z;

  float overlayMix = 0;

  if (overlayCount > 0 && underlayCount > 0) {
    ivec3 isPrimary = isUnderlay;
    bool invert = true;
    if (overlayCount == 1) {
      isPrimary = isOverlay;
      invert = false;
    }

    float result = dot(fTexBlend, isPrimary);
    if (invert)
      result = 1 - result;

    result = clamp(result * 2 - 1, 0, 1);
    overlayMix = result;
  }

  outputColor = mix(underlayColor, overlayColor, overlayMix);

  // normals
  vec3 normals;
  if ((fFlags >> 3 & 1) == 1) {
    normals = vec3(0, -1, 0);
  } else {
    // no normal maps: sampleNormalMap returns the TBN frame N
    normals = normalize(N);
  }

  float lightDotNormals = dot(normals, lightDir);
  float downDotNormals = dot(downDir, normals);

  #if DISABLE_DIRECTIONAL_SHADING
    lightDotNormals = .7;
  #endif

  // shadows are off
  float inverseShadow = 1;

  // calculate lighting

  // ambient light (no ambient occlusion map: aoFactor 1)
  vec3 ambientLightOut = ambientColor * ambientStrength;

  // directional light
  vec3 dirLightColor = lightColor * lightStrength;

  // apply shadows
  dirLightColor *= inverseShadow;

  vec3 lightOut = max(lightDotNormals, 0.0) * dirLightColor;

  // sky light
  vec3 skyLightColor = fogColor;
  float skyLightStrength = 0.5;
  float skyDotNormals = downDotNormals;
  vec3 skyLightOut = max(skyDotNormals, 0.0) * skyLightColor * skyLightStrength;

  // lightning
  vec3 lightningColor = vec3(.25, .25, .25);
  float lightningDotNormals = downDotNormals;
  vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningBrightness;

  // Artistic thin-sheet transmission
  float subsurface = materialC.w;
  vec3 transmissionOut = vec3(0.0);
  if (subsurface > 0.0)
    transmissionOut = dirLightColor * thinSheetTransmission(
      dot(normals, lightDir), dot(-lightDir, viewDir), subsurface);

  // underglow
  vec3 underglowOut = underglowColor * max(normals.y, 0) * underglowStrength;

  // apply lighting (Lumina: no specular and no point lights; the fresnel surface colour term is zero in 117 HD)
  vec3 compositeLight = ambientLightOut + lightOut + skyLightOut + lightningOut + underglowOut + transmissionOut;

  if (debugView == 2) {
    FragColor = vec4(compositeLight, 1.0);
    return;
  }

  #if VANILLA_COLOR_BANDING
    outputColor.rgb = linearToSrgb(outputColor.rgb);
    outputColor.rgb = srgbToHsv(outputColor.rgb);
    outputColor.b = floor(outputColor.b * 127) / 127;
    outputColor.rgb = hsvToSrgb(outputColor.rgb);
    outputColor.rgb = srgbToLinear(outputColor.rgb);
  #endif

  outputColor.rgb *= mix(compositeLight, vec3(1), unlit);
  outputColor.rgb = linearToSrgb(outputColor.rgb);

  // apply fog
  {
    // ground fog
    float distance = distance(fPosition, cameraPos);
    float closeFadeDistance = 1500;
    float groundFog = 1.0 - clamp((fPosition.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
    groundFog = mix(0.0, groundFogOpacity, groundFog);
    groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);
    float nightFogBlend = 0.0;

    // Scale optical depth, with lighter haze at night. Apply before water coverage too.
    if (skyEnabled != 0) {
      nightFogBlend = 1.0 - smoothstep(sin(radians(-6.0)), 0.0, skySunDir.y);
      groundFog = 1.0 - pow(1.0 - clamp(groundFog, 0.0, 1.0), mix(0.88, 0.352, nightFogBlend));
    }

    // multiply the visibility of each fog
    float fogAmount = calculateFogAmount(fPosition, cameraPos);
    float combinedFog = 1 - (1 - fogAmount) * (1 - groundFog);

    if (skyEnabled != 0) {
      if (combinedFog > 1e-4) {
        vec3 fogViewDir = normalize(fPosition - cameraPos);
        vec3 distanceFogColor = foggedSkyColor(fogViewDir);
        if (groundFog > 1e-4 && fogAmount < 1.0) {
          // Approximate reduced nighttime color sensitivity without changing luminance.
          vec3 nightFogColor = mix(skyGroundFogLight,
            vec3(linearSrgbLuminance(skyGroundFogLight)), 0.5);
          // Preserve the regular fog color by day; introduce adapted local
          // illumination only through civil dusk, from sun altitude 0 to -6 degrees.
          vec3 groundFogColor = mix(distanceFogColor, nightFogColor, nightFogBlend);
          outputColor.rgb = linearToSrgb(mix(srgbToLinear(outputColor.rgb), groundFogColor, groundFog));
        }
        // Preserve the scene's existing distance-fog composition. It hides
        // the local haze at the far boundary, matching the sky behind it.
        if (fogAmount > 1e-4)
          outputColor.rgb = mix(outputColor.rgb, linearToSrgb(distanceFogColor), fogAmount);
      }
    } else {
      outputColor.rgb = mix(outputColor.rgb, linearToSrgb(fogColor), combinedFog);
    }
  }

  outputColor.rgb = applyColorAdjustments(outputColor.rgb);
  outputColor.rgb = applyOutputCorrection(outputColor.rgb);

  FragColor = outputColor;

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
