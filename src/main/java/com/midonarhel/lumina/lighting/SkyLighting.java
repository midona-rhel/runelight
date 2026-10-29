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
package com.midonarhel.lumina.lighting;

import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.scene.sky.SkyConfiguration;
import com.midonarhel.lumina.scene.sky.SkyManager;
import com.midonarhel.lumina.scene.sky.SkyState;
import javax.inject.Inject;
import javax.inject.Singleton;
import static com.midonarhel.lumina.util.ColorUtils.ATMOSPHERIC_OPTICAL_DEPTH;
import static com.midonarhel.lumina.util.ColorUtils.linearSrgbLuminance;
import static com.midonarhel.lumina.util.MathUtils.*;

/**
 * Lumina's {@link LightingSource}: 117 HD's frame lighting. Ported literally from
 * <ul>
 * <li>ZoneRenderer's per-frame update (environment, sky, fog depth, ground fog, underglow, light direction from the
 * directional camera),</li>
 * <li>SkyRenderer.prepareFrame: environment light, the daylight cycle path (evaluateLighting with applyAtmosphere and
 * night exposure, the sun/moon handoff and the redistribution of suppressed directional light into ambient light),
 * the sky uniforms, and updateGlobalUbo's normalisation of colours by their luminance.</li>
 * </ul>
 * Settings are read from {@link HdSettings}. Not ported: water colours, shadows, stars/moon disk/sky drawing parameters other than the gradient and fog.
 */
@Singleton
public class SkyLighting implements LightingSource
{
	private final EnvironmentManager environmentManager;
	private final SkyManager skyManager;

	private final float[] directionalLight = new float[3];
	private final float[] ambientLight = new float[3];
	private final float[] fogColor = new float[3];

	private final SkyState.LightingSample endpointSample = new SkyState.LightingSample();

	private final LightingFrame fromFrame = new LightingFrame();
	private final LightingFrame toFrame = new LightingFrame();
	private final LightingFrame currentFrame = new LightingFrame();

	private long transitionId;
	private float previousTransition = 1;
	private boolean interruptedTransition;

	private boolean skyEnabled;
	private boolean usesMoonShadows;

	private static final class LightingFrame extends SkyState.GradientSample
	{
		private final SkyConfiguration configuration = new SkyConfiguration();
		private final float[] ambientLight = new float[3];
		private final float[] sunDirectionalLight = new float[3];
		private final float[] moonDirectionalLight = new float[3];
		private final float[] fog = new float[3];
		private final float[] groundFogLight = new float[3];
		private final float[] moonDisk = new float[3];
		private float fogDensity;
		private float fogHorizonAltitude;
		private float visibility;
		private float customGradient;
		private float dayLuminance;
		private float nightLuminance;

		private void interpolate(LightingFrame from, LightingFrame to, float t)
		{
			configuration.interpolateLightingParameters(from.configuration, to.configuration, t);
			mix(sunDirectionalLight, from.sunDirectionalLight, to.sunDirectionalLight, t);
			mix(moonDirectionalLight, from.moonDirectionalLight, to.moonDirectionalLight, t);
			mix(ambientLight, from.ambientLight, to.ambientLight, t);
			mix(fog, from.fog, to.fog, t);
			mix(groundFogLight, from.groundFogLight, to.groundFogLight, t);
			mix(moonDisk, from.moonDisk, to.moonDisk, t);
			mix(zenith, from.zenith, to.zenith, t);
			mix(horizon, from.horizon, to.horizon, t);
			mix(sunGlow, from.sunGlow, to.sunGlow, t);
			fogDensity = mix(from.fogDensity, to.fogDensity, t);
			fogHorizonAltitude = mix(from.fogHorizonAltitude, to.fogHorizonAltitude, t);
			visibility = mix(from.visibility, to.visibility, t);
			customGradient = mix(from.customGradient, to.customGradient, t);
			dayLuminance = mix(from.dayLuminance, to.dayLuminance, t);
			nightLuminance = mix(from.nightLuminance, to.nightLuminance, t);
		}
	}

