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
package com.midonarhel.lumina;

import com.midonarhel.lumina.common.LogLimiter;
import com.midonarhel.lumina.scene.SceneDraw;
import com.midonarhel.lumina.frame.FrameState;
import com.midonarhel.lumina.frame.GlObjects;
import com.midonarhel.lumina.light.LampFrame;
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
import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.SceneDump;
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
import com.midonarhel.lumina.config.DayLength;
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

@PluginDescriptor(
	name = "Lumina",
	description = "Lighting-focused GPU renderer",
	tags = {"gpu", "lighting", "shadows", "fog", "renderer"},
	loadInSafeMode = false
)
public class LuminaPlugin extends Plugin implements DrawCallbacks
{
	public static final Logger log = LoggerFactory.getLogger(LuminaPlugin.class);

	public static final int MAX_DISTANCE = 184;
	// how far in, in tiles, the sky's light flowing in under cover falls to a third of its brightness
	public static final int SKYLIGHT_REACH = 3;
	public static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	public static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	public static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	public Client client;

	@Inject
	public ConfigManager configManager;

	@Inject
	public ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	public LuminaConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	public RegionManager regionManager;

	public final AreaHiding areaHiding = new AreaHiding();

	@Inject
	public DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	public RenderCallbackManager renderCallbackManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private EventBus eventBus;

	@Inject
	public Gson gson;

	public SceneLights sceneLights;
	public BuildingLights buildingLights;

	@Inject
	private BuildingOverlay buildingOverlay;

	@Inject
	private OverlayManager overlayManager;


	@Inject
	@Named("developerMode")
	private boolean developerMode;

	// reference-build comparison tools (Lumina's): ::runelight cam|capture|set, and the same from
	// ~/.runelite/lumina/command-perfwip.txt once per client tick in developer mode
	private CommandFile commandFile;
	public final FrameSnapshot frameSnapshot = new FrameSnapshot();
	public int framesSinceSwap = 1000;
	private final SceneLoadReport loadReport = new SceneLoadReport();
	public boolean swapCaptureArmed;
	public int swapCaptureFrames = 8;
	// frames left to capture around a swap: set when loadScene finishes (before the client's swap), counts down per frame
	public volatile int swapCaptureLeft;
	public int swapCaptureIndex;
	public int lastSkyCacheMode;

	// frame pacing (FramePacer): the client gets no target while the pacer holds the frame on its grid
	public final FramePacer pacer = new FramePacer();

	private static final Set<String> OTHER_RENDERERS = Set.of(
		"net.runelite.client.plugins.gpu.GpuPlugin",
		"rs117.hd.HdPlugin");

	// true once this plugin owns the client's draw callbacks; guards shutDown from clobbering another renderer
	private boolean active;

	// cached: checkGLErrors runs per draw call, too often for a config proxy lookup
	private volatile boolean glErrorChecks;

	public ShaderWatcher shaderWatcher;
	public volatile String shaderError;
	public float[] textureAnimations;

	public final CpuTimer cpuTimer = new CpuTimer();

	private Canvas canvas;
	public AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	public GLCapabilities glCapabilities;

