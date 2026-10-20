package com.midonarhel.runelight;

import net.runelite.api.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug aid for finding what makes the lighting snap while walking: logs every scene load and swap with its world
 * base, and every frame in which a value that feeds the lit look jumps by more than a threshold in one frame, with
 * the world position and how long after the last scene swap it happened. Only runs when the plugin logs at DEBUG.
 * Client thread, except {@link #scene}.
 */
final class LookMonitor
{
	private static final Logger log = LoggerFactory.getLogger(LookMonitor.class);

	// the environment's values are EnvironmentUniforms.values, in its order
	private static final String[] NAMES = {
		"ambientR", "ambientG", "ambientB", "sunR", "sunG", "sunB", "skyTintR", "skyTintG", "skyTintB",
		"fogColorR", "fogColorG", "fogColorB", "fogColorWeight", "fogDepth", "haze",
		"weatherFog", "weatherFogHeight", "weatherAreaFog", "weatherDistanceFog", "skyHaze",
		"overcast", "snowiness", "weatherSun", "weatherHaze", "weatherWarmth", "weatherSaturation",
		"fogTintR", "fogTintG", "fogTintB", "wetness", "snowCover", "giWeight", "pointLights"
	};
	// a jump bigger than this in one frame is logged; most values are 0..1
	private static final float DEFAULT_THRESHOLD = 0.03f;
	private static final float FOG_DEPTH_THRESHOLD = 3;
	private static final float LIGHTS_THRESHOLD = 6;
	// one line per value per interval; a jump that goes on for a while is one jump
	private static final long INTERVAL_MILLIS = 1000;

	private final float[] now = new float[NAMES.length];
	private final float[] before = new float[NAMES.length];
	private final float[] overcast = new float[7];
	private final LogLimiter limiter = new LogLimiter(INTERVAL_MILLIS);
	private boolean primed;
	private volatile long sceneMillis;
	private int lastBaseX = Integer.MIN_VALUE, lastBaseY;

	/** A top-level scene was loaded (before the swap) or swapped in. */
	void scene(String what, Scene scene)
	{
		if (!log.isDebugEnabled())
		{
			return;
		}
		int x = scene.getBaseX(), y = scene.getBaseY();
		log.debug("Look: scene {} base ({}, {}) region ({}, {}){}{}", what, x, y, x >> 6, y >> 6,
			scene.isInstance() ? " instance" : "",
			lastBaseX == Integer.MIN_VALUE ? "" : ", moved (" + (x - lastBaseX) + ", " + (y - lastBaseY) + ") tiles");
		lastBaseX = x;
		lastBaseY = y;
		sceneMillis = System.currentTimeMillis();
	}

	/**
	 * Once a frame, after the composite's uniforms are set.
	 *
	 * @param world focal world tile (x, y, plane), -1 when unknown
	 */
	void frame(EnvironmentUniforms env, Weather weather, GlobalIllumination gi, int pointLights, int[] world,
		String biome)
	{
		float[] v = now;
		System.arraycopy(env.values, 0, v, 0, EnvironmentUniforms.VALUES);
		int i = EnvironmentUniforms.VALUES;
		v[i++] = weather.fogMultiplier();
		v[i++] = weather.fogHeightMultiplier();
		v[i++] = weather.areaFogWeight();
		v[i++] = weather.distanceFogScale();
		v[i++] = weather.skyHaze();
		weather.getOvercast(overcast);
		v[i++] = overcast[0];
		v[i++] = overcast[1];
		System.arraycopy(overcast, 3, v, i, 4);
		i += 4;
		System.arraycopy(weather.getFogTint(), 0, v, i, 3);
		i += 3;
		v[i++] = weather.getWetness();
		v[i++] = weather.getSnowCover();
		v[i++] = gi.shownWeight();
		v[i] = pointLights;

		if (primed)
		{
			for (int k = 0; k < v.length; ++k)
			{
				float limit = k == EnvironmentUniforms.VALUES - 2 ? FOG_DEPTH_THRESHOLD
					: k == v.length - 1 ? LIGHTS_THRESHOLD : DEFAULT_THRESHOLD;
				if (Math.abs(v[k] - before[k]) > limit)
				{
					report(k, before[k], v[k], world, biome);
				}
			}
		}
		primed = true;
		System.arraycopy(v, 0, before, 0, v.length);
	}

	private void report(int k, float from, float to, int[] world, String biome)
	{
		int held = limiter.check(NAMES[k]);
		if (held < 0)
		{
			return;
		}
		long since = System.currentTimeMillis() - sceneMillis;
		log.debug("Look: {} jumped {} -> {} at world ({}, {}, {}) biome {}, {} after the last scene swap{}", NAMES[k],
			from, to, world[0], world[1], world[2], biome, since < 600_000 ? since + " ms" : "long",
			held > 0 ? " (" + held + " more since last logged)" : "");
	}
}
