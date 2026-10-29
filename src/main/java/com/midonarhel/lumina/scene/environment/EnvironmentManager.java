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

import com.google.gson.Gson;
import com.midonarhel.lumina.config.DefaultSkyColor;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.util.ExpressionParser;
import com.midonarhel.lumina.util.ExpressionPredicate;
import com.midonarhel.lumina.util.Gamevals;
import com.midonarhel.lumina.util.GsonUtils;
import com.midonarhel.lumina.util.VariableSupplier;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import static com.midonarhel.lumina.util.MathUtils.*;

/**
 * 117 HD's EnvironmentManager (rs117.hd.scene.EnvironmentManager): picks the environment of the area the camera
 * focuses on, transitions between environments (3 s smoothstep) and resolves the fog and water colours.
 * <p>
 * Settings are read from {@link HdSettings}. Not ported (117 HD defaults assumed): decoupleSkyAndWaterColor off
 * and legacyLightning off (so lightning is always 0).
 */
@Slf4j
@Singleton
public class EnvironmentManager
{
	private static final float TRANSITION_DURATION = 3; // seconds
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private static final int SKIP_TRANSITION_DISTANCE = 41;

	private final Client client;

	private final Map<String, Integer> varbitConditionVars = new HashMap<>();
	private final Map<String, Integer> varpConditionVars = new HashMap<>();
	private final VariableSupplier varbitVariableSupplier;
	private final VariableSupplier varpVariableSupplier;

	private Environment[] environments = {};

	private boolean transitionComplete = true;
	@Getter
	private float transitionProgress = 1;
	private double transitionStartTime;
	@Getter
	private long transitionId;
	private final int[] previousPosition = new int[3];

	// Mutable result consumed by the renderer
	private final Environment current = Environment.DEFAULT.copy();
	// Snapshots at either end of the active transition
	private final Environment from = Environment.DEFAULT.copy();
	private final Environment to = Environment.DEFAULT.copy();
	// Area-selected definition, before lighting fallbacks are applied to {@code to}
	private Environment target = Environment.NONE;

	@Getter
	private float lightningBrightness = 0f;

	@Inject
	EnvironmentManager(Client client)
	{
		this.client = client;
		varbitVariableSupplier = name ->
		{
			Integer id = varbitConditionVars.get(name);
			return id == null ? null : client.getVarbitValue(id);
		};
		varpVariableSupplier = name ->
		{
			Integer id = varpConditionVars.get(name);
			return id == null ? null : client.getVarpValue(id);
		};
	}

	public void load(Gson gson) throws IOException
	{
		Environment[] loaded = GsonUtils.loadResource(gson, "environments.json", Environment[].class);

		int count = 0;
		for (int i = 0; i < loaded.length; i++)
		{
			Environment env = loaded[i];
			try
			{
				if (env == null)
				{
					throw new IllegalStateException("Expected an environment object");
				}
				if (!HdSettings.legacyTobEnvironment && env.isLegacyTob ||
					!HdSettings.pohThemeEnvironments && env.isPohTheme)
				{
					continue;
				}
				env.normalize();
				loaded[count++] = env;
			}
			catch (RuntimeException ex)
			{
				log.error("Ignoring invalid environment '{}' at index {}: {}", env, i, ex.getMessage());
			}
		}
		loaded = Arrays.copyOf(loaded, count);
		environments = bindConditionVars(loaded);

		Environment.OVERWORLD = Environment.AUTUMN = Environment.WINTER = Environment.DEFAULT;
		for (var env : environments)
		{
			if (env.key == null)
			{
				continue;
			}
			switch (env.key)
			{
				case "OVERWORLD":
					Environment.OVERWORLD = env;
					break;
				case "AUTUMN":
					Environment.AUTUMN = env;
					break;
				case "WINTER":
					Environment.WINTER = env;
					break;
			}
		}

		reset();
		log.debug("Loaded {} environments", environments.length);
	}

	public void reset()
	{
		target = Environment.NONE;
	}

