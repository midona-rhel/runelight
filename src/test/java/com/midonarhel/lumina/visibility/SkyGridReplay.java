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
		String name = "dump";
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
		float lastOriginal, lastSurface, lastCover, lastField, lastFieldCover;
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

		/** skylight_ao.glsl's skyRoofShare: the corners of the point's own half, with the triangle weights. */
		float roofShare(int tx, int tz, float fx, float fz, int diagonal, int plane)
		{
			if (diagonal == 0)
			{
				float vx1 = fx * fx * (3 - 2 * fx), vz1 = fz * fz * (3 - 2 * fz);
				float[] vx = {1 - vx1, vx1}, vz = {1 - vz1, vz1};
				float[] dx = {fx * (1 - fx) * (1 - fx), -fx * fx * (1 - fx)}, dz = {fz * (1 - fz) * (1 - fz), -fz * fz * (1 - fz)};
				float share = 0;
				for (int k = 0; k < 4; ++k)
				{
					int i = k & 1, j = k >> 1, x = 2 * tx + i, y = 2 * tz + j;
					int gx = x + (plane < 2 ? 0 : 2 * size), c = plane % 2 == 0 ? 0 : 2;
					share += t(x + 6 * size, y, 0, plane) * vx[i] * vz[j]
						+ t(gx, y, SkyGrid.HALF_ROOFS, c) * dx[i] * vz[j] + t(gx, y, SkyGrid.HALF_ROOFS, c + 1) * vx[i] * dz[j];
				}
				return Math.max(0, Math.min(1, share));
			}
			boolean north = diagonal == 1 ? fz > fx : fx + fz > 1;
			int slice = diagonal != 0 && !north ? SkyGrid.HALF_ROOFS : 0;
			float[] wt = weights(diagonal, fx, fz);
			float share = 0;
			for (int k = 0; k < 4; ++k)
				if (wt[k] > 0) share += wt[k] * t(2 * tx + (k & 1) + 6 * size, 2 * tz + (k >> 1), slice, plane);
			return share;
		}

		/** skylight_ao.glsl's skyDiagonalAt. */
		int diagonalAt(float[] w)
		{
			float txf = w[0] * scale / 128, tzf = w[2] * scale / 128;
			if (txf < 0 || tzf < 0 || txf >= size || tzf >= size) return 0;
			int tx = (int) Math.floor(txf), tz = (int) Math.floor(tzf);
			float fx = txf - tx, fz = tzf - tz;
			int floorMask = (int) (meta(tx, tz, fx, fz, 2 * size, 0) + 0.5f), storey = 0;
			for (int k = 1; k < 3; ++k) if ((floorMask & (1 << k)) != 0 && w[1] < meta(tx, tz, fx, fz, 0, k)) storey = k;
			return diag((int) (meta(tx, tz, fx, fz, 0, 3) + 0.5f), storey * 3);
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
				int x = half + 2 * tx + (k & 1), y = 2 * tz + (k >> 1), s = SkyGrid.VISIBILITY + level;
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
				int x = half + 2 * tx + (k & 1), y = 2 * tz + (k >> 1), s = SkyGrid.VISIBILITY + level;
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
				: 1 - roofShare(tx, tz, fx, fz, loD, roomPlane);
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
			float[] gsw = texel(bx, bz + fieldWidth), gse = texel(bx + 1, bz + fieldWidth), gnw = texel(bx, bz + 1 + fieldWidth), gne = texel(bx + 1, bz + 1 + fieldWidth);
			if (before)
			{
				// bilinear, triangles on split tiles (before the cubic rebuild)
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
			// skylight_ao.glsl's skyFieldVisibility
			if (diagonal == 0)
			{
				out[0] = hermite(fx, fz, new float[]{sw[0], se[0], nw[0], ne[0]}, new float[]{gsw[0], gse[0], gnw[0], gne[0]}, new float[]{gsw[1], gse[1], gnw[1], gne[1]});
				out[1] = hermite(fx, fz, new float[]{sw[1], se[1], nw[1], ne[1]}, new float[]{gsw[2], gse[2], gnw[2], gne[2]}, new float[]{gsw[3], gse[3], gnw[3], gne[3]});
			}
			else
			{
				boolean north = diagonal == 1 ? fz > fx : fx + fz > 1;
				int c = north ? 0 : 2;
				float[] light = {sw[c], se[c], nw[c], ne[c]}, gx = {gsw[c], gse[c], gnw[c], gne[c]}, gz = {gsw[c + 1], gse[c + 1], gnw[c + 1], gne[c + 1]};
				int k = diagonal == 1 ? (north ? 1 : 2) : (north ? 0 : 3);
				light[k] = light[k ^ 1] + light[k ^ 2] - light[3 - k];
				gx[k] = ((k & 1) == 1 ? 1 : -1) * (light[k] - light[k ^ 1]);
				gz[k] = ((k >> 1) == 1 ? 1 : -1) * (light[k] - light[k ^ 2]);
				float[] tri = diagonal == 1 ? (north ? new float[]{1 - fz, 0, fz - fx, fx} : new float[]{1 - fx, fx - fz, 0, fz})
					: (north ? new float[]{0, 1 - fz, 1 - fx, fx + fz - 1} : new float[]{1 - fx - fz, fx, fz, 0});
				out[0] = hermite(fx, fz, light, gx, gz);
				out[1] = tri[0] * sw[c + 1] + tri[1] * se[c + 1] + tri[2] * nw[c + 1] + tri[3] * ne[c + 1];
			}
			out[0] = Math.max(0, Math.min(1, out[0]));
			out[1] = Math.max(0, Math.min(1, out[1]));
			return out;
		}

		/** skylight_ao.glsl's skyHermite: corners SW, SE, NW, NE. */
		static float hermite(float fx, float fz, float[] v, float[] gx, float[] gz)
		{
			float vx1 = fx * fx * (3 - 2 * fx), vz1 = fz * fz * (3 - 2 * fz);
			float[] vx = {1 - vx1, vx1}, vz = {1 - vz1, vz1};
			float[] dx = {fx * (1 - fx) * (1 - fx), -fx * fx * (1 - fx)}, dz = {fz * (1 - fz) * (1 - fz), -fz * fz * (1 - fz)};
			float r = 0;
			for (int k = 0; k < 4; ++k)
			{
				int i = k & 1, j = k >> 1;
				r += v[k] * vx[i] * vz[j] + gx[k] * dx[i] * vz[j] + gz[k] * vx[i] * dz[j];
			}
			return r;
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
			if (!before && n[1] < 0.1f)
			{
				int diagonal = diagonalAt(o);
				if (diagonal != 0)
				{
					float ax = diagonal == 1 ? -0.70710678f : 0.70710678f, az = 0.70710678f, cell = 128f / scale;
					float s = (o[0] - ((float) Math.floor(o[0] / cell) + 0.5f) * cell) * ax + (o[2] - ((float) Math.floor(o[2] / cell) + 0.5f) * cell) * az;
					float d = n[0] * ax + n[2] * az;
					if (Math.abs(d) > SKY_SQUARE && s * d < 0 && Math.abs(s) < SKY_EDGE_REACH)
					{
						o[0] += ax * (Math.signum(d) - s);
						o[2] += az * (Math.signum(d) - s);
					}
				}
			}
			float[] fl = field(o[0], o[2]);
			float v = grid(o, n);
			float cover = fl[1] * (1 - skyExterior);
			lastCover = cover;
			lastField = fl[0];
			lastFieldCover = fl[1];
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

	/** The field as the skylight packs it: each corner's light by its straight-line distance from the open sky. */
	static float[] field(Dump d, int plane)
	{
		return field(d, plane, true);
	}

	/** The field; distance false: the corners' light the mean of their tiles' flood (before cornerLight). */
	static float[] field(Dump d, int plane, boolean distance)
	{
		boolean[] covered = SkyField.covered(d.rooms, d.floors, plane);
		float[] light = SkyField.propagate(d.walls, covered, plane, SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH));
		return SkyField.pack(d.walls, plane, light, covered, !distance ? null
			: SkyField.cornerLight(d.walls, plane, covered, SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH)));
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

	/**
	 * Every diagonal-wall tile within radius: the ground at each half's centre, and the panel's two faces (each looking
	 * into one half, 120 above the ground) with the panel on the diagonal and 11 units off it either way (Ardougne's
	 * stand 11 units outside), before | now. Values x100, half 0 (north) first.
	 */
	static String diagonalFaces(Shader before, Shader now, WallMap walls, int cx, int cz, int radius)
	{
		StringBuilder out = new StringBuilder("diagonal tiles (kind: ground h0/h1; faces into h0/h1 with the panel at -11, 0, +11 toward h0), before | now:\n");
		for (int tz = cz - radius; tz <= cz + radius; ++tz)
			for (int tx = cx - radius; tx <= cx + radius; ++tx)
			{
				int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
				if (!WallMap.split(bits)) continue;
				boolean slash = (bits & WallMap.DIAG_SW_NE) != 0;
				float ax = slash ? -0.70710678f : 0.70710678f, az = 0.70710678f;
				float mx = (tx + 0.5f) * 128, mz = (tz + 0.5f) * 128;
				out.append(String.format("  (%d,%d) %s:", tx, tz, slash ? "/" : "\\"));
				for (Shader sh : new Shader[]{before, now})
				{
					StringBuilder row = new StringBuilder();
					for (int h = 0; h < 2; ++h)
					{
						float sg = h == 0 ? 1 : -1, x = mx + ax * sg * 30, z = mz + az * sg * 30;
						row.append(h == 0 ? " ground " : "/").append(pct(sh.skylight(new float[]{x, groundY(sh, x, z), z}, new float[]{0, -1, 0})));
					}
					row.append(" faces");
					for (float off : new float[]{-11, 0, 11})
						for (int h = 0; h < 2; ++h)
						{
							float sg = h == 0 ? 1 : -1, x = mx + ax * (off + sg * 0.5f), z = mz + az * (off + sg * 0.5f);
							float v = sh.skylight(new float[]{x, groundY(sh, x, z) - 120, z}, new float[]{ax * sg, 0, az * sg});
							row.append(h == 0 ? " " : "/").append(pct(v));
						}
					out.append(row).append(sh == before ? "  |" : "\n");
				}
			}
		return out.toString();
	}

	/** The ground around the diagonal-wall tiles near (cx, cz), 8 x 8 samples a tile, before and now side by side. */
	static String diagonalMap(Shader before, Shader now, WallMap walls, int cx, int cz, int radius)
	{
		int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
		for (int tz = cz - radius; tz <= cz + radius; ++tz)
			for (int tx = cx - radius; tx <= cx + radius; ++tx)
				if (WallMap.split(walls.get(0, tx + walls.offset, tz + walls.offset)) && Math.abs(tx - cx) + Math.abs(tz - cz) <= 6)
				{
					x0 = Math.min(x0, tx - 1);
					x1 = Math.max(x1, tx + 1);
					z0 = Math.min(z0, tz - 1);
					z1 = Math.max(z1, tz + 1);
				}
		if (x1 < x0) return "none\n";
		StringBuilder out = new StringBuilder();
		float[] up = {0, -1, 0};
		for (int tz = z1; tz >= z0; --tz)
		{
			for (int j = 7; j >= 0; --j)
			{
				for (Shader sh : new Shader[]{before, now})
				{
					out.append(j == 7 ? String.format("%3d ", tz) : "    ");
					for (int tx = x0; tx <= x1; ++tx)
					{
						int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
						for (int i = 0; i < 8; ++i)
						{
							float fx = (i + 0.5f) / 8, fz = (j + 0.5f) / 8;
							boolean line = (bits & WallMap.DIAG_SW_NE) != 0 && Math.abs(fx - fz) < 0.07f
								|| (bits & WallMap.DIAG_NW_SE) != 0 && Math.abs(fx + fz - 1) < 0.07f;
							if (line)
							{
								out.append('.');
								continue;
							}
							float x = (tx + fx) * 128, z = (tz + fz) * 128;
							float v = sh.skylight(new float[]{x, groundY(sh, x, z), z}, up);
							out.append((char) ('0' + Math.max(0, Math.min(9, (int) (v * 10)))));
						}
						out.append(WallMap.split(bits) ? '*' : ' ');
					}
					out.append("   ");
				}
				out.append('\n');
			}
		}
		return out.toString();
	}

	/**
	 * The ground over tiles x0..x1 by z0..z1, 8 x 8 samples a tile, x10, walls drawn (- and | on straight edges, / and \\
	 * on diagonals): term 0 the value, 1 the trace, 2 the field's light, 3 the cover.
	 */
	static String regionMap(Shader sh, WallMap walls, int x0, int x1, int z0, int z1, int term)
	{
		StringBuilder out = new StringBuilder();
		float[] up = {0, -1, 0};
		for (int tz = z1; tz >= z0; --tz)
			for (int j = 7; j >= 0; --j)
			{
				out.append(j == 7 ? String.format("%3d ", tz) : "    ");
				for (int tx = x0; tx <= x1; ++tx)
				{
					int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
					for (int i = 0; i < 8; ++i)
					{
						float fx = (i + 0.5f) / 8, fz = (j + 0.5f) / 8;
						if ((bits & WallMap.NORTH) != 0 && j == 7 || (bits & WallMap.SOUTH) != 0 && j == 0) { out.append('-'); continue; }
						if ((bits & WallMap.EAST) != 0 && i == 7 || (bits & WallMap.WEST) != 0 && i == 0) { out.append('|'); continue; }
						if ((bits & WallMap.DIAG_SW_NE) != 0 && i == j) { out.append('/'); continue; }
						if ((bits & WallMap.DIAG_NW_SE) != 0 && i + j == 7) { out.append('\\'); continue; }
						float x = (tx + fx) * 128, z = (tz + fz) * 128;
						float v = sh.skylight(new float[]{x, groundY(sh, x, z), z}, up);
						float t = term == 0 ? v : term == 1 ? sh.lastOriginal : term == 2 ? sh.lastField : sh.lastCover;
						out.append((char) ('0' + Math.max(0, Math.min(9, (int) (t * 10)))));
					}
					out.append(' ');
				}
				out.append('\n');
			}
		return out.toString();
	}

	/**
	 * The ground over tiles x0..x1 by z0..z1 as a picture, 24 pixels a tile, north up, walls red: term as regionMap.
	 * Written to build/replay/.
	 */
	static void regionImage(Shader sh, WallMap walls, int x0, int x1, int z0, int z1, int term, String name) throws java.io.IOException
	{
		regionImage(sh, walls, x0, x1, z0, z1, term, name, 24, 1);
	}

	static void regionImage(Shader sh, WallMap walls, int x0, int x1, int z0, int z1, int term, String name, int px, float gain) throws java.io.IOException
	{
		int w = (x1 - x0 + 1) * px, h = (z1 - z0 + 1) * px;
		java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
		float[] up = {0, -1, 0};
		for (int y = 0; y < h; ++y)
			for (int x = 0; x < w; ++x)
			{
				float wx = (x0 + (x + 0.5f) / px) * 128, wz = (z1 + 1 - (y + 0.5f) / px) * 128;
				float v = sh.skylight(new float[]{wx, groundY(sh, wx, wz), wz}, up);
				float t = term == 0 ? v : term == 1 ? sh.lastOriginal : term == 2 ? sh.lastField : sh.lastCover;
				if (term >= 4)
				{
					// a term's gradient, per tile: kinks show as edges. 4 the value, 5 the field's light, 6 the
					// exterior (1 - roof share), 7 the trace
					float e2 = 128f / px;
					float t0 = term(sh, term, v), vx = sh.skylight(new float[]{wx + e2, groundY(sh, wx + e2, wz), wz}, up);
					float tx1 = term(sh, term, vx);
					float vz = sh.skylight(new float[]{wx, groundY(sh, wx, wz + e2), wz + e2}, up);
					float tz1 = term(sh, term, vz);
					t = (float) Math.hypot(tx1 - t0, tz1 - t0) * px;
				}
				int g = Math.max(0, Math.min(255, Math.round(255 * t * gain)));
				int tx = (int) Math.floor(wx / 128), tz = (int) Math.floor(wz / 128);
				float fx = wx / 128 - tx, fz = wz / 128 - tz, e = 1.5f / px;
				int bits = walls.get(0, tx + walls.offset, tz + walls.offset);
				boolean wall = (bits & WallMap.NORTH) != 0 && fz > 1 - e || (bits & WallMap.SOUTH) != 0 && fz < e
					|| (bits & WallMap.EAST) != 0 && fx > 1 - e || (bits & WallMap.WEST) != 0 && fx < e
					|| (bits & WallMap.DIAG_SW_NE) != 0 && Math.abs(fx - fz) < e || (bits & WallMap.DIAG_NW_SE) != 0 && Math.abs(fx + fz - 1) < e;
				im.setRGB(x, y, wall ? 0xc03030 : g * 0x010101);
			}
		java.nio.file.Path dir = Paths.get("build", "replay");
		Files.createDirectories(dir);
		javax.imageio.ImageIO.write(im, "png", dir.resolve(name + ".png").toFile());
	}

	private static float term(Shader sh, int term, float value)
	{
		return term == 4 ? value : term == 5 ? sh.lastField : term == 6 ? sh.skyExterior : term == 7 ? sh.lastOriginal : sh.lastFieldCover;
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

	/**
	 * Kinks: at every open tile edge within radius, how much the value's slope across the edge changes (per tile, from
	 * 1/8 tile before it to 1/8 after it), split by where the cover is: under cover (> 0.9), out of doors (< 0.1),
	 * between. Returns mean and largest per group.
	 */
	static String kinks(Shader sh, WallMap walls, int cx, int cz, int radius)
	{
		float[] up = {0, -1, 0};
		double[] sum = new double[3];
		float[] max = new float[3];
		int[] n = new int[3];
		float h = 1 / 8f;
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
						float[] v = new float[4];
						float cover = 0;
						for (int q = 0; q < 4; ++q)
						{
							float off = new float[]{-2 * h, -h, h, 2 * h}[q];
							float x = axis == 0 ? (tx + 1 + off) * 128 : (tx + along) * 128, z = axis == 0 ? (tz + along) * 128 : (tz + 1 + off) * 128;
							v[q] = sh.skylight(new float[]{x, groundY(sh, x, z), z}, up);
							cover += sh.lastCover / 4;
						}
						// the slope before the edge and after it, per tile
						float kink = Math.abs((v[3] - v[2]) / h - (v[1] - v[0]) / h);
						int g = cover > 0.9f ? 0 : cover < 0.1f ? 1 : 2;
						sum[g] += kink;
						max[g] = Math.max(max[g], kink);
						n[g]++;
					}
				}
		return String.format("covered mean %.3f max %.3f (%d) | outdoors mean %.3f max %.3f (%d) | between mean %.3f max %.3f (%d)",
			sum[0] / Math.max(1, n[0]), max[0], n[0], sum[1] / Math.max(1, n[1]), max[1], n[1], sum[2] / Math.max(1, n[2]), max[2], n[2]);
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
				Dump d = read(file);
				d.name = file.getFileName().toString().replace("skygrid-", "").replace(".bin.gz", "");
				replay(d);
			}
		}
	}

	private static void replay(Dump d) throws java.io.IOException
	{
		int cx = (int) Math.floor(d.playerX - d.voxels.baseX), cz = (int) Math.floor(d.playerZ - d.voxels.baseY);
		float[] field = field(d, 0);
		System.out.println("player local tile " + cx + "," + cz + " base " + d.voxels.baseX + "," + d.voxels.baseY);
		SkyGrid s = retrace(d);
		short[] again = new short[s.texels.capacity()];
		s.texels.duplicate().get(again);
		// the room flags (part 3 of the first slices) and where the visibility starts are laid out by the current
		// SkyGrid, which a dump from another build may not: visibility is compared slice for slice from its first
		int sliceSize = s.textureWidth * s.textureHeight * 4, dumpFirst = d.texels.length / sliceSize - SkyGrid.LEVELS;
		int differ = 0, part3 = 0;
		for (int i = 0; i < sliceSize; ++i)
			if (again[i] != d.texels[i] && (i / 4) % s.textureWidth < 6 * s.size) differ++;
		for (int level = 0; level < SkyGrid.LEVELS; ++level)
			for (int i = 0; i < sliceSize; ++i)
				if (again[(SkyGrid.VISIBILITY + level) * sliceSize + i] != d.texels[(dumpFirst + level) * sliceSize + i]) differ++;
		for (int i = 0; i < sliceSize; ++i)
			if (again[i] != d.texels[i] && (i / 4) % s.textureWidth >= 6 * s.size) part3++;
		System.out.println("retraced texels differing from the dump: " + differ + " of " + again.length
			+ " (and " + part3 + " in slice 0's room flags)");
		Shader now = new Shader(again, d.resolution, field, d.walls);
		Shader before = new Shader(again, d.resolution, field(d, 0, false), d.walls);
		before.before = true;
		before.tileRoofs = tileRoofs(d, s.size);
		System.out.println("ground skylight as the shader read the grid before roofShares (x100):\n"
			+ fineMap(before, cx - 6, cx + 5, cz - 6, cz + 1));
		System.out.println("tile-edge steps over tiles +-8 (open ground, no wall on the edge), before: " + edgeSteps(before, d.walls, cx, cz, 8)
			+ "; retraced/now: " + edgeSteps(now, d.walls, cx, cz, 8));
		System.out.println(tileTable(d, now, cx, cz, 8));
		System.out.println(wallFaces(now, d, cx, cz, 8));
		System.out.println(diagonalFaces(before, now, d.walls, cx, cz, 8));
		for (int term = 0; term < 4; ++term)
			for (Shader sh : new Shader[]{before, now})
				regionImage(sh, d.walls, cx - 8, cx + 8, cz - 8, cz + 8, term, d.name + "-" + new String[]{"value", "trace", "field", "cover"}[term]
					+ (sh == before ? "-before" : "-now"));
		for (Shader sh : new Shader[]{before, now})
		{
			String which = sh == before ? "before" : "now";
			System.out.println("slope change across open tile edges (per tile), " + which + ": " + kinks(sh, d.walls, cx, cz, 8));
			regionImage(sh, d.walls, cx - 5, cx + 2, cz - 5, cz + 1, 4, d.name + "-close-slope-" + which, 48, 2);
			regionImage(sh, d.walls, cx - 5, cx + 2, cz - 5, cz + 1, 0, d.name + "-close-shown-" + which, 48, 1);
		}
		{
			// the old corner light (the tiles' flood averaged) as a distance, against the new distance: their offset
			float fall = SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH);
			float[] old = field(d, 0, false), fresh = field(d, 0, true);
			double sum = 0, sq = 0;
			int n = 0;
			for (int i = 0; i < old.length / 2; i += 4)
			{
				float a = old[i], b = fresh[i];
				// covered corners where both are measurable (channel 1 cover, minus the diagonal code)
				float cov = old[i + 1] % 2;
				if (cov < 0.99f || a < 0.03f || a > 0.9f || b < 0.03f || b > 0.9f) continue;
				double k = Math.log(a) / Math.log(fall) - Math.log(b) / Math.log(fall);
				sum += k;
				sq += k * k;
				n++;
			}
			double mean = sum / n;
			System.out.printf("old corner light is the new one %.2f tiles further on (sd %.2f, %d corners)%n", mean, Math.sqrt(sq / n - mean * mean), n);
		}
		if (d.name.equals("market"))
		{
			// the stall by the user at 2657,3305 (optA captures): tile 48,57, walled on all four sides by the collision map
			for (Shader sh : new Shader[]{before, now})
				regionImage(sh, d.walls, 44, 53, 53, 61, 0, "market-stall-" + (sh == before ? "before" : "now"), 48, 1);
			StringBuilder st = new StringBuilder("stall 48,57 and around, terms at tile centres, now:\n");
			st.append(terms(now, d.walls, 46, 50, 55, 59));
			System.out.println(st);
		}
		if (d.name.equals("doorway"))
		{
			// where the light at a few corners deep in the room comes from: the path back to its source
			float[][] paths = new float[4][];
			boolean[] covered = SkyField.covered(d.rooms, d.floors, 0);
			SkyField.cornerLight(d.walls, 0, covered, SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH), paths);
			int corners = d.walls.size + 1, off = d.walls.offset;
			for (int[] c : new int[][]{{46, 44}, {44, 45}, {46, 46}})
			{
				StringBuilder path = new StringBuilder("path from corner " + c[0] + "," + c[1] + ":");
				int base = ((c[0] + off) * corners + c[1] + off) * 8, id = -1;
				for (int r = 0; r < 8; ++r) if (paths[0][base + r] < Float.POSITIVE_INFINITY && (id < 0 || paths[0][base + r] < paths[0][id])) id = base + r;
				for (int k = 0; id >= 0 && k < 12; ++k)
				{
					path.append(String.format(" (%.2f,%.2f d %.2f)", paths[2][id] - off, paths[3][id] - off, paths[0][id]));
					int p = (int) paths[1][id];
					if (p == id) break;
					id = p;
				}
				System.out.println(path);
			}
			for (int term = 4; term < 9; ++term)
				regionImage(now, d.walls, cx - 6, cx + 3, cz - 6, cz + 1, term, "doorway-terms-" + new String[]{"value", "field", "exterior", "trace", "fieldcover"}[term - 4], 48, term == 6 || term == 8 ? 1 : 4);
			for (int term : new int[]{0, 3})
				regionImage(now, d.walls, cx - 6, cx + 3, cz - 6, cz + 1, term, "doorway-plain-" + (term == 0 ? "value" : "cover"), 48, 1);
			// the whole room, its value's slope amplified: any straight break shows
			regionImage(now, d.walls, cx - 6, cx + 4, cz - 7, cz + 2, 4, "doorway-room-slope", 40, 10);
			// from the doorway (tile 46,48) south into the room, and across it at a tile in
			StringBuilder p = new StringBuilder("doorway profile, x 46.5, z from 49 down (value / trace / field / cover):\n");
			for (float z = 49.0f; z >= 43.9f; z -= 1 / 8f)
			{
				float x = 46.5f * 128, wz = z * 128;
				float b = before.skylight(new float[]{x, groundY(before, x, wz), wz}, new float[]{0, -1, 0});
				String bt = String.format("%3d %3d %3d %3d", pct(b), pct(before.lastOriginal), pct(before.lastField), pct(before.lastCover));
				float v = now.skylight(new float[]{x, groundY(now, x, wz), wz}, new float[]{0, -1, 0});
				String nt = String.format("%3d %3d %3d %3d (exterior %3d)", pct(v), pct(now.lastOriginal), pct(now.lastField), pct(now.lastCover), pct(now.skyExterior));
				p.append(String.format("  z %.3f  before %s | now %s%n", z, bt, nt));
			}
			System.out.println(p);
		}
		for (Shader sh : new Shader[]{before, now})
			for (int term = 0; term < 3; ++term)
				regionImage(sh, d.walls, cx - 5, cx + 2, cz - 5, cz + 1, term, d.name + "-close-" + new String[]{"value", "trace", "field"}[term]
					+ (sh == before ? "-before" : "-now"), 48, 4);
		System.out.println("diagonal tiles, 8 x 8 a tile, x10 (. on the wall line), before | now:\n" + diagonalMap(before, now, d.walls, cx, cz, 8));
		System.out.println("terms at tile centres (diagonal tiles: the half away from the SW corner), now: trace/surface/exterior/cover/field -> value\n"
			+ terms(now, d.walls, cx - 5, cx + 4, cz - 5, cz + 3));
		System.out.println("ground skylight retraced (0-9):\n" + groundMap(now, null, cx, cz, 8, 4));
		System.out.println("ground skylight retraced x100, 2 x 2 per tile, tiles " + (cx - 6) + ".." + (cx + 5) + " by " + (cz - 6) + ".." + (cz + 1) + ":\n" + fineMap(now, cx - 6, cx + 5, cz - 6, cz + 1));
	}
}
