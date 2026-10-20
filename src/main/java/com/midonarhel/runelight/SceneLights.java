package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Projectile;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places the lights from lights.json in the scene: on objects (tracked through spawn events, including
 * objects that change appearance through impostors), on NPCs, projectiles and spell effects (followed every
 * frame), and at fixed world positions. Everything runs on the client thread.
 */
class SceneLights
{
	private static final Logger log = LoggerFactory.getLogger(SceneLights.class);
	// half a tile, and how far inside it a wall decoration's light is kept (world units)
	private static final float TILE_HALF = 64, TILE_MARGIN = 2;
	private static final float JAU_TO_RAD = (float) (Math.PI * 2 / 2048);

	private final Client client;

	private final Map<Integer, List<LightDefinition>> byObject = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byNpc = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byProjectile = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byGraphics = new HashMap<>();
	private final List<LightDefinition> worldDefinitions = new ArrayList<>();
	private int definitionCount;

	/**
	 * A light at a fixed position in the current scene.
	 */
	private static final class Placed
	{
		final LightDefinition def;
		final float x, y, z;
		final int plane;
		final float seed;

		Placed(LightDefinition def, float x, float y, float z, int plane, float seed)
		{
			this.def = def;
			this.x = x;
			this.y = y;
			this.z = z;
			this.plane = plane;
			this.seed = seed;
		}
	}

	private static final class TrackedObject
	{
		int resolvedId;
		boolean hasImpostors;
		List<Placed> lights = Collections.emptyList();
	}

	private final Map<TileObject, TrackedObject> objects = new IdentityHashMap<>();
	private final List<Placed> worldLights = new ArrayList<>();
	// this frame's rooms, for the lights of the other planes
	private RoomMap viewRooms;

	// per-frame pool so collecting lights does not allocate
	private final List<PointLight> pool = new ArrayList<>();
	private int poolUsed;

	SceneLights(Client client)
	{
		this.client = client;
	}