	private Environment[] bindConditionVars(Environment[] loaded)
	{
		varbitConditionVars.clear();
		varpConditionVars.clear();
		int count = 0;
		for (Environment env : loaded)
		{
			boolean valid = bindConditionVars(env.varbitCondition, Gamevals.varbits(), varbitConditionVars, "varbit");
			valid &= bindConditionVars(env.varpCondition, Gamevals.varps(), varpConditionVars, "varp");
			if (!valid)
			{
				log.error("Ignoring environment '{}' with unknown condition variable(s)", env);
				continue;
			}
			loaded[count++] = env;
		}
		return Arrays.copyOf(loaded, count);
	}

	private static boolean bindConditionVars(
		ExpressionPredicate condition,
		Map<String, Integer> gamevals,
		Map<String, Integer> bindings,
		String kind
	)
	{
		if (!(condition instanceof ExpressionParser.SerializableExpressionPredicate))
		{
			return true;
		}
		var expr = ((ExpressionParser.SerializableExpressionPredicate) condition).expression;
		boolean valid = true;
		for (String name : expr.variables)
		{
			if (bindings.containsKey(name))
			{
				continue;
			}
			Integer id = gamevals.get(name.toUpperCase());
			if (id == null)
			{
				log.error("Unknown {} condition variable '{}'", kind, name);
				valid = false;
				continue;
			}
			bindings.put(name, id);
		}
		return valid;
	}

	private boolean isConditionSatisfied(Environment environment)
	{
		return
			environment.varbitCondition.test(varbitVariableSupplier) &&
				environment.varpCondition.test(varpVariableSupplier);
	}

	/**
	 * Collects the environments whose areas intersect the scene, ending with the default environment.
	 */
	public void loadSceneEnvironments(SceneData data)
	{
		data.environments.clear();
		for (Environment environment : environments)
		{
			if (data.intersects(environment.area))
			{
				data.environments.add(environment);
			}
		}
		data.environments.add(Environment.DEFAULT);
	}

	/**
	 * Picks the environment at the camera focal point and advances the transition. Client thread, once per frame.
	 *
	 * @param elapsedTime seconds since the plugin started (117 HD's plugin.elapsedTime)
	 */
	public void update(SceneData data, double elapsedTime)
	{
		int[] focalPoint = data.localToWorld(
			(int) client.getCameraFocalPointX(),
			(int) client.getCameraFocalPointZ(),
			client.getTopLevelWorldView().getPlane()
		);

		// skip the transitional fade if the player has moved too far since the previous frame. results in an
		// instant transition when teleporting, entering dungeons, etc.
		int tileChange = Math.max(
			Math.abs(focalPoint[0] - previousPosition[0]),
			Math.max(Math.abs(focalPoint[1] - previousPosition[1]), Math.abs(focalPoint[2] - previousPosition[2])));
		System.arraycopy(focalPoint, 0, previousPosition, 0, 3);

		boolean skipTransition = tileChange >= SKIP_TRANSITION_DISTANCE;
		for (int i = 0; i < data.environments.size(); i++)
		{
			Environment environment = data.environments.get(i);
			if (!environment.area.containsPoint(focalPoint))
			{
				continue;
			}
			if (!isConditionSatisfied(environment))
			{
				continue;
			}
			changeEnvironment(environment, skipTransition, elapsedTime);
			break;
		}

		// Update every frame, since other plugins may control it.
		updateTargetSkyColor(getResolvedTargetEnvironment());

		if (transitionComplete)
		{
			// Always write fog and water color, since they're affected by lightning
			copyTo(current.getFogColor(), to.getFogColor());
			copyTo(current.getWaterColor(), to.getWaterColor());
		}
		else
		{
			transitionProgress = smoothstep(0, 1, (float) (elapsedTime - transitionStartTime) / TRANSITION_DURATION);
			current.interpolate(from, to, transitionProgress);
			if (transitionProgress == 1)
			{
				transitionComplete = true;
			}
		}

		// legacyLightning is off: no lightning
		lightningBrightness = 0f;
	}

