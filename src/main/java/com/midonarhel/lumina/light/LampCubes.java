package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.scene.Zone;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The lamps' shadow cubes: each lamp's depth seen from it on the six faces of a cube, drawn from what the frame draws
 * (its levels and the roofs the client shows: a roof it hides stops nothing), into one depth atlas the fog's glow reads
 * and the surfaces (light/lamp_cube.glsl). A lamp's faces are drawn when it first takes a slot, moves, or what is
 * drawn changes (the levels, the hidden roofs, the scene). Each frame: beginFrame before the lamps are culled,
 * slotFor for each lamp kept (its slot, which LightClusters puts in its texels, or -1 while its cube waits or it has
 * none: more lamps than SLOTS), choose after the cull (the nearest waiting ones get their slots now), and draw after
 * the scene, before the composite and the fog read the cubes. Static scenery only: no players or NPCs.
 */
public final class LampCubes
{
	private static final Logger log = LoggerFactory.getLogger(LampCubes.class);

	// a face's texels, the atlas's slots (COLS x ROWS, each three faces wide and two high); of them at most IN_VIEW for
	// the lamps in view, the rest spare: a lamp's new cube is drawn into one while its old one still shows
	public static final int FACE = 128, COLS = 12, ROWS = 10, SLOTS = COLS * ROWS, IN_VIEW = 88;
	public static final int WIDTH = FACE * 3 * COLS, HEIGHT = FACE * 2 * ROWS;
	// texels round each face past its 90 degrees, so a filtered read near a face's edge stays on the face
	public static final int GUARD = 4;
	public static final float TAN_HALF = FACE / (float) (FACE - 2 * GUARD);
	// nothing nearer the lamp's origin than this casts (the origin is 24 off the wall it hangs on: LampWalls)
	public static final float NEAR = 8;
	// The most lamps drawn a frame, the nearest first (fps1: 4 a frame 0.05 ms at the least, 0.34 at the median). The
	// spare slots (SLOTS - IN_VIEW) are each held through a crossing over: 32 of them over FADE_NANOS keep up with it.
	// A lamp whose cube a
	// change left out of date (a roof hidden or shown, a door) keeps showing it while its new one is drawn into a spare
	// slot, then crosses over to it in FADE_NANOS: no frame pays for many, and nothing falls back. A lamp new in view has
	// no cube until its first is drawn: its walls and the drawn roofs (fog), the light map and walls (surfaces) till then.
	public static final int LAMPS_PER_FRAME = 4;
	// (bench: -Dlumina.cubeFadeNanos, 0 for none)
	static final long FADE_NANOS = Long.getLong("lumina.cubeFadeNanos", 150_000_000L);

	// each face's forward, right and up (fog_frag.glsl picks the face and its axes the same way)
	private static final float[][] FACES = {
		{1, 0, 0, 0, 0, 1, 0, 1, 0}, {-1, 0, 0, 0, 0, 1, 0, 1, 0},
		{0, 1, 0, 1, 0, 0, 0, 0, 1}, {0, -1, 0, 1, 0, 0, 0, 0, 1},
		{0, 0, 1, 1, 0, 0, 0, 1, 0}, {0, 0, -1, 1, 0, 0, 0, 1, 0},
	};

