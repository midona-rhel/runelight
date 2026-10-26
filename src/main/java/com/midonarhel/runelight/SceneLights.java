package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
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
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.client.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Places the lights from lights.json in the scene: on objects (tracked through spawn events, including
 * objects that change appearance through impostors), on NPCs, projectiles and spell effects (followed every
 * frame), and at fixed world positions. Everything runs on the client thread.
 *
 * Each light has a {@link LightLife}: when it spawns and goes, and how it fades. Where the definition has areas the
 * light is made only inside them, and where it has animations only while the parent plays one.
 *
 * Objects without a light in lights.json get one by their name when it is of something that burns or glows
 * (autolights.json, {@link AutoLightRules}). Those come after the others in the frame's lights, so a full budget
 * drops them first.
 */
class SceneLights
{
	private static final Logger log = LoggerFactory.getLogger(SceneLights.class);
	// half a tile, and how far inside it a wall decoration's light is kept (world units)
	private static final float TILE_HALF = 64, TILE_MARGIN = 2;

	private final Client client;

	private final Map<Integer, List<LightDefinition>> byObject = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byNpc = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byProjectile = new HashMap<>();
	private final Map<Integer, List<LightDefinition>> byGraphics = new HashMap<>();
	private final List<LightDefinition> worldDefinitions = new ArrayList<>();
	private int definitionCount;

	private AutoLightRules autoRules;
	// this scan's automatic lights: objects named like a light, and why some got none
	private int autoMatched, autoNoModel, autoNoSpot, autoNearWorld;
	// the rules' light for an object id, null for none
	private final Map<Integer, LightDefinition> autoById = new HashMap<>();
	// for the log: what an automatic light was made for, while the scene is scanned
	private Map<String, Integer> autoTally;

	/**
	 * What became of the scene's lights in one frame, logged a few seconds after the scene loaded: the lights that
	 * should show but don't are in one of these counts.
	 */
	private static final class Funnel
	{
		int hidden, otherPlane, outOfReach, nightOff, mapped, pending, clusters;
		// the strengths of the lights that reach the screen: under 2, 2 to 6, 6 to 15, 15 and up
		final int[] strengths = new int[4];

		void strength(float s)
		{
			++strengths[s < 2 ? 0 : s < 6 ? 1 : s < 15 ? 2 : 3];
		}
	}

	// seconds after a scene loads that its lights are summed up
	private static final float FUNNEL_DELAY = 6;
	// the game time of that frame, NaN before the scene's first frame, then until it has been logged
	private float funnelAt = Float.NaN;
	private boolean funnelPending;
	private Funnel funnel;
	private final ModelData autoModel = new ModelData();
	private final AutoLightPlacement.Spot autoSpot = new AutoLightPlacement.Spot();
	private final float[] autoMove = new float[2];

	/**
	 * A light at a fixed position in the current scene.
	 */
	private static final class Placed
	{
		final LightDefinition def;
		final float x, y, z;
		final int plane;
		final float seed;
		final LightLife life;
		// what it is attached to, null for a world light
		final TileObject object;
		final boolean auto;
		// what the light map bakes of it, null for a light that can't be baked: one that comes and goes with an
		// animation or for a time, or follows the sky
		final LightMap.Source bake;

		Placed(LightDefinition def, float[] at, int plane, float seed, boolean fadesIn, TileObject object, boolean auto)
		{
			this.def = def;
			this.x = at[0];
			this.y = at[1];
			this.z = at[2];
			this.plane = plane;
			this.seed = seed;
			this.life = new LightLife(def, fadesIn);
			this.object = object;
			this.auto = auto;
			float reach = def.kind == LightDefinition.FLICKER ? def.radius * 1.5f : def.radius;
			// a light that reaches further than the bake does would end at its edge: it stays with the clusters
			this.bake = def.outdoorLighting || def.animationSpecific() || def.fixedDespawnTime || reach > LightMapBake.MAX_RADIUS ? null
				: new LightMap.Source(plane, x, y, z, reach, def.red, def.green, def.blue, def.strength, def.nightOnly,
				def.nightMultiplier, object == null ? 0 : VoxelCapture.ownerOf(object));
		}
	}

	private static final class TrackedObject
	{
		int resolvedId;
		boolean hasImpostors;
		List<Placed> lights = Collections.emptyList();
	}

	/**
	 * A light that follows an NPC, a player's or NPC's spell effect, a projectile or a spell effect on the ground.
	 */
	private static final class Live
	{
		final LightDefinition def;
		final LightLife life;
		final float seed;
		final Object parent;
		// of the actor, for the light of its effect; else null
		final ActorSpotAnim spotAnim;
		// the NPC's form the light was made for
		final int npcId;
		float x, y, z;
		int plane;
		// the parent is gone, and the light with it once it fades
		boolean gone;