	@Inject
	SkyLighting(EnvironmentManager environmentManager, SkyManager skyManager)
	{
		this.environmentManager = environmentManager;
		this.skyManager = skyManager;
	}

	@Override
	public void fill(Lighting out, SceneData scene, double elapsedTime, int drawDistance, boolean hasSkyboxModel)
	{
		// ZoneRenderer: environmentManager.update, skyManager.update, skyRenderer.prepareFrame
		if (scene != null)
		{
			environmentManager.update(scene, elapsedTime);
		}
		skyManager.update();
		prepareFrame(out);

		// ZoneRenderer: light direction from the directional camera (skyManager.updateDirectionalCamera)
		float[] orientation = skyManager.getDirectionalOrientation(usesMoonShadows);
		forwardDirection(out.lightDir, orientation[0], orientation[1]);

		boolean replaceVanillaSkybox =
			HdSettings.replaceVanillaSkyboxes &&
				environmentManager.getTargetEnvironment().hideVanillaSkyboxes;
		out.vanillaSkybox = hasSkyboxModel && !replaceVanillaSkybox;

		Environment env = environmentManager.getCurrentEnvironment();
		float fogDepth = 0;
		if (!out.vanillaSkybox)
		{
			switch (HdSettings.fogDepthMode)
			{
				case USER_DEFINED:
					fogDepth = HdSettings.fogDepth;
					break;
				case DYNAMIC:
					fogDepth = env.fogDepth;
					break;
			}
			fogDepth *= min(drawDistance, 90) / 10.f;
		}
		out.useFog = fogDepth > 0;
		out.fogDepth = fogDepth;

		copyTo(out.underglowColor, env.getUnderglowColor());
		out.underglowStrength = env.underglowStrength;

		out.groundFogStart = env.groundFogStart;
		out.groundFogEnd = env.groundFogEnd;
		out.groundFogOpacity = HdSettings.groundFog ? env.groundFogOpacity : 0;

		out.lightningBrightness = environmentManager.getLightningBrightness();
	}

	/**
	 * -(third row of rotateX(pitch) * rotateY(yaw)): 117 HD's Camera.getForwardDirection.
	 */
	private static void forwardDirection(float[] out, float yaw, float pitch)
	{
		float sp = sin(pitch), cp = cos(pitch);
		float sy = sin(yaw), cy = cos(yaw);
		out[0] = cp * sy;
		out[1] = -sp;
		out[2] = -cp * cy;
	}

	private void prepareFrame(Lighting out)
	{
		skyEnabled = skyManager.getState().cycleActive;

		Environment env = environmentManager.getCurrentEnvironment();
		copyTo(directionalLight, env.getDirectionalColor());
		multiply(directionalLight, directionalLight, env.directionalStrength);
		copyTo(ambientLight, env.getAmbientColor());
		multiply(ambientLight, ambientLight, env.ambientStrength);
		copyTo(fogColor, env.getFogColor());

		if (skyEnabled)
		{
			updateSky(out, skyManager.getState());
		}
		else
		{
			if (HdSettings.nightAdaptationEverywhere)
			{
				float luminance =
					linearSrgbLuminance(ambientLight) +
						linearSrgbLuminance(directionalLight) * max(0, sin(env.getShadowAngles()[0]));
				float exposure = getNightExposure(luminance, env.nightExposure * 1.5f);
				multiply(ambientLight, ambientLight, exposure);
				multiply(directionalLight, directionalLight, exposure);
			}
			usesMoonShadows = false;
			previousTransition = 1;
			interruptedTransition = false;
			out.skyEnabled = false;
		}
		updateGlobalUbo(out);
	}