	void load(Gson gson)
	{
		byObject.clear();
		byNpc.clear();
		byProjectile.clear();
		byGraphics.clear();
		worldDefinitions.clear();

		LightDefinition[] defs;
		try (InputStream is = SceneLights.class.getResourceAsStream("lights.json"))
		{
			if (is == null)
			{
				log.warn("lights.json missing");
				return;
			}
			defs = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), LightDefinition[].class);
		}
		catch (Exception ex)
		{
			log.error("Unable to load lights.json", ex);
			return;
		}

		for (LightDefinition d : defs)
		{
			d.prepare();
			index(byObject, d.objectIds, d);
			index(byNpc, d.npcIds, d);
			index(byProjectile, d.projectileIds, d);
			index(byGraphics, d.graphicsObjectIds, d);
			if (d.worldX != null && d.worldY != null)
			{
				worldDefinitions.add(d);
			}
		}
		definitionCount = defs.length;
		log.debug("Loaded {} light definitions", definitionCount);
	}

	private static void index(Map<Integer, List<LightDefinition>> map, int[] ids, LightDefinition d)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				map.computeIfAbsent(id, k -> new ArrayList<>(1)).add(d);
			}
		}
	}

	int getDefinitionCount()
	{
		return definitionCount;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Static lights: objects and fixed world positions

	/**
	 * Rescan the whole scene. Called after a scene load and on startup.
	 */
	void rebuild()
	{
		objects.clear();
		worldLights.clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					add(tile.getWallObject());
					add(tile.getDecorativeObject());
					add(tile.getGroundObject());
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject go : gameObjects)
						{
							add(go);
						}
					}
				}
			}
		}

		for (LightDefinition d : worldDefinitions)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, d.worldX, d.worldY);
			if (lp == null)
			{
				continue;
			}
			float ground = Perspective.getTileHeight(client, lp, d.plane);
			worldLights.add(place(d, lp.getX(), ground, lp.getY(), 0, d.plane, d.worldX * 31 + d.worldY));
		}
		log.debug("Scene lights: {} objects with lights, {} world lights", countObjectLights(), worldLights.size());
	}

	private int countObjectLights()
	{
		int n = 0;
		for (TrackedObject t : objects.values())
		{
			n += t.lights.size();
		}
		return n;
	}

	private void add(TileObject object)
	{
		if (object == null || objects.containsKey(object))
		{
			return;
		}
		TrackedObject t = new TrackedObject();
		ObjectComposition comp = client.getObjectDefinition(object.getId());
		t.hasImpostors = comp != null && comp.getImpostorIds() != null;
		t.resolvedId = resolveId(object.getId(), comp);
		// only remember objects that have, or could switch to having, lights
		if (!t.hasImpostors && !byObject.containsKey(t.resolvedId))
		{
			return;
		}
		t.lights = placeObjectLights(object, t.resolvedId);
		objects.put(object, t);
	}

	private void remove(TileObject object)
	{
		objects.remove(object);
	}

	private int resolveId(int id, ObjectComposition comp)
	{
		if (comp != null && comp.getImpostorIds() != null)
		{
			ObjectComposition impostor = comp.getImpostor();
			if (impostor != null)
			{
				return impostor.getId();
			}
		}
		return id;
	}

	private List<Placed> placeObjectLights(TileObject object, int id)
	{
		List<LightDefinition> defs = byObject.get(id);
		LocalPoint lp = object.getLocalLocation();
		if (defs == null || lp == null)
		{
			return Collections.emptyList();
		}

		int orientation = 0;
		float x = lp.getX(), z = lp.getY();
		if (object instanceof GameObject)
		{
			orientation = ((GameObject) object).getOrientation();
		}
		else if (object instanceof DecorativeObject)
		{
			DecorativeObject d = (DecorativeObject) object;
			orientation = (d.getConfig() >> 6 & 3) * 512;
			x += d.getXOffset();
			z += d.getYOffset();
		}

		int plane = object.getPlane();
		float ground = Perspective.getTileHeight(client, lp, plane);
		List<Placed> out = new ArrayList<>(defs.size());
		for (LightDefinition d : defs)
		{
			Placed placed = place(d, x, ground, z, orientation, plane, System.identityHashCode(object));
			out.add(object instanceof DecorativeObject ? onTile(placed, lp.getX(), lp.getY()) : placed);
		}
		return out;
	}

	/**
	 * A wall decoration's light stays on the tile the decoration hangs on, which is the side of the wall it faces,
	 * whatever its definition's offsets add to the decoration's own.
	 */
	private static Placed onTile(Placed p, float tileX, float tileZ)
	{
		float x = Math.max(tileX - TILE_HALF + TILE_MARGIN, Math.min(tileX + TILE_HALF - TILE_MARGIN, p.x));
		float z = Math.max(tileZ - TILE_HALF + TILE_MARGIN, Math.min(tileZ + TILE_HALF - TILE_MARGIN, p.z));
		return x == p.x && z == p.z ? p : new Placed(p.def, x, p.y, z, p.plane, p.seed);
	}

	/**
	 * Position a light relative to its parent. Orientation is in game units (2048 per turn, 0 = south);
	 * 117 HD's offsets rotate with it and their y points up.
	 */
	private static Placed place(LightDefinition d, float x, float ground, float z, int orientation, int plane, int seed)
	{
		float s = (float) Math.sin(orientation * JAU_TO_RAD);
		float c = (float) Math.cos(orientation * JAU_TO_RAD);
		float ox = d.offset[0], oz = d.offset[2];
		float px = x - c * ox - s * oz;
		float pz = z - c * oz + s * ox;
		float py = ground - d.height - 1 - d.offset[1];
		return new Placed(d, px, py, pz, plane, (seed * 0.61803398875f) % 1f);
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
			objects.clear();
			worldLights.clear();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		add(e.getGameObject());
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		remove(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		add(e.getWallObject());
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		remove(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
	{
		add(e.getDecorativeObject());
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
	{
		remove(e.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		add(e.getGroundObject());
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned e)
	{
		remove(e.getGroundObject());
	}

	/**
	 * Objects whose appearance depends on game state (a fire pit being lit, a lamp switched on) change through
	 * impostors without a spawn event, so recheck them each tick.
	 */
	@Subscribe
	public void onGameTick(GameTick e)
	{
		for (Map.Entry<TileObject, TrackedObject> entry : objects.entrySet())
		{
			TrackedObject t = entry.getValue();
			if (!t.hasImpostors)
			{
				continue;
			}
			int id = resolveId(entry.getKey().getId(), client.getObjectDefinition(entry.getKey().getId()));
			if (id != t.resolvedId)
			{
				t.resolvedId = id;
				t.lights = placeObjectLights(entry.getKey(), id);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Per frame

	/**
	 * Add this frame's visible lights to {@code out}.
	 *
	 * @param time seconds, for flicker and pulse
	 * @param night 0 by day to 1 at night, for night-only lights and night multipliers
	 * @param maxDistance only lights whose sphere comes within this distance of the camera (xz) are added
	 * @param rooms the scene's rooms and floors, null when unknown: they decide which lights of the other planes are
	 * seen through an opening
	 */
	void collect(List<PointLight> out, float time, float night, float camX, float camZ, float maxDistance, RoomMap rooms)
	{
		poolUsed = 0;
		viewRooms = rooms;
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();

		for (TrackedObject t : objects.values())
		{
			for (Placed p : t.lights)
			{
				emitFixed(out, p, plane, time, night, camX, camZ, maxDistance);
			}
		}
		for (Placed p : worldLights)
		{
			emitFixed(out, p, plane, time, night, camX, camZ, maxDistance);
		}

		if (!byNpc.isEmpty())
		{
			for (NPC npc : wv.npcs())
			{
				NPCComposition comp = npc.getTransformedComposition();
				List<LightDefinition> defs = byNpc.get(comp != null ? comp.getId() : npc.getId());
				LocalPoint lp = defs != null ? npc.getLocalLocation() : null;
				if (lp == null)
				{
					continue;
				}
				float ground = Perspective.getTileHeight(client, lp, plane);
				int orientation = npc.getCurrentOrientation();
				float s = (float) Math.sin(orientation * JAU_TO_RAD);
				float c = (float) Math.cos(orientation * JAU_TO_RAD);
				float seed = (npc.getIndex() * 0.61803398875f) % 1f;
				for (LightDefinition d : defs)
				{
					// as place(), without allocating every frame
					float ox = d.offset[0], oz = d.offset[2];
					emit(out, d, lp.getX() - c * ox - s * oz, ground - d.height - 1 - d.offset[1], lp.getY() - c * oz + s * ox,
						plane, plane, seed, time, night, camX, camZ, maxDistance);
				}
			}
		}

		if (!byProjectile.isEmpty())
		{
			for (Projectile proj : client.getProjectiles())
			{
				List<LightDefinition> defs = byProjectile.get(proj.getId());
				if (defs == null || proj.getRemainingCycles() <= 0)
				{
					continue;
				}
				for (LightDefinition d : defs)
				{
					// projectile z is its height in scene space; 117 HD lifts the light by the definition's height
					emit(out, d, (float) proj.getX(), (float) proj.getZ() - d.height, (float) proj.getY(),
						plane, plane, (proj.getStartCycle() * 0.618f) % 1f, time, night, camX, camZ, maxDistance);
				}
			}
		}

		if (!byGraphics.isEmpty())
		{
			for (GraphicsObject g : wv.getGraphicsObjects())
			{
				List<LightDefinition> defs = byGraphics.get(g.getId());
				LocalPoint lp = defs != null && !g.finished() ? g.getLocation() : null;
				if (lp == null)
				{
					continue;
				}
				for (LightDefinition d : defs)
				{
					emit(out, d, lp.getX(), g.getZ() - d.height, lp.getY(), g.getLevel(), plane,
						(g.getStartCycle() * 0.618f) % 1f, time, night, camX, camZ, maxDistance);
				}
			}
		}
	}

	private void emitFixed(List<PointLight> out, Placed p, int plane, float time, float night, float camX, float camZ,
		float maxDistance)
	{
		int n = out.size();
		emit(out, p.def, p.x, p.y, p.z, p.plane, plane, p.seed, time, night, camX, camZ, maxDistance);
		if (out.size() > n)
		{
			out.get(n).fixed();
		}
	}

	private void emit(List<PointLight> out, LightDefinition d, float x, float y, float z, int lightPlane, int plane,
		float seed, float time, float night, float camX, float camZ, float maxDistance)
	{
		// Other planes' lights stay hidden unless they are meant to show through (the definition says so) or nothing
		// floors them off: the pit below a gallery, the room under a stairwell (117 HD hides the rest to keep them
		// from leaking through floors)
		if (lightPlane != plane && !d.visibleFromOtherPlanes && (viewRooms == null || !viewRooms.seenFrom(lightPlane, plane, x, z)))
		{
			return;
		}
		float dx = x - camX, dz = z - camZ;
		float reach = maxDistance + d.radius * 1.5f;
		if (dx * dx + dz * dz > reach * reach)
		{
			return;
		}

		float strength = d.strength;
		float radius = d.radius;
		if (d.kind == LightDefinition.FLICKER)
		{
			// same flicker curve as 117 HD: a sum of sharpened cosines at unrelated frequencies
			double t = Math.PI * 2 * ((time % 60) / 60 + seed);
			// integer powers multiplied out: Math.pow is slow, and this runs per flickering light per frame
			double c11 = Math.cos(11 * t), c17 = Math.cos(17 * t), c23 = Math.cos(23 * t);
			double c31 = Math.cos(31 * t), c71 = Math.cos(71 * t), c151 = Math.cos(151 * t);
			double c17s = c17 * c17 * c17, c31s = c31 * c31 * c31, c71s = c71 * c71, c151s = c151 * c151 * c151;
			double f = (c11 * c11 * c11 + c17s * c17s + c23 * c23 + c31s * c31s + c71s * c71s + c151s * c151s / 2) / 4.335;
			float lo = 1 - d.range / 100f, hi = 1 + d.range / 100f;
			strength *= lo + (hi - lo) * (float) f;
			radius *= 1.5f;
		}
		else if (d.kind == LightDefinition.PULSE && d.duration > 0)
		{
			// triangle wave between -range% and +range%
			float phase = (time / (d.duration / 1000f) + seed) % 1f;
			float m = 1 + (2 * (1 - 2 * Math.abs(phase - 0.5f)) - 1) * d.range / 100f;
			strength *= m;
			radius *= m;
		}

		if (d.nightOnly)
		{
			strength *= night;
		}
		strength *= 1 + (d.nightMultiplier - 1) * night;
		if (strength <= 0)
		{
			return;
		}

		if (poolUsed == pool.size())
		{
			pool.add(new PointLight());
		}
		out.add(pool.get(poolUsed++).set(x, y, z, radius, d.red, d.green, d.blue, strength).onPlane(lightPlane));
	}
}
