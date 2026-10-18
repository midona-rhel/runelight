package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the camera is: which area (environment) it is in, whether that is under the open sky or underground,
 * and the ambience that area asks for, blended over a few seconds as the camera moves between areas.
 *
 * Areas come from environments.json (tools/import_environments.py): 117 HD's environments in priority order,
 * then 3D Weather's biome per 64x64 region, then 117 HD's OVERWORLD area (mainland plus extensions such as
 * houses: open sky), then a fallback by height (y >= 4160 is underground) and finally the client's sky color.
 *
 * Everything here is relative to RuneLight's own lighting: 1.0 multipliers and zero fog are its normal look.
 * The values for the frame are in the public fields/getters, for the shaders and for other effects (fog,
 * weather, interior lights) to read.
 */
final class EnvironmentManager
{
	private static final Logger log = LoggerFactory.getLogger(EnvironmentManager.class);

	enum Classification
	{
		OVERWORLD,
		CAVE,
	}

	private static final float TRANSITION_SECONDS = 3f;
	// a jump this many tiles in a frame is a teleport or a scene change: switch without blending
	private static final int INSTANT_DISTANCE = 41;
	private static final float SHELTER_SECONDS = 0.5f;

	/** One area's look, as loaded. */
	static final class Environment
	{
		String name;
		boolean cave;
		boolean biome;
		// 117 HD's OVERWORLD area: open sky, but a biome of the same region describes it better
		boolean fallback;
		boolean instant;
		float[] ambient = {1, 1, 1};
		float[] sun = {1, 1, 1};
		// null: fog takes the color of the sky
		float[] fogColor;
		float fogDepth;
		float haze;
		float[] skyTint = {1, 1, 1};

		@Override
		public String toString()
		{
			return name;
		}
	}

	private static final class Data
	{
		Environment[] environments;
		int[] boxes;
		Map<String, int[]> biomeRegions;
	}

	/** Blendable lighting parameters. */
	static final class Ambience
	{
		final float[] ambient = {1, 1, 1};
		final float[] sun = {1, 1, 1};
		final float[] fogColor = {0, 0, 0};
		// 0: fog has the sky's color, 1: fogColor
		float fogColorWeight;
		// 117 HD's fogDepth: an artistic density, 0 none, ~6 barely visible, 50 thick
		float fogDepth;
		float haze;
		final float[] skyTint = {1, 1, 1};

		void set(Environment e)
		{
			System.arraycopy(e.ambient, 0, ambient, 0, 3);
			System.arraycopy(e.sun, 0, sun, 0, 3);
			if (e.fogColor != null)
			{
				System.arraycopy(e.fogColor, 0, fogColor, 0, 3);
				fogColorWeight = 1;
			}
			else
			{
				fogColorWeight = 0;
			}
			fogDepth = e.fogDepth;
			haze = e.haze;
			System.arraycopy(e.skyTint, 0, skyTint, 0, 3);
		}

		void copyFrom(Ambience o)
		{
			System.arraycopy(o.ambient, 0, ambient, 0, 3);
			System.arraycopy(o.sun, 0, sun, 0, 3);
			System.arraycopy(o.fogColor, 0, fogColor, 0, 3);
			fogColorWeight = o.fogColorWeight;
			fogDepth = o.fogDepth;
			haze = o.haze;
			System.arraycopy(o.skyTint, 0, skyTint, 0, 3);
		}

		void mix(Ambience a, Ambience b, float t)
		{
			mix3(ambient, a.ambient, b.ambient, t);
			mix3(sun, a.sun, b.sun, t);
			// keep a fading-in fog color from blending through black: take the other side's color when unset
			float[] fa = a.fogColorWeight > 0 ? a.fogColor : b.fogColor;
			float[] fb = b.fogColorWeight > 0 ? b.fogColor : a.fogColor;
			mix3(fogColor, fa, fb, t);
			fogColorWeight = a.fogColorWeight + (b.fogColorWeight - a.fogColorWeight) * t;
			fogDepth = a.fogDepth + (b.fogDepth - a.fogDepth) * t;
			haze = a.haze + (b.haze - a.haze) * t;
			mix3(skyTint, a.skyTint, b.skyTint, t);
		}

