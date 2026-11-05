package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import com.midonarhel.lumina.visibility.WallMap;
import java.util.Arrays;
import java.util.Set;
import net.runelite.api.WorldView;

/**
 * What stops light, for the scorecard's oracle: the triangles of the dump's own zones that the frame draws solid, read
 * straight from the vertices the client uploaded (ZoneSnapshot.vertsO), nothing derived by any lamp method. The ranges
 * are Zone.pushLevels's: the levels drawn, and above the player's level the roofs not hidden. Left out as the shadow
 * cast of Zone.renderOpaque and shadow_frag.glsl leave them out: emissive faces (a flame doesn't stop its own light).
 * Transparent faces (the alpha stream) and animated models (the frame tape) stop nothing. Each triangle has a category,
 * only for saying what a leak crossed.
 */
final class CardMesh
{
	static final int WALL = 1, ROOF = 2, TERRAIN = 4, FLOOR = 8, MODEL = 16, FOLIAGE = 32, HIDDEN = 64;
	// what rule 4 names: walls, terrain, roofs, and the floors between storeys
	static final int RULE = WALL | ROOF | TERRAIN | FLOOR | HIDDEN;
	static final String[] NAMES = {"wall", "roof", "terrain", "floor", "model", "foliage", "hidden storey"};
	private static final int VERT_INTS = 6, TRI_INTS = VERT_INTS * 3;

	float[] v = new float[9 << 18];
	byte[] cat = new byte[1 << 18];
	// per triangle its texture id where that has holes, else -1; its uv (6 floats a triangle) in uv
	int[] tex = new int[1 << 18];
	float[] uv = new float[6 << 18];
	int tris;
	final int[] byCategory = new int[7];
	private int[][] pixels;
	private int[] texSize;

	static CardMesh build(SceneDump.Loaded dump, int minLevel, int level, int maxLevel, Set<Integer> hidden, int[][][] heights, WallMap walls)
	{
		CardMesh m = new CardMesh();
		m.textures(dump);
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		for (int w = 0; w < dump.views.size(); ++w)
		{
			if (dump.views.get(w)[0] != WorldView.TOPLEVEL)
			{
				continue;
			}
			for (ZoneSnapshot z : dump.zones.get(w))
			{
				if (z.vertsO != null)
				{
					m.zone(z, (z.x - offset) << 10, (z.z - offset) << 10, minLevel, level, maxLevel, hidden, heights, walls);
				}
			}
		}
		m.trim();
		return m;
	}

	private void textures(SceneDump.Loaded dump)
	{
		int[][] p = dump.texturePixels;
		if (p == null)
		{
			pixels = new int[0][];
			texSize = new int[0];
			return;
		}
		pixels = new int[p.length][];
		texSize = new int[p.length];
		for (int i = 0; i < p.length; ++i)
		{
			if (p[i] == null)
			{
				continue;
			}
			for (int c : p[i])
			{
				if (c == 0)
				{
					pixels[i] = p[i];
					texSize[i] = (int) Math.round(Math.sqrt(p[i].length));
					break;
				}
			}
		}
	}

	private void zone(ZoneSnapshot z, int baseX, int baseZ, int minLevel, int level, int maxLevel, Set<Integer> hidden, int[][][] heights, WallMap walls)
	{
		int count = z.vertsO.length / TRI_INTS;
		byte[] shown = new byte[count]; // 0 not drawn, 1 drawn, 2 drawn and a roof's
		for (int l = minLevel; l <= maxLevel; ++l)
		{
			int[] rids = z.rids[l], roofStart = z.roofStart[l], roofEnd = z.roofEnd[l];
			int start = l == 0 ? 0 : z.levelOffsets[l - 1], end = z.levelOffsets[l];
			if (rids.length == 0 || hidden.isEmpty() || l <= level)
			{
				mark(shown, start, end, (byte) 1);
			}
			else
			{
				int endpos = start;
				for (int i = rids.length - 1; i >= 0; --i)
				{
					if (rids[i] > 0)
					{
						endpos = roofEnd[i];
						break;
					}
				}
				mark(shown, endpos, end, (byte) 1);
			}
			for (int i = 0; i < rids.length; ++i)
			{
				if (rids[i] > 0 && (rids.length > 0 && (hidden.isEmpty() || l <= level) || !hidden.contains(rids[i])))
				{
					mark(shown, roofStart[i], roofEnd[i], (byte) 2);
				}
			}
			int from = start / TRI_INTS, to = Math.min(count, end / TRI_INTS);
			for (int t = from; t < to; ++t)
			{
				if (shown[t] != 0)
				{
					add(z.vertsO, t * TRI_INTS, baseX, baseZ, l, shown[t] == 2, heights, walls);
				}
			}
		}
	}

	private static void mark(byte[] shown, int fromInts, int toInts, byte value)
	{
		Arrays.fill(shown, Math.min(shown.length, fromInts / TRI_INTS), Math.min(shown.length, toInts / TRI_INTS), value);
	}

