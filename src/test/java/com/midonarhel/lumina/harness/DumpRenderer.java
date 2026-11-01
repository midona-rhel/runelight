package com.midonarhel.lumina.harness;

import com.google.gson.Gson;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.PlainData;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.fog.FogGround;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.light.SceneLights;
import com.midonarhel.lumina.scene.ModelFake;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import com.midonarhel.lumina.visibility.BuildingDetector;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.VoxelsRestore;
import com.midonarhel.lumina.visibility.WallMap;
import com.midonarhel.lumina.water.WaterMap;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.FloatProjection;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.ui.DrawManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The offline renderer (docs/offline-render.md): a scene dump drawn again with no client and no window, by the plugin
 * itself. A LuminaPlugin is made with the dump's client faked (DumpWorld), its zones restored into buffers of its own
 * (ZoneSnapshot), its derived data handed to the classes that hold it (the scene loader's restore and swap, the voxel
 * builder, the buildings, the lamps), and the frame tape played through the plugin's draw callbacks into a
 * framebuffer of the renderer's, frame after frame up to the dump's time so what eases or accumulates settles as it had
 * in the client. Nothing of the renderer is reimplemented: the frame is the plugin's frame.
 */
public final class DumpRenderer implements AutoCloseable
{
	private static final Logger log = LoggerFactory.getLogger(DumpRenderer.class);

	/** How the frame is drawn: the warm-up before the dump's time, and the overrides. */
	public static final class Options
	{
		// simulated seconds of frames before the dump's frame, and the step between them
		public double warmupSeconds = 6;
		public double frameSeconds = 0.05;
		// settings by key, over the dump's
		public final Map<String, String> settings = new HashMap<>();
		// the camera, null for the dump's; the zones of the whole scene are drawn when it moves (the tape's are those the
		// client's frustum kept)
		public Integer cameraYaw, cameraPitch, scale;
		// the world tile the camera turns about, null for the dump's focal point (the player)
		public int[] focus;
		// the hour to hold (day length Fixed, fixedHour), null for the dump's clock
		public Integer hour;
		// the buildings (walls, rooms, floors, roofs, drawn walls): "dumped" as the client had them; "rebuild" built again
		// by BuildingLights from the dump's scene objects (DumpWorld's faked tiles), as the client builds them
		public String buildings = "dumped";
		// false: no roofs stop the lamps (the buildings' roofs left out), to see what they stop
		public boolean lampRoofs = true;
		// world tiles (x, y) whose walls are printed
		public final List<int[]> probes = new ArrayList<>();
		// snow cover on the ground everywhere (the weather field's cells), null for the dump's; give it a weather that
		// neither snows nor works the ground out from the climate (weatherMode=OVERCAST), or it moves on from there
		public Float snowCover;
		// false: the weather's state (Weather's and the field's) is not put back from the dump, so a weather given by
		// weatherMode draws as itself, from the warm-up
		public boolean restoreWeather = true;
	}

	public final SceneDump.Loaded dump;
	public final DumpWorld world;
	public final Options options;
	public final LuminaPlugin plugin;
	private final HeadlessGl gl;
	private int fbo, colorRb;
	public final int width, height;
	private final Map<Integer, Projection> projections = new HashMap<>();
	private final Map<Integer, Model> models = new HashMap<>();
	// the camera of the dump's top level scene draw, and its focal point (for the camera overrides)
	private final float[] camera = new float[5];
	private boolean freeCamera;
	private final long dumpNanos;

