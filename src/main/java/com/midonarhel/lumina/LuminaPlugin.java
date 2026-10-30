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

import com.google.inject.Provides;
import com.midonarhel.lumina.config.ColorFilter;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.LuminaConfig;
import com.midonarhel.lumina.config.SkySettings;
import com.midonarhel.lumina.dev.LuminaCommands;
import com.midonarhel.lumina.frame.FrameRenderer;
import com.midonarhel.lumina.frame.RenderThreads;
import com.midonarhel.lumina.frame.TextureManager;
import com.midonarhel.lumina.frame.ZoneDrawer;
import com.midonarhel.lumina.lighting.SkyLighting;
import com.midonarhel.lumina.scene.AreaHiding;
import com.midonarhel.lumina.scene.SceneDataLoader;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.shading.ColorMode;
import com.midonarhel.lumina.shading.DiffuseColors;
import com.midonarhel.lumina.sky.SkySource;
import java.awt.Canvas;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
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

/**
 * The plugin: creates the GL context on the client thread, wires the frame renderer, the scene loader and the
 * developer commands together, applies setting changes, and hands the client's {@link DrawCallbacks} on to them.
 */
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
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private SceneDataLoader sceneDataLoader;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private SkyLighting skyLighting;

	@Inject
	private SkySource skySource;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	private RenderThreads renderThreads;
	private SceneLoader scenes;
	private AreaHiding areaHiding;
	private ZoneDrawer zoneDrawer;
	private FrameRenderer frame;
	private LuminaCommands commands;

	@Override
	protected void startUp()
	{
		renderThreads = new RenderThreads(config.numThreads());
		HdSettings.update(config);
		SkySettings.update(config);
		DiffuseColors.shadingMode = config.shadingMode();
		// the Vanilla colour mode was called GPU before
		if ("GPU".equals(configManager.getConfiguration(LuminaConfig.GROUP, "colorMode")))
		{
			configManager.setConfiguration(LuminaConfig.GROUP, "colorMode", ColorMode.VANILLA);
		}
		DiffuseColors.colorMode = config.colorMode();
		sceneDataLoader.load();
		scenes = new SceneLoader(client, clientThread, sceneDataLoader, renderCallbackManager, modelOverrideManager);
		areaHiding = new AreaHiding(client, scenes);
		zoneDrawer = new ZoneDrawer(renderCallbackManager, modelOverrideManager, renderThreads, scenes, areaHiding);
		// the scene light: 117 HD's environment, sky and daylight-cycle lighting, or Lumina's sky (the setting picks)
		frame = new FrameRenderer(client, clientUI, config, textureManager, drawManager, this::stopPlugin, skyLighting,
			skySource, scenes, areaHiding, zoneDrawer, renderThreads);
		commands = new LuminaCommands(client, drawManager, config, configManager, environmentManager, skyLighting, skySource, frame, scenes);
		clientThread.invoke(() ->
		{
			try
			{
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

				FrameRenderer.checkGLErrors();
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

				frame.init(awtContext, canvas);
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				setupGpuFlags();
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					scenes.loadCurrentWorld();
				}

				FrameRenderer.checkGLErrors();
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
	 * Stops the plugin from the Swing thread, after a fatal error on the client thread.
	 */
	private void stopPlugin()
	{
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
				reloadSceneData = true;
				reloadScene = true;
				break;
			// Ground-matched foliage is read at upload time (DiffuseColors.inheritTileColors)
			case "recolorGroundObjects":
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
			if (doReloadTextures)
			{
				frame.freeTextureArray();
			}
			if (doReloadData)
			{
				sceneDataLoader.load();
				if (scenes.root().data != null)
				{
					environmentManager.loadSceneEnvironments(scenes.root().data);
				}
			}
			if (doRecompile)
			{
				frame.deletePrograms();
				try
				{
					frame.compilePrograms();
				}
				catch (RuntimeException ex)
				{
					log.error("Shader recompile failed after a config change; stopping the plugin", ex);
					stopPlugin();
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
				frame.freeTextureArray();

				scenes.free();

				frame.free();
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
			// the Sky section's settings are read per frame; nothing else to do for them
			SkySettings.update(config);
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
					if (!lwjglInitted)
					{
						return;
					}
					DiffuseColors.shadingMode = config.shadingMode();
					DiffuseColors.colorMode = config.colorMode();
					MaterialManager.updateNoneBrightness();
					// the other colour mode's materials replace this one's in the material table
					MaterialManager.resetSlots();
					setupGpuFlags();
					frame.deletePrograms();
					frame.compilePrograms();
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
					frame.deletePrograms();
					frame.compilePrograms();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
					renderThreads.reallocate(config.numThreads());
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
		FrameRenderer.checkGLErrors();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			frame.invalidateSceneFbo();
		}
		if (state == GameState.STARTING)
		{
			frame.resetTextureArray();
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		scenes.rebuildInvalidated();
	}

	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (developerMode)
		{
			commands.poll();
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("lumina"))
		{
			// developer tools only
			if (developerMode)
			{
				commands.run(event.getArguments());
			}
		}
		else if (event.getCommand().equals("gpumem"))
		{
			renderThreads.logMemory();
		}
	}

	// DrawCallbacks: the frame, the zones and models, and the scene loads

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		frame.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		frame.postSceneDraw(scene);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		zoneDrawer.drawZoneOpaque(scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		zoneDrawer.drawZoneAlpha(scene, level, zx, zz);
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		zoneDrawer.drawPass(scene, pass);
		if (pass == PASS_OPAQUE && scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			// Lumina's sky, behind the opaque geometry and under the alpha geometry (as 117 HD places its sky)
			frame.drawSkyAfterOpaque(scene);
		}
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		zoneDrawer.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		zoneDrawer.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		scenes.invalidateZone(scene, zx, zz);
	}

	@Override
	public void draw(int overlayColor)
	{
		frame.draw(overlayColor);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		scenes.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		scenes.despawnWorldView(worldView);
	}

	@Override
	public void swapScene(Scene scene)
	{
		scenes.swapScene(scene);
	}
}
