package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.*;

import java.util.IdentityHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
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
 * The scene's buildings: their rooms (BuildingDetector, RoomMap), found from the map's roof data, and the walls
 * (WallMap) they are split along, which follow doors as they open and close; and the soft fill the composite
 * spreads over each room that lacks light (RoomLight), darker toward its walls (composite_frag.glsl), instead of point
 * lights. Everything runs on the client thread.
 */
public class BuildingLights
{
	private static final Logger log = LoggerFactory.getLogger(BuildingLights.class);

	// the color of a tile paint that isn't drawn
	private static final int HIDDEN_HSL = 12345678;

	// the color of a room's fill, linear, of luminance 1: warm incandescent (sRGB 255, 196, 132); RoomLight says how much
	public static final float[] FILL_TINT = tint(1f, 0.552f, 0.231f);
	// sun altitudes (degrees) over which the lights fade in at dusk
	static final float FADE_START_ALTITUDE = 6;
	static final float FADE_END_ALTITUDE = -6;

	private final Client client;

	private volatile BuildingDetector.Result result = new BuildingDetector.Result();
	// rooms per tile for the fog and the room fill (RoomMap); null until a scene is built
	private volatile RoomMap rooms;
	private Scene builtFor;
	private int builtBaseX, builtBaseY;
	// The walls (WallMap) the rooms are split along and the lamps are stopped by (LampWalls): from the collision maps,
	// as doors open and close, plus the diagonal wall objects, followed through spawn events.
	private volatile WallMap walls;
	private final Map<GameObject, WallMap.Diagonal> diagonals = new IdentityHashMap<>();
	private BuildingDetector.Result coarse;
	// which planes have a floor on every tile (RoomMap.setFloors), kept for the rooms rebuilt as doors open
	private byte[] filledTiles;
	private int wallScene;

