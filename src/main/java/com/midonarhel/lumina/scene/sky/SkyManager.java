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
package com.midonarhel.lumina.scene.sky;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.midonarhel.lumina.config.DaylightCycle;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.util.AstronomyUtils;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static com.midonarhel.lumina.util.MathUtils.*;

/**
 * The parts of 117 HD's SkyManager (rs117.hd.scene.SkyManager) that produce the sun and moon state the lighting
 * reads: preset loading (sky_presets.json), the fixed sky preset of the daylight cycle setting, sun and moon angles,
 * moon illumination and the transitions between environment skies. Not ported: the real-time and custom cycles,
 * light schedules, aurora, the celestial pole and moon libration (sky drawing only).
 * <p>
 * The daylight cycle, moon behavior and moon phase settings are read from {@link HdSettings}; the real-time and
 * custom cycles (which need the latitude, longitude and cycle duration settings) are not ported.
 */
@Slf4j
@Singleton
public class SkyManager
{
	public static Map<String, SkyConfiguration> PRESETS = Map.of();
	public static final float[] DEFAULT_LATLON = {40.7128f, -74.0060f}; // New York City

	private static final String DEFAULT_PRESET_NAME = "GIELINOR";
	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = {25 * DEG_TO_RAD, 73 * DEG_TO_RAD};
	private static final float SYNTHETIC_MOON_PERIOD_DAYS = 29.53059f;
	private static final float NIGHT_MOON_PHASE_TILT = -.35f;

	private static final long SECOND_MS = 1000;
	private static final long MINUTE_MS = 60 * SECOND_MS;
	private static final long HOUR_MS = 60 * MINUTE_MS;
	private static final long DAY_MS = 24 * HOUR_MS;

	private final EnvironmentManager environmentManager;

	private long frameUtcMillis;

	@Getter
	private final SkyState state = new SkyState();
	private long transitionId;
	private ResolvedEndpoint interruptedFrom;

	private final ResolvedEndpoint resolvedFrom = new ResolvedEndpoint();
	private final ResolvedEndpoint resolvedTo = new ResolvedEndpoint();

	private static final class ResolvedMoon
	{
		private final float[] angles = new float[2];
		private final float[] illuminationDirection = new float[3];
		private float illumination;
		private float lightIllumination;
		private float visibility;
		private float directionalStrength;
	}

	private static final class ResolvedEndpoint
	{
		private final float[] sunAngles = new float[2];
		private final ResolvedMoon moon = new ResolvedMoon();

		private ResolvedEndpoint()
		{
		}

		private ResolvedEndpoint(SkyState state)
		{
			copyTo(sunAngles, state.sunAngles);
			copyTo(moon.angles, state.moonAngles);
			copyTo(moon.illuminationDirection, state.moonSurfaceLightDirection);
			moon.illumination = state.moonIllumination;
			moon.lightIllumination = state.moonLightIllumination;
			moon.visibility = state.moonVisibility;
			moon.directionalStrength = state.moonDirectionalStrength;
		}
	}

	@Inject
	SkyManager(EnvironmentManager environmentManager)
	{
		this.environmentManager = environmentManager;
	}

	/**
	 * Loads sky_presets.json. Call before loading environments, whose skies refer to the presets.
	 */
	public void load(Gson gson) throws IOException
	{
		JsonArray rawDefinitions = GsonUtils.loadResource(gson, "sky_presets.json", JsonArray.class);

		var rawDefinitionMap = new HashMap<String, JsonObject>();
		for (int i = 0; i < rawDefinitions.size(); i++)
		{
			JsonElement element = rawDefinitions.get(i);
			if (!element.isJsonObject())
			{
				log.error("Sky preset at index {} is not an object", i);
				continue;
			}
			JsonObject definition = element.getAsJsonObject();
			JsonElement name = definition.get("name");
			if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString())
			{
				log.error("Sky preset at index {} lacks a name", i);
				continue;
			}
			if (rawDefinitionMap.putIfAbsent(name.getAsString(), definition) != null)
			{
				log.error("Ignoring duplicate sky preset '{}' at index {}", name.getAsString(), i);
			}
		}