	private int fbo, depth;
	// this frame: whether the cubes are on, the scene they are drawn from, the camera, the lamps waiting (distance, index)
	// and each index's slot, the slots to draw
	private boolean active;
	private LuminaPlugin.SceneContext frameCtx;
	private float camX, camY, camZ;
	private int pending;
	private final int[] slotOfIndex = new int[LightClusters.MAX_LIGHTS];
	// per lamp index this frame: the code its texels got at the cull (cubeCode), and what choose changes it by
	private final int[] codeOfIndex = new int[LightClusters.MAX_LIGHTS], codeChange = new int[LightClusters.MAX_LIGHTS];
	private int inView;
	private final int[] toDraw = new int[LAMPS_PER_FRAME];
	private int draws;
	// per slot: the lamp's place and reach it was drawn for (x, y, z, radius), the frame it was last used; whether it
	// holds the lamp's cube, whether a change left that out of date; the slot it is crossing over from (-1: none) and
	// since when; and, for a slot being crossed over from, until when it must stay
	private final float[] drawnFor = new float[SLOTS * 4];
	private final long[] used = new long[SLOTS];
	private final boolean[] drawn = new boolean[SLOTS], stale = new boolean[SLOTS];
	private final int[] fadeFrom = new int[SLOTS];
	private final long[] fadeSince = new long[SLOTS], retiredUntil = new long[SLOTS];
	private long now;
	private final Map<Long, Integer> slotOf = new HashMap<>();
	private final long[] keyOf = new long[SLOTS];
	private final long[] waiting = new long[LightClusters.MAX_LIGHTS];
	private final float[] lightProj = new float[16];
	private long frame;
	// what was drawn: the scene, its levels and hidden roofs
	private Object drawnScene;
	private int drawnMin = -1, drawnLevel = -1, drawnMax = -1;
	private Set<Integer> drawnRoofs = new HashSet<>();
	private int drawnLast, lastPending, lastZones, lastInvalidated;
	// the tiles of the roofs whose hiding changed this frame, extended scene, roofSize a side
	private boolean[] roofTiles;
	private int roofSize;
	// per zone: the one the cubes were drawn from, what it drew (Zone.drawnHash), and whether that changed this frame
	private Zone[][] seen;
	private int[][] hashes;
	private boolean[][] changed;
	// bench: off (the walls and drawn roofs alone, as before the cubes), or every cube drawn again each frame (its cost)
	private static final boolean OFF = !Boolean.getBoolean("lumina.lampCubes");
	private static final boolean REDRAW = Boolean.getBoolean("lumina.lampCubesRedraw");

	public boolean isInitialized()
	{
		return fbo != 0;
	}

	public int atlas()
	{
		return depth;
	}

	/** Faces drawn last frame (bench). */
	public int drawnLast()
	{
		return drawnLast;
	}