	public static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	public static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	public static final Shader COMPOSITE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "composite_frag.glsl");

	public static final Shader AO_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "ao_frag.glsl");

	public static final Shader DENOISE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "denoise_frag.glsl");

	// the composite pass (Composite): its programs, their uniforms, and the full-screen draw
	public final Composite composite = new Composite(this);
	// whether the window's framebuffer has a stencil, for the edge pass at 100% render scale; 0 unknown, 1 yes, -1 no.
	// The GL bridge gives Windows and Linux one from the pixel format; on macOS it draws into an IOSurface's
	// framebuffer, color alone
	private int windowStencil;
	public static final int COMPOSITE_PARTS_UNIT = 31;
	// this frame's time for the composite's passes, seconds since the start
	public float compositeTime;
	// this frame's point lights on, and whether the light clusters and the rooms were uploaded and bound
	// (prepareLighting); whether the wet ground is drawn
	public boolean framePointLights, frameLightClusters, frameGround;

	public int glAoProgram;
	public int glDenoiseProgram;
	public int uniDenoiseFog;
	public int uniAoRadius;
	public int uniAoSamples;
	public int uniAoShadowsEnabled, uniAoLightProjRel, uniAoShadowRadius, uniAoShadowDepthRange, uniAoShadowMapSize,
		uniAoShadowMaxTile, uniAoCasterDir, uniAoSunIsCaster, uniAoStrikeLight;
	// the half resolution shadows the AO pass draws, for the composite
	public static final int SCENE_SHADOW_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 18;
	// Static scenery is drawn into a cached shadow map, redrawn only as the light turns or the player walks
	// (renderStaticShadows). It looks the same as drawing it every frame, so it is no longer a setting.
	public static final boolean SHADOW_CACHE = true;
	// the game shows a black void around the scene (instances, player houses, dungeons): it stays dark
	public boolean frameSkyVoid;
	public final FrameUniforms aoFrame = new FrameUniforms();

	public static final int AO_TEXTURE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 3;
	public static final int SHADOW_RAW_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 4;
	public static final int SHADOW_COMPARE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 5;
	// the static shadow map's maxima, for the AO pass (ShadowMap.bindMax)
	public static final int SHADOW_MAX_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 22;
	public static final int LIGHT_DATA_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 6;
	public static final int CLUSTER_DATA_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 7;
	// the rooms (RoomTexture)
	public static final int ROOM_TEXELS_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 20;
	// the fog as marched at quarter resolution, read by its upsample
	// the clouds at half resolution, which the composite's sky filters (composite_frag.glsl)
	public static final int CLOUD_HALF_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 23;

	public final LightClusters lightClusters = new LightClusters();
	// the walls that stop the lamps, and the rooms (for their fill and the puddles), on the GPU
	public final LampWalls lampWalls = new LampWalls();
	// the fixed lamps' visibility, baked on its own threads (LightMap), and as the light buffer holds it
	public final com.midonarhel.lumina.light.LightMap lightMap = new com.midonarhel.lumina.light.LightMap(
		com.midonarhel.lumina.light.LightMap.defaultThreads(), com.midonarhel.lumina.light.LightMap.burstThreads());
	public final com.midonarhel.lumina.light.LightMapBuffer lightMapBuffer = new com.midonarhel.lumina.light.LightMapBuffer();
	public final RoomTexture roomTexture = new RoomTexture();
	// how much light each building room gets and the fill it lacks (RoomLight)
	public final RoomLight roomLight = new RoomLight();
	public long roomLightNanos;
	public final OpenRooms openRooms = new OpenRooms();
	// repeating errors are logged once per interval
	public final LogLimiter logLimiter = new LogLimiter(10_000);
	public final LookMonitor lookMonitor = new LookMonitor();
	public final HitchMonitor hitchMonitor = new HitchMonitor();
	public final java.util.ArrayList<PointLight> frameLights = new java.util.ArrayList<>();
	public final PointLight testLight = new PointLight();
	// lamp.glsl's lampWallsHeader with no walls bound
	public static final float[] NO_WALLS = {0, 0, 0, 0, 1};
	public int uniAlphaPass;
	public int uniAlphaShadows, uniAlphaLightProj, uniAlphaShadowTexel, uniAlphaShadowDepthRange, uniAlphaDepthZeroToOne;
	public int uniSceneOutdoors;
	// the lamps on the transparent faces (frag.glsl's alpha pass)
	public int uniScenePointLights;
	public final LampWalls.Uniforms sceneLampWalls = new LampWalls.Uniforms();
	public int uniSceneProceduralSky;
	public int uniSceneCasterDir;
	public int uniSceneSunIsCaster, uniSceneStrikeLight;
	public int uniSceneSunAltitude;
	public int uniSceneOvercast;
	// vert.glsl's weatherNoiseOrigin: world x and z of local (scene) 0, as the composite's snow has it
	public int uniSceneNoiseOrigin;
	public int uniSceneWeatherLight;
	// the wind for foliage sway, on the scene and shadow programs
	public Wind.Locations sceneWind, shadowWind;
	private long lastSwayTick;
	public boolean swayTick = true;
	public static final int ALPHA_LAYER_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 8;
	public static final int MATERIAL_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 9;
	public final SurfaceMaterials.Uniforms sceneMaterials = new SurfaceMaterials.Uniforms();
	public final WindSway.Uniforms sceneSway = new WindSway.Uniforms();
	public final WindSway.Uniforms shadowSway = new WindSway.Uniforms();
	public static final int FOG_TEXTURE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 10;
	public static final int FOG_NOISE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 11;

	public final VolumetricFog volumetricFog = new VolumetricFog();
	public final Clouds clouds = new Clouds();
	// the half resolution pass's scissor, x0, y0 to x1, y1 exclusive: the water's rect, the clouds' rows (skyRows)
	public final int[] halfScissor = new int[4], cloudRows = new int[2];
	// the composite has no 3D sampler, so the clouds' 2D noise shares the fog noise's unit on the 2D target
	public static final int CLOUD_NOISE_UNIT = FOG_NOISE_UNIT;
	public final SkyCache skyCache = new SkyCache();
	// The composite samples the sky cache and its irradiance on the weather occlusion map's two units: the composite
	// draws before the weather passes bind the map there. The cache's own passes use them before either.
	public static final int SKY_CACHE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 12;
	public final float[] skyCacheOvercast = new float[7];
	public boolean frameFog;
	// 117 HD fog depth that doubles the volumetric fog
	public static final float AREA_FOG_DEPTH = 30;
	// under the open sky rather than underground, from the area classification (or the game's sky color)
	public boolean frameOpenSky;

	public static final Shader SHADOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	public static final Shader SHADOW_COPY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_copy_frag.glsl");

	public static final Shader SHADOW_MAX_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_max_frag.glsl");

	public int glShadowProgram;
	public int glShadowCopyProgram;
	public int glShadowMaxProgram;
	public int uniShadowLightProj;
	public int uniShadowEntityProj;
	public int uniShadowBase;
	public int uniShadowTexturesReady;
	public int uniShadowEmissiveCasts;

	private long lastSwapNanos;
	private volatile double swapIntervalMs;

	public final DayCycle dayCycle = new DayCycle();
	public final NightSky nightSky = new NightSky();
	public final EnvironmentManager environmentManager = new EnvironmentManager();
	public final EnvironmentUniforms sceneEnvUniforms = new EnvironmentUniforms();
	public final ShadowMap shadowMap = new ShadowMap();
	public final Weather weather = new Weather();
	public volatile WeatherAudio weatherAudio;
	public final float[] thunderStrike = new float[2];
	// each region's forecast, temperature and ground, which follow the game clock
	public final Climate climate = new Climate();
	public final WeatherGround weatherGround = new WeatherGround();
	// where players and small NPCs have just walked through rain
	public final WadeSteps wadeSteps = new WadeSteps();
	// GameClock's time this frame: the time of day's (the day length's), and the weather's (GameClock.weatherMillis)
	public long frameGameMillis, frameWeatherMillis;
	// two units: the occlusion map's raw and compare views; their own, since the AO program's compare sampler must
	// never find another program's color texture left on its unit (the sky cache and the water contact were there)
	public static final int WEATHER_OCCLUSION_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 26;
	// the water: data and noise for its surface pass, then the surface and its half resolution shading for the
	// composite; 16 and 17, past the composite's other units (the per-stage limit of 16 counts samplers a program
	// uses, not unit numbers; units go up to the combined limit, 80 on macOS)
	public static final int WATER_DATA_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 14;
	public static final int WATER_NOISE_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 15;
	// the water surface pass's map of things standing in the water (WaterMap.contact)
	public static final int WATER_CONTACT_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 13;
	// the terrain the volumetric fog lies on (VolumetricFog)
	public static final int FOG_GROUND_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 24;
	// the ground drawn before it while a new one eases in (VolumetricFog, LayerEase)
	public static final int FOG_GROUND_PREV_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 30;
	// the surface pass's wave maps either side of now (WaterSurface.bake)
	public static final int WATER_WAVES_UNIT_A = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 17;
	public static final int WATER_WAVES_UNIT_B = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 19;
	// two units: the weather field (WeatherField) and its ground, for the fog and the weather passes
	public static final int WEATHER_FIELD_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 16;
	public final Water water = new Water();
	public final float[] waterWind = new float[3];
	public final VoxelBuilder voxelBuilder = new VoxelBuilder();
	// the skylight's traced sky visibility for the AO pass, and the static lights' occlusion
	public static final int SKY_GRID_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 28;
	// the weather field's sky (cover, wispiness, darkness around the player) for the clouds
	public static final int WEATHER_SKY_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 27;
	public final SkyGridMap skyGrid = new SkyGridMap();
	// world units from the player's feet to the head, where the sky overhead is read for the weather sounds
	public static final float WEATHER_HEAD = 120;
	// the sky's light flowing in under cover
	public static final int SKY_FIELD_UNIT = LuminaPlugin.COMPOSITE_TEXTURE_UNIT + 25;
	public final SkyFieldMap skyField = new SkyFieldMap();
	// whether the AO pass worked the sky visibility out this frame; whether it is wanted (under the open sky, logged
	// in), and where the player is for the sky grid's tracing (world tiles)
	public boolean frameSkylight;
	public boolean frameSkylightWanted;
	public float frameSkylightTileX, frameSkylightTileZ;
	// when the plugin started; animations count from here (the day, the seasons and the weather follow GameClock)
	public long startMillis = com.midonarhel.lumina.common.Clock.millis();

	// per-frame state for the full-screen passes, captured during the scene draw
	public float frameCameraX, frameCameraY, frameCameraZ;
	public boolean frameOutdoors;
	public boolean frameHasSkybox;
	public boolean frameShadows;
	public int frameSkyColor;
	public int frameDrawDistance;

	/**
	 * Camera data for the full-screen passes. The matrices are camera-relative (no translation) to keep
	 * world position reconstruction precise far from the world origin.
	 */
	public static final class FrameUniforms
	{
		public int viewProj, invViewProj, viewport, depthZeroToOne, halfRatio;

		public void locate(int program)
		{
			viewProj = glGetUniformLocation(program, "viewProj");
			invViewProj = glGetUniformLocation(program, "invViewProj");
			viewport = glGetUniformLocation(program, "sceneViewport");
			depthZeroToOne = glGetUniformLocation(program, "depthZeroToOne");
			halfRatio = glGetUniformLocation(program, "halfRatio");
		}
	}

	public final float[] frameViewProj = Mat4.identity();
	public float[] frameInvViewProj = Mat4.identity();
	// scene viewport within the scene targets, in framebuffer pixels: x, y, width, height
	public final int[] frameViewport = new int[4];

	public static int glProgram;
	public int glUiProgram;

	public int interfaceTexture;
	// the interface texture's filter as last set: changed only with the UI scaling mode
	public int interfaceFilter;
	public int interfacePbo;
	public final InterfaceUpload interfaceUpload = new InterfaceUpload();
	public final TempModels tempModels = new TempModels();

	public int vaoUiHandle;
	public int vboUiHandle;

	public final SceneTargets sceneTargets = new SceneTargets();
	private boolean sceneFboValid;

	// composite samples the scene targets from these units; 0 is the UI texture and 1 the game texture array
	public static final int COMPOSITE_TEXTURE_UNIT = 2;

	public int textureArrayId;

	public final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	public int lastCanvasWidth;
	public int lastCanvasHeight;
	public int lastStretchedCanvasWidth;
	public int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	// the share of the drawable's resolution the scene targets were made for (config render scale)
	private double lastRenderFactor;
	public final Upscaler upscaler = new Upscaler();
	private int lastAnisotropicFilteringLevel = -1;
	private float lastTextureLodBias;

	public GpuFloatBuffer uniformBuffer;

	public int cameraYaw, cameraPitch;

	public static class RenderThread
	{
		public VAOList vaoO, vaoA;
		public ModelUploader modelUploader;
	}

	public RenderThread[] rts;

	public SceneUploader clientUploader, mapUploader;

	public static class SceneContext
	{
		public final float[] projection = Mat4.identity();

		public final int sizeX, sizeZ;
		public Zone[][] zones;

		public int cameraX, cameraY, cameraZ;
		public int minLevel, level, maxLevel;
		public Set<Integer> hideRoofIds;

		public SceneContext(int sizeX, int sizeZ)
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

		/** A view of a loaded scene's zones not swapped in yet, with the drawn scene's levels (ShadowPass.StaticShadowBuild). */
		public SceneContext(Zone[][] zones, SceneContext levels)
		{
			this.sizeX = zones.length;
			this.sizeZ = zones[0].length;
			this.zones = zones;
			this.minLevel = levels.minLevel;
			this.level = levels.level;
			this.maxLevel = levels.maxLevel;
			this.hideRoofIds = levels.hideRoofIds;
		}

		public void free(ZonePool pool, SceneJobs jobs)
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free(pool, jobs);
				}
			}
		}
	}

	public SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	public SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	// the drawn scenes: the top level and the sub world views (SceneLoader swaps them in)
	public SceneContext root;
	public SceneContext[] subs;
	public SceneLoader sceneLoader;

	// Uniforms
	public int uniFogColor;
	public int uniDrawDistance;
	public final EdgeFade edgeFade = new EdgeFade();
	public final EdgeFade.Uniforms sceneEdgeUniforms = new EdgeFade.Uniforms();
	public int uniExpandedMapLoadingChunks;
	public int uniSmoothBanding;
	public int uniWorldProj;
	public static int uniEntityProj;
	public static int uniEntityTint;
	public int uniBrightness;
	public int uniTexSourceDimensions;
	public int uniTexTargetDimensions;
	public int uniUiAlphaOverlay;
	public int uniTextureAnimations;
	public int uniTextureLightMode;
	public int uniTick;
	public int uniColorblindIntensity;
	public int uniUiColorblindIntensity;
	public static int uniBase;

	public static final float[] IDENTITY = Mat4.identity();

	@Override
	protected void startUp()
	{
		String other = otherRenderer();
		if (other != null)
		{
			log.warn("{} is enabled; Lumina will not start", other);
			chat("Lumina can't run alongside " + other + ". Turn it off, then enable Lumina again.");
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
		migrateDayLength();

		if (developerMode)
		{
			shaderWatcher = ShaderWatcher.create();
			if (shaderWatcher != null)
			{
				shaderWatcher.start();
			}
		}
		initState();

		sceneLights = new SceneLights(client);
		sceneLights.load(gson);
		eventBus.register(sceneLights);
		clientThread.invoke(sceneLights::rebuild);
		// the lights of a scene's objects are placed from the scene jobs, not in the client's event posts
		sceneLights.setJobs(sceneJobs());
		weatherAudio = new WeatherAudio();
		buildingLights = new BuildingLights(client);
		eventBus.register(buildingLights);
		clientThread.invoke(buildingLights::rebuild);
		overlayManager.add(buildingOverlay);
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

				initGlObjects();

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
					sceneLoader.startup();
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

	/**
	 * The plugin's state that needs neither the client nor GL: the data files, the scene contexts, the render threads'
	 * uploaders and the scene loader. startUp, and the offline renderer (docs/offline-render.md).
	 */
	public void initState()
	{
		glErrorChecks = config.glErrorChecks();

		SurfaceMaterials.load(gson);
		WaterOverrides.load(gson);
		areaHiding.load(gson);
		WindSway.load(gson);
		environmentManager.load(gson);
		WeatherData.load(gson);
		startMillis = com.midonarhel.lumina.common.Clock.millis();

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
		sceneLoader = new SceneLoader(this, client, clientThread, config, loadReport, renderCallbackManager);
	}

	/** The GL objects of the frame, with a context current: startUp, and the offline renderer. */
	public void initGlObjects()
	{
		glObjects.initBuffers();
		glObjects.initVao();
		programs.initProgram();
		uiBlit.init();
		if (glCapabilities.OpenGL45)
		{
			glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
		}
	}

	public int renderThreads()
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
			// the game's vertex normals (Model.getVertexNormalsX/Y/Z), which smooth faces are shaded with (VertexShading,
			// 117 HD's rule); without this flag the client leaves them null and every face comes out flat
			| DrawCallbacks.NORMALS
			| DrawCallbacks.RENDER_THREADS(threads)
		);
	}


	@Override
	protected void shutDown()
	{
		if (sceneLights != null)
		{
			eventBus.unregister(sceneLights);
			sceneLights.setLightMap(null);
			sceneLights = null;
		}
		lightMap.free();
		overlayManager.remove(buildingOverlay);
		hitchMonitor.stop();
		if (weatherAudio != null)
		{
			weatherAudio.shutDown();
			weatherAudio = null;
		}
		if (buildingLights != null)
		{
			eventBus.unregister(buildingLights);
			buildingLights.shutDown();
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
				shadowMap.free();
				ShadowMap.freeEmpty();
				weather.free();
				skyGrid.free();
				skyField.free();
				roomLight.free();
				lightClusters.free();
				lampWalls.free();
				roomTexture.free();
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

				sceneLoader.free();

				shutdownInterfaceTexture();
				programs.shutdownProgram();
				glObjects.shutdownVao();
				glObjects.shutdownBuffers();
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
	LuminaConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LuminaConfig.class);
	}

	/** The old free day length (dayCycleMinutes) becomes the nearest of the day length options, once. */
	private void migrateDayLength()
	{
		String old = configManager.getConfiguration(LuminaConfig.GROUP, "dayCycleMinutes");
		if (old == null)
		{
			return;
		}
		try
		{
			DayLength d = DayLength.nearest(Integer.parseInt(old.trim()));
			configManager.setConfiguration(LuminaConfig.GROUP, "dayLength", d);
			log.info("Day length {} minutes is now {}", old, d);
		}
		catch (NumberFormatException e)
		{
			log.warn("Day length {} not a number; left at the default", old);
		}
		configManager.unsetConfiguration(LuminaConfig.GROUP, "dayCycleMinutes");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(LuminaConfig.GROUP))
		{
			glErrorChecks = config.glErrorChecks();
			if (configChanged.getKey().equals("strikeLightning"))
			{
				if (config.strikeLightning())
				{
					weather.getLightning().strikeNow();
					configManager.setConfiguration(LuminaConfig.GROUP, "strikeLightning", false);
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
				|| configChanged.getKey().equals("debugView"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					// keeps the running programs if the new ones fail, and puts the texture animations back
					programs.reloadShaders();
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

	public void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		LuminaConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: LuminaConfig.SyncMode.OFF;

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

		int target = actualSwapInterval == 0 ? config.fpsTarget() : 0;
		client.setUnlockedFpsTarget(pacer.apply(target));
		checkGLErrors();
	}

	// the scene targets' sample count the shaders are compiled for: they read multisample textures with MSAA
	public int shaderSamples;

	/**
	 * Samples per pixel of the scene targets, 0 for none: the antialiasing mode's, or the window's when the driver
	 * forces its own, within what the multisample textures can hold.
	 */
	public int sceneSamples()
	{
		// bind the default framebuffer to check whether anti-aliasing is forced
		glBindFramebuffer(GL_FRAMEBUFFER, windowFramebuffer());
		final int forced = glGetInteger(GL_SAMPLES);
		final int max = Math.min(glGetInteger(GL_MAX_SAMPLES),
			Math.min(glGetInteger(GL_MAX_COLOR_TEXTURE_SAMPLES), glGetInteger(GL_MAX_DEPTH_TEXTURE_SAMPLES)));
		final int samples = Math.min(forced != 0 ? forced : config.antiAliasingMode().getSamples(), max);
		log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, max, forced);
		if (forced == 0 && config.antiAliasingMode().getSamples() > max)
		{
			// drawn at the GPU's most; the setting is left as chosen (profiles are shared between machines)
			log.info("Anti-aliasing {} is more than this GPU's {} samples: drawn with {}", config.antiAliasingMode(), max, samples);
		}
		return samples > 1 ? samples : 0;
	}

	// the shader programs (Programs): template, compile, uniform locations, hot reload, free
	public final Programs programs = new Programs(this);





	public float windSwayAmount()
	{
		return config.windSway() ? 1f : 0f;
	}

	public void setFogProgram(int program)
	{
		volumetricFog.setProgram(program, COMPOSITE_TEXTURE_UNIT + 2, SHADOW_COMPARE_UNIT, LIGHT_DATA_UNIT,
			CLUSTER_DATA_UNIT, FOG_NOISE_UNIT, WEATHER_FIELD_UNIT, WATER_DATA_UNIT, FOG_GROUND_UNIT, FOG_GROUND_PREV_UNIT);
	}

	public static void bindSceneSamplers(int program)
	{
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "sceneColor"), COMPOSITE_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(program, "sceneNormal"), COMPOSITE_TEXTURE_UNIT + 1);
		glUniform1i(glGetUniformLocation(program, "sceneDepth"), COMPOSITE_TEXTURE_UNIT + 2);
		glUseProgram(0);
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

	public void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage("[Lumina] " + message)
			.build());
	}


	// The frame's GL objects: the vertex arrays, the mapped buffers and the render-target FBO, created at start-up and on a size or sample change, freed at shutdown (GlObjects)
	public final GlObjects glObjects = new GlObjects(this);



	public void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}



	// the interface over the scene (UiBlit): its texture, the banded upload, the quad that draws it
	public final UiBlit uiBlit = new UiBlit(this);

	private void shutdownInterfaceTexture()
	{
		interfaceUpload.free();
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
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
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			// an armed scene dump records from here to the end of draw
			sceneDump.frameStarting(scene);
		}
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.pre(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel,
				hideRoofIds);
		}
		tempModels.flush();
		if (scene.getWorldViewId() == WorldView.TOPLEVEL && buildingLights != null)
		{
			// the building analysis' newest result, made on its worker, for every consumer this frame alike
			buildingLights.apply();
		}
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
			com.midonarhel.lumina.dev.GpuSections.section("scene setup, skybox");
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
		frameState.captureFrameState(scene, cameraX, cameraY, cameraZ);
		wadeSteps.update(client.getTopLevelWorldView(), client.getLocalPlayer(), cameraX, cameraZ,
			weather.groundActive() && client.getGameState() == GameState.LOGGED_IN);
		frameState.updateEdge(scene, cameraX, cameraZ);

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
					if (!programs.reloadShaders())
					{
						shaderSamples = samples = compiled;
					}
				}

				glObjects.initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples, renderFactor);

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
			final AffineTransform t = dpiTransform();
			frameViewport[0] = getScaledValue(t.getScaleX() * lastRenderFactor, renderWidthOff);
			frameViewport[1] = getScaledValue(t.getScaleY() * lastRenderFactor, renderCanvasHeight - renderViewportHeight - renderHeightOff);
			frameViewport[2] = getScaledValue(t.getScaleX() * lastRenderFactor, renderViewportWidth);
			frameViewport[3] = getScaledValue(t.getScaleY() * lastRenderFactor, renderViewportHeight);
		}

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final int sky = client.getSkyboxColor();
		glUniform1i(uniAlphaPass, 0);
		glUniform1i(uniSceneOutdoors, frameOutdoors ? 1 : 0);
		glUniform1i(uniSceneProceduralSky, proceduralSky() ? 1 : 0);
		glUniform3fv(uniSceneCasterDir, dayCycle.casterDir);
		glUniform1i(uniSceneSunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
		glUniform2fv(uniSceneStrikeLight, dayCycle.strikeLight);
		glUniform1f(uniSceneSunAltitude, dayCycle.sunAltitude);
		weather.setOvercastUniform(uniSceneOvercast);
		weather.setLightUniform(uniSceneWeatherLight);
		Scene noiseScene = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getScene() : null;
		glUniform2f(uniSceneNoiseOrigin, noiseScene != null ? noiseScene.getBaseX() * (float) Perspective.LOCAL_TILE_SIZE : 0,
			noiseScene != null ? noiseScene.getBaseY() * (float) Perspective.LOCAL_TILE_SIZE : 0);
		// plants can be held to a low animation rate like the game's own animations: the wind uniforms then only
		// change on those ticks (the scene and shadow programs keep the last values in between)
		long swayNow = com.midonarhel.lumina.common.Clock.nanos();
		// plants animate at the game's own 15 fps
		final int swayFps = 15;
		swayTick = swayFps <= 0 || swayNow - lastSwayTick >= 1_000_000_000L / swayFps;
		if (swayTick)
		{
			lastSwayTick = swayNow;
			weather.getWind().apply(sceneWind, weather.isWindStill());
		}
		skyFrame.setEnvironmentUniforms(sceneEnvUniforms);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
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

		// the lamps before the scene: its transparent faces are lit as they are drawn (frag.glsl)
		lampFrame.prepareLamps();
		glUseProgram(glProgram);
		glUniform1i(uniScenePointLights, framePointLights ? 1 : 0);
		sceneLampWalls.apply(frameLightClusters ? lampWalls.header() : NO_WALLS);

		skyFrame.drawSkybox(scene, sky, cameraX, cameraY, cameraZ);

		checkGLErrors();
		com.midonarhel.lumina.dev.GpuSections.section("geometry");
		cpuTimer.begin(CpuTimer.CLIENT_SCENE);
	}

	// The sky's work each frame: the skybox draw, the night sky, clouds and sky cache updates, the area environment and its uniforms (SkyFrame)
	public final SkyFrame skyFrame = new SkyFrame(this);

	@Override
	public void postSceneDraw(Scene scene)
	{
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.post(scene);
		}
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

		com.midonarhel.lumina.dev.GpuSections.section("scene end");
		sceneTargets.endScene();
		try
		{
			if (frameShadows)
			{
				cpuTimer.begin(CpuTimer.SHADOW_SUBMIT);
				com.midonarhel.lumina.dev.GpuSections.section("shadows");
				shadowPass.render();
				cpuTimer.end(CpuTimer.SHADOW_SUBMIT);
			}
			shadowPass.report();
			cpuTimer.begin(CpuTimer.OCCLUSION);
			com.midonarhel.lumina.dev.GpuSections.section("weather occlusion");
			if (weather.isActive())
			{
				// usually nothing: the map is only redrawn after the player moves far or the scenery changes
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
		VAO.recordShadowDraws = false;
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, windowFramebuffer());
		sceneFboValid = true;
		cpuTimer.begin(CpuTimer.CLIENT_UI);
	}

	// The per-frame state: the camera, draw distance, sky and fog captured for this frame, the edge fade's focus, a view ray, the frame uniforms, the alpha pass's blend state (FrameState)
	public final FrameState frameState = new FrameState(this);


	@Override
	public boolean zoneInFrustum(int zx, int zz, int maxY, int minY)
	{
		// the client asks for the top level only, in its zone indices (as drawZoneOpaque gets them)
		int offset = SCENE_OFFSET >> 3;
		return edgeFade.zoneVisible((zx - offset) << 10, (zz - offset) << 10, minY, maxY);
	}

	/** Whether the composite draws the procedural sky: not over the game's own skybox, nor in a void. */
	public boolean proceduralSky()
	{
		return config.proceduralSky() && !frameHasSkybox && !frameSkyVoid;
	}

	// The static visibility's work each frame: the skylight grid update and the voxel handover (VisibilityFrame)
	public final VisibilityFrame visibilityFrame = new VisibilityFrame(this);



	// The weather's work each frame: the field and the driver updated from the camera, and the weather sounds (WeatherFrame)
	public final WeatherFrame weatherFrame = new WeatherFrame(this);




	// the shadow pass (ShadowPass): the map from the light each frame, its static cache, a strike's bolt map
	public final ShadowPass shadowPass = new ShadowPass(this);

	/** Draw the static shadow map of a scene being loaded (SceneLoader): see ShadowPass.prebuild. */
	public ShadowPass.StaticShadowBuild prebuildStaticShadows(Scene scene, Zone[][] zones)
	{
		return shadowPass.prebuild(scene, zones);
	}

	/** A load was dropped before its swap (SceneLoader): see ShadowPass.drop. */
	public void dropStaticShadows(ShadowPass.StaticShadowBuild dropped)
	{
		shadowPass.drop(dropped);
	}


	/** Whether the window's framebuffer has a stencil (windowStencil); call with it bound. */
	public boolean hasWindowStencil()
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


	// The lamps' work each frame: lamp collection and placement, the room light, the light clusters' upload (LampFrame)
	public final LampFrame lampFrame = new LampFrame(this);





	int getLightDefinitionCount()
	{
		SceneLights lights = sceneLights;
		return lights != null ? lights.getDefinitionCount() : 0;
	}

	Weather getWeather()
	{
		return weather;
	}

	public BuildingLights getBuildingLights()
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


	double getSwapIntervalMs()
	{
		return swapIntervalMs;
	}

	double getHourOfDay()
	{
		return dayCycle.hour();
	}






	// The scene draw: zones opaque and alpha, the draw pass, dynamic and temporary models, zone invalidation, called from the plugin's DrawCallbacks (SceneDraw)
	public final SceneDraw sceneDraw = new SceneDraw(this);
	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.zoneOpaque(scene, zx, zz);
		}
		sceneDraw.drawZoneOpaque(entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.zoneAlpha(scene, level, zx, zz);
		}
		sceneDraw.drawZoneAlpha(entityProjection, scene, level, zx, zz);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.pass(scene, projection, pass);
		}
		sceneDraw.drawPass(projection, scene, pass);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		sceneDraw.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		sceneDraw.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		sceneDraw.invalidateZone(scene, zx, zz);
	}


	public static final int ALPHA_ZSORT_CLOSE = 2048;






	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		if (developerMode)
		{
			if (commandFile == null)
			{
				Path file = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("command-perfwip.txt");
				commandFile = new CommandFile(file, commands::run);
			}
			commandFile.poll();
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		hideAreas(wv);
		sceneLoader.rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			sceneLoader.rebuild(wv);
		}
	}

	/** The scene is cut to the area the player was in as it loaded: load it again when they walk into another. */
	private void hideAreas(WorldView wv)
	{
		Player player = client.getLocalPlayer();
		if (player == null || sceneLoader.loading() || client.getGameState() != GameState.LOGGED_IN)
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
	public boolean hasAction(int id)
	{
		ObjectComposition comp = client.getObjectDefinition(id);
		if (comp != null && comp.getImpostorIds() != null && comp.getImpostor() != null)
		{
			comp = comp.getImpostor();
		}
		return comp != null && Interactables.hasAction(comp.getActions());
	}




	// the interface texture holds this frame's canvas (finishInterfaceUpload)
	public boolean interfaceFinished;


	@Override
	public void draw(int overlayColor)
	{
		cpuTimer.end(CpuTimer.CLIENT_UI);
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}
		FrameTape tape = frameTape;
		if (tape != null)
		{
			tape.draw(overlayColor);
			sceneDump.ui(client.getBufferProvider());
		}

		ShaderWatcher watcher = shaderWatcher;
		if (watcher != null && watcher.poll())
		{
			hitchMonitor.job(HitchMonitor.SHADER_RELOAD);
			programs.reloadShaders();
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

		com.midonarhel.lumina.dev.GpuSections.section("UI upload");
		cpuTimer.begin(CpuTimer.UI_UPLOAD);
		uiBlit.prepare(canvasWidth, canvasHeight);
		cpuTimer.end(CpuTimer.UI_UPLOAD);

		interfaceFinished = false;
		boolean composited = false;
		if (sceneFboValid && sceneTargets.isInitialized() && composite.compositeFull.program != 0)
		{
			try
			{
				composite.draw();
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
		uiBlit.finish();
		glBindFramebuffer(GL_FRAMEBUFFER, windowFramebuffer());
		if (!composited)
		{
			// the composite covers the whole drawable; without it the window is cleared here instead. Clearing it
			// up front would open the drawable's pass early and store and reload it around the composite's passes.
			glClearColor(0, 0, 0, 1);
			glClear(GL_COLOR_BUFFER_BIT);
		}

		// Texture on UI
		com.midonarhel.lumina.dev.GpuSections.section("UI draw");
		uiBlit.draw(overlayColor, canvasHeight, canvasWidth);
		com.midonarhel.lumina.dev.GpuSections.end();

		cpuTimer.begin(CpuTimer.SWAP);
		try
		{
			// the offline renderer's framebuffer is read back, not presented
			if (offscreenFramebuffer < 0)
			{
				awtContext.swapBuffers();
				pacer.pace();
			}
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
		long now = System.nanoTime();
		double swapIntervalNow = 0;
		if (lastSwapNanos != 0)
		{
			double ms = (now - lastSwapNanos) / 1e6;
			swapIntervalMs = swapIntervalMs == 0 ? ms : swapIntervalMs * 0.95 + ms * 0.05;
			swapIntervalNow = ms;
		}
		lastSwapNanos = now;
		frameSnapshot.frame();
		// the frame's CPU steps first, so the hitch monitor and the load report read this frame's (they read one frame
		// late before: a hitch line's "swap wait 12–16 ms" was the frame before it)
		cpuTimer.endFrame();
		// the hitch monitor attributes slow frames to the jobs that ran in them (and GC); always on, it rate-limits itself
		hitchMonitor.endFrame(swapIntervalNow, cpuTimer);
		loadReport.frame(swapIntervalNow, cpuTimer);
		cpuTimer.begin(CpuTimer.OUTSIDE);
		drawManager.processDrawComplete(commands::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, windowFramebuffer());

		// the scene loads' GL work, within its budget, after the frame is presented: its time is the next frame's
		sceneLoader.runJobs();
		// an armed scene dump's frame is done: what it drew from is read back and written
		sceneDump.frameDone();

		checkGLErrors();
	}


	// the developer commands (Commands): ::runelight …, the command file, the screenshot
	public final Commands commands = new Commands(this);
	// the scene dump (::runelight scenedump name) and the frame it records, null between dumps (docs/offline-render.md)
	public final SceneDump sceneDump = new SceneDump(this);
	public volatile FrameTape frameTape;

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		sceneLoader.load(worldView, scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		sceneLoader.swap(scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		sceneLoader.despawn(worldView);
	}

	/**
	 * The one queue for GL work that must not land on a single frame (scene loads, rebuilds of per-scene textures): add
	 * a job with SceneJobs.add(name, priority, job); its step is handed a deadline and does as much as fits before it,
	 * returning true when done. The client thread runs it, ≤ 1 ms a frame for all jobs (4 ms for LOAD jobs while loading).
	 */
	SceneJobs sceneJobs()
	{
		return sceneLoader.jobs;
	}

	/** A top level swap begins (SceneLoader). */
	public void sceneSwapStarted()
	{
		hitchMonitor.job(HitchMonitor.SCENE_SWAP);
		framesSinceSwap = 0;
	}

	/** A top level load is built and waits for its swap (SceneLoader, the map loader's thread). */
	public void loadBuilt()
	{
		if (swapCaptureArmed)
		{
			// the load is done; the client swaps on one of the next frames: capture from here, so the swap frame
			// itself and its neighbours are all on file (the user's flash is a whole frame, before frame +1)
			swapCaptureArmed = false;
			swapCaptureIndex = 0;
			swapCaptureLeft = swapCaptureFrames;
		}
	}

	/**
	 * The loaded record became the drawn scene (SceneLoader.swap), client thread: every consumer takes its part of it
	 * now, or keeps the old one until the job building it finishes, the same for all. No GL call.
	 */
	public void sceneSwapped(SceneLoader.SceneDerived d)
	{
		long t0 = System.nanoTime();
		weather.invalidateOcclusion();
		water.swap(d.water);
		long tWater = System.nanoTime();
		volumetricFog.swap(d.fogGround);
		voxelBuilder.swapTo(d.voxelBuild);
		shadowPass.swapped(d.shadows);
		long tEnd = System.nanoTime();
		if (tEnd - t0 > 500_000)
		{
			log.info(String.format("Scene swap's derived data %.2f ms: water %.2f, the rest %.2f", (tEnd - t0) / 1e6,
				(tWater - t0) / 1e6, (tEnd - tWater) / 1e6));
		}
	}

	/** A zone of the top level scene was uploaded again (SceneLoader.rebuild). */
	public void zoneRebuilt()
	{
		hitchMonitor.job(HitchMonitor.ZONE_REBUILD);
		weather.invalidateOcclusion();
		shadowMap.refreshCache();
		voxelBuilder.zonesChanged();
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








	// The offline renderer (docs/offline-render.md) draws into a framebuffer of its own at a fixed DPI scale, with no
	// window: both stay unset (-1, null) in the client.
	public int offscreenFramebuffer = -1;
	public AffineTransform offscreenTransform;

	/** The framebuffer the frame ends in: the window's (the GL bridge's), or the offline renderer's. */
	public int windowFramebuffer()
	{
		return offscreenFramebuffer >= 0 ? offscreenFramebuffer : awtContext.getFramebuffer(false);
	}

	/** The window's DPI scale (its graphics configuration's), or the offline renderer's. */
	public AffineTransform dpiTransform()
	{
		return offscreenTransform != null ? offscreenTransform : clientUI.getGraphicsConfiguration().getDefaultTransform();
	}

	public int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	public void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		glDpiAwareViewport(x, y, width, height, 1);
	}

	/** renderFactor shrinks the viewport with the scene targets (RenderScale). */
	public void glDpiAwareViewport(final int x, final int y, final int width, final int height, final double renderFactor)
	{
		final AffineTransform t = dpiTransform();
		glViewport(
			getScaledValue(t.getScaleX() * renderFactor, x),
			getScaledValue(t.getScaleY() * renderFactor, y),
			getScaledValue(t.getScaleX() * renderFactor, width),
			getScaledValue(t.getScaleY() * renderFactor, height));
	}

	private double renderFactor()
	{
		return config.renderScale().factor(dpiTransform().getScaleX());
	}

	public int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	public void checkGLErrors()
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
		if (event.getCommand().equals("runelight"))
		{
			commands.run(event.getArguments());
			return;
		}
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
