package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.CardLamps;
import com.midonarhel.lumina.visibility.WallMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * The ground truth the scorecard measures every method against: from the drawn triangles (CardMesh) by exact CPU rays,
 * for each lamp, which sampled surface points it may light and how much of it each view ray's air takes. Nothing of any
 * lamp method goes in; it needs a dump and a CardProbe, and takes no GPU. Cached: one oracle per dump for all methods.
 *
 * <p>A lamp is a ball: centred on its origin (where its lines start: off the wall it hangs on) at its height, radius
 * LAMP_BALL_SHARE of its reach within [16, 96], the size the lamp code gives it. Only the part of the ball the centre
 * sees counts, so a lamp 24 units off a wall has no light behind that wall. From a surface point, offset SURFACE_OFF
 * along its face's normal, a ray to the centre and to each of BALL ball points:
 * <ul>
 * <li>OPEN: every ray arrives. The point must be lit.</li>
 * <li>BLOCKED: no ray arrives (the ball's umbra), or the lamp is on a storey the client hides. The point must be dark.
 * That is the rule: however soft a method is, nothing crosses here.</li>
 * <li>PENUMBRA: some do. Anything between dark and lit is right: this is where smoothness lives.</li>
 * </ul>
 * Hits nearer the lamp than NEAR don't count: its own fixture, as LampCubes.NEAR leaves it out.
 */
final class CardOracle
{
	static final int OPEN = 0, PENUMBRA = 1, BLOCKED = 2;
	static final int VERSION = 2;
	static final int STRIDE = 2;       // surface samples: every second pixel each way
	static final int AIR_STRIDE = 6;   // air rays: every sixth
	static final float SURFACE_OFF = 1.5f, NEAR = 12, LAMP_BALL_SHARE = 0.08f;
	static final int BALL = 12, AIR_BALL = 4;
	// a depth position this far from the mesh's first hit is something else: water, a transparent face, an animated model
	static final float MATCH = 6, MATCH_SHARE = 0.003f;

	int width, height, gridW, gridH;
	/** Per grid sample (STRIDE): a hash of the plane of the mesh triangle there, 0 where the mesh has none; for edges. */
	int[] plane;
	/** Per lamp, its surface samples: pixel index, and class | blockers << 2 (CardMesh mask, only for BLOCKED). */
	int[][] px;
	short[][] cls;
	/**
	 * Per lamp, its air samples: index into the air grid; the falloff along the ray within its reach: whole; as much as the
	 * lamp's ball gets there (airOpen, the mean of its rays); where all of it gets there (airFull: to fall short of this
	 * is a hole); where any of it gets past walls, roofs, terrain and floors, models and foliage not counted (airRule: to
	 * go over this is light through what must stop it). Between airFull and airRule anything goes, as in a penumbra.
	 */
	int airW, airH;
	int[][] airIdx;
	float[][] airWhole, airOpen, airFull, airRule;
	long meshTris, otherPixels, matchedPixels;
	int[] meshByCategory;
	long millis;

	static int classOf(short c)
	{
		return c & 3;
	}

	static int blockersOf(short c)
	{
		return c >> 2 & 127;
	}

	static CardOracle compute(SceneDump.Loaded dump, CardProbe p)
	{
		long t0 = System.nanoTime();
		DumpWorld world = new DumpWorld(dump);
		Scene top = world.scenes.get(WorldView.TOPLEVEL);
		WallMap walls = (WallMap) dump.derived.get("buildings.walls");
		CardMesh mesh = CardMesh.build(dump, p.minLevel, p.level, p.maxLevel, p.hiddenRoofs, top.getTileHeights(), walls);
		CardBvh bvh = new CardBvh(mesh);
		System.out.printf("oracle: %d triangles (wall %d, roof %d, terrain %d, floor %d, model %d, foliage %d), tree in %.1f s%n", mesh.tris,
			mesh.byCategory[0], mesh.byCategory[1], mesh.byCategory[2], mesh.byCategory[3], mesh.byCategory[4], mesh.byCategory[5], (System.nanoTime() - t0) / 1e9);
		CardOracle o = new CardOracle();
		o.width = p.width;
		o.height = p.height;
		o.meshTris = mesh.tris;
		o.meshByCategory = mesh.byCategory.clone();
		o.gridW = p.width / STRIDE;
		o.gridH = p.height / STRIDE;
		o.plane = new int[o.gridW * o.gridH];
		int n = p.width * p.height;
		// per pixel at STRIDE: the face normal of the mesh under it, 0 0 0 where it doesn't match the frame's depth
		float[] face = new float[o.gridW * o.gridH * 3];
		long[] matched = new long[o.gridH], other = new long[o.gridH];
		IntStream.range(0, o.gridH).parallel().forEach(gy ->
		{
			CardBvh.Scratch s = new CardBvh.Scratch();
			float[] nrm = new float[3];
			for (int gx = 0; gx < o.gridW; ++gx)
			{
				int i = (gy * STRIDE * p.width + gx * STRIDE) * 3;
				float x = p.pos[i], y = p.pos[i + 1], z = p.pos[i + 2];
				if (Float.isNaN(x))
				{
					continue;
				}
				float dx = x - p.camX, dy = y - p.camY, dz = z - p.camZ;
				float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				float slack = (MATCH + MATCH_SHARE * dist) / dist;
				if (bvh.closest(p.camX, p.camY, p.camZ, dx, dy, dz, 0, 1 + slack, s) && Math.abs(s.t - 1) < slack)
				{
					mesh.normal(s.tri, nrm);
					float side = nrm[0] * dx + nrm[1] * dy + nrm[2] * dz;
					float sign = side > 0 ? -1 : 1;
					int f = (gy * o.gridW + gx) * 3;
					face[f] = nrm[0] * sign;
					face[f + 1] = nrm[1] * sign;
					face[f + 2] = nrm[2] * sign;
					float d = (nrm[0] * x + nrm[1] * y + nrm[2] * z) * sign;
					int hash = Math.round(face[f] * 64) * 131 + Math.round(face[f + 1] * 64) * 17 + Math.round(face[f + 2] * 64) * 1009 + Math.round(d / 4) * 65537;
					o.plane[gy * o.gridW + gx] = hash == 0 ? 1 : hash;
					++matched[gy];
				}
				else
				{
					++other[gy];
				}
			}
		});
		o.matchedPixels = Arrays.stream(matched).sum();
		o.otherPixels = Arrays.stream(other).sum();
		int lamps = p.lamps.length;
		o.px = new int[lamps][];
		o.cls = new short[lamps][];
		float[][][] balls = new float[lamps][][];
		IntStream.range(0, lamps).parallel().forEach(k ->
		{
			balls[k] = ball(bvh, p.lamps[k]);
			o.surface(k, p, bvh, face, balls[k]);
		});
		o.air(p, bvh, balls);
		o.millis = (System.nanoTime() - t0) / 1_000_000;
		return o;
	}

	static float[][] ballOf(CardBvh bvh, float[] lamp)
	{
		return ball(bvh, lamp);
	}

	/** The lamp's ball points the centre sees, x y z; the centre first. Fibonacci points on the sphere, inner half on a second. */
	private static float[][] ball(CardBvh bvh, float[] lamp)
	{
		float cx = lamp[CardLamps.OX], cy = lamp[CardLamps.Y], cz = lamp[CardLamps.OZ];
		float r = Math.max(16, Math.min(96, lamp[CardLamps.RADIUS] * LAMP_BALL_SHARE));
		float[][] out = new float[BALL + 1][];
		out[0] = new float[]{cx, cy, cz};
		int n = 1;
		CardBvh.Scratch s = new CardBvh.Scratch();
		for (int i = 0; i < BALL; ++i)
		{
			double t = (i + 0.5) / BALL, phi = Math.PI * (3 - Math.sqrt(5)) * i;
			double y = 1 - 2 * t, h = Math.sqrt(1 - y * y);
			float rr = (i & 1) == 0 ? r : r * 0.6f;
			float bx = cx + (float) (h * Math.cos(phi)) * rr, by = cy + (float) y * rr, bz = cz + (float) (h * Math.sin(phi)) * rr;
			if (!bvh.occluded(cx, cy, cz, bx - cx, by - cy, bz - cz, NEAR / rr, 1, s))
			{
				out[n++] = new float[]{bx, by, bz};
			}
		}
		return Arrays.copyOf(out, n);
	}

	private void surface(int k, CardProbe p, CardBvh bvh, float[] face, float[][] ball)
	{
		float[] lamp = p.lamps[k];
		float lx = lamp[CardLamps.X], ly = lamp[CardLamps.Y], lz = lamp[CardLamps.Z], r = lamp[CardLamps.RADIUS];
		float cx = lamp[CardLamps.OX], cy = lamp[CardLamps.Y], cz = lamp[CardLamps.OZ];
		boolean hidden = p.onHidden[k];
		CardBvh.Scratch s = new CardBvh.Scratch();
		int cap = 1 << 12, count = 0;
		int[] idx = new int[cap];
		short[] cl = new short[cap];
		for (int gy = 0; gy < gridH; ++gy)
		{
			for (int gx = 0; gx < gridW; ++gx)
			{
				int pixel = gy * STRIDE * p.width + gx * STRIDE;
				int i = pixel * 3;
				float x = p.pos[i], y = p.pos[i + 1], z = p.pos[i + 2];
				if (Float.isNaN(x))
				{
					continue;
				}
				float tx = lx - x, ty = ly - y, tz = lz - z;
				float d2 = tx * tx + ty * ty + tz * tz;
				if (d2 >= r * r)
				{
					continue;
				}
				float nx = p.normal[i], ny = p.normal[i + 1], nz = p.normal[i + 2];
				if (nx * tx + ny * ty + nz * tz <= 0)
				{
					continue;
				}
				int f = (gy * gridW + gx) * 3;
				float fx = face[f], fy = face[f + 1], fz = face[f + 2];
				boolean matched = fx != 0 || fy != 0 || fz != 0;
				float off = SURFACE_OFF;
				if (!matched)
				{
					// water, a transparent face, an animated model: its smooth normal, further off
					fx = nx;
					fy = ny;
					fz = nz;
					off = 4;
				}
				else if (fx * tx + fy * ty + fz * tz <= 0)
				{
					// its face turned away, only its smooth normal round to the lamp: no ray from this side says anything
					continue;
				}
				float sx = x + fx * off, sy = y + fy * off, sz = z + fz * off;
				int c;
				int blockers = 0;
				if (hidden)
				{
					c = BLOCKED;
					blockers = CardMesh.HIDDEN;
				}
				else
				{
					boolean centre = seg(bvh, sx, sy, sz, ball[0], s);
					c = centre ? OPEN : BLOCKED;
					for (int b = 1; b < ball.length; ++b)
					{
						if (seg(bvh, sx, sy, sz, ball[b], s) != centre)
						{
							c = PENUMBRA;
							break;
						}
					}
					if (c == BLOCKED)
					{
						float dx = ball[0][0] - sx, dy = ball[0][1] - sy, dz = ball[0][2] - sz;
						float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
						blockers = bvh.blockers(sx, sy, sz, dx, dy, dz, 0, Math.max(0, 1 - NEAR / len), s);
					}
				}
				if (count == cap)
				{
					cap *= 2;
					idx = Arrays.copyOf(idx, cap);
					cl = Arrays.copyOf(cl, cap);
				}
				idx[count] = pixel;
				cl[count++] = (short) (c | blockers << 2 | (matched ? 0 : OTHER));
			}
		}
		px[k] = Arrays.copyOf(idx, count);
		cls[k] = Arrays.copyOf(cl, count);
	}

	/** Set on a sample whose pixel the mesh doesn't hold (water, transparent, animated): it counts apart. */
	static final int OTHER = 512;

	// whether the ray from s to b arrives
	private static boolean seg(CardBvh bvh, float sx, float sy, float sz, float[] b, CardBvh.Scratch s)
	{
		float dx = b[0] - sx, dy = b[1] - sy, dz = b[2] - sz;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= NEAR)
		{
			return true;
		}
		return !bvh.occluded(sx, sy, sz, dx, dy, dz, 0, 1 - NEAR / len, s);
	}

	// whether the ray from s to b gets past what rule 4 names: only walls, roofs, terrain and floors stop it
	private static boolean segRule(CardBvh bvh, float sx, float sy, float sz, float[] b, CardBvh.Scratch s)
	{
		float dx = b[0] - sx, dy = b[1] - sy, dz = b[2] - sz;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= NEAR)
		{
			return true;
		}
		return !bvh.occluded(sx, sy, sz, dx, dy, dz, 0, 1 - NEAR / len, CardMesh.RULE, s);
	}

	/**
	 * Each air ray (AIR_STRIDE) up to what it ends at, for each lamp whose reach it enters: the lamp's falloff along it,
	 * whole and let through. Points along it taken every AIR_STEP world units (at least 6, at most 32), each a share of
	 * the lamp's ball as above: in the air there is no hidden-storey rule; only what is drawn stops light.
	 */
	private void air(CardProbe p, CardBvh bvh, float[][][] balls)
	{
		airW = p.width / AIR_STRIDE;
		airH = p.height / AIR_STRIDE;
		int lamps = p.lamps.length;
		airIdx = new int[lamps][];
		airWhole = new float[lamps][];
		airOpen = new float[lamps][];
		airFull = new float[lamps][];
		airRule = new float[lamps][];
		float[] dirs = p.air;
		IntStream.range(0, lamps).parallel().forEach(k ->
		{
			float[] lamp = p.lamps[k];
			float lx = lamp[CardLamps.X] - p.camX, ly = lamp[CardLamps.Y] - p.camY, lz = lamp[CardLamps.Z] - p.camZ, r = lamp[CardLamps.RADIUS];
			float[][] ball = balls[k];
			int[] step = new int[Math.min(ball.length, AIR_BALL + 1)];
			for (int b = 0; b < step.length; ++b)
			{
				step[b] = b * (ball.length - 1) / Math.max(1, step.length - 1);
			}
			CardBvh.Scratch s = new CardBvh.Scratch();
			int cap = 1 << 10, count = 0;
			int[] idx = new int[cap];
			float[] whole = new float[cap], open = new float[cap], full = new float[cap], rule = new float[cap];
			for (int g = 0; g < airW * airH; ++g)
			{
				float tEnd = dirs[g * 4 + 3];
				float dx = dirs[g * 4], dy = dirs[g * 4 + 1], dz = dirs[g * 4 + 2];
				float tc = dx * lx + dy * ly + dz * lz;
				float h2 = lx * lx + ly * ly + lz * lz - tc * tc;
				if (h2 >= r * r)
				{
					continue;
				}
				float hc = (float) Math.sqrt(r * r - h2);
				float a = Math.max(tc - hc, 0), b = Math.min(tc + hc, tEnd);
				if (b <= a)
				{
					continue;
				}
				int n = Math.max(6, Math.min(32, (int) ((b - a) / AIR_STEP)));
				float dt = (b - a) / n, w = 0, o = 0, f = 0, u = 0;
				for (int j = 0; j < n; ++j)
				{
					float t = a + (j + 0.5f) * dt;
					float qx = dx * t, qy = dy * t, qz = dz * t;
					float ex = qx - lx, ey = qy - ly, ez = qz - lz;
					float att = 1 - (float) Math.sqrt(ex * ex + ey * ey + ez * ez) / r;
					att = att > 0 ? att * att * dt : 0;
					if (att <= 0)
					{
						continue;
					}
					w += att;
					int seen = 0;
					for (int q : step)
					{
						seen += seg(bvh, qx + p.camX, qy + p.camY, qz + p.camZ, ball[q], s) ? 1 : 0;
					}
					o += att * seen / step.length;
					f += seen == step.length ? att : 0;
					boolean some = seen > 0;
					for (int q = 0; q < step.length && !some; ++q)
					{
						some = segRule(bvh, qx + p.camX, qy + p.camY, qz + p.camZ, ball[step[q]], s);
					}
					u += some ? att : 0;
				}
				if (count == cap)
				{
					cap *= 2;
					idx = Arrays.copyOf(idx, cap);
					whole = Arrays.copyOf(whole, cap);
					open = Arrays.copyOf(open, cap);
					full = Arrays.copyOf(full, cap);
					rule = Arrays.copyOf(rule, cap);
				}
				idx[count] = g;
				whole[count] = w;
				full[count] = f;
				rule[count] = u;
				open[count++] = o;
			}
			airIdx[k] = Arrays.copyOf(idx, count);
			airWhole[k] = Arrays.copyOf(whole, count);
			airOpen[k] = Arrays.copyOf(open, count);
			airFull[k] = Arrays.copyOf(full, count);
			airRule[k] = Arrays.copyOf(rule, count);
		});
	}

	static final float AIR_STEP = 24;

	/**
	 * Why the oracle says what it says of a pixel and the lamp on a world tile: the rays to the lamp, each thing along them,
	 * nearest first: distance from the point, category, the triangle's corners. To check a finding by hand.
	 */
	static String explain(SceneDump.Loaded dump, CardProbe p, int x, int y, int lampX, int lampY)
	{
		DumpWorld world = new DumpWorld(dump);
		CardMesh mesh = CardMesh.build(dump, p.minLevel, p.level, p.maxLevel, p.hiddenRoofs, world.scenes.get(WorldView.TOPLEVEL).getTileHeights(),
			(WallMap) dump.derived.get("buildings.walls"));
		CardBvh bvh = new CardBvh(mesh);
		StringBuilder b = new StringBuilder();
		int pixel = y * p.width + x;
		float px = p.pos[pixel * 3], py = p.pos[pixel * 3 + 1], pz = p.pos[pixel * 3 + 2];
		b.append(String.format("pixel %d,%d at %.1f %.1f %.1f, smooth normal %.2f %.2f %.2f%n", x, y, px, py, pz, p.normal[pixel * 3], p.normal[pixel * 3 + 1], p.normal[pixel * 3 + 2]));
		CardBvh.Scratch s = new CardBvh.Scratch();
		float dx = px - p.camX, dy = py - p.camY, dz = pz - p.camZ;
		float[] n = new float[3];
		if (bvh.closest(p.camX, p.camY, p.camZ, dx, dy, dz, 0, 1.02f, s))
		{
			mesh.normal(s.tri, n);
			b.append(String.format("from the camera: t %.4f, category %s, face normal %.2f %.2f %.2f%n", s.t, CardMesh.NAMES[Integer.numberOfTrailingZeros(mesh.cat[s.tri])], n[0], n[1], n[2]));
		}
		for (int k = 0; k < p.lamps.length; ++k)
		{
			float[] l = p.lamps[k];
			if ((int) Math.floor(l[CardLamps.X] / 128) + dump.manifest.baseX != lampX || (int) Math.floor(l[CardLamps.Z] / 128) + dump.manifest.baseY != lampY)
			{
				continue;
			}
			float[][] ball = ball(bvh, l);
			b.append(String.format("lamp %d: at %.1f %.1f %.1f, origin %.1f %.1f, storey %d, reach %.0f, on a hidden storey %s, %d ball points in sight%n", k, l[CardLamps.X], l[CardLamps.Y],
				l[CardLamps.Z], l[CardLamps.OX], l[CardLamps.OZ], (int) l[CardLamps.PLANE], l[CardLamps.RADIUS], p.onHidden[k], ball.length));
			float sign = n[0] * dx + n[1] * dy + n[2] * dz > 0 ? -1 : 1;
			float sx = px + n[0] * sign * SURFACE_OFF, sy = py + n[1] * sign * SURFACE_OFF, sz = pz + n[2] * sign * SURFACE_OFF;
			int open = 0;
			for (float[] c : ball)
			{
				open += seg(bvh, sx, sy, sz, c, s) ? 1 : 0;
			}
			b.append(String.format(" %d of %d rays arrive%n", open, ball.length));
			float rx = ball[0][0] - sx, ry = ball[0][1] - sy, rz = ball[0][2] - sz;
			float len = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
			java.util.TreeMap<Float, String> hits = new java.util.TreeMap<>();
			for (int t = 0; t < mesh.tris; ++t)
			{
				float h = triangle(mesh.v, t, sx, sy, sz, rx, ry, rz);
				if (h > 0 && h < 1 && (mesh.tex[t] < 0))
				{
					hits.put(h * len, String.format("%s (%.0f %.0f %.0f) (%.0f %.0f %.0f) (%.0f %.0f %.0f)", CardMesh.NAMES[Integer.numberOfTrailingZeros(mesh.cat[t])],
						mesh.v[t * 9], mesh.v[t * 9 + 1], mesh.v[t * 9 + 2], mesh.v[t * 9 + 3], mesh.v[t * 9 + 4], mesh.v[t * 9 + 5], mesh.v[t * 9 + 6], mesh.v[t * 9 + 7], mesh.v[t * 9 + 8]));
				}
			}
			b.append(String.format(" centre ray, %.0f long:%n", len));
			hits.forEach((d, what) -> b.append(String.format("  at %.1f: %s%n", d, what)));
		}
		return b.toString();
	}

	private static float triangle(float[] v, int tri, float ox, float oy, float oz, float dx, float dy, float dz)
	{
		int a = tri * 9;
		float e1x = v[a + 3] - v[a], e1y = v[a + 4] - v[a + 1], e1z = v[a + 5] - v[a + 2], e2x = v[a + 6] - v[a], e2y = v[a + 7] - v[a + 1], e2z = v[a + 8] - v[a + 2];
		float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
		float det = e1x * px + e1y * py + e1z * pz;
		if (Math.abs(det) < 1e-9f)
		{
			return -1;
		}
		float inv = 1 / det, tx = ox - v[a], ty = oy - v[a + 1], tz = oz - v[a + 2];
		float u = (tx * px + ty * py + tz * pz) * inv;
		float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
		float w = (dx * qx + dy * qy + dz * qz) * inv;
		if (u < 0 || w < 0 || u + w > 1)
		{
			return -1;
		}
		return (e2x * qx + e2y * qy + e2z * qz) * inv;
	}

	void save(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)))
		{
			o.writeInt(VERSION);
			o.writeInt(width);
			o.writeInt(height);
			o.writeInt(gridW);
			o.writeInt(gridH);
			o.writeInt(airW);
			o.writeInt(airH);
			o.writeLong(meshTris);
			o.writeLong(otherPixels);
			o.writeLong(matchedPixels);
			o.writeLong(millis);
			ints(o, meshByCategory);
			ints(o, plane);
			o.writeInt(px.length);
			for (int k = 0; k < px.length; ++k)
			{
				ints(o, px[k]);
				shorts(o, cls[k]);
				ints(o, airIdx[k]);
				floats(o, airWhole[k]);
				floats(o, airOpen[k]);
				floats(o, airFull[k]);
				floats(o, airRule[k]);
			}
		}
	}

	static CardOracle load(Path file) throws IOException
	{
		CardOracle c = new CardOracle();
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20)))
		{
			if (in.readInt() != VERSION)
			{
				throw new IOException("oracle cache of another version: " + file);
			}
			c.width = in.readInt();
			c.height = in.readInt();
			c.gridW = in.readInt();
			c.gridH = in.readInt();
			c.airW = in.readInt();
			c.airH = in.readInt();
			c.meshTris = in.readLong();
			c.otherPixels = in.readLong();
			c.matchedPixels = in.readLong();
			c.millis = in.readLong();
			c.meshByCategory = ints(in);
			c.plane = ints(in);
			int n = in.readInt();
			c.px = new int[n][];
			c.cls = new short[n][];
			c.airIdx = new int[n][];
			c.airWhole = new float[n][];
			c.airOpen = new float[n][];
			c.airFull = new float[n][];
			c.airRule = new float[n][];
			for (int k = 0; k < n; ++k)
			{
				c.px[k] = ints(in);
				c.cls[k] = shorts(in);
				c.airIdx[k] = ints(in);
				c.airWhole[k] = floats(in);
				c.airOpen[k] = floats(in);
				c.airFull[k] = floats(in);
				c.airRule[k] = floats(in);
			}
		}
		return c;
	}

	private static void ints(DataOutputStream o, int[] a) throws IOException
	{
		o.writeInt(a.length);
		ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.asIntBuffer().put(a);
		o.write(b.array());
	}

	private static int[] ints(DataInputStream in) throws IOException
	{
		int[] a = new int[in.readInt()];
		byte[] raw = new byte[a.length * 4];
		in.readFully(raw);
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(a);
		return a;
	}

	private static void shorts(DataOutputStream o, short[] a) throws IOException
	{
		o.writeInt(a.length);
		ByteBuffer b = ByteBuffer.allocate(a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		b.asShortBuffer().put(a);
		o.write(b.array());
	}

	private static short[] shorts(DataInputStream in) throws IOException
	{
		short[] a = new short[in.readInt()];
		byte[] raw = new byte[a.length * 2];
		in.readFully(raw);
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(a);
		return a;
	}

	private static void floats(DataOutputStream o, float[] a) throws IOException
	{
		o.writeInt(a.length);
		ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.asFloatBuffer().put(a);
		o.write(b.array());
	}

	private static float[] floats(DataInputStream in) throws IOException
	{
		float[] a = new float[in.readInt()];
		byte[] raw = new byte[a.length * 4];
		in.readFully(raw);
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a);
		return a;
	}
}