	public DumpRenderer(SceneDump.Loaded dump, Options options) throws ReflectiveOperationException
	{
		this.dump = dump;
		this.options = options;
		world = new DumpWorld(dump);
		world.settings.putAll(options.settings);
		// a dump from before the day length options, on a free day length: the hour it was taken at, held
		com.midonarhel.lumina.sky.DayCycle.heldHour = options.hour == null && dump.manifest.settings.containsKey("dayCycleMinutes")
			&& !dump.manifest.settings.get("dayCycleMinutes").trim().equals("0") ? dump.manifest.hour : Double.NaN;
		if (options.hour != null)
		{
			world.settings.put("dayLength", "FIXED");
			world.settings.put("fixedHour", String.valueOf(options.hour));
		}
		SceneDump.Manifest m = dump.manifest;
		if (options.cameraYaw != null || options.cameraPitch != null || options.focus != null)
		{
			freeCamera = true;
		}
		world.cameraYaw = options.cameraYaw != null ? options.cameraYaw : m.cameraYaw;
		world.cameraPitch = options.cameraPitch != null ? options.cameraPitch : m.cameraPitch;
		world.scale = options.scale != null ? options.scale : m.scale;

		// the clock at the warm-up's start: the plugin's state is made then
		dumpNanos = m.nanos;
		long warmupNanos = (long) (options.warmupSeconds * 1e9);
		Clock.set(m.millis - warmupNanos / 1_000_000, m.nanos - warmupNanos);

		gl = new HeadlessGl();
		plugin = new LuminaPlugin();
		inject("client", world.client);
		inject("config", world.config);
		inject("gson", new Gson());
		inject("drawManager", new DrawManager());
		inject("renderCallbackManager", new RenderCallbackManager());
		inject("textureManager", new com.midonarhel.lumina.scene.TextureManager());

		int canvasW = m.stretched ? m.stretchedWidth : m.canvasWidth;
		int canvasH = m.stretched ? m.stretchedHeight : m.canvasHeight;
		width = (int) (canvasW * m.dpiScaleX);
		height = (int) (canvasH * m.dpiScaleY);
		plugin.offscreenTransform = AffineTransform.getScaleInstance(m.dpiScaleX, m.dpiScaleY);
		createFramebuffer();
		plugin.offscreenFramebuffer = fbo;

		plugin.initState();
		plugin.startMillis = m.startMillis;
		plugin.glCapabilities = gl.capabilities;
		PlainData.set(plugin, "lwjglInitted", true);
		plugin.initGlObjects();
		plugin.textureArrayId = -1;
		plugin.lastCanvasWidth = plugin.lastCanvasHeight = -1;
		plugin.lastStretchedCanvasWidth = plugin.lastStretchedCanvasHeight = -1;
		plugin.tempModels.setThreads(plugin.renderThreads());
		restore();
	}

