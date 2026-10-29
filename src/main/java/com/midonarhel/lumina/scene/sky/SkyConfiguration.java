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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.midonarhel.lumina.util.ColorUtils;
import com.midonarhel.lumina.util.ColorUtils.SrgbToLinearAdapter;
import com.midonarhel.lumina.util.GsonUtils;
import com.midonarhel.lumina.util.GsonUtils.DegreesToRadians;
import java.io.IOException;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import static com.midonarhel.lumina.util.MathUtils.*;

/**
 * A sky preset (sky_presets.json) or an environment's sky: 117 HD's SkyConfiguration
 * (rs117.hd.scene.daylight_cycle.SkyConfiguration), ported literally. Parent inheritance is done on the raw JSON
 * (117 HD re-serializes the normalized parent; the values are the same, since the only normalization defaults it
 * would add are set explicitly in the presets that are inherited from).
 */
@SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal"})
public class SkyConfiguration
{
	public static SkyConfiguration DEFAULT_PRESET;

	@Nullable
	public String name;
	@Nullable
	public String parent;
	public SkyProfile profile;
	public boolean customGradient;
	public float horizonWidth = 15;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] moonAngles;
	public boolean hideMoon;
	@Nullable
	public MoonPhase forceMoonPhase;
	public float moonDirectionalStrength = -1;
	public float moonAmbientStrength = -1;
	public float minMoonIllumination;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonDiskColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonDirectionalColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonAmbientColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] nightAmbientColor;
	public float nightAmbientStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] nightHorizonColor;
	public float nightHorizonStrength = 1;
	public float moonDiskStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Nullable
	public float[] skyFogColor;
	public float skyFogDensity = -1;
	public float skyFogHorizonAltitude;
	public float skyVisibility = 1;
	/**
	 * Negative means automatic; an explicit value can retain moonlight with hideMoon.
	 */
	public float moonLightVisibility = -1;
	public float moonVisibility = 1;
	public float starVisibility = 1;
	public float nebulaVisibility = 1;
	public float auroraVisibility = 1;
	public float moonSizeMult = 1;
	public float starHorizonHeight = 1;
	private float sunStrength = 1;
	private float sunriseSunsetStrength = 1;
	private float skyColorTakeoverAngle = 40;

	private transient boolean derivedMoonDirectionalStrength;
	private transient boolean derivedMoonAmbientStrength;
	/**
	 * The resolved JSON definition this configuration was read from, which children inherit.
	 */
	transient JsonObject definition;

	public void normalize()
	{
		if (nightHorizonColor == null)
		{
			throw new IllegalStateException("Missing night sky color");
		}
		nightHorizonColor = ensureArrayLength(nightHorizonColor, 3);
		nightHorizonStrength = max(0, nightHorizonStrength);

		if (nightAmbientColor == null)
		{
			nightAmbientColor = new float[3];
		}
		nightAmbientColor = ensureArrayLength(nightAmbientColor, 3);
		nightAmbientStrength = max(0, nightAmbientStrength);

		if (moonDiskColor == null)
		{
			moonDiskColor = ColorUtils.colorTemperatureToLinearRgb(8000);
		}
		moonDiskColor = ensureArrayLength(moonDiskColor, 3);

		boolean deriveDirectional = moonDirectionalColor == null;
		if (deriveDirectional)
		{
			moonDirectionalColor = copy(moonDiskColor);
		}
		else
		{
			moonDirectionalColor = ensureArrayLength(moonDirectionalColor, 3);
		}
		if (moonDirectionalStrength < 0)
		{
			derivedMoonDirectionalStrength = true;
			moonDirectionalStrength = 0.0008f * moonDiskStrength * pow2(moonSizeMult);
		}

		boolean deriveAmbient = moonAmbientColor == null;
		if (deriveAmbient)
		{
			moonAmbientColor = copy(moonDirectionalColor);
			if (!deriveDirectional)
			{
				// We assume that authored directional colors already include the mesopic shift.
				// Undo this before deriving ambient lighting from it
				ColorUtils.invertMesopicShift(moonAmbientColor);
			}
			ColorUtils.deriveAmbientLight(moonAmbientColor, moonAmbientColor);
		}
		else
		{
			moonAmbientColor = ensureArrayLength(moonAmbientColor, 3);
		}
		if (moonAmbientStrength < 0)
		{
			derivedMoonAmbientStrength = true;
			moonAmbientStrength = moonDirectionalStrength;
		}

		// Apply mesopic shifts to derived night colors, since our renderer does not account for this in later stages
		if (deriveDirectional)
		{
			ColorUtils.applyMesopicShift(moonDirectionalColor);
		}
		if (deriveAmbient)
		{
			ColorUtils.applyMesopicShift(moonAmbientColor);
		}

		if (sunAngles != null)
		{
			sunAngles = ensureArrayLength(sunAngles, 2);
		}
		if (moonAngles != null)
		{
			moonAngles = ensureArrayLength(moonAngles, 2);
		}
		if (skyFogColor != null)
		{
			skyFogColor = ensureArrayLength(skyFogColor, 3);
		}
		if (profile == null)
		{
			throw new IllegalStateException("Invalid sky profile");
		}
		profile.normalize();
	}

	public boolean hasExplicitMoonlight()
	{
		return !derivedMoonDirectionalStrength || !derivedMoonAmbientStrength;
	}

	@SuppressWarnings("unused")
	public static class SkyProfile
	{
		private Keyframe[] zenith;
		private Keyframe[] horizon;
		private Keyframe[] sunGlow;

		private static class Keyframe
		{
			private float altitude;
			@JsonAdapter(SrgbToLinearAdapter.class)
			private float[] color;
		}

		public void normalize()
		{
			normalizeKeyframes(zenith);
			normalizeKeyframes(horizon);
			normalizeKeyframes(sunGlow);
		}

		private static void normalizeKeyframes(@Nullable Keyframe[] keyframes)
		{
			if (keyframes == null || keyframes.length == 0)
			{
				throw new IllegalStateException("Missing sky keyframes");
			}
			float previousAltitude = Float.NEGATIVE_INFINITY;
			for (Keyframe keyframe : keyframes)
			{
				if (keyframe == null || keyframe.altitude <= previousAltitude)
				{
					throw new IllegalStateException("Sky keyframes must be ordered by altitude");
				}
				if (keyframe.color == null)
				{
					throw new IllegalStateException("Expected a sky color keyframe");
				}
				keyframe.color = ensureArrayLength(keyframe.color, 3);
				previousAltitude = keyframe.altitude;
			}
		}

		private static void interpolate(float[] out, float altitude, Keyframe[] keyframes)
		{
			int end = keyframes.length - 1;
			int i = 0;
			while (i < end && altitude > keyframes[i + 1].altitude)
			{
				i++;
			}
			Keyframe from = keyframes[i];
			if (i == end)
			{
				copyTo(out, from.color);
				return;
			}
			Keyframe to = keyframes[i + 1];
			mix(out, from.color, to.color, saturate((altitude - from.altitude) / (to.altitude - from.altitude)));
		}
	}

	/**
	 * Interpolate shader parameters. Lighting, disk color, gradients, and celestial state are evaluated separately
	 * before blending.
	 */
	public SkyConfiguration interpolateLightingParameters(SkyConfiguration from, SkyConfiguration to, float t)
	{
		horizonWidth = mix(from.horizonWidth, to.horizonWidth, t);
		// Sky fog defaults are resolved against the environment before interpolation by the renderer.
		starVisibility = mix(from.starVisibility, to.starVisibility, t);
		nebulaVisibility = mix(from.nebulaVisibility, to.nebulaVisibility, t);
		auroraVisibility = mix(from.auroraVisibility, to.auroraVisibility, t);
		moonSizeMult = mix(from.moonSizeMult, to.moonSizeMult, t);
		starHorizonHeight = mix(from.starHorizonHeight, to.starHorizonHeight, t);
		return this;
	}

	public void evaluateGradient(SkyState.GradientSample out, float sunAltitudeDegrees, float[] fogColor)
	{
		float takeover = max(0, skyColorTakeoverAngle);
		SkyProfile.interpolate(out.zenith, sunAltitudeDegrees, profile.zenith);
		SkyProfile.interpolate(out.horizon, sunAltitudeDegrees, profile.horizon);
		SkyProfile.interpolate(out.sunGlow, sunAltitudeDegrees, profile.sunGlow);
		// Authored gradients bypass the automatic fog takeover and night-color replacement.
		if (customGradient)
		{
			return;
		}
		float[] nightColor = multiply(nightHorizonColor, nightHorizonStrength);
		if (fogColor != null && sunStrength < 1)
		{
			float window = smoothstep(-25, 0, sunAltitudeDegrees);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0)
			{
				float nightBlend = smoothstep(5, -5, sunAltitudeDegrees);
				for (int i = 0; i < 3; i++)
				{
					float target = mix(fogColor[i], nightColor[i], nightBlend);
					out.zenith[i] = mix(out.zenith[i], target, suppression);
					out.horizon[i] = mix(out.horizon[i], target, suppression);
				}
				multiply(out.sunGlow, out.sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null && sunriseSunsetStrength < 1)
		{
			float window = sunAltitudeDegrees < 0 ?
				smoothstep(-15, 0, sunAltitudeDegrees) :
				takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitudeDegrees);
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0)
			{
				blendSky(out.zenith, out.horizon, fogColor, suppression);
				multiply(out.sunGlow, out.sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null)
		{
			float blend = sunAltitudeDegrees < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitudeDegrees);
			if (blend > 0)
			{
				blendSky(out.zenith, out.horizon, fogColor, blend);
			}
		}
		// Preserve the twilight gradient through civil dusk, then fade to the night tint.
		float nightBlend = smoothstep(-6, -18, sunAltitudeDegrees);
		if (nightBlend > 0)
		{
			blendSky(out.zenith, out.horizon, nightColor, nightBlend);
		}
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t)
	{
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
	}

	/**
	 * Reads an environment's sky: a preset name, or an object of overrides on top of its parent preset (by default
	 * the default preset), as 117 HD's SkyConfiguration.Adapter does.
	 */
	@Slf4j
	public static class Adapter implements TypeAdapterFactory
	{
		@Override
		@SuppressWarnings("unchecked")
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken)
		{
			if (typeToken.getRawType() != SkyConfiguration.class)
			{
				return null;
			}

			TypeAdapter<SkyConfiguration> delegate = gson.getDelegateAdapter(this, TypeToken.get(SkyConfiguration.class));
			return (TypeAdapter<T>) new TypeAdapter<SkyConfiguration>()
			{
				@Nullable
				private SkyConfiguration resolveParent(String name, String location)
				{
					SkyConfiguration parent = SkyManager.PRESETS.get(name);
					if (parent == null)
					{
						log.error("Unknown sky parent '{}' at {}; ignoring sky", name, location);
					}
					return parent;
				}

				@Override
				public SkyConfiguration read(JsonReader in) throws IOException
				{
					JsonToken token = in.peek();
					if (token == JsonToken.NULL)
					{
						in.nextNull();
						return null;
					}

					String location = GsonUtils.location(in);
					if (token == JsonToken.STRING)
					{
						return resolveParent(in.nextString(), location);
					}

					if (token != JsonToken.BEGIN_OBJECT)
					{
						log.error("Expected a sky preset or object at {}; ignoring value", location);
						in.skipValue();
						return null;
					}

					JsonObject override = new JsonParser().parse(in).getAsJsonObject();

					JsonElement parentElement = override.get("parent");
					SkyConfiguration parent = DEFAULT_PRESET;
					if (parentElement != null)
					{
						if (!parentElement.isJsonPrimitive() || !parentElement.getAsJsonPrimitive().isString())
						{
							log.error("Sky parent must be a string at {}; ignoring sky", location);
							return null;
						}
						parent = resolveParent(parentElement.getAsString(), location);
						if (parent == null)
						{
							return null;
						}
						if (override.size() == 1)
						{
							return parent;
						}
					}

					if (parent == null || parent.definition == null)
					{
						log.error("No default sky preset at {}; ignoring sky", location);
						return null;
					}
					var parentJson = parent.definition.deepCopy();
					parentJson.remove("name");
					parentJson.remove("parent");
					SkyManager.deepInheritFrom(override, parentJson);
					try
					{
						return delegate.fromJsonTree(override);
					}
					catch (RuntimeException ex)
					{
						log.error("Invalid sky configuration at {}; ignoring sky: {}", location, ex.getMessage());
						return null;
					}
				}

				@Override
				public void write(JsonWriter out, SkyConfiguration sky)
				{
					throw new UnsupportedOperationException();
				}
			};
		}
	}
}