		Live(LightDefinition def, Object parent, ActorSpotAnim spotAnim, int npcId, float seed, int plane)
		{
			this.def = def;
			this.life = new LightLife(def, true);
			this.parent = parent;
			this.spotAnim = spotAnim;
			this.npcId = npcId;
			this.seed = seed;
			this.plane = plane;
		}
	}

	private final Map<TileObject, TrackedObject> objects = new IdentityHashMap<>();
	private final List<Placed> worldLights = new ArrayList<>();
	// the lights of objects that are gone, fading out
	private final List<Placed> orphans = new ArrayList<>();
	// the lights of what is there now, by what they follow; and of what is gone
	private final Map<Object, List<Live>> attached = new IdentityHashMap<>();
	private final List<Live> fading = new ArrayList<>();
	// the tiles with a world light, where an automatic one would only double it
	private final Set<Long> worldLightTiles = new HashSet<>();
	// this frame's rooms, for the lights of the other planes
	private RoomMap viewRooms;
	// the baked lights' map, null when the static lights are all left to the clusters
	private LightMap lightMap;

	// the frame being collected
	private float frameTime, frameNight, frameSun, frameDt;
	private float camX, camZ, maxDistance;
	private int framePlane, gameCycle;
	private boolean planeChanged;
	private int lastPlane = -1;
	private float lastTime = -1;

	// per-frame pool so collecting lights does not allocate
	private final List<PointLight> pool = new ArrayList<>();
	private int poolUsed;
	// the frame's automatic lights, which go after the others
	private final List<PointLight> autoLights = new ArrayList<>();

	// scratch
	private final float[] at = new float[3];
	private final int[] world = new int[3];
	private final float[] outdoorColor = new float[3];

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
		autoById.clear();

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

		try (InputStream is = SceneLights.class.getResourceAsStream("autolights.json"))
		{
			autoRules = is != null ? new AutoLightRules(gson, is) : null;
		}
		catch (Exception ex)
		{
			log.error("Unable to load autolights.json", ex);
			autoRules = null;
		}
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

	/**
	 * The map the static lights are baked into (null: they stay with the clusters, as before it). The lights it holds
	 * are not added to the frame's lights.
	 */
	void setLightMap(LightMap map)
	{
		if (map != lightMap && lightMap != null)
		{
			lightMap.clear();
		}
		lightMap = map;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Static lights: objects and fixed world positions

	private void clear()
	{
		if (lightMap != null)
		{
			lightMap.clear();
		}
		objects.clear();
		worldLights.clear();
		worldLightTiles.clear();
		orphans.clear();
		attached.clear();
		fading.clear();
	}

	/**
	 * Rescan the whole scene. Called after a scene load and on startup.
	 */
	void rebuild()
	{
		clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		// the lights of a scene start at full strength
		for (LightDefinition d : worldDefinitions)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, d.worldX, d.worldY);
			if (lp == null)
			{
				continue;
			}
			float ground = Perspective.getTileHeight(client, lp, d.plane);
			d.locate(at, lp.getX(), ground, lp.getY(), 0, 1, 1);
			worldLights.add(new Placed(d, at, d.plane, (d.worldX * 31 + d.worldY) * 0.61803398875f % 1f, false, null, false));
			worldLightTiles.add(tileKey(d.plane, lp.getX() >> 7, lp.getY() >> 7));
		}

