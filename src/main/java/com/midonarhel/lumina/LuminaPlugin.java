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

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DynamicObject;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Projectile;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.UIScalingMode;
import com.midonarhel.lumina.geometry.DiffuseColors;
import com.midonarhel.lumina.config.ColorFilter;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.util.ObjectOrientation;
import com.midonarhel.lumina.scene.areas.Area;
import net.runelite.api.Player;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.LightingSource;
import com.midonarhel.lumina.lighting.LightingUniforms;
import com.midonarhel.lumina.lighting.SkyLighting;
import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.SceneShading;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
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
	name = "Lumina",
	description = "Lighting-focused GPU renderer",
	tags = {"gpu", "lighting", "shadows", "fog", "draw distance"},
	conflicts = {"GPU", "117 HD"},
	loadInSafeMode = false
)
@Slf4j
public class LuminaPlugin extends Plugin implements DrawCallbacks
{
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
	private LuminaConfig config;

	@Inject
	private TextureManager textureManager;


	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private SceneShading sceneShading;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SkyLighting skyLighting;

	/**
	 * The scene light, filled each frame by {@link #lightingSource} and uploaded as the scene shader's lighting uniforms.
	 */
	private final Lighting lighting = new Lighting();
	/**
	 * What decides the scene light: 117 HD's environment, sky and daylight-cycle lighting.
	 */
	private LightingSource lightingSource;
	private LightingUniforms sceneLightingUniforms, skyLightingUniforms;
	// 117 HD's plugin.elapsedTime: seconds since the plugin started
	private long startNanos;

	private Canvas canvas;
	private AWTContext awtContext;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	// developer mode: commands from .runelite/lumina/command.txt, as if typed as ::lumina <line>
	private final CommandFile commandFile = new CommandFile(
		net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("command.txt"), this::runLuminaCommand);

	private final GpuTimer gpuTimer = new GpuTimer();
	private final CpuTimer cpuTimer = new CpuTimer();
	// the running snapshot (::lumina snapshot), or null
	private Snapshot snapshot;
	private long lastFrameEndNanos;
	// worldviews between their pre and post scene callbacks; the scene timers run while it is above 0, so boats
	// drawn inside or after the top level are counted either way
	private int sceneDepth;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static int glProgram;
	private int glUiProgram;
	private int glSkyProgram;
	private int uniSkyInvProjection;
	private int uniSkyGammaCorrection;
	private int uniSkyElapsedTime;
	private int uniSkyColorblindIntensity;

	private int interfaceTexture;
	private int interfacePbo;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");
	// 117 HD's material uniform block (see MaterialManager.fillSlots)
	private final GLBuffer glMaterialBuffer = new GLBuffer("material buffer");
	private final float[] materialData = new float[MaterialManager.MAX_SLOTS * 12];
	private int uniBlockMaterials;

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
		// shading data (tile overrides, terrain normals and colours) of the scene the zones belong to
		SceneData data;

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
	private SceneData nextSceneData;

	// Uniforms
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
	private int uniDebugView;
	private int uniGammaCorrection;
	private OutputUniforms sceneOutputUniforms, skyOutputUniforms;

	// 117 HD's HdPlugin.COLOR_FILTER_FADE_DURATION
	private static final float COLOR_FILTER_FADE_DURATION = 500;

	/**
	 * The uniforms of output.glsl's colour adjustments (117 HD's UBOGlobal saturation, contrast and colour filter).
	 */
	private static final class OutputUniforms
	{
		private final int saturation, contrast, colorFilter, colorFilterPrevious, colorFilterFade;

		OutputUniforms(int program)
		{
			saturation = glGetUniformLocation(program, "saturation");
			contrast = glGetUniformLocation(program, "contrast");
			colorFilter = glGetUniformLocation(program, "colorFilter");
			colorFilterPrevious = glGetUniformLocation(program, "colorFilterPrevious");
			colorFilterFade = glGetUniformLocation(program, "colorFilterFade");
		}