	private void updateGlobalUbo(Lighting out)
	{
		copyTo(out.fogColor, fogColor);

		if (HdSettings.useLegacyBrightness)
		{
			float factor = (float) HdSettings.legacyBrightness / 20;
			multiply(ambientLight, ambientLight, factor);
			multiply(directionalLight, directionalLight, factor);
		}
		float effectiveAmbientStrength = linearSrgbLuminance(ambientLight);
		float effectiveDirectionalStrength = linearSrgbLuminance(directionalLight);
		divide(ambientLight, ambientLight, effectiveAmbientStrength);
		divide(directionalLight, directionalLight, effectiveDirectionalStrength);
		out.ambientStrength = effectiveAmbientStrength;
		copyTo(out.ambientColor, ambientLight);
		out.lightStrength = effectiveDirectionalStrength;
		copyTo(out.lightColor, directionalLight);
	}

	private void updateSky(Lighting out, SkyState state)
	{
		float transition = state.transitionProgress;
		if (transitionId != state.transitionId)
		{
			transitionId = state.transitionId;
			interruptedTransition = previousTransition < 1 && transition < 1;
			if (interruptedTransition)
			{
				fromFrame.interpolate(currentFrame, currentFrame, 1);
			}
		}
		evaluateLighting(toFrame, state.toEnvironment);
		if (transition < 1)
		{
			if (!interruptedTransition)
			{
				evaluateLighting(fromFrame, state.fromEnvironment);
			}
			currentFrame.interpolate(fromFrame, toFrame, transition);
		}
		else
		{
			currentFrame.interpolate(toFrame, toFrame, 1);
		}
		previousTransition = transition;
		// Blend complete HDR light contributions before encoding the global UBO.
		copyTo(ambientLight, currentFrame.ambientLight);
		float totalLuminance = currentFrame.dayLuminance + currentFrame.nightLuminance;
		float handoff = totalLuminance > 0 ? currentFrame.nightLuminance / totalLuminance : 0;
		// Limit the luminosity-driven handoff smoothly: its midpoint must coincide
		// with the disk disappearing at -4 degrees (celestialHorizonFade in the shader).
		// This leaves room below that altitude for moon shadows to gain contrast.
		handoff = min(handoff, 1 - smoothstep(-8, 0, state.sunAltitudeDegrees));
		usesMoonShadows = state.sunAltitudeDegrees < -4 && state.moonAltitudeDegrees > 0 && handoff >= .5f;
		float moonShadowVisibility;
		if (usesMoonShadows)
		{
			moonShadowVisibility = smoothstep(.5f, .8f, handoff);
			multiply(directionalLight, currentFrame.moonDirectionalLight, moonShadowVisibility);
		}
		else
		{
			multiply(directionalLight, currentFrame.sunDirectionalLight, 1 - smoothstep(.2f, .5f, handoff));
		}
		// Redistribute both sources' suppressed directional light into ambient.
		// max(dot(normal, lightDir), 0) averages to 1/4 over the sphere of normals;
		// this preserves average diffuse illumination while shadow contrast fades.
		for (int i = 0; i < ambientLight.length; i++)
		{
			ambientLight[i] += .25f * (currentFrame.sunDirectionalLight[i] + currentFrame.moonDirectionalLight[i] - directionalLight[i]);
		}
		copyTo(fogColor, currentFrame.horizon);
		out.skyFogDensity = currentFrame.fogDensity;
		out.skyFogHorizonHeight = sin(currentFrame.fogHorizonAltitude * DEG_TO_RAD);
		out.skyVisibility = currentFrame.visibility;
		copyTo(out.skyFogColor, currentFrame.fog);
		copyTo(out.groundFogLight, currentFrame.groundFogLight);
		out.customGradient = currentFrame.customGradient;
		copyTo(out.moonDiskColor, currentFrame.moonDisk);
		updateSkyUbo(out, currentFrame.configuration, state, currentFrame);
	}

