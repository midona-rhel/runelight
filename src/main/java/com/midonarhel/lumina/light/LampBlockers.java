package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.scene.Zone;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/** Wall and roof geometry in a stackless bounding-volume hierarchy. No lighting is baked. */
public final class LampBlockers
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LampBlockers.class);
	private int buffer, texture;
	private float[][] seen;
	private int sizeX, sizeZ;

	public static boolean blocks(int objectType)
	{
		return objectType >= 0 && objectType <= 3 || objectType == 9 || objectType >= 12 && objectType <= 21;
	}

	/** Copy positions from the opaque stream while its owning uploader still has it. */
	public static void capture(IntBuffer stream, int from, List<Float> out)
	{
		for (int at = from; at + 17 < stream.position(); at += 18)
		{
			for (int k = 0; k < 3; ++k)
			{
				int a = stream.get(at + k * 6), b = stream.get(at + k * 6 + 1);
				out.add((float) (short) a);
				out.add((float) (short) (a >> 16));
				out.add((float) (short) b);
			}
		}
	}

	public void update(LuminaPlugin.SceneContext scene)
	{
		int nx = scene != null ? scene.zones.length : 0;
		int nz = nx > 0 ? scene.zones[0].length : 0;
		boolean changed = seen == null || sizeX != nx || sizeZ != nz;
		if (changed)
		{
			seen = new float[nx * nz][];
			sizeX = nx;
			sizeZ = nz;
		}
		for (int x = 0; x < nx; ++x)
		{
			for (int z = 0; z < nz; ++z)
			{
				Zone zone = scene.zones[x][z];
				float[] data = zone != null ? zone.lampBlockers : null;
				int index = x * nz + z;
				// Object changes that leave structural geometry identical need no rebuild.
				changed |= seen[index] != data && !java.util.Arrays.equals(seen[index], data);
				seen[index] = data;
			}
		}
		if (!changed)
		{
			return;
		}
		long buildStart = System.nanoTime();
		List<float[]> triangles = new ArrayList<>();
		int base = -LuminaPlugin.SCENE_OFFSET * 128;
		for (int x = 0; x < nx; ++x)
		{
			for (int z = 0; z < nz; ++z)
			{
				float[] data = seen[x * nz + z];
				if (data == null) continue;
				for (int i = 0; i < data.length; i += 9)
				{
					float[] tri = new float[9];
					for (int k = 0; k < 9; k += 3)
					{
						tri[k] = data[i + k] + base + x * 1024;
						tri[k + 1] = data[i + k + 1];
						tri[k + 2] = data[i + k + 2] + base + z * 1024;
					}
					triangles.add(tri);
				}
			}
		}
		float[] packed = pack(triangles);
		long uploadStart = System.nanoTime();
		FloatBuffer upload = BufferUtils.createFloatBuffer(packed.length);
		upload.put(packed).flip();
		if (buffer == 0) buffer = glGenBuffers();
		if (texture == 0) texture = glGenTextures();
		glBindBuffer(GL_TEXTURE_BUFFER, buffer);
		glBufferData(GL_TEXTURE_BUFFER, upload, GL_STATIC_DRAW);
		glActiveTexture(GL_TEXTURE0 + LuminaPlugin.LAMP_CUBE_UNIT);
		glBindTexture(GL_TEXTURE_BUFFER, texture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
		log.debug("Structural point blockers: {} triangles, {} KiB; CPU prepare {} ms, upload call {} ms",
			triangles.size(), packed.length * Float.BYTES / 1024.0,
			(uploadStart - buildStart) / 1e6, (System.nanoTime() - uploadStart) / 1e6);
	}

	/** Two bounds texels per node. Branches descend; misses jump past the entire subtree.
	 * Leaves contain at most four primitives with precomputed plane/barycentric rows.
	 * Geometry itself determines the hierarchy's bounds.
	 */
	static float[] pack(List<float[]> triangles)
	{
		List<float[]> ordered = StructuralRectangles.simplify(triangles);
		Packed packed = new Packed(ordered.size() * 28 + 8);
		packNode(ordered, 0, ordered.size(), packed);
		return java.util.Arrays.copyOf(packed.values, packed.size());
	}

	/** Primitive staging avoids boxing every float while rebuilding a scene. */
	private static final class Packed
	{
		final float[] values;
		int position;
		Packed(int capacity) { values = new float[capacity]; }
		void add(float value) { values[position++] = value; }
		void set(int index, float value) { values[index] = value; }
		int size() { return position; }
	}

	private static void packNode(List<float[]> triangles, int from, int to, Packed out)
	{
		float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
		float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		float[] centersLo = lo.clone(), centersHi = hi.clone();
		for (int i = from; i < to; ++i)
		{
			float[] t = triangles.get(i);
			for (int axis = 0; axis < 3; ++axis)
			{
				for (int v = 0; v < 9; v += 3)
				{
					lo[axis] = Math.min(lo[axis], t[v + axis]);
					hi[axis] = Math.max(hi[axis], t[v + axis]);
				}
				if (t.length == 10)
				{
					float fourth = t[axis + 3] + t[axis + 6] - t[axis];
					lo[axis] = Math.min(lo[axis], fourth);
					hi[axis] = Math.max(hi[axis], fourth);
				}
				float c = center(t, axis);
				centersLo[axis] = Math.min(centersLo[axis], c);
				centersHi[axis] = Math.max(centersHi[axis], c);
			}
		}
		int node = out.size();
		for (int i = 0; i < 8; ++i) out.add(0f);
		for (int axis = 0; axis < 3; ++axis)
		{
			out.set(node + axis, from == to ? 0f : lo[axis] - 0.01f);
			out.set(node + 4 + axis, from == to ? 0f : hi[axis] + 0.01f);
		}
		if (to - from <= 4)
		{
			int metadata = to - from;
			for (int i = from; i < to; ++i)
			{
				float[] triangle = triangles.get(i);
				if (triangle.length == 10) metadata |= 8 << (i - from);
				packPrimitive(triangle, out);
			}
			out.set(node + 7, (float) metadata);
		}
		else
		{
			int widest = 0;
			for (int axis = 1; axis < 3; ++axis)
				if (centersHi[axis] - centersLo[axis] > centersHi[widest] - centersLo[widest]) widest = axis;
			final int splitAxis = widest;
			triangles.subList(from, to).sort(java.util.Comparator.comparingDouble(t -> center(t, splitAxis)));
			int mid = (from + to) >>> 1;
			packNode(triangles, from, mid, out);
			packNode(triangles, mid, to, out);
		}
		out.set(node + 3, (float) (out.size() / 4));
	}

	/** Precompute the plane and barycentric-coordinate rows instead of crossing edges per pixel. */
	private static void packPrimitive(float[] triangle, Packed out)
	{
		double[] edgeU = new double[3], edgeV = new double[3];
		for (int axis = 0; axis < 3; ++axis)
		{
			edgeU[axis] = triangle[axis + 3] - triangle[axis];
			edgeV[axis] = triangle[axis + 6] - triangle[axis];
		}
		double[] normal = cross(edgeU, edgeV);
		double square = normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2];
		double scale = Math.max(Math.abs(normal[0]), Math.max(Math.abs(normal[1]), Math.abs(normal[2])));
		packRow(cross(edgeV, normal), Math.max(square, 1e-30), triangle, out);
		packRow(cross(normal, edgeU), Math.max(square, 1e-30), triangle, out);
		packRow(normal, Math.max(scale, 1e-30), triangle, out);
	}

	private static double[] cross(double[] a, double[] b)
	{
		return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static void packRow(double[] row, double divisor, float[] origin, Packed out)
	{
		double offset = 0;
		for (int axis = 0; axis < 3; ++axis)
		{
			double value = row[axis] / divisor;
			out.add((float) value);
			offset -= value * origin[axis];
		}
		out.add((float) offset);
	}

	private static float center(float[] triangle, int axis)
	{
		return triangle.length == 10 ? (triangle[axis + 3] + triangle[axis + 6]) * 0.5f
			: (triangle[axis] + triangle[axis + 3] + triangle[axis + 6]) / 3f;
	}

	public void bind()
	{
		glActiveTexture(GL_TEXTURE0 + LuminaPlugin.LAMP_CUBE_UNIT);
		glBindSampler(LuminaPlugin.LAMP_CUBE_UNIT, 0);
		glBindTexture(GL_TEXTURE_BUFFER, texture);
	}

	public void free()
	{
		if (buffer != 0) glDeleteBuffers(buffer);
		if (texture != 0) glDeleteTextures(texture);
		buffer = texture = 0;
		seen = null;
	}
}