		void upload()
		{
			glUniform1f(saturation, HdSettings.saturation);
			glUniform1f(contrast, HdSettings.contrast);
			if (HdSettings.colorFilter != ColorFilter.NONE)
			{
				glUniform1i(colorFilter, HdSettings.colorFilter.ordinal());
				glUniform1i(colorFilterPrevious, HdSettings.colorFilterPrevious.ordinal());
				long timeSinceChange = System.currentTimeMillis() - HdSettings.colorFilterChangedAt;
				glUniform1f(colorFilterFade, Math.max(0, Math.min(1, timeSinceChange / COLOR_FILTER_FADE_DURATION)));
			}
		}
	}
	private int uniElapsedTime;
	private boolean materialsUploaded;
	// ::lumina debug: 0 off, 1 base colour, 2 light only, 3 normals
	private int debugView;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();

	@Override
	protected void startUp()
	{
		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		HdSettings.update(config);
		DiffuseColors.shadingMode = config.shadingMode();
		DiffuseColors.colorMode = config.colorMode();
		sceneShading.load();
		lightingSource = skyLighting;
		startNanos = System.nanoTime();
		clientUploader = new SceneUploader(renderCallbackManager, modelOverrideManager);
		mapUploader = new SceneUploader(renderCallbackManager, modelOverrideManager);
		clientThread.invoke(() ->
		{
			try
			{
				fboScene = -1;
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

	/**
	 * 117 HD's handling of a changed setting (HdPlugin.onConfigChanged): which keys recompile the shaders, reload
	 * the data files (117 HD reloads environments, model overrides or materials separately; Lumina reloads them all)
	 * or reload the scene. Settings read per frame need nothing.
	 *
	 * @return whether the key is a 117 HD setting
	 */
	private boolean handleHdConfigChange(String key)
	{
		boolean recompilePrograms = false;
		boolean reloadSceneData = false;
		boolean reloadTextures = false;
		boolean reloadScene = false;
		switch (key)
		{
			case "hideUnrelatedAreas":
				reloadScene = true;
				break;
			case "vanillaColorBanding":
				recompilePrograms = true;
				break;
			case "colorFilter":
				if (HdSettings.colorFilter == ColorFilter.NONE || HdSettings.colorFilterPrevious == ColorFilter.NONE)
				{
					recompilePrograms = true;
				}
				if (HdSettings.colorFilter == ColorFilter.CEL_SHADING || HdSettings.colorFilterPrevious == ColorFilter.CEL_SHADING)
				{
					reloadScene = true;
				}
				break;
			case "legacyDisableEnvironmentalLighting":
			case "pohThemeEnvironments":
			case "legacyTobEnvironment":
				reloadSceneData = true;
				break;
			case "seasonalTheme":
			case "seasonalHemisphere":
				reloadSceneData = true;
				reloadTextures = true;
				reloadScene = true;
				break;
			case "seasonalFoliage":
			case "textureResolution":
			case "infernalCape":
				reloadSceneData = true;
				reloadTextures = true;
				reloadScene = true;
				break;
			case "fillGapsInTerrain":
				reloadScene = true;
				break;
			case "hideVanillaWaterEffects":
			case "recolorGroundObjects":
				reloadSceneData = true;
				reloadScene = true;
				break;
			case "vanillaShadowMode":
				reloadScene = true;
				break;
			case "reduceOverExposure":
			case "experimentalPreserveVanillaNormals":
			case "flatShading":
				recompilePrograms = true;
				reloadScene = true;
				break;
			// read every frame (117 HD: updateCachedConfigs only); anisotropic filtering is applied by the frame too
			case "daylightCycle":
			case "moonBehavior":
			case "moonPhase":
			case "overrideSky":
			case "defaultSkyColor":
			case "replaceVanillaSkyboxes":
			case "useLegacyBrightness":
			case "brightness2":
			case "screenBrightness":
			case "fSaturation":
			case "fContrast":
			case "nightBrightness":
			case "experimentalNightAdaptationEverywhere":
			case "fogDepthMode":
			case "fogDepth":
			case "groundFog":
			case "anisotropicFilteringLevel":
				return true;
			default:
				return false;
		}

		final boolean doRecompile = recompilePrograms, doReloadData = reloadSceneData, doReloadTextures = reloadTextures, doReloadScene = reloadScene;
		clientThread.invokeLater(() ->
		{
			if (!lwjglInitted)
			{
				return;
			}
			if (doReloadTextures && textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
				textureArrayId = -1;
				lastAnisotropicFilteringLevel = -1;
			}
			if (doReloadData)
			{
				sceneShading.load();
				if (root.data != null)
				{
					environmentManager.loadSceneEnvironments(root.data);
				}
			}
			if (doRecompile)
			{
				shutdownProgram();
				try
				{
					initProgram();
				}
				catch (RuntimeException ex)
				{
					log.error("Shader recompile failed after a config change; stopping the plugin", ex);
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
			}
			if (doReloadScene && client.getGameState() == GameState.LOGGED_IN)
			{
				client.setGameState(GameState.LOADING);
			}
		});
		return true;
	}

	private void setupGpuFlags()
	{
		int cpus = Runtime.getRuntime().availableProcessors();
		int threads = Math.min(cpus - 1, config.numThreads());
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
			| DrawCallbacks.NORMALS
			// 117 HD requests unlit face colours only for shading modes that use them (DEFAULT uses the lit colours);
			// Lumina's GPU colour mode needs them too
			| (DiffuseColors.requestUnlitFaceColors() ? DrawCallbacks.UNLIT_FACE_COLORS : 0)
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
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			if (lwjglInitted)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();
				root.data = null;

				gpuTimer.free();
				snapshot = null;
				shutdownInterfaceTexture();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownFbo();
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

			glCapabilities = null;
			nextSceneData = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	LuminaConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LuminaConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(LuminaConfig.GROUP))
		{
			HdSettings.update(config);
			if (handleHdConfigChange(configChanged.getKey()))
			{
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
			else if (configChanged.getKey().equals("shadingMode") || configChanged.getKey().equals("colorMode"))
			{
				// 117 HD restarts the plugin when the shading mode changes: new GPU flags (unlit face colours),
				// recompiled shaders (DISABLE_DIRECTIONAL_SHADING) and a reloaded scene
				clientThread.invokeLater(() ->
				{
					DiffuseColors.shadingMode = config.shadingMode();
					DiffuseColors.colorMode = config.colorMode();
					MaterialManager.updateNoneBrightness();
					setupGpuFlags();
					shutdownProgram();
					initProgram();
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
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
				case "shading_config":
					return "#define DISABLE_DIRECTIONAL_SHADING " + (DiffuseColors.shadingMode.directionalShading ? 0 : 1) + "\n"
						+ "#define FLAT_SHADING " + (HdSettings.flatShading ? 1 : 0) + "\n"
						+ "#define VANILLA_COLOR_BANDING " + (HdSettings.vanillaColorBanding ? 1 : 0) + "\n"
						+ "#define APPLY_COLOR_FILTER " + (HdSettings.colorFilter != ColorFilter.NONE ? 1 : 0) + "\n";
			}
			return null;
		});
		template.addInclude(LuminaPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();
		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glSkyProgram = SKY_PROGRAM.compile(template);

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
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");
		uniDebugView = glGetUniformLocation(glProgram, "debugView");
		uniGammaCorrection = glGetUniformLocation(glProgram, "gammaCorrection");
		sceneOutputUniforms = new OutputUniforms(glProgram);
		uniElapsedTime = glGetUniformLocation(glProgram, "elapsedTime");
		uniBlockMaterials = glGetUniformBlockIndex(glProgram, "materials");
		sceneLightingUniforms = new LightingUniforms(glProgram);

		uniSkyInvProjection = glGetUniformLocation(glSkyProgram, "invProjection");
		uniSkyGammaCorrection = glGetUniformLocation(glSkyProgram, "gammaCorrection");
		skyOutputUniforms = new OutputUniforms(glSkyProgram);
		uniSkyElapsedTime = glGetUniformLocation(glSkyProgram, "elapsedTime");
		uniSkyColorblindIntensity = glGetUniformLocation(glSkyProgram, "colorblindIntensity");
		skyLightingUniforms = new LightingUniforms(glSkyProgram);

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
	}

	private void shutdownProgram()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		glDeleteProgram(glSkyProgram);
		glSkyProgram = 0;
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
		initGlBuffer(glMaterialBuffer);
		glBindBuffer(GL_UNIFORM_BUFFER, glMaterialBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, (long) materialData.length * Float.BYTES, GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		materialsUploaded = false;
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
		destroyGlBuffer(glMaterialBuffer);
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

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Color render buffer
		rboColorBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColorBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColorBuffer);

		// Depth render buffer
		rboDepthBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepthBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepthBuffer);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void shutdownFbo()
	{
		if (fboScene != -1)
		{
			glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != 0)
		{
			glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = 0;
		}

		if (rboDepthBuffer != 0)
		{
			glDeleteRenderbuffers(rboDepthBuffer);
			rboDepthBuffer = 0;
		}
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		if (sceneDepth++ == 0)
		{
			cpuTimer.end(CpuTimer.OUTSIDE);
			cpuTimer.end(CpuTimer.CLIENT_UI);
			cpuTimer.begin(CpuTimer.CLIENT_SCENE);
			gpuTimer.begin(GpuTimer.SCENE);
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

		// material table: uploaded when materials were added
		if (!materialsUploaded || MaterialManager.slotsDirty())
		{
			TextureProvider tp = client.getTextureProvider();
			int count = MaterialManager.fillSlots(materialData, tp == null ? null : tp.getTextures());
			glBindBuffer(GL_UNIFORM_BUFFER, glMaterialBuffer.glBufferId);
			glBufferSubData(GL_UNIFORM_BUFFER, 0, java.util.Arrays.copyOf(materialData, count * 12));
			glBindBuffer(GL_UNIFORM_BUFFER, 0);
			materialsUploaded = true;
		}
		glBindBufferBase(GL_UNIFORM_BUFFER, 1, glMaterialBuffer.glBufferId);

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

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
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

		glUseProgram(glProgram);

		// Setup uniforms
		final int drawDistance = getDrawDistance();
		final float elapsedTime = (float) ((System.nanoTime() - startNanos) / 1e9);
		final float gammaCorrection = HdSettings.getGammaCorrection();
		// 117 HD's SceneContext: instances load no extra map chunks
		final int expandedChunks = root.data != null && root.data.scene.isInstance() ? 0 : client.getExpandedMapLoading();

		updateAreaHiding();

		// Scene light, fog and sky: the lighting source decides them, the shaders read them as is
		lightingSource.fill(lighting, root.data, elapsedTime, drawDistance, scene.getSkybox() != null);
		sceneLightingUniforms.upload(lighting);
		glUniform1f(uniDrawDistance, (float) drawDistance);
		glUniform1i(uniExpandedMapLoadingChunks, expandedChunks);
		glUniform1f(uniColorblindIntensity, config.colorBlindIntensity());
		glUniform1f(uniGammaCorrection, gammaCorrection);
		sceneOutputUniforms.upload();
		// 117 HD's dither offset, elapsedTime modulo the largest float with 1/128 precision
		final float ditherTime = elapsedTime % 65536f;
		glUniform1f(uniElapsedTime, ditherTime);
		glUniform1i(uniDebugView, debugView);

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
		Mat4.mul(projectionMatrix, Mat4.translate(-cameraX, -cameraY, -cameraZ));
		glUniformMatrix4fv(uniWorldProj, false, projectionMatrix);
		float[] invProjection = Mat4.inverse(projectionMatrix);

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);

		glUniform4i(uniEntityTint, 0, 0, 0, 0);

		// Bind uniforms
		glUniformBlockBinding(glProgram, uniBlockMain, 0);
		glUniformBlockBinding(glProgram, uniBlockMaterials, 1);
		glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE);

		// Enable depth testing (117 HD: GL_GEQUAL, so later geometry at equal depth wins)
		glDepthFunc(GL_GEQUAL);
		glEnable(GL_DEPTH_TEST);

		drawSky(scene, invProjection, gammaCorrection, ditherTime, cameraX, cameraY, cameraZ);

		checkGLErrors();
	}

	private boolean justChangedArea;

	/**
	 * 117 HD maps vanilla textures to materials once the client's textures are known; uploads need the mapping.
	 */
	private void initVanillaMaterials()
	{
		TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null && textureProvider.getTextures() != null)
		{
			MaterialManager.initVanillaTextures(textureProvider.getTextures());
		}
	}

	/**
	 * 117 HD's updateAreaHiding: the current area is the first possible area containing the local player; when it
	 * changes, every zone is rebuilt.
	 */
	private void updateAreaHiding()
	{
		SceneData data = root.data;
		Player localPlayer = client.getLocalPlayer();
		if (data == null || localPlayer == null || !data.enableAreaHiding || data.sceneBase == null)
		{
			justChangedArea = false;
			return;
		}

		var lp = localPlayer.getLocalLocation();
		int[] worldPos = {
			data.sceneBase[0] + lp.getSceneX(),
			data.sceneBase[1] + lp.getSceneY(),
			data.sceneBase[2] + client.getTopLevelWorldView().getPlane()
		};

		// Check all areas contained in the scene in the order they appear in the list, so lower floors take
		// precedence over higher floors which include tiny portions of the floor beneath around stairs and ladders
		Area newArea = null;
		for (var area : data.possibleAreas)
		{
			if (area.containsPoint(false, worldPos))
			{
				newArea = area;
				break;
			}
		}

		if (newArea != data.currentArea)
		{
			if (justChangedArea)
			{
				// Disable area hiding if it somehow gets stuck in a loop switching areas
				data.enableAreaHiding = false;
				log.error("Disabling area hiding after moving from {} to {} at {}", data.currentArea, newArea, worldPos);
				newArea = null;
			}
			else
			{
				justChangedArea = true;
				for (Zone[] column : root.zones)
				{
					for (Zone zone : column)
					{
						zone.invalidate = true;
					}
				}
			}
			data.currentArea = newArea;
		}
		else
		{
			justChangedArea = false;
		}
	}

	/**
	 * 117 HD's zoneInFrustum area check: zones outside the current area are not drawn.
	 */
	private boolean isZoneHidden(SceneContext ctx, Scene scene, int zx, int zz)
	{
		if (ctx != root || ctx.data == null)
		{
			return false;
		}
		Area currentArea = ctx.data.currentArea;
		if (currentArea == null || ctx.data.sceneBase == null)
		{
			return false;
		}
		int[] base = ctx.data.sceneBase;
		int minX = zx * 8 - ctx.data.sceneOffset;
		int minZ = zz * 8 - ctx.data.sceneOffset;
		return !currentArea.intersects(true, base[0] + minX, base[1] + minZ, base[0] + minX + 7, base[1] + minZ + 7);
	}

	/**
	 * 117 HD's area check for dynamic and temporary models of the top-level scene.
	 */
	private boolean isOutsideArea(SceneContext ctx, int x, int z, int plane)
	{
		if (ctx != root || ctx.data == null)
		{
			return false;
		}
		Area currentArea = ctx.data.currentArea;
		if (currentArea == null || ctx.data.sceneBase == null)
		{
			return false;
		}
		int[] base = ctx.data.sceneBase;
		return !currentArea.containsPoint(base[0] + (x >> Perspective.LOCAL_COORD_BITS), base[1] + (z >> Perspective.LOCAL_COORD_BITS), base[2] + plane);
	}

	/**
	 * Clears the scene and draws its background: 117 HD's sky (SkyRenderer.clear and the sky pass) unless a vanilla
	 * skybox is shown, which 117 HD draws over black.
	 */
	private void drawSky(Scene scene, float[] invProjection, float gammaCorrection, float ditherTime, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (!lighting.vanillaSkybox || skybox == null)
		{
			glClearColor(0f, 0f, 0f, 1f);
			glClearDepth(0d);
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

			// 117 HD draws the sky behind the scene (at depth 0, after it); drawn first here without depth
			glUseProgram(glSkyProgram);
			skyLightingUniforms.upload(lighting);
			glUniformMatrix4fv(uniSkyInvProjection, false, invProjection);
			glUniform1f(uniSkyGammaCorrection, gammaCorrection);
			skyOutputUniforms.upload();
			glUniform1f(uniSkyElapsedTime, ditherTime);
			glUniform1f(uniSkyColorblindIntensity, config.colorBlindIntensity());
			glDisable(GL_BLEND);
			glDisable(GL_DEPTH_TEST);
			glDepthMask(false);
			glBindVertexArray(vaoUiHandle);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glDepthMask(true);
			glEnable(GL_DEPTH_TEST);
			glEnable(GL_BLEND);
			glUseProgram(glProgram);
			return;
		}

		glClearColor(0f, 0f, 0f, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, null, 0, 0, 0, 0, 0,
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

		if (sceneDepth > 0 && --sceneDepth == 0)
		{
			gpuTimer.end();
			cpuTimer.end(CpuTimer.CLIENT_SCENE);
			cpuTimer.begin(CpuTimer.CLIENT_UI);
		}
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	private void blitSceneFbo()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		int defaultFbo = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

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
		if (!z.initialized || isZoneHidden(ctx, scene, zx, zz))
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		// 117 HD draws opaque zones without blending
		glDisable(GL_BLEND);
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
		if (ctx.minLevel == 0)
		{
			z.renderGapFillers(zx - offset, zz - offset);
		}
		glEnable(GL_BLEND);

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
		if (!z.initialized || isZoneHidden(ctx, scene, zx, zz))
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
		boolean unsorted = !close || (scene.getOverrideAmount() > 0);
		// 117 HD: colour without depth writes, then (top level only) depth without colour, so opaque models drawn
		// later on higher planes test against alpha
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, unsorted, false);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			glColorMask(false, false, false, false);
			z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, unsorted, true);
			glColorMask(true, true, true, true);
		}

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

		if (!renderCallbackManager.drawObject(scene, tileObject) || isOutsideArea(ctx, x, z, tileObject.getPlane()))
		{
			return;
		}

		int objectId = tileObject.getId();
		if (r instanceof DynamicObject && ((DynamicObject) r).getRecordedObjectComposition() != null)
		{
			objectId = ((DynamicObject) r).getRecordedObjectComposition().getId();
		}
		ModelOverride override = dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, objectId, x, z, tileObject.getPlane());
		if (override.hide)
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		// faces can also become alpha through vanilla texture materials and override materials (117 HD's
		// mightHaveTransparency)
		if (m.getFaceTransparencies() == null && m.getFaceTextures() == null && !override.mightHaveTransparency)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, override, ObjectOrientation.getModelPreOrientation(ObjectOrientation.getObjectConfig(tileObject)), orient, x, y, z,
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
				sorter.uploadSortedModel(rt, worldProjection, m, override, ObjectOrientation.getModelPreOrientation(ObjectOrientation.getObjectConfig(tileObject)), orient, x, y, z,
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

		if (!renderCallbackManager.drawObject(scene, gameObject) || isOutsideArea(ctx, x, z, gameObject.getPlane()))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		ModelOverride override = tempModelOverride(ctx, gameObject, renderable, x, z);
		if (override.hide)
		{
			return;
		}

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0
			|| m.getFaceTextures() != null || override.mightHaveTransparency)
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, override, ObjectOrientation.getModelPreOrientation(gameObject.getConfig()), orient, x, y, z,
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
			uploader.uploadTempModel(m, override, ObjectOrientation.getModelPreOrientation(gameObject.getConfig()), orient, x, y, z,
				ctx.cameraX, ctx.cameraY, ctx.cameraZ, o.vbo.vb);
			o.addRange(ctx.projection, scene, 0);
		}
	}

