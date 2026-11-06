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
import static org.lwjgl.opengl.GL33C.*;

/**
 * The flood volume (occlusion/flood-volume/math.md): every lamp's light summed into one world-space volume that pixels,
 * transparent faces, fog and rain all read, at a cost that does not depend on how many lamps there are.
 *
 * What is drawn solid is voxelized conservatively at FINE units from the zones' own triangles, by the ranges the frame
 * draws (Zone.pushLevels): levels, hidden roofs out. Each lamp floods out from its origin, semi-Lagrangian: a cell takes
 * the bilinear value of where its ray to the lamp crosses the shell before, times free or not, so light goes in straight
 * lines at a cost of one pass over its box, and can't cross anything closed. Its visibility is kept per CELL (light/lamp_volume.glsl
 * reads this grid), and summed with its colour and falloff into the volume. A read cell is solid (air 0, nothing in it) when any
 * of its FINE cells is, and the shader divides by the air, so a filtered read takes only free cells, and any two of those
 * are on one side of every surface: light can't be interpolated across a wall.
 *
 * Two levels in one pair of textures: near CELL units a cell around the player, far FAR_CELL over the whole scene. A
 * lamp on a storey the client hides is only in the far level: surfaces near the player never see it, the fog does.
 *
 * Everything but GL runs on a worker; the render thread only finds what changed and sends finished boxes.
 */
public final class LampVolume
{
	public static final int FINE = 16, CELL = 32, FAR_CELL = 128;
	public static final int SIZE = 160, FINE_Y = 48, NEAR_Y = 48, FAR_Y = 24, GUTTER = 2;
	// the levels' places in the depth, and the same again for B
	private static final int NEAR_AT = FINE_Y + GUTTER, FAR_AT = NEAR_AT + NEAR_Y + GUTTER, LAYERS = FAR_AT + FAR_Y, GAIN_AT = NEAR_AT + NEAR_Y;
	// the fine grid, all cells world-locked: world x, z from GRID_X, world y from GRID_Y
	private static final int GRID_X = -6144, GRID_Y = -3072;
	private static final int CHUNK = 64, CHUNKS = 40, FINE_LAYERS = 256;
	private static final int COLUMN_LONGS = FINE_LAYERS / 64;
	private static final int FINE_CHUNK_LONGS = CHUNK * CHUNK * COLUMN_LONGS;
	private static final int COARSE_CHUNK_LONGS = CHUNK * CHUNK / 4 * COLUMN_LONGS / 2;
	// the far level's window: the scene and its surroundings, fixed
	private static final int FAR_X = -6144 + 1024;
	// the seed: a lamp's own fixture doesn't stop it within this of its origin
	private static final float SEED = prop("volSeed", 20f);
	// a triangle wholly within this of a lamp is its fixture (a real build knows: PointLight.owner, VoxelCapture.owner)
	private static final float FIXTURE = prop("volFixture", 72f);
	// visibility as read: 0 to 1 between these of the flood's. Below lo is the geometric edge's dark side (math.md 6)
	private static final float SHARP_LO = prop("volLo", 0.3f);
	private static final float SHARP_HI = prop("volHi", 0.8f);
	// rays to the lamp's ball from each free fine cell of a read cell the flood found on a shadow's front
	private static final int RAYS = (int) prop("volRays", 5);
	private static final float BALL = prop("volBall", 0.7f);
	// A solid read cell with free fine cells holds what those see, at this share of a free cell's air: nothing to a read that has a free
	// cell, all there is to one that hasn't, a surface in a nook no free cell reaches
	private static final float NOOK = prop("volNook", 1 / 32f);
	// how far short of a lamp's reach its light ends in the far level
	private static final float FAR_RIM = prop("volFarRim", 64f);
	// how much lower the ground stops light than it is drawn
	private static final float LOWER = prop("volLower", 32f);
	// how far above the ground under the player the finest level starts (y is down)
	private static final int FINE_ABOVE = 640;
	// how many steps of a ray that leaves a surface pass the cells that only touch it
	private static final int CRUST = (int) prop("volCrust", 2);
	// the near window follows the player in steps of this many cells
	private static final int STEP_CELLS = 16;
	// what the render thread sends a frame
	private static final int UPLOAD_BYTES = 1500 * 1024;
	// a change of more lamps than this pops rather than eases: their boxes would be sent every frame
	private static final int EASE_LAMPS = 8;
	private static final int THREADS = 4;
	// 1: a read cell in y, too, is centred on a multiple of CELL
	private static final int Y_SHIFT = (int) prop("volYShift", 1);
	// how far the window goes before the lamps' places in H are held relative to a nearer point (half floats)
	private static final float ORIGIN_REACH = 4000;
	// the flicker's map: its cells, and how far a lamp's strength may stray from what the volume holds before it is summed again
	private static final int GAIN_SIZE = 64, GAIN_CELL = 256;
	private static final float GAIN_LO = 0.5f, GAIN_HI = 1.6f;
	private static final long EASE_NANOS = 250_000_000L;
	// the offline renderer: a change of strength lands in its own frame (the client's lamps don't jump)
	public static boolean benchSync;

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

	/** One lamp as the volume holds it. */
	private static final class Lamp
	{
		float x, y, z, r, ox, oz;
		// colour times strength as summed; as the frame's list has it
		float cr, cg, cb;
		float nr, ng, nb;
		float gain = 1;
		boolean hidden;
		boolean seen;
		// its visibility per read cell in its box, and the same before a change while it eases
		byte[] vis, before;
		int bx, by, bz, bw, bh, bd;
		// the same per fine cell, a cube of fw cells from fx, fy, fz
		byte[] fineVis, fineBefore;
		int fx, fy, fz, fw;
		// what a flood replaced, and the boxes (in its own grids, x0 y0 z0 x1 y1 z1) it changed: null for all of it
		byte[] oldVis, oldFine;
		int[] nearChange, fineChange;
		long easeFrom;
		boolean flood;
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
	}

	private static final class Upload
	{
		int x, y, z, w, h, d;
		short[] a, b;
	}

	private int texA;
	private final float[] fine0 = new float[4], near = new float[4], far = new float[4], origin = new float[4];
	private final float[] shownFine = new float[4], shownNear = new float[4], shownFar = new float[4], shownOrigin = new float[4];
	private boolean windowsWait, anyHidden;
	private static LampVolume current;

	private final Map<Long, Lamp> lamps = new HashMap<>();
	private final Map<Long, ZoneCells> zones = new HashMap<>();
	private final long[][] fine = new long[CHUNKS * CHUNKS][], coarse = new long[CHUNKS * CHUNKS][];
	private int[][][] seen;
	private int minLevel = -1, level = -1, maxLevel = -1;
	private Set<Integer> hidden = new HashSet<>();
	private int windowX = Integer.MIN_VALUE, windowZ, windowY, farY, fineX, fineZ, fineY;
	private float originX, originY, originZ;
	private boolean sentAll;
	private Future<?> job;
	private volatile List<Upload> done;
	private volatile boolean doneEverything;
	private final java.util.ArrayDeque<Upload> queue = new java.util.ArrayDeque<>();
	private float windowGround;
	private ShortBuffer stage;
	private boolean anyLit;
	private boolean gainSent;
	private final float[] gainW = new float[GAIN_SIZE * GAIN_SIZE], gainG = new float[GAIN_SIZE * GAIN_SIZE];
	private final ShortBuffer gainStage = BufferUtils.createShortBuffer(GAIN_SIZE * GAIN_SIZE * 4);
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

