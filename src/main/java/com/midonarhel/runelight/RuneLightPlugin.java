/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.runelight;

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
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
import com.midonarhel.runelight.config.AntiAliasingMode;
import com.midonarhel.runelight.config.DebugView;
import com.midonarhel.runelight.config.UIScalingMode;
import com.midonarhel.runelight.config.WeatherMode;
import com.midonarhel.runelight.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.rlawt.AWTContext;
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

@PluginDescriptor(
	name = "RuneLight",
	description = "Lighting-focused GPU renderer",
	tags = {"gpu", "lighting", "shadows", "fog", "renderer"},
	loadInSafeMode = false
)
public class RuneLightPlugin extends Plugin implements DrawCallbacks
{
	private static final Logger log = LoggerFactory.getLogger(RuneLightPlugin.class);

	static final int MAX_DISTANCE = 184;
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RuneLightConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private RegionManager regionManager;

	private final AreaHiding areaHiding = new AreaHiding();

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private Gson gson;

	private SceneLights sceneLights;
	private BuildingLights buildingLights;

	@Inject
	private BuildingOverlay buildingOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RuneLightOverlay overlay;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private static final Set<String> OTHER_RENDERERS = Set.of(
		"net.runelite.client.plugins.gpu.GpuPlugin",
		"rs117.hd.HdPlugin");

	// true once this plugin owns the client's draw callbacks; guards shutDown from clobbering another renderer
	private boolean active;

	// cached: checkGLErrors runs per draw call, too often for a config proxy lookup
	private volatile boolean glErrorChecks;

	private ShaderWatcher shaderWatcher;
	private volatile String shaderError;
	private float[] textureAnimations;

