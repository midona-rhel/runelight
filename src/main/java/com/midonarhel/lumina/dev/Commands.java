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
	// developer: the skylight's traced visibility switched off (`::runelight skylight off`)
	public static volatile boolean skylightOff;

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

		final AffineTransform t = plugin.dpiTransform();
		width = plugin.getScaledValue(t.getScaleX(), width);
		height = plugin.getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		if (plugin.offscreenFramebuffer >= 0)
		{
			// the offline renderer's framebuffer
			glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.offscreenFramebuffer);
			glReadBuffer(GL_COLOR_ATTACHMENT0);
		}
		else
		{
			glReadBuffer(plugin.awtContext.getBufferMode());
		}
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
	 * (to ~/.runelite/lumina/captures), set key value (a Lumina setting, e.g. fixedHour / dayLength).
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
		else if (args.length >= 1 && args[0].equals("playerlighttrace"))
		{
			PlayerLightTrace trace = plugin.lampFrame.playerLightTrace;
			String action = args.length >= 2 ? args[1] : "help";
			if (action.equals("on"))
			{
				trace.start();
				plugin.chat("Player light trace armed: last 600 frames. Cross doorway, then playerlighttrace dump.");
			}
			else if (action.equals("off")) trace.stop();
			else if (action.equals("dump"))
			{
				try { plugin.chat("Player light trace saved: " + trace.dump()); }
				catch (java.io.IOException ex) { log.warn("Player light trace write failed", ex); plugin.chat("Player light trace write failed: " + ex.getMessage()); }
			}
			else plugin.chat("Usage: ::runelight playerlighttrace on|off|dump");
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
		else if (args.length >= 2 && args[0].equals("skylight"))
		{
			// developer A/B: `skylight off` draws the frame as if the sky grid had no answer (the shader's open-sky
			// fallback), `skylight on` restores it
			skylightOff = args[1].equals("off");
			log.info("Skylight {}", skylightOff ? "off" : "on");
		}
		else if (args.length >= 2 && args[0].equals("aurora"))
		{
			// `aurora 0.5`: the aurora held at that strength anywhere, on any night; `aurora off`: as the place has it
			com.midonarhel.lumina.sky.NightSky.auroraHold = args[1].equals("off") ? Float.NaN : Float.parseFloat(args[1]);
			log.info("Aurora {}", args[1]);
		}
		else if (args.length >= 2 && args[0].equals("strike"))
		{
			// `strike hold`: a strike whose flash stays at its top until `strike off`; `strike` alone: one strike
			com.midonarhel.lumina.sky.Lightning.hold = args[1].equals("hold");
			if (!args[1].equals("off"))
			{
				plugin.weather.getLightning().strikeNow();
			}
			log.info("Lightning {}", com.midonarhel.lumina.sky.Lightning.hold ? "held at its top" : "released");
		}
		else if (args.length >= 1 && args[0].equals("skydump"))
		{
			// the sky grid's inputs and texels, for tracing the spot again offline (SkyGridReplay)
			java.nio.file.Path file = FrameCapture.DIR.getParent().resolve("dumps")
				.resolve("skygrid-" + (args.length >= 2 ? args[1] : "dump") + ".bin.gz");
			plugin.skyGrid.requestDump(file, plugin.frameSkylightTileX, plugin.frameSkylightTileZ);
			log.info("Sky grid dump requested: {}", file);
		}
		else if (args.length >= 1 && args[0].equals("swapcapture"))
		{
			// the eases after a swap (LayerEase) take 0.3 s: `swapcapture 30` spans them at 60 FPS
			plugin.swapCaptureFrames = args.length >= 2 ? Math.max(1, Math.min(60, Integer.parseInt(args[1]))) : 8;
			plugin.swapCaptureArmed = true;
			log.info("Armed: {} frames from the end of the next scene load (spanning the swap) are captured as swap-k0..{}",
				plugin.swapCaptureFrames, plugin.swapCaptureFrames - 1);
		}
		else if (args.length >= 1 && args[0].equals("faces"))
		{
			faces(args.length >= 2 ? Integer.parseInt(args[1]) : 2, args.length >= 3 && args[2].equals("detail"));
		}
		else if (args.length >= 2 && args[0].equals("scenedump"))
		{
			// the next frame and everything it is drawn from, for the offline renderer (docs/offline-render.md)
			plugin.sceneDump.arm(args[1]);
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

	/**
	 * ::runelight faces [radius]: the shading data of the scene objects within radius tiles of the player, as the
	 * uploaders see it, to ~/.runelite/lumina/captures/faces.txt and the log: per model its faces, how many the game
	 * marks flat (faceColors3 == -1, lit by their own normal under 117 HD's rule), hidden (-2) and textured, whether it
	 * has vertex normals (Model.getVertexNormalsX), how many of its vertices have a zero normal, and how many smooth
	 * faces have all three zero (lit flat too). Every plane, from the ground up (roofs stand on the planes above).
	 * `faces [radius] detail` adds each face: its colours, texture, and per corner the vertex, position and normal.
	 */
	private void faces(int radius, boolean detail)
	{
		net.runelite.api.Player player = plugin.client.getLocalPlayer();
		net.runelite.api.WorldView wv = plugin.client.getTopLevelWorldView();
		if (player == null || wv == null)
		{
			return;
		}
		net.runelite.api.coords.LocalPoint lp = player.getLocalLocation();
		int px = lp.getSceneX(), py = lp.getSceneY();
		net.runelite.api.Tile[][][] tiles = wv.getScene().getTiles();
		StringBuilder out = new StringBuilder("player " + player.getWorldLocation() + "\n");
		for (int plane = 0; plane < tiles.length; ++plane)
		{
			for (int x = Math.max(0, px - radius); x <= Math.min(tiles[plane].length - 1, px + radius); ++x)
			{
				for (int y = Math.max(0, py - radius); y <= Math.min(tiles[plane][x].length - 1, py + radius); ++y)
				{
					net.runelite.api.Tile t = tiles[plane][x][y];
					if (t == null)
					{
						continue;
					}
					out.append("tile ").append(t.getWorldLocation()).append('\n');
					for (net.runelite.api.GameObject o : t.getGameObjects())
					{
						if (o != null && o.getSceneMinLocation().equals(t.getSceneLocation()))
						{
							out.append("orientation ").append(o.getModelOrientation())
								.append(" type ").append(o.getConfig() & 31).append(" config ").append(o.getConfig()).append(' ');
							describe(out, "game", o.getId(), o.getRenderable(), detail);
						}
					}
					if (t.getGroundObject() != null)
					{
						describe(out, "ground", t.getGroundObject().getId(), t.getGroundObject().getRenderable(), detail);
					}
					if (t.getWallObject() != null)
					{
						describe(out, "wall", t.getWallObject().getId(), t.getWallObject().getRenderable1(), detail);
					}
					if (t.getDecorativeObject() != null)
					{
						describe(out, "decoration", t.getDecorativeObject().getId(), t.getDecorativeObject().getRenderable(), detail);
					}
				}
			}
		}
		log.info("faces:\n{}", detail ? "(detail: see faces.txt)" : out);
		try
		{
			java.nio.file.Path dir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("captures");
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Files.write(dir.resolve("faces.txt"), out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (java.io.IOException ex)
		{
			log.warn("Couldn't write faces.txt", ex);
		}
	}

	private void describe(StringBuilder out, String kind, int id, net.runelite.api.Renderable r, boolean detail)
	{
		net.runelite.api.Model m = com.midonarhel.lumina.scene.ModelData.modelOf(r);
		String name = plugin.client.getObjectDefinition(id).getName();
		if (m == null)
		{
			out.append(kind).append(' ').append(id).append(' ').append(name).append(": no model\n");
			return;
		}
		int[] c3 = m.getFaceColors3();
		short[] tex = m.getFaceTextures();
		int[] nx = m.getVertexNormalsX(), ny = m.getVertexNormalsY(), nz = m.getVertexNormalsZ();
		int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
		int faces = m.getFaceCount(), flat = 0, hidden = 0, textured = 0, zeroVertices = 0, smoothAllZero = 0;
		boolean normals = nx != null && ny != null && nz != null;
		for (int f = 0; f < faces; ++f)
		{
			if (c3[f] == -1)
			{
				++flat;
			}
			else if (c3[f] == -2)
			{
				++hidden;
			}
			else if (normals && (nx[i1[f]] | ny[i1[f]] | nz[i1[f]]) == 0 && (nx[i2[f]] | ny[i2[f]] | nz[i2[f]]) == 0
				&& (nx[i3[f]] | ny[i3[f]] | nz[i3[f]]) == 0)
			{
				++smoothAllZero;
			}
			if (tex != null && tex[f] != -1)
			{
				++textured;
			}
		}
		if (normals)
		{
			for (int v = 0; v < m.getVerticesCount(); ++v)
			{
				if ((nx[v] | ny[v] | nz[v]) == 0)
				{
					++zeroVertices;
				}
			}
		}
		out.append(kind).append(' ').append(id).append(' ').append(name)
			.append(": faces ").append(faces).append(", flat (c3 -1) ").append(flat).append(", hidden ").append(hidden)
			.append(", textured ").append(textured).append(", vertex normals ").append(normals ? "yes" : "NONE")
			.append(", zero-normal vertices ").append(zeroVertices).append('/').append(m.getVerticesCount())
			.append(", smooth faces with three zero normals ").append(smoothAllZero)
			.append(", shading group ").append(com.midonarhel.lumina.scene.SurfaceMaterials.shadingForObject(id, r))
			.append('\n');
		if (!detail)
		{
			return;
		}
		float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		int[] c1 = m.getFaceColors1(), c2 = m.getFaceColors2();
		for (int f = 0; f < faces; ++f)
		{
			out.append("  face ").append(f).append(" c ").append(c1[f]).append(' ').append(c2[f]).append(' ').append(c3[f])
				.append(" tex ").append(tex != null ? tex[f] : -1);
			for (int v : new int[]{i1[f], i2[f], i3[f]})
			{
				out.append(" | v").append(v).append(" (").append((int) vx[v]).append(',').append((int) vy[v]).append(',')
					.append((int) vz[v]).append(')');
				if (normals)
				{
					out.append(" n ").append(nx[v]).append(',').append(ny[v]).append(',').append(nz[v]);
				}
			}
			out.append('\n');
		}
	}
}