	private void add(int[] s, int i, int baseX, int baseZ, int level, boolean roof, int[][][] heights, WallMap walls)
	{
		int tw = s[i + 3];
		int texWord = (short) tw;
		if ((texWord >> 9 & 3) != 0)
		{
			return;
		}
		boolean foliage = (texWord >> 11 & 1) != 0;
		int textureId = texWord & 0x1ff;
		float[] p = new float[9];
		for (int k = 0; k < 3; ++k)
		{
			int w0 = s[i + k * VERT_INTS], w1 = s[i + k * VERT_INTS + 1];
			p[k * 3] = (short) w0 + baseX;
			p[k * 3 + 1] = (short) (w0 >> 16);
			p[k * 3 + 2] = (short) w1 + baseZ;
		}
		float e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2], e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
		float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (len < 1e-3f)
		{
			return;
		}
		if (tris == cat.length)
		{
			int n = tris * 2;
			v = Arrays.copyOf(v, n * 9);
			cat = Arrays.copyOf(cat, n);
			tex = Arrays.copyOf(tex, n);
			uv = Arrays.copyOf(uv, n * 6);
		}
		System.arraycopy(p, 0, v, tris * 9, 9);
		int category = category(p, Math.abs(ny) / len, level, roof, foliage, heights, walls);
		cat[tris] = (byte) category;
		++byCategory[Integer.numberOfTrailingZeros(category)];
		boolean holes = textureId > 0 && textureId - 1 < pixels.length && pixels[textureId - 1] != null;
		tex[tris] = holes ? textureId - 1 : -1;
		if (holes)
		{
			for (int k = 0; k < 3; ++k)
			{
				int a = s[i + k * VERT_INTS + 3], b = s[i + k * VERT_INTS + 4];
				uv[tris * 6 + k * 2] = (short) (a >> 16) / 256f;
				uv[tris * 6 + k * 2 + 1] = (short) b / 256f;
			}
		}
		++tris;
	}

	private static int category(float[] p, float up, int level, boolean roof, boolean foliage, int[][][] heights, WallMap walls)
	{
		if (foliage)
		{
			return FOLIAGE;
		}
		if (roof)
		{
			return ROOF;
		}
		if (level == 0 && onGround(p, heights))
		{
			return TERRAIN;
		}
		float cx = (p[0] + p[3] + p[6]) / 3, cz = (p[2] + p[5] + p[8]) / 3;
		if (up < 0.3f && walls != null && nearWall(walls, level, cx, cz))
		{
			return WALL;
		}
		return up > 0.7f && level >= 1 ? FLOOR : MODEL;
	}

	// all three corners on the tile heights of level 0
	private static boolean onGround(float[] p, int[][][] heights)
	{
		if (heights == null)
		{
			return false;
		}
		for (int k = 0; k < 3; ++k)
		{
			float h = height(heights[0], p[k * 3], p[k * 3 + 2]);
			if (Float.isNaN(h) || Math.abs(h - p[k * 3 + 1]) > 3)
			{
				return false;
			}
		}
		return true;
	}

	static float height(int[][] h, float x, float z)
	{
		float fx = x / 128 + LuminaPlugin.SCENE_OFFSET, fz = z / 128 + LuminaPlugin.SCENE_OFFSET;
		int tx = (int) Math.floor(fx), tz = (int) Math.floor(fz);
		if (tx < 0 || tz < 0 || tx + 1 >= h.length || tz + 1 >= h[0].length)
		{
			return Float.NaN;
		}
		fx -= tx;
		fz -= tz;
		float a = h[tx][tz] + (h[tx + 1][tz] - h[tx][tz]) * fx, b = h[tx][tz + 1] + (h[tx + 1][tz + 1] - h[tx][tz + 1]) * fx;
		return a + (b - a) * fz;
	}

	private static boolean nearWall(WallMap walls, int level, float x, float z)
	{
		int plane = Math.min(level, WallMap.PLANES - 1);
		int tx = (int) Math.floor(x / 128) + walls.offset, tz = (int) Math.floor(z / 128) + walls.offset;
		if (tx < 1 || tz < 1 || tx + 1 >= walls.size || tz + 1 >= walls.size)
		{
			return false;
		}
		float fx = x / 128 + walls.offset - tx, fz = z / 128 + walls.offset - tz;
		float near = 40 / 128f;
		return fx < near && walls.edge(plane, tx, tz, WallMap.WEST) || fx > 1 - near && walls.edge(plane, tx, tz, WallMap.EAST)
			|| fz < near && walls.edge(plane, tx, tz, WallMap.SOUTH) || fz > 1 - near && walls.edge(plane, tx, tz, WallMap.NORTH)
			|| (walls.get(plane, tx, tz) & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE)) != 0;
	}

	private void trim()
	{
		v = Arrays.copyOf(v, tris * 9);
		cat = Arrays.copyOf(cat, tris);
		tex = Arrays.copyOf(tex, tris);
	}

	/** Whether the texture of triangle tri is solid at barycentrics (u, w), as shadow_frag.glsl's alpha test has it. */
	boolean solidAt(int tri, float u, float w)
	{
		int id = tex[tri], size = texSize[id];
		int a = tri * 6;
		float t = 1 - u - w;
		float tu = uv[a] * t + uv[a + 2] * u + uv[a + 4] * w, tv = uv[a + 1] * t + uv[a + 3] * u + uv[a + 5] * w;
		// the texture array: clamped along u, wrapped along v, nearest
		int px = Math.max(0, Math.min(size - 1, (int) Math.floor(tu * size)));
		int py = Math.floorMod((int) Math.floor(tv * size), size);
		return pixels[id][py * size + px] != 0;
	}

	/** Triangle tri's unit normal into n. */
	void normal(int tri, float[] n)
	{
		int a = tri * 9;
		float e1x = v[a + 3] - v[a], e1y = v[a + 4] - v[a + 1], e1z = v[a + 5] - v[a + 2];
		float e2x = v[a + 6] - v[a], e2y = v[a + 7] - v[a + 1], e2z = v[a + 8] - v[a + 2];
		float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
		float l = (float) (1 / Math.sqrt(nx * nx + ny * ny + nz * nz));
		n[0] = nx * l;
		n[1] = ny * l;
		n[2] = nz * l;
	}
}
