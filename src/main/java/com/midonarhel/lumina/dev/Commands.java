package com.midonarhel.lumina.dev;

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
import com.midonarhel.lumina.frame.Composite;
import com.midonarhel.lumina.frame.FramePacer;
import com.midonarhel.lumina.frame.Programs;
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
import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The developer commands (::runelight cam|capture|set|snapshot|pace|swapcapture and the command file) and the
 * screenshot they capture. Split out of the plugin class verbatim; they drive the plugin through {@code plugin}.
 */
public final class Commands
{
	private final LuminaPlugin plugin;

	public Commands(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	public Image screenshot()
	{
		int width = plugin.client.getCanvasWidth();
		int height = plugin.client.getCanvasHeight();

		if (plugin.client.isStretchedEnabled())
		{
			Dimension dim = plugin.client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = plugin.clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = plugin.getScaledValue(t.getScaleX(), width);
		height = plugin.getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(plugin.awtContext.getBufferMode());
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

	/**
	 * The comparison commands shared with Lumina and the 117 HD reference build: cam yaw pitch zoom, capture [name]
	 * (to ~/.runelite/lumina/captures), set key value (a Lumina setting, e.g. fixedHour / dayCycleMinutes).
	 */
	public void run(String[] args)
	{
		if (args.length >= 4 && args[0].equals("cam"))
		{
			FrameCapture.setCamera(plugin.client, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
		}
		else if (args.length >= 1 && args[0].equals("capture"))
		{
			FrameCapture.capture(plugin.client, plugin.drawManager, "Lumina (perf-wip)", args.length >= 2 ? args[1] : "capture");
		}
		else if (args.length >= 1 && args[0].equals("snapshot") && !plugin.frameSnapshot.running())
		{
			int seconds = args.length >= 2 ? Math.max(1, Math.min(120, Integer.parseInt(args[1]))) : 10;
			boolean uncapped = args.length >= 3 && args[2].equals("uncapped");
			int target = plugin.config.fpsTarget();
			if (uncapped)
			{
				plugin.configManager.setConfiguration(LuminaConfig.GROUP, "fpsTarget", 999);
			}
			java.util.List<String> header = new java.util.ArrayList<>();
			header.add("renderer: Lumina (perf-wip)");
			header.add("canvas: " + plugin.client.getCanvasWidth() + "x" + plugin.client.getCanvasHeight());
			header.add("camera: yaw " + plugin.client.getCameraYaw() + ", pitch " + plugin.client.getCameraPitch() + ", scale " + plugin.client.getScale());
			header.add("unlock_fps: " + plugin.config.unlockFps() + ", fps_target: " + (uncapped ? 999 : target) + ", vsync: " + plugin.config.syncMode());
			header.add("render_scale: " + plugin.config.renderScale() + ", ao: " + plugin.config.ambientOcclusion());
			header.add("location: " + (plugin.client.getLocalPlayer() == null ? "none" : plugin.client.getLocalPlayer().getWorldLocation()));
			plugin.frameSnapshot.start(header, seconds, uncapped ? () ->
			{
				plugin.configManager.setConfiguration(LuminaConfig.GROUP, "fpsTarget", target);
				log.info("Snapshot done: fpsTarget back to {}", target);
			} : null);
		}
		else if (args.length >= 1 && args[0].equals("tiles"))
		{
			com.midonarhel.lumina.light.LampTiles.dump(plugin, args.length >= 2 ? args[1] : "tiles");
		}
		else if (args.length >= 2 && args[0].equals("pace"))
		{
			plugin.pacer.setPaceHere(args[1].equals("on"));
			plugin.setupSyncMode();
			log.info("Frame pacing {}", plugin.pacer.isPaceHere() ? "here (Lumina's pacer)" : "by the client's limiter");
		}
		else if (args.length >= 1 && args[0].equals("swapcapture"))
		{
			// the eases after a swap (LayerEase) take 0.3 s: `swapcapture 30` spans them at 60 FPS
			plugin.swapCaptureFrames = args.length >= 2 ? Math.max(1, Math.min(60, Integer.parseInt(args[1]))) : 8;
			plugin.swapCaptureArmed = true;
			log.info("Armed: {} frames from the end of the next scene load (spanning the swap) are captured as swap-k0..{}",
				plugin.swapCaptureFrames, plugin.swapCaptureFrames - 1);
		}
		else if (args.length >= 3 && args[0].equals("set"))
		{
			plugin.configManager.setConfiguration(LuminaConfig.GROUP, args[1], args[2]);
			log.info("Set {} = {}", args[1], args[2]);
		}
		else
		{
			log.info("Unknown ::runelight command: {}", String.join(" ", args));
		}
	}
}
