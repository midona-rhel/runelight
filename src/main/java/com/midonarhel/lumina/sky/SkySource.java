/*
 * Copyright (c) 2026, Midona
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
package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.SkySettings;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.LightingSource;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Lumina's {@link LightingSource}: the sky package's light, filled into {@link Lighting} each frame.
 * <ul>
 * <li>ambient = the sky frame's ambient (the area's multiplier already in it); lightColor/lightDir = the caster's
 * colour (sun or moon, with its strength window) and direction; the sky fill as {@link SkyLight}'s SH coefficients
 * and ground bounce; {@code luminaLighting} on, so the scene shader uses Lumina's seam</li>
 * <li>fog colour = the sky frame's horizon, so the distance fog meets the sky without a band; where an area has no
 * sky (117 HD's environment is not overworld) it is the area's own fog colour, eased over the environment manager's
 * transition ({@link #visibility})</li>
 * <li>the sky background's colours, directions and disc visibilities for {@code frame.SkyRenderer}, in the
 * {@link Lighting} fields of 117 HD's sky uniforms of the same meaning; {@code skyEnabled} stays off so 117 HD's
 * sky.glsl path in the scene shader is inert</li>
 * <li>fog depth, ground fog, underglow and the vanilla skybox rule as 117 HD's source decides them</li>
 * </ul>
 * The areas' look comes from 117 HD's current environment ({@link AreaLook#derive}); the time from
 * {@link GameClock} with the Sky settings.
 */
@Singleton
public class SkySource implements LightingSource
{
	// the moon's disc (the old sky.glsl's, sRGB (0.85, 0.88, 1.0), here linear)
	private static final float[] MOON_DISC = {0.69f, 0.75f, 1f};

	private final EnvironmentManager environmentManager;

	private final GameClock clock = new GameClock();
	private final Celestial celestial = new Celestial();
	private final SkyModel model = new SkyModel();
	private final SkyFrame frame = new SkyFrame();
	private final SkyLight skyLight = new SkyLight();
	private final AreaLook look = new AreaLook();
	private final SkyModifiers modifiers = SkyModifiers.NEUTRAL;

	private final float[] envFog = new float[3];
	private final float[] overworldFog = new float[3];

	// how much of the sky shows (1) against the area's own fog colour (0): whether the environment is overworld,
	// blended between the environment manager's from and to environments by its transition (the copies carry the flag)
	private float visibility = 1;

	private double hour;

	@Inject
	SkySource(EnvironmentManager environmentManager)
	{
		this.environmentManager = environmentManager;
	}

	/** The sun, moon and caster of the last frame; the shadow module reads the caster from here. */
	public Celestial celestial()
	{
		return celestial;
	}

	public SkyFrame frame()
	{
		return frame;
	}

	/** The game clock's hour of day of the last frame. */
	public double hour()
	{
		return hour;
	}

