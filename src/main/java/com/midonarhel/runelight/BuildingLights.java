package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft overhead lights inside buildings at night, as if their lamps were lit. Rooms come from the scene's roof
 * data (BuildingDetector); each gets one or a few ceiling lights, shown on the player's plane and, through holes in
 * the floors, on the others. Everything runs on the client thread.
 */
class BuildingLights
{
	private static final Logger log = LoggerFactory.getLogger(BuildingLights.class);

	// the color of a tile paint that isn't drawn
	private static final int HIDDEN_HSL = 12345678;

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
	// The walls (WallMap) the rooms are split along and the static lights are occluded by: rebuilt every tick from
	// the collision maps, as doors open and close, plus the diagonal wall objects, followed through spawn events.
	private volatile WallMap walls;
	private final Map<GameObject, WallMap.Diagonal> diagonals = new IdentityHashMap<>();
	private BuildingDetector.Result coarse;
	// which planes have a floor on every tile (RoomMap.linkStoreys), kept for the rooms rebuilt as doors open
	private byte[] filledTiles;
	private int wallScene;
	private volatile boolean splitRooms = true;

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

	/** The scene's walls, null before a scene is built; a new object whenever they change. */
	WallMap getWalls()
	{
		return walls;
	}

	/** Whether rooms split along their interior walls; a change rebuilds the rooms on the next tick. */
	void setSplitRooms(boolean split)
	{
		if (split != splitRooms)
		{
			splitRooms = split;
			builtFor = null;
		}
	}

	void rebuild()
	{
		builtFor = null;
		result = new BuildingDetector.Result();
		lights = Collections.emptyList();
		rooms = null;
		walls = null;
		coarse = null;
		filledTiles = null;
		diagonals.clear();
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
		coarse = r;
		findDiagonals(scene);
		int[][][] flags = collisionFlags();
		byte[][][] settings = scene.getExtendedTileSettings();
		WallMap w = WallMap.build(flags, diagonals.values(), settings != null && settings.length > 0 ? settings[0].length : 0,
			RuneLightPlugin.SCENE_OFFSET, ++wallScene);
		if (splitRooms)
		{
			r = BuildingDetector.split(r, settings, w);
		}
		lights = place(r, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		RoomMap m = RoomMap.build(r, settings, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		if (m != null)
		{
			m.setWalls(w);
			m.updateOpenings(flags);
			filledTiles = floorTiles(scene, m.size);
			m.linkStoreys(filledTiles);
		}
		rooms = m;
		walls = w;
		result = r;
		builtFor = scene;
		builtBaseX = scene.getBaseX();
		builtBaseY = scene.getBaseY();
		log.debug("Buildings: {} rooms, {} lights (roof ids {}, dropped {} small, {} huge) in {} ms",
			r.rooms.size(), lights.size(), r.usedRoofIds ? "used" : "ignored", r.rejectedSmall, r.rejectedHuge,
			(System.nanoTime() - start) / 1_000_000.0);
	}

	/**
	 * Which planes have a floor (a visible tile paint or tile model) on each extended tile, a bit per plane, indexed
	 * x * size + y; a plane's tile without one is a hole in the floor, as 117 HD's light plane rule sees them.
	 */
	static byte[] floorTiles(Scene scene, int size)
	{
		byte[] out = new byte[size * size];
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int plane = 0; plane < RoomMap.PLANES && plane < tiles.length; ++plane)
		{
			for (int x = 0; x < size && x < tiles[plane].length; ++x)
			{
				Tile[] column = tiles[plane][x];
				for (int y = 0; y < size && y < column.length; ++y)
				{
					Tile tile = column[y];
					if (tile == null)
					{
						continue;
					}
					SceneTilePaint paint = tile.getSceneTilePaint();
					if (paint != null && paint.getNeColor() != HIDDEN_HSL || tile.getSceneTileModel() != null)
					{
						out[x * size + y] |= (byte) (1 << plane);
					}
				}
			}
		}
		return out;
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
			walls = null;
			coarse = null;
			diagonals.clear();
		}
	}

	private void findDiagonals(Scene scene)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					GameObject[] objects = tile != null ? tile.getGameObjects() : null;
					if (objects != null)
					{
						for (GameObject go : objects)
						{
							addDiagonal(go, tile);
						}
					}
				}
			}
		}
	}

	private void addDiagonal(GameObject go, Tile tile)
	{
		if (go == null || tile == null || (go.getConfig() & 0x1F) != WallMap.TYPE_DIAGONAL_WALL)
		{
			return;
		}
		net.runelite.api.Point p = tile.getSceneLocation();
		if (p == null)
		{
			return;
		}
		Renderable r = go.getRenderable();
		Model model = r instanceof Model ? (Model) r : r != null ? r.getModel() : null;
		int kind = model != null
			? WallMap.diagonalOf(model.getVerticesX(), model.getVerticesZ(), model.getVerticesCount(), go.getModelOrientation(),
				go.getConfig() >> 6 & 3)
			: WallMap.diagonalOf(null, null, 0, 0, go.getConfig() >> 6 & 3);
		int offset = RuneLightPlugin.SCENE_OFFSET;
		diagonals.put(go, new WallMap.Diagonal(go.getPlane(), p.getX() + offset, p.getY() + offset, kind));
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		if (builtFor != null)
		{
			addDiagonal(e.getGameObject(), e.getTile());
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		diagonals.remove(e.getGameObject());
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
			WallMap old = walls;
			if (m != null && old != null && scene != null)
			{
				int[][][] flags = collisionFlags();
				WallMap w = WallMap.build(flags, diagonals.values(), old.size, old.offset, old.scene);
				if (!w.sameWalls(old))
				{
					walls = w;
					m.setWalls(w);
					if (splitRooms && coarse != null)
					{
						// the building lights stay where they were; only which tiles each room has changes
						byte[][][] settings = scene.getExtendedTileSettings();
						RoomMap n = RoomMap.build(BuildingDetector.split(coarse, settings, w), settings, scene.getTileHeights(),
							RuneLightPlugin.SCENE_OFFSET);
						if (n != null)
						{
							n.setWalls(w);
							n.updateOpenings(flags);
							n.linkStoreys(filledTiles);
							rooms = n;
							return;
						}
					}
				}
				m.updateOpenings(flags);
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
		RoomMap map = rooms;

		int used = 0;
		for (Light l : ls)
		{
			// other floors' lights only where a hole in the floor lets them be seen (the rooms keep them from lighting
			// anything else)
			if (l.plane != plane && (map == null || !map.seenFrom(l.plane, plane, l.x, l.z)))
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
			out.add(pool.get(used++).set(l.x, l.y, l.z, l.radius, RED, GREEN, BLUE, strength).indoor().onPlane(l.plane)
				.fixed());
		}
	}

	private static float srgbToLinear(float c)
	{
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
	}
}