	private void inject(String field, Object value) throws ReflectiveOperationException
	{
		var f = LuminaPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void createFramebuffer()
	{
		// color alone, as the window's framebuffer on macOS (LuminaPlugin.hasWindowStencil)
		fbo = glGenFramebuffers();
		colorRb = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, colorRb);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, width, height);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, colorRb);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new IllegalStateException("offscreen framebuffer: " + status);
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	@SuppressWarnings("unchecked")
	private void restore()
	{
		Map<String, Object> d = dump.derived;
		Scene top = world.scenes.get(WorldView.TOPLEVEL);

		// the lamps and the buildings as the dump had them
		plugin.sceneLights = new SceneLights(world.client);
		plugin.sceneLights.replay((List<PointLight>) d.getOrDefault("lamps.collected", new ArrayList<>()),
			(List<PointLight>) d.getOrDefault("lamps.steady", new ArrayList<>()));
		plugin.buildingLights = new BuildingLights(world.client);
		if (options.buildings.equals("rebuild"))
		{
			if (world.sceneObjects == null)
			{
				throw new IllegalStateException("buildings=rebuild: the dump has no scene objects (sceneObjects)");
			}
			// as the client does on a scene load: the analysis on the buildings worker, taken on this (the client) thread
			plugin.buildingLights.rebuild();
			long until = System.nanoTime() + 60_000_000_000L;
			while (plugin.buildingLights.getWalls() == null)
			{
				if (System.nanoTime() > until)
				{
					throw new IllegalStateException("buildings=rebuild: no walls after 60 s (the buildings worker threw?)");
				}
				java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
				plugin.buildingLights.apply();
			}
		}
		else
		{
			plugin.buildingLights.restore(top, (BuildingDetector.Result) d.get("buildings.result"),
				(BuildingDetector.Result) d.get("buildings.coarse"), (RoomMap) d.get("buildings.rooms"),
				(WallMap) d.get("buildings.walls"), (byte[]) d.get("buildings.floors"),
				(com.midonarhel.lumina.visibility.RoofMap) d.get("buildings.roofs"), (boolean[]) d.get("buildings.drawnWalls"));
		}
		WallMap walls = plugin.buildingLights.getWalls();
		if (!options.lampRoofs)
		{
			BuildingLights b = plugin.buildingLights;
			b.restore(top, b.getResult(), b.getCoarse(), b.getRooms(), walls, b.getFloors(), null, b.getDrawnWalls());
		}
		for (int[] t : options.probes)
		{
			int x = t[0] - dump.manifest.baseX + com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET;
			int y = t[1] - dump.manifest.baseY + com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET;
			int bits = walls == null ? 0 : (walls.edge(0, x, y, WallMap.NORTH) ? WallMap.NORTH : 0)
				| (walls.edge(0, x, y, WallMap.EAST) ? WallMap.EAST : 0) | (walls.edge(0, x, y, WallMap.SOUTH) ? WallMap.SOUTH : 0)
				| (walls.edge(0, x, y, WallMap.WEST) ? WallMap.WEST : 0) | walls.get(0, x, y) & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE);
			// as LampWalls packs a tile for lamp.glsl: N 1, E 2, S 4, W 8, the diagonals 16 and 32
			System.out.printf("lamp bits (buildings %s) at %d,%d plane 0: %d%n", options.buildings, t[0], t[1], bits);
		}

		// the zones into buffers of the loader's pool, then swapped in as a load's
		for (int v = 0; v < dump.views.size(); ++v)
		{
			int[] view = dump.views.get(v);
			Zone[][] zones = new Zone[view[1]][view[2]];
			for (int x = 0; x < view[1]; ++x)
			{
				for (int z = 0; z < view[2]; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
			for (ZoneSnapshot s : dump.zones.get(v))
			{
				s.restore(zones[s.x][s.z], plugin.sceneLoader);
			}
			if (view[0] == WorldView.TOPLEVEL)
			{
				plugin.sceneLoader.restore(top, zones, (WaterMap) d.get("waterMap"), (FogGround) d.get("fogGround"));
				plugin.sceneLoader.swap(top);
			}
			else
			{
				plugin.sceneLoader.restoreSub(view[0], zones);
			}
		}
		VoxelsRestore.restore(plugin.voxelBuilder, d.get("voxels"));
		plugin.framesSinceSwap = 1000;
		restoreState();

		for (FrameTape.Event e : dump.tape.events)
		{
			if (e.type == FrameTape.PRE && e.worldView == WorldView.TOPLEVEL)
			{
				System.arraycopy(e.floats, 0, camera, 0, 5);
				break;
			}
		}
	}

	/** Draws the warm-up and the dump's frame, and gives the frame as presented. */
	public BufferedImage render()
	{
		SceneDump.Manifest m = dump.manifest;
		long step = (long) (options.frameSeconds * 1e9);
		long warmup = (long) (options.warmupSeconds * 1e9);
		int frames = (int) Math.max(1, warmup / step);
		for (int i = frames; i >= 0; --i)
		{
			long back = i * step;
			Clock.set(m.millis - back / 1_000_000, dumpNanos - back);
			if (i == 0)
			{
				restoreState();
			}
			frame();
		}
		return readBack();
	}

	/** One more frame at the dump's time (after a setting changed, say) and the result. */
	public BufferedImage renderAgain()
	{
		SceneDump.Manifest m = dump.manifest;
		Clock.set(m.millis, dumpNanos);
		restoreState();
		frame();
		return readBack();
	}

	/**
	 * GPU and CPU time of the dump's frame, drawn {@code frames} more times at the dump's time: per frame the GPU's
	 * elapsed time (GL_TIME_ELAPSED around the whole frame) and the CPU time of the draw calls. Gives
	 * {gpu median, gpu min, cpu median, cpu min} in ms.
	 */
	public double[] time(int frames)
	{
		SceneDump.Manifest m = dump.manifest;
		int query = glGenQueries();
		double[] gpu = new double[frames], cpu = new double[frames];
		for (int i = 0; i < frames; ++i)
		{
			Clock.set(m.millis, dumpNanos);
			restoreState();
			glFinish();
			glBeginQuery(GL_TIME_ELAPSED, query);
			long t0 = System.nanoTime();
			frame();
			cpu[i] = (System.nanoTime() - t0) / 1e6;
			glEndQuery(GL_TIME_ELAPSED);
			gpu[i] = glGetQueryObjecti64(query, GL_QUERY_RESULT) / 1e6;
		}
		glDeleteQueries(query);
		java.util.Arrays.sort(gpu);
		java.util.Arrays.sort(cpu);
		return new double[]{gpu[frames / 2], gpu[0], cpu[frames / 2], cpu[0]};
	}

	/**
	 * The frame state a warm-up doesn't settle as the dump had it (SceneDump.STATE): before the warm-up, so it starts
	 * from there, and again before the dump's frame, so that frame has it exactly.
	 */
	private void restoreState()
	{
		for (String[] f : SceneDump.stateFields(plugin))
		{
			if (!options.restoreWeather && (f[0].equals("weather") || f[0].equals("weatherField")))
			{
				continue;
			}
			String key = "state." + f[0] + "." + f[1];
			if (!dump.derived.containsKey(key))
			{
				continue;
			}
			Object v = dump.derived.get(key);
			Object owner = SceneDump.stateOwner(plugin, f[0]);
			if (v == null && PlainData.field(owner.getClass(), f[1]).getType().isPrimitive())
			{
				continue;
			}
			PlainData.set(owner, f[1], v);
		}
		// the weather field's textures take the state again
		PlainData.set(plugin.weather.getField(), "uploaded", false);
		if (options.snowCover != null)
		{
			float[][] state = (float[][]) PlainData.get(plugin.weather.getField(), "state");
			java.util.Arrays.fill(state[com.midonarhel.lumina.weather.WeatherField.SNOW_COVER], options.snowCover);
			// the field is blurred and uploaded on its next update, which is this frame's
			PlainData.set(plugin.weather, "fieldDt", 1f);
		}
		Double age = dump.manifest.state.get("water.age");
		if (age != null)
		{
			PlainData.set(plugin.water, "startNanos", Clock.nanos() - age.longValue());
		}
	}

	/** One frame: the tape played through the plugin's callbacks. */
	public void frame()
	{
		boolean drawnAll = false;
		for (FrameTape.Event e : dump.tape.events)
		{
			Scene scene = world.scenes.get(e.worldView);
			int[] a = e.ints;
			switch (e.type)
			{
				case FrameTape.PRE:
				{
					Set<Integer> roofs = null;
					if (a[7] >= 0)
					{
						roofs = new HashSet<>();
						for (int i = 0; i < a[7]; ++i)
						{
							roofs.add(a[8 + i]);
						}
					}
					float[] f = cameraFor(e);
					plugin.preSceneDraw(scene, projection(e.projection), f[0], f[1], f[2], f[3], f[4], a[0], a[1], a[2], roofs);
					break;
				}
				case FrameTape.ZONE_OPAQUE:
					if (freeCamera && e.worldView == WorldView.TOPLEVEL)
					{
						if (!drawnAll)
						{
							drawAllOpaque(scene);
							drawnAll = true;
						}
						break;
					}
					plugin.drawZoneOpaque(null, scene, a[0], a[1]);
					break;
				case FrameTape.ZONE_ALPHA:
					plugin.drawZoneAlpha(null, scene, a[0], a[1], a[2]);
					break;
				case FrameTape.PASS:
					plugin.drawPass(projection(e.projection), scene, a[0]);
					break;
				case FrameTape.DYNAMIC:
					plugin.sceneDraw.drawDynamicModel(a[0], projection(e.projection), scene, model(e.model), a[1], a[2], a[3],
						a[4], a[5], a[6], a[7]);
					break;
				case FrameTape.TEMP:
					plugin.sceneDraw.drawTempModel(projection(e.projection), scene, model(e.model), a[0], a[1], a[2], a[3], a[4],
						a[5], a[6] != 0, a[7] != 0, a[8], a[9], a[10], a[11]);
					break;
				case FrameTape.POST:
					plugin.postSceneDraw(scene);
					break;
				case FrameTape.DRAW:
					plugin.draw(a[0]);
					break;
				default:
					throw new IllegalStateException("tape event " + e.type);
			}
		}
	}

	/** The free camera's opaque zones: every one of the top level scene's (the client culled to its own frustum). */
	private void drawAllOpaque(Scene scene)
	{
		// the zones the dump has (a scene made in code has only some: SyntheticDump, FacesDump)
		for (int v = 0; v < dump.views.size(); ++v)
		{
			if (dump.views.get(v)[0] != net.runelite.api.WorldView.TOPLEVEL)
			{
				continue;
			}
			for (com.midonarhel.lumina.scene.ZoneSnapshot z : dump.zones.get(v))
			{
				plugin.drawZoneOpaque(null, scene, z.x, z.z);
			}
		}
	}

	/**
	 * The camera of a scene draw: the dump's, or turned about the focal point to the overridden yaw and pitch (the
	 * client's orbit: the camera is the focal point less its distance along the view direction).
	 */
	private float[] cameraFor(FrameTape.Event e)
	{
		float[] f = e.floats.clone();
		if (!freeCamera || e.worldView != WorldView.TOPLEVEL)
		{
			return f;
		}
		SceneDump.Manifest m = dump.manifest;
		float dx = camera[0] - m.focalX, dy = camera[1] - m.focalY, dz = camera[2] - m.focalZ;
		float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		double turn = unitsPerTurn(dump);
		double yaw = world.cameraYaw * Math.PI * 2 / turn, pitch = world.cameraPitch * Math.PI * 2 / turn;
		float fx = m.focalX, fz = m.focalZ;
		if (options.focus != null)
		{
			fx = (options.focus[0] - m.baseX) * 128 + 64;
			fz = (options.focus[1] - m.baseY) * 128 + 64;
		}
		// (orbit is checked against a dump's own camera by OfflineRenderTest)
		f[0] = fx + (float) (dist * orbit(yaw, pitch)[0]);
		f[1] = m.focalY + (float) (dist * orbit(yaw, pitch)[1]);
		f[2] = fz + (float) (dist * orbit(yaw, pitch)[2]);
		f[3] = (float) pitch;
		f[4] = (float) yaw;
		return f;
	}

	/**
	 * The client's camera units per turn: 2048 in the old clients, 16384 in this one (getCameraYaw 15349 = 5.89 rad in
	 * the first dump). Read off the dump: the units whose pitch matches the radians the tape's scene draw was given.
	 */
	public static double unitsPerTurn(SceneDump.Loaded dump)
	{
		for (FrameTape.Event e : dump.tape.events)
		{
			if (e.type == FrameTape.PRE && e.worldView == WorldView.TOPLEVEL && dump.manifest.cameraPitch != 0)
			{
				double perTurn = dump.manifest.cameraPitch * Math.PI * 2 / e.floats[3];
				return Math.abs(perTurn - 16384) < Math.abs(perTurn - 2048) ? 16384 : 2048;
			}
		}
		return 2048;
	}

	/** The unit offset of the camera from the focal point for the client's yaw and pitch (radians). */
	public static double[] orbit(double yaw, double pitch)
	{
		// the view is rotateX(pitch) rotateY(yaw) (LuminaPlugin.preSceneDrawToplevel), looking along +z: its forward
		// direction in the world is (-sin yaw cos pitch, sin pitch, cos yaw cos pitch), the camera sits behind it
		return new double[]{Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)};
	}

	private Projection projection(int index)
	{
		if (index < 0)
		{
			return null;
		}
		return projections.computeIfAbsent(index, i ->
		{
			FrameTape.Proj p = dump.tape.projections.get(i);
			float[] matrix = p.matrix != null ? p.matrix : new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
			Fakes.Answer project = args ->
			{
				float[] out = args.length > 3 ? (float[]) args[3] : new float[3];
				return FrameTape.project(p.affine, (Float) args[0], (Float) args[1], (Float) args[2], out);
			};
			return Fakes.of(FloatProjection.class, Map.of("getProjection", matrix, "project", project));
		});
	}

	private Model model(int index)
	{
		return models.computeIfAbsent(index, i -> ModelFake.of(dump.tape.models.get(i)));
	}

	/** The offscreen framebuffer as an image, top row first. */
	public BufferedImage readBack()
	{
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}
		for (int i = 0; i < pixels.length; ++i)
		{
			pixels[i] &= 0xFFFFFF;
		}
		return image;
	}

	/** A setting changed, as the plugin hears it: the debug view and the like recompile the programs. */
	public void set(String key, String value)
	{
		world.settings.put(key, value);
		if (key.equals("debugView") || key.equals("uiScalingMode") || key.equals("colorBlindMode"))
		{
			plugin.programs.reloadShaders();
		}
	}

	@Override
	public void close()
	{
		plugin.tempModels.free();
		glDeleteFramebuffers(fbo);
		glDeleteRenderbuffers(colorRb);
		gl.close();
		Clock.release();
		com.midonarhel.lumina.sky.DayCycle.heldHour = Double.NaN;
	}
}