	private void init()
	{
		depth = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, depth);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT16, WIDTH, HEIGHT, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT,
			(java.nio.ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	public void free()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(depth);
			fbo = depth = 0;
		}
		slotOf.clear();
		Arrays.fill(drawn, false);
		Arrays.fill(keyOf, 0);
		Arrays.fill(fadeFrom, -1);
		drawnScene = null;
	}

	// a lamp's key: its place to 4 units and its reach
	private static long key(PointLight l)
	{
		long x = Math.round(l.x / 4) & 0xFFFF, y = Math.round(l.y / 4) & 0xFFFF, z = Math.round(l.z / 4) & 0xFFFF;
		long r = Math.round(l.radius / 4) & 0xFFFF;
		return x << 48 | y << 32 | z << 16 | r;
	}

	/**
	 * Before the frame's lamps are culled (LampFrame): whether the cubes are on, and which of them what the frame draws
	 * (its levels and hidden roofs, set by now) leaves out of date.
	 */
	public void beginFrame(LuminaPlugin plugin)
	{
		LuminaPlugin.SceneContext ctx = plugin.root;
		active = !OFF && plugin.config.pointLights() && ctx != null && ctx.hideRoofIds != null && plugin.glShadowProgram != 0;
		pending = 0;
		draws = 0;
		inView = 0;
		if (!active)
		{
			return;
		}
		++frame;
		now = com.midonarhel.lumina.common.Clock.nanos();
		frameCtx = ctx;
		camX = plugin.frameCameraX;
		camY = plugin.frameCameraY;
		camZ = plugin.frameCameraZ;
		lastInvalidated = 0;
		lastZones = renew(ctx, sceneRoofs(plugin, ctx));
		if (REDRAW)
		{
			invalidateAll();
		}
	}

	/**
	 * During the cull, lamp index i kept: the code for its texels (16 x (its cube's slot + 1), + 4096 x (the slot it is
	 * crossing over from + 1) + 524288 x how far, 0 to 15; 0: no cube yet), and whether it waits for a cube drawn.
	 */
	int cubeCode(PointLight l, int i, float originX, float originZ)
	{
		slotOfIndex[i] = -1;
		codeOfIndex[i] = 0;
		codeChange[i] = 0;
		if (!active)
		{
			return 0;
		}
		long k = key(l);
		Integer s = slotOf.get(k);
		if (s == null)
		{
			if (inView >= IN_VIEW || (s = freeSlot()) < 0)
			{
				return 0;
			}
			take(s, k);
			// seen from its origin (LampWalls.origin: off the walls it hangs on, as its lines start), at its height: a
			// wall lamp at its own wall's face would have that wall nearer than NEAR and cut away, and light through it
			drawnFor[s * 4] = originX;
			drawnFor[s * 4 + 1] = l.y;
			drawnFor[s * 4 + 2] = originZ;
			drawnFor[s * 4 + 3] = Math.abs(l.radius);
		}
		++inView;
		used[s] = frame;
		slotOfIndex[i] = s;
		int code = drawn[s] ? code(s) : 0;
		codeOfIndex[i] = code;
		if (!drawn[s] || stale[s])
		{
			// its distance from the camera and its index, to draw the nearest first; a lamp with no cube yet before any
			// whose old one still shows
			float dx = l.x - camX, dy = l.y - camY, dz = l.z - camZ;
			waiting[pending++] = (drawn[s] ? 1L << 62 : 0) | (long) Float.floatToIntBits(dx * dx + dy * dy + dz * dz) << 10 | i;
		}
		return code;
	}

	// whether slot s is its lamp's cube now (not one it crosses over from, or a lamp's long gone)
	private boolean holds(int s)
	{
		Integer cur = slotOf.get(keyOf[s]);
		return cur != null && cur == s;
	}

	// slot s to lamp key k, from whatever it held
	private void take(int s, long k)
	{
		if (keyOf[s] != 0 && holds(s))
		{
			slotOf.remove(keyOf[s]);
		}
		keyOf[s] = k;
		slotOf.put(k, s);
		drawn[s] = stale[s] = false;
		fadeFrom[s] = -1;
	}

	// the texels' code of the lamp whose cube is slot s: and the one it crosses over from, while it does
	private int code(int s)
	{
		int code = 16 * (s + 1);
		int from = fadeFrom[s];
		if (from >= 0)
		{
			long t = now - fadeSince[s];
			if (t >= FADE_NANOS)
			{
				fadeFrom[s] = -1;
				return code;
			}
			used[from] = frame;
			code += 4096 * (from + 1) + 524288 * (int) (15 * t / FADE_NANOS);
		}
		return code;
	}

	/**
	 * After the cull: the nearest waiting lamps, at most LAMPS_PER_FRAME, drawn this frame (draw); their indices into
	 * indices and how many, so LightClusters changes their codes (codeChange).
	 */
	int choose(int[] indices, int count)
	{
		Arrays.sort(waiting, 0, pending);
		draws = 0;
		for (int w = 0; w < pending && draws < LAMPS_PER_FRAME; ++w)
		{
			int i = (int) (waiting[w] & (LightClusters.MAX_LIGHTS - 1));
			int s = slotOfIndex[i];
			int target = s;
			if (drawn[s])
			{
				// out of date: the new cube into a spare slot, the old one shown until then and crossed over from
				target = freeSlot();
				if (target < 0)
				{
					continue;
				}
				long k = keyOf[s];
				take(target, k);
				System.arraycopy(drawnFor, s * 4, drawnFor, target * 4, 4);
				fadeFrom[target] = s;
				fadeSince[target] = now;
				retiredUntil[s] = now + FADE_NANOS;
				used[target] = frame;
			}
			drawn[target] = true;
			toDraw[draws] = target;
			indices[draws++] = i;
			codeChange[i] = code(target) - codeOfIndex[i];
		}
		drawnLast = draws * 6;
		// a change to what is drawn, not a lamp that moves
		if (lastZones > 0 || REDRAW && frame % 100 == 1)
		{
			log.info("Lamp cubes: {} changed zones, {} lamps to draw, {} a frame, nearest first; {} of the frame's {} lamps"
				+ " with a cube", lastZones, pending, LAMPS_PER_FRAME, Math.min(count, IN_VIEW), count);
		}
		lastPending = pending - draws;
		return draws;
	}

	int codeChange(int i)
	{
		return codeChange[i];
	}

	/** After the scene: the faces of the lamps chosen this frame. The shadow program and its uniforms are the sun's. */
	public void draw(LuminaPlugin plugin)
	{
		if (!active || draws == 0)
		{
			return;
		}
		if (fbo == 0)
		{
			init();
		}
		draw(plugin, frameCtx, toDraw, draws);
	}

	/**
	 * What the frame draws, against what the cubes were drawn from: a new scene, every cube again; else the zones that
	 * draw something else (a zone uploaded again, as for a door; a roof hidden or shown, or the levels, changing what
	 * the zone draws: Zone.drawnHash), and only the cubes whose reach takes in one of them. The number of zones that
	 * changed (all of them for a new scene).
	 */
	private int renew(LuminaPlugin.SceneContext ctx, int[][][] sceneRoofs)
	{
		boolean levels = drawnMin != ctx.minLevel || drawnLevel != ctx.level || drawnMax != ctx.maxLevel;
		boolean roofs = !Objects.equals(drawnRoofs, ctx.hideRoofIds);
		// a roof hidden or shown, the levels the same: the lamps that reach its tiles, not every lamp in its zones
		Set<Integer> toggled = null;
		if (roofs && !levels && drawnScene == ctx && sceneRoofs != null)
		{
			toggled = new HashSet<>(drawnRoofs);
			toggled.addAll(ctx.hideRoofIds);
			Set<Integer> both = new HashSet<>(drawnRoofs);
			both.retainAll(ctx.hideRoofIds);
			toggled.removeAll(both);
		}
		levels |= roofs && toggled == null;
		if (levels || roofs)
		{
			drawnMin = ctx.minLevel;
			drawnLevel = ctx.level;
			drawnMax = ctx.maxLevel;
			drawnRoofs = new HashSet<>(ctx.hideRoofIds);
		}
		if (drawnScene != ctx || seen == null || seen.length != ctx.sizeX || seen[0].length != ctx.sizeZ)
		{
			drawnScene = ctx;
			seen = new Zone[ctx.sizeX][ctx.sizeZ];
			hashes = new int[ctx.sizeX][ctx.sizeZ];
			changed = new boolean[ctx.sizeX][ctx.sizeZ];
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					seen[x][z] = ctx.zones[x][z];
					hashes[x][z] = ctx.zones[x][z].drawnHash(ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
				}
			}
			// a new scene: no cube holds anything of it
			Arrays.fill(drawn, false);
			Arrays.fill(fadeFrom, -1);
			return ctx.sizeX * ctx.sizeZ;
		}
		int zones = 0;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				boolean again = zone != seen[x][z];
				changed[x][z] = false;
				if (!again && !levels && !roofs)
				{
					continue;
				}
				// a zone not uploaded yet draws nothing: it counts once it is
				int h = zone.drawnHash(ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
				if (again && !zone.drawable())
				{
					continue;
				}
				seen[x][z] = zone;
				// a roof toggled alone: the hash kept up to date, the lamps found by the roof's tiles below
				if (again || levels && h != hashes[x][z])
				{
					changed[x][z] = true;
					++zones;
				}
				hashes[x][z] = h;
			}
		}
		int tiles = toggled != null ? markRoofTiles(sceneRoofs, toggled) : 0;
		if (zones == 0 && tiles == 0)
		{
			return 0;
		}
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		for (int s = 0; s < SLOTS; ++s)
		{
			if (!drawn[s] || stale[s] || keyOf[s] == 0 || !holds(s))
			{
				continue;
			}
			float x = drawnFor[s * 4], z = drawnFor[s * 4 + 2], r = drawnFor[s * 4 + 3];
			if (tiles > 0 && reachesTile(x, z, r))
			{
				stale[s] = true;
				++lastInvalidated;
				continue;
			}
			search:
			for (int zx = Math.max(0, (int) Math.floor((x - r) / 1024) + offset); zx <= Math.min(ctx.sizeX - 1, (int) Math.floor((x + r) / 1024) + offset); ++zx)
			{
				for (int zz = Math.max(0, (int) Math.floor((z - r) / 1024) + offset); zz <= Math.min(ctx.sizeZ - 1, (int) Math.floor((z + r) / 1024) + offset); ++zz)
				{
					if (changed[zx][zz])
					{
						stale[s] = true;
						++lastInvalidated;
						break search;
					}
				}
			}
		}
		return zones + tiles;
	}

	/**
	 * The tiles (extended scene) where a roof whose hiding changed stands, on any level (the scene's roof ids can sit a
	 * level under the storeys they hide: glow1's roof 26), into roofTiles; how many.
	 */
	private int markRoofTiles(int[][][] sceneRoofs, Set<Integer> toggled)
	{
		int size = sceneRoofs[0].length;
		if (roofTiles == null || roofTiles.length != size * size)
		{
			roofTiles = new boolean[size * size];
		}
		Arrays.fill(roofTiles, false);
		roofSize = size;
		int n = 0;
		for (int lv = 0; lv < sceneRoofs.length; ++lv)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					if (sceneRoofs[lv][x][y] != 0 && toggled.contains(sceneRoofs[lv][x][y]) && !roofTiles[x * size + y])
					{
						roofTiles[x * size + y] = true;
						++n;
					}
				}
			}
		}
		return n;
	}

	// whether a lamp at x, z (local units) with reach r comes within half a tile of a marked roof tile (a roof's models
	// overhang their tiles a little)
	private boolean reachesTile(float x, float z, float r)
	{
		int off = LuminaPlugin.SCENE_OFFSET;
		float reach = r + 64;
		int x0 = Math.max(0, (int) Math.floor((x - reach) / 128) + off), x1 = Math.min(roofSize - 1, (int) Math.floor((x + reach) / 128) + off);
		int y0 = Math.max(0, (int) Math.floor((z - reach) / 128) + off), y1 = Math.min(roofSize - 1, (int) Math.floor((z + reach) / 128) + off);
		for (int tx = x0; tx <= x1; ++tx)
		{
			for (int ty = y0; ty <= y1; ++ty)
			{
				if (!roofTiles[tx * roofSize + ty])
				{
					continue;
				}
				float lx = (tx - off) * 128, lz = (ty - off) * 128;
				float cx = Math.max(lx, Math.min(x, lx + 128)) - x, cz = Math.max(lz, Math.min(z, lz + 128)) - z;
				if (cx * cx + cz * cz < reach * reach)
				{
					return true;
				}
			}
		}
		return false;
	}

	// the top level scene's roof ids by level and tile (extended), the client's (client thread)
	private static int[][][] sceneRoofs(LuminaPlugin plugin, LuminaPlugin.SceneContext ctx)
	{
		net.runelite.api.WorldView wv = plugin.client.getTopLevelWorldView();
		net.runelite.api.Scene scene = wv != null ? wv.getScene() : null;
		return scene != null ? scene.getRoofs() : null;
	}

	/** Lamps whose cubes the last update drew, and zones and roof tiles it found changed (bench). */
	public int lastDrawnLamps()
	{
		return drawnLast / 6;
	}

	/** Every cube drawn again (bench: what a selective redraw should equal). */
	public void invalidateAll()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			stale[s] = drawn[s];
		}
	}

	/** Cubes the last update found out of date (bench). */
	public int lastInvalidated()
	{
		return lastInvalidated;
	}

	public int lastChangedZones()
	{
		return lastZones;
	}

	/** Lamps waiting for their cube after the last update (bench). */
	public int lastPending()
	{
		return lastPending;
	}

	// a slot not used this frame: never used first, else the longest unused
	private int freeSlot()
	{
		int best = -1;
		long oldest = Long.MAX_VALUE;
		for (int s = 0; s < SLOTS; ++s)
		{
			// one in use this frame, or still crossed over from
			if (used[s] == frame || retiredUntil[s] > now)
			{
				continue;
			}
			if (used[s] < oldest)
			{
				oldest = used[s];
				best = s;
			}
		}
		return best;
	}

	private void draw(LuminaPlugin plugin, LuminaPlugin.SceneContext ctx, int[] toDraw, int draws)
	{
		boolean zeroToOne = plugin.glCapabilities.OpenGL45;
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		glDepthMask(true);
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glEnable(GL_POLYGON_OFFSET_FILL);
		glPolygonOffset(1.0f, 1.0f);
		glEnable(GL_SCISSOR_TEST);
		glUseProgram(plugin.glShadowProgram);
		glUniform1i(plugin.uniShadowTexturesReady, plugin.textureArrayId != -1 ? 1 : 0);
		// flames and other emissive models don't stop their own light
		glUniform1i(plugin.uniShadowEmissiveCasts, 0);
		glUniformMatrix4fv(plugin.uniShadowEntityProj, false, LuminaPlugin.IDENTITY);
		final int savedBase = LuminaPlugin.uniBase;
		LuminaPlugin.uniBase = plugin.uniShadowBase;
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		try
		{
			for (int d = 0; d < draws; ++d)
			{
				int s = toDraw[d];
				float x = drawnFor[s * 4], y = drawnFor[s * 4 + 1], z = drawnFor[s * 4 + 2], r = drawnFor[s * 4 + 3];
				int col = s % COLS, row = s / COLS;
				for (int f = 0; f < 6; ++f)
				{
					int vx = (col * 3 + f % 3) * FACE, vy = (row * 2 + f / 3) * FACE;
					glViewport(vx, vy, FACE, FACE);
					glScissor(vx, vy, FACE, FACE);
					glClear(GL_DEPTH_BUFFER_BIT);
					faceProj(FACES[f], x, y, z, NEAR, Math.max(r, NEAR * 2), zeroToOne, lightProj);
					glUniformMatrix4fv(plugin.uniShadowLightProj, false, lightProj);
					for (int zx = (int) Math.floor((x - r) / 1024) + offset; zx <= (int) Math.floor((x + r) / 1024) + offset; ++zx)
					{
						for (int zz = (int) Math.floor((z - r) / 1024) + offset; zz <= (int) Math.floor((z + r) / 1024) + offset; ++zz)
						{
							if (zx < 0 || zz < 0 || zx >= ctx.sizeX || zz >= ctx.sizeZ)
							{
								continue;
							}
							Zone zone = ctx.zones[zx][zz];
							if (zone.drawable() && zone.sizeO > 0)
							{
								// what the frame draws: its levels, and the roofs the client shows
								zone.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);
							}
						}
					}
				}
			}
		}
		finally
		{
			LuminaPlugin.uniBase = savedBase;
			glUniform1i(plugin.uniShadowEmissiveCasts, plugin.config.emissiveStrength() > 0 ? 0 : 1);
		}
		glBindVertexArray(0);
		glDisable(GL_SCISSOR_TEST);
		glDisable(GL_POLYGON_OFFSET_FILL);
		glDepthFunc(GL_GREATER);
		glDisable(GL_DEPTH_TEST);
		glUseProgram(0);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	/**
	 * World to clip for a face (forward, right, up) from (x, y, z): x' = right . v / TAN_HALF, y' = up . v / TAN_HALF, the
	 * depth forward . v between near and far, column-major.
	 */
	static void faceProj(float[] f, float x, float y, float z, float near, float far, boolean zeroToOne, float[] m)
	{
		float a, b;
		if (zeroToOne)
		{
			a = far / (far - near);
			b = -far * near / (far - near);
		}
		else
		{
			a = (far + near) / (far - near);
			b = -2 * far * near / (far - near);
		}
		float s = 1 / TAN_HALF;
		float[] rows = new float[16];
		// view rows: right, up, forward, each with its translation
		float[][] axes = {{f[3], f[4], f[5]}, {f[6], f[7], f[8]}, {f[0], f[1], f[2]}};
		float[] t = new float[3];
		for (int i = 0; i < 3; ++i)
		{
			t[i] = -(axes[i][0] * x + axes[i][1] * y + axes[i][2] * z);
		}
		for (int c = 0; c < 3; ++c)
		{
			rows[c] = s * axes[0][c];
			rows[4 + c] = s * axes[1][c];
			rows[8 + c] = a * axes[2][c];
			rows[12 + c] = axes[2][c];
		}
		rows[3] = s * t[0];
		rows[7] = s * t[1];
		rows[11] = a * t[2] + b;
		rows[15] = t[2];
		for (int r = 0; r < 4; ++r)
		{
			for (int c = 0; c < 4; ++c)
			{
				m[c * 4 + r] = rows[r * 4 + c];
			}
		}
	}
}
