package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import com.midonarhel.lumina.visibility.Voxelizer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The flood volume (occlusion/flood-volume/math.md): every fixed lamp's light summed into one world-space volume that pixels,
 * transparent faces, fog and rain all read, at a cost that does not depend on how many lamps there are.
 *
 * What is drawn solid is voxelized conservatively at FINE units from the zones' own triangles, by the ranges the frame
 * draws (Zone.pushLevels): levels, hidden roofs out. Each lamp floods out from its origin, semi-Lagrangian: a cell takes
 * the bilinear value of where its ray to the lamp crosses the shell before, times free or not, so light goes in straight
 * lines at a cost of one pass over its box, and can't cross anything closed. Its visibility is kept per cell of each level
 * (light/lamp_volume.glsl reads these), and summed with its colour and falloff into the volume. A read cell is solid (air next to
 * none) when any of its FINE cells is, and the shader divides by the air, so a filtered read takes only free cells, and any two of
 * those are on one side of every surface: light can't be interpolated across a wall.
 *
 * Levels in one texture: fine FINE units a cell round the player, near CELL, mid MID_CELL, all with walls, far FAR_CELL over
 * the whole scene with none. All are toroidal in y and the first three in x and z: a world cell has its texel for as long as it is in its
 * window, so a window's move sends only what comes into it. Two things are told apart (math.md 9): what the texture holds of a level
 * (data), and the rectangle the shaders blend it by (shown), which glides and never holds a cell that a send is writing.
 *
 * A lamp that moves is not in the volume: moving it there is a box of megabytes a frame. The frame's list says which lamps move
 * (PointLight.bakeSlot), and those go per pixel from a short list of uniforms, stopped along a line through the volume's air
 * (lampVolumeLine). A lamp that has moved crosses between that list and the volume by hand-over: its share of the volume goes up or down
 * in HAND_STEPS steps, and a step's boxes all land in one frame, the frame the list changes, so no read sees the lamp twice or not at all.
 * Coming in, the list shows the rest of it. Going out, the list shows it whole where it is now, and takes off, at the place it left, as much
 * of it as the volume still holds there: the same formula, so the two cancel on the frame it starts and the lamp moves at once, with what's left
 * of a step only the difference of the two looks. Its strength is what changes, as any lamp's; the shaders know nothing of it.
 *
 * Everything but GL runs on a worker; the render thread only finds what changed and sends finished boxes.
 */
public final class LampVolume
{
	private static final Logger log = LoggerFactory.getLogger(LampVolume.class);
	public static final int FINE = 16, CELL = 32, MID_CELL = 64, FAR_CELL = 128;
	public static final int SIZE = 160, FINE_Y = 48, NEAR_Y = 48, MID_Y = 40, FAR_Y = 24;
	private static final int L_FINE = 0, L_NEAR = 1, L_MID = 2, L_FAR = 3, LEVELS = 4, WALLED = 3;
	private static final int[] CELLS = {FINE, CELL, MID_CELL, FAR_CELL}, DEPTH = {FINE_Y, NEAR_Y, MID_Y, FAR_Y};
	// a level's first layer in the depth, a gutter either side of it holding the other end's layer, for a filtered read over the seam
	private static final int[] BASE = {1, 1 + FINE_Y + 2, 1 + FINE_Y + 2 + NEAR_Y + 2, 1 + FINE_Y + 2 + NEAR_Y + 2 + MID_Y + 2};
	// A's layers and the flicker's map after them; the same again for B; then the fog's field, the mid level's cells, with its gutters
	private static final int GAIN_AT = BASE[L_FAR] + FAR_Y + 1, LAYERS = GAIN_AT + 1, FOG_AT = 2 * LAYERS + 1, DEPTH_ALL = FOG_AT + MID_Y + 1;
	// the fine grid, all cells world-locked: world x, z from GRID_X, world y from GRID_Y
	private static final int GRID_X = -6144, GRID_Y = -3072;
	private static final int CHUNK = 64, CHUNKS = 40, FINE_LAYERS = 256;
	private static final int COLUMN_LONGS = FINE_LAYERS / 64;
	private static final int FINE_CHUNK_LONGS = CHUNK * CHUNK * COLUMN_LONGS;
	private static final int COARSE_CHUNK_LONGS = CHUNK * CHUNK / 4 * COLUMN_LONGS / 2;
	private static final int MID_CHUNK_LONGS = CHUNK * CHUNK / 16;
	// the far level's window: the scene and its surroundings, fixed in x and z
	private static final int FAR_X = -6144 + 1024;
	// the seed: a lamp's own fixture doesn't stop it within this of its origin
	private static final float SEED = prop("volSeed", 20f);
	// a triangle wholly within this of a lamp is its fixture (a real build knows: PointLight.owner, VoxelCapture.owner)
	private static final float FIXTURE = prop("volFixture", 72f);
	// visibility as read: 0 to 1 between these of the flood's. Below lo is the geometric edge's dark side (math.md 6)
	private static final float SHARP_LO = prop("volLo", 0.3f);
	private static final float SHARP_HI = prop("volHi", 0.75f);
	// the same for the mid level, which the fog reads: its cell's mean of a beam through a door falls short of the beam, and the fog would have a hole there
	private static final float MID_LO = prop("volMidLo", 0.2f), MID_HI = prop("volMidHi", 0.6f);
	// what a solid cell counts for in the bilinear read of the shell before, against a free one's 1: at 1 a surface dims the cells that graze it
	private static final float SOLID = prop("volSolid", 0.85f);
	// passes of the smoothing over the flood's cells, and a cell's own weight against each of its six neighbours'
	private static final int SMOOTH = (int) prop("volSmooth", 2);
	private static final float SMOOTH_SELF = prop("volSmoothSelf", 2);
	// A solid read cell with free fine cells holds what those see, at this share of a free cell's air: nothing to a read that has a free
	// cell, all there is to one that hasn't, a surface in a nook no free cell reaches
	private static final float NOOK = prop("volNook", 1 / 32f);
	private static final float GROUND_DROP = prop("volDrop", 6);
	// how far short of a lamp's reach its light ends in the far level
	private static final float FAR_RIM = prop("volFarRim", 64f);
	// how far above the ground under the player each level starts (y is down), and how far the ground goes before they move
	private static final int[] ABOVE = {640, 1100, 1900};
	private static final int FAR_ABOVE = 1900, GROUND_REACH = 256;
	// how many cells of its way to the lamp a cell touching a surface may pass, when that way leaves the surface at no more than this cosine
	private static final int CRUST = (int) prop("volCrust", 1);
	private static final float GRAZE = prop("volGraze", 0.5f);
	// how far past a lamp's reach the fine window is for it to flood coarsely: a lamp nearer than this has its fine cells ready before it is walked to
	private static final float FINE_MARGIN = 256;
	// the walled windows follow the player in steps of this many units, rounded to the nearest
	private static final int WINDOW_STEP = 128;
	// the cells a shown rectangle keeps off its level's edges: the step off a surface, the step to the light, the filter (math.md 9)
	private static final float INSET = 3.5f;
	// how fast a shown rectangle's edges go, units a second
	private static final float GLIDE = prop("volGlide", 1024f);
	// what the render thread sends a frame, and with nothing shown yet: a scene's load, which waits on all of it
	private static final int UPLOAD_BYTES = 512 * 1024, UPLOAD_BYTES_LOAD = 4096 * 1024;
	// a change of more lamps than this pops rather than eases: their boxes would be sent every frame
	private static final int EASE_LAMPS = 8;
	private static final int THREADS = 4;
	// how far the window goes before a level's lamps' places in H are held relative to a nearer point (half floats): math.md 9
	private static final float ORIGIN_REACH = 2048;
	// the flicker's map: its cells, and how far a lamp's strength may stray from what the volume holds before it is summed again
	private static final int GAIN_SIZE = 64, GAIN_CELL = 256;
	private static final float GAIN_LO = 0.5f, GAIN_HI = 1.6f;
	private static final long EASE_NANOS = 250_000_000L;
	// the most in the moving list, each 2 vec4, a lamp on its way out of the volume takes two; how long a lamp stands before it joins the volume; how many new lamps at once are a new scene
	public static final int MOVERS = 16;
	private static final int STILL_FRAMES = 30, SCENE_LAMPS = 12;
	// how far a lamp may go in a frame and be the same lamp
	private static final float MOVE_REACH = 64;
	// a lamp that has moved crosses to or from the volume in this many steps of its share, one each this long: the door's ease, in the steps a
	// send can afford. The two looks differ on relief, so one step would be a pop of that difference: occlusion/flood-volume/pop-fix/results.md
	private static final int HAND_STEPS = (int) prop("volHandSteps", 4);
	private static final long HAND_NANOS = EASE_NANOS / HAND_STEPS;
	// what one job's steps may send, reckoned from the lamps' reach, and the most that goes in one frame past the frame's send
	private static final int HAND_BYTES = (int) prop("volHandBytes", 2048 * 1024);
	// the offline renderer: a change of strength lands in its own frame (the client's lamps don't jump)
	public static boolean benchSync;
	// the bench: the player's focus moved by this for the windows alone; nothing else in the frame
	public static volatile float atkDX = prop("volAtkDx", 0), atkDZ = prop("volAtkDz", 0), atkDY;

	public int atkQueued()
	{
		return queue.size();
	}

	public long atkQueuedBytes()
	{
		long b = 0;
		for (Upload u : queue)
		{
			b += (u.a.length + u.b.length) * 2L;
		}
		return b;
	}

	public volatile int atkJobs;
	// the bench: faults kept from the game so far; a number of jobs to fail on purpose
	public volatile int atkFailures, atkFailNext;
	public long atkGainNanos;
	private static final boolean GAIN_PBO = prop("volGainPbo", 1) > 0, SEND_PBO = prop("volSendPbo", 1) > 0;
	public long atkSendMax, atkSendSum;
	private long sendNanos;
	private int gainPbo;
	public long atkUpdateNanos, atkSentBytes;

	public void update(LuminaPlugin plugin, List<PointLight> lights, LampWalls walls, LightClusters clusters)
	{
		if (stopped)
		{
			return;
		}
		long t0 = System.nanoTime();
		try
		{
			update0(plugin, lights, walls, clusters);
		}
		catch (RuntimeException e)
		{
			// the frame goes on with what the volume holds: lights are not worth a game
			++atkFailures;
			stopped = ++frameFaults >= MAX_TRIES;
			log.error("Lamp volume: the frame's update failed ({} of {}), the lamps stay as they are", frameFaults, MAX_TRIES, e);
		}
		atkUpdateNanos = System.nanoTime() - t0;
	}

