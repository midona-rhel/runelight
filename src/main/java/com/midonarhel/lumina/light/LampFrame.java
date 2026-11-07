package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.WallMap;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.RoomTexture;
import com.midonarhel.lumina.scene.EdgeFade;
import com.midonarhel.lumina.dev.CpuTimer;
import net.runelite.api.Player;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import static org.lwjgl.opengl.GL33C.*;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The lamps' work each frame: lamp collection and placement, the room light, the light clusters' upload. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class LampFrame
{
	private final LuminaPlugin plugin;
	public final com.midonarhel.lumina.dev.PlayerLightTrace playerLightTrace = new com.midonarhel.lumina.dev.PlayerLightTrace();

	/** Legacy replay option; the runtime no longer bakes point lighting. */
	@Deprecated public boolean lightMapOff = true;
	// the walls' scene (WallMap.scene, the same as a door opens or shuts) of the newest rooms BuildingLights gave, and of
	// those the room light has: rooms of the same scene as the ones before them are a door's (RoomLight.setRooms)
	private RoomMap newestRooms, roomLightRooms;
	private int newestScene = Integer.MIN_VALUE, roomLightScene = Integer.MIN_VALUE;

	public LampFrame(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Bind the shadow map for the composite and the fog, once it is drawn. */
	public void prepareLighting()
	{
		if (plugin.frameShadows && plugin.shadowMap.isInitialized())
		{
			plugin.shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		}
	}

	/**
	 * Gather, cull, upload and bind this frame's lamps, the walls that stop them and the rooms, before the scene is
	 * drawn: for its transparent faces, the composite, the fog, the weather and the water.
	 */
	public void prepareLamps()
	{
		boolean points = plugin.config.pointLights();
		BuildingLights buildings = plugin.buildingLights;
		RoomMap rooms = buildings != null ? buildings.getRooms() : null;
		plugin.volumetricFog.rooms = rooms;
		plugin.volumetricFog.openRooms = null;
		plugin.framePointLights = points;
		plugin.frameLightClusters = points || rooms != null;
		if (plugin.frameLightClusters)
		{
			if (!plugin.lightClusters.isInitialized())
			{
				plugin.lightClusters.init();
			}
			plugin.cpuTimer.begin(CpuTimer.LIGHTS);
			if (points)
			{
				collectLights();
			}
			else
			{
				plugin.frameLights.clear();
			}

			plugin.cpuTimer.end(CpuTimer.LIGHTS);
			// Legacy wall data supplies wall-mounted lamp origins; LampBlockers owns visibility.
			SceneContext drawn = plugin.root;
			plugin.lampWalls.update(points && buildings != null ? buildings.getWalls() : null, rooms,
				buildings != null ? buildings.getFloors() : null, buildings != null ? buildings.getRoofs() : null,
				drawn != null ? drawn.hideRoofIds : null, drawn != null ? drawn.level : 0);
			plugin.lightClusters.setWalls(plugin.lampWalls);
			plugin.roomTexture.update(rooms);
			// the fill by room id, for the rooms whose ids the shader reads now (a door's are packed a frame later)
			updateRoomLight(plugin.config.interiorBrightness() > 0 ? plugin.roomTexture.shown() : null);
			// the fog comes back into the rooms whose roof the client hides
			SceneContext roofCtx = drawn;
			if (plugin.frameFog && rooms != null && roofCtx != null)
			{
				plugin.openRooms.update(buildings.getResult(), roofCtx.hideRoofIds, roofCtx.level);
				plugin.volumetricFog.openRooms = plugin.openRooms.count() > 0 ? plugin.openRooms : null;
			}
			plugin.cpuTimer.begin(CpuTimer.CLUSTERS);
			// the fog integrates the lights along each pixel's ray from per-column lists
			plugin.lightClusters.setFog(plugin.frameFog, plugin.volumetricFog.getCenterX() - plugin.frameCameraX,
				plugin.volumetricFog.getCenterZ() - plugin.frameCameraZ, plugin.volumetricFog.getRadius(),
				plugin.volumetricFog.getTop() - plugin.frameCameraY, plugin.volumetricFog.getMinGlow());
			plugin.lightClusters.cull(plugin.frameLights, plugin.frameViewProj, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ);
			if (plugin.lightClusters.getOverflows() > 0 && plugin.logLimiter.check("lightClusterOverflow") >= 0)
			{
				// each one left its least important lights out there (LightClusters.MAX_LIGHTS_PER_CLUSTER)
				log.info("Light clusters: {} lamp listings over the cap of {} a cluster", plugin.lightClusters.getOverflows(),
					LightClusters.MAX_LIGHTS_PER_CLUSTER);
			}
			plugin.cpuTimer.end(CpuTimer.CLUSTERS);
			plugin.cpuTimer.begin(CpuTimer.LIGHT_UPLOAD);
			plugin.lightClusters.upload();
			plugin.cpuTimer.end(CpuTimer.LIGHT_UPLOAD);
			plugin.lightClusters.bind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT);
			if (points)
			{
				plugin.lampBlockers.update(plugin.root);
				plugin.lampBlockers.bind();
			}
			plugin.roomTexture.bind(ROOM_TEXELS_UNIT);
		}
		if (playerLightTrace.enabled())
		{
			plugin.lightClusters.tracePlayerLight(playerLightTrace, plugin.testLight, plugin.frameLights,
				points, plugin.config.testLight(), plugin.lampWalls.version(), plugin.frameViewProj,
				plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ);
		}
	}

	/**
	 * Work out the fill the rooms are given for the time of day, and hand it to the light buffer. Caves and dungeons
	 * have no buildings, and frameOutdoors stands in until areas are classified.
	 */
	public void updateRoomLight(RoomMap rooms)
	{
		long now = com.midonarhel.lumina.common.Clock.nanos();
		float dt = plugin.roomLightNanos == 0 ? 0 : Math.min((now - plugin.roomLightNanos) / 1e9f, 0.5f);
		plugin.roomLightNanos = now;
		BuildingLights buildings = plugin.buildingLights;
		// BuildingLights hands rooms and walls over together; the rooms here may be a frame behind (RoomTexture.shown)
		RoomMap newest = buildings != null ? buildings.getRooms() : null;
		if (newest != newestRooms)
		{
			WallMap walls = buildings.getWalls();
			newestRooms = newest;
			newestScene = walls != null ? walls.scene : Integer.MIN_VALUE;
		}
		int scene = rooms == null ? Integer.MIN_VALUE : rooms == newestRooms ? newestScene
			: rooms == roomLightRooms ? roomLightScene : Integer.MIN_VALUE;
		plugin.roomLight.setRooms(rooms, rooms != roomLightRooms && scene != Integer.MIN_VALUE && scene == roomLightScene);
		roomLightRooms = rooms;
		roomLightScene = scene;
		// one level for every room, from the setting and the time of day: not from its lamps or its sky
		float target = plugin.frameOutdoors ? RoomLight.target(plugin.dayCycle.sunAltitude, plugin.config.interiorBrightness() / 100f) : 0;
		plugin.roomLight.update(dt, target);
		plugin.lightClusters.setRoomFill(rooms != null ? plugin.roomLight : null);
	}

	/**
	 * Gather imported scene lights and the optional developer light carried by the player.
	 */
	public void collectLights()
	{
		plugin.frameLights.clear();
		// 0 by day, 1 once the sun is well below the horizon
		float t = Math.max(0, Math.min(1, (-2 - plugin.dayCycle.sunAltitude) / 8));
		float night = plugin.frameOutdoors ? t * t * (3 - 2 * t) : 1;
		SceneLights lights = plugin.sceneLights;
		if (lights != null)
		{
			float time = (com.midonarhel.lumina.common.Clock.millis() - plugin.startMillis) / 1000f;
			// around the focal point like the drawn world (EdgeFade), so lights don't come and go as the camera turns
			lights.collect(plugin.frameLights, time, night, plugin.dayCycle.sunAltitude, plugin.edgeFade.focalX, plugin.edgeFade.focalZ,
				plugin.edgeFade.radius);
			PointLight.dropDoubles(plugin.frameLights);
		}
		if (plugin.config.testLight())
		{
			Player player = plugin.client.getLocalPlayer();
			LocalPoint lp = player != null ? player.getLocalLocation() : null;
			if (lp != null)
			{
				float ground = Perspective.getTileHeight(plugin.client, lp, plugin.client.getTopLevelWorldView().getPlane());
				// Torso height keeps the carried light below doorway lintels; reach is about five tiles.
				plugin.frameLights.add(plugin.testLight.set(lp.getX(), ground - 150, lp.getY(), 5 * Perspective.LOCAL_TILE_SIZE,
					1.0f, 0.72f, 0.42f, 6f).onPlane(plugin.client.getTopLevelWorldView().getPlane()));
			}
		}
	}
}
