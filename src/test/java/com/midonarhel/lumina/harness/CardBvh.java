package com.midonarhel.lumina.harness;

import java.util.Arrays;

/**
 * The scorecard's ray tracer: a bounding volume hierarchy over the triangles of a CardMesh, built once, then queried by
 * any number of threads. A segment from o to o + d, t between tMin and tMax, hits a triangle on either side; an
 * alpha-tested triangle stops it only where its texture is solid at the hit, as shadow_frag.glsl has it.
 */
final class CardBvh
{
	private static final int LEAF = 4, BINS = 12;
	final CardMesh mesh;
	// per node: min x y z, max x y z; left child (inner, the right is next to it in rightOf) or first of tri order
	private float[] box;
	private int[] first, count, right;
	private int nodes;
	private final int[] order;
	private float[] cx, cy, cz, bmin, bmax;

	CardBvh(CardMesh mesh)
	{
		this.mesh = mesh;
		int n = mesh.tris;
		order = new int[n];
		cx = new float[n];
		cy = new float[n];
		cz = new float[n];
		bmin = new float[n * 3];
		bmax = new float[n * 3];
		float[] v = mesh.v;
		for (int i = 0; i < n; ++i)
		{
			order[i] = i;
			int a = i * 9;
			for (int c = 0; c < 3; ++c)
			{
				bmin[i * 3 + c] = Math.min(v[a + c], Math.min(v[a + 3 + c], v[a + 6 + c]));
				bmax[i * 3 + c] = Math.max(v[a + c], Math.max(v[a + 3 + c], v[a + 6 + c]));
			}
			cx[i] = (bmin[i * 3] + bmax[i * 3]) / 2;
			cy[i] = (bmin[i * 3 + 1] + bmax[i * 3 + 1]) / 2;
			cz[i] = (bmin[i * 3 + 2] + bmax[i * 3 + 2]) / 2;
		}
		int cap = Math.max(16, n * 2);
		box = new float[cap * 6];
		first = new int[cap];
		count = new int[cap];
		right = new int[cap];
		build(0, n);
		box = Arrays.copyOf(box, nodes * 6);
		first = Arrays.copyOf(first, nodes);
		count = Arrays.copyOf(count, nodes);
		right = Arrays.copyOf(right, nodes);
		cx = cy = cz = bmin = bmax = null;
	}