		var resolvedDefinitions = new HashMap<String, JsonObject>();
		for (String name : rawDefinitionMap.keySet())
		{
			resolveParentDefinition(name, rawDefinitionMap, resolvedDefinitions, new HashSet<>());
		}

		var allPresets = new HashMap<String, SkyConfiguration>();
		for (var entry : resolvedDefinitions.entrySet())
		{
			String name = entry.getKey();
			if (entry.getValue() == null)
			{
				continue;
			}
			try
			{
				var preset = gson.fromJson(entry.getValue(), SkyConfiguration.class);
				preset.normalize();
				preset.definition = entry.getValue();
				allPresets.put(name, preset);
			}
			catch (RuntimeException ex)
			{
				log.error("Ignoring invalid sky preset '{}': {}", name, ex.getMessage());
			}
		}

		SkyConfiguration preset = allPresets.get(DEFAULT_PRESET_NAME);
		if (preset == null)
		{
			throw new IOException("Missing or invalid " + DEFAULT_PRESET_NAME + " sky preset");
		}

		PRESETS = Map.copyOf(allPresets);
		SkyConfiguration.DEFAULT_PRESET = preset;
	}

	private static JsonObject resolveParentDefinition(
		String name,
		Map<String, JsonObject> definitions,
		Map<String, JsonObject> resolved,
		Set<String> resolving
	)
	{
		if (resolved.containsKey(name))
		{
			return resolved.get(name);
		}
		JsonObject definition = definitions.get(name);
		if (definition == null)
		{
			return null;
		}
		if (!resolving.add(name))
		{
			log.error("sky preset '{}' contains a parent loop", name);
			resolved.put(name, null);
			return null;
		}
		JsonObject result = definition.deepCopy();
		JsonElement parentName = definition.get("parent");
		if (parentName != null && parentName.isJsonPrimitive())
		{
			JsonObject parent = resolveParentDefinition(parentName.getAsString(), definitions, resolved, resolving);
			if (parent == null)
			{
				log.error("sky preset '{}' has an unknown parent '{}'", name, parentName.getAsString());
				resolved.put(name, null);
				return null;
			}
			JsonObject base = parent.deepCopy();
			base.remove("name");
			base.remove("parent");
			deepInheritFrom(result, base);
		}
		resolved.put(name, result);
		return result;
	}

	/**
	 * 117 HD's GsonUtils.deepInheritFrom: fills in keys missing from {@code overrides} from {@code base}, merging
	 * nested objects.
	 */
	static void deepInheritFrom(JsonObject overrides, JsonObject base)
	{
		for (var entry : base.entrySet())
		{
			JsonElement value = overrides.get(entry.getKey());
			if (value == null)
			{
				overrides.add(entry.getKey(), entry.getValue());
			}
			else if (value.isJsonObject() && entry.getValue().isJsonObject())
			{
				deepInheritFrom(value.getAsJsonObject(), entry.getValue().getAsJsonObject());
			}
		}
	}

	/**
	 * Resolves the sky state of the current frame. Call after {@link EnvironmentManager#update}.
	 */
	public void update()
	{
		frameUtcMillis = System.currentTimeMillis();
		// (a failed preset load behaves like the cycle being off)
		if (SkyConfiguration.DEFAULT_PRESET == null || HdSettings.isCycleDisabled())
		{
			state.cycleActive = false;
			state.utcMillis = frameUtcMillis;
			copyTo(state.shadowAngles, environmentManager.getCurrentEnvironment().getShadowAngles());
			return;
		}
		resolvePrimarySkyState();
	}

	private void resolvePrimarySkyState()
	{
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		if (transitionId != environmentManager.getTransitionId())
		{
			transitionId = environmentManager.getTransitionId();
			interruptedFrom = null;
			if (state.cycleActive && state.transitionProgress < 1 && environmentManager.getTransitionProgress() < 1)
			{
				// Continue from the last displayed result when an unfinished transition is replaced.
				interruptedFrom = new ResolvedEndpoint(state);
			}
		}
		resolveSkyState(
			state,
			from,
			to,
			environmentManager.getTransitionProgress(),
			environmentManager.getTargetEnvironment().isOverworld,
			environmentManager.getCurrentEnvironment().getShadowAngles()
		);
	}

	private void resolveSkyState(
		SkyState out,
		Environment from,
		Environment to,
		float t,
		boolean allowsCycle,
		float[] fallbackShadowAngles
	)
	{
		SkyConfiguration fromSky = from.getSky();
		SkyConfiguration toSky = to.getSky();
		DaylightCycle configCycle = HdSettings.daylightCycle;
		boolean fixedSunAngles =
			toSky.sunAngles != null ||
				configCycle.fixedSkyPreset != null && PRESETS.containsKey(configCycle.fixedSkyPreset);
		DaylightCycle cycle = fixedSunAngles ? DaylightCycle.DEFAULT : configCycle;
		// resolveCurrentUtcMillis: one simulated day per real hour for DEFAULT and the fixed presets
		long utcMillis = frameUtcMillis * (DAY_MS / HOUR_MS);
		out.latLon[0] = DEFAULT_LATLON[0];
		out.latLon[1] = DEFAULT_LATLON[1];
		resolveEndpoint(resolvedTo, to, utcMillis, out.latLon);
		ResolvedEndpoint toEndpoint = resolvedTo;
		boolean interrupted = out == state && interruptedFrom != null && t < 1;
		ResolvedEndpoint fromEndpoint = toEndpoint;
		if (interrupted)
		{
			fromEndpoint = interruptedFrom;
		}
		else if (t < 1 && (fromSky != toSky || from.directionalStrength != to.directionalStrength))
		{
			resolveEndpoint(resolvedFrom, from, utcMillis, out.latLon);
			fromEndpoint = resolvedFrom;
		}
		out.cycleActive = !HdSettings.isCycleDisabled() && allowsCycle;
		out.transitionId = transitionId;
		out.fromEnvironment = from;
		out.toEnvironment = to;
		out.transitionProgress = t;
		out.utcMillis = utcMillis;

		// Resolve and blend celestial positions, moon lighting, and shadow direction.
		interpolateAngles(out.sunAngles, fromEndpoint.sunAngles, toEndpoint.sunAngles, t);
		out.sunAltitudeDegrees = out.sunAngles[0] * RAD_TO_DEG;
		anglesToSkyDirection(out.sunDirection, out.sunAngles[0], out.sunAngles[1]);

		ResolvedMoon fromMoon = fromEndpoint.moon;
		ResolvedMoon toMoon = toEndpoint.moon;
		interpolateAngles(out.moonAngles, fromMoon.angles, toMoon.angles, t);
		out.moonAltitudeDegrees = out.moonAngles[0] * RAD_TO_DEG;
		anglesToSkyDirection(out.moonDirection, out.moonAngles[0], out.moonAngles[1]);

		out.moonVisibility = mix(fromMoon.visibility, toMoon.visibility, t);
		out.moonDirectionalStrength = mix(fromMoon.directionalStrength, toMoon.directionalStrength, t);
		out.moonIllumination = mix(fromMoon.illumination, toMoon.illumination, t);
		out.moonLightIllumination = mix(fromMoon.lightIllumination, toMoon.lightIllumination, t);
		interpolateDirection(out.moonSurfaceLightDirection, fromMoon.illuminationDirection, toMoon.illuminationDirection, t);
		copyTo(out.shadowAngles, out.cycleActive ? out.sunAngles : fallbackShadowAngles);
	}

	private void resolveEndpoint(ResolvedEndpoint out, Environment environment, long utcMillis, float[] latLon)
	{
		SkyConfiguration sky = environment.getSky();
		float[] sunAngles = sky.sunAngles;
		if (sunAngles == null && HdSettings.daylightCycle.fixedSkyPreset != null)
		{
			var preset = PRESETS.get(HdSettings.daylightCycle.fixedSkyPreset);
			if (preset != null)
			{
				sunAngles = preset.sunAngles;
			}
		}
		boolean fixedSunAngles = sunAngles != null;
		if (fixedSunAngles)
		{
			copyTo(out.sunAngles, sunAngles);
		}
		else
		{
			AstronomyUtils.getSunAngles(out.sunAngles, utcMillis, latLon);
		}

		resolveMoon(out.moon, sky, utcMillis, out.sunAngles, fixedSunAngles, latLon);
	}

	private static void interpolateDirection(float[] out, float[] from, float[] to, float t)
	{
		float cosine = clamp(dot(from, to), -1, 1);
		if (cosine > .9999f)
		{
			mix(out, from, to, t);
			normalize(out, out);
			return;
		}
		float tx = to[0] - from[0] * cosine;
		float ty = to[1] - from[1] * cosine;
		float tz = to[2] - from[2] * cosine;
		if (tx * tx + ty * ty + tz * tz < 1e-8f)
		{
			// Opposite directions need an arbitrary, stable rotation plane.
			if (abs(from[1]) < .999f)
			{
				tx = -from[2];
				ty = 0;
				tz = from[0];
			}
			else
			{
				tx = 0;
				ty = from[2];
				tz = -from[1];
			}
		}
		float inverseLength = 1 / sqrt(tx * tx + ty * ty + tz * tz);
		float angle = acos(cosine) * t;
		float tangentScale = sin(angle) * inverseLength;
		float fromScale = cos(angle);
		out[0] = from[0] * fromScale + tx * tangentScale;
		out[1] = from[1] * fromScale + ty * tangentScale;
		out[2] = from[2] * fromScale + tz * tangentScale;
		normalize(out, out);
	}

	private static void interpolateAngles(float[] out, float[] from, float[] to, float t)
	{
		out[0] = mix(from[0], to[0], t);
		out[1] = from[1] + angleDiff(to[1], from[1]) * t;
	}

	private static void anglesToSkyDirection(float[] out, float altitude, float azimuth)
	{
		float cosAltitude = cos(altitude);
		out[0] = sin(azimuth) * cosAltitude;
		out[1] = sin(altitude);
		out[2] = cos(azimuth) * cosAltitude;
		normalize(out, out);
	}

	private void resolveMoon(
		ResolvedMoon out,
		SkyConfiguration sky,
		long millis,
		float[] sunAngles,
		boolean fixedSunAngles,
		float[] latLon
	)
	{
		MoonBehavior configMoonBehavior = HdSettings.moonBehavior;
		DaylightCycle configCycle = HdSettings.daylightCycle;
		if (sky.moonAngles != null)
		{
			copyTo(out.angles, sky.moonAngles);
		}
		else if (configMoonBehavior == MoonBehavior.STATIC)
		{
			copyTo(out.angles, DEFAULT_STATIC_MOON_ANGLES);
		}
		else if (configMoonBehavior == MoonBehavior.MIRRORED)
		{
			out.angles[0] = -sunAngles[0];
			out.angles[1] = sunAngles[1] + PI;
		}
		else
		{
			AstronomyUtils.getMoonPosition(out.angles, millis, latLon);
		}
		float[] moonDirection = new float[3];
		anglesToSkyDirection(moonDirection, out.angles[0], out.angles[1]);
		anglesToSkyDirection(out.illuminationDirection, sunAngles[0], sunAngles[1]);
		MoonPhase phase = sky.forceMoonPhase != null ? sky.forceMoonPhase : HdSettings.moonPhase;
		float illumination, orbit;
		if (configMoonBehavior == MoonBehavior.MIRRORED)
		{
			// A mirrored moon needs an independent phase, since the sun is always opposite it.
			orbit = fract(millis / (DAY_MS * SYNTHETIC_MOON_PERIOD_DAYS));
			illumination = .5f - .5f * cos(orbit * TWO_PI);
		}
		else if (!fixedSunAngles || configCycle == DaylightCycle.NIGHT)
		{
			float[] astronomy = new float[3];
			AstronomyUtils.getMoonIllumination(astronomy, millis);
			illumination = astronomy[0];
			orbit = astronomy[1];
		}
		else
		{
			// Fixed visible suns determine the phase rendered beneath them.
			illumination = saturate((1 - dot(out.illuminationDirection, moonDirection)) * .5f);
			orbit = 0;
		}
		if (phase != MoonPhase.DYNAMIC)
		{
			illumination = phase.illuminatedFraction;
		}

		if (configCycle == DaylightCycle.NIGHT || configMoonBehavior == MoonBehavior.MIRRORED)
		{
			resolveSyntheticMoonSurfaceLightDirection(out.illuminationDirection, moonDirection, illumination, orbit);
		}
		if (phase.reverseDirection)
		{
			// Preserve the radial component while moving the illuminated side across the disk.
			float radial = dot(out.illuminationDirection, moonDirection);
			for (int i = 0; i < 3; i++)
			{
				out.illuminationDirection[i] = moonDirection[i] * 2 * radial - out.illuminationDirection[i];
			}
		}

		boolean naturalMoonlightEnabled =
			!sky.hideMoon ||
				sky.moonLightVisibility >= 0 ||
				sky.hasExplicitMoonlight();
		float moonLightVisibility = sky.moonLightVisibility < 0 ? 1 : sky.moonLightVisibility;
		float lightIllumination = moonLightVisibility * max(sky.minMoonIllumination, naturalMoonlightEnabled ? illumination : 0);
		float visibility = sky.moonVisibility;
		if (sky.hideMoon || configMoonBehavior == MoonBehavior.DISABLED && sky.forceMoonPhase == null)
		{
			visibility = 0;
		}
		out.illumination = illumination;
		out.lightIllumination = lightIllumination;
		out.visibility = visibility;
		out.directionalStrength = sky.moonDirectionalStrength;
	}

	/**
	 * Keep Night and mirrored moons on a fixed diagonal phase orbit around the moon.
	 */
	private static void resolveSyntheticMoonSurfaceLightDirection(float[] out, float[] moonDirection, float illumination, float orbit)
	{
		float[] moonUp = {0, abs(moonDirection[1]) < .999f ? 1 : 0, 0};
		moonUp[2] = moonUp[1] == 0 ? 1 : 0;
		float[] moonRight = new float[3];
		cross(moonRight, moonUp, moonDirection);
		normalize(moonRight, moonRight);
		cross(moonUp, moonDirection, moonRight);
		normalize(moonUp, moonUp);
		for (int i = 0; i < 3; i++)
		{
			out[i] = moonRight[i] + moonUp[i] * NIGHT_MOON_PHASE_TILT;
		}
		normalize(out, out);
		float phaseCos = illumination * 2 - 1;
		float phaseSin = sqrt(max(0, 1 - phaseCos * phaseCos));
		if (sin(orbit * TWO_PI) < 0)
		{
			phaseSin = -phaseSin;
		}
		for (int i = 0; i < 3; i++)
		{
			out[i] = moonDirection[i] * phaseCos + out[i] * phaseSin;
		}
		normalize(out, out);
	}

	/**
	 * Resolve an authored sky independently of the current area's overrides and transition. The reusable sample
	 * contains both its complete celestial state and evaluated gradient.
	 */
	public void sampleLighting(SkyState.LightingSample out, Environment environment, float[] fogColor)
	{
		resolveSkyState(out.sky, environment, environment, 1, true, environment.getShadowAngles());
		environment.getSky().evaluateGradient(out, out.sky.sunAltitudeDegrees, fogColor);
		copyTo(out.referenceFogColorLinear, fogColor);
	}

	public float getShadowAltitude(boolean useMoon)
	{
		float altitude = (useMoon ? state.moonAngles : state.shadowAngles)[0];
		// Asymptotically approach the horizon for altitudes below 2 degrees,
		// to prevent underwater terrain from shadowing specular sun & moon reflections
		final float horizonTransition = 2 * DEG_TO_RAD;
		if (state.cycleActive && altitude < horizonTransition)
		{
			altitude = horizonTransition * exp(altitude / horizonTransition - 1);
		}
		return altitude;
	}

	/**
	 * 117 HD's directional camera orientation (updateDirectionalCamera): yaw {@code PI - azimuth}, pitch the
	 * shadow altitude, without its update throttling (which only skips sub-0.0005 rad changes for 125 ms).
	 */
	public float[] getDirectionalOrientation(boolean useMoon)
	{
		float[] angles = useMoon ? state.moonAngles : state.shadowAngles;
		return new float[]{PI - angles[1], getShadowAltitude(useMoon)};
	}
}