	@Override
	public void fill(Lighting out, SceneData scene, double elapsedTime, int drawDistance, boolean hasSkyboxModel)
	{
		if (scene != null)
		{
			environmentManager.update(scene, elapsedTime);
		}

		hour = clock.hour(System.currentTimeMillis(), SkySettings.dayLength.minutes, SkySettings.fixedHour);
		celestial.update(hour);

		Environment env = environmentManager.getCurrentEnvironment();
		Environment overworld = environmentManager.getOverworldEnvironment();
		copy(envFog, env.getFogColor());
		copy(overworldFog, environmentManager.getFogColor(overworld));
		look.derive(env, envFog, overworld, overworldFog);

		model.evaluate(celestial.sunAltitude, look, modifiers, frame);
		float[] caster = celestial.casterKind == Celestial.Caster.SUN ? frame.sunColour : frame.moonColour;
		skyLight.update(frame, celestial.sunDir, celestial.sunAltitude, celestial.sunAzimuth, frame.ambient, caster,
			celestial.casterDir);

		float visibilityFrom = environmentManager.getFromEnvironment().isOverworld ? 1 : 0;
		float visibilityTo = environmentManager.getToEnvironment().isOverworld ? 1 : 0;
		visibility = visibilityFrom + (visibilityTo - visibilityFrom) * environmentManager.getTransitionProgress();

		// the scene light
		out.luminaLighting = true;
		copy(out.ambientColor, frame.ambient);
		out.ambientStrength = 1;
		copy(out.lightColor, caster);
		out.lightStrength = 1;
		copy(out.lightDir, celestial.casterDir);
		System.arraycopy(skyLight.c0, 0, out.skyFillSh, 0, 3);
		System.arraycopy(skyLight.c1, 0, out.skyFillSh, 3, 9);
		copy(out.skyFillBounce, skyLight.bounce);
		for (int i = 0; i < 3; i++)
		{
			out.fogColor[i] = envFog[i] + (frame.horizon[i] - envFog[i]) * visibility;
		}
		copy(out.underglowColor, env.getUnderglowColor());
		out.underglowStrength = env.underglowStrength;
		out.lightningBrightness = 0;

		// fog depth, ground fog and the vanilla skybox: as 117 HD's source
		boolean replaceVanillaSkybox = HdSettings.replaceVanillaSkyboxes && environmentManager.getTargetEnvironment().hideVanillaSkyboxes;
		out.vanillaSkybox = hasSkyboxModel && !replaceVanillaSkybox;
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
			fogDepth *= Math.min(drawDistance, 90) / 10.f;
		}
		out.useFog = fogDepth > 0;
		out.fogDepth = fogDepth;
		out.groundFogStart = env.groundFogStart;
		out.groundFogEnd = env.groundFogEnd;
		out.groundFogOpacity = HdSettings.groundFog ? env.groundFogOpacity : 0;

		// the sky background (frame.SkyRenderer); 117 HD's sky path in the scene shader stays off
		out.skyEnabled = false;
		copy(out.zenithColor, frame.zenith);
		copy(out.horizonColor, frame.horizon);
		copy(out.sunColor, frame.glow);
		skyDirection(out.skySunDir, celestial.sunDir);
		skyDirection(out.moonDir, celestial.moonDir);
		copy(out.moonDiskColor, MOON_DISC);
		out.skyVisibility = visibility;
		out.sunVisibility = SkySettings.showSunMoon ? frame.sunVisibility : 0;
		out.moonVisibility = SkySettings.showSunMoon ? frame.nightVisibility : 0;
		// the area's own fog colour, for where the sky does not show
		copy(out.skyFogColor, envFog);
		// 117 HD's remaining sky uniforms, unused here
		out.moonIllumination = 0;
		out.customGradient = 0;
		out.horizonWidth = 0;
		out.skyFogDensity = 0;
		out.skyFogHorizonHeight = 0;
		Arrays.fill(out.groundFogLight, 0);
		out.starHorizonHeight = 0;
	}

	/** 117 HD's sky direction convention (y up) from a game-space direction (y down). */
	private static void skyDirection(float[] out, float[] gameDir)
	{
		out[0] = gameDir[0];
		out[1] = -gameDir[1];
		out[2] = gameDir[2];
	}

	private static void copy(float[] out, float[] v)
	{
		System.arraycopy(v, 0, out, 0, 3);
	}

	/** Developer dump: the clock, the sun and the caster, the area's multipliers and the sky's visibility. */
	public String describe()
	{
		float[] up = {0, -1, 0};
		return String.format("hour=%.3f sunAltitude=%.2f sunAzimuth=%.1fdeg caster=%s strength=%.3f elevation=%.1f epoch=%d"
				+ " look.ambient=%s look.sun=%s look.skyTint=%s skyVisibility=%.3f skyFill(up)=%s",
			hour, celestial.sunAltitude, Math.toDegrees(celestial.sunAzimuth) % 360, celestial.casterKind, celestial.casterStrength,
			celestial.casterElevation(), celestial.casterEpoch, Arrays.toString(look.ambient), Arrays.toString(look.sun),
			Arrays.toString(look.skyTint), visibility, Arrays.toString(skyLight.evaluate(up, new float[3])));
	}
}