	private void changeEnvironment(Environment newEnvironment, boolean skipTransition, double elapsedTime)
	{
		if (target == newEnvironment)
		{
			return;
		}

		if (target == Environment.NONE)
		{
			skipTransition = true;
		}

		if (target.instantTransition || newEnvironment.instantTransition)
		{
			skipTransition = true;
		}

		// A fixed sky uses Default's clock rather than the configured cycle, so blending
		// between fixed and moving skies would also blend unrelated celestial motion.
		boolean oldHasFixedSun = target.getSky() != null && target.getSky().sunAngles != null;
		boolean newHasFixedSun = newEnvironment.getSky() != null && newEnvironment.getSky().sunAngles != null;
		if (!HdSettings.isCycleDisabled() && target != Environment.NONE && oldHasFixedSun != newHasFixedSun)
		{
			skipTransition = true;
		}

		log.debug("changing environment from {} to {} (instant: {})", target, newEnvironment, skipTransition);
		target = newEnvironment;
		transitionId++;
		transitionComplete = false;
		transitionProgress = 0;
		transitionStartTime = elapsedTime - (skipTransition ? TRANSITION_DURATION : 0);

		current.copyTo(from);
		float[] fromAngles = from.getShadowAngles();
		float[] currentAngles = current.getShadowAngles();
		for (int i = 0; i < fromAngles.length; i++)
		{
			fromAngles[i] = mod(currentAngles[i], TWO_PI);
		}

		Environment areaEnvironment = getResolvedTargetEnvironment();
		Environment lightingEnvironment = areaEnvironment;
		if (HdSettings.disableEnvironmentalLighting && !lightingEnvironment.force)
		{
			lightingEnvironment = getOverworldEnvironment();
		}
		lightingEnvironment.copyTo(to);
		to.fogDepth = areaEnvironment.fogDepth;
		to.groundFogStart = areaEnvironment.groundFogStart;
		to.groundFogEnd = areaEnvironment.groundFogEnd;
		to.groundFogOpacity = areaEnvironment.groundFogOpacity;

		copyTo(to.getShadowAngles(), areaEnvironment.getShadowAngles());
		updateTargetSkyColor(areaEnvironment);

		// Prevent transitions from taking the long way around
		float[] toAngles = to.getShadowAngles();
		for (int i = 0; i < 2; i++)
		{
			float diff = fromAngles[i] - toAngles[i];
			if (Math.abs(diff) > PI)
			{
				toAngles[i] += TWO_PI * Math.signum(diff);
			}
		}
	}

	private void updateTargetSkyColor(Environment env)
	{
		copyTo(to.getFogColor(), getFogColor(env));
		if (usesDefaultSkyColor(env))
		{
			DefaultSkyColor sky = HdSettings.defaultSkyColor;
			if (sky == DefaultSkyColor.OSRS)
			{
				sky = DefaultSkyColor.DEFAULT;
			}
			copyTo(to.getWaterColor(), sky.getRgb(client));
		}
		else
		{
			copyTo(to.getWaterColor(), env.getFogColor());
		}

		// Override with decoupled water/sky color if present
		if (env.hasWaterColorOverride)
		{
			copyTo(to.getWaterColor(), env.getWaterColor());
		}
	}

	private static boolean usesDefaultSkyColor(Environment env)
	{
		return !env.hasFogColorOverride || env.allowSkyOverride && HdSettings.overrideSky;
	}

	public float[] getFogColor(Environment env)
	{
		return usesDefaultSkyColor(env) ? HdSettings.defaultSkyColor.getRgb(client) : env.getFogColor();
	}

	/**
	 * 117 HD's applyLightning: a no-op with legacyLightning off.
	 */
	public void applyLightning(float[] color)
	{
	}

	private Environment getResolvedTargetEnvironment()
	{
		return target == Environment.OVERWORLD ? getOverworldEnvironment() : target;
	}

	public Environment getOverworldEnvironment()
	{
		switch (HdSettings.seasonalTheme)
		{
			case AUTUMN:
				return Environment.AUTUMN;
			case WINTER:
				return Environment.WINTER;
			default:
				return Environment.OVERWORLD;
		}
	}

	/**
	 * Mutable, interpolated environment used for the current frame.
	 */
	public Environment getCurrentEnvironment()
	{
		return current;
	}

	/**
	 * Environment at the beginning of the current transition.
	 */
	public Environment getFromEnvironment()
	{
		return from;
	}

	/**
	 * Resolved environment at the end of the current transition.
	 */
	public Environment getToEnvironment()
	{
		return to;
	}

	/**
	 * Area-selected environment definition, before atmospheric-lighting fallbacks.
	 */
	public Environment getTargetEnvironment()
	{
		return target;
	}
}