		autoTally = new TreeMap<>();
		funnelPending = true;
		funnelAt = Float.NaN;
		autoMatched = autoNoModel = autoNoSpot = autoNearWorld = 0;
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
					add(tile.getWallObject(), false);
					add(tile.getDecorativeObject(), false);
					add(tile.getGroundObject(), false);
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject go : gameObjects)
						{
							add(go, false);
						}
					}
				}
			}
		}

		for (Player player : wv.players())
		{
			attachSpotAnims(player);
		}
		for (NPC npc : wv.npcs())
		{
			attachSpotAnims(npc);
		}
		int autoCount = 0;
		for (int n : autoTally.values())
		{
			autoCount += n;
		}
		log.info("Scene lights: {} lights.json objects with lights, {} world lights", countObjectLights() - autoCount,
			worldLights.size());
		log.info("Automatic lights: {} objects named like a light, {} placed, {} without a model, {} without a place, "
				+ "{} next to a 117 HD world light; placed by name {}", autoMatched, autoCount, autoNoModel, autoNoSpot,
			autoNearWorld, autoTally);
		autoTally = null;
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

	private static long tileKey(int plane, int tileX, int tileZ)
	{
		return (long) plane << 48 | (tileX & 0xFFFFFFL) << 24 | tileZ & 0xFFFFFFL;
	}

	private void add(TileObject object, boolean fadesIn)
	{
		if (object == null || objects.containsKey(object))
		{
			return;
		}
		if (object instanceof GameObject && ((GameObject) object).getRenderable() instanceof Actor)
		{
			return;
		}
		TrackedObject t = new TrackedObject();
		ObjectComposition comp = client.getObjectDefinition(object.getId());
		t.hasImpostors = comp != null && comp.getImpostorIds() != null;
		t.resolvedId = resolveId(object.getId(), comp);
		// only remember objects that have, or could switch to having, lights
		if (!t.hasImpostors && !byObject.containsKey(t.resolvedId) && autoLight(object, t.resolvedId) == null)
		{
			return;
		}
		t.lights = placeObjectLights(object, t.resolvedId, fadesIn);
		if (!t.lights.isEmpty() || t.hasImpostors)
		{
			objects.put(object, t);
		}
	}

	private void remove(TileObject object)
	{
		TrackedObject t = objects.remove(object);
		if (t == null)
		{
			return;
		}
		// they fade out and wait their despawn delay
		for (Placed p : t.lights)
		{
			unbake(p);
			if (!p.auto)
			{
				p.life.update(true, 0);
				orphans.add(p);
			}
		}
	}

	/** The light is going: the map lets go of it, and the clusters fade it out. */
	private void unbake(Placed p)
	{
		if (lightMap != null && p.bake != null)
		{
			lightMap.remove(p.bake, true);
		}
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

	private List<Placed> placeObjectLights(TileObject object, int id, boolean fadesIn)
	{
		LocalPoint lp = object.getLocalLocation();
		if (lp == null)
		{
			return Collections.emptyList();
		}
		List<LightDefinition> defs = byObject.get(id);
		if (defs == null)
		{
			Placed auto = placeAuto(object, id, lp, fadesIn);
			return auto != null ? Collections.singletonList(auto) : Collections.emptyList();
		}

		int orientation = 0;
		int sizeX = 1, sizeY = 1;
		float x = lp.getX(), z = lp.getY();
		if (object instanceof GameObject)
		{
			GameObject go = (GameObject) object;
			orientation = go.getOrientation();
			sizeX = go.sizeX();
			sizeY = go.sizeY();
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
			if (!allowedAt(d, x, z, plane))
			{
				continue;
			}
			d.locate(at, x, ground, z, orientation, sizeX, sizeY);
			out.add(new Placed(d, onTile(object, at, lp), plane, System.identityHashCode(object) * 0.61803398875f % 1f,
				fadesIn, object, false));
		}
		return out;
	}

	/**
	 * Whether a light made at this local position may spawn, by its definition's areas.
	 */
	private boolean allowedAt(LightDefinition d, float x, float z, int plane)
	{
		if (d.areas == null && d.excludeAreas == null)
		{
			return true;
		}
		// a position the world has no tile for is in no area
		if (!EnvironmentManager.toWorld(client.getTopLevelWorldView().getScene(), (int) x >> 7, (int) z >> 7, plane, world))
		{
			world[0] = world[1] = world[2] = -1;
		}
		return d.allowedAt(world[0], world[1], world[2]);
	}

	/**
	 * A wall decoration's light stays on the tile the decoration hangs on, which is the side of the wall it faces,
	 * whatever its definition's offsets add to the decoration's own.
	 *
	 * @return at
	 */
	private static float[] onTile(TileObject object, float[] at, LocalPoint tile)
	{
		if (object instanceof DecorativeObject)
		{
			at[0] = Math.max(tile.getX() - TILE_HALF + TILE_MARGIN, Math.min(tile.getX() + TILE_HALF - TILE_MARGIN, at[0]));
			at[2] = Math.max(tile.getY() - TILE_HALF + TILE_MARGIN, Math.min(tile.getY() + TILE_HALF - TILE_MARGIN, at[2]));
		}
		return at;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Automatic lights

	/**
	 * The rules' light for this object, null for none: only game objects and wall decorations get one, and not the
	 * ones with a light in lights.json.
	 */
	private LightDefinition autoLight(TileObject object, int id)
	{
		if (autoRules == null || !(object instanceof GameObject || object instanceof DecorativeObject)
			|| byObject.containsKey(id))
		{
			return null;
		}
		if (!autoById.containsKey(id))
		{
			ObjectComposition comp = client.getObjectDefinition(id);
			autoById.put(id, comp != null ? autoRules.match(comp.getName()) : null);
		}
		return autoById.get(id);
	}

	/**
	 * The automatic light of an object, where its model puts it.
	 */
	private Placed placeAuto(TileObject object, int id, LocalPoint lp, boolean fadesIn)
	{
		LightDefinition d = autoLight(object, id);
		if (d == null)
		{
			return null;
		}
		++autoMatched;
		Renderable renderable;
		if (object instanceof GameObject)
		{
			renderable = ((GameObject) object).getRenderable();
		}
		else
		{
			DecorativeObject deco = (DecorativeObject) object;
			renderable = deco.getRenderable() != null ? deco.getRenderable() : deco.getRenderable2();
		}
		Model model = ModelData.modelOf(renderable);
		if (model == null)
		{
			++autoNoModel;
			return null;
		}
		if (!AutoLightPlacement.locate(autoModel.wrap(model), autoSpot))
		{
			++autoNoSpot;
			return null;
		}

		// the model's place in the scene, as SceneUploader draws it
		AutoLightPlacement.Spot s = autoSpot;
		if (object instanceof GameObject)
		{
			GameObject go = (GameObject) object;
			AutoLightPlacement.turn(s, go.getModelOrientation(), autoMove);
			at[0] = go.getX() + autoMove[0];
			at[1] = go.getZ() + s.y;
			at[2] = go.getY() + autoMove[1];
		}
		else
		{
			DecorativeObject deco = (DecorativeObject) object;
			at[0] = deco.getX() + deco.getXOffset() + s.x;
			at[1] = deco.getZ() + s.y;
			at[2] = deco.getY() + deco.getYOffset() + s.z;
			if (!s.flame)
			{
				AutoLightPlacement.awayFromWall(deco.getConfig(), autoMove);
				at[0] += autoMove[0];
				at[2] += autoMove[1];
			}
			onTile(object, at, lp);
		}

		int plane = object.getPlane();
		// 117 HD lights this place already
		if (nearWorldLight(at[0], at[2], plane))
		{
			++autoNearWorld;
			return null;
		}
		if (autoTally != null)
		{
			autoTally.merge(client.getObjectDefinition(id).getName(), 1, Integer::sum);
		}
		return new Placed(d, at, plane, System.identityHashCode(object) * 0.61803398875f % 1f, fadesIn, object, true);
	}

	private boolean nearWorldLight(float x, float z, int plane)
	{
		int tx = (int) x >> 7, tz = (int) z >> 7;
		for (int dx = -1; dx <= 1; ++dx)
		{
			for (int dz = -1; dz <= 1; ++dz)
			{
				if (worldLightTiles.contains(tileKey(plane, tx + dx, tz + dz)))
				{
					return true;
				}
			}
		}
		return false;
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
			clear();
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		add(e.getGameObject(), true);
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		remove(e.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned e)
	{
		add(e.getWallObject(), true);
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned e)
	{
		remove(e.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned e)
	{
		add(e.getDecorativeObject(), true);
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned e)
	{
		remove(e.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned e)
	{
		add(e.getGroundObject(), true);
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
				for (Placed p : t.lights)
				{
					unbake(p);
				}
				t.lights = placeObjectLights(entry.getKey(), id, true);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Lights that follow something

	private static float seed(int n)
	{
		return n * 0.61803398875f % 1f;
	}

	private void attachNpc(NPC npc, int id, List<LightDefinition> defs)
	{
		List<Live> lives = null;
		for (LightDefinition d : defs)
		{
			if (allowedAtActor(d, npc))
			{
				if (lives == null)
				{
					lives = new ArrayList<>(defs.size());
				}
				lives.add(new Live(d, npc, null, id, seed(npc.getIndex()), framePlane));
			}
		}
		if (lives != null)
		{
			attached.put(npc, lives);
		}
	}

	/**
	 * Lights for the spell effects an actor has that have none yet.
	 */
	private void attachSpotAnims(Actor actor)
	{
		if (byGraphics.isEmpty())
		{
			return;
		}
		for (ActorSpotAnim spotAnim : actor.getSpotAnims())
		{
			List<LightDefinition> defs = byGraphics.get(spotAnim.getId());
			if (defs == null || attached.containsKey(spotAnim))
			{
				continue;
			}
			List<Live> lives = new ArrayList<>(defs.size());
			for (LightDefinition d : defs)
			{
				if (allowedAtActor(d, actor))
				{
					lives.add(new Live(d, actor, spotAnim, -1, seed(System.identityHashCode(spotAnim)), framePlane));
				}
			}
			if (!lives.isEmpty())
			{
				attached.put(spotAnim, lives);
			}
		}
	}

	private boolean allowedAtActor(LightDefinition d, Actor actor)
	{
		LocalPoint lp = actor.getLocalLocation();
		return lp == null || allowedAt(d, lp.getX(), lp.getY(), client.getTopLevelWorldView().getPlane());
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		attachSpotAnims(e.getNpc());
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned e)
	{
		attachSpotAnims(e.getPlayer());
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged e)
	{
		attachSpotAnims(e.getActor());
	}

	/**
	 * Make the lights of the NPCs, projectiles and spell effects that came into the scene since the last frame.
	 */
	private void attachNew(WorldView wv)
	{
		if (!byNpc.isEmpty())
		{
			for (NPC npc : wv.npcs())
			{
				NPCComposition comp = npc.getTransformedComposition();
				int id = comp != null ? comp.getId() : npc.getId();
				List<LightDefinition> defs = byNpc.get(id);
				List<Live> current = attached.get(npc);
				// an NPC that became another has the lights of the other
				if (current != null && (defs == null || current.get(0).npcId != id))
				{
					retire(attached.remove(npc));
					current = null;
				}
				if (defs != null && current == null)
				{
					attachNpc(npc, id, defs);
				}
			}
		}

		if (!byProjectile.isEmpty())
		{
			for (Projectile proj : client.getProjectiles())
			{
				List<LightDefinition> defs = byProjectile.get(proj.getId());
				if (defs == null || proj.getRemainingCycles() <= 0 || attached.containsKey(proj))
				{
					continue;
				}
				List<Live> lives = new ArrayList<>(defs.size());
				for (LightDefinition d : defs)
				{
					if (allowedAt(d, (float) proj.getX(), (float) proj.getY(), framePlane))
					{
						lives.add(new Live(d, proj, null, -1, seed(proj.getStartCycle()), framePlane));
					}
				}
				if (!lives.isEmpty())
				{
					attached.put(proj, lives);
				}
			}
		}

		if (!byGraphics.isEmpty())
		{
			for (GraphicsObject g : wv.getGraphicsObjects())
			{
				List<LightDefinition> defs = byGraphics.get(g.getId());
				LocalPoint lp = defs != null && !g.finished() && !attached.containsKey(g) ? g.getLocation() : null;
				if (lp == null)
				{
					continue;
				}
				List<Live> lives = new ArrayList<>(defs.size());
				for (LightDefinition d : defs)
				{
					if (allowedAt(d, lp.getX(), lp.getY(), g.getLevel()))
					{
						lives.add(new Live(d, g, null, -1, seed(g.getStartCycle()), g.getLevel()));
					}
				}
				if (!lives.isEmpty())
				{
					attached.put(g, lives);
				}
			}
		}
	}

	private void retire(List<Live> lives)
	{
		for (Live l : lives)
		{
			l.gone = true;
		}
		fading.addAll(lives);
	}

	/**
	 * Move a light to where its parent is, and say whether the parent is there (and, for a light that follows an
	 * animation, playing it).
	 */
	private boolean follow(Live l, WorldView wv)
	{
		LightDefinition d = l.def;
		Object parent = l.parent;
		if (parent instanceof Projectile)
		{
			Projectile proj = (Projectile) parent;
			if (proj.getRemainingCycles() <= 0)
			{
				l.gone = true;
				return false;
			}
			l.plane = framePlane;
			// the projectile's z is its height in scene space; 117 HD lifts the light by the definition's height
			l.x = (float) proj.getX();
			l.y = (float) proj.getZ() - d.height;
			l.z = (float) proj.getY();
			if (d.animationSpecific())
			{
				Animation animation = proj.getAnimation();
				return d.matchesAnimation(animation != null ? animation.getId() : -1, gameCycle >= proj.getStartCycle());
			}
			return true;
		}
		if (parent instanceof GraphicsObject)
		{
			GraphicsObject g = (GraphicsObject) parent;
			LocalPoint lp = g.getLocation();
			if (g.finished() || lp == null)
			{
				l.gone = true;
				return false;
			}
			l.x = lp.getX();
			l.y = g.getZ() - d.height;
			l.z = lp.getY();
			if (d.animationSpecific())
			{
				Animation animation = g.getAnimation();
				return d.matchesAnimation(animation != null ? animation.getId() : -1, gameCycle >= g.getStartCycle());
			}
			return true;
		}

		Actor actor = (Actor) parent;
		boolean there = actor instanceof NPC
			? wv.npcs().byIndex(((NPC) actor).getIndex()) == actor
			: wv.players().byIndex(((Player) actor).getId()) == actor;
		LocalPoint lp = there ? actor.getLocalLocation() : null;
		if (lp == null || l.spotAnim != null && !actor.hasSpotAnim(l.spotAnim.getId()))
		{
			l.gone = true;
			return false;
		}
		l.plane = framePlane;
		float ground = Perspective.getTileHeight(client, lp, framePlane);
		d.locate(at, lp.getX(), ground, lp.getY(), actor.getCurrentOrientation(), 1, 1);
		l.x = at[0];
		l.y = at[1];
		l.z = at[2];
		l.life.hide(!d.ignoreActorHiding && hiddenByActor(actor, lp), planeChanged);

		if (l.spotAnim != null)
		{
			return !d.waitForAnimation || gameCycle >= l.spotAnim.getStartCycle();
		}
		if (d.animationSpecific())
		{
			int animation = actor.getAnimation();
			return d.matchesAnimation(animation, animation != -1);
		}
		return true;
	}

	/**
	 * Whether another actor stands where this one does and is the one drawn: a light would show through it.
	 */
	private boolean hiddenByActor(Actor actor, LocalPoint lp)
	{
		if (actor instanceof NPC && ((NPC) actor).getComposition().getSize() > 1)
		{
			return false;
		}
		Tile[][][] tiles = client.getTopLevelWorldView().getScene().getExtendedTiles();
		int ex = (lp.getX() >> 7) + RuneLightPlugin.SCENE_OFFSET, ey = (lp.getY() >> 7) + RuneLightPlugin.SCENE_OFFSET;
		if (framePlane < 0 || framePlane >= tiles.length || ex < 0 || ey < 0 || ex >= tiles[framePlane].length
			|| ey >= tiles[framePlane][ex].length)
		{
			return false;
		}
		Tile tile = tiles[framePlane][ex][ey];
		GameObject[] gameObjects = tile != null ? tile.getGameObjects() : null;
		if (gameObjects == null)
		{
			return false;
		}
		for (GameObject go : gameObjects)
		{
			// only the first actor on the exact spot is drawn
			if (go != null && go.getRenderable() instanceof Actor && go.getRenderable() != actor
				&& go.getX() == lp.getX() && go.getY() == lp.getY())
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Per frame

	/**
	 * Add this frame's visible lights to {@code out}, the ones from lights.json first and the automatic ones after.
	 *
	 * @param time seconds, for flicker and pulse, and how long lights have lived
	 * @param night 0 by day to 1 at night, for night-only lights and night multipliers
	 * @param sunAltitude degrees, for the lights that come in from outside
	 * @param maxDistance only lights whose sphere comes within this distance of the camera (xz) are added
	 * @param rooms the scene's rooms and floors, null when unknown: they decide which lights of the other planes are
	 * seen through an opening
	 */
	void collect(List<PointLight> out, float time, float night, float sunAltitude, float camX, float camZ,
		float maxDistance, RoomMap rooms)
	{
		poolUsed = 0;
		viewRooms = rooms;
		autoLights.clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		frameTime = time;
		frameNight = night;
		frameSun = sunAltitude;
		frameDt = lastTime < 0 ? 0 : Math.max(0, time - lastTime);
		lastTime = time;
		this.camX = camX;
		this.camZ = camZ;
		this.maxDistance = maxDistance;
		framePlane = wv.getPlane();
		planeChanged = framePlane != lastPlane;
		lastPlane = framePlane;
		gameCycle = client.getGameCycle();
		if (funnelPending)
		{
			if (Float.isNaN(funnelAt))
			{
				funnelAt = time + FUNNEL_DELAY;
			}
			else if (time >= funnelAt)
			{
				funnel = new Funnel();
			}
		}

		for (TrackedObject t : objects.values())
		{
			for (Placed p : t.lights)
			{
				emitFixed(out, p, true);
			}
		}
		for (Placed p : worldLights)
		{
			emitFixed(out, p, true);
		}
		for (Iterator<Placed> it = orphans.iterator(); it.hasNext(); )
		{
			Placed p = it.next();
			emitFixed(out, p, false);
			if (p.life.over(true))
			{
				it.remove();
			}
		}

		attachNew(wv);
		for (Iterator<List<Live>> it = attached.values().iterator(); it.hasNext(); )
		{
			List<Live> lives = it.next();
			for (Live l : lives)
			{
				advance(out, l, follow(l, wv));
			}
			if (lives.get(0).gone)
			{
				fading.addAll(lives);
				it.remove();
			}
		}
		for (Iterator<Live> it = fading.iterator(); it.hasNext(); )
		{
			Live l = it.next();
			advance(out, l, false);
			if (l.life.over(true))
			{
				it.remove();
			}
		}

		int room = LightClusters.MAX_LIGHTS - out.size();
		appendNearest(out, autoLights, room, camX, camZ);
		if (funnel != null)
		{
			logFunnel(funnel, room, out.size());
			funnel = null;
			funnelPending = false;
		}
	}

	private void logFunnel(Funnel f, int room, int total)
	{
		int objectLights = countObjectLights() - countAuto();
		log.info("Light pipeline: {} lights.json object lights + {} world lights + {} automatic; "
				+ "{} hidden (fading or waiting for their animation), {} on another plane, {} out of reach, "
				+ "{} night-only and off now (night {}); {} in the light map, {} waiting for it, {} on the clusters; "
				+ "strengths of those showing: {} under 2, {} 2 to 6, {} 6 to 15, {} 15 and up; "
				+ "clusters {}/{} used, {} automatic lights over budget",
			objectLights, worldLights.size(), countAuto(), f.hidden, f.otherPlane, f.outOfReach, f.nightOff, frameNight,
			f.mapped, f.pending, f.clusters, f.strengths[0], f.strengths[1], f.strengths[2], f.strengths[3], total,
			LightClusters.MAX_LIGHTS, Math.max(0, autoLights.size() - Math.max(0, room)));
	}

	/**
	 * Every light of the scene as it is placed, for the scene dump: the fixed ones (on objects, in the world, fading
	 * out) with the owner and whether the light map bakes them, then the ones that follow an actor, a projectile or an
	 * effect.
	 */
	void write(DataOutputStream out) throws IOException
	{
		List<Placed> fixed = new ArrayList<>(worldLights);
		fixed.addAll(orphans);
		for (TrackedObject t : objects.values())
		{
			fixed.addAll(t.lights);
		}
		List<Live> live = new ArrayList<>();
		for (List<Live> l : attached.values())
		{
			live.addAll(l);
		}
		live.addAll(fading);
		out.writeInt(fixed.size() + live.size());
		for (Placed p : fixed)
		{
			write(out, p.def, p.plane, p.x, p.y, p.z, p.object == null ? 0 : VoxelCapture.ownerOf(p.object),
				p.bake != null, p.auto);
		}
		for (Live l : live)
		{
			write(out, l.def, l.plane, l.x, l.y, l.z, 0, false, false);
		}
	}

	private static void write(DataOutputStream out, LightDefinition d, int plane, float x, float y, float z, int owner,
		boolean baked, boolean auto) throws IOException
	{
		out.writeUTF(d.description == null ? "" : d.description);
		out.writeInt(plane);
		out.writeFloat(x);
		out.writeFloat(y);
		out.writeFloat(z);
		out.writeInt(owner);
		out.writeBoolean(baked);
		out.writeBoolean(auto);
		out.writeInt(d.kind);
		out.writeFloat(d.radius);
		out.writeFloat(d.red);
		out.writeFloat(d.green);
		out.writeFloat(d.blue);
		out.writeFloat(d.strength);
		out.writeFloat(d.duration);
		out.writeFloat(d.range);
		out.writeBoolean(d.nightOnly);
		out.writeFloat(d.nightMultiplier);
		out.writeBoolean(d.outdoorLighting);
		out.writeBoolean(d.animationSpecific());
		out.writeBoolean(d.fixedDespawnTime);
	}

	private int countAuto()
	{
		int n = 0;
		for (TrackedObject t : objects.values())
		{
			for (Placed p : t.lights)
			{
				if (p.auto)
				{
					++n;
				}
			}
		}
		return n;
	}

	/**
	 * Add the nearest of the candidates to the lights, at most room of them, to leave the budget to the lights
	 * already there.
	 */
	static void appendNearest(List<PointLight> out, List<PointLight> candidates, int room, float camX, float camZ)
	{
		if (room <= 0)
		{
			return;
		}
		if (candidates.size() > room)
		{
			candidates.sort((a, b) -> Float.compare(distanceSquared(a, camX, camZ), distanceSquared(b, camX, camZ)));
		}
		out.addAll(candidates.subList(0, Math.min(room, candidates.size())));
	}

	private static float distanceSquared(PointLight l, float camX, float camZ)
	{
		float dx = l.x - camX, dz = l.z - camZ;
		return dx * dx + dz * dz;
	}

	private void emitFixed(List<PointLight> out, Placed p, boolean parentExists)
	{
		p.life.update(parentExists && (!p.def.animationSpecific() || playing(p)), frameDt);
		if (!p.life.visible())
		{
			if (funnel != null)
			{
				++funnel.hidden;
			}
			return;
		}
		List<PointLight> target = p.auto ? autoLights : out;
		int n = target.size();
		// a light that has faded in and stays is the map's; fading or going, the clusters have it
		LightMap.Source bake = lightMap != null && parentExists && p.life.brightness() >= 1 ? p.bake : null;
		emit(target, p.def, p.life, p.x, p.y, p.z, p.plane, p.seed, bake);
		if (target.size() > n)
		{
			target.get(n).fixed();
		}
	}

	/**
	 * Whether the object plays an animation its light is for.
	 */
	private static boolean playing(Placed p)
	{
		int animation = -1;
		if (p.object instanceof GameObject)
		{
			Renderable renderable = ((GameObject) p.object).getRenderable();
			Animation playing = renderable instanceof DynamicObject ? ((DynamicObject) renderable).getAnimation() : null;
			animation = playing != null ? playing.getId() : -1;
		}
		return p.def.matchesAnimation(animation, animation != -1);
	}

	private void advance(List<PointLight> out, Live l, boolean parentExists)
	{
		l.life.update(parentExists, frameDt);
		if (l.life.visible())
		{
			emit(out, l.def, l.life, l.x, l.y, l.z, l.plane, l.seed, null);
		}
	}

	/**
	 * @param bake the light's place in the light map, null for a light that isn't on it: with it, the light is baked
	 * once it is in reach; and while the map carries it, only its strength (the table) is kept up to date
	 */
	private void emit(List<PointLight> out, LightDefinition d, LightLife life, float x, float y, float z, int lightPlane,
		float seed, LightMap.Source bake)
	{
		boolean mapped = bake != null && LightMap.isLive(bake);
		// Other planes' lights stay hidden unless they are meant to show through (the definition says so) or nothing
		// floors them off: the pit below a gallery, the room under a stairwell (117 HD hides the rest to keep them
		// from leaking through floors). The map knows what the floors let through.
		if (!mapped && lightPlane != framePlane && !d.visibleFromOtherPlanes
			&& (viewRooms == null || !viewRooms.seenFrom(lightPlane, framePlane, x, z)))
		{
			if (funnel != null)
			{
				++funnel.otherPlane;
			}
			return;
		}
		float dx = x - camX, dz = z - camZ;
		float reach = maxDistance + d.radius * 1.5f;
		if (dx * dx + dz * dz > reach * reach)
		{
			if (funnel != null)
			{
				++funnel.outOfReach;
			}
			float far = reach + LightMap.EVICT_MARGIN;
			if (bake != null && dx * dx + dz * dz > far * far)
			{
				lightMap.remove(bake, false);
			}
			return;
		}
		if (bake != null)
		{
			lightMap.add(bake);
		}

		float strength = d.strength;
		float radius = d.radius;
		if (d.kind == LightDefinition.FLICKER)
		{
			// same flicker curve as 117 HD: a sum of sharpened cosines at unrelated frequencies
			double t = Math.PI * 2 * ((frameTime % 60) / 60 + seed);
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
			float phase = (frameTime / (d.duration / 1000f) + seed) % 1f;
			float m = 1 + (2 * (1 - 2 * Math.abs(phase - 0.5f)) - 1) * d.range / 100f;
			strength *= m;
			radius *= m;
		}

		if (d.nightOnly)
		{
			strength *= frameNight;
		}
		strength *= 1 + (d.nightMultiplier - 1) * frameNight;
		strength *= life.brightness();
		if (funnel != null)
		{
			if (d.nightOnly && frameNight <= 0)
			{
				++funnel.nightOff;
			}
			else if (mapped)
			{
				++funnel.mapped;
				funnel.strength(strength);
			}
			else if (bake != null)
			{
				++funnel.pending;
			}
		}
		if (mapped)
		{
			lightMap.setLevel(bake, strength);
			return;
		}
		float red = d.red, green = d.green, blue = d.blue;
		if (d.outdoorLighting)
		{
			strength *= OutdoorLight.apply(frameSun, d.red, d.green, d.blue, outdoorColor);
			red = outdoorColor[0];
			green = outdoorColor[1];
			blue = outdoorColor[2];
		}
		if (strength <= 0)
		{
			return;
		}

		if (poolUsed == pool.size())
		{
			pool.add(new PointLight());
		}
		if (funnel != null)
		{
			++funnel.clusters;
			funnel.strength(strength);
		}
		out.add(pool.get(poolUsed++).set(x, y, z, radius, red, green, blue, strength).onPlane(lightPlane));
	}
}
