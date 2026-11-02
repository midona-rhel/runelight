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
	private final float[] lightProj = new float[16];
	private long frame;
	// what was drawn: the scene, its levels and hidden roofs
	private Object drawnScene;
	private int drawnMin = -1, drawnLevel = -1, drawnMax = -1;
	private Set<Integer> drawnRoofs = new HashSet<>();
	private int drawnLast;
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
		if (drawnScene != ctx || drawnMin != ctx.minLevel || drawnLevel != ctx.level || drawnMax != ctx.maxLevel
			|| !Objects.equals(drawnRoofs, ctx.hideRoofIds))
		{
			drawnScene = ctx;
			drawnMin = ctx.minLevel;
			drawnLevel = ctx.level;
			drawnMax = ctx.maxLevel;
			drawnRoofs = new HashSet<>(ctx.hideRoofIds);
			Arrays.fill(valid, false);
		}
		if (REDRAW)
		{
			Arrays.fill(valid, false);
		}

		slots.clear();
		int[] toDraw = new int[SLOTS];
		int draws = 0;
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
				toDraw[draws++] = s;
				valid[s] = true;
			}
			slots.put(s);
		}
		slots.flip();
		glBindTexture(GL_TEXTURE_2D, table);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, Math.max(1, slots.limit()), 1, GL_RED, GL_FLOAT, slots);
		glBindTexture(GL_TEXTURE_2D, 0);
		drawnLast = draws * 6;
		// a full redraw (a scene, a roof hidden or shown), not a lamp that moves
		if (draws > SLOTS / 4 && (!REDRAW || frame % 100 == 1))
		{
			log.info("Lamp cubes: {} lamps drawn ({} faces), {} of the frame's {} lamps with a cube", draws, draws * 6,
				Math.min(count, SLOTS), count);
		}
		if (draws > 0)
		{
			draw(plugin, ctx, toDraw, draws);
		}
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
