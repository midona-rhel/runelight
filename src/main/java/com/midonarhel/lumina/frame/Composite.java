package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.common.LogLimiter;
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
import com.midonarhel.lumina.dev.CpuTimer;
import com.midonarhel.lumina.dev.FrameCapture;
import com.midonarhel.lumina.dev.FrameSnapshot;
import com.midonarhel.lumina.dev.HitchMonitor;
import com.midonarhel.lumina.dev.LookMonitor;
import com.midonarhel.lumina.dev.ShaderWatcher;
import com.midonarhel.lumina.light.LampWalls;
import com.midonarhel.lumina.light.LightClusters;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.light.RoomLight;
import com.midonarhel.lumina.light.SceneLights;
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
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The composite: the full-screen pass that lights the scene from the targets (and its half-resolution clouds/water
 * variants and the MSAA edge passes), one program per pass sharing uniforms set before each draws. Split out of the
 * plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class Composite
{
	private final LuminaPlugin plugin;

	public Composite(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	// The composite (composite_frag.glsl) is compiled once for each of its passes, so that each program holds only its
	// own path: a GPU gives a program the registers of its heaviest path, and the full resolution composite paid for
	// the cloud march and the water's reflections. The passes share their uniforms, set on each before it draws
	// (useComposite).
	public static final int COMPOSITE_FULL = 0, COMPOSITE_CLOUDS = 1, COMPOSITE_WATER = 2, COMPOSITE_CLOUDS_WATER = 3,
		COMPOSITE_EDGES = 4, COMPOSITE_MARK = 5;

	/** One of the composite's programs and its uniforms. */
	public static final class CompositeProgram
	{
		public final int pass;
		public int program;
		public int part, aoEnabled, aoStrength;
		public int cameraPos, time, outdoors, proceduralSky, lightingEnabled, sunDir, moonDir, casterDir, sunIsCaster,
			strikeLight, sunAltitude, overcast, weatherLight;
		public int shadowsEnabled, lightProjRel, shadowRadius, shadowDepthRange, shadowMapSize;
		public int pointLights, roomHeader, interiorTint, skylightOn;
		public final LampWalls.Uniforms lampWalls = new LampWalls.Uniforms();
		public int skyVoid, drawDistance, expandedChunks, clientSkyColor;
		public int lightningBolt, volumetricFog;
		public final FrameUniforms frame = new FrameUniforms();
		public final EnvironmentUniforms env = new EnvironmentUniforms();
		public final EdgeFade.Uniforms edge = new EdgeFade.Uniforms();
		public final SurfaceMaterials.Uniforms materials = new SurfaceMaterials.Uniforms();
		public final Clouds.Locations clouds = new Clouds.Locations();
		public final Water.CompositeUniforms water = new Water.CompositeUniforms();
		public final SkyCache.CompositeUniforms skyCache = new SkyCache.CompositeUniforms();
		public final NightSky.Uniforms night = new NightSky.Uniforms();
		public final WeatherGround.CompositeUniforms ground = new WeatherGround.CompositeUniforms();

		public CompositeProgram(int pass)
		{
			this.pass = pass;
		}
	}

	public final CompositeProgram compositeFull = new CompositeProgram(COMPOSITE_FULL);
	public final CompositeProgram compositeClouds = new CompositeProgram(COMPOSITE_CLOUDS);
	public final CompositeProgram compositeWater = new CompositeProgram(COMPOSITE_WATER);
	public final CompositeProgram compositeCloudsWater = new CompositeProgram(COMPOSITE_CLOUDS_WATER);
	// with MSAA, the second surface of the pixels that have two, blended over the full pass
	public final CompositeProgram compositeEdges = new CompositeProgram(COMPOSITE_EDGES);
	// with MSAA, marks the pixels that have a second surface in the stencil, so the edge pass runs on those alone
	public final CompositeProgram compositeMark = new CompositeProgram(COMPOSITE_MARK);
	public final CompositeProgram[] composites = {compositeFull, compositeClouds, compositeWater, compositeCloudsWater,
		compositeEdges, compositeMark};

	public void locate(CompositeProgram c)
	{
		int p = c.program;
		c.part = glGetUniformLocation(p, "compositePart");
		c.aoEnabled = glGetUniformLocation(p, "aoEnabled");
		c.aoStrength = glGetUniformLocation(p, "aoStrength");
		c.cameraPos = glGetUniformLocation(p, "cameraPos");
		c.time = glGetUniformLocation(p, "time");
		c.outdoors = glGetUniformLocation(p, "outdoors");
		c.proceduralSky = glGetUniformLocation(p, "proceduralSky");
		c.lightingEnabled = glGetUniformLocation(p, "lightingEnabled");
		c.sunDir = glGetUniformLocation(p, "sunDir");
		c.moonDir = glGetUniformLocation(p, "moonDir");
		c.casterDir = glGetUniformLocation(p, "casterDir");
		c.sunIsCaster = glGetUniformLocation(p, "sunIsCaster");
		c.strikeLight = glGetUniformLocation(p, "strikeLight");
		c.sunAltitude = glGetUniformLocation(p, "sunAltitude");
		c.overcast = glGetUniformLocation(p, "weatherOvercast");
		c.weatherLight = glGetUniformLocation(p, "weatherLight");
		c.shadowsEnabled = glGetUniformLocation(p, "shadowsEnabled");
		c.lightProjRel = glGetUniformLocation(p, "lightProjRel");
		c.shadowRadius = glGetUniformLocation(p, "shadowRadius");
		c.shadowDepthRange = glGetUniformLocation(p, "shadowDepthRange");
		c.shadowMapSize = glGetUniformLocation(p, "shadowMapSize");
		c.pointLights = glGetUniformLocation(p, "pointLightsEnabled");
		c.roomHeader = glGetUniformLocation(p, "roomHeader");
		c.lampWalls.locate(p);
		c.interiorTint = glGetUniformLocation(p, "interiorTint");
		c.skylightOn = glGetUniformLocation(p, "skylightOn");
		c.skyVoid = glGetUniformLocation(p, "skyVoid");
		c.drawDistance = glGetUniformLocation(p, "drawDistance");
		c.expandedChunks = glGetUniformLocation(p, "expandedMapLoadingChunks");
		c.clientSkyColor = glGetUniformLocation(p, "clientSkyColor");
		c.lightningBolt = Lightning.locateComposite(p);
		c.volumetricFog = VolumetricFog.locateComposite(p, FOG_TEXTURE_UNIT);
		c.frame.locate(p);
		c.env.locate(p);
		c.edge.locate(p);
		c.materials.locate(p);
		c.ground.locate(p);
		Clouds.locate(c.clouds, p, CLOUD_NOISE_UNIT, WEATHER_SKY_UNIT);
		c.water.locate(p, WATER_DATA_UNIT, WATER_NOISE_UNIT);
		c.skyCache.locate(p, SKY_CACHE_UNIT);
		c.night.locate(p);
		bindSceneSamplers(p);
		glUseProgram(p);
		glUniform1i(glGetUniformLocation(p, "sceneAo"), AO_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneShadow"), SCENE_SHADOW_UNIT);
		glUniform1i(glGetUniformLocation(p, "compositeParts"), COMPOSITE_PARTS_UNIT);
		glUniform1i(glGetUniformLocation(p, "shadowCompare"), SHADOW_COMPARE_UNIT);
		glUniform1i(glGetUniformLocation(p, "lightData"), LIGHT_DATA_UNIT);
		glUniform1i(glGetUniformLocation(p, "clusterData"), CLUSTER_DATA_UNIT);
		glUniform1i(glGetUniformLocation(p, "roomTexels"), ROOM_TEXELS_UNIT);
		glUniform1i(glGetUniformLocation(p, "cloudHalf"), CLOUD_HALF_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneAlpha"), ALPHA_LAYER_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneMaterial"), MATERIAL_UNIT);
		glUseProgram(0);
	}

	/**
	 * Use one of the composite's programs and set this frame's uniforms on it: the same for each, whatever it uses.
	 * After prepareLighting and the sky cache's easeComposite.
	 */
	private void use(CompositeProgram c)
	{
		glUseProgram(c.program);
		plugin.frameState.setFrameUniforms(c.frame);
		plugin.skyCache.setComposite(c.skyCache, plugin.weather.skyHaze(), plugin.config.starBrightness() / 100f);
		plugin.nightSky.set(c.night, plugin.config.nebulaStrength() / 100f);
		glUniform1i(c.aoEnabled, plugin.config.ambientOcclusion() ? 1 : 0);
		glUniform1f(c.aoStrength, plugin.config.aoStrength() / 100f);

		glUniform3f(c.cameraPos, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ);
		glUniform1f(c.time, plugin.compositeTime);
		glUniform1i(c.outdoors, plugin.frameOutdoors ? 1 : 0);
		glUniform1i(c.proceduralSky, plugin.proceduralSky() ? 1 : 0);
		glUniform1i(c.skyVoid, plugin.frameSkyVoid ? 1 : 0);
		glUniform1i(c.lightingEnabled, 1);
		glUniform3fv(c.sunDir, plugin.dayCycle.sunDir);
		glUniform3fv(c.moonDir, plugin.dayCycle.moonDir);
		glUniform3fv(c.casterDir, plugin.dayCycle.casterDir);
		glUniform1i(c.sunIsCaster, plugin.dayCycle.sunIsCaster ? 1 : 0);
		glUniform2fv(c.strikeLight, plugin.dayCycle.strikeLight);
		glUniform1f(c.sunAltitude, plugin.dayCycle.sunAltitude);
		plugin.weather.setOvercastUniform(c.overcast);
		plugin.weather.setLightUniform(c.weatherLight);
		plugin.skyFrame.setEnvironmentUniforms(c.env);

		boolean shadows = plugin.frameShadows && plugin.shadowMap.isInitialized();
		glUniform1i(c.shadowsEnabled, shadows ? 1 : 0);
		if (shadows)
		{
			glUniformMatrix4fv(c.lightProjRel, false, plugin.shadowMap.lightProjRel);
			glUniform1f(c.shadowRadius, plugin.shadowMap.getRadius());
			glUniform1f(c.shadowDepthRange, plugin.shadowMap.getDepthRange());
			glUniform1f(c.shadowMapSize, plugin.shadowMap.getSize());
		}

		if (plugin.frameLightClusters)
		{
			glUniform3fv(c.roomHeader, plugin.roomTexture.header());
			c.lampWalls.apply(plugin.lampWalls.header());
		}
		else
		{
			// nothing bound: no rooms, no walls
			glUniform3f(c.roomHeader, 0, 0, 0);
			c.lampWalls.apply(NO_WALLS);
		}
		glUniform1i(c.pointLights, plugin.framePointLights ? 1 : 0);
		glUniform1i(c.skylightOn, plugin.frameSkylight ? 1 : 0);
		glUniform3f(c.interiorTint, BuildingLights.FILL_TINT[0], BuildingLights.FILL_TINT[1], BuildingLights.FILL_TINT[2]);

		glUniform1i(c.drawDistance, plugin.frameDrawDistance);
		c.edge.set(plugin.edgeFade);
		glUniform1i(c.expandedChunks, plugin.client.getExpandedMapLoading());
		glUniform3f(c.clientSkyColor,
			(plugin.frameSkyColor >> 16 & 0xFF) / 255f, (plugin.frameSkyColor >> 8 & 0xFF) / 255f, (plugin.frameSkyColor & 0xFF) / 255f);

		Scene groundScene = plugin.client.getTopLevelWorldView().getScene();
		c.ground.set(plugin.frameGround && groundScene != null,
			groundScene != null ? groundScene.getBaseX() * (float) Perspective.LOCAL_TILE_SIZE : 0,
			groundScene != null ? groundScene.getBaseY() * (float) Perspective.LOCAL_TILE_SIZE : 0);
		c.ground.setWade(plugin.wadeSteps.steps(plugin.frameCameraX, plugin.frameCameraZ));
		glUniform1i(c.volumetricFog, plugin.frameFog ? 1 : 0);
		plugin.clouds.apply(c.clouds);
		plugin.weather.getLightning().bind(c.lightningBolt, plugin.frameCameraX, plugin.frameCameraZ);
		c.materials.set(plugin.config);
		plugin.water.setComposite(c.water, plugin.config);
	}

	/**
	 * Scissor the half resolution pass to the texels it draws: the water's rect, and the rows that can hold cloud
	 * across the whole width; with both, the rows from the first to the last of either. Enables the scissor test.
	 */
	private void scissorHalf(boolean withClouds, boolean withWater)
	{
		int[] r = plugin.halfScissor;
		if (withWater)
		{
			plugin.water.halfRect(r, plugin.sceneTargets.getHalfRatio());
		}
		if (withClouds)
		{
			plugin.clouds.skyRows(plugin.cloudRows, plugin.frameInvViewProj, plugin.frameViewport, plugin.sceneTargets.getAoWidth(),
				plugin.sceneTargets.getAoHeight(), plugin.sceneTargets.getHalfRatio());
			if (plugin.cloudRows[1] > plugin.cloudRows[0])
			{
				r[1] = withWater ? Math.min(r[1], plugin.cloudRows[0]) : plugin.cloudRows[0];
				r[3] = withWater ? Math.max(r[3], plugin.cloudRows[1]) : plugin.cloudRows[1];
				r[0] = 0;
				r[2] = plugin.sceneTargets.getAoWidth();
			}
			else if (!withWater)
			{
				r[0] = r[1] = r[2] = r[3] = 0;
			}
		}
		glEnable(GL_SCISSOR_TEST);
		glScissor(r[0], r[1], Math.max(r[2] - r[0], 0), Math.max(r[3] - r[1], 0));
	}

	// developer: GL_TIME_ELAPSED queries, when set, around the half resolution clouds' (and water's) draw, the full
	// composite's and the rain and snow's (harness WeatherCostProbe); null otherwise. passTimersUsed: a bit for each
	// query begun this frame, for the probe to clear before a frame and read after it
	public static int[] passTimers;
	public static int passTimersUsed;

	public void draw()
	{
		int width = plugin.lastStretchedCanvasWidth;
		int height = plugin.lastStretchedCanvasHeight;

		final AffineTransform transform = plugin.dpiTransform();

		width = plugin.getScaledValue(transform.getScaleX(), width);
		height = plugin.getScaledValue(transform.getScaleY(), height);

		final boolean ao = plugin.config.ambientOcclusion();

		com.midonarhel.lumina.dev.GpuSections.section("water surface");
		plugin.sceneTargets.bindTextures(COMPOSITE_TEXTURE_UNIT);
		// the water surface into its own target, against the scene's depth, before the composite reads it
		plugin.weather.getWaterWind(plugin.waterWind);
		plugin.water.drawSurface(plugin.config, plugin.waterWind, plugin.weather.waterRain(), plugin.frameOutdoors, plugin.sceneTargets.getWidth(),
			plugin.sceneTargets.getHeight(), plugin.frameViewport, plugin.frameViewProj, plugin.frameInvViewProj, plugin.frameCameraX, plugin.frameCameraY,
			plugin.frameCameraZ, plugin.edgeFade.focalX, plugin.edgeFade.focalZ, plugin.edgeFade.radius, plugin.dayCycle.casterDir, plugin.glCapabilities.OpenGL45,
			WATER_DATA_UNIT, WATER_NOISE_UNIT, WATER_CONTACT_UNIT);
		// fullscreen triangles are generated from gl_VertexID; core profile still needs some VAO bound
		glBindVertexArray(plugin.vaoUiHandle);
		plugin.cpuTimer.begin(CpuTimer.VOXELS);
		plugin.visibilityFrame.prepareVoxels();
		plugin.cpuTimer.end(CpuTimer.VOXELS);
		plugin.cpuTimer.begin(CpuTimer.SKY_GRID);
		BuildingLights buildings = plugin.buildingLights;
		plugin.skyGrid.update(plugin.frameSkylightWanted, plugin.voxelBuilder.getVoxels(), buildings != null ? buildings.getWalls() : null,
			plugin.frameSkylightTileX, plugin.frameSkylightTileZ, buildings != null ? buildings.getFloors() : null,
			buildings != null ? buildings.getRooms() : null);
		plugin.cpuTimer.end(CpuTimer.SKY_GRID);

		// the AO pass also writes the half resolution depth and normals that the fog's upsample reads, and the
		// shadows
		WorldView aoView = plugin.client.getTopLevelWorldView();
		Scene aoScene = aoView != null ? aoView.getScene() : null;
		final boolean shadows = plugin.frameShadows && plugin.shadowMap.isInitialized();
		final boolean ground = plugin.weather.groundActive();
		plugin.frameGround = ground;
		final boolean sky = plugin.skyGrid.isReady(aoScene);
		plugin.frameSkylight = false;
		final boolean halfPass = ao || plugin.frameFog || shadows || ground || sky;
		if (plugin.framesSinceSwap < 6)
		{
			// B3 trace: the user sees one frame render normally, one "really sharp", then back, on every teleport — which
			// passes ran in the frames after the swap tells which layer drops out
			// identities of the derived data in use this frame: which one changes between the frames names the layer
			log.info(String.format("post-swap frame %d: ao=%b fog=%b shadows=%b(init %b) ground=%b sky=%b half=%b skyCacheMode=%d captures=%d | voxels=%08x fogGround=%08x shadowFront=%08x rooms=%08x",
				plugin.framesSinceSwap, ao, plugin.frameFog, shadows, plugin.shadowMap.isInitialized(), ground, sky, halfPass, plugin.lastSkyCacheMode, plugin.skyCache.getCaptures(),
				System.identityHashCode(plugin.voxelBuilder.getVoxels()), System.identityHashCode(plugin.volumetricFog.groundForTrace()),
				System.identityHashCode(plugin.shadowMap.frontView()),
				System.identityHashCode(plugin.buildingLights != null ? plugin.buildingLights.getRooms() : null)));
			plugin.framesSinceSwap++;
		}
		if (plugin.swapCaptureLeft > 0)
		{
			// the frames around the swap: k = frames since the load finished, s = frames since the swap (1000 = not yet)
			FrameCapture.capture(plugin.client, plugin.drawManager, "Lumina (perf-wip)", String.format("swap-k%d-s%d", plugin.swapCaptureIndex, Math.min(plugin.framesSinceSwap, 999)));
			plugin.swapCaptureIndex++;
			plugin.swapCaptureLeft--;
		}
		if (halfPass)
		{
			com.midonarhel.lumina.dev.GpuSections.section("AO, skylight, shadow mask");
			glBindFramebuffer(GL_FRAMEBUFFER, plugin.sceneTargets.getAoFbo());
			glViewport(0, 0, plugin.sceneTargets.getAoWidth(), plugin.sceneTargets.getAoHeight());
			// the AO pass writes every texel: clearing first lets the GPU start the pass without loading the old
			// contents (the fog, denoise, cloud and composite passes do the same)
			glClear(GL_COLOR_BUFFER_BIT);
			glUseProgram(plugin.glAoProgram);
			plugin.frameState.setFrameUniforms(plugin.aoFrame);
			glUniform1f(plugin.uniAoRadius, plugin.config.aoRadius() / 100f * Perspective.LOCAL_TILE_SIZE);
			glUniform1i(plugin.uniAoSamples, ao ? plugin.config.aoQuality().pick(6, 8, 12) : 0);
			glUniform1i(plugin.uniAoShadowsEnabled, shadows ? 1 : 0);
			if (shadows)
			{
				glUniformMatrix4fv(plugin.uniAoLightProjRel, false, plugin.shadowMap.lightProjRel);
				glUniform1f(plugin.uniAoShadowRadius, plugin.shadowMap.getRadius());
				glUniform1f(plugin.uniAoShadowDepthRange, plugin.shadowMap.getDepthRange());
				glUniform1f(plugin.uniAoShadowMapSize, plugin.shadowMap.getSize());
				glUniform3fv(plugin.uniAoCasterDir, plugin.dayCycle.casterDir);
				glUniform1i(plugin.uniAoSunIsCaster, plugin.dayCycle.sunIsCaster ? 1 : 0);
				glUniform2fv(plugin.uniAoStrikeLight, plugin.dayCycle.strikeLight);
				plugin.shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
				glUniform1i(plugin.uniAoShadowMaxTile, plugin.shadowMap.bindMax(SHADOW_MAX_UNIT));
			}
			plugin.sceneTargets.bindMaterial(MATERIAL_UNIT);
			plugin.water.bindAo(plugin.config, WATER_DATA_UNIT);
			if (ground)
			{
				plugin.weather.bindGround(plugin.weatherGround, WEATHER_OCCLUSION_UNIT, WEATHER_FIELD_UNIT, plugin.frameCameraX, plugin.frameCameraY,
					plugin.frameCameraZ);
			}
			else
			{
				plugin.weatherGround.setAoOff();
			}
			plugin.frameSkylight = plugin.skyGrid.bindAo(SKY_GRID_UNIT, aoScene, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ)
				&& !com.midonarhel.lumina.dev.Commands.skylightOff;
			plugin.skyField.bindAo(SKY_FIELD_UNIT, plugin.frameCameraX, plugin.frameCameraZ);
			if (passTimers != null)
			{
				// timed with the skylight switched off: the AO program's skylight too, so its cost shows
				if (com.midonarhel.lumina.dev.Commands.skylightOff) glUniform1i(glGetUniformLocation(plugin.glAoProgram, "skylightOn"), 0);
				glBeginQuery(org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED, passTimers[0]);
			}
			glDrawArrays(GL_TRIANGLES, 0, 3);
			if (passTimers != null) glEndQuery(org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED);
			SkyGridMap.unbind(SKY_GRID_UNIT);
			SkyFieldMap.unbind(SKY_FIELD_UNIT);
			// the sky cache takes the occlusion map's unit next, without its sampler objects
			ShadowMap.unbind(WEATHER_OCCLUSION_UNIT, WEATHER_OCCLUSION_UNIT + 1);
			plugin.sceneTargets.bindAo(AO_TEXTURE_UNIT);
			plugin.sceneTargets.bindShadow(SCENE_SHADOW_UNIT);
		}

		com.midonarhel.lumina.dev.GpuSections.section("sky cache, night sky, lighting prep");
		plugin.skyFrame.updateClouds();
		plugin.cpuTimer.begin(CpuTimer.SKY_CACHE);
		int captured = plugin.skyCache.getCaptures();
		int skyCacheMode = plugin.skyFrame.updateSkyCache();
		plugin.lastSkyCacheMode = skyCacheMode;
		plugin.cpuTimer.end(CpuTimer.SKY_CACHE);
		if (captured != plugin.skyCache.getCaptures())
		{
			plugin.hitchMonitor.job(HitchMonitor.SKY_CAPTURE);
		}

		plugin.skyCache.easeComposite(skyCacheMode);
		plugin.compositeTime = (com.midonarhel.lumina.common.Clock.millis() - plugin.startMillis) / 1000f;
		plugin.skyFrame.updateNightSky();
		plugin.lampFrame.prepareLighting();
		plugin.sceneTargets.bindAlphaLayer(ALPHA_LAYER_UNIT);

		plugin.weather.bindField(WEATHER_FIELD_UNIT, WEATHER_SKY_UNIT);
		final boolean denoise = halfPass && plugin.glDenoiseProgram != 0;
		boolean denoiseFog = false;
		// after prepareLighting, which binds the shadow map and light clusters the fog also samples
		if (plugin.frameFog)
		{
			plugin.volumetricFog.waterTexture = plugin.water.dataTexture(plugin.config);
			// in a void the sun doesn't light the fog: it would glow over the black around the scene
			final boolean fogSun = plugin.frameOutdoors && !plugin.frameSkyVoid;
			final ShadowMap fogShadows = shadows ? plugin.shadowMap : null;
			final boolean fogLamps = plugin.config.pointLights() && plugin.lightClusters.isInitialized()
				&& plugin.lightClusters.hasFogLights();
			denoiseFog = denoise && plugin.volumetricFog.hasGrain(fogSun, fogShadows, fogLamps);
			com.midonarhel.lumina.dev.GpuSections.section("fog");
			plugin.volumetricFog.render(plugin.sceneTargets.fogFbo(denoiseFog), plugin.sceneTargets.getFogWidth(),
				plugin.sceneTargets.getFogHeight(), plugin.sceneTargets.getFogRatio(), FOG_NOISE_UNIT,
				plugin.frameViewProj, plugin.frameInvViewProj, plugin.frameViewport, plugin.glCapabilities.OpenGL45,
				plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ, (com.midonarhel.lumina.common.Clock.millis() - plugin.startMillis) / 1000f,
				fogSun, plugin.proceduralSky(), plugin.frameSkyColor, plugin.dayCycle, fogShadows, fogLamps,
				plugin.lampWalls.header());
		}
		// the AO, the shadows and the fog's sun shafts are all dithered: smoothed together at half resolution before
		// the composite upsamples them
		if (denoise)
		{
			com.midonarhel.lumina.dev.GpuSections.section("denoise");
			glUseProgram(plugin.glDenoiseProgram);
			plugin.frameState.setFrameUniforms(plugin.denoiseFrame);
			plugin.sceneTargets.denoise(plugin.uniDenoiseFog, AO_TEXTURE_UNIT, SCENE_SHADOW_UNIT, FOG_TEXTURE_UNIT, denoiseFog);
			plugin.sceneTargets.bindShadow(SCENE_SHADOW_UNIT);
		}
		if (plugin.frameFog)
		{
			plugin.sceneTargets.bindFog(FOG_TEXTURE_UNIT);
		}
		if (plugin.clouds.isEnabled())
		{
			plugin.clouds.bindNoise(CLOUD_NOISE_UNIT);
		}
		plugin.sceneTargets.bindMaterial(MATERIAL_UNIT);
		plugin.water.bind(plugin.config, WATER_DATA_UNIT);
		// The clouds, which the composite's sky filters, and the water's shading (water.glsl) at half resolution, with
		// everything bound for the composite: in one pass when both are drawn, timed as the clouds'
		final boolean halfClouds = plugin.clouds.isEnabled() && plugin.frameOutdoors && plugin.proceduralSky();
		final boolean halfWater = plugin.water.enabled(plugin.config);
		if (halfClouds || halfWater)
		{
			com.midonarhel.lumina.dev.GpuSections.section("clouds, water shading (half)");
			// their targets are not bound while drawn into
			glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
			glBindTexture(GL_TEXTURE_2D, 0);
			glActiveTexture(GL_TEXTURE0 + WATER_NOISE_UNIT);
			glBindTexture(GL_TEXTURE_2D, 0);
			plugin.sceneTargets.bindHalf(halfClouds, halfWater);
			glDisable(GL_BLEND);
			use(!halfWater ? compositeClouds : halfClouds ? compositeCloudsWater : compositeWater);
			scissorHalf(halfClouds, halfWater);
			if (passTimers != null)
			{
				glBeginQuery(GL_TIME_ELAPSED, passTimers[0]);
				passTimersUsed |= 1;
			}
			glDrawArrays(GL_TRIANGLES, 0, 3);
			if (passTimers != null) glEndQuery(GL_TIME_ELAPSED);
			glDisable(GL_SCISSOR_TEST);
			glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
			glBindTexture(GL_TEXTURE_2D, halfClouds ? plugin.sceneTargets.cloudTexture() : 0);
			glActiveTexture(GL_TEXTURE0 + WATER_NOISE_UNIT);
			glBindTexture(GL_TEXTURE_2D, halfWater ? plugin.sceneTargets.waterShadedTexture() : 0);
			glActiveTexture(GL_TEXTURE0);
		}

		com.midonarhel.lumina.dev.GpuSections.section("composite");
		use(compositeFull);
		if (log.isDebugEnabled())
		{
			plugin.lookMonitor.frame(compositeFull.env, plugin.weather, plugin.frameLights.size(),
				plugin.environmentManager.getWorldPoint(), plugin.environmentManager.getBiome());
		}

		if (plugin.config.debugView() == DebugView.FINAL && !Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true")))
		{
			for (int part = 1; part <= 4; part++)
			{
				plugin.sceneTargets.drawPart(part, COMPOSITE_PARTS_UNIT);
				glUniform1i(compositeFull.part, part);
				glDrawArrays(GL_TRIANGLES, 0, 3);
			}
			plugin.sceneTargets.drawPart(5, COMPOSITE_PARTS_UNIT);
			glUniform1i(compositeFull.part, 5);
		}

		// below 100% render scale the composite and the weather draw at the scene's size into the upscaler's target
		final boolean upscale = plugin.upscaler.isInitialized();
		final int sceneWidth = upscale ? plugin.sceneTargets.getWidth() : width;
		final int sceneHeight = upscale ? plugin.sceneTargets.getHeight() : height;
		// the interface texture is filled before the drawable's pass starts, so the composite, the weather and the
		// interface all draw in one pass: an upload between them would end it and store and reload the drawable
		plugin.uiBlit.finish();
		final int target = upscale ? plugin.upscaler.getFbo() : plugin.windowFramebuffer();
		glBindFramebuffer(GL_FRAMEBUFFER, target);
		final boolean edges = plugin.shaderSamples > 0;
		// The pixels with a second surface, which the edge pass shades: in the upscaler's target the full pass marks
		// them in its depth as it tells its samples apart (composite_frag.glsl: 1 there, 0 elsewhere), and the edge
		// pass is depth tested against that, so its full shading never starts on the others. In the window, which may
		// have a stencil but no depth, a small pass marks them in the stencil first; without either the edge pass
		// discards them itself.
		final boolean depthMarked = edges && upscale;
		final boolean stencil = edges && !upscale && target == 0 && plugin.hasWindowStencil();
		glViewport(0, 0, sceneWidth, sceneHeight);
		// depth and stencil are one attachment, cleared together so neither is loaded
		glClear(depthMarked ? GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT
			: stencil ? GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT : GL_COLOR_BUFFER_BIT);
		if (depthMarked)
		{
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_ALWAYS);
		}
		if (passTimers != null)
		{
			glBeginQuery(GL_TIME_ELAPSED, passTimers[1]);
			passTimersUsed |= 2;
		}
		glDrawArrays(GL_TRIANGLES, 0, 3);
		if (passTimers != null) glEndQuery(GL_TIME_ELAPSED);
		glUniform1i(compositeFull.part, 0);
		if (edges)
		{
			com.midonarhel.lumina.dev.GpuSections.section("composite mark (MSAA)");
			if (depthMarked)
			{
				// the edge pass's triangle lies at depth 0.5 (or 0 with a zero-to-one clip): less than the marks' 1 only
				glDepthFunc(GL_LESS);
				glDepthMask(false);
			}
			if (stencil)
			{
				// the pixels with a second surface, by a small program, so the edge pass's full shading never starts
				// on the others; without a stencil the edge pass discards them itself
				glUseProgram(compositeMark.program);
				plugin.frameState.setFrameUniforms(compositeMark.frame);
				glEnable(GL_STENCIL_TEST);
				glStencilFunc(GL_ALWAYS, 1, 0xFF);
				glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
				glColorMask(false, false, false, false);
				glDrawArrays(GL_TRIANGLES, 0, 3);
				glColorMask(true, true, true, true);
				glStencilFunc(GL_EQUAL, 1, 0xFF);
				glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
			}
			// the second surfaces, over the first by their share of the samples; the target's alpha stays
			com.midonarhel.lumina.dev.GpuSections.section("composite edges (MSAA)");
			use(compositeEdges);
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDisable(GL_BLEND);
			glDisable(GL_STENCIL_TEST);
		}
		if (depthMarked)
		{
			// as the scene leaves it
			glDepthMask(true);
			glDepthFunc(GL_GREATER);
			glDisable(GL_DEPTH_TEST);
		}
		glActiveTexture(GL_TEXTURE0 + COMPOSITE_PARTS_UNIT);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glActiveTexture(GL_TEXTURE0);
		glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		// before the weather binds its occlusion map on the same unit
		SkyCache.unbind(SKY_CACHE_UNIT);

		if (plugin.weather.isActive() && plugin.config.debugView() == DebugView.FINAL)
		{
			com.midonarhel.lumina.dev.GpuSections.section("weather");
			if (passTimers != null)
			{
				glBeginQuery(GL_TIME_ELAPSED, passTimers[2]);
				passTimersUsed |= 4;
			}
			plugin.weather.draw(plugin.frameViewProj, plugin.frameInvViewProj, plugin.frameViewport, plugin.glCapabilities.OpenGL45,
				plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ, plugin.dayCycle, plugin.water, plugin.config,
				WEATHER_OCCLUSION_UNIT,
				plugin.config.pointLights() && plugin.lightClusters.isInitialized(),
				plugin.lampWalls.header(), plugin.frameDrawDistance,
				plugin.wadeSteps.steps(plugin.frameCameraX, plugin.frameCameraZ));
			if (passTimers != null) glEndQuery(GL_TIME_ELAPSED);
			glViewport(0, 0, sceneWidth, sceneHeight);
		}
		// the weather draws splashes by the water pass's pixels
		Water.unbind(WATER_DATA_UNIT, WATER_NOISE_UNIT);

		if (upscale)
		{
			com.midonarhel.lumina.dev.GpuSections.section("upscale");
			glBindFramebuffer(GL_FRAMEBUFFER, plugin.windowFramebuffer());
			// the weather pass leaves its own vertex array bound (or none); the fullscreen triangle needs one bound
			glBindVertexArray(plugin.vaoUiHandle);
			plugin.upscaler.draw(width, height, plugin.config.upscaleSharpness() / 100f);
		}

		glBindVertexArray(0);
		plugin.sceneTargets.unbindTextures(COMPOSITE_TEXTURE_UNIT);
		glActiveTexture(GL_TEXTURE0 + AO_TEXTURE_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + SCENE_SHADOW_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + SHADOW_MAX_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		ShadowMap.unbind(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		LightClusters.unbind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT);
		RoomTexture.unbind(ROOM_TEXELS_UNIT);
		plugin.sceneTargets.unbindAlphaLayer(ALPHA_LAYER_UNIT);
		plugin.sceneTargets.unbindMaterial(MATERIAL_UNIT);
		VolumetricFog.unbind(FOG_TEXTURE_UNIT);
		Clouds.unbind(CLOUD_NOISE_UNIT);
		WeatherField.unbind(WEATHER_FIELD_UNIT, WEATHER_FIELD_UNIT + 1, WEATHER_SKY_UNIT);
		glUseProgram(0);

		plugin.checkGLErrors();
	}
}