	private void evaluateLighting(LightingFrame out, Environment env)
	{
		SkyConfiguration sky = env.getSky();
		copyTo(endpointSample.referenceFogColorLinear, env.getFogColor());
		environmentManager.applyLightning(endpointSample.referenceFogColorLinear);
		skyManager.sampleLighting(endpointSample, env, endpointSample.referenceFogColorLinear);
		SkyState state = endpointSample.sky;
		float sunAltDeg = state.sunAltitudeDegrees;
		multiply(out.sunDirectionalLight, env.getDirectionalColor(), env.directionalStrength);
		multiply(out.ambientLight, env.getAmbientColor(), env.ambientStrength);
		float moonAltDeg = state.moonAltitudeDegrees;

		float moonLightIllumination = state.moonLightIllumination;
		// fogDepth is an artistic density control, not a physical extinction coefficient.
		float defaultDensity = max(0, env.fogDepth) / 100;
		out.fogDensity = max(0, sky.skyFogDensity < 0 ? defaultDensity : sky.skyFogDensity);
		out.fogHorizonAltitude = clamp(sky.skyFogHorizonAltitude, -90, 90);
		out.visibility = saturate(sky.skyVisibility);
		copyTo(out.fog, endpointSample.horizon);
		if (sky.skyFogColor != null)
		{
			// Darken the haze before it clears, rather than replacing it with the bright sunset horizon.
			float nightBlend = smoothstep(5, -5, sunAltDeg);
			for (int i = 0; i < out.fog.length; i++)
			{
				float nightColor = sky.customGradient ?
					endpointSample.horizon[i] :
					sky.nightHorizonColor[i] * sky.nightHorizonStrength;
				out.fog[i] = mix(sky.skyFogColor[i], nightColor, nightBlend);
			}
		}

		multiply(out.moonDirectionalLight, sky.moonDirectionalColor, state.moonDirectionalStrength * moonLightIllumination);
		float[] nightAmbientLight = multiply(sky.moonAmbientColor, sky.moonAmbientStrength * moonLightIllumination);
		applyAtmosphere(out.sunDirectionalLight, out.ambientLight, sunAltDeg);
		applyAtmosphere(out.moonDirectionalLight, nightAmbientLight, moonAltDeg);
		// Airglow/starlight is independent of the environment's daytime lighting and moon phase.
		for (int i = 0; i < nightAmbientLight.length; i++)
		{
			nightAmbientLight[i] += sky.nightAmbientColor[i] * sky.nightAmbientStrength;
		}
		float moonLuminance = linearSrgbLuminance(out.moonDirectionalLight);
		float nightLuminance =
			linearSrgbLuminance(nightAmbientLight) +
				moonLuminance * max(0, sin(moonAltDeg * DEG_TO_RAD));
		// Use the same upward-facing reference surface as adaptation, before any
		// shadow handoff attenuation, so fading shadows cannot drive their own fade.
		out.dayLuminance =
			linearSrgbLuminance(out.ambientLight) +
				linearSrgbLuminance(out.sunDirectionalLight) * max(0, sin(sunAltDeg * DEG_TO_RAD));
		float nightExposure = env.nightExposure * 1.5f;
		float exposure = getNightExposure(out.dayLuminance + nightLuminance, nightExposure);
		out.nightLuminance = nightLuminance * exposure;
		if (HdSettings.nightAdaptationEverywhere)
		{
			out.dayLuminance *= exposure;
			multiply(out.ambientLight, out.ambientLight, exposure);
			multiply(out.sunDirectionalLight, out.sunDirectionalLight, exposure);
		}
		else
		{
			float exposureAdjustedDayLuminance = out.dayLuminance / max(HdSettings.nightBrightness, 1);
			float targetLuminance = nightExposure - exposureAdjustedDayLuminance;
			// Ease in during the sunset
			exposure = mix(1, getNightExposure(nightLuminance, targetLuminance), smoothstep(3.8f, -6, sunAltDeg));
		}
		multiply(nightAmbientLight, nightAmbientLight, exposure);
		multiply(out.moonDirectionalLight, out.moonDirectionalLight, exposure);
		add(out.ambientLight, out.ambientLight, nightAmbientLight);
		// Broad local scattering, independent of shadow ownership. The quarter is
		// the spherical average of a directional source in our diffuse-light units;
		// this omits forward scattering and local lights. Night exposure is already applied.
		for (int i = 0; i < out.groundFogLight.length; i++)
		{
			out.groundFogLight[i] = out.ambientLight[i] + .25f * (out.sunDirectionalLight[i] + out.moonDirectionalLight[i]);
		}
		// Transition environments already contain the resolved authored/default fog color,
		// not the definition's override flags. Use it as an artistic tint, preserving magnitude.
		multiply(out.groundFogLight, out.groundFogLight, env.getFogColor());

		copyTo(out.zenith, endpointSample.zenith);
		copyTo(out.horizon, endpointSample.horizon);
		copyTo(out.sunGlow, endpointSample.sunGlow);
		multiply(out.moonDisk, sky.moonDiskColor, sky.moonDiskStrength);
		out.customGradient = sky.customGradient ? 1 : 0;
		out.configuration.interpolateLightingParameters(sky, sky, 1);
	}

