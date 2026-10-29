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

import com.google.gson.Gson;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.SkySettings;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.LightingSource;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Lumina's {@link LightingSource}: the sky package's light, filled into {@link Lighting} each frame.
 * <ul>
 * <li>ambient = the sky frame's ambient (the area's multiplier already in it); lightColor/lightDir = the caster's
 * colour (sun or moon, with its strength window) and direction; the sky fill as {@link SkyLight}'s SH coefficients
 * and ground bounce; {@code luminaLighting} on, so the scene shader uses Lumina's seam</li>
 * <li>fog colour = the sky frame's fog: the horizon after the area's tint and haze, so the distance fog meets the
 * sky without a band (the scene shader adds the sun's glow in the view direction, {@code fogSkyGlow}); underground
 * the area's own fog colour, which is also the background there ({@code skyVisibility} 0). None of 117 HD's fog
 * data: the depth is {@link SkyModel#CLEAR_DAY_FOG_DEPTH} (or the user's fixed depth setting)</li>
 * <li>the sky background's colours, directions and disc visibilities for {@code frame.SkyRenderer}, in the
 * {@link Lighting} fields of 117 HD's sky uniforms of the same meaning; {@code skyEnabled} stays off so 117 HD's
 * sky.glsl path in the scene shader is inert</li>
 * <li>ground fog, underglow and the vanilla skybox rule as 117 HD's source decides them</li>
 * </ul>
 * The area's look is {@code area_looks.json}'s entry for the environment manager's target environment
 * ({@link AreaLook.Table}; an environment without one gets {@link AreaLook#derive}, logged once), eased between the
 * previous and the new look with the manager's transition. The time comes from {@link GameClock} with the Sky
 * settings.
 */
@Slf4j
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
	private final SkyModifiers modifiers = SkyModifiers.NEUTRAL;

	private final AreaLook.Table looks;
	// the look in use: lookFrom (the look at the moment the target changed) eased to lookTo (the target's)
	private final AreaLook look = new AreaLook();
	private final AreaLook lookFrom = new AreaLook();
	private final AreaLook lookTo = new AreaLook();
	private final AreaLook derived = new AreaLook();
	private String lookName;
	private boolean lookDerived;
	private final Set<String> missingLogged = new HashSet<>();

	private double hour;

	@Inject
	SkySource(EnvironmentManager environmentManager, Gson gson)
	{
		this.environmentManager = environmentManager;
		AreaLook.Table table;
		try
		{
			table = AreaLook.Table.load(gson);
			log.debug("Loaded {} area looks", table.size());
		}
		catch (IOException ex)
		{
			log.error("Lumina: area_looks.json failed to load, every area gets the look derived from 117 HD's environment", ex);
			table = new AreaLook.Table();
		}
		looks = table;
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

	/** The area look in use this frame (blended). */
	public AreaLook look()
	{
		return look;
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
		updateLook();

		model.evaluate(celestial.sunAltitude, look, modifiers, frame);
		float[] caster = celestial.casterKind == Celestial.Caster.SUN ? frame.sunColour : frame.moonColour;
		skyLight.update(frame, celestial.sunDir, celestial.sunAltitude, celestial.sunAzimuth, frame.ambient, caster,
			celestial.casterDir);
		float visibility = 1 - look.underground;

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
		copy(out.fogColor, frame.fog);
		out.fogSkyGlow = visibility;
		copy(out.underglowColor, env.getUnderglowColor());
		out.underglowStrength = env.underglowStrength;
		out.lightningBrightness = 0;

		// fog depth (Lumina's one depth, or the user's fixed one), ground fog and the vanilla skybox as 117 HD's source
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
					fogDepth = frame.fogDepth;
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
		// the area's own fog colour, the background where the sky does not show
		copy(out.skyFogColor, frame.fog);
		// 117 HD's remaining sky uniforms, unused here
		out.moonIllumination = 0;
		out.customGradient = 0;
		out.horizonWidth = 0;
		out.skyFogDensity = 0;
		out.skyFogHorizonHeight = 0;
		Arrays.fill(out.groundFogLight, 0);
		out.starHorizonHeight = 0;
	}

	/**
	 * The look of the environment manager's target environment (the seasonal overworld for OVERWORLD), eased from
	 * the look in use when the target changed with the manager's transition progress (which it resets on the change
	 * and completes at once for an instant transition).
	 */
	private void updateLook()
	{
		Environment target = environmentManager.getTargetEnvironment();
		if (target == Environment.OVERWORLD)
		{
			target = environmentManager.getOverworldEnvironment();
		}
		String name = target == Environment.NONE ? "OVERWORLD" : target.toString();
		if (!name.equals(lookName))
		{
			lookName = name;
			lookFrom.set(look);
			AreaLook found = looks.get(name);
			lookDerived = found == null;
			if (lookDerived)
			{
				if (target == Environment.NONE)
				{
					found = AreaLook.OVERWORLD;
				}
				else
				{
					derived.derive(target, environmentManager.getOverworldEnvironment());
					found = derived;
					if (missingLogged.add(name))
					{
						log.warn("area_looks.json has no entry for environment {}: its look is derived from 117 HD's environment", name);
					}
				}
			}
			lookTo.set(found);
		}
		look.mix(lookFrom, lookTo, environmentManager.getTransitionProgress());
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

	/** Developer dump: the clock, the sun and the caster, the area look in use and the fog. */
	public String describe()
	{
		float[] up = {0, -1, 0};
		return String.format("hour=%.3f sunAltitude=%.2f sunAzimuth=%.1fdeg caster=%s strength=%.3f elevation=%.1f epoch=%d"
				+ " area=%s%s look.ambient=%s look.sun=%s look.skyTint=%s look.fogColor=%s fogWeight=%.2f haze=%.2f"
				+ " underground=%.2f fog(linear)=%s fogDepth=%.1f skyFill(up)=%s",
			hour, celestial.sunAltitude, Math.toDegrees(celestial.sunAzimuth) % 360, celestial.casterKind, celestial.casterStrength,
			celestial.casterElevation(), celestial.casterEpoch, lookName, lookDerived ? " (derived, no entry)" : "",
			Arrays.toString(look.ambient), Arrays.toString(look.sun), Arrays.toString(look.skyTint), Arrays.toString(look.fogColor),
			look.fogWeight, look.haze, look.underground, Arrays.toString(frame.fog), frame.fogDepth,
			Arrays.toString(skyLight.evaluate(up, new float[3])));
	}
}
