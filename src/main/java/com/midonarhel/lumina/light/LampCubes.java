package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.scene.Zone;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The lamps' shadow cubes: each lamp's depth seen from it on the six faces of a cube, drawn from what the frame draws
 * (its levels and the roofs the client shows: a roof it hides stops nothing), into one depth atlas the fog's glow reads
 * (fog_frag.glsl, lampCubeOpen). A lamp's faces are drawn when it first takes a slot, moves, or what is drawn changes
 * (the levels, the hidden roofs, the scene); the slot a frame's lamp index has is in a table beside the atlas, -1 for a
 * lamp without one (more lamps than SLOTS: the walls stop those). Static scenery only: no players or NPCs.
 */
public final class LampCubes
{
	private static final Logger log = LoggerFactory.getLogger(LampCubes.class);

	// a face's texels, the atlas's slots (COLS x ROWS, each three faces wide and two high)
	public static final int FACE = 128, COLS = 10, ROWS = 8, SLOTS = COLS * ROWS;
	public static final int WIDTH = FACE * 3 * COLS, HEIGHT = FACE * 2 * ROWS;
	// texels round each face past its 90 degrees, so a filtered read near a face's edge stays on the face
	public static final int GUARD = 4;
	public static final float TAN_HALF = FACE / (float) (FACE - 2 * GUARD);
	// nothing nearer the lamp than this casts (its own bracket, the flame's model if it isn't emissive)
	public static final float NEAR = 24;
	// the most lamps drawn a frame (each about 0.1 ms of the GPU's and of the CPU's, fps1): after a roof is hidden or
	// shown, or a scene loads, the cubes come back over a few frames, the nearest first; until its cube is drawn a
	// lamp's glow is stopped by its walls and the drawn roofs (fog_frag.glsl), which already know the change
	public static final int LAMPS_PER_FRAME = 12;

	// each face's forward, right and up (fog_frag.glsl picks the face and its axes the same way)
	private static final float[][] FACES = {
		{1, 0, 0, 0, 0, 1, 0, 1, 0}, {-1, 0, 0, 0, 0, 1, 0, 1, 0},
		{0, 1, 0, 1, 0, 0, 0, 0, 1}, {0, -1, 0, 1, 0, 0, 0, 0, 1},
		{0, 0, 1, 1, 0, 0, 0, 1, 0}, {0, 0, -1, 1, 0, 0, 0, 1, 0},
	};

	private int fbo, depth, table;
	// per slot: the lamp's place and reach it was drawn for (x, y, z, radius), the frame it was last used, drawn yet
	private final float[] drawnFor = new float[SLOTS * 4];
	private final long[] used = new long[SLOTS];
	private final boolean[] valid = new boolean[SLOTS];
	private final Map<Long, Integer> slotOf = new HashMap<>();
	private final long[] keyOf = new long[SLOTS];
	private final FloatBuffer slots = BufferUtils.createFloatBuffer(LightClusters.MAX_LIGHTS);
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
	private static final boolean OFF = Boolean.getBoolean("lumina.noLampCubes");
	private static final boolean REDRAW = Boolean.getBoolean("lumina.lampCubesRedraw");

	public boolean isInitialized()
	{
		return fbo != 0;
	}

	public int atlas()
	{
		return depth;
	}

	public int table()
	{
		return table;
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
		table = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, table);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, LightClusters.MAX_LIGHTS, 1, 0, GL_RED, GL_FLOAT, (FloatBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	public void free()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(depth);
			glDeleteTextures(table);
			fbo = depth = table = 0;
		}
		slotOf.clear();
		Arrays.fill(valid, false);
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
	 * Give the frame's lamps (LightClusters' order) their slots, draw the faces of the ones that need it, and send the
	 * table. After the frame's scene draw (its zones up to date); the shadow program and its uniforms are the sun's.
	 */
	public void update(LuminaPlugin plugin, PointLight[] lamps, int count)
	{
		LuminaPlugin.SceneContext ctx = plugin.root;
		if (OFF || ctx == null || ctx.hideRoofIds == null || plugin.glShadowProgram == 0)
		{
			return;
		}
		if (fbo == 0)
		{
			init();
		}
		++frame;
		// what is drawn changed: every slot drawn again
		lastInvalidated = 0;
		int renewed = renew(ctx, sceneRoofs(plugin, ctx));
		lastZones = renewed;
		if (REDRAW)
		{
			Arrays.fill(valid, false);
		}

		slots.clear();
		int pending = 0;
		for (int i = 0; i < count; ++i)
		{
			PointLight l = lamps[i];
			long k = key(l);
			Integer s = slotOf.get(k);
			if (s == null)
			{
				s = freeSlot();
				if (s < 0)
				{
					slots.put(-1);
					continue;
				}
				if (keyOf[s] != 0)
				{
					slotOf.remove(keyOf[s]);
				}
				keyOf[s] = k;
				slotOf.put(k, s);
				valid[s] = false;
			}
			used[s] = frame;
			if (!valid[s])
			{
				drawnFor[s * 4] = l.x;
				drawnFor[s * 4 + 1] = l.y;
				drawnFor[s * 4 + 2] = l.z;
				drawnFor[s * 4 + 3] = Math.abs(l.radius);
				// its distance from the camera and its index, to draw the nearest first
				float dx = l.x - plugin.frameCameraX, dy = l.y - plugin.frameCameraY, dz = l.z - plugin.frameCameraZ;
				waiting[pending++] = (long) Float.floatToIntBits(dx * dx + dy * dy + dz * dz) << 32 | i;
				slots.put(-1);
				continue;
			}
			slots.put(s);
		}
		slots.flip();
		Arrays.sort(waiting, 0, pending);
		int draws = Math.min(pending, LAMPS_PER_FRAME);
		int[] toDraw = new int[draws];
		for (int d = 0; d < draws; ++d)
		{
			int i = (int) waiting[d];
			int s = slotOf.get(key(lamps[i]));
			toDraw[d] = s;
			valid[s] = true;
			slots.put(i, s);
		}
		glBindTexture(GL_TEXTURE_2D, table);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, Math.max(1, slots.limit()), 1, GL_RED, GL_FLOAT, slots);
		glBindTexture(GL_TEXTURE_2D, 0);
		drawnLast = draws * 6;
		// a full redraw (a scene, a roof hidden or shown), not a lamp that moves
		if (renewed > 0 || REDRAW && frame % 100 == 1)
		{
			log.info("Lamp cubes: {} changed zones, {} lamps to draw, {} a frame, nearest first; {} of the frame's {} lamps"
				+ " with a cube", renewed, pending, LAMPS_PER_FRAME, Math.min(count, SLOTS), count);
		}
		lastPending = pending;
		if (draws > 0)
		{
			draw(plugin, ctx, toDraw, draws);
		}
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
			Arrays.fill(valid, false);
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
			if (!valid[s])
			{
				continue;
			}
			float x = drawnFor[s * 4], z = drawnFor[s * 4 + 2], r = drawnFor[s * 4 + 3];
			if (tiles > 0 && reachesTile(x, z, r))
			{
				valid[s] = false;
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
						valid[s] = false;
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
		Arrays.fill(valid, false);
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
			if (used[s] == frame)
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
