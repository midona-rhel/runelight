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
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
import net.runelite.api.Perspective;
import net.runelite.api.Player;
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
import com.midonarhel.runelight.config.UIScalingMode;
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
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RuneLightConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private RegionManager regionManager;

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

	private int glCompositeProgram;
	private int uniCompositeDebugView;
	private int uniCompositeAoEnabled;
	private int uniCompositeAoStrength;
	private final FrameUniforms compositeFrame = new FrameUniforms();

	private int glAoProgram;
	private int uniAoRadius;
	private final FrameUniforms aoFrame = new FrameUniforms();

	private static final int AO_TEXTURE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 3;
	private static final int SHADOW_RAW_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 4;
	private static final int SHADOW_COMPARE_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 5;
	private static final int LIGHT_DATA_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 6;
	private static final int CLUSTER_DATA_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 7;

	private final LightClusters lightClusters = new LightClusters();
	private final java.util.ArrayList<PointLight> frameLights = new java.util.ArrayList<>();
	private final PointLight testLight = new PointLight();
	private int uniCompositePointLights;
	private int uniAlphaPass;
	private int uniSceneOutdoors;
	private int uniSceneProceduralSky;
	private int uniSceneCasterDir;
	private int uniSceneSunIsCaster;
	private int uniSceneSunAltitude;
	private static final int ALPHA_LAYER_UNIT = RuneLightPlugin.COMPOSITE_TEXTURE_UNIT + 8;

	static final Shader SHADOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	private int glShadowProgram;
	private int uniShadowLightProj;
	private int uniShadowEntityProj;
	private int uniShadowBase;
	private int uniShadowTexturesReady;

	// composite lighting uniforms
	private int uniCompositeCameraPos;
	private int uniCompositeTime;
	private int uniCompositeOutdoors;
	private int uniCompositeProceduralSky;
	private int uniCompositeLightingEnabled;
	private int uniCompositeSunDir;
	private int uniCompositeMoonDir;
	private int uniCompositeCasterDir;
	private int uniCompositeSunIsCaster;
	private int uniCompositeSunAltitude;
	private int uniCompositeShadowsEnabled;
	private int uniCompositeLightProjRel;
	private int uniCompositeShadowRadius;
	private int uniCompositeShadowDepthRange;
	private int uniCompositeShadowMapSize;
	private int uniCompositeUseFog;
	private int uniCompositeFogDepth;
	private int uniCompositeDrawDistance;
	private int uniCompositeExpandedChunks;
	private int uniCompositeClientSkyColor;

	private long frameCpuStart;
	private volatile double cpuFrameMs;

	private final DayCycle dayCycle = new DayCycle();
	final EnvironmentManager environmentManager = new EnvironmentManager();
	private final EnvironmentUniforms sceneEnvUniforms = new EnvironmentUniforms();
	private final EnvironmentUniforms compositeEnvUniforms = new EnvironmentUniforms();
	private final ShadowMap shadowMap = new ShadowMap();
	// when the plugin started; the day cycle and sky animation count from here
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
		int viewProj, invViewProj, viewport, depthZeroToOne;

		void locate(int program)
		{
			viewProj = glGetUniformLocation(program, "viewProj");
			invViewProj = glGetUniformLocation(program, "invViewProj");
			viewport = glGetUniformLocation(program, "sceneViewport");
			depthZeroToOne = glGetUniformLocation(program, "depthZeroToOne");
		}
	}

	private final float[] frameViewProj = Mat4.identity();
	private float[] frameInvViewProj = Mat4.identity();
	// scene viewport within the scene targets, in framebuffer pixels: x, y, width, height
	private final int[] frameViewport = new int[4];

	static int glProgram;
	private int glUiProgram;

	private int interfaceTexture;
	private int interfacePbo;

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
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	static class RenderThread
	{
		VAOList vaoO, vaoA;
		float[] tmp = new float[3];
		ModelUploader modelUploader;
	}

	private RenderThread[] rts;

	private SceneUploader clientUploader, mapUploader;

	static class SceneContext
	{
		final float[] projection = Mat4.identity();

		final int sizeX, sizeZ;
		Zone[][] zones;

		private int cameraX, cameraY, cameraZ;
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
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniBlockMain;
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

		sceneLights = new SceneLights(client);
		sceneLights.load(gson);
		eventBus.register(sceneLights);
		clientThread.invoke(sceneLights::rebuild);
		environmentManager.load(gson);
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

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
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

	private void setupGpuFlags()
	{
		int cpus = Runtime.getRuntime().availableProcessors();
		int threads = Math.min(cpus - 1, config.numThreads());
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
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

			if (lwjglInitted)
			{
				gpuTimer.free();
				shadowMap.free();
				lightClusters.free();

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
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				setupGpuFlags();
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					shutdownProgram();
					initProgram();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
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
						+ "#define CLUSTER_STRIDE " + (LightClusters.MAX_LIGHTS_PER_CLUSTER + 1) + "\n"
						+ "#define CLUSTER_NEAR " + LightClusters.NEAR + "\n"
						+ "#define CLUSTER_FAR " + LightClusters.FAR + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
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

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glCompositeProgram = COMPOSITE_PROGRAM.compile(template);
		glAoProgram = AO_PROGRAM.compile(template);
		glShadowProgram = SHADOW_PROGRAM.compile(template);

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
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");

		uniCompositeDebugView = glGetUniformLocation(glCompositeProgram, "debugView");
		uniCompositeAoEnabled = glGetUniformLocation(glCompositeProgram, "aoEnabled");
		uniCompositeAoStrength = glGetUniformLocation(glCompositeProgram, "aoStrength");
		uniAlphaPass = glGetUniformLocation(glProgram, "alphaPass");
		uniSceneOutdoors = glGetUniformLocation(glProgram, "outdoors");
		uniSceneProceduralSky = glGetUniformLocation(glProgram, "proceduralSky");
		uniSceneCasterDir = glGetUniformLocation(glProgram, "casterDir");
		uniSceneSunIsCaster = glGetUniformLocation(glProgram, "sunIsCaster");
		uniSceneSunAltitude = glGetUniformLocation(glProgram, "sunAltitude");
		sceneEnvUniforms.locate(glProgram);
		compositeEnvUniforms.locate(glCompositeProgram);
		compositeFrame.locate(glCompositeProgram);
		bindSceneSamplers(glCompositeProgram);
		glUseProgram(glCompositeProgram);
		glUniform1i(glGetUniformLocation(glCompositeProgram, "sceneAo"), AO_TEXTURE_UNIT);
		glUseProgram(0);

		uniAoRadius = glGetUniformLocation(glAoProgram, "aoRadius");
		aoFrame.locate(glAoProgram);
		bindSceneSamplers(glAoProgram);

		int c = glCompositeProgram;
		uniCompositeCameraPos = glGetUniformLocation(c, "cameraPos");
		uniCompositeTime = glGetUniformLocation(c, "time");
		uniCompositeOutdoors = glGetUniformLocation(c, "outdoors");
		uniCompositeProceduralSky = glGetUniformLocation(c, "proceduralSky");
		uniCompositeLightingEnabled = glGetUniformLocation(c, "lightingEnabled");
		uniCompositeSunDir = glGetUniformLocation(c, "sunDir");
		uniCompositeMoonDir = glGetUniformLocation(c, "moonDir");
		uniCompositeCasterDir = glGetUniformLocation(c, "casterDir");
		uniCompositeSunIsCaster = glGetUniformLocation(c, "sunIsCaster");
		uniCompositeSunAltitude = glGetUniformLocation(c, "sunAltitude");
		uniCompositeShadowsEnabled = glGetUniformLocation(c, "shadowsEnabled");
		uniCompositeLightProjRel = glGetUniformLocation(c, "lightProjRel");
		uniCompositeShadowRadius = glGetUniformLocation(c, "shadowRadius");
		uniCompositeShadowDepthRange = glGetUniformLocation(c, "shadowDepthRange");
		uniCompositeShadowMapSize = glGetUniformLocation(c, "shadowMapSize");
		uniCompositeUseFog = glGetUniformLocation(c, "useFog");
		uniCompositeFogDepth = glGetUniformLocation(c, "fogDepth");
		uniCompositeDrawDistance = glGetUniformLocation(c, "drawDistance");
		uniCompositeExpandedChunks = glGetUniformLocation(c, "expandedMapLoadingChunks");
		uniCompositeClientSkyColor = glGetUniformLocation(c, "clientSkyColor");
		glUseProgram(c);
		glUniform1i(glGetUniformLocation(c, "shadowRaw"), SHADOW_RAW_UNIT);
		glUniform1i(glGetUniformLocation(c, "shadowCompare"), SHADOW_COMPARE_UNIT);
		glUniform1i(glGetUniformLocation(c, "lightData"), LIGHT_DATA_UNIT);
		glUniform1i(glGetUniformLocation(c, "clusterData"), CLUSTER_DATA_UNIT);
		glUniform1i(glGetUniformLocation(c, "sceneAlpha"), ALPHA_LAYER_UNIT);
		uniCompositePointLights = glGetUniformLocation(c, "pointLightsEnabled");
		glUseProgram(0);

		uniShadowLightProj = glGetUniformLocation(glShadowProgram, "lightProj");
		uniShadowEntityProj = glGetUniformLocation(glShadowProgram, "entityProj");
		uniShadowBase = glGetUniformLocation(glShadowProgram, "base");
		uniShadowTexturesReady = glGetUniformLocation(glShadowProgram, "texturesReady");
		glUseProgram(glShadowProgram);
		glUniform1i(glGetUniformLocation(glShadowProgram, "textures"), 1); // game texture array lives on unit 1
		glUseProgram(0);
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

		glDeleteProgram(glCompositeProgram);
		glCompositeProgram = 0;

		glDeleteProgram(glAoProgram);
		glAoProgram = 0;

		glDeleteProgram(glShadowProgram);
		glShadowProgram = 0;
	}

	/**
	 * Recompile every program. On failure the previous programs stay in use, so a typo while editing
	 * a shader shows an error instead of taking down the renderer.
	 */
	private void reloadShaders()
	{
		glBindVertexArray(vaoUiHandle);
		Template template = createTemplate();
		int scene = 0, ui = 0, composite = 0, ao = 0, shadow = 0;
		try
		{
			scene = PROGRAM.compile(template);
			ui = UI_PROGRAM.compile(template);
			composite = COMPOSITE_PROGRAM.compile(template);
			ao = AO_PROGRAM.compile(template);
			shadow = SHADOW_PROGRAM.compile(template);
		}
		catch (ShaderException ex)
		{
			// glDeleteProgram ignores 0
			glDeleteProgram(scene);
			glDeleteProgram(ui);
			glDeleteProgram(composite);
			glDeleteProgram(ao);
			String msg = ex.getMessage();
			if (!msg.equals(shaderError))
			{
				shaderError = msg;
				log.error("Shader reload failed:\n{}", msg);
				chat("Shader error: " + firstLines(msg, 3));
			}
			return;
		}
		finally
		{
			glBindVertexArray(0);
		}

		shutdownProgram();
		glProgram = scene;
		glUiProgram = ui;
		glCompositeProgram = composite;
		glAoProgram = ao;
		glShadowProgram = shadow;
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
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		sceneTargets.init(width, height, aaSamples);

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
	}

	private void shutdownFbo()
	{
		sceneTargets.free();
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
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
			for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
			{
				rts[i].vaoO.map();
				rts[i].vaoA.map();
			}

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
		gpuTimer.begin(GpuTimer.SCENE);
		frameCpuStart = System.nanoTime();
		captureFrameState(scene, cameraX, cameraY, cameraZ);
		scene.setDrawDistance(getDrawDistance());

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
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownFbo();

				// Bind default FBO to check whether anti-aliasing is forced
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
				final int forcedAASamples = glGetInteger(GL_SAMPLES);
				final int maxSamples = glGetInteger(GL_MAX_SAMPLES);
				final int samples = forcedAASamples != 0 ? forcedAASamples :
					Math.min(antiAliasingMode.getSamples(), maxSamples);

				log.debug("AA samples: {}, max samples: {}, forced samples: {}", samples, maxSamples, forcedAASamples);

				initFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
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

		glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);
		{
			final AffineTransform t = clientUI.getGraphicsConfiguration().getDefaultTransform();
			frameViewport[0] = getScaledValue(t.getScaleX(), renderWidthOff);
			frameViewport[1] = getScaledValue(t.getScaleY(), renderCanvasHeight - renderViewportHeight - renderHeightOff);
			frameViewport[2] = getScaledValue(t.getScaleX(), renderViewportWidth);
			frameViewport[3] = getScaledValue(t.getScaleY(), renderViewportHeight);
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
		glUniform1i(uniSceneProceduralSky, config.proceduralSky() && !frameHasSkybox ? 1 : 0);
		glUniform3fv(uniSceneCasterDir, dayCycle.casterDir);
		glUniform1i(uniSceneSunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
		glUniform1f(uniSceneSunAltitude, dayCycle.sunAltitude);
		setEnvironmentUniforms(sceneEnvUniforms);
		glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		glUniform1i(uniFogDepth, fogDepth);
		glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);
		glUniform1i(uniExpandedMapLoadingChunks, client.getExpandedMapLoading());
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());

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
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(glProgram, uniBlockMain, 0);
		glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		drawSkybox(scene, sky, cameraX, cameraY, cameraZ);
		sceneTargets.clearAlphaLayer();

		checkGLErrors();
	}

	private void drawSkybox(Scene scene, int sky, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (skybox == null)
		{
			glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
			return;
		}

		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, 0, 0, 0, 0,
			(int) cameraX, (int) cameraY, (int) cameraZ, o.vbo.vb);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
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

		sceneTargets.resolve();
		if (frameShadows)
		{
			gpuTimer.begin(GpuTimer.SHADOW);
			renderShadowMap();
		}
		gpuTimer.end();
		VAO.recordShadowDraws = false;
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
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
		frameFogDepth = config.fogDepth();
		frameDrawDistance = getDrawDistance() * Perspective.LOCAL_TILE_SIZE;

		boolean openSky = updateEnvironment(scene);
		frameOutdoors = config.sunLighting() && openSky;

		dayCycle.update(System.currentTimeMillis() - startMillis, config.dayCycleMinutes(), config.fixedHour());

		frameShadows = frameOutdoors && config.shadows() && client.getGameState() == GameState.LOGGED_IN;
		VAO.clearShadowDraws();
		VAO.recordShadowDraws = frameShadows;
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
		u.set(on ? environmentManager : null, config.areaLightStrength() / 100f, fog, getDrawDistance());
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
		shadowMap.update(dayCycle.casterDir, fx, fy, fz, frameCameraX, frameCameraY, frameCameraZ,
			radius, depthRange, glCapabilities.OpenGL45);

		glBindFramebuffer(GL_FRAMEBUFFER, shadowMap.getFbo());
		glViewport(0, 0, size, size);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		// both faces cast: OSRS models are not reliably closed, and backfaces reduce light leaking
		glDisable(GL_CULL_FACE);
		glEnable(GL_POLYGON_OFFSET_FILL);
		glPolygonOffset(1.0f, 1.0f);

		glUseProgram(glShadowProgram);
		glUniformMatrix4fv(uniShadowLightProj, false, shadowMap.lightProj);
		glUniform1i(uniShadowTexturesReady, textureArrayId != -1 ? 1 : 0);
		glUniformMatrix4fv(uniShadowEntityProj, false, IDENTITY);

		// Zone and VAO draw through these shared uniform locations; point them at the shadow program
		final int savedBase = uniBase, savedEntityProj = uniEntityProj, savedEntityTint = uniEntityTint;
		uniBase = uniShadowBase;
		uniEntityProj = uniShadowEntityProj;
		uniEntityTint = -1;
		try
		{
			// static scenery: zones around the focus, with margin for tall casters just outside the map
			int offset = SCENE_OFFSET >> 3;
			float reach = radius + 2048;
			final boolean roofShadows = config.roofShadows();
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
					Zone zone = ctx.zones[x][z];
					if (zone.initialized && zone.sizeO > 0)
					{
						if (roofShadows)
						{
							// every level and every roof, including ones hidden from the camera
							zone.renderOpaque(x - offset, z - offset, ctx.minLevel, ctx.level, 3, Collections.emptySet());
						}
						else
						{
							zone.renderOpaque(x - offset, z - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
						}
					}
				}
			}

			// players, NPCs and other models drawn this frame
			glUniform3i(uniShadowBase, 0, 0, 0);
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

	/**
	 * Transparent faces go to their own layer, blended as premultiplied "over" so the composite can put the layer
	 * on top of the lit opaque scene. They don't write depth (Zone and VAO already turn depth writes off).
	 */
	private void setAlphaPass(boolean alpha)
	{
		sceneTargets.setAlphaPass(alpha);
		if (alpha)
		{
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		}
		else
		{
			glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);
		}
		glUniform1i(uniAlphaPass, alpha ? 1 : 0);
	}

	private void setFrameUniforms(FrameUniforms u)
	{
		glUniformMatrix4fv(u.viewProj, false, frameViewProj);
		glUniformMatrix4fv(u.invViewProj, false, frameInvViewProj);
		glUniform4i(u.viewport, frameViewport[0], frameViewport[1], frameViewport[2], frameViewport[3]);
		glUniform1i(u.depthZeroToOne, glCapabilities.OpenGL45 ? 1 : 0);
	}

	private void setLightingUniforms()
	{
		glUniform3f(uniCompositeCameraPos, frameCameraX, frameCameraY, frameCameraZ);
		glUniform1f(uniCompositeTime, (System.currentTimeMillis() - startMillis) / 1000f);
		glUniform1i(uniCompositeOutdoors, frameOutdoors ? 1 : 0);
		glUniform1i(uniCompositeProceduralSky, config.proceduralSky() && !frameHasSkybox ? 1 : 0);
		glUniform1i(uniCompositeLightingEnabled, 1);
		glUniform3fv(uniCompositeSunDir, dayCycle.sunDir);
		glUniform3fv(uniCompositeMoonDir, dayCycle.moonDir);
		glUniform3fv(uniCompositeCasterDir, dayCycle.casterDir);
		glUniform1i(uniCompositeSunIsCaster, dayCycle.sunIsCaster ? 1 : 0);
		glUniform1f(uniCompositeSunAltitude, dayCycle.sunAltitude);
		setEnvironmentUniforms(compositeEnvUniforms);

		boolean shadows = frameShadows && shadowMap.isInitialized();
		glUniform1i(uniCompositeShadowsEnabled, shadows ? 1 : 0);
		if (shadows)
		{
			glUniformMatrix4fv(uniCompositeLightProjRel, false, shadowMap.lightProjRel);
			glUniform1f(uniCompositeShadowRadius, shadowMap.getRadius());
			glUniform1f(uniCompositeShadowDepthRange, shadowMap.getDepthRange());
			glUniform1f(uniCompositeShadowMapSize, shadowMap.getSize());
			shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		}

		boolean points = config.pointLights();
		if (points)
		{
			if (!lightClusters.isInitialized())
			{
				lightClusters.init();
			}
			collectLights();
			lightClusters.update(frameLights, frameViewProj, frameCameraX, frameCameraY, frameCameraZ);
			lightClusters.bind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT);
		}
		glUniform1i(uniCompositePointLights, points ? 1 : 0);

		glUniform1i(uniCompositeUseFog, frameFogDepth > 0 ? 1 : 0);
		glUniform1i(uniCompositeFogDepth, frameFogDepth);
		glUniform1i(uniCompositeDrawDistance, frameDrawDistance);
		glUniform1i(uniCompositeExpandedChunks, client.getExpandedMapLoading());
		glUniform3f(uniCompositeClientSkyColor,
			(frameSkyColor >> 16 & 0xFF) / 255f, (frameSkyColor >> 8 & 0xFF) / 255f, (frameSkyColor & 0xFF) / 255f);
	}

	/**
	 * Gather this frame's point lights: scene lights from lights.json, plus the optional test light.
	 */
	private void collectLights()
	{
		frameLights.clear();
		SceneLights lights = sceneLights;
		if (lights != null)
		{
			float time = (System.currentTimeMillis() - startMillis) / 1000f;
			// 0 by day, 1 once the sun is well below the horizon
			float t = Math.max(0, Math.min(1, (-2 - dayCycle.sunAltitude) / 8));
			float night = frameOutdoors ? t * t * (3 - 2 * t) : 1;
			lights.collect(frameLights, time, night, frameCameraX, frameCameraZ, frameDrawDistance);
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
	}

	int getLightDefinitionCount()
	{
		SceneLights lights = sceneLights;
		return lights != null ? lights.getDefinitionCount() : 0;
	}

	int getPointLightCount()
	{
		return lightClusters.getLightCount();
	}

	double getCpuFrameMs()
	{
		return cpuFrameMs;
	}

	double getHourOfDay()
	{
		return dayCycle.hour();
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
		// fullscreen triangles are generated from gl_VertexID; core profile still needs some VAO bound
		glBindVertexArray(vaoUiHandle);

		if (ao)
		{
			glBindFramebuffer(GL_FRAMEBUFFER, sceneTargets.getAoFbo());
			glViewport(0, 0, sceneTargets.getAoWidth(), sceneTargets.getAoHeight());
			glUseProgram(glAoProgram);
			setFrameUniforms(aoFrame);
			glUniform1f(uniAoRadius, config.aoRadius() / 100f * Perspective.LOCAL_TILE_SIZE);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			sceneTargets.bindAo(AO_TEXTURE_UNIT);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glViewport(0, 0, width, height);

		glUseProgram(glCompositeProgram);
		setFrameUniforms(compositeFrame);
		glUniform1i(uniCompositeDebugView, config.debugView().ordinal());
		glUniform1i(uniCompositeAoEnabled, ao ? 1 : 0);
		glUniform1f(uniCompositeAoStrength, config.aoStrength() / 100f);
		setLightingUniforms();
		sceneTargets.bindAlphaLayer(ALPHA_LAYER_UNIT);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindVertexArray(0);
		sceneTargets.unbindTextures(COMPOSITE_TEXTURE_UNIT);
		glActiveTexture(GL_TEXTURE0 + AO_TEXTURE_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		ShadowMap.unbind(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		LightClusters.unbind(LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT);
		glActiveTexture(GL_TEXTURE0 + ALPHA_LAYER_UNIT);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
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

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb);
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
				sorter.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb, false);
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

		Renderable renderable = gameObject.getRenderable();
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0)
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb, a.vbo.vb,
					renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH);
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? renderMode : 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			ModelUploader uploader = rt.modelUploader;
			uploader.uploadTempModel(m, orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
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
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
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

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
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

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
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
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		else
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		ShaderWatcher watcher = shaderWatcher;
		if (watcher != null && watcher.poll())
		{
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

		prepareInterfaceTexture(canvasWidth, canvasHeight);

		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid && sceneTargets.isInitialized())
		{
			gpuTimer.begin(GpuTimer.POST);
			compositeScene();
		}
		gpuTimer.begin(GpuTimer.PRESENT);

		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		gpuTimer.end();
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

		gpuTimer.endFrame();
		if (frameCpuStart != 0)
		{
			// CPU time from the start of the scene draw to the swap, including the client's own scene traversal
			double ms = (System.nanoTime() - frameCpuStart) / 1e6;
			cpuFrameMs = cpuFrameMs == 0 ? ms : cpuFrameMs * 0.9 + ms * 0.1;
			frameCpuStart = 0;
		}
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
		glUniform1i(uniTex, 0);
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
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

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
			&& gameState == GameState.LOGGED_IN)
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

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
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

		ctx.zones = nextZones;
		nextZones = null;

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
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
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

			log.warn("glGetError:", new Exception(errStr));
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
