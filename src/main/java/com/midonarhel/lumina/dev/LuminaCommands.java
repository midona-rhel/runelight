package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.config.LuminaConfig;
import com.midonarhel.lumina.frame.FrameRenderer;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.SkyLighting;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;
import static org.lwjgl.opengl.GL33C.GL_RENDERER;
import static org.lwjgl.opengl.GL33C.GL_VERSION;
import static org.lwjgl.opengl.GL33C.glGetString;

/**
 * The ::lumina developer commands, typed in the chat or read from .runelite/lumina/command.txt:
 * <ul>
 * <li>cam yaw pitch zoom, capture [name]: {@link FrameCapture}</li>
 * <li>dump: every lighting value the shaders get, for comparing against 117 HD</li>
 * <li>debug albedo|light|normal|off: the scene shader's comparison views</li>
 * <li>snapshot [seconds]: a {@link Snapshot} of the frame times</li>
 * </ul>
 * The plugin only calls this in developer mode. Everything runs on the client thread.
 */
@Slf4j
public final class LuminaCommands
{
	private final Client client;
	private final DrawManager drawManager;
	private final LuminaConfig config;
	private final EnvironmentManager environmentManager;
	private final SkyLighting skyLighting;
	private final FrameRenderer frame;
	private final SceneLoader scenes;

	// commands from .runelite/lumina/command.txt, as if typed as ::lumina <line>
	private final CommandFile commandFile = new CommandFile(
		RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("command.txt"), this::run);

	public LuminaCommands(Client client, DrawManager drawManager, LuminaConfig config, EnvironmentManager environmentManager,
		SkyLighting skyLighting, FrameRenderer frame, SceneLoader scenes)
	{
		this.client = client;
		this.drawManager = drawManager;
		this.config = config;
		this.environmentManager = environmentManager;
		this.skyLighting = skyLighting;
		this.frame = frame;
		this.scenes = scenes;
	}

	/**
	 * Runs the command file's lines, if there is one. Once per game tick.
	 */
	public void poll()
	{
		commandFile.poll();
	}

	public void run(String[] args)
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
			var data = scenes.root().data;
			Lighting lighting = frame.lighting();
			StringBuilder sb = new StringBuilder("Lighting dump: target=")
				.append(environmentManager.getTargetEnvironment())
				.append(" to=").append(environmentManager.getToEnvironment())
				.append(" candidates=").append(data == null ? "none" : data.environments)
				.append(" focal=").append(data == null ? "none" : Arrays.toString(data.localToWorld(
					(int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ(), client.getTopLevelWorldView().getPlane())))
				.append(" sceneBase=").append(data == null ? "none" : Arrays.toString(data.sceneBase))
				.append(System.lineSeparator()).append("  ").append(skyLighting.describe());
			for (var f : Lighting.class.getFields())
			{
				try
				{
					Object v = f.get(lighting);
					sb.append(System.lineSeparator()).append("  ").append(f.getName()).append(" = ")
						.append(v instanceof float[] ? Arrays.toString((float[]) v) : v);
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
					frame.setDebugView(1);
					break;
				case "light":
					frame.setDebugView(2);
					break;
				case "normal":
					frame.setDebugView(3);
					break;
				default:
					frame.setDebugView(0);
			}
			return;
		}
		if (args.length >= 1 && args[0].equals("snapshot") && !frame.timing().isSnapshotRunning())
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
			frame.timing().startSnapshot(snapshotHeader(seconds), seconds);
		}
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
			+ ", stretched " + frame.stretchedCanvasWidth() + "x" + frame.stretchedCanvasHeight());
		h.add("anti_aliasing: " + config.antiAliasingMode());
		h.add("draw_distance: " + frame.getDrawDistance() + ", expanded_map_loading: " + config.expandedMapLoadingZones());
		h.add("unlock_fps: " + config.unlockFps() + ", fps_target: " + config.fpsTarget() + ", sync: " + config.syncMode());
		h.add("camera: yaw " + client.getCameraYaw() + ", pitch " + client.getCameraPitch() + ", scale " + client.getScale());
		h.add("location: " + (player == null ? "none" : player.getWorldLocation()));
		h.add("gpu_scene note: includes GPU idle while the client traverses the scene");
		return h;
	}
}