	private void init()
	{
		texA = glGenTextures();
		glBindTexture(GL_TEXTURE_3D, texA);
		// A in the first half of the depth, B in the second (light/lamp_volume.glsl)
		glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, SIZE, SIZE, LAYERS * 2, 0, GL_RGBA, GL_HALF_FLOAT, (ShortBuffer) null);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		// the near level is toroidal: a world cell is at its place modulo SIZE, so the window moves by sending what comes into it
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_3D, 0);
		stage = BufferUtils.createShortBuffer(4 * SIZE * SIZE * 32);
		current = this;
	}

	public void free()
	{
		if (texA != 0)
		{
			glDeleteTextures(texA);
			texA = 0;
		}
		lamps.clear();
		zones.clear();
		seen = null;
		sentAll = false;
		Arrays.fill(fine, null);
		Arrays.fill(coarse, null);
	}

	public void bind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_3D, texA);
		glActiveTexture(GL_TEXTURE0);
	}

	public float[] fineWindow()
	{
		return shownFine;
	}

	public float[] nearWindow()
	{
		return shownNear;
	}

	public float[] farWindow()
	{
		return shownFar;
	}

	public float[] origin()
	{
		return shownOrigin;
	}

	/**
	 * A frame's lamps: send what the worker finished, find what changed since what the volume holds, and start the worker on
	 * it unless it is at work: then the next frame looks again.
	 */
	public void update(LuminaPlugin plugin, List<PointLight> lights, LampWalls walls, LightClusters clusters)
	{
		if (texA == 0)
		{
			init();
		}
		drain(UPLOAD_BYTES);
		if (job != null && !job.isDone())
		{
			return;
		}
		if (job != null)
		{
			finish();
			if (benchSync && doneEverything)
			{
				drain(Integer.MAX_VALUE);
			}
			showWindows();
		}
		LuminaPlugin.SceneContext ctx = plugin.root;
		if (ctx == null || ctx.zones == null || ctx.hideRoofIds == null)
		{
			return;
		}
		// the lamps
		boolean strengths = false;
		boolean moved = false;
		List<Lamp> added = new ArrayList<>();
		for (Lamp l : lamps.values())
		{
			l.seen = false;
		}
		float[] o = new float[2];
		boolean lit = false;
		for (int i = 0, n = lights.size(); i < n; ++i)
		{
			PointLight p = lights.get(i);
			long key = (long) Float.floatToIntBits(p.x) * 31 + (long) Float.floatToIntBits(p.y) * 17 << 20 ^ Float.floatToIntBits(p.z) ^ (long) (p.radius / 16) << 50;
			Lamp l = lamps.get(key);
			if (l == null)
			{
				l = new Lamp();
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
				lamps.put(key, l);
				added.add(l);
			}
			l.seen = true;
			float s = p.intensity * p.mapFade;
			float nr = p.r * s, ng = p.g * s, nb = p.b * s;
			// a flicker doesn't touch the volume: it goes on its gain map (updateGain). A real change of strength does.
			float was = l.cr + l.cg + l.cb, is = nr + ng + nb;
			float ratio = was > 0 ? is / was : 0;
			if (ratio > GAIN_LO && ratio < GAIN_HI && !l.flood)
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
			if (h != l.hidden)
			{
				l.hidden = h;
				strengths = true;
				l.cr = -1;
			}
			strengths |= l.nr != l.cr || l.ng != l.cg || l.nb != l.cb;
		}
		anyLit = lit;
		anyHidden = false;
		for (Lamp l : lamps.values())
		{
			anyHidden |= l.hidden && l.seen;
		}
		updateGain();
		List<Lamp> gone = new ArrayList<>();
		for (Iterator<Lamp> it = lamps.values().iterator(); it.hasNext(); )
		{
			Lamp l = it.next();
			if (!l.seen)
			{
				gone.add(l);
				it.remove();
			}
		}
		// the window
		int wx = Math.floorDiv((int) plugin.edgeFade.focalX - SIZE * CELL / 2, CELL * STEP_CELLS) * STEP_CELLS * CELL;
		int wz = Math.floorDiv((int) plugin.edgeFade.focalZ - SIZE * CELL / 2, CELL * STEP_CELLS) * STEP_CELLS * CELL;
		int stepUnits = CELL * STEP_CELLS;
		int ffx = Math.floorDiv((int) plugin.edgeFade.focalX - SIZE * FINE / 2, stepUnits) * stepUnits;
		int ffz = Math.floorDiv((int) plugin.edgeFade.focalZ - SIZE * FINE / 2, stepUnits) * stepUnits;
		int wy = windowY, fy = farY, ffy = fineY;
		float ground = playerY(plugin);
		if (windowX == Integer.MIN_VALUE || Math.abs(ground - windowGround) > 256)
		{
			windowGround = ground;
			wy = Math.floorDiv(Math.round(ground) - 1100, CELL) * CELL;
			fy = Math.floorDiv(Math.round(ground) - 1900, FAR_CELL) * FAR_CELL;
			ffy = Math.floorDiv(Math.round(ground) - FINE_ABOVE, FINE) * FINE;
		}
		boolean windowMoved = wx != windowX || wz != windowZ || wy != windowY || ffx != fineX || ffz != fineZ || ffy != fineY;
		// the zones
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
				if (seen[x][z] != ctx.zones[x][z].lampTriangles)
				{
					changedZones.add(new int[]{x, z});
				}
			}
		}
		boolean shownChanged = ctx.minLevel != minLevel || ctx.level != level || ctx.maxLevel != maxLevel
			|| !ctx.hideRoofIds.equals(hidden);
		boolean easing = false;
		for (Lamp l : lamps.values())
		{
			easing |= l.before != null;
		}
		if (added.isEmpty() && gone.isEmpty() && !strengths && !windowMoved && changedZones.isEmpty() && !shownChanged && !easing)
		{
			return;
		}
		Job j = new Job();
		j.ctx = ctx;
		j.added = added;
		j.gone = gone;
		j.changedZones = changedZones;
		j.shownChanged = shownChanged;
		j.windowMoved = windowMoved;
		j.wx = wx;
		j.wz = wz;
		j.wy = wy;
		j.fy = fy;
		j.ffx = ffx;
		j.ffz = ffz;
		j.ffy = ffy;
		j.ox = wx + SIZE * CELL / 2f;
		j.oz = wz + SIZE * CELL / 2f;
		j.oy = wy + NEAR_Y * CELL / 2f;
		j.now = Clock.nanos();
		minLevel = ctx.minLevel;
		level = ctx.level;
		maxLevel = ctx.maxLevel;
		hidden = new HashSet<>(ctx.hideRoofIds);
		j.minLevel = minLevel;
		j.level = level;
		j.maxLevel = maxLevel;
		j.hidden = hidden;
		job = WORKER.submit(j);
		if (benchSync && !shownChanged && changedZones.isEmpty())
		{
			finish();
			drain(Integer.MAX_VALUE);
		}
	}

	// the ground under the player's focus (world y, down)
	// The lamps' flicker: per GAIN_CELL of the ground, what the lamps there are at now over what the volume holds them at, weighted by
	// their light there, less 1. A slice of the texture no read filters into (a gutter layer of A), a read of it per lit pixel. Cheap
	// enough for every frame: a lamp touches a few cells.
	private void updateGain()
	{
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
		float x0 = windowX + SIZE * CELL / 2f - GAIN_SIZE * GAIN_CELL / 2f, z0 = windowZ + SIZE * CELL / 2f - GAIN_SIZE * GAIN_CELL / 2f;
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
		glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, GAIN_AT, GAIN_SIZE, GAIN_SIZE, 1, GL_RGBA, GL_HALF_FLOAT, gainStage);
		glBindTexture(GL_TEXTURE_3D, 0);
	}

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

	// what the worker finished: the window's new place and the boxes
	private void finish()
	{
		try
		{
			job.get();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
		job = null;
		List<Upload> list = done;
		done = null;
		if (list != null)
		{
			queue.addAll(list);
		}
		fine0[0] = fineX;
		fine0[1] = fineZ;
		fine0[2] = fineY;
		fine0[3] = 1;
		near[0] = windowX;
		near[1] = windowZ;
		near[2] = windowY;
		near[3] = 1;
		far[0] = FAR_X;
		far[1] = FAR_X;
		far[2] = farY;
		far[3] = anyHidden ? 1 : 0;
		origin[0] = originX;
		origin[1] = originY;
		origin[2] = originZ;
		windowsWait = true;
	}

	// The shaders see a window's new place when all that comes into it is sent: until then they read what they read
	private void showWindows()
	{
		shownFar[3] = anyHidden ? 1 : 0;
		if (windowsWait && queue.isEmpty())
		{
			windowsWait = false;
			System.arraycopy(fine0, 0, shownFine, 0, 4);
			System.arraycopy(near, 0, shownNear, 0, 4);
			System.arraycopy(far, 0, shownFar, 0, 4);
			System.arraycopy(origin, 0, shownOrigin, 0, 4);
		}
	}

	// what the worker finished, oldest first, up to this many bytes
	private void drain(int bytes)
	{
		if (queue.isEmpty())
		{
			showWindows();
			return;
		}
		long t0 = System.nanoTime();
		int sent = 0;
		glBindTexture(GL_TEXTURE_3D, texA);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
		while (bytes > 0 && !queue.isEmpty())
		{
			Upload u = queue.poll();
			send(u, u.a, 0);
			send(u, u.b, LAYERS);
			bytes -= (u.a.length + u.b.length) * 2;
			sent += (u.a.length + u.b.length) * 2;
		}
		glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
		glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
		glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
		glBindTexture(GL_TEXTURE_3D, 0);
		showWindows();
		if (System.getenv("LUMINA_VOL_LOG") != null)
		{
			System.out.printf("lamp volume: sent %d kB in %.2f ms%n", sent / 1024, (System.nanoTime() - t0) / 1e6);
		}
	}

	// A box, split where it wraps round the texture
	private void send(Upload u, short[] data, int layer)
	{
		if (stage.capacity() < data.length)
		{
			stage = BufferUtils.createShortBuffer(data.length);
		}
		stage.clear();
		stage.put(data);
		stage.flip();
		glPixelStorei(GL_UNPACK_ROW_LENGTH, u.w);
		glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, u.d);
		int w0 = Math.min(u.w, SIZE - u.x), d0 = Math.min(u.d, SIZE - u.z);
		piece(u, 0, 0, u.x, u.z, w0, d0, layer);
		if (w0 < u.w)
		{
			piece(u, w0, 0, 0, u.z, u.w - w0, d0, layer);
		}
		if (d0 < u.d)
		{
			piece(u, 0, d0, u.x, 0, w0, u.d - d0, layer);
		}
		if (w0 < u.w && d0 < u.d)
		{
			piece(u, w0, d0, 0, 0, u.w - w0, u.d - d0, layer);
		}
	}

	private void piece(Upload u, int skipX, int skipZ, int x, int z, int w, int d, int layer)
	{
		glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipX);
		glPixelStorei(GL_UNPACK_SKIP_ROWS, skipZ);
		glTexSubImage3D(GL_TEXTURE_3D, 0, x, z, u.y + layer, w, d, u.h, GL_RGBA, GL_HALF_FLOAT, stage);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the worker

	private final class Job implements Runnable
	{
		LuminaPlugin.SceneContext ctx;
		List<Lamp> added, gone;
		List<int[]> changedZones;
		boolean shownChanged, windowMoved;
		int wx, wz, wy, fy, ffx, ffz, ffy;
		float ox, oy, oz;
		int minLevel, level, maxLevel;
		Set<Integer> hidden;
		long now;

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
			int stepX = (wx - windowX) / CELL, stepZ = (wz - windowZ) / CELL;
			int fineStepX = (ffx - fineX) / FINE, fineStepZ = (ffz - fineZ) / FINE;
			boolean everything = !sentAll || wy != windowY || ffy != fineY || Math.abs(stepX) >= SIZE || Math.abs(stepZ) >= SIZE
				|| Math.abs(fineStepX) >= SIZE || Math.abs(fineStepZ) >= SIZE
				|| Math.abs(ox - originX) > ORIGIN_REACH || Math.abs(oz - originZ) > ORIGIN_REACH;
			if (windowMoved)
			{
				windowX = wx;
				windowZ = wz;
				windowY = wy;
				farY = fy;
				fineX = ffx;
				fineZ = ffz;
				fineY = ffy;
			}
			if (everything)
			{
				originX = ox;
				originY = oy;
				originZ = oz;
			}
			// the zones that changed, and those newly in reach of a lamp
			List<int[]> build = new ArrayList<>(changedZones);
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
			final float[] points = new float[lamps.size() * 5];
			{
				int k = 0;
				for (Lamp l : lamps.values())
				{
					points[k++] = l.x;
					points[k++] = l.y;
					points[k++] = l.z;
					points[k++] = l.ox;
					points[k++] = l.oz;
				}
			}
			for (int x = 0; x < ctx.zones.length; ++x)
			{
				for (int z = 0; z < ctx.zones[0].length; ++z)
				{
					final int fx = x, fz = z;
					long key = zoneKey(x, z);
					ZoneCells old = zones.get(key);
					Zone now = ctx.zones[x][z];
					if (needed.contains(key) && (old == null || old.triangles != now.lampTriangles))
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
			for (Map.Entry<Long, ZoneCells> e : built.entrySet())
			{
				for (Segment seg : e.getValue().segments)
				{
					seg.shown = shown(seg);
				}
				// a door's new zone differs from the old by its door: only lamps that reach that see a change
				difference(dirty, zones.put(e.getKey(), e.getValue()), e.getValue());
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
					}
				}
			}
			if (bitsChanged)
			{
				rebuildBits();
			}
			long t2 = System.nanoTime();
			// the lamps to flood: new, or a change in their box
			List<Lamp> floods = new ArrayList<>();
			for (Lamp l : lamps.values())
			{
				boolean touched = dirty[0] <= dirty[2] && (l.x + l.r + 32 - GRID_X) / FINE >= dirty[0] && (l.x - l.r - 32 - GRID_X) / FINE <= dirty[2]
					&& (l.z + l.r + 32 - GRID_X) / FINE >= dirty[1] && (l.z - l.r - 32 - GRID_X) / FINE <= dirty[3];
				if (l.flood || touched)
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
					l.oldVis = l.vis;
					l.oldFine = l.fineVis;
					flood(l, scratch.get());
					l.fineChange = l.oldFine == null ? null : changedBox(l.fineVis, l.oldFine, l.fw, l.fw, l.fw);
					l.nearChange = l.oldVis == null ? null : changedBox(l.vis, l.oldVis, l.bw, l.bh, l.bd);
				});
			}
			parallel(tasks);
			// a lamp a change didn't touch is done with; a few that it did ease over to it
			int altered = 0;
			for (Lamp l : floods)
			{
				altered += l.oldVis != null && l.nearChange != null ? 1 : 0;
			}
			for (Iterator<Lamp> it = floods.iterator(); it.hasNext(); )
			{
				Lamp l = it.next();
				boolean fresh = l.flood;
				l.flood = false;
				if (!fresh && l.oldVis != null && l.nearChange == null)
				{
					it.remove();
				}
				else if (!fresh && sentAll && altered <= EASE_LAMPS)
				{
					l.before = l.oldVis;
					l.fineBefore = l.oldFine;
					l.easeFrom = now;
				}
				l.oldVis = null;
				l.oldFine = null;
			}
			long t3 = System.nanoTime();
			// the boxes to sum: everything, or each lamp changed
			List<int[]> fineBoxes = new ArrayList<>(), nearBoxes = new ArrayList<>(), farBoxes = new ArrayList<>();
			if (everything)
			{
				layers(fineBoxes, 0, 0, SIZE, SIZE, FINE_Y);
				layers(nearBoxes, 0, 0, SIZE, SIZE, NEAR_Y);
				layers(farBoxes, 0, 0, SIZE, SIZE, FAR_Y);
			}
			else
			{
				if (fineStepX != 0)
				{
					layers(fineBoxes, fineStepX > 0 ? SIZE - fineStepX : 0, 0, Math.abs(fineStepX), SIZE, FINE_Y);
				}
				if (fineStepZ != 0)
				{
					layers(fineBoxes, 0, fineStepZ > 0 ? SIZE - fineStepZ : 0, SIZE, Math.abs(fineStepZ), FINE_Y);
				}
				if (stepX != 0)
				{
					layers(nearBoxes, stepX > 0 ? SIZE - stepX : 0, 0, Math.abs(stepX), SIZE, NEAR_Y);
				}
				if (stepZ != 0)
				{
					layers(nearBoxes, 0, stepZ > 0 ? SIZE - stepZ : 0, SIZE, Math.abs(stepZ), NEAR_Y);
				}
				List<Lamp> changed = new ArrayList<>(gone);
				changed.addAll(floods);
				for (Lamp l : lamps.values())
				{
					boolean ease = l.before != null;
					if (ease || l.nr != l.cr || l.ng != l.cg || l.nb != l.cb)
					{
						if (!floods.contains(l))
						{
							if (!ease)
							{
								l.nearChange = null;
								l.fineChange = null;
							}
							changed.add(l);
						}
					}
				}
				if (changed.size() > 64)
				{
					fineBoxes.clear();
					nearBoxes.clear();
					layers(fineBoxes, 0, 0, SIZE, SIZE, FINE_Y);
					layers(nearBoxes, 0, 0, SIZE, SIZE, NEAR_Y);
					layers(farBoxes, 0, 0, SIZE, SIZE, FAR_Y);
				}
				else
				{
					for (Lamp l : changed)
					{
						slices(fineBoxes, fineBox(l));
						slices(nearBoxes, nearBox(l));
						slices(farBoxes, farBox(l));
					}
				}
			}
			for (Lamp l : lamps.values())
			{
				l.cr = l.nr;
				l.cg = l.ng;
				l.cb = l.nb;
				if (l.before != null && now - l.easeFrom >= EASE_NANOS)
				{
					l.before = null;
					l.fineBefore = null;
				}
				if (l.before == null)
				{
					l.nearChange = null;
					l.fineChange = null;
				}
			}
			final List<Lamp> list = new ArrayList<>(lamps.values());
			final int fines = fineBoxes.size(), nears = nearBoxes.size();
			final Upload[] uploads = new Upload[fines + nears + farBoxes.size()];
			tasks.clear();
			for (int i = 0; i < fines; ++i)
			{
				final int k = i;
				tasks.add(() -> uploads[k] = sumFine(fineBoxes.get(k), list, now));
			}
			for (int i = 0; i < nears; ++i)
			{
				final int k = i;
				tasks.add(() -> uploads[fines + k] = sumNear(nearBoxes.get(k), list, now));
			}
			for (int i = 0; i < farBoxes.size(); ++i)
			{
				final int k = i;
				tasks.add(() -> uploads[fines + nears + k] = sumFar(farBoxes.get(k), list, now));
			}
			parallel(tasks);
			long t4 = System.nanoTime();
			sentAll = true;
			doneEverything = everything;
			done = new ArrayList<>(Arrays.asList(uploads));
			lastJob = String.format("window " + windowX + "," + windowY + "," + windowZ + " zones %d voxelize %.1f bits %.1f flood %d lamps %.1f sum %d boxes %.1f ms", built.size(), (t1 - t0) / 1e6, (t2 - t1) / 1e6,
				floods.size(), (t3 - t2) / 1e6, uploads.length, (t4 - t3) / 1e6);
			if (Boolean.getBoolean("lumina.volLog") || System.getenv("LUMINA_VOL_LOG") != null)
			{
				System.out.println("lamp volume: " + lastJob);
			}
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

	// a box of a level, as boxes of a few layers each: what a frame can send
	private static void layers(List<int[]> boxes, int x, int z, int w, int d, int layers)
	{
		for (int y = 0; y < layers; y += 8)
		{
			boxes.add(new int[]{x, y, z, w, Math.min(8, layers - y), d});
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

	// the box, in fine x and z, of the cells shown in one of the two and not the other
	private static void difference(int[] box, ZoneCells before, ZoneCells after)
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
	private ZoneCells voxelize(Zone zone, int zx, int zz, float[] allPoints)
	{
		return voxelize(zone.lampTriangles, zone.levelOffsets, zone.rids, zone.roofStart, zone.roofEnd, zx, zz, allPoints);
	}

	private ZoneCells voxelize(int[] t, int[] levelOffsets, int[][] allRids, int[][] allStart, int[][] allEnd, int zx, int zz, float[] allPoints)
	{
		ZoneCells out = new ZoneCells();
		out.triangles = t;
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

	private static boolean onTileCorners(float[] v)
	{
		for (int k = 0; k < 9; k += 3)
		{
			if (Math.floorMod((int) v[k], 128) != 0 || Math.floorMod((int) v[k + 2], 128) != 0)
			{
				return false;
			}
		}
		return true;
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
			// The ground lies a little lower than it is drawn. Its stairs of cells rise above it, and light grazing a slope would stop on them.
			// A straight line between two points above the ground doesn't go under a wall that stands on it.
			if (seg.level == 0 && LOWER > 0 && onTileCorners(v))
			{
				v[1] += LOWER;
				v[4] += LOWER;
				v[7] += LOWER;
				minY += LOWER;
				maxY += LOWER;
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

	private void rebuildBits()
	{
		for (long[] c : fine)
		{
			if (c != null)
			{
				Arrays.fill(c, 0);
			}
		}
		for (long[] c : coarse)
		{
			if (c != null)
			{
				Arrays.fill(c, 0);
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
		int cx = x + 1 >> 1, cz = z + 1 >> 1, cy = y + Y_SHIFT >> 1;
		int cc = (cz >> 5) * CHUNKS + (cx >> 5);
		if (cc >= coarse.length || cy >= FINE_LAYERS / 2)
		{
			return;
		}
		long[] g = coarse[cc];
		if (g == null)
		{
			g = coarse[cc] = new long[COARSE_CHUNK_LONGS];
		}
		g[(((cz & 31) << 5 | (cx & 31)) * (COLUMN_LONGS / 2)) + (cy >> 6)] |= 1L << (cy & 63);
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
			if (!solidFine(2 * x - 1 + (k & 1), 2 * y - Y_SHIFT + (k >> 1 & 1), 2 * z - 1 + (k >> 2)))
			{
				return false;
			}
		}
		return true;
	}

	// a read cell, x y z in CELL units from the grid's corner
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
		float[] sum = new float[1 << 14], rayed = new float[1 << 14];
		short[] count = new short[1 << 14], trace = new short[1 << 14];
	}

	// A lamp's flood: shells of a cube round its origin, each cell the bilinear value of where its ray to the lamp crosses the
	// shell's plane before it, on the axis the ray runs most along. Then per read cell the mean of its free cells, sharpened.
	private void flood(Lamp l, Scratch sc)
	{
		int s = FINE;
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
		float gx = (l.ox - GRID_X) / s, gy = (l.y - GRID_Y) / s, gz = (l.oz - GRID_X) / s;
		int lx = (int) Math.floor(gx), ly = (int) Math.floor(gy), lz = (int) Math.floor(gz);
		float ox = gx - lx - 0.5f, oy = gy - ly - 0.5f, oz = gz - lz - 0.5f;
		float reach = (l.r + s) / s, reach2 = reach * reach;
		int i = 0;
		for (int dy = -n; dy <= n; ++dy)
		{
			for (int dz = -n; dz <= n; ++dz, i += w)
			{
				// outside the ball nothing flows: a row's ends count as solid
				float left = reach2 - dy * dy - dz * dz;
				int end = left < 0 ? -1 : Math.min(n, (int) Math.sqrt(left));
				Arrays.fill(solid, i, i + w, true);
				for (int dx = -end; dx <= end; ++dx)
				{
					solid[i + n + dx] = solidFine(lx + dx, ly + dy, lz + dz);
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
							value = plane(v, at - (ax > 0 ? 1 : -1), ay * t, az * t, stepY, stepZ);
						}
						else if (by >= bz)
						{
							float t = 1 / by;
							value = plane(v, at - (ay > 0 ? stepY : -stepY), ax * t, az * t, 1, stepZ);
						}
						else
						{
							float t = 1 / bz;
							value = plane(v, at - (az > 0 ? stepZ : -stepZ), ax * t, ay * t, 1, stepY);
						}
						v[at] = value;
					}
				}
			}
		}
		if (RAYS > 0)
		{
			frontRays(l, sc, n, w, gx - (lx - n), gy - (ly - n), gz - (lz - n), cells);
		}
		// per fine cell. A solid one holds what its free neighbours see, for a read with nothing free near it
		int fx0 = lx - n, fy0 = ly - n, fz0 = lz - n;
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
					fineVis[i] = (byte) (sharpen(o) * 255 + 0.5f);
				}
			}
		}
		// per read cell
		int bx = fx0 + 1 >> 1, by = fy0 + Y_SHIFT >> 1, bz = fz0 + 1 >> 1;
		int bw = (fx0 + w) - 1 + 1 >> 1, bh = (fy0 + w) - 1 + Y_SHIFT >> 1, bd = (fz0 + w) - 1 + 1 >> 1;
		bw -= bx - 1;
		bh -= by - 1;
		bd -= bz - 1;
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
		i = 0;
		for (int y = 0; y < w; ++y)
		{
			int cy = (fy0 + y + Y_SHIFT >> 1) - by;
			for (int z = 0; z < w; ++z)
			{
				int cz = (fz0 + z + 1 >> 1) - bz;
				for (int x = 0; x < w; ++x, ++i)
				{
					if (!solid[i])
					{
						int c = (cy * bd + cz) * bw + (fx0 + x + 1 >> 1) - bx;
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
				vis[c] = (byte) (sharpen(sum[c] / count[c]) * 255 + 0.5f);
			}
		}
		// a solid cell's trace, as a fine one's: no more than its least free neighbour
		for (int y = 0, c = 0; y < bh; ++y)
		{
			for (int z = 0; z < bd; ++z)
			{
				for (int x = 0; x < bw; ++x, ++c)
				{
					if (vis[c] != 0 && solidCoarse(bx + x, by + y, bz + z))
					{
						int least = 256;
						for (int ay = Math.max(0, y - 1); ay <= Math.min(bh - 1, y + 1); ++ay)
						{
							for (int az = Math.max(0, z - 1); az <= Math.min(bd - 1, z + 1); ++az)
							{
								for (int ax = Math.max(0, x - 1); ax <= Math.min(bw - 1, x + 1); ++ax)
								{
									int b = vis[(ay * bd + az) * bw + ax] & 255;
									if (b < least && !solidCoarse(bx + ax, by + ay, bz + az))
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
		l.vis = vis;
		l.bx = bx;
		l.by = by;
		l.bz = bz;
		l.bw = bw;
		l.bh = bh;
		l.bd = bd;
		l.fineVis = fineVis;
		l.fx = fx0;
		l.fy = fy0;
		l.fz = fz0;
		l.fw = w;
	}

	private static float sharpen(float o)
	{
		if (o <= 0)
		{
			return 0;
		}
		float t = (o - SHARP_LO) / (SHARP_HI - SHARP_LO);
		return t <= 0 ? 0 : t >= 1 ? 1 : t * t * (3 - 2 * t);
	}

	// The flood's front is wider than the shadow's: it spreads a little a shell. A cell at 0 or 1 is past its reach; a cell between gets the
	// truth, by rays to the part of the lamp's ball its centre sees. Steps along a ray's longest axis are a 26-connected walk: it can't pass
	// a closed surface's conservative voxels. So a cell lit here is on the lamp's side of everything closed, the flood or no flood.
	private void frontRays(Lamp l, Scratch sc, int n, int w, float cx, float cy, float cz, int cells)
	{
		float[] v = sc.v;
		boolean[] solid = sc.solid;
		float ball = Math.min(96, Math.max(16, 0.08f * l.r)) * BALL / FINE;
		float seed = SEED / FINE;
		// the ball's points, each as far out as the centre sees
		float[] pts = new float[Math.max(RAYS, 1) * 3];
		float[] dirs = {0, 0, 0, 1, 1, 1, -1, -1, 1, -1, 1, -1, 1, -1, -1, 1, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, -1};
		for (int k = 0; k < RAYS; ++k)
		{
			float dx = dirs[k * 3], dy = dirs[k * 3 + 1], dz = dirs[k * 3 + 2];
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			len = len > 0 ? ball / len : 0;
			dx *= len;
			dy *= len;
			dz *= len;
			float far = 1;
			int steps = (int) Math.ceil(ball) * 2;
			for (int i = 1; i <= steps; ++i)
			{
				float t = i / (float) steps;
				int at = cellAt(cx + dx * t, cy + dy * t, cz + dz * t, w);
				if (at < 0 || solid[at] && (dx * dx + dy * dy + dz * dz) * t * t > seed * seed)
				{
					far = (i - 1) / (float) steps;
					break;
				}
			}
			pts[k * 3] = cx + dx * far;
			pts[k * 3 + 1] = cy + dy * far;
			pts[k * 3 + 2] = cz + dz * far;
		}
		float[] rayed = sc.rayed;
		if (rayed.length < cells)
		{
			rayed = sc.rayed = new float[cells];
		}
		Arrays.fill(rayed, 0, cells, -1);
		int stepZ = w, stepY = w * w;
		int i = 0;
		for (int y = 0; y < w; ++y)
		{
			for (int z = 0; z < w; ++z)
			{
				for (int x = 0; x < w; ++x, ++i)
				{
					float o = v[i];
					if (solid[i] || o >= 1 || x == 0 || y == 0 || z == 0 || x == w - 1 || y == w - 1 || z == w - 1)
					{
						continue;
					}
					// the way out of the crust of cells a surface here has: from what is solid round it to what is free
					float ax = 0, ay = 0, az = 0;
					boolean crust = false, lit = o > 0;
					for (int dy = -1, at = i - stepY - stepZ - 1; dy <= 1; ++dy, at += stepY - 3 * stepZ)
					{
						for (int dz = -1; dz <= 1; ++dz, at += stepZ - 3)
						{
							for (int dx = -1; dx <= 1; ++dx, ++at)
							{
								if (solid[at])
								{
									crust = true;
									ax -= dx;
									ay -= dy;
									az -= dz;
								}
								else if (v0(v, at, i))
								{
									lit = true;
								}
							}
						}
					}
					if (!lit)
					{
						continue;
					}
					int seen = 0;
					for (int k = 0; k < RAYS; ++k)
					{
						float dx = pts[k * 3] - x - 0.5f, dy = pts[k * 3 + 1] - y - 0.5f, dz = pts[k * 3 + 2] - z - 0.5f;
						// a ray that leaves a surface passes the cells that only touch it
						int skip = crust && dx * ax + dy * ay + dz * az > 0 ? CRUST : 0;
						if (free(solid, w, x + 0.5f, y + 0.5f, z + 0.5f, pts[k * 3], pts[k * 3 + 1], pts[k * 3 + 2], cx, cy, cz, seed, skip))
						{
							++seen;
						}
					}
					rayed[i] = seen / (float) RAYS;
				}
			}
		}
		for (i = 0; i < cells; ++i)
		{
			if (rayed[i] >= 0)
			{
				v[i] = rayed[i];
			}
		}
	}

	private static boolean v0(float[] v, int at, int centre)
	{
		return at != centre && v[at] > 0;
	}

	private static int cellAt(float x, float y, float z, int w)
	{
		int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
		return ix < 0 || iy < 0 || iz < 0 || ix >= w || iy >= w || iz >= w ? -1 : (iy * w + iz) * w + ix;
	}

	// whether a cell's centre sees a point of the ball; within the seed of the lamp's centre nothing stops it
	private static boolean free(boolean[] solid, int w, float x0, float y0, float z0, float x1, float y1, float z1, float cx, float cy, float cz, float seed, int skip)
	{
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		float big = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		int steps = (int) Math.ceil(big);
		if (steps == 0)
		{
			return true;
		}
		float sx = dx / steps, sy = dy / steps, sz = dz / steps;
		float x = x0, y = y0, z = z0;
		float s2 = seed * seed;
		for (int i = 1; i < steps; ++i)
		{
			x += sx;
			y += sy;
			z += sz;
			int ix = (int) x, iy = (int) y, iz = (int) z;
			if (i > skip && ix >= 0 && iy >= 0 && iz >= 0 && ix < w && iy < w && iz < w && solid[(iy * w + iz) * w + ix])
			{
				float ax = x - cx, ay = y - cy, az = z - cz;
				if (ax * ax + ay * ay + az * az > s2)
				{
					return false;
				}
			}
		}
		return true;
	}

	private static float plane(float[] v, int at, float a, float b, int stepA, int stepB)
	{
		// the ray's crossing, relative to the cell behind this one: toward the lamp is minus the slope
		float fa = -a, fb = -b;
		int ia = fa < 0 ? -1 : 0, ib = fb < 0 ? -1 : 0;
		float ta = fa - ia, tb = fb - ib;
		int p = at + ia * stepA + ib * stepB;
		float v00 = v[p], v10 = v[p + stepA], v01 = v[p + stepB], v11 = v[p + stepA + stepB];
		float l = v00 + (v10 - v00) * ta, h = v01 + (v11 - v01) * ta;
		return l + (h - l) * tb;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// the sum

	private int[] fineBox(Lamp l)
	{
		int ox = Math.floorDiv(fineX - GRID_X, FINE), oy = Math.floorDiv(fineY - GRID_Y, FINE), oz = Math.floorDiv(fineZ - GRID_X, FINE);
		int[] t = l.fineChange != null ? l.fineChange : new int[]{0, 0, 0, l.fw, l.fw, l.fw};
		int x0 = Math.max(0, l.fx + t[0] - ox), y0 = Math.max(0, l.fy + t[1] - oy), z0 = Math.max(0, l.fz + t[2] - oz);
		int x1 = Math.min(SIZE, l.fx + t[3] - ox), y1 = Math.min(FINE_Y, l.fy + t[4] - oy), z1 = Math.min(SIZE, l.fz + t[5] - oz);
		return x1 > x0 && y1 > y0 && z1 > z0 ? new int[]{x0, y0, z0, x1 - x0, y1 - y0, z1 - z0} : null;
	}

	private int[] nearBox(Lamp l)
	{
		int ox = Math.floorDiv(windowX - GRID_X, CELL), oy = Math.floorDiv(windowY - GRID_Y, CELL), oz = Math.floorDiv(windowZ - GRID_X, CELL);
		int[] t = l.nearChange != null ? l.nearChange : new int[]{0, 0, 0, l.bw, l.bh, l.bd};
		int x0 = Math.max(0, l.bx + t[0] - ox), y0 = Math.max(0, l.by + t[1] - oy), z0 = Math.max(0, l.bz + t[2] - oz);
		int x1 = Math.min(SIZE, l.bx + t[3] - ox), y1 = Math.min(NEAR_Y, l.by + t[4] - oy), z1 = Math.min(SIZE, l.bz + t[5] - oz);
		return x1 > x0 && y1 > y0 && z1 > z0 ? new int[]{x0, y0, z0, x1 - x0, y1 - y0, z1 - z0} : null;
	}

	private int[] farBox(Lamp l)
	{
		int r = FAR_CELL / CELL;
		int ox = Math.floorDiv(FAR_X - GRID_X, FAR_CELL), oy = Math.floorDiv(farY - GRID_Y, FAR_CELL);
		int[] t = l.nearChange != null ? l.nearChange : new int[]{0, 0, 0, l.bw, l.bh, l.bd};
		int x0 = Math.max(0, Math.floorDiv(l.bx + t[0], r) - ox), y0 = Math.max(0, Math.floorDiv(l.by + t[1], r) - oy), z0 = Math.max(0, Math.floorDiv(l.bz + t[2], r) - ox);
		int x1 = Math.min(SIZE, Math.floorDiv(l.bx + t[3] - 1, r) - ox + 1), y1 = Math.min(FAR_Y, Math.floorDiv(l.by + t[4] - 1, r) - oy + 1), z1 = Math.min(SIZE, Math.floorDiv(l.bz + t[5] - 1, r) - ox + 1);
		return x1 > x0 && y1 > y0 && z1 > z0 ? new int[]{x0, y0, z0, x1 - x0, y1 - y0, z1 - z0} : null;
	}

	private Upload sumFine(int[] box, List<Lamp> list, long now)
	{
		int w = box[3], h = box[4], d = box[5], cells = w * h * d;
		float[] f = new float[cells * 8];
		int ox = Math.floorDiv(fineX - GRID_X, FINE), oy = Math.floorDiv(fineY - GRID_Y, FINE), oz = Math.floorDiv(fineZ - GRID_X, FINE);
		for (Lamp l : list)
		{
			if (l.hidden || l.fineVis == null || l.nr + l.ng + l.nb <= 0)
			{
				continue;
			}
			addLamp(l, f, box, l.fineVis, l.fineBefore, l.fx, l.fy, l.fz, l.fw, l.fw, l.fw, ox + box[0], oy + box[1], oz + box[2], 1, now, FINE, 0.5f, 0.5f, false, 0);
		}
		Upload u = upload(box, Math.floorMod(Math.floorDiv(fineX, FINE) + box[0], SIZE), box[1], Math.floorMod(Math.floorDiv(fineZ, FINE) + box[2], SIZE));
		short one = half(1), nook = half(NOOK);
		int i = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int z = 0; z < d; ++z)
			{
				for (int x = 0; x < w; ++x, ++i)
				{
					int cx = ox + box[0] + x, cy = oy + box[1] + y, cz = oz + box[2] + z;
					boolean solid = solidFine(cx, cy, cz);
					if (solid && NOOK <= 0)
					{
						continue;
					}
					write(u, i, f, solid ? NOOK : 1, solid ? nook : one);
				}
			}
		}
		return u;
	}

	private static Upload upload(int[] box, int x, int y, int z)
	{
		Upload u = new Upload();
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

	private static void write(Upload u, int i, float[] f, float s, short air)
	{
		int a = i * 4, g = i * 8;
		u.a[a] = half(f[g] * s);
		u.a[a + 1] = half(f[g + 1] * s);
		u.a[a + 2] = half(f[g + 2] * s);
		u.a[a + 3] = air;
		u.b[a] = half(f[g + 3] * s);
		u.b[a + 1] = half(f[g + 4] * s);
		u.b[a + 2] = half(f[g + 5] * s);
		u.b[a + 3] = half(f[g + 6] * s);
	}

	private Upload sumNear(int[] box, List<Lamp> list, long now)
	{
		int w = box[3], h = box[4], d = box[5], cells = w * h * d;
		float[] f = new float[cells * 8];
		int ox = Math.floorDiv(windowX - GRID_X, CELL), oy = Math.floorDiv(windowY - GRID_Y, CELL), oz = Math.floorDiv(windowZ - GRID_X, CELL);
		for (Lamp l : list)
		{
			if (l.hidden || l.vis == null || l.nr + l.ng + l.nb <= 0)
			{
				continue;
			}
			addLamp(l, f, box, l.vis, l.before, l.bx, l.by, l.bz, l.bw, l.bh, l.bd, ox + box[0], oy + box[1], oz + box[2], 1, now, CELL, 0, 0.5f - 0.5f * Y_SHIFT, false, 0);
		}
		// where the world cell is in the toroidal level
		Upload u = upload(box, Math.floorMod(Math.floorDiv(windowX, CELL) + box[0], SIZE), box[1] + NEAR_AT, Math.floorMod(Math.floorDiv(windowZ, CELL) + box[2], SIZE));
		short one = half(1), nook = half(NOOK);
		int i = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int z = 0; z < d; ++z)
			{
				for (int x = 0; x < w; ++x, ++i)
				{
					int cx = ox + box[0] + x, cy = oy + box[1] + y, cz = oz + box[2] + z;
					boolean solid = solidCoarse(cx, cy, cz);
					if (solid && (NOOK <= 0 || allSolid(cx, cy, cz)))
					{
						continue;
					}
					write(u, i, f, solid ? NOOK : 1, solid ? nook : one);
				}
			}
		}
		return u;
	}

	private Upload sumFar(int[] box, List<Lamp> list, long now)
	{
		int w = box[3], h = box[4], d = box[5], cells = w * h * d;
		float[] f = new float[cells * 8];
		int r = FAR_CELL / CELL;
		int ox = Math.floorDiv(FAR_X - GRID_X, FAR_CELL), oy = Math.floorDiv(farY - GRID_Y, FAR_CELL);
		for (Lamp l : list)
		{
			if (l.vis != null && l.nr + l.ng + l.nb > 0)
			{
				// a lamp on a storey the client hides glows in the air and falls on no surface: alpha is what may
				addLamp(l, f, box, l.vis, l.before, l.bx, l.by, l.bz, l.bw, l.bh, l.bd, (ox + box[0]) * r, (oy + box[1]) * r, (ox + box[2]) * r, r, now, CELL, 0, 0.5f - 0.5f * Y_SHIFT, l.hidden, FAR_RIM);
			}
		}
		Upload u = upload(box, box[0], box[1] + FAR_AT, box[2]);
		float scale = 1f / (r * r * r);
		for (int i = 0; i < cells; ++i)
		{
			write(u, i, f, scale, half(f[i * 8 + 7] * scale));
		}
		return u;
	}

	// Adds a lamp into a box of 8 floats a cell, from a grid of its visibility at gx, gy, gz of gw x gh x gd cells: from grid cell (cx, cy, cz) on,
	// r grid cells to a box cell. A grid cell's centre is at (i + shift) * size from the grid's corner.
	private void addLamp(Lamp l, float[] f, int[] box, byte[] vis, byte[] before, int gx, int gy, int gz, int gw, int gh, int gd, int cx, int cy, int cz, int r, long now,
		float size, float shift, float shiftY, boolean noSurface, float rim)
	{
		int w = box[3], h = box[4], d = box[5];
		int x0 = Math.max(cx, gx), y0 = Math.max(cy, gy), z0 = Math.max(cz, gz);
		int x1 = Math.min(cx + w * r, gx + gw), y1 = Math.min(cy + h * r, gy + gh), z1 = Math.min(cz + d * r, gz + gd);
		// a level's cells blur a lamp's edge past its reach: a coarse one's falloff ends this much short of it
		float reach = l.r - rim;
		float r2 = reach * reach, ir = 1 / reach;
		float lum = 0.2126f * l.nr + 0.7152f * l.ng + 0.0722f * l.nb;
		float px = l.x - originX, py = l.y - originY, pz = l.z - originZ;
		float ease = before == null || before.length != vis.length ? 1 : Math.min(1, (now - l.easeFrom) / (float) EASE_NANOS);
		ease = ease * ease * (3 - 2 * ease);
		float onSurface = noSurface ? 0 : 1;
		for (int y = y0; y < y1; ++y)
		{
			float wy = (y + shiftY) * size + GRID_Y - l.y;
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
					int c = (row + (x - cx) / r) * 8;
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
		windowX = Math.floorDiv((int) focalX - SIZE * CELL / 2, CELL * STEP_CELLS) * STEP_CELLS * CELL;
		windowZ = Math.floorDiv((int) focalZ - SIZE * CELL / 2, CELL * STEP_CELLS) * STEP_CELLS * CELL;
		windowY = Math.floorDiv(Math.round(ground) - 1100, CELL) * CELL;
		int stepUnits = CELL * STEP_CELLS;
		fineX = Math.floorDiv((int) focalX - SIZE * FINE / 2, stepUnits) * stepUnits;
		fineZ = Math.floorDiv((int) focalZ - SIZE * FINE / 2, stepUnits) * stepUnits;
		fineY = Math.floorDiv(Math.round(ground) - FINE_ABOVE, FINE) * FINE;
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
		rebuildBits();
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
			heap += l.vis.length + l.fineVis.length;
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

	/** The way from a point to lamp i over the fine solid cells, and its cells' visibility above the point. */
	public String offlineWay(int i, float x, float y, float z)
	{
		Lamp l = lamps.get((long) i);
		StringBuilder o = new StringBuilder("way lamp ").append(l.x).append(' ').append(l.y).append(' ').append(l.z).append(" r ").append(l.r).append(" origin ").append(l.ox).append(' ').append(l.oz).append('\n');
		for (float up = 0; up <= 48; up += 16)
		{
			float t = 0;
			float qy = y - up;
			float d = (float) Math.sqrt((l.ox - x) * (l.ox - x) + (l.y - qy) * (l.y - qy) + (l.oz - z) * (l.oz - z));
			o.append("up ").append(up).append(' ');
			for (int k = 0; k <= 80; ++k)
			{
				t = k / 80f;
				o.append(solidFine(Math.floorDiv((int) (x + (l.ox - x) * t) - GRID_X, FINE), Math.floorDiv((int) (qy + (l.y - qy) * t) - GRID_Y, FINE), Math.floorDiv((int) (z + (l.oz - z) * t) - GRID_X, FINE)) ? '#' : '.');
			}
			int a = Math.floorDiv((int) x - GRID_X, FINE) - l.fx, b = Math.floorDiv((int) qy - GRID_Y, FINE) - l.fy, c = Math.floorDiv((int) z - GRID_X, FINE) - l.fz;
			o.append(" vis ").append(a >= 0 && b >= 0 && c >= 0 && a < l.fw && b < l.fw && c < l.fw ? l.fineVis[(b * l.fw + c) * l.fw + a] & 255 : -1).append(" len ").append(d).append('\n');
		}
		return o.toString();
	}

	/** Slices round a point, read cells then fine: x across, z down, for three layers of y. */
	public String offlineSlice(int wx, int wy, int wz)
	{
		StringBuilder o = new StringBuilder();
		int cx = Math.floorDiv(wx - GRID_X + CELL / 2, CELL), cz = Math.floorDiv(wz - GRID_X + CELL / 2, CELL), cy = Math.floorDiv(wy - GRID_Y + (CELL * Y_SHIFT) / 2, CELL);
		for (int k = -1; k <= 2; ++k)
		{
			o.append("read y ").append((cy - k) * CELL + GRID_Y).append(" x from ").append((cx - 5) * CELL + GRID_X).append(" z from ").append((cz - 5) * CELL + GRID_X).append('\n');
			for (int z = -5; z <= 5; ++z)
			{
				for (int x = -5; x <= 5; ++x)
				{
					o.append(solidCoarse(cx + x, cy - k, cz + z) ? '#' : '.');
				}
				o.append('\n');
			}
		}
		int fx = Math.floorDiv(wx - GRID_X, FINE), fz = Math.floorDiv(wz - GRID_X, FINE), fy = Math.floorDiv(wy - GRID_Y, FINE);
		for (int k = 0; k <= 4; k += 2)
		{
			o.append("fine y ").append((fy - k) * FINE + GRID_Y).append(" x from ").append((fx - 10) * FINE + GRID_X).append(" z from ").append((fz - 10) * FINE + GRID_X).append('\n');
			for (int z = -10; z <= 10; ++z)
			{
				for (int x = -10; x <= 10; ++x)
				{
					o.append(solidFine(fx + x, fy - k, fz + z) ? '#' : '.');
				}
				o.append('\n');
			}
		}
		return o.toString();
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
		Lamp l = lamps.get((long) i);
		float gx = (x - GRID_X) / CELL, gy = (y - GRID_Y) / CELL - 0.5f + 0.5f * Y_SHIFT, gz = (z - GRID_X) / CELL;
		float e = Math.min(Math.min(x - windowX, windowX + SIZE * CELL - x), Math.min(z - windowZ, windowZ + SIZE * CELL - z));
		if (e < 0 || y < windowY || y > windowY + NEAR_Y * CELL)
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
			if (solidCoarse(cx, cy, cz))
			{
				if (NOOK <= 0 || allSolid(cx, cy, cz))
				{
					continue;
				}
				w *= NOOK;
			}
			air += w;
			int a = cx - l.bx, b = cy - l.by, c = cz - l.bz;
			if (a >= 0 && b >= 0 && c >= 0 && a < l.bw && b < l.bh && c < l.bd)
			{
				sum += w * (l.vis[(b * l.bd + c) * l.bw + a] & 255) / 255f;
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

	/** The far level's light along a line through lamp i, against its falloff. */
	public String offlineFar(int i)
	{
		Lamp l = lamps.get((long) i);
		l.nr = l.ng = l.nb = 1;
		farY = Math.floorDiv(Math.round(l.y) - 1500, FAR_CELL) * FAR_CELL;
		int r = FAR_CELL / CELL, ox = Math.floorDiv(FAR_X - GRID_X, FAR_CELL), oy = Math.floorDiv(farY - GRID_Y, FAR_CELL);
		int[] box = {0, 0, 0, SIZE, FAR_Y, SIZE};
		float[] f = new float[SIZE * FAR_Y * SIZE * 8];
		addLamp(l, f, box, l.vis, null, l.bx, l.by, l.bz, l.bw, l.bh, l.bd, ox * r, oy * r, ox * r, r, 0, CELL, 0, 0.5f - 0.5f * Y_SHIFT, false, FAR_RIM);
		StringBuilder o = new StringBuilder("far lamp " + i + " r " + l.r + "\n");
		int cy = Math.floorDiv((int) l.y - GRID_Y, FAR_CELL) - oy, cz = Math.floorDiv((int) l.z - GRID_X, FAR_CELL) - ox;
		for (int y = cy - 3; y <= cy + 3; ++y)
		{
			o.append("y ").append(y - cy).append(':');
			for (int x = Math.floorDiv((int) l.x - GRID_X, FAR_CELL) - ox - 12; x <= Math.floorDiv((int) l.x - GRID_X, FAR_CELL) - ox + 12; ++x)
			{
				if (y >= 0 && y < FAR_Y && x >= 0 && x < SIZE)
				{
					o.append(String.format(" %.2f", f[((y * SIZE + cz) * SIZE + x) * 8] / (r * r * r)));
				}
			}
			o.append('\n');
		}
		return o.toString();
	}

	/** The same from the finest level. */
	public float offlineReadFine(int i, float x, float y, float z)
	{
		Lamp l = lamps.get((long) i);
		float gx = (x - GRID_X) / FINE - 0.5f, gy = (y - GRID_Y) / FINE - 0.5f, gz = (z - GRID_X) / FINE - 0.5f;
		float e = Math.min(Math.min(x - fineX, fineX + SIZE * FINE - x), Math.min(z - fineZ, fineZ + SIZE * FINE - z));
		if (e < 0 || y < fineY || y > fineY + FINE_Y * FINE)
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
			if (solidFine(cx, cy, cz))
			{
				if (NOOK <= 0)
				{
					continue;
				}
				w *= NOOK;
			}
			air += w;
			int a = cx - l.fx, b = cy - l.fy, c = cz - l.fz;
			if (a >= 0 && b >= 0 && c >= 0 && a < l.fw && b < l.fw && c < l.fw)
			{
				sum += w * (l.fineVis[(b * l.fw + c) * l.fw + a] & 255) / 255f;
			}
		}
		if (air < 1e-4f)
		{
			return -1;
		}
		AIR.get()[0] = air;
		return sum / air;
	}

	public static String defines()
	{
		return "#define LAMP_VOLUME_SIZE " + SIZE + ".0\n#define LAMP_VOLUME_FINE_Y " + FINE_Y + ".0\n#define LAMP_VOLUME_NEAR_Y " + NEAR_Y + ".0\n#define LAMP_VOLUME_FAR_Y " + FAR_Y + ".0\n"
			+ "#define LAMP_VOLUME_NEAR_AT " + NEAR_AT + ".0\n#define LAMP_VOLUME_FAR_AT " + FAR_AT + ".0\n#define LAMP_VOLUME_GAIN_AT " + GAIN_AT + ".0\n"
			+ "#define LAMP_VOLUME_LAYERS " + LAYERS + ".0\n#define LAMP_VOLUME_FINE " + FINE + ".0\n#define LAMP_VOLUME_CELL " + CELL + ".0\n"
			+ "#define LAMP_VOLUME_Y_SHIFT " + (0.5f * Y_SHIFT) + "\n#define LAMP_VOLUME_FAR_CELL " + FAR_CELL + ".0\n#define LAMP_VOLUME_GAIN_SIZE " + GAIN_SIZE + ".0\n#define LAMP_VOLUME_GAIN_CELL " + GAIN_CELL + ".0\n";
	}
}
