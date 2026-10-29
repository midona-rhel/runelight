package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.config.LightingSourceKind;
import com.midonarhel.lumina.config.DayLength;
import com.midonarhel.lumina.config.LuminaConfig;
import com.midonarhel.lumina.config.SkySettings;
import com.midonarhel.lumina.frame.FrameRenderer;
import com.midonarhel.lumina.lighting.Lighting;
import com.midonarhel.lumina.lighting.SkyLighting;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.environment.EnvironmentManager;
import com.midonarhel.lumina.sky.SkySource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
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
	private final ConfigManager configManager;
	private final EnvironmentManager environmentManager;
	private final SkyLighting skyLighting;
	private final SkySource skySource;
	private final FrameRenderer frame;
	private final SceneLoader scenes;
	// the dump's scratch for the source that is not active
	private final Lighting otherLighting = new Lighting();

	// commands from .runelite/lumina/command.txt, as if typed as ::lumina <line>
	private final CommandFile commandFile = new CommandFile(
		RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("command.txt"), this::run);

	public LuminaCommands(Client client, DrawManager drawManager, LuminaConfig config, ConfigManager configManager,
		EnvironmentManager environmentManager, SkyLighting skyLighting, SkySource skySource, FrameRenderer frame, SceneLoader scenes)
	{
		this.client = client;
		this.configManager = configManager;
		this.drawManager = drawManager;
		this.config = config;
		this.environmentManager = environmentManager;
		this.skyLighting = skyLighting;
		this.skySource = skySource;
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
		// ::lumina time <hour 0-24 | run>: hold the game clock at an hour, or let it run (24 minute day)
		if (args.length >= 2 && args[0].equals("time"))
		{
			if (args[1].equals("run"))
			{
				configManager.setConfiguration(LuminaConfig.GROUP, "dayLength", DayLength.MINUTES_24);
			}
			else
			{
				try
				{
					int hour = Math.max(0, Math.min(24, Integer.parseInt(args[1])));
					configManager.setConfiguration(LuminaConfig.GROUP, "fixedHour", hour);
					configManager.setConfiguration(LuminaConfig.GROUP, "dayLength", DayLength.FIXED_HOUR);
				}
				catch (NumberFormatException ex)
				{
					log.warn("::lumina time <hour|run>");
				}
			}
			return;
		}
		// ::lumina source <lumina|hd117>: which lighting source the frame uses
		if (args.length >= 2 && args[0].equals("source"))
		{
			configManager.setConfiguration(LuminaConfig.GROUP, "lightingSource",
				args[1].equalsIgnoreCase("hd117") ? LightingSourceKind.HD117 : LightingSourceKind.LUMINA);
			return;
		}
		if (args.length >= 1 && args[0].equals("dump"))
		{
			// every lighting value the shaders get, from both sources for this frame, for comparing against 117 HD and
			// against each other (the active source is marked)
			var data = scenes.root().data;
			StringBuilder sb = new StringBuilder("Lighting dump: active=").append(SkySettings.lightingSource)
				.append(" target=").append(environmentManager.getTargetEnvironment())
				.append(" to=").append(environmentManager.getToEnvironment())
				.append(" candidates=").append(data == null ? "none" : data.environments)
				.append(" focal=").append(data == null ? "none" : Arrays.toString(data.localToWorld(
					(int) client.getCameraFocalPointX(), (int) client.getCameraFocalPointZ(), client.getTopLevelWorldView().getPlane())))
				.append(" sceneBase=").append(data == null ? "none" : Arrays.toString(data.sceneBase));
			for (LightingSourceKind kind : LightingSourceKind.values())
			{
				Lighting lighting = frame.lightingOf(kind, otherLighting);
				sb.append(System.lineSeparator()).append("[").append(kind.name()).append(kind == SkySettings.lightingSource ? ", active] " : "] ")
					.append(kind == LightingSourceKind.LUMINA ? skySource.describe() : skyLighting.describe());
				// the light on flat ground and on a vertical wall facing the light, as the scene shader sums it
				float[] up = {0, -1, 0};
				float[] wall = {lighting.lightDir[0], 0, lighting.lightDir[2]};
				float len = (float) Math.sqrt(wall[0] * wall[0] + wall[2] * wall[2]);
				if (len > 1e-6f)
				{
					wall[0] /= len;
					wall[2] /= len;
				}
				sb.append(System.lineSeparator()).append("  light on flat ground = ").append(Arrays.toString(irradiance(lighting, up)))
					.append(", on a wall facing the light = ").append(Arrays.toString(irradiance(lighting, wall)));
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

	/**
	 * The light a lit surface with this normal (unit, y down) gets, summed as frag.glsl sums it: Lumina's seam
	 * (ambient + sky fill SH + caster) or 117 HD's (ambient + directional + half the fog colour on upward faces).
	 */
	static float[] irradiance(Lighting l, float[] n)
	{
		float[] out = new float[3];
		float ndl = Math.max(0, n[0] * l.lightDir[0] + n[1] * l.lightDir[1] + n[2] * l.lightDir[2]);
		for (int i = 0; i < 3; i++)
		{
			out[i] = l.ambientColor[i] * l.ambientStrength + l.lightColor[i] * l.lightStrength * ndl;
			if (l.luminaLighting)
			{
				out[i] += Math.max(0, l.skyFillSh[i] + l.skyFillSh[3 + i] * n[0] + l.skyFillSh[6 + i] * n[1] + l.skyFillSh[9 + i] * n[2]
					+ l.skyFillBounce[i] * Math.max(0, n[1]));
			}
			else
			{
				out[i] += 0.5f * l.fogColor[i] * Math.max(0, -n[1]);
			}
		}
		return out;
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
