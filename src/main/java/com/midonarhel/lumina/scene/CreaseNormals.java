package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Smooth normals of our own for models, in place of the game's vertex normals (the user's experiment, 2026-10-31:
 * "since these verts are basically inside each other, why don't we merge them and recalc the mesh shading based on
 * that"). The game sums a smooth face's normal into each vertex index it uses, whatever the angle: a thatched roof's
 * slope corner that the model shares with its timbers, eave underside and hip leans 14-40 degrees off the slope
 * (roof dump at 2657,3301), so a flat slope shades as a gradient; and where a model splits a vertex, faces that
 * should round show an edge.
 *
 * Here a model's vertices are welded by position (the same integer position is one corner, whichever index), and a
 * face's corner takes the angle-weighted sum of the welded smooth faces at that corner whose normal is within
 * {@link #CREASE_DEGREES} of the face's own; across a sharper fold it keeps its own normal. Faces the game marks
 * flat (faceColors3 == -1) and hidden ones (-2) neither take nor give: flat faces stay flat, as the game and 117 HD
 * keep them.
 *
 * Scene models are done at their zone upload. Every other model (actors, animated and moving objects, uploaded per
 * frame) keeps the normals of the first pose it was uploaded in, cached by the client's face index array, which an
 * animated model's frames share with their source model: the game's own normals are the same for every frame too
 * (computed once, unanimated), and per frame they would cost about 60 ns a face.
 */
public final class CreaseNormals
{
	/**
	 * Faces meeting at less than this round together. From the evidence (docs/117hd-model-shading.md 5b): an 8-sided
	 * trunk folds 45 degrees between sides and a 6-sided one 60, which should round (the user: the magic tree's trunk
	 * is smooth); the Ardougne thatched roofs' ridges fold 74-90, their timbers and gables 90, eave undersides 125 and
	 * more, a crate's edges 90, which should stay crisp. The roofs' hips fold 46.7 and 50.2, inside the trunks' range:
	 * no single angle keeps them crisp and rounds an octagonal trunk, so they round.
	 */
	static final float CREASE_DEGREES = 62;
	private static final float CREASE_COS = (float) Math.cos(Math.toRadians(CREASE_DEGREES));

	// a corner's normal, unit length times this (117 HD's face normal scale, VertexShading.faceNormal)
	private static final int SCALE = 2048;

	/** A model's welded corners: each vertex's weld group, and per group the faces that use it. */
	static final class Weld
	{
		final int[] group;       // per vertex
		final int[] groupStart;  // per group, into groupFaces; groups + 1 entries
		final int[] groupFaces;

		Weld(int[] group, int[] groupStart, int[] groupFaces)
		{
			this.group = group;
			this.groupStart = groupStart;
			this.groupFaces = groupFaces;
		}
	}

	/** A model's corner normals, once computed: x y z per corner, nine per face, and which faces have them. */
	static final class Normals
	{
		final int vertexCount, faceCount;
		final short[] corners;
		final boolean[] smooth;

		Normals(int vertexCount, int faceCount, short[] corners, boolean[] smooth)
		{
			this.vertexCount = vertexCount;
			this.faceCount = faceCount;
			this.corners = corners;
			this.smooth = smooth;
		}
	}

	private static final Map<int[], Normals> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	// the cost, for the scene load report: scene models (zone uploads), the others computed, and the others cached
	private static final LongAdder sceneNanos = new LongAdder(), sceneModels = new LongAdder(),
		otherNanos = new LongAdder(), otherModels = new LongAdder(), otherCached = new LongAdder();

	/** The cost since the last call, as text, and starts over. */
	static String takeCost()
	{
		return String.format("scene models %d in %.1f ms; others %d computed in %.1f ms, %d from the cache",
			sceneModels.sumThenReset(), sceneNanos.sumThenReset() / 1e6, otherModels.sumThenReset(),
			otherNanos.sumThenReset() / 1e6, otherCached.sumThenReset());
	}

	// per uploader: the face normals and corner angles of the model being computed
	private float[] faceX = new float[0], faceY = new float[0], faceZ = new float[0];
	private float[] angles = new float[0];
	// the normals the uploader reads, null when the model has none of ours
	private Normals current;

	/** {@link #compute} for a model the uploaders read through ModelData; nothing for a model without game normals. */
	void compute(ModelData m)
	{
		current = null;
		if (m.normalsX == null || m.normalsY == null || m.normalsZ == null)
		{
			return;
		}
		Normals n = m.normalsKey != null ? CACHE.get(m.normalsKey) : null;
		if (n != null && n.vertexCount == m.vertexCount && n.faceCount == m.faceCount)
		{
			current = n;
			otherCached.increment();
			return;
		}
		long t = System.nanoTime();
		compute(m.vertexCount, m.verticesX, m.verticesY, m.verticesZ, m.faceCount, m.indices1, m.indices2,
			m.indices3, m.colors3);
		if (m.normalsKey != null)
		{
			CACHE.put(m.normalsKey, current);
		}
		otherNanos.add(System.nanoTime() - t);
		otherModels.increment();
	}

	/** {@link #compute} for a scene model; nothing for a model without game normals. */
	void compute(net.runelite.api.Model m)
	{
		current = null;
		if (m.getVertexNormalsX() == null || m.getVertexNormalsY() == null || m.getVertexNormalsZ() == null)
		{
			return;
		}
		long t = System.nanoTime();
		compute(m.getVerticesCount(), m.getVerticesX(), m.getVerticesY(), m.getVerticesZ(), m.getFaceCount(),
			m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3(), m.getFaceColors3());
		sceneNanos.add(System.nanoTime() - t);
		sceneModels.increment();
	}

	static Weld weld(int vertexCount, float[] vx, float[] vy, float[] vz, int faceCount, int[] i1, int[] i2,
		int[] i3)
	{
		// vertices at one integer position are one group: open addressing on the packed position
		int size = Integer.highestOneBit(Math.max(vertexCount, 1)) << 2;
		int shift = 32 - Integer.numberOfTrailingZeros(size);
		long[] keys = new long[size];
		int[] ids = new int[size];
		java.util.Arrays.fill(ids, -1);
		int[] group = new int[vertexCount];
		int groups = 0;
		for (int v = 0; v < vertexCount; ++v)
		{
			long k = ((long) Math.round(vx[v]) & 0x1fffff) << 42 | ((long) Math.round(vy[v]) & 0x1fffff) << 21
				| (long) Math.round(vz[v]) & 0x1fffff;
			int h = (int) (k ^ k >>> 32) * 0x9e3779b1 >>> shift;
			while (ids[h] != -1 && keys[h] != k)
			{
				h = h + 1 & size - 1;
			}
			if (ids[h] == -1)
			{
				keys[h] = k;
				ids[h] = groups++;
			}
			group[v] = ids[h];
		}
		int[] start = new int[groups + 1];
		for (int f = 0; f < faceCount; ++f)
		{
			++start[group[i1[f]] + 1];
			++start[group[i2[f]] + 1];
			++start[group[i3[f]] + 1];
		}
		for (int g = 0; g < groups; ++g)
		{
			start[g + 1] += start[g];
		}
		int[] fill = java.util.Arrays.copyOf(start, groups);
		int[] faces = new int[faceCount * 3];
		for (int f = 0; f < faceCount; ++f)
		{
			faces[fill[group[i1[f]]]++] = f;
			faces[fill[group[i2[f]]]++] = f;
			faces[fill[group[i3[f]]]++] = f;
		}
		return new Weld(group, start, faces);
	}

	/**
	 * Every smooth face's three corner normals for this model as it is now, model space, unit length times 2048, read
	 * back with {@link #corner}. A face is smooth when the game does not mark it flat or hidden (colors3 -1, -2).
	 */
	void compute(int vertexCount, float[] vx, float[] vy, float[] vz, int faceCount, int[] i1, int[] i2, int[] i3,
		int[] colors3)
	{
		Weld w = weld(vertexCount, vx, vy, vz, faceCount, i1, i2, i3);
		if (faceX.length < faceCount)
		{
			int n = Math.max(faceCount, faceX.length * 2);
			faceX = new float[n];
			faceY = new float[n];
			faceZ = new float[n];
			angles = new float[n * 3];
		}
		boolean[] smooth = new boolean[faceCount];
		short[] corners = new short[faceCount * 9];
		for (int f = 0; f < faceCount; ++f)
		{
			int a = i1[f], b = i2[f], c = i3[f];
			float e0x = vx[b] - vx[a], e0y = vy[b] - vy[a], e0z = vz[b] - vz[a];
			float e1x = vx[c] - vx[a], e1y = vy[c] - vy[a], e1z = vz[c] - vz[a];
			// cross(B - A, C - A), the face's normal
			float x = e0y * e1z - e0z * e1y, y = e0z * e1x - e0x * e1z, z = e0x * e1y - e0y * e1x;
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			smooth[f] = colors3[f] != -1 && colors3[f] != -2 && l > 0;
			if (!smooth[f])
			{
				continue;
			}
			faceX[f] = x / l;
			faceY[f] = y / l;
			faceZ[f] = z / l;
			float e2x = vx[c] - vx[b], e2y = vy[c] - vy[b], e2z = vz[c] - vz[b];
			angles[f * 3] = angle(e0x, e0y, e0z, e1x, e1y, e1z);
			angles[f * 3 + 1] = angle(-e0x, -e0y, -e0z, e2x, e2y, e2z);
			angles[f * 3 + 2] = (float) Math.PI - angles[f * 3] - angles[f * 3 + 1];
		}
		for (int f = 0; f < faceCount; ++f)
		{
			if (!smooth[f])
			{
				continue;
			}
			float fx = faceX[f], fy = faceY[f], fz = faceZ[f];
			for (int k = 0; k < 3; ++k)
			{
				int g = w.group[k == 0 ? i1[f] : k == 1 ? i2[f] : i3[f]];
				float sx = 0, sy = 0, sz = 0;
				int seen = -1;
				for (int j = w.groupStart[g]; j < w.groupStart[g + 1]; ++j)
				{
					int h = w.groupFaces[j];
					// a face using the group at two corners (degenerate) counts once
					if (h == seen || !smooth[h])
					{
						continue;
					}
					seen = h;
					float hx = faceX[h], hy = faceY[h], hz = faceZ[h];
					if (fx * hx + fy * hy + fz * hz >= CREASE_COS)
					{
						// weighed by its angle at this corner: a quad's two triangles weigh as the quad, however
						// it is split (by area, a side meeting the corner with two triangles outweighed one meeting
						// it with one)
						float a = angles[h * 3 + (w.group[i1[h]] == g ? 0 : w.group[i2[h]] == g ? 1 : 2)];
						sx += hx * a;
						sy += hy * a;
						sz += hz * a;
					}
				}
				// the face itself is always in its own sum
				float l = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
				int o = f * 9 + k * 3;
				corners[o] = (short) (sx / l * SCALE);
				corners[o + 1] = (short) (sy / l * SCALE);
				corners[o + 2] = (short) (sz / l * SCALE);
			}
		}
		current = new Normals(vertexCount, faceCount, corners, smooth);
	}

	/** The angle between two edges from a corner, radians. */
	private static float angle(float ax, float ay, float az, float bx, float by, float bz)
	{
		float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
		return (float) Math.atan2(Math.sqrt(cx * cx + cy * cy + cz * cz), ax * bx + ay * by + az * bz);
	}

	/** Copies smooth face f's corner normals from the last {@link #compute} into out, x y z per corner. */
	void corner(int f, int[] out)
	{
		short[] c = current.corners;
		for (int i = 0; i < 9; ++i)
		{
			out[i] = c[f * 9 + i];
		}
	}

	/** Whether the last {@link #compute} gave face f corner normals of its own (a smooth face with an area). */
	boolean has(int f)
	{
		Normals n = current;
		return n != null && f < n.faceCount && n.smooth[f];
	}
}
