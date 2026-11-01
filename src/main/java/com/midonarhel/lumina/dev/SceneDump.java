package com.midonarhel.lumina.dev;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import java.awt.Dimension;
import java.awt.geom.AffineTransform;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Preferences;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigItem;

/**
 * The scene dump: one frame of the live client written out whole, so the offline renderer (docs/offline-render.md)
 * draws it again through the real upload and shader code and the result can be checked against what the client showed.
 * {@code ::runelight scenedump name} arms it; the next frame is recorded (the frame tape, FrameTape) and, after it is
 * presented, written to ~/.runelite/lumina/dumps/name/:
 * <ul>
 * <li>manifest.json: the time, the camera and viewport, the player, every setting, what the client said this frame</li>
 * <li>zones.bin.gz: every drawn zone of every world view, read back from its buffers (ZoneSnapshot)</li>
 * <li>dynamic.bin.gz: the frame tape, the callbacks with the dynamic and temporary models</li>
 * <li>derived.bin.gz: what the scene's loads and workers derived (voxels, water map, fog ground, walls, rooms, lamps)
 * and the scene's own arrays, as plain data (PlainData)</li>
 * <li>textures.bin.gz: the game's textures as the texture array is made from them; ui.bin.gz: the interface's pixels</li>
 * <li>reference.png, reference.txt: the frame as presented (FrameCapture)</li>
 * </ul>
 */
