package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.common.Half;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPInputStream;
import org.junit.Assume;
import org.junit.Test;

/**
 * Replays a `::runelight skydump` (SkyGridDump): reads the sky grid's inputs, traces them again with the current
 * SkyGrid, and evaluates skylight_ao.glsl's skylightVisibility for the ground around the player on the CPU, from the
 * dumped texels (read as the shader before SkyGrid.roofShares read them: the market dump is from before it) and from
 * the new trace, printed as maps with how the ground steps across tile edges, for every skygrid-*.bin.gz in
 * ~/.runelite/lumina/dumps (none there: it does nothing). Shader is a copy of the GLSL: change
 * both together.
 */
public class SkyGridReplay
{
	static final class Dump
	{
		float playerX, playerZ;
		int resolution;
		SceneVoxels voxels;
		WallMap walls;
		RoomMap rooms;
		byte[] floors;
		short[] texels;
	}

	static Dump read(Path file) throws Exception
	{
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)))))
		{
			Dump d = new Dump();
			if (in.readInt() != SkyGridDump.VERSION) throw new IOException("dump version");
			d.playerX = in.readFloat();
			d.playerZ = in.readFloat();
			d.resolution = in.readInt();
			int baseX = in.readInt(), baseY = in.readInt();
			boolean instance = in.readBoolean();
			int[][][] heights = readHeights(in);
			VoxelGrid g = new VoxelGrid(in.readInt(), in.readInt(), in.readInt(), in.readInt());
			for (int i = 0; i < g.cells.length; ++i) g.cells[i] = in.readInt();
			for (int i = 0; i < g.top.length; ++i) g.top[i] = in.readFloat();
			d.voxels = new SceneVoxels(g, baseX, baseY, instance, heights);
			if (in.readBoolean())
			{
				SkyTerrain t = new SkyTerrain();
				for (int i = 0; i < t.triangles.length; ++i)
				{
					int n = in.readInt();
					if (n < 0) continue;
					int[] a = new int[n];
					for (int k = 0; k < n; ++k) a[k] = in.readInt();
					t.triangles[i] = a;
				}
				d.voxels.terrain = t;
			}
			if (in.readBoolean())
			{
				int size = in.readInt(), offset = in.readInt(), scene = in.readInt();
				WallMap w = WallMap.empty(size, offset, scene);
				byte[] bits = (byte[]) field(WallMap.class, "bits").get(w);
				boolean[] full = (boolean[]) field(WallMap.class, "full").get(w);
				byte[] see = (byte[]) field(WallMap.class, "see").get(w);
				in.readFully(bits);
				for (int i = 0; i < full.length; ++i) full[i] = in.readBoolean();
				in.readFully(see);
				d.walls = w;
			}
			if (in.readBoolean())
			{
				Constructor<RoomMap> c = RoomMap.class.getDeclaredConstructor(int.class, int.class);
				c.setAccessible(true);
				RoomMap r = c.newInstance(in.readInt(), in.readInt());
				for (int i = 0; i < r.floors.length; ++i) r.floors[i] = in.readFloat();
				for (int i = 0; i < r.roofed.length; ++i) r.roofed[i] = in.readBoolean();
				d.rooms = r;
			}
			int n = in.readInt();
			if (n >= 0)
			{
				d.floors = new byte[n];
				in.readFully(d.floors);
			}
			d.texels = new short[in.readInt()];
			for (int i = 0; i < d.texels.length; ++i) d.texels[i] = in.readShort();
			return d;
		}
	}

	private static Field field(Class<?> c, String name) throws Exception
	{
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static int[][][] readHeights(DataInputStream in) throws IOException
	{
		int levels = in.readInt();
		if (levels < 0) return null;
		int[][][] h = new int[levels][][];
		for (int l = 0; l < levels; ++l)
		{
			h[l] = new int[in.readInt()][];
			for (int x = 0; x < h[l].length; ++x)
			{
				h[l][x] = new int[in.readInt()];
				for (int z = 0; z < h[l][x].length; ++z) h[l][x][z] = in.readInt();
			}
		}
		return h;
	}

	/** The dump's inputs traced again by the current SkyGrid, the way SkyGridMap's worker hands them over. */
	static SkyGrid retrace(Dump d)
	{
		SkyGrid s = new SkyGrid(d.resolution);
		s.setWalls(d.walls);
		s.setGrid(d.voxels.grid, d.voxels.heights, com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET, d.voxels.baseX,
			d.voxels.baseY, d.voxels.instance, d.floors);
		s.terrain = d.voxels.terrain;
		s.setRooms(d.rooms);
		while (s.dirtyCount() > 0)
		{
			s.trace(1 << 22, d.playerX, d.playerZ);
		}
		return s;
	}

	/** skylight_ao.glsl's skylightVisibility on the CPU, over one grid's texels and the sky field. */
	static final class Shader
	{
		static final float SKY_GRID_LOW = 40, SKY_GRID_STEP = 72, SKY_NORMAL_OFFSET = 1, SKY_EDGE_REACH = 32,
			SKY_SQUARE = 0.9f, SKY_OUTDOOR_STRENGTH = 0.35f, SKY_BEHIND = 0.02f, SKY_TOP_SLACK = 8, SKY_TOP_FADE = 16;
		final short[] tex;
		final int size, width, height, scale;
		final float[] field;
		final int fieldWidth, fieldOffset;
		// the shader before SkyGrid.roofShares (its texels: slice 0's part 3 holds the tile's roof bits, read per
		// tile), which also blended cover in two steps
		boolean before;
		// the tile roof bits the shader read before roofShares, [z * size + x], from the dump's rooms
		int[] tileRoofs;
		// the last skylight()'s terms, for the breakdown
		float lastOriginal, lastSurface, lastCover, lastField;
		float skyExterior;

		Shader(short[] tex, int resolution, float[] field, WallMap walls)
		{
			this.tex = tex;
			this.scale = resolution;
			size = SkyGrid.SIZE * resolution;
			width = 8 * size;
			height = 2 * size;
			this.field = field;
			fieldWidth = walls.size * SkyField.TEXELS_PER_TILE;
			fieldOffset = walls.offset;
		}

		float t(int x, int y, int slice, int c)
		{
			return Half.fromHalf(tex[((slice * height + y) * width + x) * 4 + c]);
		}

		/** Slice 0 read bilinearly between a tile's 2 x 2 texels, part's x offset in texels. */
		float meta(int tx, int tz, float fx, float fz, int part, int c)
		{
			int x = 2 * tx + part, y = 2 * tz;
			float a = t(x, y, 0, c), b = t(x + 1, y, 0, c), cc = t(x, y + 1, 0, c), d = t(x + 1, y + 1, 0, c);
			return (a + (b - a) * fx) * (1 - fz) + (cc + (d - cc) * fx) * fz;
		}

		static float smoothstep(float a, float b, float v)
		{
			float t = Math.max(0, Math.min(1, (v - a) / (b - a)));
			return t * t * (3 - 2 * t);
		}

		float[] weights(int diagonal, float fx, float fz)
		{
			boolean north = diagonal == 1 ? fz > fx : fx + fz > 1;
			if (diagonal == 0) return new float[]{(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz};
			if (diagonal == 1) return north ? new float[]{1 - fz, 0, fz - fx, fx} : new float[]{1 - fx, fx - fz, 0, fz};
			return north ? new float[]{0, 1 - fz, 1 - fx, fx + fz - 1} : new float[]{1 - fx - fz, fx, fz, 0};
		}

		float level(int tx, int tz, float fx, float fz, int level, int diagonal, float[] n, float[] w)
		{
			boolean north = diagonal == 1 ? fz > fx : fx + fz > 1;
			int half = diagonal != 0 && !north ? 4 * size : 0;
			float[] wt = weights(diagonal, fx, fz);
			float[] a = new float[4];
			float minusZ = 0, valid = 0;
			for (int k = 0; k < 4; ++k)
			{
				if (wt[k] <= 0) continue;
				int x = half + 2 * tx + (k & 1), y = 2 * tz + (k >> 1), s = 1 + level;
				if (t(x, y, s, 0) < 0) continue;
				float sx = (tx + (k & 1)) * (128f / scale) + t(x + 2 * size, y, s, 1), sy = t(x + 2 * size, y, s, 2),
					sz = (tz + (k >> 1)) * (128f / scale) + t(x + 2 * size, y, s, 3);
				float weight = wt[k] * (SKY_BEHIND + smoothstep(-16, 32, (sx - w[0]) * n[0] + (sy - w[1]) * n[1] + (sz - w[2]) * n[2]));
				valid += weight;
				for (int c = 0; c < 4; ++c) a[c] += t(x, y, s, c) * weight;
				minusZ += t(x + 2 * size, y, s, 0) * weight;
			}
			if (valid <= 0.00001f) return -1;
			for (int c = 0; c < 4; ++c) a[c] /= valid;
			minusZ /= valid;
			float down = (a[1] + a[2] + a[3] + minusZ) * 0.25f;
			return n[1] * n[1] * (n[1] < 0 ? a[0] : down) + n[0] * n[0] * (n[0] > 0 ? a[1] : a[2])
				+ n[2] * n[2] * (n[2] > 0 ? a[3] : minusZ);
		}

		float[] surface(int tx, int tz, float fx, float fz, int level, int diagonal, float[] w, float[] n)
		{
			boolean north = diagonal == 1 ? fz > fx : fx + fz > 1;
			int half = diagonal != 0 && !north ? 4 * size : 0;
			float[] wt = weights(diagonal, fx, fz);
			float[] sum = new float[2];
			for (int k = 0; k < 4; ++k)
			{
				if (wt[k] <= 0) continue;
				int x = half + 2 * tx + (k & 1), y = 2 * tz + (k >> 1), s = 1 + level;
				float a0 = t(x, y, s, 0);
				if (a0 < 0) continue;
				float sx = (tx + (k & 1)) * (128f / scale) + t(x + 2 * size, y, s, 1), sy = t(x + 2 * size, y, s, 2),
					sz = (tz + (k >> 1)) * (128f / scale) + t(x + 2 * size, y, s, 3);
				float weight = wt[k] * smoothstep(-16, 32, (sx - w[0]) * n[0] + (sy - w[1]) * n[1] + (sz - w[2]) * n[2]);
				weight *= 1 - smoothstep(48, 128, sy - w[1]);
				float mz = t(x + 2 * size, y, s, 0);
				float value = n[1] * n[1] * (n[1] < 0 ? a0 : (t(x, y, s, 1) + t(x, y, s, 2) + t(x, y, s, 3) + mz) * 0.25f)
					+ n[0] * n[0] * (n[0] > 0 ? t(x, y, s, 1) : t(x, y, s, 2)) + n[2] * n[2] * (n[2] > 0 ? t(x, y, s, 3) : mz);
				sum[0] += value * weight;
				sum[1] += weight;
			}
			return sum;
		}

		static int diag(int code, int level)
		{
			return code / (level / 3 == 0 ? 1 : level / 3 == 1 ? 3 : 9) % 3;
		}

		float grid(float[] w, float[] n)
		{
			skyExterior = 1;
			float txf = w[0] * scale / 128, tzf = w[2] * scale / 128;
			if (txf < 0 || tzf < 0 || txf >= size || tzf >= size) return 1;
			int tx = (int) Math.floor(txf), tz = (int) Math.floor(tzf);
			float fx = txf - tx, fz = tzf - tz;
			float[] meta = {meta(tx, tz, fx, fz, 0, 0), meta(tx, tz, fx, fz, 0, 1), meta(tx, tz, fx, fz, 0, 2), meta(tx, tz, fx, fz, 0, 3)};
			int floorMask = (int) (meta(tx, tz, fx, fz, 2 * size, 0) + 0.5f);
			int first = 0, last = 8;
			for (int f = 1; f < 3; ++f)
			{
				if ((floorMask & (1 << f)) == 0) continue;
				if (w[1] < meta[f]) first = f * 3;
				else last = Math.min(last, f * 3 - 1);
			}
			int lo = first, hi = first;
			float loY = meta[first / 3] - SKY_GRID_LOW, hiY = loY;
			for (int k = first + 1; k <= last; ++k)
			{
				if (w[1] >= hiY) break;
				lo = hi;
				loY = hiY;
				hi = k;
				hiY = meta[k / 3] - SKY_GRID_LOW - (k % 3) * SKY_GRID_STEP;
			}
			float blend = lo == hi ? 0 : Math.max(0, Math.min(1, (loY - w[1]) / Math.max(loY - hiY, 1)));
			int code = (int) (meta[3] + 0.5f);
			int loD = diag(code, lo), hiD = diag(code, hi);
			float[] roomFloors = {meta(tx, tz, fx, fz, 4 * size, 0), meta(tx, tz, fx, fz, 4 * size, 1), meta(tx, tz, fx, fz, 4 * size, 2), meta(tx, tz, fx, fz, 4 * size, 3)};
			int roomPlane = 0;
			for (int k = 1; k < 4; ++k) if (w[1] <= roomFloors[k]) roomPlane = k;
			float exterior = before ? ((tileRoofs[tz * size + tx] & (1 << roomPlane)) == 0 ? 1 : 0)
				: 1 - meta(tx, tz, fx, fz, 6 * size, roomPlane);
			if (exterior < 1 && n[1] < -0.3f)
			{
				float top = meta(tx, tz, fx, fz, 2 * size, 1);
				exterior += (1 - exterior) * (1 - smoothstep(top + SKY_TOP_SLACK, top + SKY_TOP_SLACK + SKY_TOP_FADE, w[1])) * smoothstep(0.3f, 0.7f, -n[1]);
			}
			float lower = level(tx, tz, fx, fz, lo, loD, n, w);
			float upper = level(tx, tz, fx, fz, hi, hiD, n, w);
			float original = lower < 0 ? upper : upper < 0 ? lower : lower + (upper - lower) * blend;
			if (original < 0)
			{
				float best = 1e30f;
				for (int k = first; k <= last; ++k)
				{
					float value = level(tx, tz, fx, fz, k, diag(code, k), n, w);
					float distance = Math.abs(w[1] - (meta[k / 3] - SKY_GRID_LOW - (k % 3) * SKY_GRID_STEP));
					if (value >= 0 && distance < best)
					{
						original = value;
						best = distance;
					}
				}
				if (original < 0) original = Math.max(0, level(tx, tz, fx, fz, 9, 0, n, w));
			}
			skyExterior = exterior;
			lastOriginal = original;
			lastSurface = -1;
			if (exterior > 0)
			{
				float[] a = surface(tx, tz, fx, fz, lo, loD, w, n), b = surface(tx, tz, fx, fz, hi, hiD, w, n);
				float tv = a[0] + (b[0] - a[0]) * blend, ty = a[1] + (b[1] - a[1]) * blend;
				float sw = 1 - ty;
				float[] top = surface(tx, tz, fx, fz, 9, 0, w, n);
				tv += top[0] * sw;
				ty += top[1] * sw;
				if (ty > 0.0001f)
				{
					lastSurface = tv / ty;
					float m = smoothstep(0, 0.5f, -n[1]) * exterior;
					return original + (tv / ty - original) * m;
				}
			}
			return original;
		}

		/** The field's light and cover at a local position (grid coordinates, tile 0 at 0). */
		float[] field(float x, float z)
		{
			float tx = Math.max(0, Math.min(fieldWidth / 2f - 0.0001f, x / 128 + fieldOffset));
			float tz = Math.max(0, Math.min(fieldWidth / 2f - 0.0001f, z / 128 + fieldOffset));
			int bx = (int) tx * 2, bz = (int) tz * 2;
			float fx = tx - (int) tx, fz = tz - (int) tz;
			float[] sw = texel(bx, bz), se = texel(bx + 1, bz), nw = texel(bx, bz + 1), ne = texel(bx + 1, bz + 1);
			int diagonal = (int) Math.floor(sw[1] * 0.5f);
			float code = 2f * diagonal;
			sw[1] -= code;
			se[1] -= code;
			nw[1] -= code;
			ne[1] -= code;
			float[] out = new float[2];
			for (int c = 0; c < 2; ++c)
			{
				float v = (sw[c] * (1 - fx) + se[c] * fx) * (1 - fz) + (nw[c] * (1 - fx) + ne[c] * fx) * fz;
				if (diagonal == 1)
					v = fz > fx ? sw[c] * (1 - fz) + nw[c] * (fz - fx) + ne[c] * fx
						: sw[c + 2] * (1 - fx) + se[c + 2] * (fx - fz) + ne[c + 2] * fz;
				else if (diagonal == 2)
					v = fx + fz > 1 ? nw[c] * (1 - fx) + ne[c] * (fx + fz - 1) + se[c] * (1 - fz)
						: sw[c + 2] * (1 - fx - fz) + se[c + 2] * fx + nw[c + 2] * fz;
				out[c] = v;
			}
			return out;
		}

		private float[] texel(int x, int y)
		{
			int o = (y * fieldWidth + x) * 4;
			return new float[]{field[o], field[o + 1], field[o + 2], field[o + 3]};
		}

		float skylight(float[] p, float[] n)
		{
			float[] o = {p[0] + n[0] * SKY_NORMAL_OFFSET, p[1] + n[1] * SKY_NORMAL_OFFSET, p[2] + n[2] * SKY_NORMAL_OFFSET};
			for (int axis = 0; axis < 2; ++axis)
			{
				float d = axis == 0 ? n[0] : n[2];
				float x = axis == 0 ? o[0] : o[2];
				float f = (float) (x / 128 - Math.floor(x / 128)) * 128;
				float step = 0;
				if (Math.abs(d) > SKY_SQUARE && n[1] < 0.1f)
				{
					float toEdge = d > 0 ? 128 - f : f;
					if (toEdge < SKY_EDGE_REACH) step = (toEdge + 1) * Math.signum(d);
				}
				o[axis == 0 ? 0 : 2] += step;
			}
			float[] fl = field(o[0], o[2]);
			float v = grid(o, n);
			float cover = fl[1] * (1 - skyExterior);
			lastCover = cover;
			lastField = fl[0];
			float outdoors = 1 - cover;
			if (before)
			{
				v = v + (1 - (1 - v) * SKY_OUTDOOR_STRENGTH - v) * outdoors;
				return v + (Math.max(v, fl[0]) - v) * cover;
			}
			float outside = 1 - (1 - v) * SKY_OUTDOOR_STRENGTH, inside = Math.max(v, fl[0]);
			return outside + (inside - outside) * cover;
		}
	}

	static float[] field(Dump d, int plane)
	{
		boolean[] covered = SkyField.covered(d.rooms, d.floors, plane);
		float[] light = SkyField.propagate(d.walls, covered, plane, SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH));
		return SkyField.pack(d.walls, plane, light, covered);
	}

	/** The ground's skylight over tiles around (cx, cz), sub samples per tile side, as rows of 0-9 (north up). */
	static String groundMap(Shader sh, SkyGrid ground, int cx, int cz, int radius, int sub)
	{
		StringBuilder out = new StringBuilder();
		float[] up = {0, -1, 0};
		for (int tz = cz + radius; tz >= cz - radius; --tz)
		{
			for (int j = sub - 1; j >= 0; --j)
			{
				out.append(String.format("%4d ", j == sub - 1 ? tz : 0).replace("   0 ", "     "));
				for (int tx = cx - radius; tx <= cx + radius; ++tx)
				{
					for (int i = 0; i < sub; ++i)
					{
						float x = (tx + (i + 0.5f) / sub) * 128, z = (tz + (j + 0.5f) / sub) * 128;
						float y = groundY(sh, x, z);
						float v = sh.skylight(new float[]{x, y, z}, up);
						out.append((char) ('0' + Math.max(0, Math.min(9, (int) (v * 10)))));
					}
					out.append(' ');
				}
				out.append('\n');
			}
		}
		return out.toString();
	}

	static String fineMap(Shader sh, int x0, int x1, int z0, int z1)
	{
		StringBuilder out = new StringBuilder();
		float[] up = {0, -1, 0};
		for (int tz = z1; tz >= z0; --tz)
		{
			for (int j = 1; j >= 0; --j)
			{
				out.append(j == 1 ? String.format("%4d ", tz) : "     ");
				for (int tx = x0; tx <= x1; ++tx)
				{
					for (int i = 0; i < 2; ++i)
					{
						float x = (tx + 0.25f + 0.5f * i) * 128, z = (tz + 0.25f + 0.5f * j) * 128;
						out.append(String.format("%3d", Math.round(100 * sh.skylight(new float[]{x, groundY(sh, x, z), z}, up))));
					}
					out.append(" |");
				}
				out.append('\n');
			}
		}
		return out.toString();
	}

	/**
	 * How the ground's skylight steps across the tile edges no wall stands on, against how it changes over the same
	 * distance inside tiles: the mean and largest step from 1/16 tile before an edge to 1/16 after it, along x and z.
	 */
	static String edgeSteps(Shader sh, WallMap walls, int cx, int cz, int radius)
	{
		float[] up = {0, -1, 0};
		double sum = 0, inside = 0;
		float max = 0;
		int n = 0, m = 0, over10 = 0;
		for (int tz = cz - radius; tz <= cz + radius; ++tz)
			for (int tx = cx - radius; tx <= cx + radius; ++tx)
				for (int axis = 0; axis < 2; ++axis)
				{
					int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
					if ((bits & (axis == 0 ? WallMap.EAST : WallMap.NORTH)) != 0 || WallMap.split(bits)) continue;
					int ox = tx + (axis == 0 ? 1 : 0), oz = tz + (axis == 0 ? 0 : 1);
					if (WallMap.split(walls.get(0, ox + walls.offset, oz + walls.offset))) continue;
					for (int k = 0; k < 4; ++k)
					{
						float along = (k + 0.5f) / 4;
						float[] a = axis == 0 ? new float[]{(tx + 1 - 1 / 16f) * 128, 0, (tz + along) * 128} : new float[]{(tx + along) * 128, 0, (tz + 1 - 1 / 16f) * 128};
						float[] b = axis == 0 ? new float[]{(tx + 1 + 1 / 16f) * 128, 0, (tz + along) * 128} : new float[]{(tx + along) * 128, 0, (tz + 1 + 1 / 16f) * 128};
						float[] c = axis == 0 ? new float[]{(tx + 0.5f - 1 / 16f) * 128, 0, (tz + along) * 128} : new float[]{(tx + along) * 128, 0, (tz + 0.5f - 1 / 16f) * 128};
						float[] e = axis == 0 ? new float[]{(tx + 0.5f + 1 / 16f) * 128, 0, (tz + along) * 128} : new float[]{(tx + along) * 128, 0, (tz + 0.5f + 1 / 16f) * 128};
						for (float[] p : new float[][]{a, b, c, e}) p[1] = groundY(sh, p[0], p[2]);
						float step = Math.abs(sh.skylight(a, up) - sh.skylight(b, up));
						sum += step;
						max = Math.max(max, step);
						if (step > 0.1f) over10++;
						n++;
						inside += Math.abs(sh.skylight(c, up) - sh.skylight(e, up));
						m++;
					}
				}
		return String.format("mean %.3f, max %.3f, %d of %d over 0.1 (inside tiles: mean %.3f)", sum / n, max, over10, n, inside / m);
	}

	/**
	 * Both faces of every straight wall on plane 0 within radius tiles: the shader's value for a face 4 units off the
	 * edge, 120 above the ground, mid-edge, its normal toward the side it is on. A room side is a roof-flagged tile.
	 */
	static String wallFaces(Shader sh, Dump d, int cx, int cz, int radius)
	{
		StringBuilder out = new StringBuilder("wall faces (tile, edge: roofed side value [trace/surface/exterior/cover/field] | other side value [...]):\n");
		int off = d.walls.offset;
		for (int tz = cz - radius; tz <= cz + radius; ++tz)
			for (int tx = cx - radius; tx <= cx + radius; ++tx)
			{
				int bits = d.walls.get(0, tx + off, tz + off);
				for (int e = 0; e < 4; ++e)
				{
					int side = new int[]{WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST}[e];
					if ((bits & side) == 0) continue;
					int dx = e == 1 ? 1 : e == 3 ? -1 : 0, dz = e == 0 ? 1 : e == 2 ? -1 : 0;
					// the point on the edge
					float ex = (tx + 0.5f + 0.5f * dx) * 128, ez = (tz + 0.5f + 0.5f * dz) * 128;
					boolean here = d.rooms.roofed[d.rooms.index(0, tx + off, tz + off)];
					boolean there = d.rooms.roofed[d.rooms.index(0, tx + dx + off, tz + dz + off)];
					if (here == there) continue;
					String[] faces = new String[2];
					for (int k = 0; k < 2; ++k)
					{
						// k 0: the face toward this tile (normal -d), 1: toward the neighbour (normal +d)
						float sgn = k == 0 ? -1 : 1;
						float x = ex + sgn * dx * 4, z = ez + sgn * dz * 4;
						float y = groundY(sh, Math.min(Math.max(x, 1), sh.size * 128 - 1), Math.min(Math.max(z, 1), sh.size * 128 - 1)) - 120;
						float v = sh.skylight(new float[]{x, y, z}, new float[]{sgn * dx, 0, sgn * dz});
						faces[k] = String.format("%2d [%2d/%2d/%2d/%2d/%2d]", pct(v), pct(sh.lastOriginal), pct(sh.lastSurface),
							pct(sh.skyExterior), pct(sh.lastCover), pct(sh.lastField));
					}
					String room = here ? faces[0] : faces[1], outside = here ? faces[1] : faces[0];
					out.append(String.format("  (%d,%d) %s: room %s | outside %s\n", tx, tz, "NESW".charAt(e), room, outside));
				}
			}
		return out.toString();
	}

	static int[] tileRoofs(Dump d, int size)
	{
		int[] out = new int[size * size];
		for (int z = 0; z < size; ++z)
			for (int x = 0; x < size; ++x)
			{
				int rx = x + d.rooms.offset, rz = z + d.rooms.offset, mask = 0;
				for (int f = 0; f < RoomMap.PLANES; ++f) if (d.rooms.roofed[d.rooms.index(f, rx, rz)]) mask |= 1 << f;
				out[z * size + x] = mask;
			}
		return out;
	}

	static String terms(Shader sh, WallMap walls, int x0, int x1, int z0, int z1)
	{
		StringBuilder out = new StringBuilder();
		float[] up = {0, -1, 0};
		for (int tz = z1; tz >= z0; --tz)
		{
			out.append(String.format("%3d ", tz));
			for (int tx = x0; tx <= x1; ++tx)
			{
				int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
				float fx = 0.5f, fz = 0.5f;
				if ((bits & WallMap.DIAG_SW_NE) != 0) { fx = 0.75f; fz = 0.25f; }
				if ((bits & WallMap.DIAG_NW_SE) != 0) { fx = 0.75f; fz = 0.75f; }
				float x = (tx + fx) * 128, z = (tz + fz) * 128;
				float v = sh.skylight(new float[]{x, groundY(sh, x, z), z}, up);
				out.append(String.format("%s%2d/%2d/%2d/%2d/%2d>%2d ", WallMap.split(bits) ? "*" : " ", pct(sh.lastOriginal), pct(sh.lastSurface),
					pct(sh.skyExterior), pct(sh.lastCover), pct(sh.lastField), pct(v)));
			}
			out.append('\n');
		}
		return out.toString();
	}

	private static int pct(float v)
	{
		return Math.max(-9, Math.min(99, Math.round(v * 100)));
	}

	static float groundY(Shader sh, float x, float z)
	{
		int tx = (int) (x / 128), tz = (int) (z / 128);
		return sh.meta(tx, tz, x / 128 - tx, z / 128 - tz, 0, 0);
	}

	static String tileTable(Dump d, Shader sh, int cx, int cz, int radius)
	{
		StringBuilder out = new StringBuilder("per tile: wall bits plane 0 (N1 E2 S4 W8 /16 \\32), roofed(r), roomMask, field light/cover\n");
		for (int tz = cz + radius; tz >= cz - radius; --tz)
		{
			out.append(String.format("%4d ", tz));
			for (int tx = cx - radius; tx <= cx + radius; ++tx)
			{
				int ex = tx + d.walls.offset, ez = tz + d.walls.offset;
				int bits = d.walls.get(0, ex, ez);
				boolean roofed = d.rooms.roofed[d.rooms.index(0, ex, ez)];
				float mask = sh.t(2 * tx + 6 * sh.size, 2 * tz, 0, 0);
				float[] f = sh.field(tx * 128 + 64, tz * 128 + 64);
				out.append(String.format("%2d%s%.1f %.1f/%.1f|", bits, roofed ? "r" : ".", mask, f[0], f[1]));
			}
			out.append('\n');
		}
		return out.toString();
	}

	@Test
	public void roundTrip() throws Exception
	{
		// flat ground at layer 10 and a roof over tiles 50..53 at 192..256 on posts, walls none
		int n = SkyGrid.SIZE * 2;
		VoxelGrid g = new VoxelGrid(n, 12, n, -10);
		for (int z = 0; z < n; ++z)
			for (int x = 0; x < n; ++x)
			{
				g.cells[g.index(x, 10, z)] = 1;
				if (x >= 100 && x < 108 && z >= 100 && z < 108) g.cells[g.index(x, 6, z)] = 1;
			}
		SceneVoxels v = new SceneVoxels(g, 0, 0, false, new int[1][SkyGrid.SIZE + 2 * com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET + 1][SkyGrid.SIZE + 2 * com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET + 1]);
		int ext = SkyGrid.SIZE + 2 * com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET;
		WallMap walls = WallMap.empty(ext, com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET, 1);
		Constructor<RoomMap> c = RoomMap.class.getDeclaredConstructor(int.class, int.class);
		c.setAccessible(true);
		RoomMap rooms = c.newInstance(ext, com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET);
		Dump d = new Dump();
		d.resolution = 1;
		d.voxels = v;
		d.walls = walls;
		d.rooms = rooms;
		d.playerX = 51;
		d.playerZ = 51;
		SkyGrid s = retrace(d);
		Path file = Files.createTempFile("skygrid", ".bin.gz");
		SkyGridDump.write(file, v, walls, rooms, null, s, 51, 51);
		Dump back = read(file);
		Files.delete(file);
		short[] texels = new short[s.texels.capacity()];
		s.texels.duplicate().get(texels);
		org.junit.Assert.assertArrayEquals(texels, back.texels);
		Shader sh = new Shader(back.texels, 1, field(back, 0), back.walls);
		float[] up = {0, -1, 0};
		float under = sh.skylight(new float[]{52 * 128, 0, 52 * 128}, up), open = sh.skylight(new float[]{30 * 128, 0, 30 * 128}, up);
		org.junit.Assert.assertTrue(under + " " + open, under < open - 0.1f);
	}

	@Test
	public void replay() throws Exception
	{
		Path dir = Paths.get(System.getProperty("user.home"), ".runelite", "lumina", "dumps");
		Assume.assumeTrue(Files.isDirectory(dir));
		try (java.util.stream.Stream<Path> files = Files.list(dir))
		{
			for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().startsWith("skygrid-")).sorted()::iterator)
			{
				System.out.println("=== " + file.getFileName());
				replay(read(file));
			}
		}
	}

	private static void replay(Dump d)
	{
		int cx = (int) Math.floor(d.playerX - d.voxels.baseX), cz = (int) Math.floor(d.playerZ - d.voxels.baseY);
		float[] field = field(d, 0);
		System.out.println("player local tile " + cx + "," + cz + " base " + d.voxels.baseX + "," + d.voxels.baseY);
		SkyGrid s = retrace(d);
		short[] again = new short[s.texels.capacity()];
		s.texels.duplicate().get(again);
		// slice 0's part 3 (the room flags) is laid out by the current SkyGrid, which a dump from another build may not
		int differ = 0, part3 = 0;
		for (int i = 0; i < again.length; ++i)
		{
			if (again[i] == d.texels[i]) continue;
			int texel = i / 4, x = texel % s.textureWidth, slice = texel / (s.textureWidth * s.textureHeight);
			if (slice == 0 && x >= 6 * s.size) part3++;
			else differ++;
		}
		System.out.println("retraced texels differing from the dump: " + differ + " of " + again.length
			+ " (and " + part3 + " in slice 0's room flags)");
		Shader now = new Shader(again, d.resolution, field, d.walls);
		Shader before = new Shader(again, d.resolution, field, d.walls);
		before.before = true;
		before.tileRoofs = tileRoofs(d, s.size);
		System.out.println("ground skylight as the shader read the grid before roofShares (x100):\n"
			+ fineMap(before, cx - 6, cx + 5, cz - 6, cz + 1));
		System.out.println("tile-edge steps over tiles +-8 (open ground, no wall on the edge), before: " + edgeSteps(before, d.walls, cx, cz, 8)
			+ "; retraced/now: " + edgeSteps(now, d.walls, cx, cz, 8));
		System.out.println(tileTable(d, now, cx, cz, 8));
		System.out.println(wallFaces(now, d, cx, cz, 8));
		System.out.println("terms at tile centres (diagonal tiles: the half away from the SW corner), now: trace/surface/exterior/cover/field -> value\n"
			+ terms(now, d.walls, cx - 5, cx + 4, cz - 5, cz + 3));
		System.out.println("ground skylight retraced (0-9):\n" + groundMap(now, null, cx, cz, 8, 4));
		System.out.println("ground skylight retraced x100, 2 x 2 per tile, tiles " + (cx - 6) + ".." + (cx + 5) + " by " + (cz - 6) + ".." + (cz + 1) + ":\n" + fineMap(now, cx - 6, cx + 5, cz - 6, cz + 1));
	}
}
