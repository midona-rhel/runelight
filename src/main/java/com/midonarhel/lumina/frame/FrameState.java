package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.common.LogLimiter;
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
 * The per-frame state: the camera, draw distance, sky and fog captured for this frame, the edge fade's focus, a view ray, the frame uniforms, the alpha pass's blend state. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class FrameState
{
	private final LuminaPlugin plugin;

	public FrameState(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/**
	 * Draw the scene targets to the window. Effects that work on the whole frame (fog, AO, grading) go in
	 * composite_frag.glsl.
	 */
	public void captureFrameState(Scene scene, float cameraX, float cameraY, float cameraZ)
	{
		plugin.frameCameraX = cameraX;
		plugin.frameCameraY = cameraY;
		plugin.frameCameraZ = cameraZ;
		plugin.frameSkyColor = plugin.client.getSkyboxColor();
		plugin.frameHasSkybox = scene.getSkybox() != null;
		{
			// the game's own sky color is black where nothing exists around the scene; areas may still call it
			// open sky (a player house has daylight)
			int r = plugin.frameSkyColor >> 16 & 0xFF, g = plugin.frameSkyColor >> 8 & 0xFF, b = plugin.frameSkyColor & 0xFF;
			plugin.frameSkyVoid = !plugin.frameHasSkybox && r + g + b <= 24;
		}
		plugin.frameDrawDistance = plugin.getDrawDistance() * Perspective.LOCAL_TILE_SIZE;

		plugin.cpuTimer.begin(CpuTimer.ENVIRONMENT);
		plugin.frameOpenSky = plugin.skyFrame.updateEnvironment(scene);
		plugin.cpuTimer.end(CpuTimer.ENVIRONMENT);
		plugin.frameOutdoors = plugin.config.sunLighting() && plugin.frameOpenSky;

		plugin.frameGameMillis = GameClock.gameMillis(com.midonarhel.lumina.common.Clock.millis(), plugin.config.dayCycleMinutes());
		plugin.dayCycle.update(plugin.frameGameMillis, plugin.config.dayCycleMinutes(), plugin.config.fixedHour());

		plugin.frameShadows = plugin.frameOutdoors && plugin.config.shadows() && plugin.client.getGameState() == GameState.LOGGED_IN;
		VAO.clearShadowDraws();
		VAO.recordShadowDraws = plugin.frameShadows;

		plugin.cpuTimer.begin(CpuTimer.WEATHER);
		plugin.weather.getLightning().setSunStrength(plugin.dayCycle.directStrength(plugin.volumetricFog.overcast[0], plugin.volumetricFog.overcast[1]));
		plugin.weatherFrame.update(cameraX, cameraY, cameraZ);
		plugin.dayCycle.applyStrike(plugin.weather.getLightning());
		plugin.visibilityFrame.updateSkylight();
		plugin.cpuTimer.end(CpuTimer.WEATHER);

		plugin.frameFog = false;
		Player player = plugin.client.getLocalPlayer();
		LocalPoint lp = player != null ? player.getLocalLocation() : null;
		if (plugin.config.volumetricFog() && lp != null && plugin.client.getGameState() == GameState.LOGGED_IN && plugin.volumetricFog.hasGround())
		{
			// thicker in rain and snow, and in areas 117 HD gives fog (its fogDepth: ~6 faint, 50 thick)
			float areaFog = plugin.config.areaEnvironments() ? plugin.environmentManager.getFogDepth() : 0;
			plugin.volumetricFog.areaDensity = plugin.weather.fogMultiplier()
				* (1 + plugin.weather.areaFogWeight() * Math.min(areaFog / AREA_FOG_DEPTH, 2));
			plugin.volumetricFog.areaHeight = plugin.weather.fogHeightMultiplier();
			plugin.volumetricFog.weatherField = plugin.weather.getField();
			plugin.volumetricFog.weatherFog = plugin.weather.fogMultiplier();
			System.arraycopy(plugin.weather.getFogTint(), 0, plugin.volumetricFog.areaTint, 0, 3);
			plugin.volumetricFog.updateParameters(plugin.config, lp.getX(), lp.getY(), plugin.client.getTopLevelWorldView().getPlane(), plugin.frameDrawDistance);
			plugin.frameFog = true;
		}
	}

	/**
	 * Where the drawn world ends this frame (EdgeFade): a circle of the draw distance around the focal point, inside
	 * the larger square the client is told to draw, with a fade as wide as the plugin.weather's fog asks.
	 */
	public void updateEdge(Scene scene, float cameraX, float cameraZ)
	{
		int drawDistance = plugin.getDrawDistance();
		float fx = plugin.client.getCameraFocalPointX(), fz = plugin.client.getCameraFocalPointZ();
		float offset = (float) Math.hypot(cameraX - fx, cameraZ - fz);
		scene.setDrawDistance(EdgeFade.clientDrawDistance(drawDistance, offset));
		// read back in case the client limits it
		float radius = EdgeFade.radius(drawDistance, scene.getDrawDistance(), offset);
		float areaFog = plugin.config.areaEnvironments() && !plugin.frameHasSkybox
			? plugin.environmentManager.getFogDepth() * plugin.weather.distanceFogScale() : 0;
		plugin.edgeFade.update(fx, fz, radius, plugin.client.getExpandedMapLoading(),
			EdgeFade.fogginess(plugin.weather.fogMultiplier(), areaFog));
	}

	/** The unit view ray through a point of the screen (-1 to 1) as frame.glsl's reconstructPosition; null before there is one. */
	public float[] viewRay(float ndcX, float ndcY)
	{
		float[] m = plugin.frameInvViewProj;
		float x = m[0] * ndcX + m[4] * ndcY + m[8] + m[12], y = m[1] * ndcX + m[5] * ndcY + m[9] + m[13];
		float z = m[2] * ndcX + m[6] * ndcY + m[10] + m[14], w = m[3] * ndcX + m[7] * ndcY + m[11] + m[15];
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return length > 1e-6f && Math.abs(w) > 1e-9f ? new float[]{x / length * Math.signum(w), y / length * Math.signum(w),
			z / length * Math.signum(w)} : null;
	}

	/**
	 * Transparent faces go to their own layer, blended as premultiplied "over" so the composite can put the layer
	 * on top of the lit opaque scene. They don't write depth (Zone and VAO already turn depth writes off).
	 */
	public void setAlphaPass(boolean alpha)
	{
		plugin.sceneTargets.setAlphaPass(alpha);
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
		glUniform1i(plugin.uniAlphaPass, alpha ? 1 : 0);
		// the shadow map as the shadow pass left it last frame, with that frame's matrix (frag.glsl alphaShadow)
		boolean shadows = alpha && plugin.frameShadows && plugin.shadowMap.isInitialized();
		glUniform1i(plugin.uniAlphaShadows, shadows ? 1 : 0);
		if (shadows)
		{
			plugin.shadowMap.bindForSampling(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
			glUniformMatrix4fv(plugin.uniAlphaLightProj, false, plugin.shadowMap.lightProj);
			glUniform1f(plugin.uniAlphaShadowTexel, 2 * plugin.shadowMap.getRadius() / plugin.shadowMap.getSize());
			glUniform1f(plugin.uniAlphaShadowDepthRange, plugin.shadowMap.getDepthRange());
			glUniform1i(plugin.uniAlphaDepthZeroToOne, plugin.glCapabilities.OpenGL45 ? 1 : 0);
		}
	}

	public void setFrameUniforms(FrameUniforms u)
	{
		glUniformMatrix4fv(u.viewProj, false, plugin.frameViewProj);
		glUniformMatrix4fv(u.invViewProj, false, plugin.frameInvViewProj);
		glUniform4i(u.viewport, plugin.frameViewport[0], plugin.frameViewport[1], plugin.frameViewport[2], plugin.frameViewport[3]);
		glUniform1i(u.depthZeroToOne, plugin.glCapabilities.OpenGL45 ? 1 : 0);
		glUniform1f(u.halfRatio, plugin.sceneTargets.getHalfRatio());
	}
}
