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
package com.midonarhel.lumina.scene.environment;

import com.google.gson.annotations.JsonAdapter;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.scene.areas.AreaManager;
import com.midonarhel.lumina.scene.sky.SkyConfiguration;
import com.midonarhel.lumina.util.ColorUtils;
import com.midonarhel.lumina.util.ExpressionParser;
import com.midonarhel.lumina.util.ExpressionPredicate;
import com.midonarhel.lumina.util.GsonUtils;
import com.midonarhel.lumina.util.MathUtils;
import javax.annotation.Nullable;
import lombok.Getter;
import static com.midonarhel.lumina.util.ColorUtils.rgb;

/**
 * One entry of environments.json: 117 HD's Environment (rs117.hd.scene.environments.Environment) without the keys
 * of features Lumina does not port (water caustics, wind, roof shadows).
 */
public class Environment
{
	private static final float[] DEFAULT_SHADOW_ANGLES = {(float) Math.toRadians(52), (float) Math.toRadians(235)};
	private static final float[] DEFAULT_FOG_COLOR = rgb("#000000");
	private static final float[] DEFAULT_WATER_COLOR = rgb("#66eaff");

	public static final Environment DEFAULT;
	public static final Environment NONE;

	static
	{
		DEFAULT = new Environment();
		DEFAULT.key = "DEFAULT";
		DEFAULT.area = Area.ALL;
		DEFAULT.ambientColor = rgb("#ffffff");
		DEFAULT.ambientStrength = 1;
		DEFAULT.fogColor = DEFAULT_FOG_COLOR;
		DEFAULT.waterColor = DEFAULT_WATER_COLOR;
		DEFAULT.shadowAngles = DEFAULT_SHADOW_ANGLES;
		DEFAULT.normalize();

		NONE = new Environment();
		NONE.key = "NONE";
		NONE.fogColor = rgb("#ff00ff");
		NONE.normalize();
	}

	public static Environment OVERWORLD = DEFAULT, AUTUMN = DEFAULT, WINTER = DEFAULT;

	public String key;
	@JsonAdapter(AreaManager.Adapter.class)
	public Area area = Area.NONE;
	public boolean isOverworld = false;
	public boolean isPohTheme = false;
	public boolean isLegacyTob = false;
	public boolean isUnderwater = false;
	public boolean force = false;
	public boolean allowSkyOverride = true;
	public boolean lightningEffects = false;
	public boolean instantTransition = false;
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varbitCondition = ExpressionPredicate.TRUE;
	@JsonAdapter(ExpressionParser.PredicateAdapter.class)
	public ExpressionPredicate varpCondition = ExpressionPredicate.TRUE;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	@Getter
	private float[] ambientColor;
	public float ambientStrength = -1;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	@Getter
	private float[] directionalColor = rgb("#ffffff");
	public float directionalStrength = .25f;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	@Getter
	private float[] waterColor;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	@Getter
	private float[] underglowColor = rgb("#000000");
	public float underglowStrength = 0;
	/**
	 * Sun altitude and azimuth (horizontal coordinate system), in radians.
	 */
	@JsonAdapter(GsonUtils.DegreesToRadians.class)
	@Getter
	private float[] shadowAngles;
	@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
	@Getter
	private float[] fogColor;
	public float fogDepth = 25;
	public float groundFogStart = -200;
	public float groundFogEnd = -500;
	public float groundFogOpacity = 0;
	@JsonAdapter(SkyConfiguration.Adapter.class)
	@Nullable
	private SkyConfiguration sky;
	public boolean hideVanillaSkyboxes = false;
	public float nightExposure = 1;

	public transient boolean hasWaterColorOverride;
	public transient boolean hasFogColorOverride;
	private transient boolean normalized;

