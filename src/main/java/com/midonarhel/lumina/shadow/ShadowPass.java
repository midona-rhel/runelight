package com.midonarhel.lumina.shadow;

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
import com.midonarhel.lumina.frame.InterfaceUpload;
import com.midonarhel.lumina.frame.FramePacer;
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
import com.midonarhel.lumina.LuminaPlugin.SceneContext;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The shadow pass: the scene drawn from the sun or moon into the shadow map each frame, the static shadow cache
 * drawn by the scheduler over frames (and held for a scene being loaded, or for a strike's bolt), and the state the
 * shadow program needs. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class ShadowPass
{
	private final LuminaPlugin plugin;

	public ShadowPass(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/**
	 * Draw the opaque scene near the player from the sun or moon. Runs after the main scene so it can reuse the
	 * dynamic models already uploaded this frame; the composite pass then shadows the visible pixels.
	 */
	public void render()
	{
		SceneContext ctx = plugin.root;
		if (ctx == null || ctx.hideRoofIds == null || plugin.glShadowProgram == 0)
		{
			return;
		}

		int size = plugin.config.shadowResolution().getSize();
		if (!plugin.shadowMap.isInitialized() || plugin.shadowMap.getSize() != size)
		{
			plugin.shadowMap.free();
			plugin.shadowMap.init(size);
		}

		float fx = plugin.frameCameraX, fy = plugin.frameCameraY, fz = plugin.frameCameraZ;
		Player player = plugin.client.getLocalPlayer();
		if (player != null)
		{
			LocalPoint lp = player.getLocalLocation();
			if (lp != null)
			{
				fx = lp.getX();
				fz = lp.getY();
				fy = Perspective.getTileHeight(plugin.client, lp, plugin.client.getTopLevelWorldView().getPlane());
			}
		}

		float radius = plugin.config.shadowDistance() * Perspective.LOCAL_TILE_SIZE;
		float depthRange = radius + 4096;
		final boolean cache = SHADOW_CACHE;
		if (!cache)
		{
			plugin.shadowMap.freeCache();
			plugin.shadowMap.update(plugin.dayCycle.casterDir, fx, fy, fz, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ,
				radius, depthRange, plugin.glCapabilities.OpenGL45);
			glBindFramebuffer(GL_FRAMEBUFFER, plugin.shadowMap.getFbo());
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

		glUseProgram(plugin.glShadowProgram);
		if (plugin.swayTick)
		{
			plugin.weather.getWind().apply(plugin.shadowWind, plugin.weather.isWindStill());
		}
		glUniform1i(plugin.uniShadowTexturesReady, plugin.textureArrayId != -1 ? 1 : 0);
		glUniform1i(plugin.uniShadowEmissiveCasts, plugin.config.emissiveStrength() > 0 ? 0 : 1);
		glUniformMatrix4fv(plugin.uniShadowEntityProj, false, IDENTITY);
		// swaying plants are left out of the static cache and redrawn each frame (Wind.java)
		final boolean swayShadows = plugin.windSwayAmount() > 0 && plugin.config.windSwayShadows();
		plugin.shadowSway.set(swayShadows ? plugin.windSwayAmount() : 0);

		// Zone and VAO draw through these shared uniform locations; point them at the shadow program
		final int savedBase = uniBase, savedEntityProj = uniEntityProj, savedEntityTint = uniEntityTint;
		uniBase = plugin.uniShadowBase;
		uniEntityProj = plugin.uniShadowEntityProj;
		uniEntityTint = -1;
		try
		{
			if (cache)
			{
				renderStaticShadows(ctx, size, fx, fy, fz, radius, depthRange, swayShadows);
				if (plugin.shadowMap.frontView() != null)
				{
					// this frame's map starts as a copy of the static one
					glBindVertexArray(plugin.vaoUiHandle);
					plugin.shadowMap.useFront(plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ, plugin.glShadowCopyProgram);
					glUseProgram(plugin.glShadowProgram);
				}
				else
				{
					// no static map drawn yet (start-up, a setting changed; the scheduler draws it): this frame's map
					// holds the moving models only
					plugin.shadowMap.update(plugin.dayCycle.casterDir, fx, fy, fz, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ,
						radius, depthRange, plugin.glCapabilities.OpenGL45);
					glBindFramebuffer(GL_FRAMEBUFFER, plugin.shadowMap.getFbo());
					glViewport(0, 0, size, size);
					glClearDepth(1.0);
					glClear(GL_DEPTH_BUFFER_BIT);
				}
				glUniformMatrix4fv(plugin.uniShadowLightProj, false, plugin.shadowMap.lightProj);
				if (swayShadows)
				{
					shadowZones(ctx, fx, fz, radius, null, 0, Integer.MAX_VALUE, Zone.SWAY_ONLY);
				}
			}
			else
			{
				glUniformMatrix4fv(plugin.uniShadowLightProj, false, plugin.shadowMap.lightProj);
				shadowZones(ctx, fx, fz, radius, null, 0, Integer.MAX_VALUE, Zone.SWAY_ALL);
			}

			// players, NPCs and other models drawn this frame
			glUniform3i(plugin.uniShadowBase, 0, 0, 0);
			glUniformMatrix4fv(plugin.uniShadowEntityProj, false, IDENTITY);
			for (int i = 0; i < VAO.shadowDrawCount; ++i)
			{
				VAO.ShadowDraw d = VAO.shadowDraws.get(i);
				glUniformMatrix4fv(plugin.uniShadowEntityProj, false, d.projection);
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
		plugin.checkGLErrors();
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

	private int[] shadowBuildZones = new int[64];
	private int shadowBuildCount;
	// the static map being drawn by the scheduler, null for none (one at a time)
	private StaticShadowBuild staticBuild;

	/**
	 * A static shadow map drawn by the scheduler (SceneJobs) into the texture not in use, a share of its zones a frame:
	 * at most a SHADOW_BUILD_FRAMES-th of them (the GPU's share) and as many as fit the frame's budget (the CPU's).
	 * Drawn for the scene in use it replaces the front map when done (or is held beside it, for a strike's bolt); drawn
	 * for a scene being loaded (SceneLoader) it is held until that scene is swapped in, and replaces the front then, or
	 * when it is done if the swap came first: until then the old map stands in.
	 */
	public final class StaticShadowBuild implements SceneJobs.Job
	{
		private final SceneContext ctx;
		private final float[] dir;
		private float fx, fy, fz;
		// a load's scene: the focus is worked out from it on the client thread when the build starts
		private final Scene scene;
		// a bolt's map, held beside the front one; a load's map, for the scene not swapped in yet
		private final boolean bolt;
		private final boolean forLoad;
		private volatile boolean swappedIn;
		private boolean started, held;
		private int build, next, perFrame, sway;
		private float radius;

		public StaticShadowBuild(SceneContext ctx, float[] dir, float fx, float fy, float fz, boolean bolt, boolean forLoad)
		{
			this.ctx = ctx;
			this.dir = dir;
			this.fx = fx;
			this.fy = fy;
			this.fz = fz;
			this.scene = null;
			this.bolt = bolt;
			this.forLoad = forLoad;
		}

		public StaticShadowBuild(SceneContext ctx, float[] dir, Scene scene, boolean bolt, boolean forLoad)
		{
			this.ctx = ctx;
			this.dir = dir;
			this.scene = scene;
			this.bolt = bolt;
			this.forLoad = forLoad;
		}

		/**
		 * A load's focus: where the player stands in the loaded scene when a region load keeps them inside it, else the
		 * scene's centre (a teleport lands near it). Client thread.
		 */
		private void focusOnLoadedScene()
		{
			int tx = Constants.SCENE_SIZE / 2, tz = Constants.SCENE_SIZE / 2;
			Player player = plugin.client.getLocalPlayer();
			if (player != null)
			{
				int px = player.getWorldLocation().getX() - scene.getBaseX(), pz = player.getWorldLocation().getY() - scene.getBaseY();
				if (px >= 0 && pz >= 0 && px < Constants.SCENE_SIZE && pz < Constants.SCENE_SIZE)
				{
					tx = px;
					tz = pz;
				}
			}
			fx = tx * Perspective.LOCAL_TILE_SIZE + 64f;
			fz = tz * Perspective.LOCAL_TILE_SIZE + 64f;
			fy = scene.getTileHeights()[0][tx + SCENE_OFFSET][tz + SCENE_OFFSET];
		}

		@Override
		public boolean step(long deadline)
		{
			if (plugin.glShadowProgram == 0 || !plugin.shadowMap.isInitialized() || plugin.shadowMap.getSize() != plugin.config.shadowResolution().getSize())
			{
				return finish(false);
			}
			int size = plugin.shadowMap.getSize();
			if (!started)
			{
				if (!forLoad && staticBuild != this)
				{
					// queued for the drawn scene, then a load's build took over: starting now would draw over the map the
					// load holds for its swap (startBuild drops the held one; f5a20af3, 21:06: the swap then found none)
					return true;
				}
				started = true;
				// one static build at a time: a load's takes over from one of the drawn scene's
				staticBuild = this;
				if (scene != null)
				{
					focusOnLoadedScene();
				}
				float[] d = dir != null ? dir : plugin.dayCycle.casterDir;
				radius = plugin.config.shadowDistance() * Perspective.LOCAL_TILE_SIZE;
				sway = plugin.windSwayAmount() > 0 && plugin.config.windSwayShadows() ? Zone.SWAY_EXCLUDE : Zone.SWAY_ALL;
				float sx = Math.round(fx / SHADOW_CACHE_SNAP) * (float) SHADOW_CACHE_SNAP;
				float sz = Math.round(fz / SHADOW_CACHE_SNAP) * (float) SHADOW_CACHE_SNAP;
				plugin.hitchMonitor.job(HitchMonitor.STATIC_SHADOWS);
				plugin.shadowMap.startBuild(d, sx, fy, sz, radius + SHADOW_CACHE_SNAP, radius + 4096 + SHADOW_CACHE_SNAP,
					plugin.glCapabilities.OpenGL45);
				build = plugin.shadowMap.builds;
				shadowBuildCount = 0;
				shadowZones(ctx, sx, sz, radius + SHADOW_CACHE_SNAP, null, 0, 0, sway);
				zones = Arrays.copyOf(shadowBuildZones, shadowBuildCount);
				// a load's map is drawn behind the client's loading box, before its swap, which may come a frame or two
				// after the load (a115b4c4: three frames late, the old place's map stood in and the new one landed in one
				// frame, 33 % of pixels): all of it in its first frame, as far as the frame's budget goes
				perFrame = forLoad ? zones.length : Math.max(1, (zones.length + SHADOW_BUILD_FRAMES - 1) / SHADOW_BUILD_FRAMES);
			}
			if (plugin.shadowMap.builds != build || !plugin.shadowMap.isBuilding())
			{
				// the map was forgotten (settings, levels) or another build took its place
				return finish(false);
			}
			glGetIntegerv(GL_VIEWPORT, viewport);
			beginShadowPass();
			try
			{
				ShadowMap.View v = plugin.shadowMap.buildingView();
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.shadowMap.getBuildFbo());
				glViewport(0, 0, size, size);
				glUniformMatrix4fv(plugin.uniShadowLightProj, false, v.proj);
				int end = Math.min(next + perFrame, zones.length);
				while (next < end)
				{
					shadowZones(ctx, v.focusX, v.focusZ, v.radius, zones, next, next + 1, sway);
					next++;
					if (System.nanoTime() >= deadline)
					{
						break;
					}
				}
				if (next < zones.length)
				{
					return false;
				}
				glBindVertexArray(plugin.vaoUiHandle);
				if (bolt || forLoad && !swappedIn)
				{
					plugin.shadowMap.holdBuild(plugin.glShadowMaxProgram);
					held = true;
				}
				else
				{
					plugin.shadowMap.finishBuild(plugin.glShadowMaxProgram);
				}
			}
			finally
			{
				endShadowPass();
				glBindFramebuffer(GL_FRAMEBUFFER, plugin.awtContext.getFramebuffer(false));
				glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			}
			return finish(true);
		}

		private int[] zones;
		private final int[] viewport = new int[4];

		private boolean finish(boolean drawn)
		{
			if (!drawn && started && plugin.shadowMap.builds == build && plugin.shadowMap.isBuilding())
			{
				plugin.shadowMap.invalidateCache();
			}
			if (staticBuild == this && (!held || !forLoad))
			{
				staticBuild = null;
			}
			return true;
		}

		/**
		 * The scene this was drawn for is swapped in (client thread): the held map becomes the front one. Not drawn yet
		 * (the swap came first), what is left of it is drawn now, so the scene never shows with the old place's map.
		 */
		public void swappedIn()
		{
			swappedIn = true;
			boolean drawing = !held && staticBuild == this || !started;
			if (drawing)
			{
				// done drawing, it takes the front itself (swappedIn is set); the queue then drops it
				while (!step(Long.MAX_VALUE) && plugin.shadowMap.isBuilding())
				{
				}
			}
			else if (!held)
			{
				// it was given up (a setting or the levels changed while it was drawn): a new one is drawn as usual
				plugin.shadowMap.refreshCache();
			}
			if (held)
			{
				plugin.shadowMap.swapFront();
				// the old scene's map is in other coordinates: never swapped back
				plugin.shadowMap.dropHeld();
				held = false;
				if (staticBuild == this)
				{
					staticBuild = null;
				}
			}
		}
	}

	/**
	 * Draw the static shadow map of a scene being loaded (SceneLoader, the map loader's thread, its zones uploaded):
	 * around where the player will stand, the scene's centre after a teleport. Null when shadows are off.
	 */
	public StaticShadowBuild prebuild(Scene scene, Zone[][] zones)
	{
		if (!plugin.config.shadows() || plugin.glShadowProgram == 0)
		{
			return null;
		}
		StaticShadowBuild b = new StaticShadowBuild(new SceneContext(zones, plugin.root), null, scene, false, true);
		plugin.sceneLoader.jobs.add("static shadows (load)", SceneJobs.LOAD, b);
		return b;
	}

	/** A load was dropped before its swap (SceneLoader, client thread): its map is not to be used. */
	public void drop(StaticShadowBuild dropped)
	{
		if (dropped != null && staticBuild == dropped)
		{
			staticBuild = null;
			if (dropped.held)
			{
				plugin.shadowMap.dropHeld();
			}
		}
	}

	/** The swap, client thread: the loaded scene's static map replaces the front one if drawn (else when it is). */
	public void swapped(StaticShadowBuild loaded)
	{
		if (loaded != null)
		{
			loaded.swappedIn();
		}
		else
		{
			plugin.shadowMap.refreshCache();
		}
	}

	/** The shadow program's state for drawing zones from the light; endShadowPass puts the frame's back. */
	private void beginShadowPass()
	{
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		// both faces cast: OSRS models are not reliably closed, and backfaces reduce light leaking
		glDisable(GL_CULL_FACE);
		glEnable(GL_POLYGON_OFFSET_FILL);
		glPolygonOffset(1.0f, 1.0f);

		glUseProgram(plugin.glShadowProgram);
		glUniform1i(plugin.uniShadowTexturesReady, plugin.textureArrayId != -1 ? 1 : 0);
		glUniform1i(plugin.uniShadowEmissiveCasts, plugin.config.emissiveStrength() > 0 ? 0 : 1);
		glUniformMatrix4fv(plugin.uniShadowEntityProj, false, IDENTITY);
		final boolean swayShadows = plugin.windSwayAmount() > 0 && plugin.config.windSwayShadows();
		plugin.shadowSway.set(swayShadows ? plugin.windSwayAmount() : 0);

		// Zone and VAO draw through these shared uniform locations; point them at the shadow program
		savedBase = uniBase;
		savedEntityProj = uniEntityProj;
		savedEntityTint = uniEntityTint;
		uniBase = plugin.uniShadowBase;
		uniEntityProj = plugin.uniShadowEntityProj;
		uniEntityTint = -1;
	}

	private int savedBase, savedEntityProj, savedEntityTint;

	private void endShadowPass()
	{
		uniBase = savedBase;
		uniEntityProj = savedEntityProj;
		uniEntityTint = savedEntityTint;
		glBindVertexArray(0);
		glDisable(GL_POLYGON_OFFSET_FILL);
		glDepthFunc(GL_GREATER);
		glDisable(GL_DEPTH_TEST);
		glUseProgram(0);
	}

	/**
	 * Keep the static shadow map current: when the light has turned, the player has walked out of the snapped cell, a
	 * zone was rebuilt or a strike needs its bolt's map, queue a new one for the scheduler to draw (StaticShadowBuild).
	 */
	private void renderStaticShadows(SceneContext ctx, int size, float fx, float fy, float fz, float radius,
		float depthRange, boolean swayShadows)
	{
		final int sway = swayShadows ? Zone.SWAY_EXCLUDE : Zone.SWAY_ALL;
		int key = java.util.Objects.hash(System.identityHashCode(ctx), ctx.minLevel, ctx.level, ctx.maxLevel,
			plugin.config.emissiveStrength() > 0, plugin.textureArrayId != -1, size, radius, sway);
		plugin.shadowMap.checkCacheKey(key);
		if (staticBuild != null)
		{
			return;
		}

		ShadowMap.View front = plugin.shadowMap.frontView();
		if (front != null && shadowStale(front, fx, fy, fz, radius, size))
		{
			// the light turned to where the held map (a lightning strike's, or the sun's it left) is drawn for: use it
			ShadowMap.View held = plugin.shadowMap.heldView();
			if (held != null && !shadowStale(held, fx, fy, fz, radius, size))
			{
				plugin.shadowMap.swapFront();
				return;
			}
		}
		// a strike needs the map for its bolt drawn before its flash, beside the one in use
		float[] bolt = plugin.weather.getLightning().getBoltDir();
		boolean holdBolt = front != null && bolt != null && !plugin.shadowMap.isStale()
			&& !shadowStale(front, fx, fy, fz, radius, size)
			&& !directionMatches(front, bolt) && !directionMatches(plugin.shadowMap.heldView(), bolt);
		if (front == null || plugin.shadowMap.isStale() || shadowStale(front, fx, fy, fz, radius, size) || holdBolt)
		{
			staticBuild = new StaticShadowBuild(ctx, holdBolt ? bolt.clone() : null, fx, fy, fz, holdBolt, false);
			plugin.sceneLoader.jobs.add(holdBolt ? "static shadows (bolt)" : "static shadows", SceneJobs.SHOWN, staticBuild);
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
	public void report()
	{
		float[] bolt = plugin.weather.getLightning().getBoltDir();
		ShadowMap.View front = plugin.shadowMap.frontView();
		boolean ready = !plugin.frameShadows
			|| bolt != null && (directionMatches(front, bolt) || directionMatches(plugin.shadowMap.heldView(), bolt));
		plugin.weather.getLightning().setShadowMaps(ready, !plugin.frameShadows || directionMatches(front, plugin.dayCycle.casterDir));
	}

	private boolean shadowStale(ShadowMap.View v, float fx, float fy, float fz, float radius, int size)
	{
		float half = SHADOW_CACHE_SNAP * 0.5f;
		if (Math.abs(fx - v.focusX) > half || Math.abs(fz - v.focusZ) > half || Math.abs(fy - v.focusY) > SHADOW_CACHE_SNAP)
		{
			return true;
		}
		float[] d = plugin.dayCycle.casterDir;
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
		if (list != null)
		{
			for (int i = from; i < to; ++i)
			{
				shadowZone(ctx, list[i] >> 16, list[i] & 0xFFFF, offset, sway);
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
					shadowZone(ctx, x, z, offset, sway);
					continue;
				}
				Zone zone = ctx.zones[x][z];
				if (zone.drawable() && zone.sizeO > 0)
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

	private static void shadowZone(SceneContext ctx, int x, int z, int offset, int sway)
	{
		Zone zone = ctx.zones[x][z];
		if (!zone.drawable() || zone.sizeO <= 0)
		{
			return;
		}
		// every level and every roof, including ones hidden from the camera, so interiors stay shaded
		zone.renderOpaque(x - offset, z - offset, ctx.minLevel, ctx.level, 3, Collections.emptySet(), sway);
	}
}
