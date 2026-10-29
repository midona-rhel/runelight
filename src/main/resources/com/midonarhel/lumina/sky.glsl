/*
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

// 117 HD's sky gradient and sky fog (utils/sky.glsl, utils/sky_fog.glsl, utils/starfield.glsl nightHorizonOffset),
// for scene fog and the sky background. The uniforms are UBOSky's entries of the same names (see
// com.midonarhel.lumina.lighting.Lighting). Not ported: the night sky background (stars, nebulae) blended in by
// visibleSkyColor below astronomical twilight, the sun and moon disks, aurorae, glare reduction (off by default).
// Needs lighting.glsl.

#define CELESTIAL_HORIZON_OFFSET 0.05

uniform int skyEnabled;
uniform vec3 skyZenithColor;
uniform vec3 skyHorizonColor;
uniform vec3 skySunColor;
uniform float skyCustomGradient;
uniform float skyHorizonWidth;
uniform vec3 skySunDir;
uniform vec3 skyMoonDir;
uniform vec3 skyMoonDiskColor;
uniform float skyMoonIllumination;
uniform float skyMoonVisibility;
uniform vec3 skyFogColor;
uniform float skyFogDensity;
uniform float skyFogHorizonHeight;
uniform vec3 skyGroundFogLight;
uniform float skyVisibility;
uniform float skyStarHorizonHeight;

// Keep the daytime sky visible through astronomical twilight.
float skyDaylightFactor() {
  return smoothstep(sin(radians(-18.0)), 0.0, skySunDir.y);
}

// Release opacity rather than optical depth, retaining subdued sunset colors
// before gradually revealing the night sky.
float skyFogDaylightFactor() {
  return smoothstep(sin(radians(-25.0)), 0.f, skySunDir.y);
}

float skyFogTransmittance(float upAmount) {
  // Broaden the horizon band for the game's finite view distance. This artistic
  // path approximation has a smooth, flat slope at the horizon and stays positive.
  float elevation = max(upAmount - skyFogHorizonHeight, 0.0) / 0.16;
  float pathLength = 1.0 / (1.0 + elevation * elevation);
  float daytimeTransmittance = exp(-skyFogDensity * pathLength);
  return skyVisibility * mix(1.0, daytimeTransmittance, skyFogDaylightFactor());
}

// Forward lobe of Henyey-Greenstein scattering, normalized to one on-axis.
float skyFogForwardScatter(float lightDot) {
  const float anisotropy = 0.85;
  float denominator = 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * clamp(lightDot, -1.0, 1.0);
  return pow(1.0 - anisotropy, 3.0) / max(denominator * sqrt(denominator), 1e-5);
}

vec3 applySkyFog(vec3 color, float transmittance) {
  return mix(skyFogColor, color, transmittance);
}

vec3 skyFogGlow(vec3 viewDir, vec3 sunDir, vec3 moonDir, float transmittance) {
  vec3 glow =
    skySunColor * skyFogForwardScatter(dot(viewDir, sunDir)) * 0.08 +
    skyMoonDiskColor * skyFogForwardScatter(dot(viewDir, moonDir)) *
      skyMoonIllumination * skyMoonVisibility * 0.003;
  // Attenuate the glow to feel approximately right
  return glow * (1.0 - transmittance) * sqrt(transmittance);
}

float nightHorizonOffset(float height) {
  return (height - 1.0) * 1.2;
}

struct SkyGradient {
  vec3 sunDir;         // sun direction with the perceived-horizon offset applied
  float upAmount;      // how much the view is looking up (-viewDir.y)
  float sunSideBlend;  // 0 = facing away from sun, 1 = facing toward sun
  float zenithBlend;   // 0 = horizon, 1 = zenith
  float nightFade;     // 0 = deep night, 1 = sun at/above horizon
  vec3 color;          // linear sRGB gradient + sun glow (before haze/stars/moon)
};

SkyGradient computeSkyGradient(vec3 viewDir, bool includeSunGlow) {
  SkyGradient g;

  g.sunDir = normalize(vec3(skySunDir.x, -skySunDir.y + CELESTIAL_HORIZON_OFFSET, skySunDir.z));
  g.upAmount = -viewDir.y;

  // Fade the sun-facing bias near vertical views to avoid pinching.
  vec2 viewHoriz = vec2(viewDir.x, viewDir.z);
  float viewHorizLen = length(viewHoriz);
  vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
  vec3 sunHorizontal = normalize(vec3(g.sunDir.x, 0.0, g.sunDir.z));

  float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
  g.sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

  g.zenithBlend = smoothstep(-0.1, 0.7, g.upAmount);

  float sunAltitude = clamp(skySunDir.y, 0.0, 1.0);
  float daytimeFactor = smoothstep(0.0, 0.64, sunAltitude);
  float dimFadeout = smoothstep(0.0, 0.34, sunAltitude);
  float darkSideDim = mix(0.7, 1.0, dimFadeout);
  vec3 darkSideColor = mix(skyZenithColor * darkSideDim, skyHorizonColor, daytimeFactor);
  vec3 sunSideColor = skyHorizonColor;

  // Retain residual twilight until the sun reaches astronomical night at -18 degrees.
  g.nightFade = skyDaylightFactor() * (1.0 - skyCustomGradient);

  vec3 horizonColor = mix(darkSideColor, sunSideColor, g.sunSideBlend);
  horizonColor = mix(skyZenithColor, horizonColor, g.nightFade);

  g.color = mix(horizonColor, skyZenithColor, g.zenithBlend);

  // Custom skies have a symmetric horizon band independent of the sun's direction.
  vec3 customColor = mix(skyHorizonColor, skyZenithColor,
    smoothstep(0.0, skyHorizonWidth, abs(g.upAmount)));
  g.color = mix(g.color, customColor, skyCustomGradient);

  // Use multiply/sqrt equivalents of pow for the glow falloffs.
  // Below sunset, keep scattered sunlight at the perceived horizon. Its color and
  // disappearance are authored in sunGlow, independently of the sun disk's position.
  vec3 glowDir = normalize(vec3(skySunDir.x, -max(0.0, skySunDir.y) + CELESTIAL_HORIZON_OFFSET, skySunDir.z));
  float sunDot = dot(viewDir, glowDir);
  if (includeSunGlow && sunDot > 0.0) {
    float s2 = sunDot * sunDot;
    float s4 = s2 * s2;
    float s8 = s4 * s4;
    float s16 = s8 * s8;
    float s32 = s16 * s16;
    float s128 = s32 * s32; s128 = s128 * s128;
    float coreGlow = s128 * 0.4;
    float innerGlow = s32 * 0.25;
    float midGlow = s8 * 0.15;
    float outerGlow = s2 * sunDot * sqrt(sunDot) * 0.08;
    vec3 glow = skySunColor * (coreGlow + innerGlow + midGlow + outerGlow);
    g.color += glow;
  }

  return g;
}

float nightSkyBlend(SkyGradient sky) {
  float baseProgress = 1.0 - sky.nightFade;
  float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
  return pow(baseProgress, mix(0.4, 0.9, sunProximity));
}

float nightSkyHorizonFade(float upAmount, float horizonShift) {
  return smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, upAmount);
}

// Broad visible sky color, excluding celestial disks, aurorae, and individual stars.
// Lumina: the night sky background that 117 HD blends in when the blend amount exceeds 0.001 is not ported.
vec3 visibleSkyColor(SkyGradient sky, vec3 viewDir) {
  return sky.color;
}

// Linear sky radiance for scene fog and broad water reflections, without disks or stars.
vec3 foggedSkyColor(vec3 viewDir) {
  SkyGradient sky = computeSkyGradient(viewDir, true);
  vec3 color = visibleSkyColor(sky, viewDir);
  vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + CELESTIAL_HORIZON_OFFSET, skyMoonDir.z));
  float transmittance = skyFogTransmittance(sky.upAmount);
  vec3 glow = skyFogGlow(viewDir, sky.sunDir, moonDir, transmittance);
  color = applySkyFog(color, transmittance) + glow;
  return color;
}
