package com.midonarhel.lumina.light;

import com.midonarhel.lumina.common.LogLimiter;
import com.midonarhel.lumina.visibility.VisibilityFrame;
import com.midonarhel.lumina.sky.SkyFrame;
import com.midonarhel.lumina.weather.WeatherFrame;
import com.midonarhel.lumina.common.Mat4;
import com.midonarhel.lumina.common.Rumble;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.common.ShaderException;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.OpenRooms;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.RoomTexture;
import com.midonarhel.lumina.visibility.SkyFieldMap;
import com.midonarhel.lumina.visibility.SkyGridMap;
import com.midonarhel.lumina.visibility.VoxelBuilder;
import com.midonarhel.lumina.fog.VolumetricFog;
import com.midonarhel.lumina.water.Water;
import com.midonarhel.lumina.water.WaterOverrides;
import com.midonarhel.lumina.water.WaterSurface;
import com.midonarhel.lumina.frame.InterfaceUpload;
import com.midonarhel.lumina.frame.Composite;
import com.midonarhel.lumina.frame.FramePacer;
import com.midonarhel.lumina.frame.Programs;
import com.midonarhel.lumina.frame.UiBlit;
import com.midonarhel.lumina.frame.SceneTargets;
import com.midonarhel.lumina.frame.Upscaler;
import com.midonarhel.lumina.scene.AreaHiding;
import com.midonarhel.lumina.scene.EdgeFade;
import com.midonarhel.lumina.scene.GLBuffer;
import com.midonarhel.lumina.scene.GpuFloatBuffer;
import com.midonarhel.lumina.scene.GroundBlender;
import com.midonarhel.lumina.scene.Interactables;
import com.midonarhel.lumina.scene.ModelUploader;
import com.midonarhel.lumina.scene.RegionManager;
import com.midonarhel.lumina.scene.SceneJobs;
import com.midonarhel.lumina.scene.SceneLoadReport;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.SceneUploader;
import com.midonarhel.lumina.scene.SurfaceMaterials;
import com.midonarhel.lumina.scene.TempModels;
import com.midonarhel.lumina.scene.TextureManager;
import com.midonarhel.lumina.scene.VAO;
import com.midonarhel.lumina.scene.VAOList;
import com.midonarhel.lumina.scene.VertexShading;
import com.midonarhel.lumina.scene.WindSway;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.ZonePool;
import com.midonarhel.lumina.shadow.ShadowMap;
import com.midonarhel.lumina.shadow.ShadowPass;
import com.midonarhel.lumina.weather.Climate;
import com.midonarhel.lumina.weather.WadeSteps;
import com.midonarhel.lumina.weather.Weather;
import com.midonarhel.lumina.weather.WeatherAudio;
import com.midonarhel.lumina.weather.WeatherData;
import com.midonarhel.lumina.weather.WeatherField;
import com.midonarhel.lumina.weather.WeatherGround;
import com.midonarhel.lumina.weather.Wind;
import com.midonarhel.lumina.sky.Clouds;
import com.midonarhel.lumina.sky.DayCycle;
import com.midonarhel.lumina.sky.EnvironmentManager;
import com.midonarhel.lumina.sky.EnvironmentUniforms;
import com.midonarhel.lumina.sky.GameClock;
import com.midonarhel.lumina.sky.Lightning;
import com.midonarhel.lumina.sky.NightSky;
import com.midonarhel.lumina.sky.SkyCache;
import com.midonarhel.lumina.dev.BuildingOverlay;
import com.midonarhel.lumina.dev.CommandFile;
import com.midonarhel.lumina.dev.Commands;
import com.midonarhel.lumina.dev.CpuTimer;
import com.midonarhel.lumina.dev.FrameCapture;
import com.midonarhel.lumina.dev.FrameSnapshot;
import com.midonarhel.lumina.dev.HitchMonitor;
import com.midonarhel.lumina.dev.LookMonitor;
import com.midonarhel.lumina.dev.ShaderWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import net.runelite.api.BufferProvider;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Preferences;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.DebugView;
import com.midonarhel.lumina.config.UIScalingMode;
import com.midonarhel.lumina.config.WeatherMode;
import com.midonarhel.lumina.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The lamps' work each frame: lamp collection and placement, the room light, the light clusters' upload. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class LampFrame
{
	private final LuminaPlugin plugin;

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
			// the roofs as drawn: those the client hides over the player stop nothing
			SceneContext drawn = plugin.root;
			plugin.lampWalls.update(points && buildings != null ? buildings.getWalls() : null, rooms,
				buildings != null ? buildings.getFloors() : null, buildings != null ? buildings.getRoofs() : null,
				drawn != null ? drawn.hideRoofIds : null, drawn != null ? drawn.level : 0);
			plugin.lightClusters.setWalls(plugin.lampWalls);
			plugin.roomTexture.update(rooms);
			updateRoomLight(plugin.config.interiorBrightness() > 0 ? rooms : null);
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
			plugin.roomTexture.bind(ROOM_TEXELS_UNIT);
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
		SceneLights lights = plugin.sceneLights;
		plugin.roomLight.setRooms(rooms);
		if (rooms != null && buildings != null)
		{
			plugin.roomLight.followWalls(buildings.getWalls(), buildings.getFloors(), SKYLIGHT_REACH);
			// the rooms' lamps are the fixed ones, as they are now; with no lamps on, none
			plugin.roomLight.followLamps(out ->
			{
				if (plugin.framePointLights && lights != null)
				{
					lights.steadyLamps(out);
					PointLight.dropDoubles(out);
				}
			}, buildings.getWalls(), dt);
		}
		float target = plugin.frameOutdoors ? RoomLight.target(plugin.dayCycle.sunAltitude, plugin.config.interiorBrightness() / 100f) : 0;
		plugin.roomLight.update(dt, target, plugin.dayCycle.sunAltitude);
		plugin.lightClusters.setRoomFill(rooms != null ? plugin.roomLight : null);
	}

	/**
	 * Gather this frame's point lights: scene lights from lights.json, plus the optional test light.
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
				// a little above head height (y points down), warm like a torch, reaching about 5 tiles
				plugin.frameLights.add(plugin.testLight.set(lp.getX(), ground - 250, lp.getY(), 5 * Perspective.LOCAL_TILE_SIZE,
					1.0f, 0.72f, 0.42f, 6f).onPlane(plugin.client.getTopLevelWorldView().getPlane()));
			}
		}
	}
}