public final class SceneDump
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SceneDump.class);

	// 2: zone vertices of 24 bytes (the normal's length), the shading group on dynamic and temporary models, the game's
	// vertex normals with each model
	public static final int VERSION = 2;
	public static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("dumps");

	/** What the client said this frame, the settings and the time: manifest.json. */
	public static final class Manifest
	{
		public int version = VERSION;
		public String name;
		public String takenAt;
		public String renderer = "Lumina (perf-wip)";
		// the renderer's clock at the frame (Clock), the plugin's start, the game clock's time
		public long millis, nanos, startMillis, gameMillis;
		public int cameraYaw, cameraPitch, scale;
		public float focalX, focalY, focalZ;
		// the camera the frame was drawn from (preSceneDraw)
		public float cameraX, cameraY, cameraZ;
		public int canvasWidth, canvasHeight;
		public int viewportX, viewportY, viewportWidth, viewportHeight;
		public boolean stretched;
		public int stretchedWidth, stretchedHeight;
		public double dpiScaleX = 1, dpiScaleY = 1;
		public int skyboxColor, expandedMapLoading, gameCycle;
		public String gameState;
		public double textureBrightness;
		public int soundEffectVolume = 127;
		public int plane, baseX, baseY;
		public boolean instance;
		// x, y, world view (local) and x, y, plane (world); null without a player
		public int[] playerLocal, playerWorld;
		public float hour, sunAltitude;
		public float[] sunDir, moonDir, casterDir;
		public boolean sunIsCaster;
		// the frame tape's index of the skybox model, -1 for none
		public int skyboxModel = -1;
		// every setting, key to value as the config gives it (an enum by name)
		public Map<String, String> settings = new TreeMap<>();
		// the world views with zones in zones.bin.gz, the top level first
		public List<Integer> worldViews = new ArrayList<>();
		// the frame state's few time-integrated scalars (the clouds' and the water's drift), restored by name
		public Map<String, Double> state = new TreeMap<>();
		public int uiWidth, uiHeight;
		// what the plain data left out (PlainData.Writer.skipped)
		public List<String> skipped = new ArrayList<>();
		// the frame tape's largest projection error (FrameTape.Proj.error)
		public float projectionError;
		// lamps.collected is what the scene's lamps gave (SceneLights.collect), without the test light the frame adds
		// after them (LampFrame.collectLights); false in the first dumps, whose list ends with it (load takes it off)
		public boolean lampsWithoutTestLight;
	}

	private final LuminaPlugin plugin;
	// where dumps go (DIR; a test's own directory)
	public Path root = DIR;
	// the last dump's writer, for whoever waits for its files
	public volatile Thread writer;
	private volatile String armed;
	private String name;
	private Manifest manifest;
	private FrameTape tape;
	private int[] uiPixels;

	public SceneDump(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** Dump the next frame as name (client thread). */
	public void arm(String name)
	{
		if (!name.matches("[A-Za-z0-9_.-]+"))
		{
			log.warn("Scene dump name {}: letters, digits, '.', '_' and '-' only", name);
			return;
		}
		armed = name;
		log.info("Scene dump armed: the next frame goes to {}", root.resolve(name));
	}

	/** The top level scene's draw begins (preSceneDraw): the armed dump's frame starts here. */
	public void frameStarting(Scene scene)
	{
		String n = armed;
		if (n == null || tape != null)
		{
			return;
		}
		armed = null;
		name = n;
		tape = new FrameTape();
		Client client = plugin.client;
		manifest = new Manifest();
		manifest.name = n;
		manifest.takenAt = LocalDateTime.now().toString();
		manifest.millis = Clock.millis();
		manifest.nanos = Clock.nanos();
		manifest.startMillis = plugin.startMillis;
		Model skybox = scene.getSkybox();
		manifest.skyboxModel = skybox != null ? tape.model(skybox) : -1;
		plugin.frameTape = tape;
		try
		{
			Files.createDirectories(root.resolve(n));
		}
		catch (IOException ex)
		{
			log.warn("Scene dump {}: can't make its directory", n, ex);
		}
		FrameCapture.capture(client, plugin.drawManager, manifest.renderer, root.resolve(n), "reference");
	}

	/** The frame's interface (draw, before it is uploaded). */
	public void ui(BufferProvider bp)
	{
		if (tape != null && bp != null)
		{
			uiPixels = bp.getPixels().clone();
			manifest.uiWidth = bp.getWidth();
			manifest.uiHeight = bp.getHeight();
		}
	}

	/** The frame is presented (end of draw): collect what it drew from and write it out. */
	public void frameDone()
	{
		if (tape == null)
		{
			return;
		}
		FrameTape t = tape;
		Manifest m = manifest;
		String n = name;
		int[] ui = uiPixels;
		tape = null;
		manifest = null;
		uiPixels = null;
		plugin.frameTape = null;
		try
		{
			long start = System.nanoTime();
			describe(m);
			Path dir = root.resolve(n);
			Files.createDirectories(dir);
			// the zones go straight to their file, read back one at a time: all of them at once are hundreds of MB
			writeZones(dir.resolve("zones.bin.gz"), m);
			byte[] derived = derived(m);
			byte[] textures = textures();
			byte[] dynamic = bytes(t::write);
			for (FrameTape.Proj p : t.projections)
			{
				m.projectionError = Math.max(m.projectionError, p.error);
			}
			byte[] uiBytes = uiBytes(m.uiWidth, m.uiHeight, ui != null ? ui : new int[0]);
			log.info("Scene dump {}: collected in {} ms ({} events, {} models), writing", n, (System.nanoTime() - start) / 1_000_000,
				t.events.size(), t.models.size());
			Thread w = new Thread(() ->
			{
				try
				{
					write(dir, m, null, derived, textures, dynamic, uiBytes);
					log.info("Scene dump written: {}", dir);
				}
				catch (IOException ex)
				{
					log.warn("Scene dump {} not written", n, ex);
				}
			}, "Scene dump");
			writer = w;
			w.start();
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Scene dump {} failed", n, ex);
		}
	}

	private void describe(Manifest m)
	{
		Client client = plugin.client;
		m.gameMillis = plugin.frameGameMillis;
		m.cameraYaw = client.getCameraYaw();
		m.cameraPitch = client.getCameraPitch();
		m.scale = client.getScale();
		m.focalX = client.getCameraFocalPointX();
		m.focalY = client.getCameraFocalPointY();
		m.focalZ = client.getCameraFocalPointZ();
		m.cameraX = plugin.frameCameraX;
		m.cameraY = plugin.frameCameraY;
		m.cameraZ = plugin.frameCameraZ;
		m.canvasWidth = client.getCanvasWidth();
		m.canvasHeight = client.getCanvasHeight();
		m.viewportX = client.getViewportXOffset();
		m.viewportY = client.getViewportYOffset();
		m.viewportWidth = client.getViewportWidth();
		m.viewportHeight = client.getViewportHeight();
		m.stretched = client.isStretchedEnabled();
		Dimension stretched = client.getStretchedDimensions();
		if (stretched != null)
		{
			m.stretchedWidth = stretched.width;
			m.stretchedHeight = stretched.height;
		}
		AffineTransform t = plugin.dpiTransform();
		m.dpiScaleX = t.getScaleX();
		m.dpiScaleY = t.getScaleY();
		m.skyboxColor = client.getSkyboxColor();
		m.expandedMapLoading = client.getExpandedMapLoading();
		m.gameCycle = client.getGameCycle();
		m.gameState = client.getGameState().name();
		TextureProvider tp = client.getTextureProvider();
		m.textureBrightness = tp != null ? tp.getBrightness() : 0;
		Preferences prefs = client.getPreferences();
		if (prefs != null)
		{
			m.soundEffectVolume = prefs.getSoundEffectVolume();
		}
		WorldView wv = client.getTopLevelWorldView();
		m.plane = wv.getPlane();
		m.baseX = wv.getBaseX();
		m.baseY = wv.getBaseY();
		m.instance = wv.getScene().isInstance();
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			LocalPoint lp = player.getLocalLocation();
			WorldPoint wp = player.getWorldLocation();
			m.playerLocal = new int[]{lp.getX(), lp.getY(), lp.getWorldView()};
			m.playerWorld = new int[]{wp.getX(), wp.getY(), wp.getPlane()};
		}
		m.hour = (float) plugin.dayCycle.hour();
		m.sunAltitude = plugin.dayCycle.sunAltitude;
		m.sunDir = plugin.dayCycle.sunDir.clone();
		m.moonDir = plugin.dayCycle.moonDir.clone();
		m.casterDir = plugin.dayCycle.casterDir.clone();
		m.sunIsCaster = plugin.dayCycle.sunIsCaster;
		m.settings = settings(plugin.config);
		// the water's clock as an age: its start is restored as now minus this
		m.state.put("water.age", (double) (Clock.nanos() - (Long) PlainData.get(plugin.water, "startNanos")));
		for (String[] f : stateFields(plugin))
		{
			Object v = PlainData.get(stateOwner(plugin, f[0]), f[1]);
			if (v instanceof Number)
			{
				m.state.put(f[0] + "." + f[1], ((Number) v).doubleValue());
			}
		}
	}

	/**
	 * The frame state a warm-up of a few seconds doesn't settle, owner and field: integrated since the client started
	 * (the clouds' and the water's drift, the wind's phase) or over minutes (the weather's wetness and snow, its eased
	 * cover, fog and light, the weather field around the player). The dump keeps them (derived.bin.gz, "state.owner.field";
	 * the numbers also in the manifest) and the offline renderer puts them back before the warm-up and its last frame.
	 * "*" takes every field of the owner but its GL names and upload bookkeeping.
	 */
	public static final String[][] STATE = {
		{"clouds", "windX"}, {"clouds", "windZ"},
		{"water", "driftX"}, {"water", "driftZ"}, {"water", "strength"},
		{"weather", "cover"}, {"weather", "rainAmount"}, {"weather", "snowAmount"}, {"weather", "fog"}, {"weather", "areaFog"},
		{"weather", "distanceFog"}, {"weather", "cloudWisp"}, {"weather", "cloudDark"}, {"weather", "light"},
		{"weather", "fogTint"}, {"weather", "rainStyle"}, {"weather", "flakeStyle"}, {"weather", "type"},
		{"weather", "fogHeight"}, {"weather", "humidity"}, {"weather", "storm"}, {"weather", "snowiness"},
		{"weather", "rainEffective"}, {"weather", "snowEffective"}, {"weather", "gust"}, {"weather", "windX"},
		{"weather", "windZ"}, {"weather", "windOffsetX"}, {"weather", "windOffsetZ"}, {"weather", "rainFall"},
		{"weather", "snowFall"}, {"weather", "clockWraps"}, {"weather", "wetness"}, {"weather", "snowCover"},
		{"wind", "*"},
		{"weatherField", "*"},
	};

	// what "*" leaves out: GL names, upload state, scratch
	private static final java.util.Set<String> NOT_STATE = java.util.Set.of("texField", "texGround", "texSky", "texSizeX",
		"texSizeZ", "uploaded", "fieldBytes", "groundBytes", "skyBytes", "blurTmp", "corner", "cornerWeight");

	/** STATE with each "*" spelled out: owner, field. */
	public static List<String[]> stateFields(LuminaPlugin plugin)
	{
		List<String[]> out = new ArrayList<>();
		for (String[] s : STATE)
		{
			if (!s[1].equals("*"))
			{
				out.add(s);
				continue;
			}
			for (java.lang.reflect.Field f : PlainData.fields(stateOwner(plugin, s[0]).getClass()))
			{
				if (!NOT_STATE.contains(f.getName()) && !f.isSynthetic())
				{
					out.add(new String[]{s[0], f.getName()});
				}
			}
		}
		return out;
	}

	public static Object stateOwner(LuminaPlugin plugin, String owner)
	{
		switch (owner)
		{
			case "clouds":
				return plugin.clouds;
			case "water":
				return plugin.water;
			case "weather":
				return plugin.weather;
			case "wind":
				return plugin.weather.getWind();
			case "weatherField":
				return plugin.weather.getField();
			default:
				throw new IllegalArgumentException(owner);
		}
	}

	/** Every setting by its key, as the config gives it now: enums by name. */
	public static Map<String, String> settings(LuminaConfig config)
	{
		Map<String, String> out = new TreeMap<>();
		for (Method method : LuminaConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null || method.getParameterCount() != 0)
			{
				continue;
			}
			try
			{
				Object v = method.invoke(config);
				out.put(item.keyName(), v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v));
			}
			catch (ReflectiveOperationException | RuntimeException ex)
			{
				log.debug("setting {} not read", item.keyName(), ex);
			}
		}
		return out;
	}

	private void writeZones(Path file, Manifest m) throws IOException
	{
		List<Integer> views = new ArrayList<>();
		List<LuminaPlugin.SceneContext> contexts = new ArrayList<>();
		views.add(WorldView.TOPLEVEL);
		contexts.add(plugin.root);
		for (int i = 0; i < plugin.subs.length; ++i)
		{
			if (plugin.subs[i] != null)
			{
				views.add(i);
				contexts.add(plugin.subs[i]);
			}
		}
		m.worldViews = views;
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fastGzip(file), 1 << 16)))
		{
			out.writeInt(views.size());
			for (int v = 0; v < views.size(); ++v)
			{
				LuminaPlugin.SceneContext ctx = contexts.get(v);
				out.writeInt(views.get(v));
				out.writeInt(ctx.sizeX);
				out.writeInt(ctx.sizeZ);
				int count = 0;
				for (int x = 0; x < ctx.sizeX; ++x)
				{
					for (int z = 0; z < ctx.sizeZ; ++z)
					{
						count += ZoneSnapshot.dumpable(ctx.zones[x][z]) ? 1 : 0;
					}
				}
				out.writeInt(count);
				for (int x = 0; x < ctx.sizeX; ++x)
				{
					for (int z = 0; z < ctx.sizeZ; ++z)
					{
						ZoneSnapshot s = ZoneSnapshot.of(ctx.zones[x][z], x, z);
						if (s != null)
						{
							s.write(out);
						}
					}
				}
			}
		}
	}

	private static OutputStream fastGzip(Path file) throws IOException
	{
		return new GZIPOutputStream(Files.newOutputStream(file), 1 << 16)
		{
			{
				def.setLevel(java.util.zip.Deflater.BEST_SPEED);
			}
		};
	}

	/** zones.bin.gz's content: per world view its id, size in zones and drawn zones. */
	static byte[] zonesBytes(List<int[]> views, List<List<ZoneSnapshot>> zones) throws IOException
	{
		return bytes(out ->
		{
			out.writeInt(views.size());
			for (int v = 0; v < views.size(); ++v)
			{
				int[] view = views.get(v);
				out.writeInt(view[0]);
				out.writeInt(view[1]);
				out.writeInt(view[2]);
				out.writeInt(zones.get(v).size());
				for (ZoneSnapshot s : zones.get(v))
				{
					s.write(out);
				}
			}
		});
	}

	private byte[] derived(Manifest m) throws IOException
	{
		Map<String, Object> d = new LinkedHashMap<>();
		WorldView wv = plugin.client.getTopLevelWorldView();
		getters(d, "wv.", WorldView.class, wv);
		getters(d, "scene.", Scene.class, wv.getScene());
		for (net.runelite.api.WorldEntity we : wv.worldEntities())
		{
			WorldView sub = we.getWorldView();
			getters(d, "wv" + sub.getId() + ".", WorldView.class, sub);
			getters(d, "scene" + sub.getId() + ".", Scene.class, sub.getScene());
		}
		d.put("waterMap", plugin.clientUploader.water);
		d.put("fogGround", PlainData.get(plugin.volumetricFog, "ground"));
		d.put("voxels", plugin.voxelBuilder.getVoxels());
		var buildings = plugin.getBuildingLights();
		if (buildings != null)
		{
			d.put("buildings.result", buildings.getResult());
			d.put("buildings.coarse", buildings.getCoarse());
			d.put("buildings.rooms", buildings.getRooms());
			d.put("buildings.walls", buildings.getWalls());
			d.put("buildings.floors", buildings.getFloors());
		}
		// what SceneLights.collect gave: the test light is the frame's own (LampFrame.collectLights adds it again)
		List<PointLight> collected = new ArrayList<>(plugin.frameLights);
		collected.removeIf(l -> l == plugin.testLight);
		d.put("lamps.collected", collected);
		m.lampsWithoutTestLight = true;
		List<PointLight> steady = new ArrayList<>();
		if (plugin.sceneLights != null)
		{
			plugin.sceneLights.steadyLamps(steady);
		}
		d.put("lamps.steady", steady);
		for (String[] f : stateFields(plugin))
		{
			d.put("state." + f[0] + "." + f[1], PlainData.get(stateOwner(plugin, f[0]), f[1]));
		}
		return derivedBytes(d, m.skipped);
	}

	/** derived.bin.gz's content: the map as plain data; what it left out is added to skipped. */
	static byte[] derivedBytes(Map<String, Object> d, List<String> skipped) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			PlainData.Writer w = new PlainData.Writer(out);
			w.write(d);
			skipped.addAll(w.skipped);
		}
		return bytes.toByteArray();
	}

	/**
	 * Every no-argument getter of the interface that gives a primitive or an array of them, by name: what the offline
	 * renderer's fake scene and world view answer.
	 */
	static void getters(Map<String, Object> out, String prefix, Class<?> type, Object o)
	{
		if (o == null)
		{
			return;
		}
		for (Method method : type.getMethods())
		{
			if (method.getParameterCount() != 0 || !(method.getName().startsWith("get") || method.getName().startsWith("is")))
			{
				continue;
			}
			Class<?> r = method.getReturnType();
			Class<?> base = r;
			while (base.isArray())
			{
				base = base.getComponentType();
			}
			if (!base.isPrimitive() || base == void.class)
			{
				continue;
			}
			try
			{
				out.put(prefix + method.getName(), method.invoke(o));
			}
			catch (ReflectiveOperationException | RuntimeException ex)
			{
				log.debug("{}{} not read", prefix, method.getName(), ex);
			}
		}
	}

	private byte[] textures() throws IOException
	{
		TextureProvider tp = plugin.client.getTextureProvider();
		if (tp == null)
		{
			return texturesBytes(false, 0, new int[0][], new int[0], new int[0]);
		}
		Texture[] textures = tp.getTextures();
		double brightness = tp.getBrightness();
		int n = textures != null ? textures.length : 0;
		int[][] pixels = new int[n][];
		int[] direction = new int[n], speed = new int[n];
		// as the texture array is made (TextureManager.initTextureArray): at brightness 1, the shader applies it
		tp.setBrightness(1.0d);
		try
		{
			for (int i = 0; i < n; ++i)
			{
				Texture t = textures[i];
				if (t != null)
				{
					int[] p = tp.load(i);
					pixels[i] = p != null ? p.clone() : new int[0];
					direction[i] = t.getAnimationDirection();
					speed[i] = t.getAnimationSpeed();
				}
			}
		}
		finally
		{
			tp.setBrightness(brightness);
		}
		return texturesBytes(true, brightness, pixels, direction, speed);
	}

	/** textures.bin.gz's content: whether there was a provider, its brightness, per id the texture or none. */
	static byte[] texturesBytes(boolean present, double brightness, int[][] pixels, int[] direction, int[] speed)
		throws IOException
	{
		return bytes(out ->
		{
			out.writeBoolean(present);
			if (!present)
			{
				return;
			}
			out.writeDouble(brightness);
			out.writeInt(pixels.length);
			for (int i = 0; i < pixels.length; ++i)
			{
				out.writeBoolean(pixels[i] != null);
				if (pixels[i] != null)
				{
					out.writeInt(direction[i]);
					out.writeInt(speed[i]);
					writeInts(out, pixels[i]);
				}
			}
		});
	}

	/** ui.bin.gz's content. */
	static byte[] uiBytes(int width, int height, int[] pixels) throws IOException
	{
		return bytes(out ->
		{
			out.writeInt(width);
			out.writeInt(height);
			writeInts(out, pixels);
		});
	}

	/** The dump's files into dir (the reference capture is FrameCapture's; zones null: written already). */
	static void write(Path dir, Manifest m, byte[] zones, byte[] derived, byte[] textures, byte[] dynamic, byte[] ui)
		throws IOException
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
		Files.createDirectories(dir);
		Files.write(dir.resolve("manifest.json"), gson.toJson(m).getBytes(StandardCharsets.UTF_8));
		if (zones != null)
		{
			gzip(dir.resolve("zones.bin.gz"), zones);
		}
		gzip(dir.resolve("derived.bin.gz"), derived);
		gzip(dir.resolve("textures.bin.gz"), textures);
		gzip(dir.resolve("dynamic.bin.gz"), dynamic);
		gzip(dir.resolve("ui.bin.gz"), ui);
	}

	/** A dump read back (or made in code) written again, as the client writes one: load(dir) reads it. */
	public static void save(Loaded l, Path dir) throws IOException
	{
		Manifest m = l.manifest;
		m.uiWidth = l.uiWidth;
		m.uiHeight = l.uiHeight;
		write(dir, m, zonesBytes(l.views, l.zones), derivedBytes(l.derived, m.skipped),
			texturesBytes(l.texturePixels != null, l.textureBrightness, l.texturePixels != null ? l.texturePixels : new int[0][],
				l.textureDirection != null ? l.textureDirection : new int[0], l.textureSpeed != null ? l.textureSpeed : new int[0]),
			bytes(l.tape::write), uiBytes(l.uiWidth, l.uiHeight, l.uiPixels != null ? l.uiPixels : new int[0]));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// reading

	/** A dump read back: every part as it was written. */
	public static final class Loaded
	{
		public Path dir;
		public Manifest manifest;
		public FrameTape tape;
		// per world view, the top level first: id, size in zones, the zones
		public final List<int[]> views = new ArrayList<>();
		public final List<List<ZoneSnapshot>> zones = new ArrayList<>();
		public Map<String, Object> derived;
		public double textureBrightness;
		// per texture id: null for none; the pixels at brightness 1, the animation direction and speed
		public int[][] texturePixels;
		public int[] textureDirection, textureSpeed;
		public int uiWidth, uiHeight;
		public int[] uiPixels;
	}

	@SuppressWarnings("unchecked")
	public static Loaded load(Path dir) throws IOException
	{
		Loaded l = new Loaded();
		l.dir = dir;
		l.manifest = new Gson().fromJson(new String(Files.readAllBytes(dir.resolve("manifest.json")), StandardCharsets.UTF_8),
			Manifest.class);
		if (l.manifest.version != VERSION)
		{
			throw new IOException("dump version " + l.manifest.version + ", this reads " + VERSION);
		}
		try (DataInputStream in = gunzip(dir.resolve("zones.bin.gz")))
		{
			int views = in.readInt();
			for (int v = 0; v < views; ++v)
			{
				l.views.add(new int[]{in.readInt(), in.readInt(), in.readInt()});
				int n = in.readInt();
				List<ZoneSnapshot> list = new ArrayList<>(n);
				for (int i = 0; i < n; ++i)
				{
					list.add(ZoneSnapshot.read(in));
				}
				l.zones.add(list);
			}
		}
		try (DataInputStream in = gunzip(dir.resolve("dynamic.bin.gz")))
		{
			l.tape = FrameTape.read(in);
		}
		try (DataInputStream in = gunzip(dir.resolve("derived.bin.gz")))
		{
			l.derived = (Map<String, Object>) new PlainData.Reader(in).read();
		}
		if (!l.manifest.lampsWithoutTestLight && "true".equals(l.manifest.settings.get("testLight")))
		{
			// a first dump's lamps end with the frame's test light (LampFrame.collectLights): the replay adds it itself
			List<PointLight> collected = (List<PointLight>) l.derived.get("lamps.collected");
			if (collected != null && !collected.isEmpty() && isTestLight(collected.get(collected.size() - 1)))
			{
				collected.remove(collected.size() - 1);
			}
			l.manifest.lampsWithoutTestLight = true;
		}
		try (DataInputStream in = gunzip(dir.resolve("textures.bin.gz")))
		{
			// none: the client had no texture provider
			boolean present = in.readBoolean();
			l.textureBrightness = present ? in.readDouble() : 0;
			int n = present ? in.readInt() : 0;
			l.texturePixels = new int[n][];
			l.textureDirection = new int[n];
			l.textureSpeed = new int[n];
			for (int i = 0; i < n; ++i)
			{
				if (in.readBoolean())
				{
					l.textureDirection[i] = in.readInt();
					l.textureSpeed[i] = in.readInt();
					l.texturePixels[i] = readInts(in);
				}
			}
		}
		try (DataInputStream in = gunzip(dir.resolve("ui.bin.gz")))
		{
			l.uiWidth = in.readInt();
			l.uiHeight = in.readInt();
			l.uiPixels = readInts(in);
		}
		return l;
	}

	/** LampFrame.collectLights's test light: five tiles' reach, warm like a torch, intensity 6. */
	private static boolean isTestLight(PointLight p)
	{
		return ((Number) PlainData.get(p, "radius")).floatValue() == 5 * 128 && ((Number) PlainData.get(p, "r")).floatValue() == 1f
			&& ((Number) PlainData.get(p, "g")).floatValue() == 0.72f && ((Number) PlainData.get(p, "b")).floatValue() == 0.42f
			&& ((Number) PlainData.get(p, "intensity")).floatValue() == 6f;
	}

	// ---------------------------------------------------------------------------------------------------------------

	interface Body
	{
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] bytes(Body body) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			body.write(out);
		}
		return bytes.toByteArray();
	}

	private static void gzip(Path file, byte[] data) throws IOException
	{
		try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file)), 1 << 16))
		{
			out.write(data);
		}
	}

	private static DataInputStream gunzip(Path file) throws IOException
	{
		InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)), 1 << 16);
		return new DataInputStream(new BufferedInputStream(in, 1 << 16));
	}

	static void writeInts(DataOutputStream out, int[] a) throws IOException
	{
		ZoneSnapshot.writeInts(out, a);
	}

	static int[] readInts(DataInputStream in) throws IOException
	{
		return ZoneSnapshot.readInts(in);
	}
}