	public boolean atkWindowsWait()
	{
		return pending;
	}

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "lamp-volume");
		t.setDaemon(true);
		return t;
	});
	private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r ->
	{
		Thread t = new Thread(r, "lamp-volume-pool");
		t.setDaemon(true);
		return t;
	});

	// a bench's setting, from a property or LUMINA_<NAME>
	private static float prop(String name, float value)
	{
		String v = System.getProperty("lumina." + name);
		v = v != null ? v : System.getenv("LUMINA_" + name.toUpperCase());
		return v != null ? Float.parseFloat(v) : value;
	}

	/** A lamp's visibility per cell of a level, in a box of the level's grid. */
	private static final class Grid
	{
		byte[] vis, before, old;
		int x, y, z, w, h, d;
		// where old lay
		int oldX, oldY, oldZ, oldW, oldH, oldD;
		// the box, in this grid, x0 y0 z0 x1 y1 z1, that a flood changed: null for all of it
		int[] change;
	}

	/** One lamp as the volume holds it. */
	private static final class Lamp
	{
		float x, y, z, r, ox, oz;
		// colour times strength as summed; as the frame's list has it
		float cr, cg, cb;
		float nr, ng, nb;
		float gain = 1;
		// the fog at it (VolumetricFog.lampShare), as summed
		float fog = 1;
		boolean hidden;
		boolean seen;
		// sent: the shaders see it in the volume
		boolean inField;
		// a hand-over: steps of HAND_STEPS it is summed at, and as the shaders see it; its whole colour; a step on its way; when the last began
		int steps = HAND_STEPS, shownSteps = HAND_STEPS;
		float fr, fg, fb;
		boolean stepping;
		long stepAt;
		// it left this place: the lamp's now at atX, atY, atZ, and its share stays here until it is handed over
		boolean out;
		float atX, atY, atZ;
		final Grid[] grid = {new Grid(), new Grid(), new Grid()};
		long easeFrom;
		boolean flood, fineOnly, asleep;
	}

	/** A lamp not in the volume yet: the frames it has stood here. */
	private static final class Standing
	{
		int frames;
		long lastFrame;
		boolean moved;
	}

	/** The cells a run of a zone's triangles marks. */
	private static final class Segment
	{
		int level, rid;
		boolean tail; // past the level's last roof: drawn whatever is hidden
		int[] cells;
		int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		boolean shown;
	}

	private static final class ZoneCells
	{
		int[] triangles;
		Segment[] segments;
		// how much of the fixtures' list this was made with
		int points;
	}

	private static final class Upload
	{
		int level, x, y, z, w, h, d;
		short[] a, b, fog;
		// goes in the same frame as the next; the last of a job's hand-over
		boolean joined, handEnd;
	}

	// a walking swap: what's shown stays in texA, moved by shift in the shaders, frozen as it was, while the new scene's volume is made in texBack; they
	// change places when all of it is there, so a lamp in both scenes neither goes out nor changes (results2.md)
	private int texBack;
	private boolean frozen;
	private int builtBaseX, builtBaseY, frontBaseX, frontBaseY;
	private boolean baseKnown;
	private final float[] shift = new float[3], frontShown = new float[LEVELS * 8], frontOrigin = new float[LEVELS * 4], frontFar = new float[4], frontGainAt = new float[2];
	public volatile int atkFlips;
	public static volatile float atkShiftX;
	private int texA, fboBlend, programBlend, vaoBlend, uniBlendShift;
	// per level, world x, y, z: the window the worker sums for, and what the texture holds (null: nothing)
	private final int[][] window = new int[LEVELS][3];
	private final int[][] data = new int[LEVELS][];
	// per level, what the shaders blend it by, min x y z 0 max x y z 0
	private final float[] shown = new float[LEVELS * 8];
	// per level the world place H's lamps are relative to
	private final float[] origin = new float[LEVELS * 4];
	// the far level's corner and whether any lamp is on a hidden storey
	private final float[] farInfo = new float[4];
	private final float[] gainAt = new float[2];
	private boolean anyHidden;
	private static LampVolume current;

	private final Map<Long, Lamp> lamps = new HashMap<>();
	private final Map<Long, Standing> standing = new HashMap<>();
	private final Map<Long, ZoneCells> zones = new HashMap<>();
	private final long[][] fine = new long[CHUNKS * CHUNKS][], coarse = new long[CHUNKS * CHUNKS][], mid = new long[CHUNKS * CHUNKS][];
	private int[][][] seen;
	private int minLevel = -1, level = -1, maxLevel = -1;
	private Set<Integer> hidden = new HashSet<>();
	private boolean windowSet;
	private boolean sentAll;
	private Future<?> job;
	private volatile List<Upload> done;
	private volatile boolean doneEverything;
	private final java.util.ArrayDeque<Upload> queue = new java.util.ArrayDeque<>();
	// a job's boxes wait in the queue: nothing of them is sent until the shown rectangles lie in what stays
	private boolean pending;
	// the lamps that are in the volume when the queue is empty, those that have left it; where those were
	private final List<Lamp> arriving = new ArrayList<>(), jobArriving = new ArrayList<>();
	private final List<float[]> leaving = new ArrayList<>();
	// the steps of the job at work, and of the jobs whose boxes wait, in order
	private List<Lamp> jobHanded = new ArrayList<>();
	private final java.util.ArrayDeque<List<Lamp>> flips = new java.util.ArrayDeque<>();
	private long frame, lastNanos;
	private float windowGround;
	private ShortBuffer stage;
	private boolean anyLit;
	// A job that fails leaves what it was doing half done, on a worker: the volume keeps showing what it holds, every lamp floods again and everything is
	// summed again, at once, then after a wait, and past MAX_TRIES not until the next scene. Lamps the job brought show what the moving list can show.
	private static final int MAX_TRIES = 3;
	private static final long[] TRY_AFTER = {0, 1_000_000_000L, 4_000_000_000L};
	private int tries, frameFaults;
	private long retryAt;
	private boolean retryAll, stopped;
	private LuminaPlugin.SceneContext gaveUpOn;
	private boolean gainSent;
	private final float[] gainW = new float[GAIN_SIZE * GAIN_SIZE], gainG = new float[GAIN_SIZE * GAIN_SIZE];
	private final ShortBuffer gainStage = BufferUtils.createShortBuffer(GAIN_SIZE * GAIN_SIZE * 4);
	// the frame's moving lamps: x y z reach, r g b 0; a reach under 0 lights no surface (a hidden storey)
	private final float[] movers = new float[MOVERS * 8];
	private int moverCount;
	private final List<float[]> moverList = new ArrayList<>(), moverPlaces = new ArrayList<>();
	private float[] lastMovers = new float[0];
	// the bench's tally
	private volatile long lastJobNanos;
	public static volatile String lastJob = "";

	public static LampVolume current()
	{
		return current;
	}

	public boolean busy()
	{
		return job != null && !job.isDone();
	}

	private static final String BLEND_VERT = "#version 330 core\nvoid main() { gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0); }\n";
	private static final String BLEND_FRAG = "#version 330 core\nuniform vec3 shift;\nout vec4 color;\nvoid main() { color = vec4(shift, 0.0); }\n";

	private void init()
	{
		texA = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texA);
		// A in the first half of the depth, B in the second (light/lamp_volume.glsl)
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, SIZE, SIZE, DEPTH_ALL, 0, GL_RGBA, GL_HALF_FLOAT, (ShortBuffer) null);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// the walled levels are toroidal: a world cell is at its place modulo SIZE, so a window moves by sending what comes into it
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_3D, 0);
		stage = BufferUtils.createShortBuffer(4 * SIZE * SIZE * 32);
		// the origin's move: a blend, no read of the texture (rebase)
		int vs = glCreateShader(GL_VERTEX_SHADER), fs = glCreateShader(GL_FRAGMENT_SHADER);
		glShaderSource(vs, BLEND_VERT);
		glCompileShader(vs);
		glShaderSource(fs, BLEND_FRAG);
		glCompileShader(fs);
		programBlend = glCreateProgram();
		glAttachShader(programBlend, vs);
		glAttachShader(programBlend, fs);
		glLinkProgram(programBlend);
		glDeleteShader(vs);
		glDeleteShader(fs);
		uniBlendShift = glGetUniformLocation(programBlend, "shift");
		vaoBlend = glGenVertexArrays();
		fboBlend = glGenFramebuffers();
		for (int i = 0; i < LEVELS; ++i)
		{
			emptyShown(i);
		}
		farInfo[0] = FAR_X;
		farInfo[1] = FAR_X;
		current = this;
	}

	public void free()
	{
		if (texA != 0)
		{
			glDeleteTextures(texA);
			if (texBack != 0)
			{
				glDeleteTextures(texBack);
				texBack = 0;
			}
			frozen = false;
			baseKnown = false;
			Arrays.fill(shift, 0);
			glDeleteProgram(programBlend);
			glDeleteVertexArrays(vaoBlend);
			glDeleteFramebuffers(fboBlend);
			if (gainPbo != 0)
			{
				glDeleteBuffers(gainPbo);
				gainPbo = 0;
			}
			texA = 0;
		}
		lamps.clear();
		zones.clear();
		standing.clear();
		seen = null;
		sentAll = false;
		windowSet = false;
		pending = false;
		queue.clear();
		arriving.clear();
		flips.clear();
		jobHanded = new ArrayList<>();
		tries = frameFaults = 0;
		holding = false;
		retryAt = 0;
		retryAll = stopped = false;
		gaveUpOn = null;
		Arrays.fill(data, null);
		Arrays.fill(fine, null);
		Arrays.fill(coarse, null);
		Arrays.fill(mid, null);
	}

	public void bind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, texA);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Per level, its shown rectangle: min then max, vec4s. */
	public float[] shown()
	{
		return frozen ? frontShown : shown;
	}

	public float[] origins()
	{
		return frozen ? frontOrigin : origin;
	}

	public float[] farInfo()
	{
		return frozen ? frontFar : farInfo;
	}

	public float[] gainAt()
	{
		return frozen ? frontGainAt : gainAt;
	}

	public float[] shift()
	{
		return shift;
	}

	// the back texture, made the first time it's needed
	private void allocateBack()
	{
		if (texBack != 0)
		{
			return;
		}
		texBack = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texBack);
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, SIZE, SIZE, DEPTH_ALL, 0, GL_RGBA, GL_HALF_FLOAT, (ShortBuffer) null);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_3D, 0);
	}

	private void readBase(LuminaPlugin plugin)
	{
		try
		{
			net.runelite.api.WorldView wv = plugin.client.getTopLevelWorldView();
			builtBaseX = wv.getBaseX();
			builtBaseY = wv.getBaseY();
			baseKnown = true;
		}
		catch (RuntimeException e)
		{
			// the bench's fakes
			builtBaseX = builtBaseY = 0;
			baseKnown = true;
		}
	}

	public float[] movers()
	{
		return movers;
	}

	public int moverCount()
	{
		return moverCount;
	}

	/**
	 * A frame's lamps: send what the worker finished, find what changed since what the volume holds, and start the worker on
	 * it unless it is at work: then the next frame looks again.
	 */
	public void update0(LuminaPlugin plugin, List<PointLight> lights, LampWalls walls, LightClusters clusters)
	{
		if (texA == 0)
		{
			init();
		}
		++frame;
		long nanos = System.nanoTime();
		float units = lastNanos == 0 || benchSync ? 1e9f : Math.min(0.1f, (nanos - lastNanos) / 1e9f) * GLIDE;
		lastNanos = nanos;
		// a big job, lamps by the hundred going on or off, goes at a load's pace or it's seconds of a wave across the world
		drain(data[L_FAR] != null && atkQueuedBytes() < 8 * UPLOAD_BYTES_LOAD / 4 ? UPLOAD_BYTES : UPLOAD_BYTES_LOAD, units);
		boolean idle = job == null || job.isDone();
		if (job != null && idle)
		{
			finish();
			if (benchSync && doneEverything)
			{
				drain(Integer.MAX_VALUE, units);
			}
		}
		LuminaPlugin.SceneContext ctx = plugin.root;
		idle &= ctx != null && ctx.zones != null && ctx.zones.length > 0 && nanos - retryAt >= 0 && ctx != gaveUpOn;
		// a new scene's lamps come over many frames, its scene job's: one job takes them all (hold)
		if (idle && !benchSync)
		{
			idle = !hold(plugin, ctx, lights);
		}
		// the lamps
		boolean strengths = false;
		List<Lamp> added = new ArrayList<>();
		List<PointLight> newcomers = new ArrayList<>();
		for (Lamp l : lamps.values())
		{
			l.seen = false;
		}
		float[] o = new float[2];
		boolean lit = false;
		moverList.clear();
		moverPlaces.clear();
		for (int i = 0, n = lights.size(); i < n; ++i)
		{
			PointLight p = lights.get(i);
			long key = key(p);
			Lamp l = lamps.get(key);
			if (l == null)
			{
				newcomers.add(p);
				continue;
			}
			l.seen = true;
			l.out = false;
			l.asleep = false;
			float s = p.intensity;
			float nr = p.r * s, ng = p.g * s, nb = p.b * s;
			boolean hand = handing(l);
			if (hand)
			{
				l.fr = nr;
				l.fg = ng;
				l.fb = nb;
			}
			if (!l.inField && data[L_FAR] != null)
			{
				mover(p, clusters, hand ? rest(l) : 1, hand);
			}
			if (!idle)
			{
				continue;
			}
			// a flicker doesn't touch the volume: it goes on its gain map (updateGain). A real change of strength does.
			float was = l.cr + l.cg + l.cb, is = nr + ng + nb;
			float ratio = was > 0 ? is / was : 0;
			if (hand)
			{
				// summed as its steps say: step() changes it
				l.gain = 1;
			}
			else if (ratio > GAIN_LO && ratio < GAIN_HI && !l.flood)
			{
				l.gain = ratio;
			}
			else
			{
				l.gain = 1;
				l.nr = nr;
				l.ng = ng;
				l.nb = nb;
			}
			lit |= s > 0;
			boolean h = clusters.onHiddenStorey(p);
			float fog = plugin.volumetricFog.lampShare(l.x, l.y, l.z);
			if (h != l.hidden || Math.abs(fog - l.fog) > 0.02f * Math.max(fog, 0.1f))
			{
				l.hidden = h;
				l.fog = fog;
				strengths = true;
				l.cr = -1;
			}
			strengths |= l.nr != l.cr || l.ng != l.cg || l.nb != l.cb;
		}
		// a lamp gone from its place with one new next to it has moved: it stays there until it's handed over
		Map<PointLight, Lamp> moved = newcomers.isEmpty() || data[L_FAR] == null ? Map.of() : moved(newcomers);
		// lamps not in the volume: those that stand join it, those that move stay out
		List<Lamp> gone = new ArrayList<>();
		if (idle)
		{
			for (Iterator<Lamp> it = lamps.values().iterator(); it.hasNext(); )
			{
				Lamp l = it.next();
				if (l.out && l.steps == 0 && !l.stepping)
				{
					// handed over: the volume holds none of it
					it.remove();
				}
				else if (!l.seen && !l.out)
				{
					if (sentAll && l.inField && !holding)
					{
						// Off, not gone: the client's night-only lamps, a couple of hundred, go off and on together as the player goes out of doors and in.
						// It stays with its floods, strength nothing, and comes back without flooding or waiting (flood-volume/live/results2.md)
						l.asleep = true;
						l.nr = l.ng = l.nb = 0;
						strengths |= l.cr + l.cg + l.cb > 0;
					}
					else
					{
						gone.add(l);
						it.remove();
					}
				}
			}
		}
		for (Lamp l : gone)
		{
			leaving.add(new float[]{l.x, l.y, l.z});
		}
		boolean newScene = !sentAll;
		if (idle && !newScene)
		{
			int fixed = 0;
			for (PointLight p : newcomers)
			{
				fixed += p.bakeSlot >= 0 && !moved.containsKey(p) && !movedHere(p, gone) ? 1 : 0;
			}
			// in the client a scene is known by its zones (hold): lamps by the dozen coming on at once, night-only ones the first time indoors, aren't one
			newScene = benchSync && fixed >= SCENE_LAMPS;
		}
		if (idle && newScene)
		{
			endHandOvers();
		}
		// the list holds a lamp and what it takes off at the place it left; past that it shows what the volume doesn't hold
		int leavingPlaces = 0;
		for (Lamp l : lamps.values())
		{
			leavingPlaces += l.out && l.shownSteps > 0 ? 1 : 0;
		}
		boolean subtract = moverList.size() + newcomers.size() + leavingPlaces <= MOVERS;
		List<Lamp> handed = new ArrayList<>();
		float handBytes = 0;
		for (PointLight p : newcomers)
		{
			long key = key(p);
			Standing st = standing.computeIfAbsent(key, k -> new Standing());
			st.lastFrame = frame;
			++st.frames;
			Lamp left = moved.get(p);
			st.moved |= left != null || movedHere(p, gone);
			int need = p.bakeSlot >= 0 && !st.moved ? 0 : benchSync && !st.moved ? 1 : STILL_FRAMES;
			boolean hand = st.moved && !newScene && data[L_FAR] != null;
			if (idle && (newScene || st.frames > need) && (!hand || handed.isEmpty() || handBytes + handCost(p.radius) <= HAND_BYTES))
			{
				Lamp l = new Lamp();
				l.x = p.x;
				l.y = p.y;
				l.z = p.z;
				l.r = Math.abs(p.radius);
				o[0] = p.ox;
				o[1] = p.oz;
				if (walls != null)
				{
					walls.origin(p.plane, p.ox, p.oz, o);
				}
				l.ox = o[0];
				l.oz = o[1];
				l.flood = true;
				l.seen = true;
				float s = p.intensity;
				l.nr = p.r * s;
				l.ng = p.g * s;
				l.nb = p.b * s;
				l.hidden = clusters.onHiddenStorey(p);
				l.fog = plugin.volumetricFog.lampShare(l.x, l.y, l.z);
				l.cr = -1;
				lit |= s > 0;
				strengths = true;
				lamps.put(key, l);
				added.add(l);
				if (hand)
				{
					l.fr = l.nr;
					l.fg = l.ng;
					l.fb = l.nb;
					l.steps = 0;
					l.shownSteps = 0;
					step(l, 1, nanos);
					handed.add(l);
					handBytes += handCost(l.r);
				}
				else if (!newScene)
				{
					l.shownSteps = 0;
				}
				if (!newScene)
				{
					// lit by the list until it shows
					mover(p, clusters, hand ? rest(l) : 1, hand);
				}
			}
			else if (!newScene)
			{
				mover(p, clusters, left != null && !subtract ? rest(left) : 1, left != null);
			}
		}
		if (idle && !newScene)
		{
			strengths |= steps(nanos, handed, handBytes);
		}
		for (Lamp l : lamps.values())
		{
			if (subtract && l.out && l.shownSteps > 0)
			{
				float r = l.hidden ? -l.r : l.r, share = -l.shownSteps / (float) HAND_STEPS;
				moverList.add(new float[]{l.x, l.y, l.z, r, l.fr * share, l.fg * share, l.fb * share, (l.fr + l.fg + l.fb) * l.r});
			}
		}
		for (Iterator<Standing> it = standing.values().iterator(); it.hasNext(); )
		{
			if (it.next().lastFrame != frame)
			{
				it.remove();
			}
		}
		nearestWhileDark(plugin, lights, clusters);
		sendMovers();
		if (!idle)
		{
			return;
		}
		anyLit = lit;
		anyHidden = false;
		for (Lamp l : lamps.values())
		{
			anyHidden |= l.hidden;
		}
		farInfo[3] = anyHidden ? 1 : 0;
		updateGain();
		if (newScene && sentAll)
		{
			// the whole scene moved: what the texture holds is of no use, and nothing of it shows until the new is there
			sentAll = false;
			Arrays.fill(data, null);
			for (int i = 0; i < LEVELS; ++i)
			{
				emptyShown(i);
			}
		}
		// the windows
		int focusX = (int) (plugin.edgeFade.focalX + atkDX), focusZ = (int) (plugin.edgeFade.focalZ + atkDZ);
		float ground = playerY(plugin) + atkDY;
		int[][] to = new int[LEVELS][3];
		for (int i = 0; i < WALLED; ++i)
		{
			to[i][0] = snap(focusX - SIZE * CELLS[i] / 2);
			to[i][2] = snap(focusZ - SIZE * CELLS[i] / 2);
			to[i][1] = window[i][1];
		}
		to[L_FAR][0] = FAR_X;
		to[L_FAR][2] = FAR_X;
		to[L_FAR][1] = window[L_FAR][1];
		if (!windowSet || Math.abs(ground - windowGround) > GROUND_REACH)
		{
			windowGround = ground;
			for (int i = 0; i < WALLED; ++i)
			{
				to[i][1] = Math.floorDiv(Math.round(ground) - ABOVE[i], CELLS[i]) * CELLS[i];
			}
			to[L_FAR][1] = Math.floorDiv(Math.round(ground) - FAR_ABOVE, FAR_CELL) * FAR_CELL;
		}
		boolean windowMoved = !windowSet;
		for (int i = 0; i < LEVELS; ++i)
		{
			windowMoved |= !Arrays.equals(to[i], window[i]);
		}
		// one move at a time: what a window's boxes wait on is the move before them being done
		if (windowMoved && windowSet && (pending || !queue.isEmpty()))
		{
			windowMoved = false;
		}
		// a level's origin when nothing is on its way: boxes sent after this are summed from the new one
		if (windowSet && !pending && queue.isEmpty() && !frozen)
		{
			rebase(focusX, ground, focusZ);
		}
		// the zones
		Set<Integer> hide = ctx.hideRoofIds != null ? ctx.hideRoofIds : Set.of();
		int zx = ctx.zones.length, zz = ctx.zones[0].length;
		if (seen == null || seen.length != zx || seen[0].length != zz)
		{
			seen = new int[zx][zz][];
		}
		List<int[]> changedZones = new ArrayList<>();
		for (int x = 0; x < zx; ++x)
		{
			for (int z = 0; z < zz; ++z)
			{
				if (ctx.zones[x][z] != null && seen[x][z] != ctx.zones[x][z].lampTriangles)
				{
					changedZones.add(new int[]{x, z});
				}
			}
		}
		boolean shownChanged = ctx.minLevel != minLevel || ctx.level != level || ctx.maxLevel != maxLevel
			|| !hide.equals(hidden);
		boolean easing = false;
		for (Lamp l : lamps.values())
		{
			easing |= l.grid[L_NEAR].before != null;
		}
		if (added.isEmpty() && gone.isEmpty() && !strengths && !windowMoved && changedZones.isEmpty() && !shownChanged && !easing && !retryAll)
		{
			return;
		}
		Job j = new Job();
		j.ctx = ctx;
		j.points = fixturePoints(plugin);
		jobContext = ctx;
		j.added = added;
		j.gone = gone;
		j.changedZones = changedZones;
		j.shownChanged = shownChanged;
		j.windowMoved = windowMoved;
		j.to = windowMoved ? to : null;
		j.now = Clock.nanos();
		if (windowMoved && !windowSet)
		{
			// the first window: every level's origin in the middle of it
			for (int i = 0; i < LEVELS; ++i)
			{
				origin[i * 4] = focusX;
				origin[i * 4 + 1] = ground - 300;
				origin[i * 4 + 2] = focusZ;
			}
		}
		windowSet = true;
		minLevel = ctx.minLevel;
		level = ctx.level;
		maxLevel = ctx.maxLevel;
		hidden = new HashSet<>(hide);
		j.minLevel = minLevel;
		j.level = level;
		j.maxLevel = maxLevel;
		j.hidden = hidden;
		for (Lamp l : added)
		{
			if (!l.stepping)
			{
				jobArriving.add(l);
			}
		}
		j.handed = handed;
		jobHanded = handed;
		if (windowMoved)
		{
			// the render thread's own: what the rectangles go by. The worker reads it.
			for (int i = 0; i < LEVELS; ++i)
			{
				System.arraycopy(to[i], 0, nextWindow[i], 0, 3);
			}
		}
		job = WORKER.submit(j);
		if (benchSync && !shownChanged && changedZones.isEmpty())
		{
			finish();
			drain(Integer.MAX_VALUE, units);
		}
	}

	private final int[][] nextWindow = new int[LEVELS][3];
	private LuminaPlugin.SceneContext jobContext;
	// Every lamp's place this scene has had, x y z ox oz, from the scene's placed lights and the lamps listed: a triangle wholly near one is that lamp's
	// fixture, whenever its zone is looked at and whichever lamps are listed then. They only grow within a scene, so a lamp that leaves and comes back
	// finds its fixture as it left it.
	private final java.util.ArrayList<float[]> fixtures = new java.util.ArrayList<>();
	private final Set<Long> fixtureKeys = new HashSet<>();

	private void fixture(float x, float y, float z, float ox, float oz)
	{
		if (fixtureKeys.add((long) Float.floatToIntBits(x) * 31 + (long) Float.floatToIntBits(y) * 17 << 20 ^ Float.floatToIntBits(z) ^ (long) Float.floatToIntBits(ox) << 9 ^ (long) Float.floatToIntBits(oz) << 3))
		{
			fixtures.add(new float[]{x, y, z, ox, oz});
		}
	}

	private float[] fixturePoints(LuminaPlugin plugin)
	{
		float[] placed = plugin.sceneLights != null ? plugin.sceneLights.fixtures() : new float[0];
		for (int i = 0; i + 4 < placed.length; i += 5)
		{
			fixture(placed[i], placed[i + 1], placed[i + 2], placed[i + 3], placed[i + 4]);
		}
		for (Lamp l : lamps.values())
		{
			fixture(l.x, l.y, l.z, l.ox, l.oz);
		}
		float[] f = new float[fixtures.size() * 5];
		for (int i = 0; i < fixtures.size(); ++i)
		{
			System.arraycopy(fixtures.get(i), 0, f, i * 5, 5);
		}
		return f;
	}

	private static long key(PointLight p)
	{
		return (long) Float.floatToIntBits(p.x) * 31 + (long) Float.floatToIntBits(p.y) * 17 << 20 ^ Float.floatToIntBits(p.z) ^ (long) (p.radius / 16) << 50;
	}

	private static int snap(int corner)
	{
		return Math.floorDiv(corner + WINDOW_STEP / 2, WINDOW_STEP) * WINDOW_STEP;
	}

	// whether a lamp new here is one that left the volume or the moving list a moment ago, next to here
	private boolean movedHere(PointLight p, List<Lamp> gone)
	{
		float reach = MOVE_REACH * MOVE_REACH;
		for (Lamp l : gone)
		{
			float dx = l.x - p.x, dy = l.y - p.y, dz = l.z - p.z;
			if (dx * dx + dy * dy + dz * dz < reach)
			{
				return true;
			}
		}
		for (int i = 0; i < lastMovers.length; i += 3)
		{
			float dx = lastMovers[i] - p.x, dy = lastMovers[i + 1] - p.y, dz = lastMovers[i + 2] - p.z;
			if (dx * dx + dy * dy + dz * dz < reach)
			{
				return true;
			}
		}
		return false;
	}

	// a lamp for the moving list, at this share of it, what the volume doesn't show: a hand-over; else until the volume has let go of it where
	// it was, it stays there and doesn't show twice
	private void mover(PointLight p, LightClusters clusters, float share, boolean hand)
	{
		float s = p.intensity;
		float lum = (p.r + p.g + p.b) * s;
		moverPlaces.add(new float[]{p.x, p.y, p.z});
		if (lum <= 0 || share <= 0)
		{
			return;
		}
		for (int i = 0; i < leaving.size() && !hand; ++i)
		{
			float[] w = leaving.get(i);
			float dx = w[0] - p.x, dy = w[1] - p.y, dz = w[2] - p.z;
			if (dx * dx + dy * dy + dz * dz < 4 * MOVE_REACH * MOVE_REACH)
			{
				return;
			}
		}
		float r = Math.abs(p.radius);
		s *= share;
		moverList.add(new float[]{p.x, p.y, p.z, clusters.onHiddenStorey(p) ? -r : r, p.r * s, p.g * s, p.b * s, lum * r});
	}

	private static boolean handing(Lamp l)
	{
		return l.steps < HAND_STEPS || l.stepping;
	}

	// the share of a lamp the volume doesn't show
	private static float rest(Lamp l)
	{
		return (HAND_STEPS - l.shownSteps) / (float) HAND_STEPS;
	}

	// which of the frame's new lamps are ones that left a place in the volume, each with the nearest place it could have left
	private Map<PointLight, Lamp> moved(List<PointLight> newcomers)
	{
		Map<PointLight, Lamp> moved = new java.util.IdentityHashMap<>();
		for (Lamp l : lamps.values())
		{
			if (l.seen || l.asleep)
			{
				continue;
			}
			float x = l.out ? l.atX : l.x, y = l.out ? l.atY : l.y, z = l.out ? l.atZ : l.z, best = MOVE_REACH * MOVE_REACH;
			PointLight to = null;
			for (PointLight p : newcomers)
			{
				float dx = x - p.x, dy = y - p.y, dz = z - p.z, d2 = dx * dx + dy * dy + dz * dz;
				if (d2 < best && !moved.containsKey(p))
				{
					best = d2;
					to = p;
				}
			}
			if (to != null)
			{
				float s = to.intensity;
				l.out = true;
				l.gain = 1;
				l.atX = to.x;
				l.atY = to.y;
				l.atZ = to.z;
				l.fr = to.r * s;
				l.fg = to.g * s;
				l.fb = to.b * s;
				moved.put(to, l);
			}
		}
		return moved;
	}

	// what a lamp's boxes come to, about
	private static float handCost(float radius)
	{
		float cells = 2 * Math.abs(radius) / FINE + 2;
		return 16 * cells * cells * cells;
	}

	// a step of a lamp's share of the volume
	private static void step(Lamp l, int by, long nanos)
	{
		l.steps += by;
		float share = l.steps / (float) HAND_STEPS;
		l.nr = l.fr * share;
		l.ng = l.fg * share;
		l.nb = l.fb * share;
		l.stepping = true;
		l.stepAt = nanos;
	}

	// The next steps due, those that waited longest first, as many as a job may send. True if any.
	private boolean steps(long nanos, List<Lamp> handed, float bytes)
	{
		List<Lamp> due = null;
		for (Lamp l : lamps.values())
		{
			if ((l.out ? l.steps > 0 : l.steps < HAND_STEPS) && !l.stepping && (benchSync || nanos - l.stepAt >= HAND_NANOS))
			{
				due = due != null ? due : new ArrayList<>();
				due.add(l);
			}
		}
		if (due == null)
		{
			return false;
		}
		due.sort((a, b) -> Long.compare(a.stepAt, b.stepAt));
		boolean any = false;
		for (Lamp l : due)
		{
			if (!handed.isEmpty() && bytes + handCost(l.r) > HAND_BYTES)
			{
				break;
			}
			bytes += handCost(l.r);
			step(l, l.out ? -1 : 1, nanos);
			handed.add(l);
			any = true;
		}
		return any;
	}

	// a new scene is sent whole: nothing is on its way between places
	private void endHandOvers()
	{
		for (Iterator<Lamp> it = lamps.values().iterator(); it.hasNext(); )
		{
			Lamp l = it.next();
			if (l.out)
			{
				it.remove();
			}
			else if (handing(l))
			{
				l.steps = l.shownSteps = HAND_STEPS;
				l.stepping = false;
				l.inField = true;
				l.nr = l.fr;
				l.ng = l.fg;
				l.nb = l.fb;
			}
		}
	}

	// a step shows: the moving list takes the rest of the lamp in the frame its boxes land
	private static void land(List<Lamp> list)
	{
		for (Lamp l : list)
		{
			l.shownSteps = l.steps;
			l.stepping = false;
			if (l.steps >= HAND_STEPS)
			{
				l.inField = true;
			}
		}
	}

	// The brightest fit, and the last of those fades on the one left out, so one coming in over another eases
	// While a scene's volume isn't there yet, the lamps nearest the player go per pixel, so what's beside you doesn't go dark at a region's edge. Nothing
	// stops them for those few frames: the line test has no air to read.
	private void nearestWhileDark(LuminaPlugin plugin, List<PointLight> lights, LightClusters clusters)
	{
		if (benchSync || data[L_FAR] != null || lights.isEmpty() || frozen)
		{
			return;
		}
		float fx = plugin.edgeFade.focalX, fz = plugin.edgeFade.focalZ;
		List<PointLight> near = new ArrayList<>(lights);
		near.sort((a, b) -> Float.compare((a.x - fx) * (a.x - fx) + (a.z - fz) * (a.z - fz), (b.x - fx) * (b.x - fx) + (b.z - fz) * (b.z - fz)));
		for (int i = 0; i < Math.min(MOVERS, near.size()); ++i)
		{
			mover(near.get(i), clusters, 1, true);
		}
	}

	private void sendMovers()
	{
		moverList.sort((a, b) -> Float.compare(b[7], a[7]));
		int n = Math.min(MOVERS, moverList.size());
		float out = moverList.size() > MOVERS ? moverList.get(MOVERS)[7] : 0;
		lastMovers = new float[moverPlaces.size() * 3];
		for (int i = 0; i < moverPlaces.size(); ++i)
		{
			System.arraycopy(moverPlaces.get(i), 0, lastMovers, i * 3, 3);
		}
		for (int i = 0; i < n; ++i)
		{
			float[] m = moverList.get(i);
			float fade = out > 0 ? Math.max(0, Math.min(1, (m[7] - out) / (0.5f * m[7]))) : 1;
			movers[i * 8] = m[0];
			movers[i * 8 + 1] = m[1];
			movers[i * 8 + 2] = m[2];
			movers[i * 8 + 3] = m[3];
			movers[i * 8 + 4] = m[4] * fade;
			movers[i * 8 + 5] = m[5] * fade;
			movers[i * 8 + 6] = m[6] * fade;
			movers[i * 8 + 7] = 0;
		}
		moverCount = n;
	}

	// The move of a level's origin (math.md 9): H plus G times the old origin less the new, for every texel, one blended quad a layer,
	// in the frame the shaders start reading the new origin. One level a frame. True if it did one.
	private boolean rebase(float x, float y, float z)
	{
		for (int i = 0; i < LEVELS; ++i)
		{
			float reach = i == L_FAR ? 2 * ORIGIN_REACH : ORIGIN_REACH;
			if (Math.abs(x - origin[i * 4]) <= reach && Math.abs(z - origin[i * 4 + 2]) <= reach && Math.abs(y - 300 - origin[i * 4 + 1]) <= reach)
			{
				continue;
			}
			float nx = x, ny = y - 300, nz = z;
			int[] program = new int[1], draw = new int[1], read = new int[1], vao = new int[1], view = new int[4], src = new int[4];
			glGetIntegerv(GL_CURRENT_PROGRAM, program);
			glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, draw);
			glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, read);
			glGetIntegerv(GL_VERTEX_ARRAY_BINDING, vao);
			glGetIntegerv(GL_VIEWPORT, view);
			glGetIntegerv(GL_BLEND_SRC_RGB, src);
			int srcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA), dstRgb = glGetInteger(GL_BLEND_DST_RGB), dstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
			boolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), scissor = glIsEnabled(GL_SCISSOR_TEST), cull = glIsEnabled(GL_CULL_FACE), stencil = glIsEnabled(GL_STENCIL_TEST);
			glBindFramebuffer(GL_FRAMEBUFFER, fboBlend);
			glViewport(0, 0, SIZE, SIZE);
			glDisable(GL_DEPTH_TEST);
			glDisable(GL_SCISSOR_TEST);
			glDisable(GL_CULL_FACE);
			glDisable(GL_STENCIL_TEST);
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE, GL_ZERO, GL_ONE);
			glUseProgram(programBlend);
			glBindVertexArray(vaoBlend);
			glUniform3f(uniBlendShift, origin[i * 4] - nx, origin[i * 4 + 1] - ny, origin[i * 4 + 2] - nz);
			for (int layer = BASE[i] - 1; layer <= BASE[i] + DEPTH[i]; ++layer)
			{
				glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, texA, 0, LAYERS + layer);
				glDrawArrays(GL_TRIANGLES, 0, 3);
			}
			glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 0, 0, 0);
			glBindVertexArray(vao[0]);
			glUseProgram(program[0]);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw[0]);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, read[0]);
			glViewport(view[0], view[1], view[2], view[3]);
			glBlendFuncSeparate(src[0], dstRgb, srcAlpha, dstAlpha);
			state(GL_BLEND, blend);
			state(GL_DEPTH_TEST, depth);
			state(GL_SCISSOR_TEST, scissor);
			state(GL_CULL_FACE, cull);
			state(GL_STENCIL_TEST, stencil);
			origin[i * 4] = nx;
			origin[i * 4 + 1] = ny;
			origin[i * 4 + 2] = nz;
			return true;
		}
		return false;
	}

	private static void state(int cap, boolean on)
	{
		if (on)
		{
			glEnable(cap);
		}
		else
		{
			glDisable(cap);
		}
	}

	// the lamps' flicker: per GAIN_CELL of the ground, what the lamps there are at now over what the volume holds them at, weighted by
	// their light there, less 1. A slice of the texture no read filters into (a layer of A past the far level), a read of it per lit pixel. Cheap
	// enough for every frame: a lamp touches a few cells.
	private void updateGain()
	{
		if (frozen)
		{
			return;
		}
		boolean any = false;
		for (Lamp l : lamps.values())
		{
			any |= l.gain != 1;
		}
		if (!any && !gainSent)
		{
			return;
		}
		gainSent = any;
		Arrays.fill(gainW, 0);
		Arrays.fill(gainG, 0);
		float x0 = window[L_NEAR][0] + SIZE * CELL / 2f - GAIN_SIZE * GAIN_CELL / 2f, z0 = window[L_NEAR][2] + SIZE * CELL / 2f - GAIN_SIZE * GAIN_CELL / 2f;
		gainAt[0] = x0;
		gainAt[1] = z0;
		for (Lamp l : lamps.values())
		{
			float lum = l.nr + l.ng + l.nb;
			int cx0 = Math.max(0, (int) Math.floor((l.x - l.r - x0) / GAIN_CELL - 0.5f)), cx1 = Math.min(GAIN_SIZE - 1, (int) Math.ceil((l.x + l.r - x0) / GAIN_CELL - 0.5f));
			int cz0 = Math.max(0, (int) Math.floor((l.z - l.r - z0) / GAIN_CELL - 0.5f)), cz1 = Math.min(GAIN_SIZE - 1, (int) Math.ceil((l.z + l.r - z0) / GAIN_CELL - 0.5f));
			for (int z = cz0; z <= cz1; ++z)
			{
				for (int x = cx0; x <= cx1; ++x)
				{
					float dx = (x + 0.5f) * GAIN_CELL + x0 - l.x, dz = (z + 0.5f) * GAIN_CELL + z0 - l.z;
					float d = 1 - (float) Math.sqrt(dx * dx + dz * dz) / (l.r + GAIN_CELL);
					if (d > 0)
					{
						float w = lum * d * d;
						gainW[z * GAIN_SIZE + x] += w;
						gainG[z * GAIN_SIZE + x] += w * (l.gain - 1);
					}
				}
			}
		}
		gainStage.clear();
		for (int i = 0; i < GAIN_SIZE * GAIN_SIZE; ++i)
		{
			gainStage.put(half(gainW[i] > 0 ? gainG[i] / gainW[i] : 0)).put((short) 0).put((short) 0).put((short) 0);
		}
		gainStage.flip();
		glBindTexture(GL_TEXTURE_3D, texA);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
		long gt = System.nanoTime();
		if (GAIN_PBO)
		{
			// through a buffer: the driver takes it from there; a direct write to a texture this size is 5 ms of the client thread, every frame a lamp flickers
			if (gainPbo == 0)
			{
				gainPbo = glGenBuffers();
			}
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, gainPbo);
			glBufferData(GL_PIXEL_UNPACK_BUFFER, gainStage, GL_STREAM_DRAW);
			glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, GAIN_AT, GAIN_SIZE, GAIN_SIZE, 1, GL_RGBA, GL_HALF_FLOAT, 0L);
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		}
		else
		{
			glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, GAIN_AT, GAIN_SIZE, GAIN_SIZE, 1, GL_RGBA, GL_HALF_FLOAT, gainStage);
		}
		atkGainNanos = System.nanoTime() - gt;
		glBindTexture(GL_TEXTURE_3D, 0);
	}

	// the ground under the player's focus (world y, down)
	private static float playerY(LuminaPlugin plugin)
	{
		net.runelite.api.WorldView view = plugin.client.getTopLevelWorldView();
		net.runelite.api.Scene scene = view != null ? view.getScene() : null;
		int[][][] heights = scene != null ? scene.getTileHeights() : null;
		int tx = ((int) plugin.edgeFade.focalX >> 7) + LuminaPlugin.SCENE_OFFSET, tz = ((int) plugin.edgeFade.focalZ >> 7) + LuminaPlugin.SCENE_OFFSET;
		if (heights == null || tx < 0 || tz < 0 || tx >= heights[0].length || tz >= heights[0][0].length)
		{
			return plugin.frameCameraY + 1000;
		}
		return heights[0][tx][tz];
	}

	// what the worker finished: its boxes go in the queue
	private void finish()
	{
		try
		{
			job.get();
			tries = 0;
			retryAll = false;
		}
		catch (Exception e)
		{
			jobFailed(e);
		}
		job = null;
		if (sceneHeld != 0 && sceneJob == 0)
		{
			sceneJob = System.nanoTime();
			sceneFrames = 0;
		}
		List<Upload> list = done;
		done = null;
		if (list != null)
		{
			queue.addAll(list);
		}
		if (!jobHanded.isEmpty())
		{
			if (list != null && !list.isEmpty())
			{
				flips.add(jobHanded);
			}
			else
			{
				land(jobHanded);
			}
			jobHanded = new ArrayList<>();
		}
		arriving.addAll(jobArriving);
		jobArriving.clear();
		pending = true;
	}

	// How many frames a new scene's list has to stand still before its lamps go in, the most a scene can make them wait, and how long after it
	// stragglers still aren't a new scene of their own
	private static final int HOLD_STILL = 6;
	private static final long HOLD_NANOS = 5_000_000_000L;
	// how far a scene's base can go and still be a walk: a scene is 104 tiles, a region's edge moves it about 40
	private static final int WALK_TILES = 64;
	// a change of this many zones is a scene load: a door is a few
	private static final int SCENE_ZONES = 64;
	private boolean holding;
	private int heldFrames, stillFrames, heldLamps;
	// for the log: when a scene's lamps began to be waited for, and when they stopped; when its job ended, and the frames its send took
	private long sceneFrom, sceneHeld, sceneJob;
	private int sceneFrames;

	// True while a new scene's lamps are still coming. The list is a few lamps longer each frame of the scene job (a client's load, not the bench's one
	// frame). A job per few lamps sums a box for each lamp, each more than a tile's worth of megabytes at a walking pace's send: seconds, 3.3 to 3.9 on fps1,
	// and any twelve of them count as a new scene and put out what is lit. So what's shown goes out now, the lamps wait until the list stands, and
	// they all go in one job with a load's send, as if they had come in a frame.
	private boolean hold(LuminaPlugin plugin, LuminaPlugin.SceneContext ctx, List<PointLight> lights)
	{
		if (!holding)
		{
			boolean first = !sentAll && lamps.isEmpty();
			if (!first && !newZones(ctx))
			{
				return false;
			}
			holding = true;
			heldFrames = stillFrames = 0;
			heldLamps = -1;
			sceneFrom = System.nanoTime();
			fixtures.clear();
			fixtureKeys.clear();
			// a walk over a region's edge: the world is where it was, by what the scene's base moved. Far away, a teleport: nothing of it is any use
			boolean shownNow = frozen || sentAll && data[L_FAR] != null;
			readBase(plugin);
			int dx = builtBaseX - frontBaseX, dz = builtBaseY - frontBaseY;
			boolean walking = !first && shownNow && Math.abs(dx) <= WALK_TILES && Math.abs(dz) <= WALK_TILES;
			if (walking && !frozen)
			{
				allocateBack();
				System.arraycopy(shown, 0, frontShown, 0, shown.length);
				System.arraycopy(origin, 0, frontOrigin, 0, origin.length);
				System.arraycopy(farInfo, 0, frontFar, 0, farInfo.length);
				System.arraycopy(gainAt, 0, frontGainAt, 0, gainAt.length);
			}
			frozen = walking;
			shift[0] = walking ? dx * 128f + atkShiftX : 0;
			shift[2] = walking ? dz * 128f : 0;
			// what was on its way to the old scene stays out
			queue.clear();
			while (!flips.isEmpty())
			{
				land(flips.poll());
			}
			pending = false;
			arriving.clear();
			leaving.clear();
			endHandOvers();
			sentAll = false;
			Arrays.fill(data, null);
			for (int i = 0; i < LEVELS; ++i)
			{
				emptyShown(i);
			}
		}
		++heldFrames;
		int n = lights.size();
		stillFrames = n == heldLamps ? stillFrames + 1 : 0;
		heldLamps = n;
		// the world lights come first and the list can stand for frames before the objects': the client's log has 5 of 449 lamps taken for all of them
		boolean placing = plugin.sceneLights != null && plugin.sceneLights.placing();
		if (n > 0 && stillFrames >= HOLD_STILL && !placing || System.nanoTime() - sceneFrom > HOLD_NANOS)
		{
			holding = false;
			readBase(plugin);
			sceneHeld = System.nanoTime();
			return false;
		}
		return true;
	}

	private boolean newZones(LuminaPlugin.SceneContext ctx)
	{
		if (seen == null || seen.length != ctx.zones.length || seen[0].length != ctx.zones[0].length)
		{
			return seen != null;
		}
		int n = 0;
		for (int x = 0; x < ctx.zones.length; ++x)
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				n += ctx.zones[x][z] != null && seen[x][z] != ctx.zones[x][z].lampTriangles ? 1 : 0;
			}
		}
		return n >= SCENE_ZONES;
	}

	// nothing of the job lands; next, everything from the start, the worker's half-done state with it
	private void jobFailed(Exception e)
	{
		++atkFailures;
		done = null;
		queue.clear();
		boolean last = ++tries >= MAX_TRIES;
		log.error(last ? "Lamp volume: a job failed for the last time ({} of {}): the lamps stay as they are until the next scene"
			: "Lamp volume: a job failed ({} of {}): the lamps stay as they are and it goes again", tries, MAX_TRIES, e);
		if (last)
		{
			gaveUpOn = jobContext;
			tries = 0;
		}
		else
		{
			retryAt = System.nanoTime() + TRY_AFTER[tries - 1];
		}
		retryAll = true;
		seen = null;
		// the windows stay where the texture has them: a move the job didn't send isn't shown
		for (int i = 0; i < LEVELS; ++i)
		{
			if (data[i] != null)
			{
				System.arraycopy(data[i], 0, nextWindow[i], 0, 3);
				System.arraycopy(data[i], 0, window[i], 0, 3);
			}
		}
		for (Lamp l : lamps.values())
		{
			l.flood = true;
			for (Grid g : l.grid)
			{
				g.old = g.before = null;
				g.change = null;
			}
		}
	}

	private void emptyShown(int i)
	{
		float[] s = shown;
		int a = i * 8;
		s[a] = s[a + 1] = s[a + 2] = 1e7f;
		s[a + 4] = s[a + 5] = s[a + 6] = -1e7f;
	}

	// the rectangle of a window that a level can show: 6 floats, min then max; none for no window
	private static float[] rectOf(int[] w, int i)
	{
		if (w == null)
		{
			return null;
		}
		float c = CELLS[i], e = INSET * c;
		if (i == L_FAR)
		{
			// the far level's cells fill x and z to the edge, and past it the edge is clamped
			return new float[]{-1e7f, w[1] + c, -1e7f, 1e7f, w[1] + (DEPTH[i] - 1) * c, 1e7f};
		}
		return new float[]{w[0] + e, w[1] + e, w[2] + e, w[0] + SIZE * c - e, w[1] + DEPTH[i] * c - e, w[2] + SIZE * c - e};
	}

	// The lamps' boxes wait until no shown rectangle holds a cell they write. The rectangles' edges glide there and back (math.md 9): units a
	// frame. Oldest boxes first, up to this many bytes.
	private void drain(int bytes, float units)
	{
		boolean clear = true;
		for (int i = 0; i < LEVELS; ++i)
		{
			float[] t = rectOf(data[i], i);
			if (pending && t != null)
			{
				// only what stays: the part of the texture a send doesn't write
				float[] u = rectOf(nextWindow[i], i);
				for (int k = 0; k < 3; ++k)
				{
					t[k] = Math.max(t[k], u[k]);
					t[k + 3] = Math.min(t[k + 3], u[k + 3]);
				}
				if (t[0] > t[3] || t[1] > t[4] || t[2] > t[5])
				{
					t = null;
				}
			}
			clear &= glide(i, t, units);
		}
		if (!pending)
		{
			return;
		}
		if (!clear)
		{
			return;
		}
		if (!queue.isEmpty())
		{
			long t0 = System.nanoTime();
			int sent = 0;
			++sceneFrames;
			glBindTexture(GL_TEXTURE_3D, frozen ? texBack : texA);
			glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
			if (SEND_PBO)
			{
				if (gainPbo == 0)
				{
					gainPbo = glGenBuffers();
				}
				glBindBuffer(GL_PIXEL_UNPACK_BUFFER, gainPbo);
			}
			boolean joined = false;
			while ((bytes > 0 || joined) && !queue.isEmpty())
			{
				Upload u = queue.poll();
				joined = u.joined;
				send(u, u.a, BASE[u.level]);
				send(u, u.b, BASE[u.level] + LAYERS);
				bytes -= (u.a.length + u.b.length) * 2;
				sent += (u.a.length + u.b.length) * 2;
				if (u.fog != null)
				{
					send(u, u.fog, FOG_AT);
					bytes -= u.fog.length * 2;
					sent += u.fog.length * 2;
				}
				if (u.handEnd && !flips.isEmpty())
				{
					land(flips.poll());
				}
			}
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			sendNanos = System.nanoTime() - t0;
			atkSendMax = Math.max(atkSendMax, sendNanos);
			atkSendSum += sendNanos;
			glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
			glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
			glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
			glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
			glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
			glBindTexture(GL_TEXTURE_3D, 0);
			atkSentBytes = sent;
			if (System.getenv("LUMINA_VOL_LOG") != null)
			{
				System.out.printf("lamp volume: sent %d kB in %.2f ms%n", sent / 1024, (System.nanoTime() - t0) / 1e6);
			}
		}
		if (queue.isEmpty() && (job == null || job.isDone()))
		{
			// all there: the windows are what the texture holds, and the rectangles may grow to them
			pending = false;
			for (int i = 0; i < LEVELS; ++i)
			{
				data[i] = nextWindow[i].clone();
				glide(i, rectOf(data[i], i), units);
			}
			if (frozen)
			{
				int t = texA;
				texA = texBack;
				texBack = t;
				frozen = false;
				Arrays.fill(shift, 0);
				++atkFlips;
			}
			if (baseKnown)
			{
				frontBaseX = builtBaseX;
				frontBaseY = builtBaseY;
			}
			for (Lamp l : arriving)
			{
				l.inField = true;
				l.shownSteps = HAND_STEPS;
			}
			arriving.clear();
			leaving.clear();
			if (sceneJob != 0)
			{
				long at = System.nanoTime();
				log.info("Lamp volume: {} lamps lit {} ms after the scene began: the list {} ms, the job {} ms ({}), the send {} ms over {} frames", lamps.size(), (at - sceneFrom) / 1_000_000,
					(sceneHeld - sceneFrom) / 1_000_000, (sceneJob - sceneHeld) / 1_000_000, lastJob, (at - sceneJob) / 1_000_000, sceneFrames);
				sceneFrom = sceneHeld = sceneJob = 0;
			}
		}
	}

	// A shown rectangle toward t by so many units a side; true when it is inside it. With nothing to go to it is empty at once; from
	// empty it is t at once: the level is new, nothing moves.
	private boolean glide(int i, float[] t, float units)
	{
		float[] s = shown;
		int a = i * 8;
		if (t == null)
		{
			emptyShown(i);
			return true;
		}
		if (s[a] > s[a + 4])
		{
			if (!pending)
			{
				s[a] = t[0];
				s[a + 1] = t[1];
				s[a + 2] = t[2];
				s[a + 4] = t[3];
				s[a + 5] = t[4];
				s[a + 6] = t[5];
			}
			return true;
		}
		for (int k = 0; k < 3; ++k)
		{
			s[a + k] = toward(s[a + k], t[k], units);
			s[a + 4 + k] = toward(s[a + 4 + k], t[k + 3], units);
		}
		return s[a] >= t[0] && s[a + 1] >= t[1] && s[a + 2] >= t[2] && s[a + 4] <= t[3] && s[a + 5] <= t[4] && s[a + 6] <= t[5];
	}

	private static float toward(float from, float to, float units)
	{
		return from < to ? Math.min(from + units, to) : Math.max(from - units, to);
	}

	// A box, split where it wraps round the texture. base: the layer of the field it goes in
	private void send(Upload u, short[] data, int base)
	{
		if (stage.capacity() < data.length)
		{
			stage = BufferUtils.createShortBuffer(data.length);
		}
		stage.clear();
		stage.put(data);
		stage.flip();
		if (SEND_PBO)
		{
			glBufferData(GL_PIXEL_UNPACK_BUFFER, stage, GL_STREAM_DRAW);
		}
		glPixelStorei(GL_UNPACK_ROW_LENGTH, u.w);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, u.d);
		int depth = DEPTH[u.level];
		int h0 = Math.min(u.h, depth - u.y);
		pieces(u, 0, h0, base + u.y);
		if (h0 < u.h)
		{
			pieces(u, h0, u.h - h0, base);
		}
		// the gutters: each end's layer again past the other end
		int first = (depth - u.y) % depth, last = depth - 1 - u.y;
		if (first < u.h)
		{
			pieces(u, first, 1, base + depth);
		}
		if (last < u.h)
		{
			pieces(u, last, 1, base - 1);
		}
	}

	private void pieces(Upload u, int skipLayers, int layers, int layer)
	{
		glPixelStorei(GL_UNPACK_SKIP_IMAGES, skipLayers);
		int w0 = Math.min(u.w, SIZE - u.x), d0 = Math.min(u.d, SIZE - u.z);
		piece(u, 0, 0, u.x, u.z, w0, d0, layer, layers);
		if (w0 < u.w)
		{
			piece(u, w0, 0, 0, u.z, u.w - w0, d0, layer, layers);
		}
		if (d0 < u.d)
		{
			piece(u, 0, d0, u.x, 0, w0, u.d - d0, layer, layers);
		}
		if (w0 < u.w && d0 < u.d)
		{
			piece(u, w0, d0, 0, 0, u.w - w0, u.d - d0, layer, layers);
		}
	}

	private void piece(Upload u, int skipX, int skipZ, int x, int z, int w, int d, int layer, int layers)
	{
		glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipX);
		glPixelStorei(GL_UNPACK_SKIP_ROWS, skipZ);
		if (SEND_PBO)
		{
			glTexSubImage3D(GL_TEXTURE_3D, 0, x, z, layer, w, d, layers, GL_RGBA, GL_HALF_FLOAT, 0L);
		}
		else
		{
			glTexSubImage3D(GL_TEXTURE_3D, 0, x, z, layer, w, d, layers, GL_RGBA, GL_HALF_FLOAT, stage);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the worker

	private final class Job implements Runnable
	{
		LuminaPlugin.SceneContext ctx;
		List<Lamp> added, gone, handed;
		List<int[]> changedZones;
		boolean shownChanged, windowMoved;
		int[][] to;
		int minLevel, level, maxLevel;
		Set<Integer> hidden;
		long now;
		float[] points;

		@Override
		public void run()
		{
			try
			{
				long t0 = System.nanoTime();
				work();
				lastJobNanos = System.nanoTime() - t0;
			}
			catch (RuntimeException e)
			{
				e.printStackTrace();
				throw e;
			}
		}

		private void work()
		{
			long t0 = System.nanoTime();
			if (atkFailNext > 0)
			{
				--atkFailNext;
				throw new IllegalStateException("the bench failed this job");
			}
			boolean everything = !sentAll || retryAll;
			int[][] from = new int[LEVELS][];
			for (int i = 0; i < LEVELS; ++i)
			{
				from[i] = window[i].clone();
				if (windowMoved)
				{
					window[i] = to[i].clone();
				}
			}
			// the zones that changed, and those newly in reach of a lamp
			int offset = LuminaPlugin.SCENE_OFFSET >> 3;
			int[] dirty = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
			List<Runnable> tasks = new ArrayList<>();
			Set<Long> needed = new HashSet<>();
			for (int x = 0; x < ctx.zones.length; ++x)
			{
				for (int z = 0; z < ctx.zones[0].length; ++z)
				{
					float x0 = (x - offset) << 10, z0 = (z - offset) << 10, x1 = x0 + 1024, z1 = z0 + 1024;
					for (Lamp l : lamps.values())
					{
						float dx = Math.max(0, Math.max(x0 - l.x, l.x - x1)), dz = Math.max(0, Math.max(z0 - l.z, l.z - z1));
						float reach = l.r + 512;
						if (dx * dx + dz * dz < reach * reach)
						{
							needed.add(zoneKey(x, z));
							break;
						}
					}
				}
			}
			final Map<Long, ZoneCells> built = new java.util.concurrent.ConcurrentHashMap<>();
			for (int x = 0; x < ctx.zones.length; ++x)
			{
				for (int z = 0; z < ctx.zones[0].length; ++z)
				{
					final int fx = x, fz = z;
					long key = zoneKey(x, z);
					ZoneCells old = zones.get(key);
					Zone now = ctx.zones[x][z];
					if (now == null)
					{
						continue;
					}
					if (needed.contains(key) && (old == null || old.triangles != now.lampTriangles || fixtureCame(old, x, z, points)))
					{
						tasks.add(() ->
						{
							ZoneCells c = voxelize(now, fx, fz, points);
							if (c != null)
							{
								built.put(zoneKey(fx, fz), c);
							}
						});
					}
					seen[x][z] = now.lampTriangles;
				}
			}
			parallel(tasks);
			long t1 = System.nanoTime();
			boolean bitsChanged = !built.isEmpty();
			CellList altered = new CellList();
			for (Map.Entry<Long, ZoneCells> e : built.entrySet())
			{
				for (Segment seg : e.getValue().segments)
				{
					seg.shown = shown(seg);
				}
				// a door's new zone differs from the old by its door: only lamps that reach that see a change
				difference(dirty, zones.put(e.getKey(), e.getValue()), e.getValue(), altered);
			}
			// what is shown now
			for (ZoneCells c : zones.values())
			{
				for (Segment s : c.segments)
				{
					boolean shown = shown(s);
					if (shown != s.shown)
					{
						s.shown = shown;
						bitsChanged = true;
						dirty[0] = Math.min(dirty[0], s.minX);
						dirty[1] = Math.min(dirty[1], s.minZ);
						dirty[2] = Math.max(dirty[2], s.maxX);
						dirty[3] = Math.max(dirty[3], s.maxZ);
						altered.add(s.cells);
					}
				}
			}
			if (bitsChanged)
			{
				rebuildBits(dirty);
			}
			long t2 = System.nanoTime();
			// the lamps to flood: new, or a change in their box
			List<Lamp> floods = new ArrayList<>();
			for (Lamp l : lamps.values())
			{
				boolean touched = dirty[0] <= dirty[2] && (l.x + l.r + 32 - GRID_X) / FINE >= dirty[0] && (l.x - l.r - 32 - GRID_X) / FINE <= dirty[2]
					&& (l.z + l.r + 32 - GRID_X) / FINE >= dirty[1] && (l.z - l.r - 32 - GRID_X) / FINE <= dirty[3];
				if (!l.flood && !touched && l.grid[L_FINE].vis == null && l.grid[L_NEAR].vis != null && nearFine(l, 0))
				{
					// the fine window has come near it: what the coarser levels show of it stays, or the lamp changes on screen as the player runs
					l.flood = true;
					l.fineOnly = true;
				}
				else if (l.grid[L_FINE].vis != null && !nearFine(l, 2 * FINE_MARGIN))
				{
					l.grid[L_FINE].vis = null;
					l.grid[L_FINE].before = null;
					l.grid[L_FINE].w = l.grid[L_FINE].h = l.grid[L_FINE].d = 0;
				}
				if (l.flood || touched && lightsAny(l, altered))
				{
					floods.add(l);
				}
			}
			tasks.clear();
			final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
			for (Lamp l : floods)
			{
				tasks.add(() ->
				{
					for (Grid g : l.grid)
					{
						g.old = g.vis;
						g.oldX = g.x;
						g.oldY = g.y;
						g.oldZ = g.z;
						g.oldW = g.w;
						g.oldH = g.h;
						g.oldD = g.d;
					}
					flood(l, scratch.get());
					for (Grid g : l.grid)
					{
						g.change = change(g);
					}
					if (l.fineOnly)
					{
						l.fineOnly = false;
						for (int i = L_NEAR; i <= L_MID; ++i)
						{
							Grid g = l.grid[i];
							if (g.old != null)
							{
								g.vis = g.old;
								g.x = g.oldX;
								g.y = g.oldY;
								g.z = g.oldZ;
								g.w = g.oldW;
								g.h = g.oldH;
								g.d = g.oldD;
								g.change = new int[]{0, 0, 0, 0, 0, 0};
							}
						}
					}
				});
			}
			parallel(tasks);
			// a lamp a change didn't touch is done with; a few that it did ease over to it
			int easing = 0;
			for (Lamp l : floods)
			{
				easing += l.grid[L_NEAR].old != null && changed(l) ? 1 : 0;
			}
			for (Iterator<Lamp> it = floods.iterator(); it.hasNext(); )
			{
				Lamp l = it.next();
				boolean fresh = l.flood;
				l.flood = false;
				if (!fresh && l.grid[L_NEAR].old != null && !changed(l))
				{
					it.remove();
				}
				else if (!fresh && sentAll && easing <= EASE_LAMPS && sameCells(l))
				{
					for (Grid g : l.grid)
					{
						g.before = g.old;
					}
					l.easeFrom = now;
				}
				for (Grid g : l.grid)
				{
					g.old = null;
				}
			}
			long t3 = System.nanoTime();
			// the boxes to sum: everything, what came into a window, each lamp changed
			List<List<int[]>> boxes = new ArrayList<>(), steps = new ArrayList<>();
			for (int i = 0; i < LEVELS; ++i)
			{
				boxes.add(new ArrayList<>());
				steps.add(new ArrayList<>());
				if (everything)
				{
					whole(boxes.get(i), i);
				}
				else if (windowMoved)
				{
					cameIn(boxes.get(i), i, from[i], window[i]);
				}
			}
			if (!everything)
			{
				List<Lamp> changed = new ArrayList<>(gone);
				changed.addAll(floods);
				for (Lamp l : lamps.values())
				{
					boolean ease = l.grid[L_NEAR].before != null;
					if (ease || l.nr != l.cr || l.ng != l.cg || l.nb != l.cb)
					{
						if (!floods.contains(l))
						{
							if (!ease)
							{
								for (Grid g : l.grid)
								{
									g.change = null;
								}
							}
							changed.add(l);
						}
					}
				}
				if (changed.size() > 64)
				{
					for (int i = 0; i < LEVELS; ++i)
					{
						boxes.get(i).clear();
						whole(boxes.get(i), i);
					}
				}
				else
				{
					for (Lamp l : changed)
					{
						// a hand-over's boxes go first, or a box after them would show its step early
						List<List<int[]>> to = handed.contains(l) ? steps : boxes;
						for (int i = 0; i < LEVELS; ++i)
						{
							slices(to.get(i), box(l, i));
						}
					}
					// lamps' boxes overlap: past a level's size it's less to send it whole
					for (int i = 0; i < LEVELS; ++i)
					{
						long cells = 0;
						for (int[] b : boxes.get(i))
						{
							cells += (long) b[3] * b[4] * b[5];
						}
						if (cells > (long) SIZE * SIZE * DEPTH[i])
						{
							boxes.get(i).clear();
							whole(boxes.get(i), i);
						}
					}
				}
			}
			for (Lamp l : lamps.values())
			{
				l.cr = l.nr;
				l.cg = l.ng;
				l.cb = l.nb;
				if (l.grid[L_NEAR].before != null && now - l.easeFrom >= EASE_NANOS)
				{
					for (Grid g : l.grid)
					{
						g.before = null;
					}
				}
				if (l.grid[L_NEAR].before == null)
				{
					for (Grid g : l.grid)
					{
						g.change = null;
					}
				}
			}
			final List<Lamp> list = new ArrayList<>(lamps.values());
			int count = 0;
			for (int i = 0; i < LEVELS; ++i)
			{
				count += steps.get(i).size() + boxes.get(i).size();
			}
			final Upload[] uploads = new Upload[count];
			tasks.clear();
			int k = 0;
			for (List<List<int[]>> group : List.of(steps, boxes))
			{
				for (int i = 0; i < LEVELS; ++i)
				{
					for (int[] b : group.get(i))
					{
						final int at = k++, lv = i;
						tasks.add(() -> uploads[at] = lv == L_FAR ? sumFar(b, list, now) : sumWalled(lv, b, list, now));
					}
				}
			}
			parallel(tasks);
			if (!handed.isEmpty() && count > 0)
			{
				int first = 0, bytes = 0;
				for (int i = 0; i < LEVELS; ++i)
				{
					first += steps.get(i).size();
				}
				for (int i = 0; i < first; ++i)
				{
					bytes += (uploads[i].a.length + uploads[i].b.length) * 2;
				}
				for (int i = 0; i + 1 < first && bytes <= HAND_BYTES; ++i)
				{
					uploads[i].joined = true;
				}
				// none of its own, when all was summed: the job's last
				uploads[first > 0 ? first - 1 : count - 1].handEnd = true;
			}
			long t4 = System.nanoTime();
			sentAll = true;
			++atkJobs;
			doneEverything = everything;
			done = new ArrayList<>(Arrays.asList(uploads));
			lastJob = String.format("window " + window[L_NEAR][0] + "," + window[L_NEAR][1] + "," + window[L_NEAR][2] + " zones %d voxelize %.1f bits %.1f flood %d lamps %.1f sum %d boxes %.1f ms", built.size(), (t1 - t0) / 1e6, (t2 - t1) / 1e6,
				floods.size(), (t3 - t2) / 1e6, uploads.length, (t4 - t3) / 1e6);
			if (Boolean.getBoolean("lumina.volLog") || System.getenv("LUMINA_VOL_LOG") != null)
			{
				System.out.println("lamp volume: " + lastJob);
			}
		}

		private boolean changed(Lamp l)
		{
			for (Grid g : l.grid)
			{
				if (g.change != null)
				{
					return true;
				}
			}
			return false;
		}

		private boolean shown(Segment s)
		{
			if (s.level < minLevel || s.level > maxLevel)
			{
				return false;
			}
			if (hidden.isEmpty() || s.level <= level || s.tail)
			{
				return true;
			}
			return s.rid > 0 && !hidden.contains(s.rid);
		}
	}

	// all of a level, as boxes of a few layers each: what a frame can send
	private static void whole(List<int[]> boxes, int i)
	{
		layers(boxes, 0, 0, SIZE, SIZE, 0, DEPTH[i]);
	}

	// the cells of a level's window that were not in the old one
	private static void cameIn(List<int[]> boxes, int i, int[] from, int[] to)
	{
		int c = CELLS[i];
		int dx = (to[0] - from[0]) / c, dy = (to[1] - from[1]) / c, dz = (to[2] - from[2]) / c;
		if (Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE || Math.abs(dy) >= DEPTH[i])
		{
			whole(boxes, i);
			return;
		}
		if (dx != 0)
		{
			layers(boxes, dx > 0 ? SIZE - dx : 0, 0, Math.abs(dx), SIZE, 0, DEPTH[i]);
		}
		if (dz != 0)
		{
			layers(boxes, 0, dz > 0 ? SIZE - dz : 0, SIZE, Math.abs(dz), 0, DEPTH[i]);
		}
		if (dy != 0)
		{
			layers(boxes, 0, 0, SIZE, SIZE, dy > 0 ? DEPTH[i] - dy : 0, Math.abs(dy));
		}
	}

	// a box of a level, as boxes of a few layers each
	private static void layers(List<int[]> boxes, int x, int z, int w, int d, int y0, int layers)
	{
		for (int y = 0; y < layers; y += 8)
		{
			boxes.add(new int[]{x, y0 + y, z, w, Math.min(8, layers - y), d});
		}
	}

	// a box as boxes of a few layers each
	private static void slices(List<int[]> boxes, int[] box)
	{
		if (box != null)
		{
			for (int y = 0; y < box[4]; y += 8)
			{
				boxes.add(new int[]{box[0], box[1] + y, box[2], box[3], Math.min(8, box[4] - y), box[5]});
			}
		}
	}

	private static long zoneKey(int x, int z)
	{
		return (long) x << 20 | z;
	}

	/** The cells a change made or took away, packed as a segment's are. */
	private static final class CellList
	{
		int[] cells = new int[1024];
		int count;

		void add(int cell)
		{
			if (count == cells.length)
			{
				cells = Arrays.copyOf(cells, count * 2);
			}
			cells[count++] = cell;
		}

		void add(int[] more)
		{
			for (int c : more)
			{
				add(c);
			}
		}
	}

	// whether a lamp's light gets to a cell that changed: lights it, or one next to it
	private static boolean lightsAny(Lamp l, CellList list)
	{
		Grid g = l.grid[L_FINE];
		if (g.vis == null)
		{
			return true;
		}
		int w = g.w, stepZ = w, stepY = w * w;
		for (int i = 0; i < list.count; ++i)
		{
			int k = list.cells[i];
			int x = (k & 2047) - g.x, z = (k >> 11 & 2047) - g.z, y = (k >> 22 & 255) - g.y;
			if (x >= 1 && y >= 1 && z >= 1 && x < w - 1 && y < w - 1 && z < w - 1)
			{
				int at = (y * w + z) * w + x;
				byte[] v = g.vis;
				if ((v[at] | v[at - 1] | v[at + 1] | v[at - stepZ] | v[at + stepZ] | v[at - stepY] | v[at + stepY]) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	// the box, in fine x and z, of the cells shown in one of the two and not the other, and those cells
	private static void difference(int[] box, ZoneCells before, ZoneCells after, CellList list)
	{
		int[] a = shownCells(before), b = shownCells(after);
		int i = 0, j = 0;
		while (i < a.length || j < b.length)
		{
			int cell;
			if (j == b.length || i < a.length && a[i] < b[j])
			{
				cell = a[i++];
			}
			else if (i == a.length || b[j] < a[i])
			{
				cell = b[j++];
			}
			else
			{
				++i;
				++j;
				continue;
			}
			int x = cell & 2047, z = cell >> 11 & 2047;
			box[0] = Math.min(box[0], x);
			box[1] = Math.min(box[1], z);
			box[2] = Math.max(box[2], x);
			box[3] = Math.max(box[3], z);
			list.add(cell);
		}
	}

	private static int[] shownCells(ZoneCells c)
	{
		if (c == null)
		{
			return new int[0];
		}
		int n = 0;
		for (Segment s : c.segments)
		{
			n += s.shown ? s.cells.length : 0;
		}
		int[] all = new int[n];
		n = 0;
		for (Segment s : c.segments)
		{
			if (s.shown)
			{
				System.arraycopy(s.cells, 0, all, n, s.cells.length);
				n += s.cells.length;
			}
		}
		Arrays.sort(all);
		int m = 0;
		for (int i = 0; i < n; ++i)
		{
			if (m == 0 || all[i] != all[m - 1])
			{
				all[m++] = all[i];
			}
		}
		return Arrays.copyOf(all, m);
	}

	// What a flood changed in a grid. A lamp the fine window has left floods at the near level's size, so its fine grid can be gone, and its grids
	// can lie differently from the last flood's: then there's no comparing them, and all of it counts as changed.
	private static int[] change(Grid g)
	{
		if (g.old == null || g.vis == null)
		{
			return null;
		}
		return sameCells(g) ? changedBox(g.vis, g.old, g.w, g.h, g.d) : new int[]{0, 0, 0, g.w, g.h, g.d};
	}

	private static boolean sameCells(Grid g)
	{
		return g.old != null && g.vis != null && g.x == g.oldX && g.y == g.oldY && g.z == g.oldZ && g.w == g.oldW && g.h == g.oldH && g.d == g.oldD;
	}

	// an ease goes cell by cell from the old grid: all of them there in the same place, or none
	private static boolean sameCells(Lamp l)
	{
		for (Grid g : l.grid)
		{
			if ((g.old != null || g.vis != null) && !sameCells(g))
			{
				return false;
			}
		}
		return true;
	}

	// where two grids of the same size differ, x0 y0 z0 x1 y1 z1; null if nowhere
	private static int[] changedBox(byte[] a, byte[] b, int w, int h, int d)
	{
		if (a.length != b.length)
		{
			return null;
		}
		int x0 = w, y0 = h, z0 = d, x1 = 0, y1 = 0, z1 = 0;
		int i = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int z = 0; z < d; ++z)
			{
				for (int x = 0; x < w; ++x, ++i)
				{
					if (a[i] != b[i])
					{
						x0 = Math.min(x0, x);
						y0 = Math.min(y0, y);
						z0 = Math.min(z0, z);
						x1 = Math.max(x1, x + 1);
						y1 = Math.max(y1, y + 1);
						z1 = Math.max(z1, z + 1);
					}
				}
			}
		}
		return x1 > 0 ? new int[]{x0, y0, z0, x1, y1, z1} : null;
	}

	private static void parallel(List<Runnable> tasks)
	{
		if (tasks.size() < 2)
		{
			tasks.forEach(Runnable::run);
			return;
		}
		List<Future<?>> f = new ArrayList<>(tasks.size());
		for (Runnable t : tasks)
		{
			f.add(POOL.submit(t));
		}
		for (Future<?> x : f)
		{
			try
			{
				x.get();
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// solid cells

	private static final int VERT_INTS = 6, TRI_INTS = VERT_INTS * 3, STORED = 7;

	/** A zone's stream as this class keeps it, as a zone's upload hands it over: per triangle its positions and its texture word. */
	public static int[] triangles(int[] stream, int length)
	{
		int count = length / TRI_INTS;
		int[] t = new int[count * STORED];
		for (int i = 0, o = 0, at = 0; i < count; ++i, at += TRI_INTS)
		{
			for (int k = 0; k < 3; ++k)
			{
				t[o++] = stream[at + k * VERT_INTS];
				t[o++] = stream[at + k * VERT_INTS + 1];
			}
			t[o++] = stream[at + 3];
		}
		return t;
	}

	// A zone's opaque triangles, as what stops light: by Zone.pushLevels's ranges, each run of them its own cells. Emissive faces out,
	// as the shadow pass leaves them out, and swaying foliage.
	// whether a place in the list came after this zone was looked at, near enough for a fixture of it to be in the zone
	private static boolean fixtureCame(ZoneCells c, int zx, int zz, float[] points)
	{
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		float x0 = ((zx - offset) << 10) - FIXTURE, z0 = ((zz - offset) << 10) - FIXTURE, x1 = x0 + 1024 + 2 * FIXTURE, z1 = z0 + 1024 + 2 * FIXTURE;
		for (int i = c.points; i + 4 < points.length; i += 5)
		{
			float x = points[i], z = points[i + 2], ox = points[i + 3], oz = points[i + 4];
			if (x > x0 && x < x1 && z > z0 && z < z1 || ox > x0 && ox < x1 && oz > z0 && oz < z1)
			{
				return true;
			}
		}
		return false;
	}

	private ZoneCells voxelize(Zone zone, int zx, int zz, float[] allPoints)
	{
		return voxelize(zone.lampTriangles, zone.levelOffsets, zone.rids, zone.roofStart, zone.roofEnd, zx, zz, allPoints);
	}

	private ZoneCells voxelize(int[] t, int[] levelOffsets, int[][] allRids, int[][] allStart, int[][] allEnd, int zx, int zz, float[] allPoints)
	{
		ZoneCells out = new ZoneCells();
		out.triangles = t;
		out.points = allPoints.length;
		if (t == null)
		{
			out.segments = new Segment[0];
			return out;
		}
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		int baseX = (zx - offset) << 10, baseZ = (zz - offset) << 10;
		int count = t.length / STORED;
		// the lamps near the zone
		float[] points = new float[allPoints.length];
		int np = 0;
		for (int i = 0; i < allPoints.length; i += 5)
		{
			float x = allPoints[i], z = allPoints[i + 2];
			if (x > baseX - 512 && x < baseX + 1536 && z > baseZ - 512 && z < baseZ + 1536)
			{
				System.arraycopy(allPoints, i, points, np, 5);
				np += 5;
			}
		}
		points = Arrays.copyOf(points, np);
		List<Segment> segs = new ArrayList<>();
		float[] v = new float[9];
		for (int l = 0; l < 4; ++l)
		{
			int start = l == 0 ? 0 : levelOffsets[l - 1], end = levelOffsets[l];
			int[] rids = allRids[l], roofStart = allStart[l], roofEnd = allEnd[l];
			int tail = start;
			for (int i = rids.length - 1; i >= 0; --i)
			{
				if (rids[i] > 0)
				{
					tail = roofEnd[i];
					break;
				}
			}
			// the roofs' runs, by id, then what lies between them and the tail
			boolean[] taken = new boolean[count];
			for (int i = 0; i < rids.length; ++i)
			{
				if (roofEnd[i] > roofStart[i])
				{
					Segment seg = new Segment();
					seg.level = l;
					seg.rid = rids[i];
					cells(t, roofStart[i], roofEnd[i], baseX, baseZ, seg, v, taken, points);
					if (seg.cells != null)
					{
						segs.add(seg);
					}
				}
			}
			Segment before = new Segment();
			before.level = l;
			cells(t, start, tail, baseX, baseZ, before, v, taken, points);
			if (before.cells != null)
			{
				segs.add(before);
			}
			Segment after = new Segment();
			after.level = l;
			after.tail = true;
			cells(t, tail, end, baseX, baseZ, after, v, taken, points);
			if (after.cells != null)
			{
				segs.add(after);
			}
		}
		out.segments = segs.toArray(new Segment[0]);
		return out;
	}

	// whether the triangle lies within FIXTURE of a lamp's place or origin: its own fixture, which stops nothing of it
	private static boolean onLamp(float[] v, float[] points)
	{
		float f2 = FIXTURE * FIXTURE;
		for (int i = 0; i < points.length; i += 5)
		{
			boolean all = true;
			for (int k = 0; k < 9 && all; k += 3)
			{
				float dx = v[k] - points[i], dy = v[k + 1] - points[i + 1], dz = v[k + 2] - points[i + 2];
				float ox = v[k] - points[i + 3], oz = v[k + 2] - points[i + 4];
				all = dx * dx + dy * dy + dz * dz < f2 || ox * ox + dy * dy + oz * oz < f2;
			}
			if (all)
			{
				return true;
			}
		}
		return false;
	}

	// from and to are places in the stream, as a zone's ranges have them
	private static void cells(int[] t, int from, int to, int baseX, int baseZ, Segment seg, float[] v, boolean[] taken, float[] points)
	{
		int[] buf = new int[1024];
		int n = 0;
		int count = Math.min(to / TRI_INTS, t.length / STORED);
		float half = FINE * 0.5f + 0.5f;
		for (int tri = from / TRI_INTS; tri < count; ++tri)
		{
			if (taken[tri])
			{
				continue;
			}
			taken[tri] = true;
			int i = tri * STORED;
			int texWord = (short) t[i + 6];
			// emissive; foliage
			if ((texWord >> 9 & 3) != 0 || (texWord >> 11 & 1) != 0)
			{
				continue;
			}
			float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f, minZ = 1e9f, maxZ = -1e9f;
			for (int k = 0; k < 3; ++k)
			{
				int w0 = t[i + k * 2], w1 = t[i + k * 2 + 1];
				float x = (short) w0 + baseX, y = (short) (w0 >> 16), z = (short) w1 + baseZ;
				v[k * 3] = x;
				v[k * 3 + 1] = y;
				v[k * 3 + 2] = z;
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = Math.max(maxY, y);
				minZ = Math.min(minZ, z);
				maxZ = Math.max(maxZ, z);
			}
			float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2], bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			if (nx * nx + ny * ny + nz * nz < 1e-4f || onLamp(v, points))
			{
				continue;
			}
			// a floor's cells stand proud of it by up to a cell, and stop light grazing it; lower it a little (y is down)
			if (GROUND_DROP > 0 && ny * ny > 0.5f * (nx * nx + ny * ny + nz * nz))
			{
				minY += GROUND_DROP;
				maxY += GROUND_DROP;
				v[1] += GROUND_DROP;
				v[4] += GROUND_DROP;
				v[7] += GROUND_DROP;
			}
			int x0 = Math.max(0, Math.floorDiv((int) Math.floor(minX) - GRID_X, FINE)), x1 = Math.min(CHUNK * CHUNKS - 1, Math.floorDiv((int) Math.floor(maxX) - GRID_X, FINE));
			int z0 = Math.max(0, Math.floorDiv((int) Math.floor(minZ) - GRID_X, FINE)), z1 = Math.min(CHUNK * CHUNKS - 1, Math.floorDiv((int) Math.floor(maxZ) - GRID_X, FINE));
			int y0 = Math.max(0, Math.floorDiv((int) Math.floor(minY) - GRID_Y, FINE)), y1 = Math.min(FINE_LAYERS - 1, Math.floorDiv((int) Math.floor(maxY) - GRID_Y, FINE));
			for (int y = y0; y <= y1; ++y)
			{
				for (int z = z0; z <= z1; ++z)
				{
					for (int x = x0; x <= x1; ++x)
					{
						if (Voxelizer.triBoxOverlap((x + 0.5f) * FINE + GRID_X, (y + 0.5f) * FINE + GRID_Y, (z + 0.5f) * FINE + GRID_X, half, v))
						{
							if (n == buf.length)
							{
								buf = Arrays.copyOf(buf, n * 2);
							}
							buf[n++] = x | z << 11 | y << 22;
							seg.minX = Math.min(seg.minX, x);
							seg.maxX = Math.max(seg.maxX, x);
							seg.minZ = Math.min(seg.minZ, z);
							seg.maxZ = Math.max(seg.maxZ, z);
						}
					}
				}
			}
		}
		if (n == 0)
		{
			return;
		}
		Arrays.sort(buf, 0, n);
		int m = 1;
		for (int i = 1; i < n; ++i)
		{
			if (buf[i] != buf[m - 1])
			{
				buf[m++] = buf[i];
			}
		}
		seg.cells = Arrays.copyOf(buf, m);
	}

	// The solid bits again, inside a box of fine x, z, x, z or all of them. A cell of a coarser level is outside the box and still depends on
	// it by a few fine cells: its column is cleared and set again from those.
	private void rebuildBits(int[] box)
	{
		if (box != null && box[0] > box[2])
		{
			return;
		}
		int span = 2 * CHUNK;
		if (box != null && (box[2] - box[0] + 1) * (box[3] - box[1] + 1) < span * span)
		{
			int x0 = Math.max(0, box[0]), z0 = Math.max(0, box[1]), x1 = Math.min(CHUNK * CHUNKS - 1, box[2]), z1 = Math.min(CHUNK * CHUNKS - 1, box[3]);
			for (int z = z0; z <= z1; ++z)
			{
				for (int x = x0; x <= x1; ++x)
				{
					long[] f = fine[(z >> 6) * CHUNKS + (x >> 6)];
					if (f != null)
					{
						Arrays.fill(f, ((z & 63) << 6 | (x & 63)) * COLUMN_LONGS, (((z & 63) << 6 | (x & 63)) + 1) * COLUMN_LONGS, 0);
					}
				}
			}
			for (int z = z0 + 1 >> 1; z <= Math.min(CHUNK * CHUNKS / 2 - 1, z1 + 1 >> 1); ++z)
			{
				for (int x = x0 + 1 >> 1; x <= Math.min(CHUNK * CHUNKS / 2 - 1, x1 + 1 >> 1); ++x)
				{
					long[] f = coarse[(z >> 5) * CHUNKS + (x >> 5)];
					if (f != null)
					{
						int at = ((z & 31) << 5 | (x & 31)) * (COLUMN_LONGS / 2);
						Arrays.fill(f, at, at + COLUMN_LONGS / 2, 0);
					}
				}
			}
			for (int z = z0 + 2 >> 2; z <= Math.min(CHUNK * CHUNKS / 4 - 1, z1 + 2 >> 2); ++z)
			{
				for (int x = x0 + 2 >> 2; x <= Math.min(CHUNK * CHUNKS / 4 - 1, x1 + 2 >> 2); ++x)
				{
					long[] f = mid[(z >> 4) * CHUNKS + (x >> 4)];
					if (f != null)
					{
						f[(z & 15) << 4 | x & 15] = 0;
					}
				}
			}
			int ax = x0 - 4, az = z0 - 4, bx = x1 + 4, bz = z1 + 4;
			for (ZoneCells c : zones.values())
			{
				for (Segment s : c.segments)
				{
					if (s.shown && s.minX <= bx && s.maxX >= ax && s.minZ <= bz && s.maxZ >= az)
					{
						for (int k : s.cells)
						{
							int x = k & 2047, z = k >> 11 & 2047;
							if (x >= ax && x <= bx && z >= az && z <= bz)
							{
								setFine(x, k >> 22 & 255, z);
							}
						}
					}
				}
			}
			return;
		}
		for (long[][] bits : new long[][][]{fine, coarse, mid})
		{
			for (long[] c : bits)
			{
				if (c != null)
				{
					Arrays.fill(c, 0);
				}
			}
		}
		for (ZoneCells z : zones.values())
		{
			for (Segment s : z.segments)
			{
				if (s.shown)
				{
					for (int k : s.cells)
					{
						setFine(k & 2047, k >> 22 & 255, k >> 11 & 2047);
					}
				}
			}
		}
	}

	private void setFine(int x, int y, int z)
	{
		int c = (z >> 6) * CHUNKS + (x >> 6);
		long[] f = fine[c];
		if (f == null)
		{
			f = fine[c] = new long[FINE_CHUNK_LONGS];
		}
		f[(((z & 63) << 6 | (x & 63)) * COLUMN_LONGS) + (y >> 6)] |= 1L << (y & 63);
		// a read cell is two fine cells either side of a multiple of CELL, so a wall on a tile's edge makes one layer of them solid,
		// not two
		int cx = x + 1 >> 1, cz = z + 1 >> 1, cy = y + 1 >> 1;
		int cc = (cz >> 5) * CHUNKS + (cx >> 5);
		if (cx >> 5 < CHUNKS && cc < coarse.length && cy < FINE_LAYERS / 2)
		{
			long[] g = coarse[cc];
			if (g == null)
			{
				g = coarse[cc] = new long[COARSE_CHUNK_LONGS];
			}
			g[(((cz & 31) << 5 | (cx & 31)) * (COLUMN_LONGS / 2)) + (cy >> 6)] |= 1L << (cy & 63);
		}
		// and the mid level's, four fine cells round a multiple of its size
		int mx = x + 2 >> 2, mz = z + 2 >> 2, my = y + 2 >> 2;
		int mc = (mz >> 4) * CHUNKS + (mx >> 4);
		if (mx >> 4 < CHUNKS && mc < mid.length && my < FINE_LAYERS / 4)
		{
			long[] g = mid[mc];
			if (g == null)
			{
				g = mid[mc] = new long[MID_CHUNK_LONGS];
			}
			g[(mz & 15) << 4 | mx & 15] |= 1L << my;
		}
	}

	private boolean solidFine(int x, int y, int z)
	{
		if (x < 0 || z < 0 || y < 0 || x >= CHUNK * CHUNKS || z >= CHUNK * CHUNKS || y >= FINE_LAYERS)
		{
			return false;
		}
		long[] f = fine[(z >> 6) * CHUNKS + (x >> 6)];
		return f != null && (f[(((z & 63) << 6 | (x & 63)) * COLUMN_LONGS) + (y >> 6)] >>> (y & 63) & 1) != 0;
	}

	// whether every fine cell of a read cell is solid
	private boolean allSolid(int x, int y, int z)
	{
		for (int k = 0; k < 8; ++k)
		{
			if (!solidFine(2 * x - 1 + (k & 1), 2 * y - 1 + (k >> 1 & 1), 2 * z - 1 + (k >> 2)))
			{
				return false;
			}
		}
		return true;
	}

	// a read cell, x y z in CELL units from the grid's corner
	private boolean solidMid(int x, int y, int z)
	{
		if (x < 0 || z < 0 || y < 0 || x >= CHUNK * CHUNKS / 4 || z >= CHUNK * CHUNKS / 4 || y >= FINE_LAYERS / 4)
		{
			return false;
		}
		long[] f = mid[(z >> 4) * CHUNKS + (x >> 4)];
		return f != null && (f[(z & 15) << 4 | x & 15] >>> y & 1) != 0;
	}

	private boolean solidCoarse(int x, int y, int z)
	{
		if (x < 0 || z < 0 || y < 0 || x >= CHUNK * CHUNKS / 2 || z >= CHUNK * CHUNKS / 2 || y >= FINE_LAYERS / 2)
		{
			return false;
		}
		long[] f = coarse[(z >> 5) * CHUNKS + (x >> 5)];
		return f != null && (f[(((z & 31) << 5 | (x & 31)) * (COLUMN_LONGS / 2)) + (y >> 6)] >>> (y & 63) & 1) != 0;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the flood

	private static final class Scratch
	{
		float[] v = new float[1 << 16];
		boolean[] solid = new boolean[1 << 16];
		float[] sum = new float[1 << 14], next = new float[1 << 14];
		long[][] column = new long[1 << 10][];
		int[] columnAt = new int[1 << 10];
		short[] count = new short[1 << 14], trace = new short[1 << 14];
	}

	// A lamp's flood: shells of a cube round its origin, each cell the bilinear value of where its ray to the lamp crosses the
	// shell's plane before it, on the axis the ray runs most along. Then the crust, the smoothing, and per read cell the mean of its free
	// cells, sharpened.
	private void flood(Lamp l, Scratch sc)
	{
		// a lamp clear of the fine window floods its cells at the near level's size: an eighth of the work, for a level it can't reach
		boolean wide = !nearFine(l, FINE_MARGIN);
		int s = wide ? CELL : FINE;
		int n = (int) Math.ceil(l.r / s) + 1;
		int w = 2 * n + 1, cells = w * w * w;
		if (sc.v.length < cells)
		{
			sc.v = new float[cells];
			sc.solid = new boolean[cells];
		}
		float[] v = sc.v;
		boolean[] solid = sc.solid;
		Arrays.fill(v, 0, cells, 0);
		// a near cell is centred on a multiple of its size
		float half = wide ? 0.5f : 0;
		float gx = (l.ox - GRID_X) / s + half, gy = (l.y - GRID_Y) / s + half, gz = (l.oz - GRID_X) / s + half;
		int lx = (int) Math.floor(gx), ly = (int) Math.floor(gy), lz = (int) Math.floor(gz);
		float ox = gx - lx - 0.5f, oy = gy - ly - 0.5f, oz = gz - lz - 0.5f;
		float reach = (l.r + s) / s, reach2 = reach * reach;
		// each column's bits once, then a load a cell
		if (sc.column.length < w * w)
		{
			sc.column = new long[w * w][];
			sc.columnAt = new int[w * w];
		}
		long[][] column = sc.column;
		int[] columnAt = sc.columnAt;
		for (int dz = -n, c = 0; dz <= n; ++dz)
		{
			for (int dx = -n; dx <= n; ++dx, ++c)
			{
				int x = lx + dx, z = lz + dz;
				if (wide)
				{
					boolean in = x >= 0 && z >= 0 && x < CHUNK * CHUNKS / 2 && z < CHUNK * CHUNKS / 2;
					column[c] = in ? coarse[(z >> 5) * CHUNKS + (x >> 5)] : null;
					columnAt[c] = in ? ((z & 31) << 5 | (x & 31)) * (COLUMN_LONGS / 2) : 0;
				}
				else
				{
					boolean in = x >= 0 && z >= 0 && x < CHUNK * CHUNKS && z < CHUNK * CHUNKS;
					column[c] = in ? fine[(z >> 6) * CHUNKS + (x >> 6)] : null;
					columnAt[c] = in ? ((z & 63) << 6 | (x & 63)) * COLUMN_LONGS : 0;
				}
			}
		}
		int i = 0;
		for (int dy = -n; dy <= n; ++dy)
		{
			int y = ly + dy;
			boolean inY = y >= 0 && y < (wide ? FINE_LAYERS / 2 : FINE_LAYERS);
			int word = y >> 6, bit = y & 63;
			for (int dz = -n, c = 0; dz <= n; ++dz, i += w, c += w)
			{
				// outside the ball nothing flows: a row's ends count as solid
				float left = reach2 - dy * dy - dz * dz;
				int end = left < 0 ? -1 : Math.min(n, (int) Math.sqrt(left));
				Arrays.fill(solid, i, i + n - end, true);
				Arrays.fill(solid, i + n + end + 1, i + w, true);
				for (int at = n - end; at <= n + end; ++at)
				{
					long[] f = inY ? column[c + at] : null;
					solid[i + at] = f != null && (f[columnAt[c + at] + word] >>> bit & 1) != 0;
				}
			}
		}
		float seed = SEED / s, seed2 = seed * seed;
		int centre = (n * w + n) * w + n;
		int seedCells = (int) Math.ceil(seed) + 1;
		for (int dy = -seedCells; dy <= seedCells; ++dy)
		{
			for (int dz = -seedCells; dz <= seedCells; ++dz)
			{
				for (int dx = -seedCells; dx <= seedCells; ++dx)
				{
					float ax = dx - ox, ay = dy - oy, az = dz - oz;
					if (ax * ax + ay * ay + az * az <= seed2 || dx == 0 && dy == 0 && dz == 0)
					{
						v[centre + (dy * w + dz) * w + dx] = 1;
					}
				}
			}
		}
		int stepY = w * w, stepZ = w;
		for (int m = 1; m <= n; ++m)
		{
			// the six faces of shell m, by the axis the ray runs most along
			for (int dy = -m; dy <= m; ++dy)
			{
				for (int dz = -m; dz <= m; ++dz)
				{
					boolean inner = Math.abs(dy) < m && Math.abs(dz) < m;
					for (int dx = -m; dx <= m; dx += inner ? 2 * m : 1)
					{
						int at = centre + (dy * w + dz) * w + dx;
						if (solid[at] || v[at] != 0)
						{
							continue;
						}
						float ax = dx - ox, ay = dy - oy, az = dz - oz;
						float bx = Math.abs(ax), by = Math.abs(ay), bz = Math.abs(az);
						float value;
						if (bx >= by && bx >= bz)
						{
							float t = 1 / bx;
							value = plane(v, solid, at - (ax > 0 ? 1 : -1), ay * t, az * t, stepY, stepZ);
						}
						else if (by >= bz)
						{
							float t = 1 / by;
							value = plane(v, solid, at - (ay > 0 ? stepY : -stepY), ax * t, az * t, 1, stepZ);
						}
						else
						{
							float t = 1 / bz;
							value = plane(v, solid, at - (az > 0 ? stepZ : -stepZ), ax * t, ay * t, 1, stepY);
						}
						v[at] = value;
					}
				}
			}
		}
		crust(sc, n, w, ox, oy, oz, cells);
		smooth(sc, w, cells);
		v = sc.v;
		// per fine cell. A solid one holds what its free neighbours see, for a read with nothing free near it
		int fx0 = lx - n, fy0 = ly - n, fz0 = lz - n;
		if (wide)
		{
			reduce(l.grid[L_NEAR], L_NEAR, 0, sc, w, fx0, fy0, fz0);
			reduce(l.grid[L_MID], L_MID, 1, sc, w, fx0, fy0, fz0);
			Grid g = l.grid[L_FINE];
			g.vis = null;
			g.before = null;
			g.w = g.h = g.d = 0;
			return;
		}
		byte[] fineVis = new byte[cells];
		i = 0;
		for (int y = 0; y < w; ++y)
		{
			for (int z = 0; z < w; ++z)
			{
				for (int x = 0; x < w; ++x, ++i)
				{
					float o = v[i];
					if (solid[i])
					{
						if (x == 0 || y == 0 || z == 0 || x == w - 1 || y == w - 1 || z == w - 1)
						{
							continue;
						}
						if (v[i - 1] + v[i + 1] + v[i - stepZ] + v[i + stepZ] + v[i - stepY] + v[i + stepY] <= 0)
						{
							continue;
						}
						// the least any free neighbour has: a read that has a free cell can't take through a trace what that side doesn't see
						o = 2;
						for (int ay = -stepY; ay <= stepY; ay += stepY)
						{
							for (int az = -stepZ; az <= stepZ; az += stepZ)
							{
								for (int ax = -1; ax <= 1; ++ax)
								{
									int at = i + ay + az + ax;
									if (!solid[at] && v[at] < o)
									{
										o = v[at];
									}
								}
							}
						}
						if (o > 1)
						{
							continue;
						}
					}
					fineVis[i] = (byte) (sharpen(o, SHARP_LO, SHARP_HI) * 255 + 0.5f);
				}
			}
		}
		reduce(l.grid[L_NEAR], L_NEAR, 1, sc, w, fx0, fy0, fz0);
		reduce(l.grid[L_MID], L_MID, 2, sc, w, fx0, fy0, fz0);
		Grid g = l.grid[L_FINE];
		g.vis = fineVis;
		g.x = fx0;
		g.y = fy0;
		g.z = fz0;
		g.w = w;
		g.h = w;
		g.d = w;
	}

	// whether a lamp's reach comes within a margin of the fine window
	private boolean nearFine(Lamp l, float margin)
	{
		int[] w = window[L_FINE];
		float half = SIZE * FINE / 2f;
		float e = Math.max(Math.abs(l.x - (w[0] + half)), Math.abs(l.z - (w[2] + half)));
		return e <= half + l.r + margin;
	}

	// a lamp's visibility per cell of a coarser level, from the flood's: the mean of the cell's free fine cells; a solid cell keeps a
	// trace, no more than its least free neighbour
	private void reduce(Grid g, int lv, int shift, Scratch sc, int w, int fx0, int fy0, int fz0)
	{
		float[] v = sc.v;
		boolean[] solid = sc.solid;
		int half = 1 << shift >> 1;
		int bx = fx0 + half >> shift, by = fy0 + half >> shift, bz = fz0 + half >> shift;
		int bw = (fx0 + w - 1 + half >> shift) - bx + 1, bh = (fy0 + w - 1 + half >> shift) - by + 1, bd = (fz0 + w - 1 + half >> shift) - bz + 1;
		int total = bw * bh * bd;
		if (sc.sum.length < total)
		{
			sc.sum = new float[total];
			sc.count = new short[total];
			sc.trace = new short[total];
		}
		float[] sum = sc.sum;
		short[] count = sc.count, trace = sc.trace;
		Arrays.fill(sum, 0, total, 0);
		Arrays.fill(count, 0, total, (short) 0);
		int i = 0;
		for (int y = 0; y < w; ++y)
		{
			int cy = (fy0 + y + half >> shift) - by;
			for (int z = 0; z < w; ++z)
			{
				int cz = (fz0 + z + half >> shift) - bz;
				for (int x = 0; x < w; ++x, ++i)
				{
					if (!solid[i])
					{
						int c = (cy * bd + cz) * bw + (fx0 + x + half >> shift) - bx;
						sum[c] += v[i];
						++count[c];
					}
				}
			}
		}
		byte[] vis = new byte[total];
		for (int c = 0; c < total; ++c)
		{
			if (count[c] > 0 && sum[c] > 0)
			{
				vis[c] = (byte) (sharpen(sum[c] / count[c], lv == L_MID ? MID_LO : SHARP_LO, lv == L_MID ? MID_HI : SHARP_HI) * 255 + 0.5f);
			}
		}
		// a solid cell's trace, as a fine one's: no more than its least free neighbour
		for (int y = 0, c = 0; y < bh; ++y)
		{
			for (int z = 0; z < bd; ++z)
			{
				for (int x = 0; x < bw; ++x, ++c)
				{
					if (vis[c] != 0 && solid(lv, bx + x, by + y, bz + z))
					{
						int least = 256;
						for (int ay = Math.max(0, y - 1); ay <= Math.min(bh - 1, y + 1); ++ay)
						{
							for (int az = Math.max(0, z - 1); az <= Math.min(bd - 1, z + 1); ++az)
							{
								for (int ax = Math.max(0, x - 1); ax <= Math.min(bw - 1, x + 1); ++ax)
								{
									int b = vis[(ay * bd + az) * bw + ax] & 255;
									if (b < least && !solid(lv, bx + ax, by + ay, bz + az))
									{
										least = b;
									}
								}
							}
						}
						trace[c] = (short) least;
					}
					else
					{
						trace[c] = -1;
					}
				}
			}
		}
		for (int c = 0; c < total; ++c)
		{
			if (trace[c] >= 0 && trace[c] < 256)
			{
				vis[c] = (byte) trace[c];
			}
		}
		g.vis = vis;
		g.x = bx;
		g.y = by;
		g.z = bz;
		g.w = bw;
		g.h = bh;
		g.d = bd;
	}

	private static float sharpen(float o, float lo, float hi)
	{
		if (o <= 0)
		{
			return 0;
		}
		float t = (o - lo) / (hi - lo);
		return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
	}

	// Conservative cells stand proud of a slope and stop light that grazes it. On a shadow's front, a free cell touching a surface, whose way to the
	// lamp leaves that surface at a shallow angle, takes what the flood has CRUST + 1 cells along that way, past the cells that only touch the surface.
	// That is a value of the flood's own smooth field: no ray's yes or no, which on a grid of these cells reads as steps (flood-volume/soft/results.md).
	// A steep way off a surface doesn't pass: nothing proud of the surface stops it, so what stops it is a real blocker. Nothing here feeds the flood.
	private static void crust(Scratch sc, int n, int w, float ox, float oy, float oz, int cells)
	{
		float[] v = sc.v;
		boolean[] solid = sc.solid;
		float[] out = sc.next;
		if (out.length < cells)
		{
			out = sc.next = new float[cells];
		}
		System.arraycopy(v, 0, out, 0, cells);
		int stepZ = w, stepY = w * w, k = CRUST + 1;
		for (int y = 1, i0 = stepY; y < w - 1; ++y, i0 += stepY)
		{
			for (int z = 1; z < w - 1; ++z)
			{
				int i = i0 + z * stepZ + 1;
				for (int x = 1; x < w - 1; ++x, ++i)
				{
					if (solid[i] || v[i] >= 1 || !(solid[i - 1] || solid[i + 1] || solid[i - stepZ] || solid[i + stepZ] || solid[i - stepY] || solid[i + stepY]))
					{
						continue;
					}
					// the dark side of a front is a cell off the light
					if (v[i] <= 0 && v[i - 1] <= 0 && v[i + 1] <= 0 && v[i - stepZ] <= 0 && v[i + stepZ] <= 0 && v[i - stepY] <= 0 && v[i + stepY] <= 0)
					{
						continue;
					}
					float ax = x - n - ox, ay = y - n - oy, az = z - n - oz;
					float bx = Math.abs(ax), by = Math.abs(ay), bz = Math.abs(az);
					float big = Math.max(bx, Math.max(by, bz));
					if (big <= k + 1)
					{
						continue;
					}
					// the way out of the crust, from what is solid round it to what is free
					float ox2 = 0, oy2 = 0, oz2 = 0;
					for (int dy = -1, at = i - stepY - stepZ - 1; dy <= 1; ++dy, at += stepY - 3 * stepZ)
					{
						for (int dz = -1; dz <= 1; ++dz, at += stepZ - 3)
						{
							for (int dx = -1; dx <= 1; ++dx, ++at)
							{
								if (solid[at])
								{
									ox2 -= dx;
									oy2 -= dy;
									oz2 -= dz;
								}
							}
						}
					}
					// toward the lamp is minus a
					float along = -(ax * ox2 + ay * oy2 + az * oz2);
					if (along <= 0 || along * along > GRAZE * GRAZE * (ax * ax + ay * ay + az * az) * (ox2 * ox2 + oy2 * oy2 + oz2 * oz2))
					{
						continue;
					}
					float t = k / big, value;
					if (bx >= by && bx >= bz)
					{
						value = far(v, solid, i - (ax > 0 ? k : -k), ay * t, az * t, stepY, stepZ);
					}
					else if (by >= bz)
					{
						value = far(v, solid, i - (ay > 0 ? k : -k) * stepY, ax * t, az * t, 1, stepZ);
					}
					else
					{
						value = far(v, solid, i - (az > 0 ? k : -k) * stepZ, ax * t, ay * t, 1, stepY);
					}
					if (value > out[i])
					{
						out[i] = value;
					}
				}
			}
		}
		System.arraycopy(out, 0, v, 0, cells);
	}

	// A read's cells are a straight edge's stairs unless the edge spans a few of them. So each pass, a free cell takes the mean of itself and its free
	// face neighbours. A step goes only between free cells that share a face, and a closed surface's conservative cells separate those, so
	// this can't carry light through anything closed, however many passes run: it only rounds a shadow's edge in the air the edge is in.
	private static void smooth(Scratch sc, int w, int cells)
	{
		float[] v = sc.v;
		boolean[] solid = sc.solid;
		float[] out = sc.next;
		if (out.length < cells)
		{
			out = sc.next = new float[cells];
		}
		int stepZ = w, stepY = w * w;
		for (int pass = 0; pass < SMOOTH; ++pass)
		{
			System.arraycopy(v, 0, out, 0, cells);
			for (int y = 1, i0 = stepY; y < w - 1; ++y, i0 += stepY)
			{
				for (int z = 1; z < w - 1; ++z)
				{
					int i = i0 + z * stepZ + 1;
					for (int x = 1; x < w - 1; ++x, ++i)
					{
						float c = v[i];
						// most cells: all open, all dark, or solid
						if (solid[i] || c == v[i - 1] && c == v[i + 1] && c == v[i - stepZ] && c == v[i + stepZ] && c == v[i - stepY] && c == v[i + stepY])
						{
							continue;
						}
						float sum = c * SMOOTH_SELF, weight = SMOOTH_SELF;
						for (int k = 0; k < 6; ++k)
						{
							int at = i + (k == 0 ? -1 : k == 1 ? 1 : k == 2 ? -stepZ : k == 3 ? stepZ : k == 4 ? -stepY : stepY);
							if (!solid[at])
							{
								sum += v[at];
								++weight;
							}
						}
						out[i] = sum / weight;
					}
				}
			}
			float[] t = v;
			v = out;
			out = t;
		}
		sc.v = v;
		sc.next = out;
	}

	// plane's read of a plane further along the ray: a and b can be past a cell, and what's solid in it doesn't count
	private static float far(float[] v, boolean[] solid, int at, float a, float b, int stepA, int stepB)
	{
		float fa = -a, fb = -b;
		int ia = (int) Math.floor(fa), ib = (int) Math.floor(fb);
		float ta = fa - ia, tb = fb - ib;
		int p = at + ia * stepA + ib * stepB;
		float sum = 0, weight = 0, w;
		w = (1 - ta) * (1 - tb);
		if (!solid[p])
		{
			sum += w * v[p];
			weight += w;
		}
		w = ta * (1 - tb);
		if (!solid[p + stepA])
		{
			sum += w * v[p + stepA];
			weight += w;
		}
		w = (1 - ta) * tb;
		if (!solid[p + stepB])
		{
			sum += w * v[p + stepB];
			weight += w;
		}
		w = ta * tb;
		if (!solid[p + stepA + stepB])
		{
			sum += w * v[p + stepA + stepB];
			weight += w;
		}
		return weight > 0 ? sum / weight : 0;
	}

	private static float plane(float[] v, boolean[] solid, int at, float a, float b, int stepA, int stepB)
	{
		// the ray's crossing, relative to the cell behind this one: toward the lamp is minus the slope
		float fa = -a, fb = -b;
		int ia = fa < 0 ? -1 : 0, ib = fb < 0 ? -1 : 0;
		float ta = fa - ia, tb = fb - ib;
		int p = at + ia * stepA + ib * stepB;
		float v00 = v[p], v10 = v[p + stepA], v01 = v[p + stepB], v11 = v[p + stepA + stepB];
		float l = v00 + (v10 - v00) * ta, h = v01 + (v11 - v01) * ta;
		float value = l + (h - l) * tb;
		if (value > 0)
		{
			// the part of the read that fell on solid cells counts for less than free ones
			float blocked = (solid[p] ? (1 - ta) * (1 - tb) : 0) + (solid[p + stepA] ? ta * (1 - tb) : 0) + (solid[p + stepB] ? (1 - ta) * tb : 0) + (solid[p + stepA + stepB] ? ta * tb : 0);
			value /= 1 - blocked * (1 - SOLID);
		}
		return value;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the sum

	// a lamp's box in a level's window, x y z w h d; of what changed, if a change is known
	private int[] box(Lamp l, int i)
	{
		int[] win = window[i];
		if (i == L_FAR)
		{
			Grid g = l.grid[L_NEAR];
			int r = FAR_CELL / CELL;
			int ox = Math.floorDiv(win[0] - GRID_X, FAR_CELL), oy = Math.floorDiv(win[1] - GRID_Y, FAR_CELL), oz = Math.floorDiv(win[2] - GRID_X, FAR_CELL);
			int[] t = g.change != null ? g.change : new int[]{0, 0, 0, g.w, g.h, g.d};
			int x0 = Math.max(0, Math.floorDiv(g.x + t[0], r) - ox), y0 = Math.max(0, Math.floorDiv(g.y + t[1], r) - oy), z0 = Math.max(0, Math.floorDiv(g.z + t[2], r) - oz);
			int x1 = Math.min(SIZE, Math.floorDiv(g.x + t[3] - 1, r) - ox + 1), y1 = Math.min(FAR_Y, Math.floorDiv(g.y + t[4] - 1, r) - oy + 1), z1 = Math.min(SIZE, Math.floorDiv(g.z + t[5] - 1, r) - oz + 1);
			return x1 > x0 && y1 > y0 && z1 > z0 ? new int[]{x0, y0, z0, x1 - x0, y1 - y0, z1 - z0} : null;
		}
		Grid g = l.grid[i];
		int c = CELLS[i];
		int ox = Math.floorDiv(win[0] - GRID_X, c), oy = Math.floorDiv(win[1] - GRID_Y, c), oz = Math.floorDiv(win[2] - GRID_X, c);
		int[] t = g.change != null ? g.change : new int[]{0, 0, 0, g.w, g.h, g.d};
		int x0 = Math.max(0, g.x + t[0] - ox), y0 = Math.max(0, g.y + t[1] - oy), z0 = Math.max(0, g.z + t[2] - oz);
		int x1 = Math.min(SIZE, g.x + t[3] - ox), y1 = Math.min(DEPTH[i], g.y + t[4] - oy), z1 = Math.min(SIZE, g.z + t[5] - oz);
		return x1 > x0 && y1 > y0 && z1 > z0 ? new int[]{x0, y0, z0, x1 - x0, y1 - y0, z1 - z0} : null;
	}

	private boolean solid(int i, int x, int y, int z)
	{
		return i == L_FINE ? solidFine(x, y, z) : i == L_NEAR ? solidCoarse(x, y, z) : solidMid(x, y, z);
	}

	private Upload sumWalled(int i, int[] box, List<Lamp> list, long now)
	{
		int w = box[3], h = box[4], d = box[5], cells = w * h * d;
		// the mid level's cells hold the fog's field too
		boolean fog = i == L_MID;
		int stride = fog ? 11 : 8;
		float[] f = new float[cells * stride];
		int[] win = window[i];
		int size = CELLS[i];
		int ox = Math.floorDiv(win[0] - GRID_X, size), oy = Math.floorDiv(win[1] - GRID_Y, size), oz = Math.floorDiv(win[2] - GRID_X, size);
		// a fine cell's edges are on multiples of its size; the others' centres are
		float shift = i == L_FINE ? 0.5f : 0;
		for (Lamp l : list)
		{
			Grid g = l.grid[i];
			// a lamp on a hidden storey lights no surface; the air takes it
			if (l.hidden && !fog || g.vis == null || l.nr + l.ng + l.nb <= 0)
			{
				continue;
			}
			addLamp(l, f, stride, box, g.vis, g.before, g.x, g.y, g.z, g.w, g.h, g.d, ox + box[0], oy + box[1], oz + box[2], 1, now, size, shift, false, 0, i);
		}
		// where the world cell is in the toroidal level
		Upload u = upload(i, box, Math.floorMod(Math.floorDiv(win[0], size) + box[0], SIZE), Math.floorMod(Math.floorDiv(win[1], size) + box[1], DEPTH[i]),
			Math.floorMod(Math.floorDiv(win[2], size) + box[2], SIZE));
		short one = half(1), nook = half(NOOK);
		if (fog)
		{
			u.fog = new short[u.a.length];
		}
		int c = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int z = 0; z < d; ++z)
			{
				for (int x = 0; x < w; ++x, ++c)
				{
					int cx = ox + box[0] + x, cy = oy + box[1] + y, cz = oz + box[2] + z;
					boolean solid = solid(i, cx, cy, cz);
					if (solid && (NOOK <= 0 || i == L_NEAR && allSolid(cx, cy, cz)))
					{
						continue;
					}
					float s = solid ? NOOK : 1;
					write(u, c, f, stride, s, solid ? nook : one);
					if (fog)
					{
						int a = c * 4, g = c * stride + 8;
						u.fog[a] = half(f[g] * s);
						u.fog[a + 1] = half(f[g + 1] * s);
						u.fog[a + 2] = half(f[g + 2] * s);
						u.fog[a + 3] = solid ? nook : one;
					}
				}
			}
		}
		return u;
	}

	private static Upload upload(int level, int[] box, int x, int y, int z)
	{
		Upload u = new Upload();
		u.level = level;
		u.x = x;
		u.y = y;
		u.z = z;
		u.w = box[3];
		u.h = box[4];
		u.d = box[5];
		u.a = new short[u.w * u.h * u.d * 4];
		u.b = new short[u.a.length];
		return u;
	}

	private static void write(Upload u, int i, float[] f, int stride, float s, short air)
	{
		int a = i * 4, g = i * stride;
		u.a[a] = half(f[g] * s);
		u.a[a + 1] = half(f[g + 1] * s);
		u.a[a + 2] = half(f[g + 2] * s);
		u.a[a + 3] = air;
		u.b[a] = half(f[g + 3] * s);
		u.b[a + 1] = half(f[g + 4] * s);
		u.b[a + 2] = half(f[g + 5] * s);
		u.b[a + 3] = half(f[g + 6] * s);
	}

	private Upload sumFar(int[] box, List<Lamp> list, long now)
	{
		int w = box[3], h = box[4], d = box[5], cells = w * h * d;
		float[] f = new float[cells * 8];
		int r = FAR_CELL / CELL;
		int[] win = window[L_FAR];
		int ox = Math.floorDiv(win[0] - GRID_X, FAR_CELL), oy = Math.floorDiv(win[1] - GRID_Y, FAR_CELL), oz = Math.floorDiv(win[2] - GRID_X, FAR_CELL);
		for (Lamp l : list)
		{
			Grid g = l.grid[L_NEAR];
			if (g.vis != null && l.nr + l.ng + l.nb > 0)
			{
				// a lamp on a storey the client hides glows in the air and falls on no surface: alpha is what may
				addLamp(l, f, 8, box, g.vis, g.before, g.x, g.y, g.z, g.w, g.h, g.d, (ox + box[0]) * r, (oy + box[1]) * r, (oz + box[2]) * r, r, now, CELL, 0, l.hidden, FAR_RIM, L_FAR);
			}
		}
		Upload u = upload(L_FAR, box, box[0], Math.floorMod(Math.floorDiv(win[1], FAR_CELL) + box[1], FAR_Y), box[2]);
		float scale = 1f / (r * r * r);
		for (int i = 0; i < cells; ++i)
		{
			write(u, i, f, 8, scale, half(f[i * 8 + 7] * scale));
		}
		return u;
	}

	// Adds a lamp into a box of 8 floats a cell, from a grid of its visibility at gx, gy, gz of gw x gh x gd cells: from grid cell (cx, cy, cz) on,
	// r grid cells to a box cell. A grid cell's centre is at (i + shift) * size from the grid's corner. H is relative to the level's origin.
	private void addLamp(Lamp l, float[] f, int stride, int[] box, byte[] vis, byte[] before, int gx, int gy, int gz, int gw, int gh, int gd, int cx, int cy, int cz, int r, long now,
		float size, float shift, boolean noSurface, float rim, int lv)
	{
		int w = box[3], h = box[4], d = box[5];
		int x0 = Math.max(cx, gx), y0 = Math.max(cy, gy), z0 = Math.max(cz, gz);
		int x1 = Math.min(cx + w * r, gx + gw), y1 = Math.min(cy + h * r, gy + gh), z1 = Math.min(cz + d * r, gz + gd);
		// a level's cells blur a lamp's edge past its reach: a coarse one's falloff ends this much short of it
		float reach = l.r - rim;
		float r2 = reach * reach, ir = 1 / reach;
		float lum = 0.2126f * l.nr + 0.7152f * l.ng + 0.0722f * l.nb;
		float px = l.x - origin[lv * 4], py = l.y - origin[lv * 4 + 1], pz = l.z - origin[lv * 4 + 2];
		float ease = before == null || before.length != vis.length ? 1 : Math.min(1, (now - l.easeFrom) / (float) EASE_NANOS);
		ease = ease * ease * (3 - 2 * ease);
		float onSurface = noSurface ? 0 : 1;
		for (int y = y0; y < y1; ++y)
		{
			float wy = (y + shift) * size + GRID_Y - l.y;
			int ty = (y - cy) / r;
			for (int z = z0; z < z1; ++z)
			{
				float wz = (z + shift) * size + GRID_X - l.z;
				int tz = (z - cz) / r;
				int at = ((y - gy) * gd + (z - gz)) * gw + (x0 - gx);
				int row = (ty * d + tz) * w;
				for (int x = x0; x < x1; ++x, ++at)
				{
					int b = vis[at] & 255;
					float o = b;
					if (ease < 1)
					{
						o = (before[at] & 255) + (b - (before[at] & 255)) * ease;
					}
					if (o == 0)
					{
						continue;
					}
					float wx = (x + shift) * size + GRID_X - l.x;
					float d2 = wx * wx + wy * wy + wz * wz;
					if (d2 >= r2)
					{
						continue;
					}
					float dist = (float) Math.sqrt(d2);
					float att = 1 - dist * ir;
					float v = att * att * o * (1 / 255f);
					int c = (row + (x - cx) / r) * stride;
					if (stride > 8)
					{
						float air = v * l.fog;
						f[c + 8] += l.nr * air;
						f[c + 9] += l.ng * air;
						f[c + 10] += l.nb * air;
						if (l.hidden)
						{
							continue;
						}
					}
					f[c] += l.nr * v;
					f[c + 1] += l.ng * v;
					f[c + 2] += l.nb * v;
					float g = lum * v / Math.max(dist, 16);
					f[c + 3] += g * px;
					f[c + 4] += g * py;
					f[c + 5] += g * pz;
					f[c + 6] += g;
					f[c + 7] += onSurface * lum * v;
				}
			}
		}
	}

	static short halfForTest(float f)
	{
		return half(f);
	}

	private static short half(float f)
	{
		int b = Float.floatToRawIntBits(f);
		int sign = b >>> 16 & 0x8000;
		int v = (b & 0x7fffffff) + 0x1000;
		if (v >= 0x47800000)
		{
			return (short) (sign | 0x7bff);
		}
		if (v >= 0x38800000)
		{
			return (short) (sign | v - 0x38000000 >>> 13);
		}
		if (v < 0x33000000)
		{
			return (short) sign;
		}
		v = (b & 0x7fffffff) >>> 23;
		return (short) (sign | ((b & 0x7fffff | 0x800000) + (0x800000 >>> v - 102)) >>> 126 - v);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the bench, without GL: the same voxels and floods, read on the CPU (harness/FloodCpu)

	/** lamps: x, y, z, reach, origin x, origin z. */
	public void offline(List<ZoneSnapshot> list, int offset, int minLevel, int level, int maxLevel, Set<Integer> hide, float[][] in, float focalX, float focalZ, float ground)
	{
		lamps.clear();
		float[] points = new float[in.length * 5];
		for (int i = 0; i < in.length; ++i)
		{
			Lamp l = new Lamp();
			l.x = in[i][0];
			l.y = in[i][1];
			l.z = in[i][2];
			l.r = in[i][3];
			l.ox = in[i][4];
			l.oz = in[i][5];
			lamps.put((long) i, l);
			System.arraycopy(new float[]{l.x, l.y, l.z, l.ox, l.oz}, 0, points, i * 5, 5);
		}
		for (int i = 0; i < WALLED; ++i)
		{
			window[i][0] = snap((int) focalX - SIZE * CELLS[i] / 2);
			window[i][2] = snap((int) focalZ - SIZE * CELLS[i] / 2);
			window[i][1] = Math.floorDiv(Math.round(ground) - ABOVE[i], CELLS[i]) * CELLS[i];
		}
		List<Runnable> tasks = new ArrayList<>();
		final Map<Long, ZoneCells> built = new java.util.concurrent.ConcurrentHashMap<>();
		for (ZoneSnapshot z : list)
		{
			if (z.vertsO != null)
			{
				tasks.add(() -> built.put(zoneKey(z.x, z.z), voxelize(triangles(z.vertsO, z.vertsO.length), z.levelOffsets, z.rids, z.roofStart, z.roofEnd, z.x, z.z, points)));
			}
		}
		long t0 = System.nanoTime();
		parallel(tasks);
		long t1 = System.nanoTime();
		zones.putAll(built);
		for (ZoneCells c : zones.values())
		{
			for (Segment seg : c.segments)
			{
				seg.shown = seg.level >= minLevel && seg.level <= maxLevel && (hide.isEmpty() || seg.level <= level || seg.tail || seg.rid > 0 && !hide.contains(seg.rid));
			}
		}
		rebuildBits(null);
		long t2 = System.nanoTime();
		tasks.clear();
		final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);
		final java.util.concurrent.atomic.AtomicLong each = new java.util.concurrent.atomic.AtomicLong();
		for (Lamp l : lamps.values())
		{
			tasks.add(() ->
			{
				long s = System.nanoTime();
				flood(l, scratch.get());
				each.addAndGet(System.nanoTime() - s);
			});
		}
		parallel(tasks);
		long t3 = System.nanoTime();
		long heap = 0;
		for (Lamp l : lamps.values())
		{
			heap += l.grid[L_NEAR].vis.length + (l.grid[L_FINE].vis != null ? l.grid[L_FINE].vis.length : 0) + l.grid[L_MID].vis.length;
		}
		lastJob = String.format("voxelize wall %.0f ms, bits %.0f ms, flood wall %.0f ms, cpu %.0f ms, lamp grids %.1f MB", (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, each.get() / 1e6, heap / 1e6);
	}

	String line(float x0, float y0, float z0, float x1, float y1, float z1)
	{
		StringBuilder o = new StringBuilder();
		for (int i = 0; i <= 64; ++i)
		{
			float t = i / 64f, x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t, z = z0 + (z1 - z0) * t;
			o.append(solidFine(Math.floorDiv((int) x - GRID_X, FINE), Math.floorDiv((int) y - GRID_Y, FINE), Math.floorDiv((int) z - GRID_X, FINE)) ? '#' : '.');
		}
		return o + " zones " + zones.size() + " segments " + zones.values().iterator().next().segments.length;
	}

	/** Along a line of fine cells: the first free cell's distance, the first solid past that, the last solid from the end, in units; -1 none. */
	public float[] offlineLine(float x0, float y0, float z0, float x1, float y1, float z1)
	{
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float firstFree = -1, firstSolid = -1, lastSolid = -1;
		for (float t = 0; t <= len; t += 2)
		{
			float u = t / len;
			boolean solid = solidFine(Math.floorDiv((int) (x0 + dx * u) - GRID_X, FINE), Math.floorDiv((int) (y0 + dy * u) - GRID_Y, FINE), Math.floorDiv((int) (z0 + dz * u) - GRID_X, FINE));
			if (!solid && firstFree < 0)
			{
				firstFree = t;
			}
			if (solid && firstFree >= 0)
			{
				firstSolid = firstSolid < 0 ? t : firstSolid;
				lastSolid = len - t;
			}
		}
		return new float[]{firstFree, firstSolid, lastSolid};
	}

	/** Whether a fine cell past the first is solid on the way from x, y, z along n for a length. */
	public boolean offlineStepCrosses(float x, float y, float z, float nx, float ny, float nz, float length)
	{
		for (float t = 4; t <= length; t += 4)
		{
			if (solidFine(Math.floorDiv((int) (x + nx * t) - GRID_X, FINE), Math.floorDiv((int) (y + ny * t) - GRID_Y, FINE), Math.floorDiv((int) (z + nz * t) - GRID_X, FINE)))
			{
				return true;
			}
		}
		return false;
	}

	/** Lamp i's visibility as a read at world point x, y, z has it; -1 with no free cell in it, -2 outside the near window. */
	public static final ThreadLocal<float[]> AIR = ThreadLocal.withInitial(() -> new float[1]);

	public float offlineRead(int i, float x, float y, float z)
	{
		return read(i, L_NEAR, x, y, z, 0);
	}

	/** The same from the mid level. */
	public float offlineReadMid(int i, float x, float y, float z)
	{
		return read(i, L_MID, x, y, z, 0);
	}

	/** The same from the finest level. */
	public float offlineReadFine(int i, float x, float y, float z)
	{
		return read(i, L_FINE, x, y, z, 0.5f);
	}

	private float read(int i, int lv, float x, float y, float z, float shift)
	{
		Lamp l = lamps.get((long) i);
		Grid g = l.grid[lv];
		float size = CELLS[lv];
		int[] win = window[lv];
		float gx = (x - GRID_X) / size - shift, gy = (y - GRID_Y) / size - shift, gz = (z - GRID_X) / size - shift;
		float e = Math.min(Math.min(x - win[0], win[0] + SIZE * size - x), Math.min(z - win[2], win[2] + SIZE * size - z));
		if (e < 0 || y < win[1] || y > win[1] + DEPTH[lv] * size || g.vis == null)
		{
			return -2;
		}
		int ix = (int) Math.floor(gx), iy = (int) Math.floor(gy), iz = (int) Math.floor(gz);
		float fx = gx - ix, fy = gy - iy, fz = gz - iz;
		float air = 0, sum = 0;
		for (int k = 0; k < 8; ++k)
		{
			int cx = ix + (k & 1), cy = iy + (k >> 1 & 1), cz = iz + (k >> 2);
			float w = ((k & 1) != 0 ? fx : 1 - fx) * ((k >> 1 & 1) != 0 ? fy : 1 - fy) * ((k >> 2) != 0 ? fz : 1 - fz);
			if (w <= 0)
			{
				continue;
			}
			if (solid(lv, cx, cy, cz))
			{
				if (NOOK <= 0 || lv == L_NEAR && allSolid(cx, cy, cz))
				{
					continue;
				}
				w *= NOOK;
			}
			air += w;
			int a = cx - g.x, b = cy - g.y, c = cz - g.z;
			if (a >= 0 && b >= 0 && c >= 0 && a < g.w && b < g.h && c < g.d)
			{
				sum += w * (g.vis[(b * g.d + c) * g.w + a] & 255) / 255f;
			}
		}
		// as the shader: less than a share of free cell, and the caller tries further off the surface
		if (air < 1e-4f)
		{
			return -1;
		}
		AIR.get()[0] = air;
		return sum / air;
	}

	public static String defines()
	{
		StringBuilder o = new StringBuilder();
		o.append("#define LAMP_VOLUME_SIZE ").append(SIZE).append(".0\n");
		o.append("#define LAMP_VOLUME_DEPTH ").append(DEPTH_ALL).append(".0\n#define LAMP_VOLUME_B_AT ").append(LAYERS).append(".0\n");
		o.append("#define LAMP_VOLUME_FOG_AT ").append(FOG_AT).append(".0\n");
		o.append("#define LAMP_VOLUME_FINE ").append(FINE).append(".0\n#define LAMP_VOLUME_CELL ").append(CELL).append(".0\n");
		o.append("#define LAMP_VOLUME_MID ").append(MID_CELL).append(".0\n#define LAMP_VOLUME_FAR_CELL ").append(FAR_CELL).append(".0\n");
		o.append("#define LAMP_VOLUME_FINE_Y ").append(FINE_Y).append(".0\n#define LAMP_VOLUME_NEAR_Y ").append(NEAR_Y).append(".0\n");
		o.append("#define LAMP_VOLUME_MID_Y ").append(MID_Y).append(".0\n#define LAMP_VOLUME_FAR_Y ").append(FAR_Y).append(".0\n");
		o.append("#define LAMP_VOLUME_FINE_AT ").append(BASE[L_FINE]).append(".0\n#define LAMP_VOLUME_NEAR_AT ").append(BASE[L_NEAR]).append(".0\n");
		o.append("#define LAMP_VOLUME_MID_AT ").append(BASE[L_MID]).append(".0\n#define LAMP_VOLUME_FAR_AT ").append(BASE[L_FAR]).append(".0\n");
		o.append("#define LAMP_VOLUME_GAIN_AT ").append(GAIN_AT).append(".0\n");
		o.append("#define LAMP_VOLUME_GAIN_SIZE ").append(GAIN_SIZE).append(".0\n#define LAMP_VOLUME_GAIN_CELL ").append(GAIN_CELL).append(".0\n");
		o.append("#define LAMP_MOVERS ").append(MOVERS).append("\n");
		return o.toString();
	}
}