	final GpuTimer gpuTimer = new GpuTimer();
	final CpuTimer cpuTimer = new CpuTimer();

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static final Shader COMPOSITE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "composite_frag.glsl");

	static final Shader AO_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "ao_frag.glsl");

	static final Shader DENOISE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "denoise_frag.glsl");

	// The composite (composite_frag.glsl) is compiled once for each of its passes, so that each program holds only its
	// own path: a GPU gives a program the registers of its heaviest path, and the full resolution composite paid for
	// the cloud march and the water's reflections. The passes share their uniforms, set on each before it draws
	// (useComposite).
	private static final int COMPOSITE_FULL = 0, COMPOSITE_CLOUDS = 1, COMPOSITE_WATER = 2, COMPOSITE_CLOUDS_WATER = 3,
		COMPOSITE_EDGES = 4, COMPOSITE_MARK = 5;

	/** One of the composite's programs and its uniforms. */
	private static final class CompositeProgram
	{
		final int pass;
		int program;
		int part, aoEnabled, aoStrength;
		int cameraPos, time, outdoors, proceduralSky, lightingEnabled, sunDir, moonDir, casterDir, sunIsCaster,
			strikeLight, sunAltitude, overcast, weatherLight;
		int shadowsEnabled, lightProjRel, shadowRadius, shadowDepthRange, shadowMapSize;
		int pointLights, lightVisSize, roomHeader, interiorTint, skylightOn, skylightFloor;
		int skyVoid, useFog, fogDepth, drawDistance, expandedChunks, clientSkyColor;
		int lightningBolt, volumetricFog;
		final FrameUniforms frame = new FrameUniforms();
		final EnvironmentUniforms env = new EnvironmentUniforms();
		final EdgeFade.Uniforms edge = new EdgeFade.Uniforms();
		final SurfaceMaterials.Uniforms materials = new SurfaceMaterials.Uniforms();
		final Clouds.Locations clouds = new Clouds.Locations();
		final Water.CompositeUniforms water = new Water.CompositeUniforms();
		final SkyCache.CompositeUniforms skyCache = new SkyCache.CompositeUniforms();
		final WeatherGround.CompositeUniforms ground = new WeatherGround.CompositeUniforms();

		CompositeProgram(int pass)
		{
			this.pass = pass;
		}
	}

	private final CompositeProgram compositeFull = new CompositeProgram(COMPOSITE_FULL);
	private final CompositeProgram compositeClouds = new CompositeProgram(COMPOSITE_CLOUDS);
	private final CompositeProgram compositeWater = new CompositeProgram(COMPOSITE_WATER);
	private final CompositeProgram compositeCloudsWater = new CompositeProgram(COMPOSITE_CLOUDS_WATER);
	// with MSAA, the second surface of the pixels that have two, blended over the full pass
	private final CompositeProgram compositeEdges = new CompositeProgram(COMPOSITE_EDGES);
	// with MSAA, marks the pixels that have a second surface in the stencil, so the edge pass runs on those alone
	private final CompositeProgram compositeMark = new CompositeProgram(COMPOSITE_MARK);
	private final CompositeProgram[] composites = {compositeFull, compositeClouds, compositeWater, compositeCloudsWater,
		compositeEdges, compositeMark};
	// whether the window's framebuffer has a stencil, for the edge pass at 100% render scale; 0 unknown, 1 yes, -1 no.
	// The GL bridge gives Windows and Linux one from the pixel format; on macOS it draws into an IOSurface's
	// framebuffer, color alone
	private int windowStencil;
	private static final int COMPOSITE_PARTS_UNIT = 31;
	// this frame's time for the composite's passes, seconds since the start
	private float compositeTime;
	// this frame's point lights on, and whether the light clusters and the rooms were uploaded and bound
	// (prepareLighting); whether the wet ground is drawn
	private boolean framePointLights, frameLightClusters, frameGround;

	private int glAoProgram;
	private int glDenoiseProgram;
	private int uniDenoiseFog;
	private int uniAoRadius;
	private int uniAoSamples;
	private int uniAoShadowsEnabled, uniAoLightProjRel, uniAoShadowRadius, uniAoShadowDepthRange, uniAoShadowMapSize,
		uniAoShadowMaxTile, uniAoCasterDir, uniAoSunIsCaster, uniAoStrikeLight;
	// the half resolution shadows the AO pass draws, for the composite
	private static final int SCENE_SHADOW_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 18;
	// Static scenery is drawn into a cached shadow map, redrawn only as the light turns or the player walks
	// (renderStaticShadows). It looks the same as drawing it every frame, so it is no longer a setting.
	private static final boolean SHADOW_CACHE = true;
	// the game shows a black void around the scene (instances, player houses, dungeons): it stays dark
	private boolean frameSkyVoid;
	private final FrameUniforms aoFrame = new FrameUniforms();

	private static final int AO_TEXTURE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 3;
	private static final int SHADOW_RAW_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 4;
	private static final int SHADOW_COMPARE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 5;
	// the static shadow map's maxima, for the AO pass (ShadowMap.bindMax)
	private static final int SHADOW_MAX_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 22;
	private static final int LIGHT_DATA_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 6;
	private static final int CLUSTER_DATA_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 7;
	// the static lights' occlusion grids (LightOcclusion), filtered by the hardware
	private static final int LIGHT_VIS_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 21;
	// the fog as marched at quarter resolution, read by its upsample
	// the clouds at half resolution, which the composite's sky filters (composite_frag.glsl)
	private static final int CLOUD_HALF_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 23;

	private final LightClusters lightClusters = new LightClusters();
	// the static lights baked into per-storey maps (LightMap), which the composite, the fog and the weather read from
	// the same unit as the occlusion grids, which the map takes the place of (the shaders have one or the other)
	private static final int LIGHT_MAP_BLOCKS_PER_FRAME = 24;
	// more are sent in a frame while the sending has taken less than this, up to the most (a scene load has hundreds)
	private static final long LIGHT_MAP_SEND_BUDGET_NANOS = 1_000_000;
	private static final int LIGHT_MAP_BLOCKS_MAX = 96;
	private final LightMap lightMap = new LightMap(LightMap.defaultThreads(), LightMap.burstThreads());
	private final LightMapTexture lightMapTexture = new LightMapTexture();
	// how much light each building room gets and the fill it lacks (RoomLight); it reads the blocks sent to the texture
	private final RoomLight roomLight = new RoomLight();
	private final LightMap.Sink lightMapSink = (plane, bx, bz, a, b) ->
	{
		lightMapTexture.block(plane, bx, bz, a, b);
		roomLight.block(plane, bx, bz, a, b);
	};
	private int roomLightTexture;
	private long roomLightNanos;
	// what the light map was last given to stop light, and what that was made from
	private LightBlockers lightBlockers;
	private WallMap blockerWalls;
	private ModelBlockers blockerModels;
	private RoomMap blockerRooms;
	private byte[] blockerFloors;
	// the map carries the static lights this frame
	private boolean frameLightMap;
	// the voxel build is wanted for the models the light map is stopped by
	private boolean frameLightMapWanted;
	private final OpenRooms openRooms = new OpenRooms();
	// repeating errors are logged once per interval
	private final LogLimiter logLimiter = new LogLimiter(10_000);
	private final LookMonitor lookMonitor = new LookMonitor();
	private final HitchMonitor hitchMonitor = new HitchMonitor();
	private final java.util.ArrayList<PointLight> frameLights = new java.util.ArrayList<>();
	private final PointLight testLight = new PointLight();
	private int uniAlphaPass;
	private int uniSceneOutdoors;
	private int uniSceneProceduralSky;
	private int uniSceneCasterDir;
	private int uniSceneSunIsCaster, uniSceneStrikeLight;
	private int uniSceneSunAltitude;
	private int uniSceneOvercast;
	private int uniSceneWeatherLight;
	// the wind for foliage sway, on the scene and shadow programs
	private Wind.Locations sceneWind, shadowWind;
	private long lastSwayTick;
	private boolean swayTick = true;
	private static final int ALPHA_LAYER_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 8;
	private static final int MATERIAL_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 9;
	private final SurfaceMaterials.Uniforms sceneMaterials = new SurfaceMaterials.Uniforms();
	private final WindSway.Uniforms sceneSway = new WindSway.Uniforms();
	private final WindSway.Uniforms shadowSway = new WindSway.Uniforms();
	private static final int FOG_TEXTURE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 10;
	private static final int FOG_NOISE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 11;

	private final VolumetricFog volumetricFog = new VolumetricFog();
	private final Clouds clouds = new Clouds();
	// the half resolution pass's scissor, x0, y0 to x1, y1 exclusive: the water's rect, the clouds' rows (skyRows)
	private final int[] halfScissor = new int[4], cloudRows = new int[2];
	// the composite has no 3D sampler, so the clouds' 2D noise shares the fog noise's unit on the 2D target
	private static final int CLOUD_NOISE_UNIT = FOG_NOISE_UNIT;
	private final SkyCache skyCache = new SkyCache();
	// The composite samples the sky cache and its irradiance on the weather occlusion map's two units: the composite
	// draws before the weather passes bind the map there. The cache's own passes use them before either.
	private static final int SKY_CACHE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 12;
	private final float[] skyCacheOvercast = new float[7];
	private boolean frameFog;
	// 117 HD fog depth that doubles the volumetric fog
	private static final float AREA_FOG_DEPTH = 30;
	// under the open sky rather than underground, from the area classification (or the game's sky color)
	private boolean frameOpenSky;

	static final Shader SHADOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	static final Shader SHADOW_COPY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_copy_frag.glsl");

	static final Shader SHADOW_MAX_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_max_frag.glsl");

	private int glShadowProgram;
	private int glShadowCopyProgram;
	private int glShadowMaxProgram;
	private int uniShadowLightProj;
	private int uniShadowEntityProj;
	private int uniShadowBase;
	private int uniShadowTexturesReady;
	private int uniShadowEmissiveCasts;

	private long frameCpuStart;
	private volatile double cpuFrameMs;
	private long lastSwapNanos;
	private volatile double swapIntervalMs;

	private final DayCycle dayCycle = new DayCycle();
	final EnvironmentManager environmentManager = new EnvironmentManager();
	private final EnvironmentUniforms sceneEnvUniforms = new EnvironmentUniforms();
	private final ShadowMap shadowMap = new ShadowMap();
	private final Weather weather = new Weather();
	private volatile WeatherAudio weatherAudio;
	private final float[] thunderStrike = new float[2];
	// each region's forecast, temperature and ground, which follow the game clock
	private final Climate climate = new Climate();
	private final WeatherGround weatherGround = new WeatherGround();
	// where players and small NPCs have just walked through rain
	private final WadeSteps wadeSteps = new WadeSteps();
	// GameClock's time this frame
	private long frameGameMillis;
	// two units: the occlusion map's raw and compare views; their own, since the AO program's compare sampler must
	// never find another program's color texture left on its unit (the sky cache and the water contact were there)
	private static final int WEATHER_OCCLUSION_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 26;
	// the water: data and noise for its surface pass, then the surface and its half resolution shading for the
	// composite; 16 and 17, past the composite's other units (the per-stage limit of 16 counts samplers a program
	// uses, not unit numbers; units go up to the combined limit, 80 on macOS)
	private static final int WATER_DATA_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 14;
	private static final int WATER_NOISE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 15;
	// the water surface pass's map of things standing in the water (WaterMap.contact)
	private static final int WATER_CONTACT_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 13;
	// the terrain the volumetric fog lies on (VolumetricFog)
	private static final int FOG_GROUND_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 24;
	// the surface pass's wave maps either side of now (WaterSurface.bake)
	private static final int WATER_WAVES_UNIT_A = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 17;
	private static final int WATER_WAVES_UNIT_B = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 19;
	// two units: the weather field (WeatherField) and its ground, for the fog and the weather passes
	private static final int WEATHER_FIELD_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 16;
	private final Water water = new Water();
	private final float[] waterWind = new float[3];
	private final VoxelBuilder voxelBuilder = new VoxelBuilder();
	// the skylight's traced sky visibility for the AO pass, and the static lights' occlusion
	private static final int SKY_GRID_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 28;
	// the weather field's sky (cover, wispiness, darkness around the player) for the clouds
	private static final int WEATHER_SKY_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 27;
	private final SkyGridMap skyGrid = new SkyGridMap();
	// world units from the player's feet to the head, where the sky overhead is read for the weather sounds
	private static final float WEATHER_HEAD = 120;
	// the sky's light flowing in under cover
	private static final int SKY_FIELD_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 25;
	private final SkyFieldMap skyField = new SkyFieldMap();
	private final LightOcclusion lightOcclusion = new LightOcclusion();
	// whether the AO pass worked the sky visibility out this frame; whether it is wanted (under the open sky, logged
	// in), and where the player is for the sky grid's tracing (world tiles)
	private boolean frameSkylight;
	private boolean frameSkylightWanted;
	private float frameSkylightTileX, frameSkylightTileZ;
	// when the plugin started; animations count from here (the day, the seasons and the weather follow GameClock)
	private long startMillis = System.currentTimeMillis();

	// per-frame state for the full-screen passes, captured during the scene draw
	private float frameCameraX, frameCameraY, frameCameraZ;
	private boolean frameOutdoors;
	private boolean frameHasSkybox;
	private boolean frameShadows;
	private int frameSkyColor;
	private int frameFogDepth;
	private int frameDrawDistance;

	/**
	 * Camera data for the full-screen passes. The matrices are camera-relative (no translation) to keep
	 * world position reconstruction precise far from the world origin.
	 */
	private static final class FrameUniforms
	{
		int viewProj, invViewProj, viewport, depthZeroToOne, halfRatio;

		void locate(int program)
		{
			viewProj = glGetUniformLocation(program, "viewProj");
			invViewProj = glGetUniformLocation(program, "invViewProj");
			viewport = glGetUniformLocation(program, "sceneViewport");
			depthZeroToOne = glGetUniformLocation(program, "depthZeroToOne");
			halfRatio = glGetUniformLocation(program, "halfRatio");
		}
	}

	private final float[] frameViewProj = Mat4.identity();
	private float[] frameInvViewProj = Mat4.identity();
	// scene viewport within the scene targets, in framebuffer pixels: x, y, width, height
	private final int[] frameViewport = new int[4];

	static int glProgram;
	private int glUiProgram;

	private int interfaceTexture;
	// the interface texture's filter as last set: changed only with the UI scaling mode
	private int interfaceFilter;
	private int interfacePbo;
	private final InterfaceUpload interfaceUpload = new InterfaceUpload();
	private final TempModels tempModels = new TempModels();

	private int vaoUiHandle;
	private int vboUiHandle;

	private final SceneTargets sceneTargets = new SceneTargets();
	private boolean sceneFboValid;

	// composite samples the scene targets from these units; 0 is the UI texture and 1 the game texture array
	private static final int COMPOSITE_TEXTURE_UNIT = 2;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	// the share of the drawable's resolution the scene targets were made for (config render scale)
	private double lastRenderFactor;
	private final Upscaler upscaler = new Upscaler();
	private int lastAnisotropicFilteringLevel = -1;
	private float lastTextureLodBias;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	static class RenderThread
	{
		VAOList vaoO, vaoA;
		ModelUploader modelUploader;
	}

	private RenderThread[] rts;

	private SceneUploader clientUploader, mapUploader;

	static class SceneContext
	{
		final float[] projection = Mat4.identity();

		final int sizeX, sizeZ;
		Zone[][] zones;

		int cameraX, cameraY, cameraZ;
		private int minLevel, level, maxLevel;
		private Set<Integer> hideRoofIds;

		SceneContext(int sizeX, int sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
		}

		void free()
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free();
				}
			}
		}
	}

	SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	private SceneContext root;
	private SceneContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniDrawDistance;
	private final EdgeFade edgeFade = new EdgeFade();
	private final EdgeFade.Uniforms sceneEdgeUniforms = new EdgeFade.Uniforms();
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextureAnimations;
	private int uniTextureLightMode;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();

	@Override
	protected void startUp()
	{
		String other = otherRenderer();
		if (other != null)
		{
			log.warn("{} is enabled; RuneLight will not start", other);
			chat("RuneLight can't run alongside " + other + ". Turn it off, then enable RuneLight again.");
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.setPluginEnabled(this, false);
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex)
				{
					log.error("error stopping plugin", ex);
				}
			});
			return;
		}

		if (developerMode)
		{
			shaderWatcher = ShaderWatcher.create();
			if (shaderWatcher != null)
			{
				shaderWatcher.start();
			}
		}
		overlayManager.add(overlay);
		glErrorChecks = config.glErrorChecks();

		VertexShading.undoVanillaShading = config.removeBakedShading();
		VertexShading.unlitColors = config.unlitColors();
		VertexShading.smoothFlatFaces = config.smoothShapes();
		GroundBlender.enabled = config.groundBlending();

		sceneLights = new SceneLights(client);
		sceneLights.load(gson);
		SurfaceMaterials.load(gson);
		WaterOverrides.load(gson);
		areaHiding.load(gson);
		WindSway.load(gson);
		eventBus.register(sceneLights);
		clientThread.invoke(sceneLights::rebuild);
		weatherAudio = new WeatherAudio();
		buildingLights = new BuildingLights(client);
		eventBus.register(buildingLights);
		clientThread.invoke(buildingLights::rebuild);
		overlayManager.add(buildingOverlay);
		environmentManager.load(gson);
		WeatherData.load(gson);
		startMillis = System.currentTimeMillis();

		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		clientUploader = new SceneUploader(renderCallbackManager);
		mapUploader = new SceneUploader(renderCallbackManager);
		clientThread.invoke(() ->
		{
			try
			{
				lastAnisotropicFilteringLevel = -1;
				lastTextureLodBias = 0;

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					// a stencil for the composite's edge pass (windowStencil)
					awtContext.configurePixelFormat(0, 0, 8);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));

				if (!glCapabilities.OpenGL33)
				{
					throw new RuntimeException("OpenGL 3.3 is required but not available");
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						// [LWJGL] OpenGL debug message
						//	ID: 0x20071
						//	Source: API
						//	Type: OTHER
						//	Severity: NOTIFICATION
						//	Message: Buffer detailed info: Buffer object 2 (bound to GL_PIXEL_UNPACK_BUFFER_ARB, usage hint is GL_STREAM_DRAW) has been mapped WRITE_ONLY in SYSTEM HEAP memory (fast).
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20052
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				setupSyncMode();

				initBuffers();
				initVao();
				initProgram();
				initInterfaceTexture();
				gpuTimer.init();
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				active = true;
				setupGpuFlags();
				tempModels.setThreads(renderThreads());
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	private int renderThreads()
	{
		return Math.min(Runtime.getRuntime().availableProcessors() - 1, config.numThreads());
	}

	private void setupGpuFlags()
	{
		int threads = renderThreads();
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
			// zones are culled by zoneInFrustum: a circle around the player and the real frustum (EdgeFade)
			| DrawCallbacks.ZBUF_ZONE_FRUSTUM_CHECK
			| DrawCallbacks.RENDER_THREADS(threads)
		);
	}

	private void startupWorldLoad()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		if (sceneLights != null)
		{
			eventBus.unregister(sceneLights);
			sceneLights = null;
		}
		overlayManager.remove(buildingOverlay);
		lightOcclusion.shutdown();
		lightMap.free();
		hitchMonitor.stop();
		if (weatherAudio != null)
		{
			weatherAudio.shutDown();
			weatherAudio = null;
		}
		if (buildingLights != null)
		{
			eventBus.unregister(buildingLights);
			buildingLights = null;
		}
		if (shaderWatcher != null)
		{
			shaderWatcher.stop();
			shaderWatcher = null;
		}

		clientThread.invoke(() ->
		{
			if (active)
			{
				client.setGpuFlags(0);
				client.setDrawCallbacks(null);
				client.setUnlockedFps(false);
				client.setExpandedMapLoading(0);
				active = false;
			}
			tempModels.free();

			if (lwjglInitted)
			{
				gpuTimer.free();
				shadowMap.free();
				ShadowMap.freeEmpty();
				weather.free();
				skyGrid.free();
				skyField.free();
				roomLight.free();
				lightClusters.free();
				lightMapTexture.free();
				volumetricFog.free();
				clouds.free();
				water.free();
				skyCache.free();
				voxelBuilder.free();

				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();

				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownFbo();
				lwjglInitted = false;
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			if (glCapabilities != null)
			{
				glCapabilities = null;
				// force main buffer provider rebuild to turn off alpha channel
				client.resizeCanvas();
			}
		});
	}

	@Provides
	RuneLightConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RuneLightConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(RuneLightConfig.GROUP))
		{
			glErrorChecks = config.glErrorChecks();
			if (configChanged.getKey().equals("strikeLightning"))
			{
				if (config.strikeLightning())
				{
					weather.getLightning().strikeNow();
					configManager.setConfiguration(RuneLightConfig.GROUP, "strikeLightning", false);
				}
				return;
			}
			if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingZones());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("removeBakedShading") || configChanged.getKey().equals("unlitColors")
				|| configChanged.getKey().equals("smoothShapes"))
			{
				clientThread.invokeLater(() ->
				{
					// colors are baked into the vertex data at upload, so reload the scene
					VertexShading.undoVanillaShading = config.removeBakedShading();
					VertexShading.unlitColors = config.unlitColors();
					VertexShading.smoothFlatFaces = config.smoothShapes();
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("groundBlending"))
			{
				clientThread.invokeLater(() ->
				{
					// blended colors are baked into the terrain vertices at upload
					GroundBlender.enabled = config.groundBlending();
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("water") || configChanged.getKey().equals("hideOtherAreas"))
			{
				clientThread.invokeLater(() ->
				{
					// the bed is baked into the terrain at upload
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				setupGpuFlags();
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode")
				|| configChanged.getKey().equals("debugView") || configChanged.getKey().equals("lightMap"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					// keeps the running programs if the new ones fail, and puts the texture animations back
					reloadShaders();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
					tempModels.setThreads(renderThreads());
					for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
					{
						rts[i].vaoO.free();
						rts[i].vaoA.free();
					}

					int numThreads = config.numThreads();
					rts = new RenderThread[numThreads + 1];
					for (int i = 0; i < rts.length; ++i)
					{
						var rt = new RenderThread();
						rt.modelUploader = new ModelUploader();
						rt.vaoO = new VAOList(i > 0);
						rt.vaoA = new VAOList(i > 0);
						rts[i] = rt;
					}

					setupGpuFlags();
				});
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		RuneLightConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: RuneLightConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	// the scene targets' sample count the shaders are compiled for: they read multisample textures with MSAA
	private int shaderSamples;

	/**
	 * Samples per pixel of the scene targets, 0 for none: the antialiasing mode's, or the window's when the driver
	 * forces its own, within what the multisample textures can hold.
	 */
	private int sceneSamples()
	{
		// bind the default framebuffer to check whether anti-aliasing is forced
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		final int forced = glGetInteger(GL_SAMPLES);
		final int max = Math.min(glGetInteger(GL_MAX_SAMPLES),
			Math.min(glGetInteger(GL_MAX_COLOR_TEXTURE_SAMPLES), glGetInteger(GL_MAX_DEPTH_TEXTURE_SAMPLES)));
		final int samples = Math.min(forced != 0 ? forced : config.antiAliasingMode().getSamples(), max);
		log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, max, forced);
		return samples > 1 ? samples : 0;
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "cluster_config":
					return "#define CLUSTERS_X " + LightClusters.CLUSTERS_X + "\n"
						+ "#define CLUSTERS_Y " + LightClusters.CLUSTERS_Y + "\n"
						+ "#define CLUSTERS_Z " + LightClusters.CLUSTERS_Z + "\n"
						+ "#define CLUSTER_ENTRIES " + LightClusters.CLUSTER_ENTRIES + "\n"
						+ "#define CLUSTER_NEAR " + LightClusters.NEAR + "\n"
						+ "#define CLUSTER_FAR " + LightClusters.FAR + "\n"
						+ "#define ROOM_FILL_BASE " + LightClusters.ROOM_FILL_BASE + "\n"
						+ "#define ROOM_BASE " + LightClusters.ROOM_BASE + "\n"
						+ "#define LIGHT_EXTRA_BASE " + LightClusters.LIGHT_EXTRA_BASE + "\n"
						+ "#define LIGHT_MAP_TABLE_BASE " + LightClusters.LIGHT_MAP_TABLE_BASE + "\n";
				case "light_map_config":
					// the shaders read the static lights from the light map, or from the clusters and the occlusion grids
					return "#define LIGHT_MAP " + (config.lightMap() ? 1 : 0) + "\n"
						+ "#define LIGHT_MAP_LAYERS " + LightMapBake.HEIGHT_LAYERS + "\n"
						+ "#define LIGHT_MAP_LAYER_HEIGHT " + (float) LightMapBake.LAYER_HEIGHT + "\n";
				case "composite_mode":
					// Compile out the diagnostic branches and intermediate samplers in normal rendering.
					// Changing this diagnostic property requires a shader reload.
					return Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true"))
						? "const int compositePart = 0;\n" : "uniform int compositePart;\n";
				case "msaa_config":
					// the scene targets' samples (scene_samplers.glsl): the programs are compiled for them
					return "#define MSAA_SAMPLES " + shaderSamples + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
				case "debug_view":
					// the composite's debug branches compile out; changing the view reloads the shaders
					return "const int debugView = " + config.debugView().ordinal() + ";\n";
			}
			return null;
		});
		ShaderWatcher watcher = shaderWatcher;
		if (watcher != null)
		{
			template.addDirectory(watcher.getDirectory());
		}
		template.addInclude(RuneLightPlugin.class);
		return template;
	}

	/** One of the composite's programs (composite_frag.glsl's COMPOSITE_PASS). */
	private int compileComposite(int pass) throws ShaderException
	{
		return COMPOSITE_PROGRAM.compile(createTemplate()
			.add(key -> key.equals("composite_pass") ? "#define COMPOSITE_PASS " + pass + "\n" : null));
	}

	private void initProgram() throws ShaderException
	{
		shaderSamples = sceneSamples();
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		for (CompositeProgram c : composites)
		{
			c.program = compileComposite(c.pass);
		}
		glAoProgram = AO_PROGRAM.compile(template);
		glDenoiseProgram = DENOISE_PROGRAM.compile(template);
		glShadowProgram = SHADOW_PROGRAM.compile(template);
		glShadowCopyProgram = SHADOW_COPY_PROGRAM.compile(template);
		glShadowMaxProgram = SHADOW_MAX_PROGRAM.compile(template);
		setFogProgram(VolumetricFog.PROGRAM.compile(template));
		water.setSurfaceProgram(WaterSurface.PROGRAM.compile(template), COMPOSITE_TEXTURE_UNIT + 2, WATER_DATA_UNIT,
			WATER_NOISE_UNIT, WATER_CONTACT_UNIT, WATER_WAVES_UNIT_A, WATER_WAVES_UNIT_B);
		weather.setPrograms(weather.compile(template), COMPOSITE_TEXTURE_UNIT, MATERIAL_UNIT, WEATHER_OCCLUSION_UNIT, SHADOW_COMPARE_UNIT, ALPHA_LAYER_UNIT,
			LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, WATER_DATA_UNIT, WEATHER_FIELD_UNIT, LIGHT_VIS_UNIT);
		skyCache.setPrograms(skyCache.compile(template), CLOUD_NOISE_UNIT, WEATHER_SKY_UNIT, SKY_CACHE_UNIT);
		upscaler.setProgram(Upscaler.PROGRAM.compile(template));

		glBindVertexArray(0);

		initUniforms();
	}

	private void initUniforms()
	{
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		sceneEdgeUniforms.locate(glProgram);
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		// program state, kept until the program is linked again
		glUniformBlockBinding(glProgram, glGetUniformBlockIndex(glProgram, "uniforms"), 0);
		glUseProgram(glProgram);
		glUniform1i(glGetUniformLocation(glProgram, "textures"), 1); // texture sampler array is bound to texture1
		glUseProgram(0);
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
		glUseProgram(glUiProgram);
		glUniform1i(glGetUniformLocation(glUiProgram, "tex"), 0);
		glUseProgram(0);

		uniAlphaPass = glGetUniformLocation(glProgram, "alphaPass");
		uniSceneOutdoors = glGetUniformLocation(glProgram, "outdoors");
		uniSceneProceduralSky = glGetUniformLocation(glProgram, "proceduralSky");
		uniSceneCasterDir = glGetUniformLocation(glProgram, "casterDir");
		uniSceneSunIsCaster = glGetUniformLocation(glProgram, "sunIsCaster");
		uniSceneStrikeLight = glGetUniformLocation(glProgram, "strikeLight");
		uniSceneSunAltitude = glGetUniformLocation(glProgram, "sunAltitude");
		uniSceneOvercast = glGetUniformLocation(glProgram, "weatherOvercast");
		uniSceneWeatherLight = glGetUniformLocation(glProgram, "weatherLight");
		sceneWind = Wind.locate(glProgram);
		shadowWind = Wind.locate(glShadowProgram);
		sceneEnvUniforms.locate(glProgram);
		for (CompositeProgram c : composites)
		{
			locateComposite(c);
		}

		uniDenoiseFog = glGetUniformLocation(glDenoiseProgram, "denoiseFog");
		glUseProgram(glDenoiseProgram);
		glUniform1i(glGetUniformLocation(glDenoiseProgram, "sceneAo"), AO_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(glDenoiseProgram, "sceneShadow"), SCENE_SHADOW_UNIT);
		glUniform1i(glGetUniformLocation(glDenoiseProgram, "sceneFog"), FOG_TEXTURE_UNIT);
		glUseProgram(0);
		uniAoRadius = glGetUniformLocation(glAoProgram, "aoRadius");
		uniAoSamples = glGetUniformLocation(glAoProgram, "aoSamples");
		uniAoShadowsEnabled = glGetUniformLocation(glAoProgram, "shadowsEnabled");
		uniAoLightProjRel = glGetUniformLocation(glAoProgram, "lightProjRel");
		uniAoShadowRadius = glGetUniformLocation(glAoProgram, "shadowRadius");
		uniAoShadowDepthRange = glGetUniformLocation(glAoProgram, "shadowDepthRange");
		uniAoShadowMapSize = glGetUniformLocation(glAoProgram, "shadowMapSize");
		uniAoShadowMaxTile = glGetUniformLocation(glAoProgram, "shadowMaxTile");
		uniAoCasterDir = glGetUniformLocation(glAoProgram, "casterDir");
		uniAoSunIsCaster = glGetUniformLocation(glAoProgram, "sunIsCaster");
		uniAoStrikeLight = glGetUniformLocation(glAoProgram, "strikeLight");
		glUseProgram(glAoProgram);
		glUniform1i(glGetUniformLocation(glAoProgram, "shadowRaw"), SHADOW_RAW_UNIT);
		glUniform1i(glGetUniformLocation(glAoProgram, "shadowCompare"), SHADOW_COMPARE_UNIT);
		glUniform1i(glGetUniformLocation(glAoProgram, "shadowMax"), SHADOW_MAX_UNIT);
		glUniform1i(glGetUniformLocation(glAoProgram, "sceneMaterial"), MATERIAL_UNIT);
		glUseProgram(0);
		aoFrame.locate(glAoProgram);
		bindSceneSamplers(glAoProgram);
		weatherGround.locate(glAoProgram, WEATHER_OCCLUSION_UNIT, WEATHER_FIELD_UNIT);
		skyGrid.locateAo(glAoProgram, SKY_GRID_UNIT);
		skyField.locateAo(glAoProgram, SKY_FIELD_UNIT);
		water.locateAo(glAoProgram, WATER_DATA_UNIT);
		// the shadow samplers' units hold an empty depth map until their maps are bound
		ShadowMap.unbind(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		ShadowMap.unbind(WEATHER_OCCLUSION_UNIT, WEATHER_OCCLUSION_UNIT + 1);

		sceneMaterials.locate(glProgram);
		sceneSway.locate(glProgram);

		uniShadowLightProj = glGetUniformLocation(glShadowProgram, "lightProj");
		uniShadowEntityProj = glGetUniformLocation(glShadowProgram, "entityProj");
		uniShadowBase = glGetUniformLocation(glShadowProgram, "base");
		uniShadowTexturesReady = glGetUniformLocation(glShadowProgram, "texturesReady");
		uniShadowEmissiveCasts = glGetUniformLocation(glShadowProgram, "emissiveCasts");
		shadowSway.locate(glShadowProgram);
		glUseProgram(glShadowProgram);
		glUniform1i(glGetUniformLocation(glShadowProgram, "textures"), 1); // game texture array lives on unit 1
		glUseProgram(glShadowCopyProgram);
		glUniform1i(glGetUniformLocation(glShadowCopyProgram, "staticDepth"), 0);
		glUseProgram(glShadowMaxProgram);
		glUniform1i(glGetUniformLocation(glShadowMaxProgram, "source"), 0);
		glUseProgram(0);
	}

	private void locateComposite(CompositeProgram c)
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
		c.lightVisSize = glGetUniformLocation(p, "lightVisSize");
		c.roomHeader = glGetUniformLocation(p, "roomHeader");
		c.interiorTint = glGetUniformLocation(p, "interiorTint");
		c.skylightOn = glGetUniformLocation(p, "skylightOn");
		c.skylightFloor = glGetUniformLocation(p, "skylightFloor");
		c.skyVoid = glGetUniformLocation(p, "skyVoid");
		c.useFog = glGetUniformLocation(p, "useFog");
		c.fogDepth = glGetUniformLocation(p, "fogDepth");
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
		bindSceneSamplers(p);
		glUseProgram(p);
		glUniform1i(glGetUniformLocation(p, "sceneAo"), AO_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneShadow"), SCENE_SHADOW_UNIT);
		glUniform1i(glGetUniformLocation(p, "compositeParts"), COMPOSITE_PARTS_UNIT);
		glUniform1i(glGetUniformLocation(p, "shadowCompare"), SHADOW_COMPARE_UNIT);
		glUniform1i(glGetUniformLocation(p, "lightData"), LIGHT_DATA_UNIT);
		glUniform1i(glGetUniformLocation(p, "clusterData"), CLUSTER_DATA_UNIT);
		glUniform1i(glGetUniformLocation(p, "lightVis"), LIGHT_VIS_UNIT);
		glUniform1i(glGetUniformLocation(p, "lightMap"), LIGHT_VIS_UNIT);
		glUniform1i(glGetUniformLocation(p, "cloudHalf"), CLOUD_HALF_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneAlpha"), ALPHA_LAYER_UNIT);
		glUniform1i(glGetUniformLocation(p, "sceneMaterial"), MATERIAL_UNIT);
		glUseProgram(0);
	}

	private float windSwayAmount()
	{
		return config.windSway() ? config.windSwayStrength() / 100f : 0f;
	}

	private void setFogProgram(int program)
	{
		volumetricFog.setProgram(program, COMPOSITE_TEXTURE_UNIT + 2, SHADOW_COMPARE_UNIT, LIGHT_DATA_UNIT,
			CLUSTER_DATA_UNIT, FOG_NOISE_UNIT, WEATHER_FIELD_UNIT, WATER_DATA_UNIT, FOG_GROUND_UNIT, LIGHT_VIS_UNIT);
	}

	private static void bindSceneSamplers(int program)
	{
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "sceneColor"), COMPOSITE_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(program, "sceneNormal"), COMPOSITE_TEXTURE_UNIT + 1);
		glUniform1i(glGetUniformLocation(program, "sceneDepth"), COMPOSITE_TEXTURE_UNIT + 2);
		glUseProgram(0);
	}

	private void shutdownProgram()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		for (CompositeProgram c : composites)
		{
			glDeleteProgram(c.program);
			c.program = 0;
		}

		glDeleteProgram(glAoProgram);
		glAoProgram = 0;

		glDeleteProgram(glDenoiseProgram);
		glDenoiseProgram = 0;

		glDeleteProgram(glShadowProgram);
		glShadowProgram = 0;

		glDeleteProgram(glShadowCopyProgram);
		glShadowCopyProgram = 0;

		glDeleteProgram(glShadowMaxProgram);
		glShadowMaxProgram = 0;

		weather.freePrograms();
		water.freeSurfaceProgram();
		skyCache.freePrograms();
		upscaler.freeProgram();
	}

	/**
	 * Recompile every program. On failure the previous programs stay in use, so a typo while editing
	 * a shader shows an error instead of taking down the renderer.
	 */
	private boolean reloadShaders()
	{
		glBindVertexArray(vaoUiHandle);
		Template template = createTemplate();
		int scene = 0, ui = 0, ao = 0, denoise = 0, shadow = 0, shadowCopy = 0, shadowMax = 0, fog = 0, waterSurface = 0, upscale = 0;
		int[] composite = new int[composites.length];
		Weather.Programs weatherPrograms;
		SkyCache.Programs skyCachePrograms;
		try
		{
			scene = PROGRAM.compile(template);
			ui = UI_PROGRAM.compile(template);
			for (int i = 0; i < composites.length; ++i)
			{
				composite[i] = compileComposite(composites[i].pass);
			}
			ao = AO_PROGRAM.compile(template);
			denoise = DENOISE_PROGRAM.compile(template);
			shadow = SHADOW_PROGRAM.compile(template);
			shadowCopy = SHADOW_COPY_PROGRAM.compile(template);
			shadowMax = SHADOW_MAX_PROGRAM.compile(template);
			fog = VolumetricFog.PROGRAM.compile(template);
			waterSurface = WaterSurface.PROGRAM.compile(template);
			upscale = Upscaler.PROGRAM.compile(template);
			weatherPrograms = weather.compile(template);
			try
			{
				skyCachePrograms = skyCache.compile(template);
			}
			catch (ShaderException ex)
			{
				glDeleteProgram(weatherPrograms.rain);
				glDeleteProgram(weatherPrograms.snow);
				throw ex;
			}
		}
		catch (ShaderException ex)
		{
			// glDeleteProgram ignores 0
			glDeleteProgram(scene);
			glDeleteProgram(ui);
			for (int program : composite)
			{
				glDeleteProgram(program);
			}
			glDeleteProgram(ao);
			glDeleteProgram(denoise);
			glDeleteProgram(shadow);
			glDeleteProgram(shadowCopy);
			glDeleteProgram(shadowMax);
			glDeleteProgram(fog);
			glDeleteProgram(waterSurface);
			glDeleteProgram(upscale);
			String msg = ex.getMessage();
			if (!msg.equals(shaderError))
			{
				shaderError = msg;
				log.error("Shader reload failed:\n{}", msg);
				chat("Shader error: " + firstLines(msg, 3));
			}
			return false;
		}
		finally
		{
			glBindVertexArray(0);
		}

		shutdownProgram();
		glProgram = scene;
		glUiProgram = ui;
		for (int i = 0; i < composites.length; ++i)
		{
			composites[i].program = composite[i];
		}
		glAoProgram = ao;
		glDenoiseProgram = denoise;
		glShadowProgram = shadow;
		glShadowCopyProgram = shadowCopy;
		glShadowMaxProgram = shadowMax;
		setFogProgram(fog);
		water.setSurfaceProgram(waterSurface, COMPOSITE_TEXTURE_UNIT + 2, WATER_DATA_UNIT, WATER_NOISE_UNIT,
			WATER_CONTACT_UNIT, WATER_WAVES_UNIT_A, WATER_WAVES_UNIT_B);
		weather.setPrograms(weatherPrograms, COMPOSITE_TEXTURE_UNIT, MATERIAL_UNIT, WEATHER_OCCLUSION_UNIT, SHADOW_COMPARE_UNIT, ALPHA_LAYER_UNIT,
			LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, WATER_DATA_UNIT, WEATHER_FIELD_UNIT, LIGHT_VIS_UNIT);
		skyCache.setPrograms(skyCachePrograms, CLOUD_NOISE_UNIT, WEATHER_SKY_UNIT, SKY_CACHE_UNIT);
		upscaler.setProgram(upscale);
		initUniforms();

		if (textureAnimations != null)
		{
			glUseProgram(glProgram);
			glUniform2fv(uniTextureAnimations, textureAnimations);
			glUseProgram(0);
		}

		if (shaderError != null)
		{
			chat("Shaders OK again.");
		}
		shaderError = null;
		log.info("Shaders reloaded");
		return true;
	}

	String getShaderError()
	{
		return shaderError;
	}

	boolean isHotReloading()
	{
		return shaderWatcher != null;
	}

	/**
	 * @return the name of another enabled renderer plugin (stock GPU, 117 HD, ...), or null
	 */
	private String otherRenderer()
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p == this || !pluginManager.isPluginEnabled(p))
			{
				continue;
			}
			if (p instanceof DrawCallbacks || OTHER_RENDERERS.contains(p.getClass().getName()))
			{
				PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
				return d != null ? d.name() : p.getClass().getSimpleName();
			}
		}
		return null;
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("[RuneLight] " + message)
			.build());
	}

	private static String firstLines(String s, int n)
	{
		String[] lines = s.split("\n");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(n, lines.length); ++i)
		{
			if (i > 0)
			{
				sb.append(" | ");
			}
			sb.append(lines[i].trim());
		}
		return sb.toString();
	}

	private void initVao()
	{
		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		Zone.initBuffer();

		for (int i = 0; i < rts.length; ++i)
		{
			rts[i].vaoO = new VAOList(i > 0);
			rts[i].vaoA = new VAOList(i > 0);
		}
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (rts[i].vaoO != null)
			{
				rts[i].vaoO.free();
				rts[i].vaoO = null;
			}
			if (rts[i].vaoA != null)
			{
				rts[i].vaoA.free();
				rts[i].vaoA = null;
			}
		}
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		interfaceFilter = GL_LINEAR;
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		interfaceUpload.free();
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initFbo(int width, int height, int aaSamples, double renderFactor)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = Math.max(1, getScaledValue(transform.getScaleX() * renderFactor, width));
		height = Math.max(1, getScaledValue(transform.getScaleY() * renderFactor, height));

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		sceneTargets.init(width, height, aaSamples, (float) Math.max(1, 2 * renderFactor));
		if (renderFactor != 1)
		{
			upscaler.init(width, height);
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
	}

	private void shutdownFbo()
	{
		sceneTargets.free();
		upscaler.free();
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		tempModels.flush();
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		ctx.cameraX = (int) cameraX;
		ctx.cameraY = (int) cameraY;
		ctx.cameraZ = (int) cameraZ;
		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			cpuTimer.end(CpuTimer.OUTSIDE);
			frameCpuStart = System.nanoTime();
			// mapping makes the client thread wait for the driver: timed apart from the client's own work
			cpuTimer.begin(CpuTimer.BUFFER_MAP);
			for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
			{
				rts[i].vaoO.map();
				rts[i].vaoA.map();
			}
			cpuTimer.end(CpuTimer.BUFFER_MAP);

			this.cameraYaw = client.getCameraYaw();
			this.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			System.arraycopy(((FloatProjection) entityProjection).getProjection(), 0, ctx.projection, 0, 16);
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		// the hitch monitor reads the GPU's times too, but only when someone is reading the debug log
		gpuTimer.setEnabled(config.gpuPassTimings());
		captureFrameState(scene, cameraX, cameraY, cameraZ);
		wadeSteps.update(client.getTopLevelWorldView(), client.getLocalPlayer(), cameraX, cameraZ,
			weather.groundActive() && client.getGameState() == GameState.LOGGED_IN);
		updateEdge(scene, cameraX, cameraZ);

		// UBO
		uniformBuffer.clear();
		uniformBuffer
			.put(cameraYaw)
			.put(cameraPitch)
			.put(cameraX)
			.put(cameraY)
			.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// Setup FBO and anti-aliasing
		{
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final double renderFactor = renderFactor();
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode
				|| lastRenderFactor != renderFactor)
			{
				shutdownFbo();

				int samples = sceneSamples();
				if (samples != shaderSamples)
				{
					// the programs read the scene as multisample textures or not: recompiled to match
					final int compiled = shaderSamples;
					shaderSamples = samples;
					if (!reloadShaders())
					{
						shaderSamples = samples = compiled;
					}
				}

				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples, renderFactor);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
				lastRenderFactor = renderFactor;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneTargets.getRenderFbo());
		}

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

		if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel);
			lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
		}

		// the mip level follows the window's resolution, not the reduced scene's
		final float lodBias = lastRenderFactor < 1 ? (float) (Math.log(lastRenderFactor) / Math.log(2)) : 0;
		if (textureArrayId != -1 && lastTextureLodBias != lodBias)
		{
			textureManager.setLodBias(textureArrayId, lodBias);
			lastTextureLodBias = lodBias;
		}

		// Setup viewport
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();
		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = viewportHeight;
		int renderViewportWidth = viewportWidth;
		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			renderCanvasHeight = dim.height;

			double scaleFactorY = dim.getHeight() / canvasHeight;
			double scaleFactorX = dim.getWidth() / canvasWidth;

			// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
			final int padding = 1;

			// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
			renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
			renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

			// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
			renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
			renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
		}

		glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight, lastRenderFactor);
		{
			final AffineTransform t = clientUI.getGraphicsConfiguration().getDefaultTransform();
			frameViewport[0] = getScaledValue(t.getScaleX() * lastRenderFactor, renderWidthOff);
			frameViewport[1] = getScaledValue(t.getScaleY() * lastRenderFactor, renderCanvasHeight - renderViewportHeight - renderHeightOff);
			frameViewport[2] = getScaledValue(t.getScaleX() * lastRenderFactor, renderViewportWidth);
			frameViewport[3] = getScaledValue(t.getScaleY() * lastRenderFactor, renderViewportHeight);
		}

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final int fogDepth = config.fogDepth();
		final int sky = client.getSkyboxColor();
		// the scene shader only uses fog for transparent faces; opaque ones are fogged in the composite pass
		glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
		glUniform1i(uniAlphaPass, 0);
		glUniform1i(uniSceneOutdoors, frameOutdoors ? 1 : 0);
		glUniform1i(uniSceneProceduralSky, proceduralSky() ? 1 : 0);
		glUniform3fv(uniSceneCasterDir, dayCycle.casterDir);
		glUniform1i(uniSceneSunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
		glUniform2fv(uniSceneStrikeLight, dayCycle.strikeLight);
		glUniform1f(uniSceneSunAltitude, dayCycle.sunAltitude);
		weather.setOvercastUniform(uniSceneOvercast);
		weather.setLightUniform(uniSceneWeatherLight);
		// plants can be held to a low animation rate like the game's own animations: the wind uniforms then only
		// change on those ticks (the scene and shadow programs keep the last values in between)
		long swayNow = System.nanoTime();
		int swayFps = config.windSwayFps().getFps();
		swayTick = swayFps <= 0 || swayNow - lastSwayTick >= 1_000_000_000L / swayFps;
		if (swayTick)
		{
			lastSwayTick = swayNow;
			weather.getWind().apply(sceneWind, weather.isWindStill());
		}
		setEnvironmentUniforms(sceneEnvUniforms);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());
		sceneMaterials.set(config);
		sceneSway.set(windSwayAmount());

		// Brightness happens to also be stored in the texture provider, so we use that
		TextureProvider textureProvider = client.getTextureProvider();
		glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
		glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);
		glUniform1f(uniTextureLightMode, config.brightTextures() ? 1f : 0f);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// avoid textures animating during loading
			glUniform1i(uniTick, client.getGameCycle() & 127);
		}

		// Calculate projection matrix
		float[] projectionMatrix = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projectionMatrix, Mat4.projection(viewportWidth, viewportHeight, 50));
		Mat4.mul(projectionMatrix, Mat4.rotateX(cameraPitch));
		Mat4.mul(projectionMatrix, Mat4.rotateY(cameraYaw));
		System.arraycopy(projectionMatrix, 0, frameViewProj, 0, 16);
		frameInvViewProj = Mat4.inverse(frameViewProj);
		edgeFade.setFrustum(frameViewProj, cameraX, cameraY, cameraZ);
		sceneEdgeUniforms.set(edgeFade);
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// No blending in the opaque pass: its faces all have alpha 1, so blending changed nothing, but it made every
		// attachment read back what it overwrote. setAlphaPass turns it on for the transparency layer.
		glDisable(GL_BLEND);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		gpuTimer.begin(GpuTimer.SCENE);
		drawSkybox(scene, sky, cameraX, cameraY, cameraZ);

		checkGLErrors();
		cpuTimer.begin(CpuTimer.CLIENT_SCENE);
	}

	private void drawSkybox(Scene scene, int sky, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (skybox == null)
		{
			sceneTargets.clear((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f);
			return;
		}

		sceneTargets.clear(0f, 0f, 0f);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, 0, 0, 0, 0,
			(int) cameraX, (int) cameraY, (int) cameraZ, o.vbo.vb, 0);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		tempModels.flush();
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);
		cpuTimer.end(CpuTimer.CLIENT_SCENE);

		gpuTimer.begin(GpuTimer.RESOLVE);
		sceneTargets.resolveAlpha();
		try
		{
			if (frameShadows)
			{
				gpuTimer.begin(GpuTimer.SHADOW);
				cpuTimer.begin(CpuTimer.SHADOW_SUBMIT);
				renderShadowMap();
				cpuTimer.end(CpuTimer.SHADOW_SUBMIT);
			}
			reportShadowMaps();
			cpuTimer.begin(CpuTimer.OCCLUSION);
			if (weather.isActive())
			{
				// usually nothing: the map is only redrawn after the player moves far or the scenery changes
				gpuTimer.begin(GpuTimer.OCCLUSION);
				weather.renderOcclusion(root, glShadowProgram, uniShadowLightProj,
					uniShadowEntityProj, uniShadowBase, uniShadowTexturesReady, textureArrayId != -1, glCapabilities.OpenGL45);
			}
			cpuTimer.end(CpuTimer.OCCLUSION);
		}
		catch (RuntimeException ex)
		{
			cpuTimer.end(CpuTimer.OCCLUSION);
			logLimiter.warn(log, "shadows " + ex, "Error drawing the shadow or weather occlusion map", ex);
			glBindVertexArray(0);
			glUseProgram(0);
			glDisable(GL_POLYGON_OFFSET_FILL);
			glDisable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER);
		}
		gpuTimer.end();
		VAO.recordShadowDraws = false;
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
		cpuTimer.begin(CpuTimer.CLIENT_UI);
	}

	/**
	 * Draw the scene targets to the window. Effects that work on the whole frame (fog, AO, grading) go in
	 * composite_frag.glsl.
	 */
	private void captureFrameState(Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		frameCameraX = cameraX;
		frameCameraY = cameraY;
		frameCameraZ = cameraZ;
		frameSkyColor = client.getSkyboxColor();
		frameHasSkybox = scene.getSkybox() != null;
		{
			// the game's own sky color is black where nothing exists around the scene; areas may still call it
			// open sky (a player house has daylight)
			int r = frameSkyColor >> 16 & 0xFF, g = frameSkyColor >> 8 & 0xFF, b = frameSkyColor & 0xFF;
			frameSkyVoid = !frameHasSkybox && r + g + b <= 24;
		}
		frameFogDepth = config.fogDepth();
		frameDrawDistance = getDrawDistance() * Perspective.LOCAL_TILE_SIZE;

		cpuTimer.begin(CpuTimer.ENVIRONMENT);
		frameOpenSky = updateEnvironment(scene);
		cpuTimer.end(CpuTimer.ENVIRONMENT);
		frameOutdoors = config.sunLighting() && frameOpenSky;

		frameGameMillis = GameClock.gameMillis(System.currentTimeMillis(), config.dayCycleMinutes());
		dayCycle.update(frameGameMillis, config.dayCycleMinutes(), config.fixedHour());

		frameShadows = frameOutdoors && config.shadows() && client.getGameState() == GameState.LOGGED_IN;
		VAO.clearShadowDraws();
		VAO.recordShadowDraws = frameShadows;

		cpuTimer.begin(CpuTimer.WEATHER);
		weather.getLightning().setSunStrength(dayCycle.directStrength(volumetricFog.overcast[0], volumetricFog.overcast[1]));
		updateWeather(cameraX, cameraY, cameraZ);
		dayCycle.applyStrike(weather.getLightning());
		updateSkylight();
		cpuTimer.end(CpuTimer.WEATHER);

		frameFog = false;
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (config.volumetricFog() && lp != null && client.getGameState() == GameState.LOGGED_IN && volumetricFog.hasGround())
		{
			// thicker in rain and snow, and in areas 117 HD gives fog (its fogDepth: ~6 faint, 50 thick)
			float areaFog = config.areaEnvironments() ? environmentManager.getFogDepth() * config.areaFogStrength() / 100f : 0;
			volumetricFog.areaDensity = weather.fogMultiplier()
				* (1 + weather.areaFogWeight() * Math.min(areaFog / AREA_FOG_DEPTH, 2));
			volumetricFog.areaHeight = weather.fogHeightMultiplier();
			volumetricFog.weatherField = weather.getField();
			volumetricFog.weatherFog = weather.fogMultiplier();
			System.arraycopy(weather.getFogTint(), 0, volumetricFog.areaTint, 0, 3);
			volumetricFog.updateParameters(config, lp.getX(), lp.getY(), client.getTopLevelWorldView().getPlane(), frameDrawDistance);
			frameFog = true;
		}
	}

	/**
	 * Where the drawn world ends this frame (EdgeFade): a circle of the draw distance around the focal point, inside
	 * the larger square the client is told to draw, with a fade as wide as the weather's fog asks.
	 */
	private void updateEdge(Scene scene, float cameraX, float cameraZ)
	{
		int drawDistance = getDrawDistance();
		float fx = client.getCameraFocalPointX(), fz = client.getCameraFocalPointZ();
		float offset = (float) Math.hypot(cameraX - fx, cameraZ - fz);
		scene.setDrawDistance(EdgeFade.clientDrawDistance(drawDistance, offset));
		// read back in case the client limits it
		float radius = EdgeFade.radius(drawDistance, scene.getDrawDistance(), offset);
		float areaFog = config.areaEnvironments() && !frameHasSkybox
			? environmentManager.getFogDepth() * config.areaFogStrength() / 100f * weather.distanceFogScale() : 0;
		edgeFade.update(fx, fz, radius, client.getExpandedMapLoading(), config.edgeFadeTiles(),
			EdgeFade.fogginess(weather.fogMultiplier(), areaFog));
	}

	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY)
	{
		// the client asks for the top level only, in its zone indices (as drawZoneOpaque gets them)
		int offset = SCENE_OFFSET >> 3;
		return edgeFade.zoneVisible((zx - offset) << 10, (zz - offset) << 10, minY, maxY);
	}

	/** Whether the composite draws the procedural sky: not over the game's own skybox, nor in a void. */
	private boolean proceduralSky()
	{
		return config.proceduralSky() && !frameHasSkybox && !frameSkyVoid;
	}

	/**
	 * Whether the skylight (or the roof rule for lights) is wanted this frame and where the player is, for the sky
	 * grid; the light flowing in under cover follows the walls.
	 */
	private void updateSkylight()
	{
		WorldView wv = client.getTopLevelWorldView();
		boolean wanted = wv != null && frameOutdoors && client.getGameState() == GameState.LOGGED_IN;
		frameSkylightWanted = wanted;
		int plane = wv != null ? wv.getPlane() : 0;
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		Scene scene = wv != null ? wv.getScene() : null;
		if (wanted && lp != null && scene != null)
		{
			frameSkylightTileX = scene.getBaseX() + lp.getX() / (float) Perspective.LOCAL_TILE_SIZE;
			frameSkylightTileZ = scene.getBaseY() + lp.getY() / (float) Perspective.LOCAL_TILE_SIZE;
		}
		BuildingLights buildings = buildingLights;
		if (wanted && config.skylightFlow() && buildings != null)
		{
			skyField.update(buildings.getWalls(), buildings.getRooms(), buildings.getFloors(), plane, config.skylightReach());
		}
		else
		{
			skyField.free();
		}
	}

	private void updateWeather(float cameraX, float cameraY, float cameraZ)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		float fx = cameraX, fy = cameraY, fz = cameraZ;
		int plane = wv.getPlane();
		Player player = client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (lp != null)
		{
			fx = lp.getX();
			fz = lp.getY();
			fy = Perspective.getTileHeight(client, lp, plane);
		}
		boolean sky = frameOpenSky && client.getGameState() == GameState.LOGGED_IN;
		String biomeName = config.areaEnvironments() ? environmentManager.getBiome() : null;
		ShaderWatcher watcher = shaderWatcher;
		if (watcher != null)
		{
			WeatherData.reloadIfChanged(watcher.getDirectory(), gson);
		}
		climate.update(frameGameMillis, config.weatherSeasonDays());
		weather.updateField(sky ? wv.getScene() : null, plane, environmentManager, config.areaEnvironments(), biomeName,
			config.weatherMode(), climate, config.weatherIntensity(), config.weatherBuildup(), dayCycle.hour(), fx, fz);
		weather.update(config.weatherBuildup(), config.weatherVarying(), config.weatherOvercast(),
			config.weatherLightning(), sky, fx, fy, fz, plane);
		weather.getOvercast(volumetricFog.overcast);
		updateWeatherAudio(fx, fy, fz);
	}

	/**
	 * Hand the weather's sounds this frame's rain, how far indoors the player is, the volume (the setting times the
	 * game's own sound effects volume) and any thunder that has just begun.
	 */
	private void updateWeatherAudio(float x, float y, float z)
	{
		WeatherAudio audio = weatherAudio;
		if (audio == null)
		{
			return;
		}
		float volume = 0;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Preferences prefs = client.getPreferences();
			volume = config.weatherSounds() / 100f * (prefs != null ? Math.min(1, prefs.getSoundEffectVolume() / 127f) : 1);
		}
		BuildingLights buildings = buildingLights;
		// the sky seen straight up at the player's head, from the skylight's grid; the room map where there is none
		WorldView wv = client.getTopLevelWorldView();
		float sky = skyGrid.upVisibility(wv != null ? wv.getScene() : null, x, y - WEATHER_HEAD, z);
		float indoors = WeatherAudio.indoorMix(sky, buildings != null ? buildings.getRooms() : null, x, y - 40, z);
		audio.update(weather.rainLoudness(), indoors, volume);
		// the thunder plays at any volume above 0; the light follows its rumble either way
		if (weather.getLightning().takeThunder(thunderStrike))
		{
			Rumble rumble = audio.thunder(thunderStrike[0], thunderStrike[1], volume > 0);
			if (rumble != null)
			{
				weather.getLightning().rumble(rumble);
			}
		}
	}

	/**
	 * Find the area the camera looks at and whether it is under the open sky. Without area data, the game's
	 * black underground sky is the only hint.
	 */
	private boolean updateEnvironment(Scene scene)
	{
		if (!config.areaEnvironments())
		{
			int r = frameSkyColor >> 16 & 0xFF, g = frameSkyColor >> 8 & 0xFF, b = frameSkyColor & 0xFF;
			return r + g + b > 24;
		}
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		environmentManager.update(loggedIn ? scene : null,
			(int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ(),
			client.getTopLevelWorldView().getPlane(), frameSkyColor,
			(System.currentTimeMillis() - startMillis) / 1000f);
		return environmentManager.isOverworld();
	}

	private void setEnvironmentUniforms(EnvironmentUniforms u)
	{
		boolean on = config.areaEnvironments();
		// the game's own skyboxes have their own colors; area fog would not match them, like in 117 HD
		float fog = frameHasSkybox ? 0 : config.areaFogStrength() / 100f;
		u.set(on ? environmentManager : null, config.areaLightStrength() / 100f, fog * weather.distanceFogScale(),
			getDrawDistance(), config.volumetricFogMaxCover() / 100f, edgeFade.focalX, edgeFade.focalZ);
	}

	/**
	 * Draw the opaque scene near the player from the sun or moon. Runs after the main scene so it can reuse the
	 * dynamic models already uploaded this frame; the composite pass then shadows the visible pixels.
	 */
	private void renderShadowMap()
	{
		SceneContext ctx = root;
		if (ctx == null || ctx.hideRoofIds == null || glShadowProgram == 0)
		{
			return;
		}

		int size = config.shadowResolution().getSize();
		if (!shadowMap.isInitialized() || shadowMap.getSize() != size)
		{
			shadowMap.free();
			shadowMap.init(size);
		}

		float fx = frameCameraX, fy = frameCameraY, fz = frameCameraZ;
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			LocalPoint lp = player.getLocalLocation();
			if (lp != null)
			{
				fx = lp.getX();
				fz = lp.getY();
				fy = Perspective.getTileHeight(client, lp, client.getTopLevelWorldView().getPlane());
			}
		}

		float radius = config.shadowDistance() * Perspective.LOCAL_TILE_SIZE;
		float depthRange = radius + 4096;
		final boolean cache = SHADOW_CACHE;
		if (!cache)
		{
			shadowMap.freeCache();
			shadowMap.update(dayCycle.casterDir, fx, fy, fz, frameCameraX, frameCameraY, frameCameraZ,
				radius, depthRange, glCapabilities.OpenGL45);
			glBindFramebuffer(GL_FRAMEBUFFER, shadowMap.getFbo());
			glViewport(0, 0, size, size);
			glClearDepth(1.0);
			glClear(GL_DEPTH_BUFFER_BIT);
		}

		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		// both faces cast: OSRS models are not reliably closed, and backfaces reduce light leaking
		glDisable(GL_CULL_FACE);
		glEnable(GL_POLYGON_OFFSET_FILL);
		glPolygonOffset(1.0f, 1.0f);

		glUseProgram(glShadowProgram);
		if (swayTick)
		{
			weather.getWind().apply(shadowWind, weather.isWindStill());
		}
		glUniform1i(uniShadowTexturesReady, textureArrayId != -1 ? 1 : 0);
		glUniform1i(uniShadowEmissiveCasts, config.emissiveMaterials() ? 0 : 1);
		glUniformMatrix4fv(uniShadowEntityProj, false, IDENTITY);
		// swaying plants are left out of the static cache and redrawn each frame (Wind.java)
		final boolean swayShadows = windSwayAmount() > 0 && config.windSwayShadows();
		shadowSway.set(swayShadows ? windSwayAmount() : 0);

		// Zone and VAO draw through these shared uniform locations; point them at the shadow program
		final int savedBase = uniBase, savedEntityProj = uniEntityProj, savedEntityTint = uniEntityTint;
		uniBase = uniShadowBase;
		uniEntityProj = uniShadowEntityProj;
		uniEntityTint = -1;
		try
		{
			if (cache)
			{
				renderStaticShadows(ctx, size, fx, fy, fz, radius, depthRange, swayShadows);
				// this frame's map starts as a copy of the static one
				gpuTimer.begin(GpuTimer.SHADOW_COPY);
				glBindVertexArray(vaoUiHandle);
				shadowMap.useFront(frameCameraX, frameCameraY, frameCameraZ, glShadowCopyProgram);
				glUseProgram(glShadowProgram);
				gpuTimer.begin(GpuTimer.SHADOW);
				glUniformMatrix4fv(uniShadowLightProj, false, shadowMap.lightProj);
				if (swayShadows)
				{
					shadowZones(ctx, fx, fz, radius, null, 0, Integer.MAX_VALUE, Zone.SWAY_ONLY);
				}
			}
			else
			{
				glUniformMatrix4fv(uniShadowLightProj, false, shadowMap.lightProj);
				shadowZones(ctx, fx, fz, radius, null, 0, Integer.MAX_VALUE, Zone.SWAY_ALL);
			}

			// players, NPCs and other models drawn this frame
			gpuTimer.begin(GpuTimer.SHADOW_MODELS);
			glUniform3i(uniShadowBase, 0, 0, 0);
			glUniformMatrix4fv(uniShadowEntityProj, false, IDENTITY);
			for (int i = 0; i < VAO.shadowDrawCount; ++i)
			{
				VAO.ShadowDraw d = VAO.shadowDraws.get(i);
				glUniformMatrix4fv(uniShadowEntityProj, false, d.projection);
				glBindVertexArray(d.vao);
				glDrawArrays(GL_TRIANGLES, d.first, d.count);
			}
		}
		finally
		{
			uniBase = savedBase;
			uniEntityProj = savedEntityProj;
			uniEntityTint = savedEntityTint;
		}

		glBindVertexArray(0);
		glDisable(GL_POLYGON_OFFSET_FILL);
		glDepthFunc(GL_GREATER);
		glDisable(GL_DEPTH_TEST);
		glUseProgram(0);
		checkGLErrors();
	}

	// Static shadow cache. The scenery is drawn around a focus snapped to this many world units, into a map this
	// much larger, so it still covers the player until they walk out of the snapped cell.
	private static final int SHADOW_CACHE_SNAP = 256;
	// A new static map is drawn over this many frames while the old one is still used, so no frame pays for it all.
	private static final int SHADOW_BUILD_FRAMES = 3;
	// The light may turn until the shadow of a caster this tall (world units) has moved this many texels, which
	// depends on how low the light is: shadows lengthen fast near the horizon.
	private static final float SHADOW_CACHE_HEIGHT = 512;
	private static final float SHADOW_CACHE_TEXELS = 1.0f;

	private boolean shadowStaticDirty;
	private int[] shadowBuildZones = new int[64];
	private int shadowBuildCount, shadowBuildNext, shadowBuildPerFrame;
	// the map being drawn is kept beside the one in use, not used (a strike's bolt)
	private boolean shadowBuildHeld;

	/**
	 * Keep the static shadow map current: start a new one when the light has turned or the player has walked out of
	 * the snapped cell, and draw this frame's share of it.
	 */
	private void renderStaticShadows(SceneContext ctx, int size, float fx, float fy, float fz, float radius,
		float depthRange, boolean swayShadows)
	{
		final int sway = swayShadows ? Zone.SWAY_EXCLUDE : Zone.SWAY_ALL;
		final boolean roofShadows = config.roofShadows();
		int key = java.util.Objects.hash(System.identityHashCode(ctx), ctx.minLevel, ctx.level, ctx.maxLevel,
			roofShadows, roofShadows ? 0 : ctx.hideRoofIds.hashCode(), config.emissiveMaterials(), textureArrayId != -1,
			size, radius, sway);
		shadowMap.checkCacheKey(key);
		if (shadowStaticDirty)
		{
			shadowStaticDirty = false;
			shadowMap.invalidateCache();
		}

		ShadowMap.View front = shadowMap.frontView();
		if (!shadowMap.isBuilding() && front != null && shadowStale(front, fx, fy, fz, radius, size))
		{
			// the light turned to where the held map (a lightning strike's, or the sun's it left) is drawn for: use it
			ShadowMap.View held = shadowMap.heldView();
			if (held != null && !shadowStale(held, fx, fy, fz, radius, size))
			{
				glBindVertexArray(vaoUiHandle);
				shadowMap.swapFront(glShadowMaxProgram);
				return;
			}
		}
		// a strike needs the map for its bolt drawn before its flash, beside the one in use
		float[] bolt = weather.getLightning().getBoltDir();
		boolean holdBolt = !shadowMap.isBuilding() && front != null && bolt != null && !shadowMap.isStale()
			&& !shadowStale(front, fx, fy, fz, radius, size)
			&& !directionMatches(front, bolt) && !directionMatches(shadowMap.heldView(), bolt);
		if (!shadowMap.isBuilding()
			&& (front == null || shadowMap.isStale() || shadowStale(front, fx, fy, fz, radius, size) || holdBolt))
		{
			shadowBuildHeld = holdBolt;
			float sx = Math.round(fx / SHADOW_CACHE_SNAP) * (float) SHADOW_CACHE_SNAP;
			float sz = Math.round(fz / SHADOW_CACHE_SNAP) * (float) SHADOW_CACHE_SNAP;
			hitchMonitor.job(HitchMonitor.STATIC_SHADOWS);
			shadowMap.startBuild(holdBolt ? bolt : dayCycle.casterDir, sx, fy, sz, radius + SHADOW_CACHE_SNAP,
				depthRange + SHADOW_CACHE_SNAP, glCapabilities.OpenGL45);
			shadowBuildCount = 0;
			shadowZones(ctx, sx, sz, radius + SHADOW_CACHE_SNAP, null, 0, 0, sway);
			shadowBuildNext = 0;
			// the first map is needed at once
			int frames = front == null ? 1 : SHADOW_BUILD_FRAMES;
			shadowBuildPerFrame = (shadowBuildCount + frames - 1) / frames;
		}
		if (!shadowMap.isBuilding())
		{
			return;
		}

		ShadowMap.View v = shadowMap.buildingView();
		glBindFramebuffer(GL_FRAMEBUFFER, shadowMap.getBuildFbo());
		glViewport(0, 0, size, size);
		glUniformMatrix4fv(uniShadowLightProj, false, v.proj);
		int end = Math.min(shadowBuildNext + shadowBuildPerFrame, shadowBuildCount);
		shadowZones(ctx, v.focusX, v.focusZ, v.radius, shadowBuildZones, shadowBuildNext, end, sway);
		shadowBuildNext = end;
		if (shadowBuildNext >= shadowBuildCount)
		{
			glBindVertexArray(vaoUiHandle);
			if (shadowBuildHeld)
			{
				shadowMap.holdBuild();
			}
			else
			{
				shadowMap.finishBuild(glShadowMaxProgram);
			}
		}
	}

	// cosine of the angle within which a static shadow map counts as drawn for a light
	private static final float SHADOW_MATCH = 0.9999f;

	private static boolean directionMatches(ShadowMap.View v, float[] d)
	{
		return v != null && d[0] * v.dir[0] + d[1] * v.dir[1] + d[2] * v.dir[2] > SHADOW_MATCH;
	}

	/**
	 * Tell a strike what the shadow maps came to this frame: whether one is drawn for the bolt, and whether the map
	 * in use is for the light in use. Without shadows, both are true.
	 */
	private void reportShadowMaps()
	{
		float[] bolt = weather.getLightning().getBoltDir();
		ShadowMap.View front = shadowMap.frontView();
		boolean ready = !frameShadows
			|| bolt != null && (directionMatches(front, bolt) || directionMatches(shadowMap.heldView(), bolt));
		weather.getLightning().setShadowMaps(ready, !frameShadows || directionMatches(front, dayCycle.casterDir));
	}

	private boolean shadowStale(ShadowMap.View v, float fx, float fy, float fz, float radius, int size)
	{
		float half = SHADOW_CACHE_SNAP * 0.5f;
		if (Math.abs(fx - v.focusX) > half || Math.abs(fz - v.focusZ) > half || Math.abs(fy - v.focusY) > SHADOW_CACHE_SNAP)
		{
			return true;
		}
		float[] d = dayCycle.casterDir;
		// sine of the angle between the light now and when the map was drawn
		float cx = d[1] * v.dir[2] - d[2] * v.dir[1];
		float cy = d[2] * v.dir[0] - d[0] * v.dir[2];
		float cz = d[0] * v.dir[1] - d[1] * v.dir[0];
		float angle = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (d[0] * v.dir[0] + d[1] * v.dir[1] + d[2] * v.dir[2] < 0)
		{
			return true;
		}
		// a shadow of height h is h / tan(elevation) long; it moves h / sin^2(elevation) per radian
		float sinElevation = Math.max(-d[1], 0.1f);
		float texel = 2 * radius / size;
		return angle > SHADOW_CACHE_TEXELS * texel * sinElevation * sinElevation / SHADOW_CACHE_HEIGHT;
	}

	/**
	 * The static zones around a focus. With list null and to 0, only collects them into shadowBuildZones; with list
	 * null and to MAX_VALUE, draws all of them; else draws list[from..to).
	 */
	private void shadowZones(SceneContext ctx, float fx, float fz, float radius, int[] list, int from, int to, int sway)
	{
		// zones around the focus, with margin for tall casters just outside the map
		int offset = SCENE_OFFSET >> 3;
		float reach = radius + 2048;
		final boolean roofShadows = config.roofShadows();
		if (list != null)
		{
			for (int i = from; i < to; ++i)
			{
				shadowZone(ctx, list[i] >> 16, list[i] & 0xFFFF, offset, roofShadows, sway);
			}
			return;
		}
		boolean collect = to == 0;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			float cx = ((x - offset) << 10) + 512;
			if (Math.abs(cx - fx) > reach)
			{
				continue;
			}
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				float cz = ((z - offset) << 10) + 512;
				if (Math.abs(cz - fz) > reach)
				{
					continue;
				}
				if (!collect)
				{
					shadowZone(ctx, x, z, offset, roofShadows, sway);
					continue;
				}
				Zone zone = ctx.zones[x][z];
				if (zone.initialized && zone.sizeO > 0)
				{
					if (shadowBuildCount == shadowBuildZones.length)
					{
						shadowBuildZones = Arrays.copyOf(shadowBuildZones, shadowBuildCount * 2);
					}
					shadowBuildZones[shadowBuildCount++] = x << 16 | z;
				}
			}
		}
	}

	private static void shadowZone(SceneContext ctx, int x, int z, int offset, boolean roofShadows, int sway)
	{
		Zone zone = ctx.zones[x][z];
		if (!zone.initialized || zone.sizeO <= 0)
		{
			return;
		}
		if (roofShadows)
		{
			// every level and every roof, including ones hidden from the camera
			zone.renderOpaque(x - offset, z - offset, ctx.minLevel, ctx.level, 3, Collections.emptySet(), sway);
		}
		else
		{
			zone.renderOpaque(x - offset, z - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds, sway);
		}
	}

	/**
	 * Transparent faces go to their own layer, blended as premultiplied "over" so the composite can put the layer
	 * on top of the lit opaque scene. They don't write depth (Zone and VAO already turn depth writes off).
	 */
	private void setAlphaPass(boolean alpha)
	{
		sceneTargets.setAlphaPass(alpha);
		if (alpha)
		{
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		}
		else
		{
			// the opaque pass writes without blending (preSceneDrawToplevel)
			glDisable(GL_BLEND);
		}
		glUniform1i(uniAlphaPass, alpha ? 1 : 0);
	}

	/** Whether the window's framebuffer has a stencil (windowStencil); call with it bound. */
	private boolean hasWindowStencil()
	{
		if (windowStencil == 0)
		{
			int bits = glGetFramebufferAttachmentParameteri(GL_FRAMEBUFFER, GL_STENCIL,
				GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
			windowStencil = glGetError() == GL_NO_ERROR && bits > 0 ? 1 : -1;
			log.debug("Window framebuffer stencil bits: {}", bits);
		}
		return windowStencil > 0;
	}

	private void setFrameUniforms(FrameUniforms u)
	{
		glUniformMatrix4fv(u.viewProj, false, frameViewProj);
		glUniformMatrix4fv(u.invViewProj, false, frameInvViewProj);
		glUniform4i(u.viewport, frameViewport[0], frameViewport[1], frameViewport[2], frameViewport[3]);
		glUniform1i(u.depthZeroToOne, glCapabilities.OpenGL45 ? 1 : 0);
		glUniform1f(u.halfRatio, sceneTargets.getHalfRatio());
	}

	/**
	 * Bind the shadow map, and gather, cull, upload and bind this frame's point lights and rooms: for the composite and
	 * the fog.
	 */
	private void prepareLighting()
	{
		if (frameShadows && shadowMap.isInitialized())
		{
			shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		}

		boolean points = config.pointLights();
		BuildingLights buildingRooms = buildingLights;
		// the light map needs the rooms' floors, to know which storey a surface is on
		RoomMap rooms = buildingRooms != null && (config.roomLights() || config.noFogIndoors() || config.lightMap())
			? buildingRooms.getRooms() : null;
		volumetricFog.rooms = config.noFogIndoors() ? rooms : null;
		volumetricFog.openRooms = null;
		framePointLights = points;
		frameLightClusters = points || rooms != null;
		if (frameLightClusters)
		{
			if (!lightClusters.isInitialized())
			{
				lightClusters.init();
			}
			cpuTimer.begin(CpuTimer.LIGHTS);
			if (points)
			{
				collectLights();
			}
			else
			{
				frameLights.clear();
				frameLightMap = false;
			}
			cpuTimer.end(CpuTimer.LIGHTS);
			lightClusters.setRooms(rooms, config.roomLights());
			updateRoomLight(config.buildingLights() ? rooms : null);
			lightClusters.setLightMap(frameLightMap ? lightMap : null);
			SceneContext roofCtx = root;
			boolean openRoofs = frameFog && rooms != null && config.roomLights() && config.openRoofFog() && roofCtx != null;
			if (openRoofs)
			{
				openRooms.update(buildingRooms.getResult(), roofCtx.hideRoofIds, roofCtx.level);
			}
			openRoofs = openRoofs && openRooms.count() > 0;
			lightClusters.setOpenRooms(openRoofs ? openRooms : null);
			volumetricFog.openRooms = openRoofs ? openRooms : null;
			lightClusters.setOcclusion(config.lightWallOcclusion() ? lightOcclusion.getAtlas() : null,
				config.lightRoofOcclusion() && frameSkylight);
			cpuTimer.begin(CpuTimer.CLUSTERS);
			// the fog integrates the lights along each pixel's ray from per-column lists
			lightClusters.setFog(frameFog, volumetricFog.getCenterX() - frameCameraX,
				volumetricFog.getCenterZ() - frameCameraZ, volumetricFog.getRadius(),
				volumetricFog.getTop() - frameCameraY, volumetricFog.getMinGlow());
			lightClusters.cull(frameLights, frameViewProj, frameCameraX, frameCameraY, frameCameraZ);
			cpuTimer.end(CpuTimer.CLUSTERS);
			cpuTimer.begin(CpuTimer.LIGHT_UPLOAD);
			lightClusters.upload();
			cpuTimer.end(CpuTimer.LIGHT_UPLOAD);
			lightClusters.bind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, LIGHT_VIS_UNIT);
			if (frameLightMap)
			{
				lightMapTexture.bind(LIGHT_VIS_UNIT);
			}
		}
	}

	/**
	 * Work out the fill the rooms are given for the time of day, and hand it to the light buffer. Caves and dungeons
	 * have no buildings, and frameOutdoors stands in until areas are classified.
	 */
	private void updateRoomLight(RoomMap rooms)
	{
		long now = System.nanoTime();
		float dt = roomLightNanos == 0 ? 0 : Math.min((now - roomLightNanos) / 1e9f, 0.5f);
		roomLightNanos = now;
		BuildingLights buildings = buildingLights;
		roomLight.setRooms(rooms);
		if (rooms != null && buildings != null)
		{
			roomLight.followWalls(buildings.getWalls(), buildings.getFloors(), config.skylightReach());
		}
		float target = config.buildingLights() && frameOutdoors
			? RoomLight.target(dayCycle.sunAltitude, config.buildingLightsByDay(), config.buildingLightBrightness() / 100f) : 0;
		roomLight.update(dt, target, dayCycle.sunAltitude);
		lightClusters.setRoomFill(rooms != null ? roomLight : null);
	}

	/**
	 * Give the light map the scene's walls, solid objects and models, the time of day and the view, and send the blocks
	 * it has baked. Before the lights are collected: the ones it carries leave the list.
	 *
	 * @return whether the map carries the static lights this frame
	 */
	private boolean updateLightMap(float night)
	{
		BuildingLights b = buildingLights;
		WallMap walls = b != null ? b.getWalls() : null;
		RoomMap built = b != null ? b.getRooms() : null;
		byte[] floors = b != null ? b.getFloors() : null;
		frameLightMapWanted = config.lightMap() && config.pointLights();
		SceneLights lights = sceneLights;
		if (lights == null)
		{
			return false;
		}
		if (!frameLightMapWanted || walls == null || built == null || floors == null)
		{
			lights.setLightMap(null);
			if (!frameLightMapWanted)
			{
				lightMapTexture.free();
			}
			roomLight.setCells(0);
			return false;
		}
		cpuTimer.begin(CpuTimer.LIGHT_MAP);
		// a scene's models are not the previous scene's: the lights wait for them a moment (LightMap.setBlockers)
		boolean modelsAwaited = voxelBuilder.modelsAwaited();
		ModelBlockers models = modelsAwaited ? null : voxelBuilder.getModelBlockers();
		if (walls != blockerWalls || models != blockerModels || built != blockerRooms || floors != blockerFloors)
		{
			lightBlockers = new LightBlockers(walls, models, built.floors, floors);
			blockerWalls = walls;
			blockerModels = models;
			blockerRooms = built;
			blockerFloors = floors;
		}
		lightMap.setBlockers(lightBlockers, modelsAwaited);
		lightMap.setNight(night);
		lightMap.setFocus(edgeFade.focalX, edgeFade.focalZ);
		gpuTimer.begin(GpuTimer.LIGHT_MAP);
		boolean ready = lightMapTexture.ensure(lightMap.cells());
		if (lightMapTexture.generation() != roomLightTexture)
		{
			// a new, empty texture
			roomLightTexture = lightMapTexture.generation();
			roomLight.clearLight();
		}
		roomLight.setCells(ready ? lightMap.cells() : 0);
		if (ready)
		{
			lightMap.update(LIGHT_MAP_BLOCKS_PER_FRAME, LIGHT_MAP_SEND_BUDGET_NANOS, LIGHT_MAP_BLOCKS_MAX, lightMapSink);
		}
		gpuTimer.end();
		cpuTimer.end(CpuTimer.LIGHT_MAP);
		lights.setLightMap(ready ? lightMap : null);
		return ready;
	}

	/**
	 * Use one of the composite's programs and set this frame's uniforms on it: the same for each, whatever it uses.
	 * After prepareLighting and the sky cache's easeComposite.
	 */
	private void useComposite(CompositeProgram c)
	{
		glUseProgram(c.program);
		setFrameUniforms(c.frame);
		skyCache.setComposite(c.skyCache, weather.skyHaze(), config.starBrightness() / 100f);
		glUniform1i(c.aoEnabled, config.ambientOcclusion() ? 1 : 0);
		glUniform1f(c.aoStrength, config.aoStrength() / 100f);

		glUniform3f(c.cameraPos, frameCameraX, frameCameraY, frameCameraZ);
		glUniform1f(c.time, compositeTime);
		glUniform1i(c.outdoors, frameOutdoors ? 1 : 0);
		glUniform1i(c.proceduralSky, proceduralSky() ? 1 : 0);
		glUniform1i(c.skyVoid, frameSkyVoid ? 1 : 0);
		glUniform1i(c.lightingEnabled, 1);
		glUniform3fv(c.sunDir, dayCycle.sunDir);
		glUniform3fv(c.moonDir, dayCycle.moonDir);
		glUniform3fv(c.casterDir, dayCycle.casterDir);
		glUniform1i(c.sunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
		glUniform2fv(c.strikeLight, dayCycle.strikeLight);
		glUniform1f(c.sunAltitude, dayCycle.sunAltitude);
		weather.setOvercastUniform(c.overcast);
		weather.setLightUniform(c.weatherLight);
		setEnvironmentUniforms(c.env);

		boolean shadows = frameShadows && shadowMap.isInitialized();
		glUniform1i(c.shadowsEnabled, shadows ? 1 : 0);
		if (shadows)
		{
			glUniformMatrix4fv(c.lightProjRel, false, shadowMap.lightProjRel);
			glUniform1f(c.shadowRadius, shadowMap.getRadius());
			glUniform1f(c.shadowDepthRange, shadowMap.getDepthRange());
			glUniform1f(c.shadowMapSize, shadowMap.getSize());
		}

		if (frameLightClusters)
		{
			glUniform2f(c.lightVisSize, lightClusters.getAtlasWidth(), lightClusters.getAtlasHeight());
			glUniform4fv(c.roomHeader, lightClusters.getRoomHeader());
		}
		else
		{
			// nothing bound: no rooms
			glUniform4f(c.roomHeader, 0, 0, 0, 0);
		}
		glUniform1i(c.pointLights, framePointLights ? 1 : 0);
		glUniform1i(c.skylightOn, frameSkylight ? 1 : 0);
		glUniform1f(c.skylightFloor, config.skylightFloor() / 100f);
		glUniform3f(c.interiorTint, BuildingLights.FILL_TINT[0], BuildingLights.FILL_TINT[1], BuildingLights.FILL_TINT[2]);

		glUniform1i(c.useFog, frameFogDepth > 0 ? 1 : 0);
		glUniform1i(c.fogDepth, frameFogDepth);
		glUniform1i(c.drawDistance, frameDrawDistance);
		c.edge.set(edgeFade);
		glUniform1i(c.expandedChunks, client.getExpandedMapLoading());
		glUniform3f(c.clientSkyColor,
			(frameSkyColor >> 16 & 0xFF) / 255f, (frameSkyColor >> 8 & 0xFF) / 255f, (frameSkyColor & 0xFF) / 255f);

		Scene groundScene = client.getTopLevelWorldView().getScene();
		c.ground.set(frameGround && groundScene != null,
			groundScene != null ? groundScene.getBaseX() * (float) Perspective.LOCAL_TILE_SIZE : 0,
			groundScene != null ? groundScene.getBaseY() * (float) Perspective.LOCAL_TILE_SIZE : 0);
		c.ground.setWade(wadeSteps.steps(frameCameraX, frameCameraZ));
		glUniform1i(c.volumetricFog, frameFog ? 1 : 0);
		clouds.apply(c.clouds);
		weather.getLightning().bind(c.lightningBolt, frameCameraX, frameCameraZ);
		c.materials.set(config);
		water.setComposite(c.water, config);
	}

	/**
	 * Gather this frame's point lights: scene lights from lights.json, plus the optional test light.
	 */
	private void collectLights()
	{
		frameLights.clear();
		BuildingLights walls = buildingLights;
		if (walls != null)
		{
			walls.setSplitRooms(config.roomsByWalls());
		}
		// 0 by day, 1 once the sun is well below the horizon
		float t = Math.max(0, Math.min(1, (-2 - dayCycle.sunAltitude) / 8));
		float night = frameOutdoors ? t * t * (3 - 2 * t) : 1;
		// the light map takes the static lights' place, and the occlusion grids with it
		frameLightMap = updateLightMap(night);
		lightOcclusion.update(!frameLightMap && walls != null && config.lightWallOcclusion() ? walls.getWalls() : null);
		SceneLights lights = sceneLights;
		if (lights != null)
		{
			float time = (System.currentTimeMillis() - startMillis) / 1000f;
			// around the focal point like the drawn world (EdgeFade), so lights don't come and go as the camera turns
			lights.collect(frameLights, time, night, dayCycle.sunAltitude, edgeFade.focalX, edgeFade.focalZ,
				edgeFade.radius, walls != null ? walls.getRooms() : null);
		}
		if (config.testLight())
		{
			Player player = client.getLocalPlayer();
			LocalPoint lp = player != null ? player.getLocalLocation() : null;
			if (lp != null)
			{
				float ground = Perspective.getTileHeight(client, lp, client.getTopLevelWorldView().getPlane());
				// a little above head height (y points down), warm like a torch, reaching about 5 tiles
				frameLights.add(testLight.set(lp.getX(), ground - 250, lp.getY(), 5 * Perspective.LOCAL_TILE_SIZE,
					1.0f, 0.72f, 0.42f, 6f));
			}
		}
		lightOcclusion.assign(frameLights, config.lightWallOcclusion() && !frameLightMap);
	}

	int getLightDefinitionCount()
	{
		SceneLights lights = sceneLights;
		return lights != null ? lights.getDefinitionCount() : 0;
	}

	Weather getWeather()
	{
		return weather;
	}

	BuildingLights getBuildingLights()
	{
		return buildingLights;
	}

	ShadowMap getShadowMap()
	{
		return shadowMap;
	}

	int getPointLightCount()
	{
		return lightClusters.getLightCount();
	}

	double getCpuFrameMs()
	{
		return cpuFrameMs;
	}

	double getSwapIntervalMs()
	{
		return swapIntervalMs;
	}

	double getHourOfDay()
	{
		return dayCycle.hour();
	}

	/**
	 * Scissor the half resolution pass to the texels it draws: the water's rect, and the rows that can hold cloud
	 * across the whole width; with both, the rows from the first to the last of either. Enables the scissor test.
	 */
	private void scissorHalf(boolean withClouds, boolean withWater)
	{
		int[] r = halfScissor;
		if (withWater)
		{
			water.halfRect(r, sceneTargets.getHalfRatio());
		}
		if (withClouds)
		{
			clouds.skyRows(cloudRows, frameInvViewProj, frameViewport, sceneTargets.getAoWidth(),
				sceneTargets.getAoHeight(), sceneTargets.getHalfRatio());
			if (cloudRows[1] > cloudRows[0])
			{
				r[1] = withWater ? Math.min(r[1], cloudRows[0]) : cloudRows[0];
				r[3] = withWater ? Math.max(r[3], cloudRows[1]) : cloudRows[1];
				r[0] = 0;
				r[2] = sceneTargets.getAoWidth();
			}
			else if (!withWater)
			{
				r[0] = r[1] = r[2] = r[3] = 0;
			}
		}
		glEnable(GL_SCISSOR_TEST);
		glScissor(r[0], r[1], Math.max(r[2] - r[0], 0), Math.max(r[3] - r[1], 0));
	}

	private void updateClouds()
	{
		WorldView wv = client.getTopLevelWorldView();
		long baseX = wv != null ? (long) wv.getBaseX() * Perspective.LOCAL_TILE_SIZE : 0;
		long baseZ = wv != null ? (long) wv.getBaseY() * Perspective.LOCAL_TILE_SIZE : 0;
		clouds.setWeather(weather.cloudWisp(), weather.cloudDark(), weather.getWind());
		clouds.setEye(frameCameraX, frameCameraY, frameCameraZ, client.getCameraFocalPointX(),
			client.getCameraFocalPointY(), client.getCameraFocalPointZ(), edgeFade.radius, config.skyHorizonAtEdge());
		clouds.update(config.clouds(), config.cloudShadows(), config.cloudCoverage(), config.cloudSpeed(),
			config.cloudDetail(), weather.cloudCover(), baseX, baseZ, weather.getField());
	}

	/**
	 * Capture or blend the sky cache when the composite will use it, and return how it uses it (SkyCache.MODE_*).
	 */
	private int updateSkyCache()
	{
		int mode = config.skyCache() ? SkyCache.MODE_LIGHT : 0;
		// Loading a region keeps drawing the last frames; dropping the cache for them would switch the sky light.
		// Without updates the composite eases from the last capture back to the analytic light (SkyCache).
		GameState state = client.getGameState();
		if (mode == 0 || !frameOutdoors || (state != GameState.LOGGED_IN && state != GameState.LOADING))
		{
			skyCache.bind(SKY_CACHE_UNIT);
			return 0;
		}
		int cycle = config.dayCycleMinutes();
		double gameMinutes = cycle > 0 ? frameGameMillis / 60_000.0 : Double.NaN;
		weather.getOvercast(skyCacheOvercast);
		int settings = java.util.Objects.hash(config.clouds(), config.cloudCoverage(), config.cloudDetail(), cycle,
			config.fixedHour(), config.weatherOvercast(), Math.round(skyCacheOvercast[3] * 20),
			Math.round(skyCacheOvercast[4] * 20), Math.round(skyCacheOvercast[5] * 20), Math.round(skyCacheOvercast[6] * 20),
			Math.round(weather.cloudWisp() * 10), Math.round(weather.cloudDark() * 10));
		if (config.clouds())
		{
			clouds.bindNoise(CLOUD_NOISE_UNIT);
			weather.getField().bindSky(WEATHER_SKY_UNIT);
		}
		skyCache.update(clouds, gameMinutes, config.skyCacheMinutes(), frameCameraX, frameCameraY, frameCameraZ,
			dayCycle, skyCacheOvercast, settings, SKY_CACHE_UNIT);
		skyCache.bind(SKY_CACHE_UNIT);
		return mode;
	}

	/** Start a build of the scene's voxels, which the skylight traces, if one is due. */
	private void prepareVoxels()
	{
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv != null ? wv.getScene() : null;
		try
		{
			int builds = voxelBuilder.builds();
			voxelBuilder.prepare(frameSkylightWanted || frameLightMapWanted, scene, root != null ? root.zones : null, clientUploader.water);
			if (builds != voxelBuilder.builds())
			{
				hitchMonitor.job(HitchMonitor.VOXEL_BUILD);
			}
		}
		catch (RuntimeException ex)
		{
			logLimiter.warn(log, "voxels " + ex, "Voxel build failed", ex);
			voxelBuilder.free();
		}
	}

	private void compositeScene()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		final boolean ao = config.ambientOcclusion();

		sceneTargets.bindTextures(COMPOSITE_TEXTURE_UNIT);
		// the water surface into its own target, against the scene's depth, before the composite reads it
		weather.getWaterWind(waterWind);
		gpuTimer.begin(GpuTimer.WATER);
		water.drawSurface(config, waterWind, weather.waterRain(), frameOutdoors, sceneTargets.getWidth(),
			sceneTargets.getHeight(), frameViewport, frameViewProj, frameInvViewProj, frameCameraX, frameCameraY,
			frameCameraZ, edgeFade.focalX, edgeFade.focalZ, edgeFade.radius, dayCycle.casterDir, glCapabilities.OpenGL45,
			WATER_DATA_UNIT, WATER_NOISE_UNIT, WATER_CONTACT_UNIT);
		gpuTimer.end();
		// fullscreen triangles are generated from gl_VertexID; core profile still needs some VAO bound
		glBindVertexArray(vaoUiHandle);
		cpuTimer.begin(CpuTimer.VOXELS);
		prepareVoxels();
		cpuTimer.end(CpuTimer.VOXELS);
		gpuTimer.begin(GpuTimer.SKY_GRID);
		cpuTimer.begin(CpuTimer.SKY_GRID);
		BuildingLights buildings = buildingLights;
		skyGrid.update(frameSkylightWanted, voxelBuilder.getVoxels(), buildings != null ? buildings.getWalls() : null,
			frameSkylightTileX, frameSkylightTileZ, buildings != null ? buildings.getFloors() : null,
			buildings != null ? buildings.getRooms() : null);
		cpuTimer.end(CpuTimer.SKY_GRID);
		gpuTimer.end();

		// the AO pass also writes the half resolution depth and normals that the fog's upsample reads, and the
		// shadows
		WorldView aoView = client.getTopLevelWorldView();
		Scene aoScene = aoView != null ? aoView.getScene() : null;
		final boolean shadows = frameShadows && shadowMap.isInitialized();
		final boolean ground = config.weatherSurfaces() && weather.groundActive();
		frameGround = ground;
		final boolean sky = skyGrid.isReady(aoScene);
		frameSkylight = false;
		final boolean halfPass = ao || frameFog || shadows || ground || sky;
		if (halfPass)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, sceneTargets.getAoFbo());
			glViewport(0, 0, sceneTargets.getAoWidth(), sceneTargets.getAoHeight());
			// the AO pass writes every texel: clearing first lets the GPU start the pass without loading the old
			// contents (the fog, denoise, cloud and composite passes do the same)
			glClear(GL_COLOR_BUFFER_BIT);
			glUseProgram(glAoProgram);
			setFrameUniforms(aoFrame);
			glUniform1f(uniAoRadius, config.aoRadius() / 100f * Perspective.LOCAL_TILE_SIZE);
			glUniform1i(uniAoSamples, ao ? config.aoQuality().pick(6, 8, 12) : 0);
			glUniform1i(uniAoShadowsEnabled, shadows ? 1 : 0);
			if (shadows)
			{
				glUniformMatrix4fv(uniAoLightProjRel, false, shadowMap.lightProjRel);
				glUniform1f(uniAoShadowRadius, shadowMap.getRadius());
				glUniform1f(uniAoShadowDepthRange, shadowMap.getDepthRange());
				glUniform1f(uniAoShadowMapSize, shadowMap.getSize());
				glUniform3fv(uniAoCasterDir, dayCycle.casterDir);
				glUniform1i(uniAoSunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
				glUniform2fv(uniAoStrikeLight, dayCycle.strikeLight);
				shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
				glUniform1i(uniAoShadowMaxTile, shadowMap.bindMax(SHADOW_MAX_UNIT));
			}
			sceneTargets.bindMaterial(MATERIAL_UNIT);
			water.bindAo(config, WATER_DATA_UNIT);
			if (ground)
			{
				weather.bindGround(weatherGround, WEATHER_OCCLUSION_UNIT, WEATHER_FIELD_UNIT, frameCameraX, frameCameraY,
					frameCameraZ);
			}
			else
			{
				weatherGround.setAoOff();
			}
			frameSkylight = skyGrid.bindAo(SKY_GRID_UNIT, aoScene, frameCameraX, frameCameraY, frameCameraZ,
				config.skylightOutdoors());
			skyField.bindAo(SKY_FIELD_UNIT, frameCameraX, frameCameraZ);
			gpuTimer.begin(GpuTimer.AO);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			gpuTimer.end();
			SkyGridMap.unbind(SKY_GRID_UNIT);
			SkyFieldMap.unbind(SKY_FIELD_UNIT);
			// the sky cache takes the occlusion map's unit next, without its sampler objects
			ShadowMap.unbind(WEATHER_OCCLUSION_UNIT, WEATHER_OCCLUSION_UNIT + 1);
			sceneTargets.bindAo(AO_TEXTURE_UNIT);
			sceneTargets.bindShadow(SCENE_SHADOW_UNIT);
		}

		updateClouds();
		gpuTimer.begin(GpuTimer.SKY);
		cpuTimer.begin(CpuTimer.SKY_CACHE);
		int captured = skyCache.getCaptures();
		int skyCacheMode = updateSkyCache();
		cpuTimer.end(CpuTimer.SKY_CACHE);
		if (captured != skyCache.getCaptures())
		{
			hitchMonitor.job(HitchMonitor.SKY_CAPTURE);
		}
		gpuTimer.end();

		skyCache.easeComposite(skyCacheMode);
		compositeTime = (System.currentTimeMillis() - startMillis) / 1000f;
		prepareLighting();
		sceneTargets.bindAlphaLayer(ALPHA_LAYER_UNIT);

		weather.bindField(WEATHER_FIELD_UNIT, WEATHER_SKY_UNIT);
		final boolean denoise = halfPass && glDenoiseProgram != 0;
		boolean denoiseFog = false;
		// after prepareLighting, which binds the shadow map and light clusters the fog also samples
		if (frameFog)
		{
			gpuTimer.begin(GpuTimer.FOG);
			volumetricFog.indoorFactor = config.noFogIndoors() ? VolumetricFog.INDOOR_DENSITY : 1;
			volumetricFog.waterTexture = water.dataTexture(config);
			// in a void the sun doesn't light the fog: it would glow over the black around the scene
			final boolean fogSun = frameOutdoors && !frameSkyVoid;
			final ShadowMap fogShadows = shadows ? shadowMap : null;
			denoiseFog = denoise && volumetricFog.hasShafts(fogSun, fogShadows);
			volumetricFog.render(sceneTargets.fogFbo(denoiseFog), sceneTargets.getAoWidth(),
				sceneTargets.getAoHeight(), sceneTargets.getHalfRatio(), FOG_NOISE_UNIT,
				frameViewProj, frameInvViewProj, frameViewport, glCapabilities.OpenGL45,
				frameCameraX, frameCameraY, frameCameraZ, (System.currentTimeMillis() - startMillis) / 1000f,
				fogSun, proceduralSky(), frameSkyColor, dayCycle, fogShadows,
				config.pointLights() && lightClusters.isInitialized()
					&& (lightClusters.hasFogLights() || frameLightMap && lightMap.hasLive()),
				lightClusters.getRoomHeader());
			gpuTimer.end();
		}
		// the AO, the shadows and the fog's sun shafts are all dithered: smoothed together at half resolution before
		// the composite upsamples them
		if (denoise)
		{
			gpuTimer.begin(GpuTimer.FILTER);
			glUseProgram(glDenoiseProgram);
			sceneTargets.denoise(uniDenoiseFog, AO_TEXTURE_UNIT, SCENE_SHADOW_UNIT, FOG_TEXTURE_UNIT, denoiseFog);
			sceneTargets.bindShadow(SCENE_SHADOW_UNIT);
			gpuTimer.end();
		}
		if (frameFog)
		{
			sceneTargets.bindFog(FOG_TEXTURE_UNIT);
		}
		if (clouds.isEnabled())
		{
			clouds.bindNoise(CLOUD_NOISE_UNIT);
		}
		sceneTargets.bindMaterial(MATERIAL_UNIT);
		water.bind(config, WATER_DATA_UNIT);
		// The clouds, which the composite's sky filters, and the water's shading (water.glsl) at half resolution, with
		// everything bound for the composite: in one pass when both are drawn, timed as the clouds'
		final boolean halfClouds = clouds.isEnabled() && frameOutdoors && proceduralSky();
		final boolean halfWater = water.enabled(config);
		if (halfClouds || halfWater)
		{
			// their targets are not bound while drawn into
			glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
			glBindTexture(GL_TEXTURE_2D, 0);
			glActiveTexture(GL_TEXTURE0 + WATER_NOISE_UNIT);
			glBindTexture(GL_TEXTURE_2D, 0);
			sceneTargets.bindHalf(halfClouds, halfWater);
			glDisable(GL_BLEND);
			useComposite(!halfWater ? compositeClouds : halfClouds ? compositeCloudsWater : compositeWater);
			gpuTimer.begin(halfClouds ? GpuTimer.CLOUDS : GpuTimer.WATER);
			scissorHalf(halfClouds, halfWater);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDisable(GL_SCISSOR_TEST);
			gpuTimer.end();
			glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
			glBindTexture(GL_TEXTURE_2D, halfClouds ? sceneTargets.cloudTexture() : 0);
			glActiveTexture(GL_TEXTURE0 + WATER_NOISE_UNIT);
			glBindTexture(GL_TEXTURE_2D, halfWater ? sceneTargets.waterShadedTexture() : 0);
			glActiveTexture(GL_TEXTURE0);
		}

		useComposite(compositeFull);
		if (log.isDebugEnabled())
		{
			lookMonitor.frame(compositeFull.env, weather, frameLights.size(),
				environmentManager.getWorldPoint(), environmentManager.getBiome());
		}

		if (config.debugView() == DebugView.FINAL && !Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true")))
		{
			for (int part = 1; part <= 4; part++)
			{
				sceneTargets.drawPart(part, COMPOSITE_PARTS_UNIT);
				glUniform1i(compositeFull.part, part);
				gpuTimer.begin(GpuTimer.AMBIENT + part - 1);
				glDrawArrays(GL_TRIANGLES, 0, 3);
				gpuTimer.end();
			}
			sceneTargets.drawPart(5, COMPOSITE_PARTS_UNIT);
			glUniform1i(compositeFull.part, 5);
		}

		// below 100% render scale the composite and the weather draw at the scene's size into the upscaler's target
		final boolean upscale = upscaler.isInitialized();
		final int sceneWidth = upscale ? sceneTargets.getWidth() : width;
		final int sceneHeight = upscale ? sceneTargets.getHeight() : height;
		// the interface texture is filled before the drawable's pass starts, so the composite, the weather and the
		// interface all draw in one pass: an upload between them would end it and store and reload the drawable
		finishInterfaceUpload();
		final int target = upscale ? upscaler.getFbo() : awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_FRAMEBUFFER, target);
		final boolean edges = shaderSamples > 0;
		final boolean stencil = edges && (upscale || target == 0 && hasWindowStencil());
		glViewport(0, 0, sceneWidth, sceneHeight);
		glClear(stencil ? GL_COLOR_BUFFER_BIT | GL_STENCIL_BUFFER_BIT : GL_COLOR_BUFFER_BIT);
		gpuTimer.begin(GpuTimer.COMPOSITE);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glUniform1i(compositeFull.part, 0);
		if (edges)
		{
			if (stencil)
			{
				// the pixels with a second surface, by a small program, so the edge pass's full shading never starts
				// on the others; without a stencil the edge pass discards them itself
				glUseProgram(compositeMark.program);
				setFrameUniforms(compositeMark.frame);
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
			useComposite(compositeEdges);
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDisable(GL_BLEND);
			glDisable(GL_STENCIL_TEST);
		}
		gpuTimer.end();
		glActiveTexture(GL_TEXTURE0 + COMPOSITE_PARTS_UNIT);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glActiveTexture(GL_TEXTURE0);
		glActiveTexture(GL_TEXTURE0 + CLOUD_HALF_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		// before the weather binds its occlusion map on the same unit
		SkyCache.unbind(SKY_CACHE_UNIT);

		if (weather.isActive() && config.debugView() == DebugView.FINAL)
		{
			gpuTimer.begin(GpuTimer.WEATHER);
			weather.draw(frameViewProj, frameInvViewProj, frameViewport, glCapabilities.OpenGL45,
				frameCameraX, frameCameraY, frameCameraZ, dayCycle, config.weatherSplashes(), water, config,
				WEATHER_OCCLUSION_UNIT,
				config.pointLights() && config.weatherPointLights() && lightClusters.isInitialized(),
				lightClusters.getRoomHeader(), frameDrawDistance,
				wadeSteps.steps(frameCameraX, frameCameraZ));
			gpuTimer.end();
			glViewport(0, 0, sceneWidth, sceneHeight);
		}
		// the weather draws splashes by the water pass's pixels
		Water.unbind(WATER_DATA_UNIT, WATER_NOISE_UNIT);

		if (upscale)
		{
			gpuTimer.begin(GpuTimer.UPSCALE);
			glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
			// the weather pass leaves its own vertex array bound (or none); the fullscreen triangle needs one bound
			glBindVertexArray(vaoUiHandle);
			upscaler.draw(width, height, config.upscaleSharpness() / 100f);
			gpuTimer.end();
		}

		glBindVertexArray(0);
		sceneTargets.unbindTextures(COMPOSITE_TEXTURE_UNIT);
		glActiveTexture(GL_TEXTURE0 + AO_TEXTURE_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + SCENE_SHADOW_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + SHADOW_MAX_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		ShadowMap.unbind(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		LightClusters.unbind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, LIGHT_VIS_UNIT);
		LightMapTexture.unbind(LIGHT_VIS_UNIT);
		glActiveTexture(GL_TEXTURE0 + ALPHA_LAYER_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		sceneTargets.unbindMaterial(MATERIAL_UNIT);
		VolumetricFog.unbind(FOG_TEXTURE_UNIT);
		Clouds.unbind(CLOUD_NOISE_UNIT);
		WeatherField.unbind(WEATHER_FIELD_UNIT, WEATHER_FIELD_UNIT + 1, WEATHER_SKY_UNIT);
		glUseProgram(0);

		checkGLErrors();
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);

		checkGLErrors();
	}

	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		tempModels.flush();
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

		RenderThread rt = rts[0];
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0));

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		// the temp models must be in the buffers before they are drawn or unmapped
		tempModels.flush();
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
				{
					rts[i].vaoO.draw();
				}
			}
			else
			{
				glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			setAlphaPass(false);
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}
		else if (pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			for (int i = 0; i < rts.length; ++i)
			{
				rts[i].vaoA.unmap();
			}

			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
			setAlphaPass(true);
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}
		// past the edge of the world (EdgeFade) it would be all sky; the client's larger square still offers it
		if (scene.getWorldViewId() == WorldView.TOPLEVEL && !edgeFade.modelVisible(x, z))
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int material = SurfaceMaterials.forObject(tileObject.getId(), r);
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, material);
			o.addRange(ctx.projection, scene, 0);
		}
		else
		{
			m.calculateBoundsCylinder();

			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			if (o == null || a == null)
			{
				return;
			}

			ModelUploader sorter = rt.modelUploader;

			int start = a.vbo.vb.position();
			try
			{
				sorter.uploadSortedModel(worldProjection, m, orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb, false, material);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}
		if (scene.getWorldViewId() == WorldView.TOPLEVEL && !edgeFade.modelVisible(x, z))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		int renderMode = renderable.getRenderMode();
		int material = SurfaceMaterials.forRenderable(renderable, gameObject.getHash(), gameObject.getId());
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0)
		{
			m.calculateBoundsCylinder();
			int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
			int zx = (gameObject.getX() >> 10) + offset;
			int zz = (gameObject.getY() >> 10) + offset;
			int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
			tempModels.uploadSorted(rts[0], ctx, scene, worldProjection, m, orient, x, y, z, material,
				renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH, zx, zz, plane,
				y - renderable.getModelHeight() /* to render players over locs */);
		}
		else
		{
			tempModels.uploadTemp(rts[0], ctx, scene, m, orient, x, y, z, material);
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.trace("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		hideAreas(wv);
		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	/** The scene is cut to the area the player was in as it loaded: load it again when they walk into another. */
	private void hideAreas(WorldView wv)
	{
		Player player = client.getLocalPlayer();
		if (player == null || nextZones != null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		LocalPoint lp = player.getLocalLocation();
		if (areaHiding.changed(wv.getScene(), config.hideOtherAreas(), lp.getSceneX(), lp.getSceneY(), wv.getPlane()))
		{
			hitchMonitor.job(HitchMonitor.AREA_HIDING);
			client.setGameState(GameState.LOADING);
		}
	}

	/** Whether the object has an action to interact with, as it looks now; client thread (Interactables). */
	private boolean hasAction(int id)
	{
		ObjectComposition comp = client.getObjectDefinition(id);
		if (comp != null && comp.getImpostorIds() != null && comp.getImpostor() != null)
		{
			comp = comp.getImpostor();
		}
		return comp != null && Interactables.hasAction(comp.getActions());
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				clientUploader.zoneSize(scene, zone, x, z);
				Interactables.resolve(this::hasAction);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;
				if (ctx == root)
				{
					hitchMonitor.job(HitchMonitor.ZONE_REBUILD);
					weather.invalidateOcclusion();
					shadowMap.refreshCache();
					voxelBuilder.zonesChanged();
				}

				log.trace("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		// copied on worker threads while the composite is submitted; interfaceUpload.finish fills the texture
		interfaceUpload.start(interfacePbo, bufferProvider.getPixels(), bufferProvider.getWidth(),
			bufferProvider.getHeight());
	}

	// the interface texture holds this frame's canvas (finishInterfaceUpload)
	private boolean interfaceFinished;

	private void finishInterfaceUpload()
	{
		if (interfaceFinished)
		{
			return;
		}
		interfaceFinished = true;
		cpuTimer.begin(CpuTimer.UI_UPLOAD);
		interfaceUpload.await();
		cpuTimer.end(CpuTimer.UI_UPLOAD);
		// the composite samples none of unit 0, and the interface draws from it
		glActiveTexture(GL_TEXTURE0);
		interfaceUpload.finish(interfaceTexture);
	}

	@Override
	public void draw(int overlayColor)
	{
		cpuTimer.end(CpuTimer.CLIENT_UI);
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		ShaderWatcher watcher = shaderWatcher;
		if (watcher != null && watcher.poll())
		{
			hitchMonitor.job(HitchMonitor.SHADER_RELOAD);
			reloadShaders();
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
			if (textureArrayId > -1)
			{
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				textureAnimations = texAnims;
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		cpuTimer.begin(CpuTimer.UI_UPLOAD);
		prepareInterfaceTexture(canvasWidth, canvasHeight);
		cpuTimer.end(CpuTimer.UI_UPLOAD);

		interfaceFinished = false;
		boolean composited = false;
		if (sceneFboValid && sceneTargets.isInitialized() && compositeFull.program != 0)
		{
			try
			{
				compositeScene();
				composited = true;
			}
			catch (RuntimeException ex)
			{
				// one broken effect must not flood the log every frame or take the UI with it
				logLimiter.warn(log, "composite " + ex, "Error compositing the scene", ex);
				glBindVertexArray(0);
				glUseProgram(0);
				glActiveTexture(GL_TEXTURE0);
			}
		}
		finishInterfaceUpload();
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		gpuTimer.begin(GpuTimer.UI);
		if (!composited)
		{
			// the composite covers the whole drawable; without it the window is cleared here instead. Clearing it
			// up front would open the drawable's pass early and store and reload it around the composite's passes.
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		gpuTimer.end();
		cpuTimer.begin(CpuTimer.SWAP);
		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		cpuTimer.end(CpuTimer.SWAP);
		gpuTimer.endFrame();
		if (frameCpuStart != 0)
		{
			// CPU time from the start of the scene draw to the swap, including the client's own scene traversal
			double ms = (System.nanoTime() - frameCpuStart) / 1e6;
			cpuFrameMs = cpuFrameMs == 0 ? ms : cpuFrameMs * 0.9 + ms * 0.1;
			frameCpuStart = 0;
		}
		long now = System.nanoTime();
		double swapIntervalNow = 0;
		if (lastSwapNanos != 0)
		{
			double ms = (now - lastSwapNanos) / 1e6;
			swapIntervalMs = swapIntervalMs == 0 ? ms : swapIntervalMs * 0.95 + ms * 0.05;
			swapIntervalNow = ms;
		}
		lastSwapNanos = now;
		if (log.isDebugEnabled())
		{
			hitchMonitor.endFrame(swapIntervalNow, cpuTimer, gpuTimer);
		}
		cpuTimer.endFrame();
		cpuTimer.begin(CpuTimer.OUTSIDE);
		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(glUiProgram);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(uniUiColorblindIntensity, config.colorBlindIntensity());

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), dim.width), getScaledValue(t.getScaleY(), dim.height));
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		if (function != interfaceFilter)
		{
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);
			interfaceFilter = function;
		}

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);

		// glReadPixels returns rows bottom-up, flip them to top-down
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}

		return image;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
		if (state == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
			lastTextureLodBias = 0;
		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			nextZones = null;
			nextRoofChanges = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		regionManager.prepare(scene);
		lookMonitor.scene("load", scene);

		// loaded again where the player stands, their tile is known; a new map is built around them instead
		Player player = client.getLocalPlayer();
		boolean inPlace = player != null && scene.getBaseX() == prev.getBaseX() && scene.getBaseY() == prev.getBaseY()
			&& scene.isInstance() == prev.isInstance();
		LocalPoint playerPoint = inPlace ? player.getLocalLocation() : null;
		areaHiding.prepare(scene, config.hideOtherAreas(), client.getTopLevelWorldView().getPlane(),
			playerPoint != null ? new int[]{playerPoint.getSceneX(), playerPoint.getSceneY()} : null);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN
			// zones built with other tiles hidden can't be reused
			&& areaHiding.sameAsShown())
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		// the water's bed depths and flow, needed to size and upload the zones; swapped in with them
		Stopwatch sw = Stopwatch.createStarted();
		WaterMap waterMap = config.water()
			? WaterMap.build(scene, WaterOverrides.get()) : null;
		mapUploader.water = waterMap;
		water.sceneLoaded(waterMap);
		log.debug("Water map time {}", sw);
		volumetricFog.sceneLoaded(scene);

		// size the zones which require upload
		sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			Interactables.resolve(this::hasAction);
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			Interactables.resolve(this::hasAction);
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		hitchMonitor.job(HitchMonitor.SCENE_SWAP);
		areaHiding.swapped();
		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;
		lookMonitor.scene("swap", scene);

		ctx.zones = nextZones;
		nextZones = null;
		weather.invalidateOcclusion();
		shadowStaticDirty = true;
		voxelBuilder.sceneSwapped();
		water.swap();
		volumetricFog.swap();
		clientUploader.water = mapUploader.water;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		glDpiAwareViewport(x, y, width, height, 1);
	}

	/** renderFactor shrinks the viewport with the scene targets (RenderScale). */
	private void glDpiAwareViewport(final int x, final int y, final int width, final int height, final double renderFactor)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX() * renderFactor, x),
			getScaledValue(t.getScaleY() * renderFactor, y),
			getScaledValue(t.getScaleX() * renderFactor, width),
			getScaledValue(t.getScaleY() * renderFactor, height));
	}

	private double renderFactor()
	{
		return config.renderScale().factor(clientUI.getGraphicsConfiguration().getDefaultTransform().getScaleX());
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void checkGLErrors()
	{
		if (!glErrorChecks)
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			// an error from a feature that is on keeps coming every frame: log each kind and place once in a while
			Exception where = new Exception(errStr);
			StackTraceElement[] st = where.getStackTrace();
			String caller = st.length > 1 ? st[1].toString() : "";
			logLimiter.warn(log, errStr + " " + caller, "glGetError:", where);
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("gpumem"))
		{
			int totalSzKb = 0;
			for (int i = 0; i < rts.length; ++i)
			{
				RenderThread rt = rts[i];
				int szKb = rt.vaoO.size() + rt.vaoA.size();
				totalSzKb += szKb;
				log.info("RenderThread{}: {}kb", i, szKb);
			}
			log.info("Total: {}kb", totalSzKb);
		}
	}
}
