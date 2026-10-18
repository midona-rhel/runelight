package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft overhead lights inside buildings at night, as if their lamps were lit. Rooms come from the scene's roof
 * data (BuildingDetector); each gets one or a few ceiling lights, shown on the player's plane only. Everything
 * runs on the client thread.
 */
class BuildingLights
{
	private static final Logger log = LoggerFactory.getLogger(BuildingLights.class);

	// a storey is 240 units; hang the light a little under the ceiling (y points down)
	static final float CEILING_HEIGHT = 200;
	// radius in tiles: this share of the room's (or cell's) diagonal, plus a margin so the corners get a little.
	// Point lights are unshadowed, so anything bigger spills onto the ground outside the walls.
	static final float RADIUS_PER_EXTENT = 0.55f;
	static final float RADIUS_MARGIN = 0.75f;
	static final float MIN_RADIUS = 2.5f;
	static final float MAX_RADIUS = 8;
	// 117 HD units: indoor lamps there are 5-15, these fill whole rooms so stay lower
	static final float STRENGTH = 3.5f;
	// share of the night strength used by day when lights are on in the daytime too
	static final float DAY_FACTOR = 0.35f;
	// sun altitudes (degrees) over which the lights fade in at dusk
	static final float FADE_START_ALTITUDE = 6;
	static final float FADE_END_ALTITUDE = -6;
	// warm incandescent, sRGB
	private static final float RED = srgbToLinear(255 / 255f);
	private static final float GREEN = srgbToLinear(196 / 255f);
	private static final float BLUE = srgbToLinear(132 / 255f);

	private final Client client;

	/**
	 * A light in world space (game units, y down).
	 */
	static final class Light
	{
		final int plane;
		final float x, y, z, radius;

		Light(int plane, float x, float y, float z, float radius)
		{
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
		}
	}

	private volatile BuildingDetector.Result result = new BuildingDetector.Result();
	private volatile List<Light> lights = Collections.emptyList();
	// rooms per tile for room-aware point lights and fog (RoomMap); null until a scene is built
	private volatile RoomMap rooms;
	private Scene builtFor;
	private int builtBaseX, builtBaseY;

	private final List<PointLight> pool = new ArrayList<>();

	BuildingLights(Client client)
	{
		this.client = client;
	}

	BuildingDetector.Result getResult()
	{
		return result;
	}

	List<Light> getLights()
	{
		return lights;
	}

	RoomMap getRooms()
	{
		return rooms;
	}

	void rebuild()
	{
		builtFor = null;
		result = new BuildingDetector.Result();
		lights = Collections.emptyList();
		rooms = null;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Scene scene = client.getTopLevelWorldView().getScene();
		if (scene == null)
		{
			return;
		}

		long start = System.nanoTime();
		BuildingDetector.Result r = BuildingDetector.detect(scene.getExtendedTileSettings(), scene.getRoofs());
		lights = place(r, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		RoomMap m = RoomMap.build(r, scene.getExtendedTileSettings(), scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		if (m != null)
		{
			m.updateOpenings(collisionFlags());
		}
		rooms = m;
		result = r;
		builtFor = scene;
		builtBaseX = scene.getBaseX();
		builtBaseY = scene.getBaseY();
		log.debug("Buildings: {} rooms, {} lights (roof ids {}, dropped {} small, {} huge) in {} ms",
			r.rooms.size(), lights.size(), r.usedRoofIds ? "used" : "ignored", r.rejectedSmall, r.rejectedHuge,
			(System.nanoTime() - start) / 1_000_000.0);
	}

	/**
	 * Turn tile-space anchors into world-space lights below the ceiling of their floor.
	 *
	 * @param offset extended scene tiles before local tile 0
	 */
	static List<Light> place(BuildingDetector.Result r, int[][][] heights, int offset)
	{
		List<Light> out = new ArrayList<>(r.anchors.size());
		for (BuildingDetector.Anchor a : r.anchors)
		{
			float ground = 0;
			if (heights != null && a.mapLevel < heights.length)
			{
				int[][] h = heights[a.mapLevel];
				int x = a.tileX, y = a.tileY;
				if (x + 1 < h.length && y + 1 < h[x].length)
				{
					ground = (h[x][y] + h[x + 1][y] + h[x][y + 1] + h[x + 1][y + 1]) / 4f;
				}
			}
			float radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, a.extent * RADIUS_PER_EXTENT + RADIUS_MARGIN));
			out.add(new Light(a.plane,
				(a.x - offset) * Perspective.LOCAL_TILE_SIZE,
				ground - CEILING_HEIGHT,
				(a.y - offset) * Perspective.LOCAL_TILE_SIZE,
				radius * Perspective.LOCAL_TILE_SIZE));
		}
		return out;
	}

	/**
	 * 0 by day to 1 at night, starting a little before sunset so interiors are lit as it gets dark.
	 */
	static float nightFactor(float sunAltitude)
	{
		float t = (FADE_START_ALTITUDE - sunAltitude) / (FADE_START_ALTITUDE - FADE_END_ALTITUDE);
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			rebuild();
		}
		else if (e.getGameState() == GameState.LOADING || e.getGameState() == GameState.LOGIN_SCREEN)
		{
			builtFor = null;
			result = new BuildingDetector.Result();
			lights = Collections.emptyList();
			rooms = null;
		}
	}

	private int[][][] collisionFlags()
	{
		WorldView wv = client.getTopLevelWorldView();
		CollisionData[] maps = wv != null ? wv.getCollisionMaps() : null;
		if (maps == null)
		{
			return null;
		}
		int[][][] flags = new int[maps.length][][];
		for (int i = 0; i < maps.length; ++i)
		{
			flags[i] = maps[i] != null ? maps[i].getFlags() : null;
		}
		return flags;
	}

	/**
	 * Scenes can be swapped without a LOGGED_IN transition (instances, plugins reloading the map); the check is
	 * a couple of compares, and a rebuild takes a few milliseconds.
	 */
	@Subscribe
	public void onGameTick(GameTick e)
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		if (scene != null && (scene != builtFor || scene.getBaseX() != builtBaseX || scene.getBaseY() != builtBaseY))
		{
			rebuild();
		}
		else
		{
			// doors open and close
			RoomMap m = rooms;
			if (m != null)
			{
				m.updateOpenings(collisionFlags());
			}
		}
	}

	/**
	 * Add this frame's building lights to {@code out}.
	 *
	 * @param brightness multiplier on the default strength
	 * @param night 0 by day to 1 at night
	 */
	void collect(List<PointLight> out, float brightness, float night, float camX, float camZ, float maxDistance)
	{
		float strength = STRENGTH * brightness * night;
		List<Light> ls = lights;
		if (strength <= 0 || ls.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();

		int used = 0;
		for (Light l : ls)
		{
			if (l.plane != plane)
			{
				continue;
			}
			float dx = l.x - camX, dz = l.z - camZ;
			float reach = maxDistance + l.radius;
			if (dx * dx + dz * dz > reach * reach)
			{
				continue;
			}
			if (used == pool.size())
			{
				pool.add(new PointLight());
			}
			out.add(pool.get(used++).set(l.x, l.y, l.z, l.radius, RED, GREEN, BLUE, strength).indoor().onPlane(l.plane));
		}
	}

	private static float srgbToLinear(float c)
	{
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
	}
}
