package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.common.LogLimiter;
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
import com.midonarhel.lumina.dev.BuildingOverlay;
import com.midonarhel.lumina.dev.CommandFile;
import com.midonarhel.lumina.dev.Commands;
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
import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The sky's work each frame: the skybox draw, the night sky, clouds and sky cache updates, the area environment and its uniforms. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class SkyFrame
{
	private final LuminaPlugin plugin;

	public SkyFrame(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	public void drawSkybox(Scene scene, int sky, float cameraX, float cameraY, float cameraZ)
	{
		Model skybox = scene.getSkybox();
		if (skybox == null)
		{
			plugin.sceneTargets.clear((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f);
			return;
		}

		plugin.sceneTargets.clear(0f, 0f, 0f);

		int size = skybox.getFaceCount() * 3 * VAO.VERT_SIZE;
		RenderThread rt = plugin.rts[0];
		VAO o = rt.vaoO.get(size);
		rt.modelUploader.uploadTempModel(skybox, 0, 0, 0, 0,
			(int) cameraX, (int) cameraY, (int) cameraZ, o.vbo.vb, 0);

		float[] skyboxProjection = Mat4.translate(cameraX, cameraY, cameraZ);
		o.addRange(skyboxProjection, scene, Renderable.RENDERMODE_UNSORTED_NO_DEPTH);

		rt.vaoO.draw();

		glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
	}

	public void updateNightSky()
	{
		float[] forward = plugin.viewRay(0, 0);
		float[] above = plugin.viewRay(0, 2f / Math.max(plugin.frameViewport[3], 1));
		float pixelAngle = 0;
		if (forward != null && above != null)
		{
			pixelAngle = (float) Math.sqrt(Math.pow(forward[0] - above[0], 2) + Math.pow(forward[1] - above[1], 2)
				+ Math.pow(forward[2] - above[2], 2));
		}
		plugin.nightSky.update(plugin.compositeTime, plugin.dayCycle.phase, forward, pixelAngle,
			plugin.config.areaEnvironments() ? plugin.environmentManager.getBiome() : null, plugin.frameGameMillis, plugin.config.aurora());
	}

	/**
	 * Find the area the camera looks at and whether it is under the open sky. Without area data, the game's
	 * black underground sky is the only hint.
	 */
	public boolean updateEnvironment(Scene scene)
	{
		if (!plugin.config.areaEnvironments())
		{
			int r = plugin.frameSkyColor >> 16 & 0xFF, g = plugin.frameSkyColor >> 8 & 0xFF, b = plugin.frameSkyColor & 0xFF;
			return r + g + b > 24;
		}
		boolean loggedIn = plugin.client.getGameState() == GameState.LOGGED_IN;
		plugin.environmentManager.update(loggedIn ? scene : null,
			(int) plugin.client.getCameraFocalPointX(), (int) plugin.client.getCameraFocalPointZ(),
			plugin.client.getTopLevelWorldView().getPlane(), plugin.frameSkyColor,
			(System.currentTimeMillis() - plugin.startMillis) / 1000f);
		return plugin.environmentManager.isOverworld();
	}

	public void setEnvironmentUniforms(EnvironmentUniforms u)
	{
		boolean on = plugin.config.areaEnvironments();
		// the game's own skyboxes have their own colors; area fog would not match them, like in 117 HD
		float fog = plugin.frameHasSkybox ? 0 : plugin.weather.distanceFogScale();
		u.set(on ? plugin.environmentManager : null, fog, plugin.getDrawDistance(), plugin.edgeFade.focalX, plugin.edgeFade.focalZ);
	}

	public void updateClouds()
	{
		WorldView wv = plugin.client.getTopLevelWorldView();
		long baseX = wv != null ? (long) wv.getBaseX() * Perspective.LOCAL_TILE_SIZE : 0;
		long baseZ = wv != null ? (long) wv.getBaseY() * Perspective.LOCAL_TILE_SIZE : 0;
		plugin.clouds.setWeather(plugin.weather.cloudWisp(), plugin.weather.cloudDark(), plugin.weather.getWind());
		plugin.clouds.setEye(plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ, plugin.client.getCameraFocalPointX(),
			plugin.client.getCameraFocalPointY(), plugin.client.getCameraFocalPointZ(), plugin.edgeFade.radius, plugin.config.skyHorizonAtEdge());
		plugin.clouds.update(plugin.config.clouds(), plugin.config.cloudShadows(), plugin.config.cloudCoverage(), plugin.config.cloudSpeed(),
			plugin.weather.cloudCover(), baseX, baseZ, plugin.weather.getField());
	}

	/**
	 * Capture or blend the sky cache when the composite will use it, and return how it uses it (SkyCache.MODE_*).
	 */
	public int updateSkyCache()
	{
		// Loading a region keeps drawing the last frames; dropping the cache for them would switch the sky light.
		// Without updates the composite eases from the last capture back to the analytic light (SkyCache).
		GameState state = plugin.client.getGameState();
		if (!plugin.frameOutdoors || (state != GameState.LOGGED_IN && state != GameState.LOADING))
		{
			plugin.skyCache.bind(SKY_CACHE_UNIT);
			return 0;
		}
		int cycle = plugin.config.dayCycleMinutes();
		double gameMinutes = cycle > 0 ? plugin.frameGameMillis / 60_000.0 : Double.NaN;
		plugin.weather.getOvercast(plugin.skyCacheOvercast);
		int settings = java.util.Objects.hash(plugin.config.clouds(), plugin.config.cloudCoverage(), cycle,
			plugin.config.fixedHour(), Math.round(plugin.skyCacheOvercast[3] * 20),
			Math.round(plugin.skyCacheOvercast[4] * 20), Math.round(plugin.skyCacheOvercast[5] * 20), Math.round(plugin.skyCacheOvercast[6] * 20),
			Math.round(plugin.weather.cloudWisp() * 10), Math.round(plugin.weather.cloudDark() * 10));
		if (plugin.config.clouds())
		{
			plugin.clouds.bindNoise(CLOUD_NOISE_UNIT);
			plugin.weather.getField().bindSky(WEATHER_SKY_UNIT);
		}
		plugin.skyCache.update(plugin.clouds, gameMinutes, plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ,
			plugin.dayCycle, plugin.skyCacheOvercast, settings, SKY_CACHE_UNIT);
		plugin.skyCache.bind(SKY_CACHE_UNIT);
		return SkyCache.MODE_LIGHT;
	}
}