	public Environment normalize()
	{
		if (area == null)
		{
			area = Area.NONE;
		}
		if (varbitCondition == null)
		{
			varbitCondition = ExpressionPredicate.TRUE;
		}
		if (varpCondition == null)
		{
			varpCondition = ExpressionPredicate.TRUE;
		}
		if (directionalColor == null)
		{
			directionalColor = rgb("#ffffff");
		}
		directionalColor = ensureLength(directionalColor, 3);
		if (underglowColor == null)
		{
			underglowColor = rgb("#000000");
		}

		if (!normalized)
		{
			hasFogColorOverride = fogColor != null;
			hasWaterColorOverride = waterColor != null;
		}
		if (fogColor == null)
		{
			fogColor = DEFAULT_FOG_COLOR;
		}
		if (waterColor == null)
		{
			waterColor = DEFAULT_WATER_COLOR;
		}

		if (area != Area.ALL && area != Area.NONE)
		{
			isOverworld = Area.OVERWORLD.intersects(area);
			if (!isOverworld)
			{
				if (!hasFogColorOverride)
				{
					fogColor = DEFAULT.fogColor;
					hasFogColorOverride = true;
				}
				if (!hasWaterColorOverride)
				{
					waterColor = DEFAULT.waterColor;
					hasWaterColorOverride = true;
				}
			}
		}

		if (ambientColor == null)
		{
			if (isOverworld)
			{
				ambientColor = new float[3];
				ColorUtils.deriveAmbientLight(ambientColor, directionalColor);
			}
			else
			{
				ambientColor = DEFAULT.ambientColor;
			}
		}
		ambientColor = ensureLength(ambientColor, 3);
		if (ambientStrength < 0)
		{
			ambientStrength = isOverworld ? directionalStrength : DEFAULT.ambientStrength;
		}

		shadowAngles = shadowAngles == null ? DEFAULT_SHADOW_ANGLES : ensureLength(shadowAngles, 2);

		fogDepth = Math.max(0, fogDepth);

		if (sky != null)
		{
			sky.normalize();
		}

		normalized = true;
		return this;
	}

	public SkyConfiguration getSky()
	{
		return sky != null ? sky : SkyConfiguration.DEFAULT_PRESET;
	}

	/**
	 * A mutable copy, used as the start, end and current state of a transition.
	 */
	public Environment copy()
	{
		var env = new Environment();
		env.fogColor = new float[3];
		env.waterColor = new float[3];
		env.ambientColor = new float[3];
		env.directionalColor = new float[3];
		env.underglowColor = new float[3];
		env.shadowAngles = new float[3];
		copyTo(env);
		return env;
	}

	public void copyTo(Environment target)
	{
		target.interpolate(this, this, 0);
	}

	public Environment interpolate(Environment from, Environment to, float t)
	{
		mix(fogColor, from.fogColor, to.fogColor, t);
		mix(waterColor, from.waterColor, to.waterColor, t);
		mix(ambientColor, from.ambientColor, to.ambientColor, t);
		mix(directionalColor, from.directionalColor, to.directionalColor, t);
		mix(underglowColor, from.underglowColor, to.underglowColor, t);
		mix(shadowAngles, from.shadowAngles, to.shadowAngles, t);
		fogDepth = mix(from.fogDepth, to.fogDepth, t);
		nightExposure = mix(from.nightExposure, to.nightExposure, t);
		ambientStrength = mix(from.ambientStrength, to.ambientStrength, t);
		directionalStrength = mix(from.directionalStrength, to.directionalStrength, t);
		underglowStrength = mix(from.underglowStrength, to.underglowStrength, t);
		groundFogStart = mix(from.groundFogStart, to.groundFogStart, t);
		groundFogEnd = mix(from.groundFogEnd, to.groundFogEnd, t);
		groundFogOpacity = mix(from.groundFogOpacity, to.groundFogOpacity, t);
		sky = t == 1 ? to.sky : from.sky;
		return this;
	}

	private static float mix(float a, float b, float t)
	{
		return MathUtils.mix(a, b, t);
	}

	private static void mix(float[] out, float[] a, float[] b, float t)
	{
		MathUtils.mix(out, a, b, t);
	}

	private static float[] ensureLength(float[] array, int length)
	{
		if (array.length == length)
		{
			return array;
		}
		float[] result = new float[length];
		System.arraycopy(array, 0, result, 0, Math.min(length, array.length));
		return result;
	}

	@Override
	public String toString()
	{
		return key != null ? key : area.name;
	}
}