	private void updateSkyUbo(Lighting out, SkyConfiguration configuration, SkyState state, SkyState.GradientSample sky)
	{
		out.skyEnabled = true;
		copyTo(out.zenithColor, sky.zenith);
		copyTo(out.horizonColor, sky.horizon);
		copyTo(out.sunColor, sky.sunGlow);
		out.horizonWidth = sin(clamp(configuration.horizonWidth, .001f, 90) * DEG_TO_RAD);
		copyTo(out.skySunDir, state.sunDirection);
		copyTo(out.moonDir, state.moonDirection);
		out.moonIllumination = state.moonIllumination;
		out.moonVisibility = state.moonVisibility;
		out.starHorizonHeight = configuration.starHorizonHeight;
	}

	private static float getNightExposure(float luminance, float target)
	{
		final float softFloor = 0.006f;
		// Above 100%, raise the target rather than extrapolating the blend past full adaptation.
		float adaptedExposure = max(1, (target * max(HdSettings.nightBrightness, 1) + softFloor) / (max(0, luminance) + softFloor));
		return mix(1, adaptedExposure, saturate(HdSettings.nightBrightness));
	}

	/**
	 * Attenuate overhead-calibrated linear lighting through a shared reference atmosphere, in place.
	 * Authored ambient/direct ratios do not determine atmospheric density.
	 */
	private static void applyAtmosphere(float[] directional, float[] ambient, float altitudeDegrees)
	{
		float elevation = sin(max(0, altitudeDegrees) * DEG_TO_RAD);
		float curvature = 1 / 38.f;
		float airMass = sqrt(1 + curvature * curvature) / sqrt(elevation * elevation + curvature * curvature);
		float directVisibility = smoothstep(0, .5f, altitudeDegrees);
		float twilight = smoothstep(-18, -12, altitudeDegrees);
		for (int i = 0; i < 3; i++)
		{
			float depth = ATMOSPHERIC_OPTICAL_DEPTH[i];
			directional[i] *= exp(-depth * (airMass - 1)) * directVisibility;
			float scattering = (1 - exp(-depth * airMass)) / (1 - exp(-depth)) / airMass;
			// Approximate upper-atmosphere twilight: red fades faster than blue below sunset.
			float twilightDecay = exp(min(0, altitudeDegrees) * (.65f - .1f * i));
			ambient[i] *= scattering * twilightDecay * twilight;
		}
	}

	/** Developer dump: the inputs of the sky fog colour, for comparing against 117 HD. */
	public String describe()
	{
		var state = skyManager.getState();
		var env = state.toEnvironment;
		return "toEnvironment=" + env + " fog=" + java.util.Arrays.toString(env == null ? null : env.getFogColor())
			+ " hasFogColorOverride=" + (env != null && env.hasFogColorOverride) + " isOverworld=" + (env != null && env.isOverworld)
			+ " referenceFog=" + java.util.Arrays.toString(endpointSample.referenceFogColorLinear)
			+ " sampleHorizon=" + java.util.Arrays.toString(endpointSample.horizon)
			+ " sampleSunAlt=" + endpointSample.sky.sunAltitudeDegrees
			+ " overworldArea=" + com.midonarhel.lumina.scene.areas.Area.OVERWORLD;
	}
}