		private static void mix3(float[] out, float[] a, float[] b, float t)
		{
			for (int i = 0; i < 3; ++i)
			{
				out[i] = a[i] + (b[i] - a[i]) * t;
			}
		}
	}

	static final Environment NEUTRAL = new Environment();
	static final Environment NEUTRAL_CAVE = new Environment();

	static
	{
		NEUTRAL.name = "NONE";
		NEUTRAL_CAVE.name = "NONE";
		NEUTRAL_CAVE.cave = true;
	}

	private Environment[] environments = new Environment[0];
	private int[] boxes = new int[0];
	// region id -> indices into boxes (in priority order), and region id -> biome environment
	private final Map<Integer, int[]> regionBoxes = new HashMap<>();
	private final Map<Integer, Environment> regionBiome = new HashMap<>();
	private Environment caveBiome = NEUTRAL_CAVE;

	// selection
	private Environment target = NEUTRAL;
	private Environment biome;
	private Classification classification = Classification.OVERWORLD;
	private final int[] worldPoint = {-1, -1, -1};
	private final int[] lastWorldPoint = {Integer.MIN_VALUE, 0, 0};
	private Scene lastScene;
	private int lastLocalTileX = Integer.MIN_VALUE, lastLocalTileY, lastPlane;
	private boolean lastSkyBlack;
	private boolean hasTarget;

	// blending
	private final Ambience from = new Ambience();
	private final Ambience to = new Ambience();
	private final Ambience current = new Ambience();
	private float transitionStart = -TRANSITION_SECONDS;
	private float sheltered;
	private boolean underRoof;

	void load(Gson gson)
	{
		Data data;
		try (InputStream is = EnvironmentManager.class.getResourceAsStream("environments.json"))
		{
			if (is == null)
			{
				log.warn("environments.json missing");
				return;
			}
			data = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class);
		}
		catch (Exception ex)
		{
			log.error("Unable to load environments.json", ex);
			return;
		}

		environments = data.environments;
		boxes = data.boxes;
		regionBoxes.clear();
		regionBiome.clear();

		Map<Integer, List<Integer>> byRegion = new HashMap<>();
		for (int b = 0; b < boxes.length; b += 7)
		{
			int rx0 = boxes[b + 1] >> 6, ry0 = boxes[b + 2] >> 6;
			int rx1 = boxes[b + 4] >> 6, ry1 = boxes[b + 5] >> 6;
			for (int rx = rx0; rx <= rx1; ++rx)
			{
				for (int ry = ry0; ry <= ry1; ++ry)
				{
					byRegion.computeIfAbsent(rx << 8 | ry, k -> new ArrayList<>()).add(b);
				}
			}
		}
		for (Map.Entry<Integer, List<Integer>> e : byRegion.entrySet())
		{
			List<Integer> l = e.getValue();
			int[] a = new int[l.size()];
			for (int i = 0; i < a.length; ++i)
			{
				a[i] = l.get(i);
			}
			regionBoxes.put(e.getKey(), a);
		}