	// The analysis runs on this thread, off the client's (it was 3–18 ms on the swap frame, and WallMap.build every
	// tick): the client thread hands it a copy of the collision flags and takes the result at the next frame (apply).
	private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "Lumina buildings");
		t.setDaemon(true);
		return t;
	});
	// the collision flags the last analysis was handed, to see a door change with a compare instead of a rebuild
	private int[][][] handedFlags;
	// a result waiting to be taken by the client thread; the number of the newest request, so older results are dropped
	private final java.util.concurrent.atomic.AtomicReference<Runnable> pending = new java.util.concurrent.atomic.AtomicReference<>();
	private int requests;
	private volatile int newestRequest;

	private static float[] tint(float r, float g, float b)
	{
		float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
		return new float[]{r / luminance, g / luminance, b / luminance};
	}

	public BuildingLights(Client client)
	{
		this.client = client;
	}

	public BuildingDetector.Result getResult()
	{
		return result;
	}

	public RoomMap getRooms()
	{
		return rooms;
	}

	/** The scene's walls, null before a scene is built; a new object whenever they change. */
	public WallMap getWalls()
	{
		return walls;
	}

	/** Which planes have a floor on each extended tile (floorTiles), null before a scene is built. */
	public byte[] getFloors()
	{
		return filledTiles;
	}

	/** Analyse the scene drawn now, on the worker; the old result is gone at once, the new one comes with apply. */
	public void rebuild()
	{
		builtFor = null;
		result = new BuildingDetector.Result();
		rooms = null;
		walls = null;
		coarse = null;
		filledTiles = null;
		diagonals.clear();
		handedFlags = null;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Scene scene = client.getTopLevelWorldView().getScene();
		if (scene == null)
		{
			return;
		}
		builtFor = scene;
		builtBaseX = scene.getBaseX();
		builtBaseY = scene.getBaseY();
		int[][][] flags = copy(collisionFlags());
		handedFlags = flags;
		int request = newestRequest = ++requests;
		int wallsScene = ++wallScene;
		worker.execute(() ->
		{
			long start = System.nanoTime();
			// the scene's tiles are read here, off the client thread: they change only as objects spawn, which the
			// spawn events follow once the result is taken
			Map<GameObject, WallMap.Diagonal> found = new IdentityHashMap<>();
			findDiagonals(scene, found);
			BuildingDetector.Result r = BuildingDetector.detect(scene.getExtendedTileSettings(), scene.getRoofs());
			BuildingDetector.Result coarseResult = r;
			byte[][][] settings = scene.getExtendedTileSettings();
			WallMap w = WallMap.build(flags, found.values(), settings != null && settings.length > 0 ? settings[0].length : 0,
				LuminaPlugin.SCENE_OFFSET, wallsScene);
			r = BuildingDetector.split(r, settings, w);
			RoomMap m = RoomMap.build(r, settings, scene.getTileHeights(), LuminaPlugin.SCENE_OFFSET);
			byte[] filled = null;
			if (m != null)
			{
				m.setWalls(w);
				filled = floorTiles(scene, m.size);
				m.setFloors(filled);
			}
			BuildingDetector.Result done = r;
			byte[] filledDone = filled;
			log.info("Buildings: {} rooms (roof ids {}, dropped {} small, {} huge) in {} ms (worker)",
				r.rooms.size(), r.usedRoofIds ? "used" : "ignored", r.rejectedSmall, r.rejectedHuge,
				(System.nanoTime() - start) / 1_000_000.0);
			offer(request, () ->
			{
				if (builtFor != scene)
				{
					return;
				}
				// spawns since the hand-off are kept: the analysis' diagonals join them
				found.putAll(diagonals);
				diagonals.clear();
				diagonals.putAll(found);
				coarse = coarseResult;
				filledTiles = filledDone;
				rooms = m;
				walls = w;
				result = done;
			});
		});
	}

	private void offer(int request, Runnable apply)
	{
		if (request == newestRequest)
		{
			pending.set(apply);
		}
	}

	/**
	 * The analysis of a scene from a dump (dev/SceneDump, the offline renderer), in place of the worker's: client
	 * thread, before the frame.
	 */
	public void restore(Scene scene, BuildingDetector.Result result, BuildingDetector.Result coarse, RoomMap rooms,
		WallMap walls, byte[] floors)
	{
		pending.set(null);
		builtFor = scene;
		builtBaseX = scene.getBaseX();
		builtBaseY = scene.getBaseY();
		this.result = result != null ? result : new BuildingDetector.Result();
		this.coarse = coarse;
		this.rooms = rooms;
		this.walls = walls;
		filledTiles = floors;
	}

	/** The coarse analysis (before the walls split it), for the scene dump. */
	public BuildingDetector.Result getCoarse()
	{
		return coarse;
	}

	/** Take the worker's newest result, if one is waiting: client thread, once a frame before the consumers. */
	public void apply()
	{
		Runnable r = pending.getAndSet(null);
		if (r != null)
		{
			r.run();
		}
	}

	public void shutDown()
	{
		worker.shutdownNow();
	}

	private static int[][][] copy(int[][][] flags)
	{
		if (flags == null)
		{
			return null;
		}
		int[][][] out = new int[flags.length][][];
		for (int p = 0; p < flags.length; ++p)
		{
			if (flags[p] != null)
			{
				out[p] = new int[flags[p].length][];
				for (int x = 0; x < flags[p].length; ++x)
				{
					out[p][x] = flags[p][x].clone();
				}
			}
		}
		return out;
	}

	private static boolean same(int[][][] a, int[][][] b)
	{
		if (a == null || b == null || a.length != b.length)
		{
			return a == b;
		}
		for (int p = 0; p < a.length; ++p)
		{
			if (a[p] == null || b[p] == null)
			{
				if (a[p] != b[p])
				{
					return false;
				}
				continue;
			}
			if (a[p].length != b[p].length)
			{
				return false;
			}
			for (int x = 0; x < a[p].length; ++x)
			{
				if (!java.util.Arrays.equals(a[p][x], b[p][x]))
				{
					return false;
				}
			}
		}
		return true;
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
	 * 0 by day to 1 at night, starting a little before sunset so interiors are lit as it gets dark.
	 */
	public static float nightFactor(float sunAltitude)
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
				rooms = null;
			walls = null;
			coarse = null;
			diagonals.clear();
		}
	}

	private static void findDiagonals(Scene scene, Map<GameObject, WallMap.Diagonal> into)
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
							addDiagonal(go, tile, into);
						}
					}
				}
			}
		}
	}

	private static void addDiagonal(GameObject go, Tile tile, Map<GameObject, WallMap.Diagonal> into)
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
		int offset = LuminaPlugin.SCENE_OFFSET;
		into.put(go, new WallMap.Diagonal(go.getPlane(), p.getX() + offset, p.getY() + offset, kind));
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		if (builtFor != null)
		{
			addDiagonal(e.getGameObject(), e.getTile(), diagonals);
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
			// doors open and close: a compare of the collision flags with those last handed over on the client thread
			// (tens of microseconds), the walls and rooms built again on the worker only when they changed
			RoomMap m = rooms;
			WallMap old = walls;
			BuildingDetector.Result coarseNow = coarse;
			byte[] filledNow = filledTiles;
			int[][][] flagsNow = collisionFlags();
			if (m == null || old == null || scene == null || same(flagsNow, handedFlags))
			{
				return;
			}
			int[][][] flags = copy(flagsNow);
			handedFlags = flags;
			java.util.List<WallMap.Diagonal> diagonalsNow = new java.util.ArrayList<>(diagonals.values());
			int request = newestRequest = ++requests;
			Scene sceneNow = scene;
			worker.execute(() ->
			{
				WallMap w = WallMap.build(flags, diagonalsNow, old.size, old.offset, old.scene);
				if (w.sameWalls(old))
				{
					return;
				}
				RoomMap n = null;
				if (coarseNow != null)
				{
					byte[][][] settings = sceneNow.getExtendedTileSettings();
					n = RoomMap.build(BuildingDetector.split(coarseNow, settings, w), settings, sceneNow.getTileHeights(),
						LuminaPlugin.SCENE_OFFSET);
					if (n != null)
					{
						n.setWalls(w);
						n.setFloors(filledNow);
					}
				}
				RoomMap rebuilt = n;
				offer(request, () ->
				{
					if (builtFor != sceneNow || walls != old)
					{
						return;
					}
					walls = w;
					if (rebuilt != null)
					{
						rooms = rebuilt;
					}
					else
					{
						m.setWalls(w);
					}
				});
			});
		}
	}
}