	private int build(int from, int to)
	{
		int node = nodes++;
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		float[] c = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = from; i < to; ++i)
		{
			int t = order[i];
			for (int a = 0; a < 3; ++a)
			{
				b[a] = Math.min(b[a], bmin[t * 3 + a]);
				b[3 + a] = Math.max(b[3 + a], bmax[t * 3 + a]);
			}
			c[0] = Math.min(c[0], cx[t]);
			c[3] = Math.max(c[3], cx[t]);
			c[1] = Math.min(c[1], cy[t]);
			c[4] = Math.max(c[4], cy[t]);
			c[2] = Math.min(c[2], cz[t]);
			c[5] = Math.max(c[5], cz[t]);
		}
		System.arraycopy(b, 0, box, node * 6, 6);
		int n = to - from;
		if (n <= LEAF)
		{
			first[node] = from;
			count[node] = n;
			return node;
		}
		// binned surface area split along each axis
		float best = Float.MAX_VALUE;
		int bestAxis = -1;
		float bestPos = 0;
		int[] binCount = new int[BINS];
		float[] binBox = new float[BINS * 6];
		for (int axis = 0; axis < 3; ++axis)
		{
			float lo = c[axis], hi = c[3 + axis];
			if (hi - lo < 1e-3f)
			{
				continue;
			}
			float[] cen = axis == 0 ? cx : axis == 1 ? cy : cz;
			Arrays.fill(binCount, 0);
			for (int k = 0; k < BINS; ++k)
			{
				for (int a = 0; a < 3; ++a)
				{
					binBox[k * 6 + a] = Float.MAX_VALUE;
					binBox[k * 6 + 3 + a] = -Float.MAX_VALUE;
				}
			}
			float scale = BINS / (hi - lo);
			for (int i = from; i < to; ++i)
			{
				int t = order[i];
				int k = Math.min(BINS - 1, (int) ((cen[t] - lo) * scale));
				++binCount[k];
				for (int a = 0; a < 3; ++a)
				{
					binBox[k * 6 + a] = Math.min(binBox[k * 6 + a], bmin[t * 3 + a]);
					binBox[k * 6 + 3 + a] = Math.max(binBox[k * 6 + 3 + a], bmax[t * 3 + a]);
				}
			}
			float[] leftArea = new float[BINS];
			float[] g = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			int[] leftCount = new int[BINS];
			int sum = 0;
			for (int k = 0; k < BINS - 1; ++k)
			{
				grow(g, binBox, k);
				sum += binCount[k];
				leftCount[k] = sum;
				leftArea[k] = area(g);
			}
			Arrays.fill(g, 0, 3, Float.MAX_VALUE);
			Arrays.fill(g, 3, 6, -Float.MAX_VALUE);
			int rightCount = 0;
			for (int k = BINS - 1; k > 0; --k)
			{
				grow(g, binBox, k);
				rightCount += binCount[k];
				float cost = leftArea[k - 1] * leftCount[k - 1] + area(g) * rightCount;
				if (leftCount[k - 1] > 0 && rightCount > 0 && cost < best)
				{
					best = cost;
					bestAxis = axis;
					bestPos = lo + k / scale;
				}
			}
		}
		int mid;
		if (bestAxis < 0)
		{
			mid = (from + to) >>> 1;
		}
		else
		{
			float[] cen = bestAxis == 0 ? cx : bestAxis == 1 ? cy : cz;
			int i = from, j = to - 1;
			while (i <= j)
			{
				if (cen[order[i]] < bestPos)
				{
					++i;
				}
				else
				{
					int s = order[i];
					order[i] = order[j];
					order[j--] = s;
				}
			}
			mid = i;
			if (mid == from || mid == to)
			{
				mid = (from + to) >>> 1;
			}
		}
		count[node] = 0;
		first[node] = build(from, mid);
		right[node] = build(mid, to);
		return node;
	}

	private static void grow(float[] g, float[] bins, int k)
	{
		for (int a = 0; a < 3; ++a)
		{
			g[a] = Math.min(g[a], bins[k * 6 + a]);
			g[3 + a] = Math.max(g[3 + a], bins[k * 6 + 3 + a]);
		}
	}

	private static float area(float[] g)
	{
		float x = g[3] - g[0], y = g[4] - g[1], z = g[5] - g[2];
		return x < 0 ? 0 : x * y + y * z + z * x;
	}

	/** Per thread: the traversal's stack, and what closest found. */
	static final class Scratch
	{
		final int[] stack = new int[128];
		int tri;
		float t, u, v;
	}

	private boolean slab(int node, float ox, float oy, float oz, float ix, float iy, float iz, float tMin, float tMax)
	{
		float[] b = box;
		int k = node * 6;
		float t1 = (b[k] - ox) * ix, t2 = (b[k + 3] - ox) * ix;
		float lo = Math.min(t1, t2), hi = Math.max(t1, t2);
		t1 = (b[k + 1] - oy) * iy;
		t2 = (b[k + 4] - oy) * iy;
		lo = Math.max(lo, Math.min(t1, t2));
		hi = Math.min(hi, Math.max(t1, t2));
		t1 = (b[k + 2] - oz) * iz;
		t2 = (b[k + 5] - oz) * iz;
		lo = Math.max(lo, Math.min(t1, t2));
		hi = Math.min(hi, Math.max(t1, t2));
		return hi >= Math.max(lo, tMin) && lo <= tMax;
	}

	// t along the segment of triangle tri's hit, NaN for none; its barycentrics into s
	private float hit(int tri, float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, Scratch s)
	{
		float[] v = mesh.v;
		int a = tri * 9;
		float ax = v[a], ay = v[a + 1], az = v[a + 2];
		float e1x = v[a + 3] - ax, e1y = v[a + 4] - ay, e1z = v[a + 5] - az;
		float e2x = v[a + 6] - ax, e2y = v[a + 7] - ay, e2z = v[a + 8] - az;
		float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
		float det = e1x * px + e1y * py + e1z * pz;
		if (det > -1e-9f && det < 1e-9f)
		{
			return Float.NaN;
		}
		float inv = 1 / det;
		float tx = ox - ax, ty = oy - ay, tz = oz - az;
		float u = (tx * px + ty * py + tz * pz) * inv;
		if (u < 0 || u > 1)
		{
			return Float.NaN;
		}
		float qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
		float w = (dx * qx + dy * qy + dz * qz) * inv;
		if (w < 0 || u + w > 1)
		{
			return Float.NaN;
		}
		float t = (e2x * qx + e2y * qy + e2z * qz) * inv;
		if (t < tMin || t > tMax)
		{
			return Float.NaN;
		}
		if (mesh.tex[tri] >= 0 && !mesh.solidAt(tri, u, w))
		{
			return Float.NaN;
		}
		s.u = u;
		s.v = w;
		return t;
	}

	private static float inv(float d)
	{
		return d == 0 ? 1e30f : 1 / d;
	}

	/** Whether anything stops the segment; with mask 0 any triangle, else only one whose category is in it. */
	boolean occluded(float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, Scratch s)
	{
		float ix = inv(dx), iy = inv(dy), iz = inv(dz);
		int[] stack = s.stack;
		int sp = 0;
		stack[sp++] = 0;
		while (sp > 0)
		{
			int node = stack[--sp];
			if (!slab(node, ox, oy, oz, ix, iy, iz, tMin, tMax))
			{
				continue;
			}
			int c = count[node];
			if (c == 0)
			{
				stack[sp++] = right[node];
				stack[sp++] = first[node];
				continue;
			}
			for (int i = first[node], e = i + c; i < e; ++i)
			{
				if (!Float.isNaN(hit(order[i], ox, oy, oz, dx, dy, dz, tMin, tMax, s)))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** The categories of everything along the segment (a CardMesh mask), 0 for nothing. */
	int blockers(float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, Scratch s)
	{
		float ix = inv(dx), iy = inv(dy), iz = inv(dz);
		int[] stack = s.stack;
		int sp = 0;
		stack[sp++] = 0;
		int mask = 0;
		while (sp > 0)
		{
			int node = stack[--sp];
			if (!slab(node, ox, oy, oz, ix, iy, iz, tMin, tMax))
			{
				continue;
			}
			int c = count[node];
			if (c == 0)
			{
				stack[sp++] = right[node];
				stack[sp++] = first[node];
				continue;
			}
			for (int i = first[node], e = i + c; i < e; ++i)
			{
				if (!Float.isNaN(hit(order[i], ox, oy, oz, dx, dy, dz, tMin, tMax, s)))
				{
					mask |= mesh.cat[order[i]];
				}
			}
		}
		return mask;
	}

	/** The nearest hit along the segment: true with s.tri, s.t. */
	boolean closest(float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, Scratch s)
	{
		float ix = inv(dx), iy = inv(dy), iz = inv(dz);
		int[] stack = s.stack;
		int sp = 0;
		stack[sp++] = 0;
		float best = tMax;
		int tri = -1;
		while (sp > 0)
		{
			int node = stack[--sp];
			if (!slab(node, ox, oy, oz, ix, iy, iz, tMin, best))
			{
				continue;
			}
			int c = count[node];
			if (c == 0)
			{
				stack[sp++] = right[node];
				stack[sp++] = first[node];
				continue;
			}
			for (int i = first[node], e = i + c; i < e; ++i)
			{
				float t = hit(order[i], ox, oy, oz, dx, dy, dz, tMin, best, s);
				if (!Float.isNaN(t))
				{
					best = t;
					tri = order[i];
				}
			}
		}
		s.tri = tri;
		s.t = best;
		return tri >= 0;
	}
}