		if (data.biomeRegions != null)
		{
			for (Map.Entry<String, int[]> e : data.biomeRegions.entrySet())
			{
				Environment env = environments[Integer.parseInt(e.getKey())];
				for (int rid : e.getValue())
				{
					regionBiome.put(rid, env);
				}
			}
		}
		for (Environment e : environments)
		{
			if (e.biome && "CAVE".equals(e.name))
			{
				caveBiome = e;
			}
		}
		log.debug("Loaded {} environments, {} boxes over {} regions, {} biome regions",
			environments.length, boxes.length / 7, regionBoxes.size(), regionBiome.size());
	}

	/**
	 * Pick the environment at a local point of the top-level scene and advance the blend. Call once per frame.
	 *
	 * @param scene    top-level scene, or null when there is none (login screen)
	 * @param localX   camera focal point, local units
	 * @param localY   camera focal point, local units
	 * @param plane    the plane being viewed
	 * @param skyColor the client's sky color; black means underground when nothing else knows
	 * @param time     seconds, monotonic
	 */
	void update(Scene scene, int localX, int localY, int plane, int skyColor, float time)
	{
		int tileX = localX >> 7, tileY = localY >> 7;
		int r = skyColor >> 16 & 0xFF, g = skyColor >> 8 & 0xFF, b = skyColor & 0xFF;
		boolean skyBlack = r + g + b <= 24;

		if (scene == null)
		{
			lastScene = null;
			lastWorldPoint[0] = Integer.MIN_VALUE;
			select(null, skyBlack ? Classification.CAVE : Classification.OVERWORLD, null, true, time);
			advance(time, false);
			return;
		}

		// the lookup only changes when the focal tile, plane or scene does
		if (scene != lastScene || tileX != lastLocalTileX || tileY != lastLocalTileY || plane != lastPlane
			|| skyBlack != lastSkyBlack)
		{
			lastScene = scene;
			lastLocalTileX = tileX;
			lastLocalTileY = tileY;
			lastPlane = plane;
			lastSkyBlack = skyBlack;
			underRoof = isUnderRoof(scene, tileX, tileY, plane);

			boolean jumped = true;
			if (toWorld(scene, tileX, tileY, plane, worldPoint))
			{
				if (lastWorldPoint[0] != Integer.MIN_VALUE)
				{
					int d = Math.max(Math.abs(worldPoint[0] - lastWorldPoint[0]),
						Math.max(Math.abs(worldPoint[1] - lastWorldPoint[1]), Math.abs(worldPoint[2] - lastWorldPoint[2])));
					jumped = d >= INSTANT_DISTANCE;
				}
				System.arraycopy(worldPoint, 0, lastWorldPoint, 0, 3);
				lookup(worldPoint, skyBlack, jumped, time);
			}
			else
			{
				select(null, skyBlack ? Classification.CAVE : Classification.OVERWORLD, null, true, time);
			}
		}

		advance(time, classification == Classification.CAVE || underRoof);
	}

	private void lookup(int[] wp, boolean skyBlack, boolean jumped, float time)
	{
		int x = wp[0], y = wp[1], p = wp[2];
		int region = x >> 6 << 8 | y >> 6;

		Environment env = null;
		int[] candidates = regionBoxes.get(region);
		if (candidates != null)
		{
			for (int b : candidates)
			{
				if (x >= boxes[b + 1] && x <= boxes[b + 4] && y >= boxes[b + 2] && y <= boxes[b + 5]
					&& p >= boxes[b + 3] && p <= boxes[b + 6])
				{
					env = environments[boxes[b]];
					break;
				}
			}
		}

		Environment regionEnv = regionBiome.get(region);
		if (env != null && env.fallback && regionEnv != null && !regionEnv.cave)
		{
			env = regionEnv;
		}

		Classification c;
		if (env != null)
		{
			c = env.cave ? Classification.CAVE : Classification.OVERWORLD;
		}
		else if (regionEnv != null)
		{
			env = regionEnv;
			c = env.cave ? Classification.CAVE : Classification.OVERWORLD;
		}
		else if (y >= Constants.OVERWORLD_MAX_Y)
		{
			env = caveBiome;
			c = Classification.CAVE;
		}
		else
		{
			// mainland coordinates nobody described: trust the client's sky
			c = skyBlack ? Classification.CAVE : Classification.OVERWORLD;
		}
		select(env, c, regionEnv, jumped, time);
	}

	// areas whose water is murky besides caves; matched against environment names
	private static final String[] MURKY_SWAMP_AREAS = {"SWAMP", "MORYTANIA", "POISON_WASTE", "MEIYERDITCH",
		"DARKMEYER", "BARROWS", "STRANGLEWOOD", "BACKWATER"};
	// caves with clear water
	private static final String[] CLEAR_CAVES = {"ZANARIS", "TEARS_OF_GUTHIX", "LUNAR_DREAM_WORLD", "COSMIC_ENTITYS_PLANE"};

	/**
	 * How the area of a scene tile treats its water (WaterMap.CLEAR, MURKY_SWAMP or MURKY_CAVE): murky underground
	 * and in swamps. For scene loading; safe off the client thread once loaded.
	 */
	int waterMurk(Scene scene, int sceneX, int sceneY, int plane)
	{
		int[] wp = new int[3];
		if (environments == null || !toWorld(scene, sceneX, sceneY, plane, wp))
		{
			return WaterMap.CLEAR;
		}
		Environment env = environmentAt(wp[0], wp[1], wp[2]);
		if (env == null)
		{
			return WaterMap.CLEAR;
		}
		String name = env.name != null ? env.name.toUpperCase() : "";
		if (env.cave)
		{
			for (String c : CLEAR_CAVES)
			{
				if (name.contains(c))
				{
					return WaterMap.CLEAR;
				}
			}
			return WaterMap.MURKY_CAVE;
		}
		for (String m : MURKY_SWAMP_AREAS)
		{
			if (name.contains(m))
			{
				return WaterMap.MURKY_SWAMP;
			}
		}
		return WaterMap.CLEAR;
	}

	/**
	 * The biome whose weather a scene tile has, for WeatherField: the region's 3D Weather biome, null where no
	 * biome describes it (the default biome's weather), or WeatherField.NO_SKY underground and in caves.
	 *
	 * @param wp scratch, 3 ints
	 */
	String weatherBiome(Scene scene, int sceneX, int sceneY, int plane, int[] wp)
	{
		if (!toWorld(scene, sceneX, sceneY, plane, wp))
		{
			return null;
		}
		Environment env = environmentAt(wp[0], wp[1], wp[2]);
		if (env != null && env.cave)
		{
			return WeatherField.NO_SKY;
		}
		Environment biome = regionBiome.get(wp[0] >> 6 << 8 | wp[1] >> 6);
		return biome != null && !biome.cave ? biome.name : null;
	}

	/**
	 * The area at a world point, as {@link #lookup} picks it, without changing the current one. Null where nothing
	 * describes the point.
	 */
	private Environment environmentAt(int x, int y, int p)
	{
		int region = x >> 6 << 8 | y >> 6;
		Environment env = null;
		int[] candidates = regionBoxes.get(region);
		if (candidates != null)
		{
			for (int b : candidates)
			{
				if (x >= boxes[b + 1] && x <= boxes[b + 4] && y >= boxes[b + 2] && y <= boxes[b + 5]
					&& p >= boxes[b + 3] && p <= boxes[b + 6])
				{
					env = environments[boxes[b]];
					break;
				}
			}
		}
		Environment regionEnv = regionBiome.get(region);
		if (env == null || env.fallback && regionEnv != null && !regionEnv.cave)
		{
			env = regionEnv != null ? regionEnv : env;
		}
		if (env == null && y >= Constants.OVERWORLD_MAX_Y)
		{
			env = caveBiome;
		}
		return env;
	}

	private void select(Environment env, Classification c, Environment biome, boolean jumped, float time)
	{
		if (env == null)
		{
			env = c == Classification.CAVE ? NEUTRAL_CAVE : NEUTRAL;
		}
		this.biome = biome;
		if (hasTarget && env == target && c == classification)
		{
			return;
		}

		boolean instant = !hasTarget || jumped || c != classification || env.instant || target.instant;
		log.debug("environment {} -> {} ({}{})", target, env, c, instant ? ", instant" : "");
		hasTarget = true;
		target = env;
		classification = c;

		from.copyFrom(current);
		to.set(env);
		transitionStart = instant ? time - TRANSITION_SECONDS : time;
	}

	private void advance(float time, boolean shelteredNow)
	{
		float t = Math.max(0, Math.min(1, (time - transitionStart) / TRANSITION_SECONDS));
		t = t * t * (3 - 2 * t);
		current.mix(from, to, t);

		float dt = Math.max(0, Math.min(0.25f, time - lastTime));
		lastTime = time;
		float step = dt / SHELTER_SECONDS;
		sheltered = shelteredNow ? Math.min(1, sheltered + step) : Math.max(0, sheltered - step);
	}

	private float lastTime;

	private static boolean isUnderRoof(Scene scene, int tileX, int tileY, int plane)
	{
		byte[][][] settings = scene.getExtendedTileSettings();
		int sx = tileX + RuneLightPlugin.SCENE_OFFSET, sy = tileY + RuneLightPlugin.SCENE_OFFSET;
		if (settings == null || plane < 0 || plane >= settings.length || sx < 0 || sy < 0
			|| sx >= settings[plane].length || sy >= settings[plane][sx].length)
		{
			return false;
		}
		return (settings[plane][sx][sy] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
	}

	/**
	 * Scene tile to world tile. Instances are built from chunks of the real map: use the template chunk's real
	 * location so areas defined on the real map apply.
	 */
	static boolean toWorld(Scene scene, int sceneX, int sceneY, int plane, int[] out)
	{
		if (!scene.isInstance())
		{
			out[0] = scene.getBaseX() + sceneX;
			out[1] = scene.getBaseY() + sceneY;
			out[2] = plane;
			return true;
		}

		if (sceneX < 0 || sceneY < 0 || sceneX >= Constants.SCENE_SIZE || sceneY >= Constants.SCENE_SIZE
			|| plane < 0 || plane >= Constants.MAX_Z)
		{
			return false;
		}
		int[][][] templates = scene.getInstanceTemplateChunks();
		if (templates == null)
		{
			return false;
		}
		int chunk = templates[plane][sceneX / 8][sceneY / 8];
		if (chunk == -1)
		{
			return false;
		}
		int rotation = 4 - (chunk >> 1 & 3);
		int cx = (chunk >> 14 & 1023) * 8;
		int cy = (chunk >> 3 & 2047) * 8;
		int x = sceneX & 7, y = sceneY & 7;
		switch (rotation & 3)
		{
			case 1:
				out[0] = cx + y;
				out[1] = cy + (7 - x);
				break;
			case 2:
				out[0] = cx + (7 - x);
				out[1] = cy + (7 - y);
				break;
			case 3:
				out[0] = cx + (7 - y);
				out[1] = cy + x;
				break;
			default:
				out[0] = cx + x;
				out[1] = cy + y;
				break;
		}
		out[2] = chunk >> 24 & 3;
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Results for this frame

	Classification getClassification()
	{
		return classification;
	}

	/** Under the open sky: sun, moon, sky and daylight apply. Inside a building under a roof still counts. */
	boolean isOverworld()
	{
		return classification == Classification.OVERWORLD;
	}

	/** 0 in the open, 1 under a roof or underground, eased over half a second. For weather. */
	float getSheltered()
	{
		return sheltered;
	}

	/** The area's own environment, or a biome, or NONE. */
	Environment getEnvironment()
	{
		return target;
	}

	/** 3D Weather's biome of the current region, or null. */
	Environment getBiome()
	{
		return biome;
	}

	/** World tile the lookup used: x, y, plane; -1 when unknown. */
	int[] getWorldPoint()
	{
		return worldPoint;
	}

	/** Blended lighting parameters for this frame, see {@link Ambience}. */
	Ambience getAmbience()
	{
		return current;
	}

	/** Fog color as sRGB; mix it over the sky-derived fog color by {@link #getFogColorWeight()}. */
	float[] getFogColor()
	{
		return current.fogColor;
	}

	float getFogColorWeight()
	{
		return current.fogColorWeight;
	}

	/** 117 HD-style fog depth (density): 0 none, ~6 barely visible, 50 thick. */
	float getFogDepth()
	{
		return current.fogDepth;
	}

	/** How much of the sky is hidden behind fog, 0 to 1. */
	float getHaze()
	{
		return current.haze;
	}

	int getEnvironmentCount()
	{
		return environments.length;
	}
}