	/**
	 * The model override of a temporary model (players, NPCs, projectiles, graphics objects and spawned locs).
	 */
	private ModelOverride tempModelOverride(SceneContext ctx, GameObject gameObject, Renderable renderable, int x, int z)
	{
		int plane = gameObject.getPlane();
		if (renderable instanceof NPC)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_NPC, ((NPC) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof GraphicsObject)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_GRAPHICS_OBJECT, ((GraphicsObject) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof Projectile)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_PROJECTILE, ((Projectile) renderable).getId(), x, z, plane);
		}
		if (renderable instanceof DynamicObject)
		{
			var composition = ((DynamicObject) renderable).getRecordedObjectComposition();
			int id = composition != null ? composition.getId() : gameObject.getId();
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, id, x, z, plane);
		}
		if (renderable instanceof Model)
		{
			return dynamicModelOverride(ctx, ModelOverrideManager.TYPE_OBJECT, gameObject.getId(), x, z, plane);
		}
		return ModelOverride.NONE;
	}

	/**
	 * Looks up a model override, resolving area-restricted overrides from the model's local position.
	 */
	private ModelOverride dynamicModelOverride(SceneContext ctx, int type, int id, int x, int z, int plane)
	{
		ModelOverride override = modelOverrideManager.getOverride(type, id);
		if (override.areaOverrides != null && ctx.data != null)
		{
			override = ModelOverrideManager.resolveArea(override, ctx.data.localToWorld(x, z, plane));
		}
		return override;
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
				initVanillaMaterials();
				if (ctx.data == null || ctx.data.scene != scene)
				{
					ctx.data = sceneShading.createSceneData(wv, scene, client.getExpandedMapLoading());
					if (ctx == root)
					{
						ctx.data.initAreaHiding();
					}
				}
				clientUploader.zoneSize(scene, ctx.data, zone, x, z);

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

				clientUploader.uploadZone(scene, ctx.data, zone, x, z);

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
		// after the CPU copy above, so the query holds only the GPU upload
		gpuTimer.begin(GpuTimer.UI_UPLOAD);
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
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		cpuTimer.end(CpuTimer.CLIENT_UI);
		cpuTimer.begin(CpuTimer.DRAW);
		prepareInterfaceTexture(canvasWidth, canvasHeight);

		gpuTimer.begin(GpuTimer.BLIT);
		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid)
		{
			blitSceneFbo();
		}

		// Texture on UI
		gpuTimer.begin(GpuTimer.UI);
		drawUi(overlayColor, canvasHeight, canvasWidth);
		gpuTimer.end();
		cpuTimer.end(CpuTimer.DRAW);

		try
		{
			cpuTimer.begin(CpuTimer.SWAP);
			awtContext.swapBuffers();
			cpuTimer.end(CpuTimer.SWAP);
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

		drawManager.processDrawComplete(this::screenshot);

		endFrameTiming();

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
			nextSceneData = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		// shading data of the new scene, before its zones are sized and uploaded. 117 HD hides unrelated areas at
		// upload and draw time instead of removing tiles from the scene, so all tiles take part in terrain normals
		Stopwatch sceneDataTime = Stopwatch.createStarted();
		initVanillaMaterials();
		SceneData sceneData = sceneShading.createSceneData(worldView, scene, client.getExpandedMapLoading());
		sceneData.initAreaHiding();
		log.debug("Scene data time {}", sceneDataTime);

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
			// 117 HD reuses zones only within the same hidden area
			&& (root.data == null || root.data.currentArea == sceneData.currentArea))
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
					mapUploader.zoneSize(scene, sceneData, zone, x, z);
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
					mapUploader.uploadZone(scene, sceneData, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
		nextSceneData = sceneData;
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
		initVanillaMaterials();
		ctx.data = sceneShading.createSceneData(worldView, scene, 0);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, ctx.data, zone, x, z);
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

				mapUploader.uploadZone(scene, ctx.data, zone, x, z);
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
		ctx.data = nextSceneData;
		nextSceneData = null;

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
		if (!log.isDebugEnabled())
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

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	private void endFrameTiming()
	{
		long now = System.nanoTime();
		double frameMs = lastFrameEndNanos == 0 ? 0 : (now - lastFrameEndNanos) / 1e6;
		lastFrameEndNanos = now;

		long frame = gpuTimer.getFrame();
		sceneDepth = 0;
		cpuTimer.endFrame();
		gpuTimer.endFrame();
		if (snapshot != null)
		{
			int players = 0;
			for (var ignored : client.getTopLevelWorldView().players())
			{
				players++;
			}
			// the first frame after start has no previous frame to measure from
			if (frameMs > 0)
			{
				snapshot.addFrame(frame, frameMs, cpuTimer, players);
			}
			if (gpuTimer.getReadFrame() >= 0)
			{
				snapshot.addGpu(gpuTimer.getReadFrame(), gpuTimer);
			}
			if (snapshot.isDone())
			{
				snapshot.write();
				snapshot = null;
				gpuTimer.setEnabled(false);
			}
		}
		cpuTimer.begin(CpuTimer.OUTSIDE);
	}

	/** The conditions a snapshot is measured under, for its file. */
	private List<String> snapshotHeader(int seconds)
	{
		var player = client.getLocalPlayer();
		List<String> h = new ArrayList<>();
		h.add("plugin: Lumina");
		h.add("device: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
		h.add("os: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
		h.add("seconds: " + seconds);
		h.add("canvas: " + client.getCanvasWidth() + "x" + client.getCanvasHeight()
			+ ", stretched " + lastStretchedCanvasWidth + "x" + lastStretchedCanvasHeight);
		h.add("anti_aliasing: " + config.antiAliasingMode());
		h.add("draw_distance: " + getDrawDistance() + ", expanded_map_loading: " + config.expandedMapLoadingZones());
		h.add("unlock_fps: " + config.unlockFps() + ", fps_target: " + config.fpsTarget() + ", sync: " + config.syncMode());
		h.add("camera: yaw " + client.getCameraYaw() + ", pitch " + client.getCameraPitch() + ", scale " + client.getScale());
		h.add("location: " + (player == null ? "none" : player.getWorldLocation()));
		h.add("gpu_scene note: includes GPU idle while the client traverses the scene");
		return h;
	}

	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (developerMode)
		{
			commandFile.poll();
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("lumina"))
		{
			runLuminaCommand(event.getArguments());
		}
		else if (event.getCommand().equals("gpumem"))
		{
			runGpuMemCommand();
		}
	}

	private void runLuminaCommand(String[] args)
	{
		// developer tools only
		if (!developerMode)
		{
			return;
		}
		{
			if (args.length >= 4 && args[0].equals("cam"))
			{
				try
				{
					FrameCapture.setCamera(client, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
				}
				catch (NumberFormatException ex)
				{
					log.warn("::lumina cam yaw pitch zoom: numbers expected");
				}
				return;
			}
			if (args.length >= 1 && args[0].equals("capture"))
			{
				FrameCapture.capture(client, drawManager, "Lumina", args.length >= 2 ? args[1] : "capture");
				return;
			}
			if (args.length >= 1 && args[0].equals("dump"))
			{
				// every lighting value the shaders get, for comparing against 117 HD
				StringBuilder sb = new StringBuilder("Lighting dump: target=")
					.append(environmentManager.getTargetEnvironment())
					.append(" to=").append(environmentManager.getToEnvironment())
					.append(" candidates=").append(root.data == null ? "none" : root.data.environments)
					.append(" focal=").append(root.data == null ? "none" : java.util.Arrays.toString(root.data.localToWorld(
						(int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ(), client.getTopLevelWorldView().getPlane())))
					.append(" sceneBase=").append(root.data == null ? "none" : java.util.Arrays.toString(root.data.sceneBase))
					.append(System.lineSeparator()).append("  ").append(skyLighting.describe());
				for (var f : Lighting.class.getFields())
				{
					try
					{
						Object v = f.get(lighting);
						sb.append(System.lineSeparator()).append("  ").append(f.getName()).append(" = ")
							.append(v instanceof float[] ? java.util.Arrays.toString((float[]) v) : v);
					}
					catch (IllegalAccessException ignored)
					{
					}
				}
				log.info(sb.toString());
				return;
			}
			if (args.length >= 2 && args[0].equals("debug"))
			{
				switch (args[1])
				{
					case "albedo":
						debugView = 1;
						break;
					case "light":
						debugView = 2;
						break;
					case "normal":
						debugView = 3;
						break;
					default:
						debugView = 0;
				}
				return;
			}
			if (args.length >= 1 && args[0].equals("snapshot") && snapshot == null)
			{
				int seconds = 20;
				if (args.length >= 2)
				{
					try
					{
						seconds = Math.max(1, Math.min(120, Integer.parseInt(args[1])));
					}
					catch (NumberFormatException ignored)
					{
					}
				}
				snapshot = new Snapshot(snapshotHeader(seconds), seconds);
				gpuTimer.setEnabled(true);
				log.info("Snapshot started: {} s", seconds);
			}
		}
	}

	private void runGpuMemCommand()
	{
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
