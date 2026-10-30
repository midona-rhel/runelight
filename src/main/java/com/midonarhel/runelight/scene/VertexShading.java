package com.midonarhel.runelight.scene;

import com.midonarhel.runelight.*;

/**
 * Per-vertex shading data for the uploaders: smooth normals and the base (unshaded) face colors.
 *
 * Normals are octahedral-encoded into 16 bits, 8 per axis, and stored in the unused last short of the texture
 * attribute. 0 means "no vertex normal": the face is flat shaded and the fragment shader uses the triangle's
 * own normal instead. Encoded values are 1-255 per axis so a valid normal is never 0.
 */
public final class VertexShading
{
	// 117 HD's constants for approximately undoing vanilla's directional shading
	private static final int BASE_LIGHTEN = 10;
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	private static final float LIGHTNESS_MULTIPLIER = 3;

	public static boolean undoVanillaShading = true;

	private VertexShading()
	{
	}

	static int encodeNormal(float x, float y, float z)
	{
		float l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
		if (l1 < 1e-6f)
		{
			return 0;
		}
		float u = x / l1, v = y / l1;
		if (z < 0)
		{
			float ou = u;
			u = (1 - Math.abs(v)) * (ou >= 0 ? 1 : -1);
			v = (1 - Math.abs(ou)) * (v >= 0 ? 1 : -1);
		}
		int qu = Math.round((u * 0.5f + 0.5f) * 254) + 1;
		int qv = Math.round((v * 0.5f + 0.5f) * 254) + 1;
		return qu | qv << 8;
	}

	static float[] decodeNormal(int packed)
	{
		float u = ((packed & 0xff) - 1) / 254f * 2 - 1;
		float v = ((packed >> 8 & 0xff) - 1) / 254f * 2 - 1;
		float z = 1 - Math.abs(u) - Math.abs(v);
		if (z < 0)
		{
			float ou = u;
			u = (1 - Math.abs(v)) * (ou >= 0 ? 1 : -1);
			v = (1 - Math.abs(ou)) * (v >= 0 ? 1 : -1);
		}
		float l = (float) Math.sqrt(u * u + v * v + z * z);
		return new float[]{u / l, v / l, z / l};
	}

	/**
	 * Encoded normal of model vertex {@code v}, rotated by the model's orientation like its position is.
	 * Returns 0 when the model has no normal for that vertex.
	 */
	static int vertexNormal(float[] nx, float[] ny, float[] nz, int v, float orientSine, float orientCosine)
	{
		float x = nx[v], y = ny[v], z = nz[v];
		if (orientSine != 0 || orientCosine != 1)
		{
			float x0 = x;
			x = z * orientSine + x0 * orientCosine;
			z = z * orientCosine - x0 * orientSine;
		}
		return encodeNormal(x, y, z);
	}

	// The crease angle for faces the game marks flat: at each corner the normal averages the neighbouring faces
	// that meet this one within the angle, so low-poly round shapes (tree trunks, canopies, rocks) read as round
	// while real corners (crates, walls) stay sharp. Per corner, not per face: a canopy face whose one corner sits
	// on a sharp fold still shades smoothly at its other two.
	private static final float SMOOTH_COS = 0.643f; // cos(50 degrees): 5-sided and rounder prisms smooth, cubes don't
	// a neighbour on the same plane (the other triangle of a quad) bends no normal, so it doesn't make a face "rounded"
	private static final float COPLANAR_COS = 0.9995f;

	/**
	 * Normals for a face the game marks as flat shaded: at each corner, the area-weighted average of this face
	 * and the faces around that corner folded less than the crease angle from it (a face on the same plane with
	 * the opposite winding, double-sided foliage, counts flipped rather than cancelling). Where no neighbour is
	 * within the angle the corner keeps the face's own normal. Positions are in unrotated model space.
	 *
	 * @param face this face's index, in {@code n}'s face arrays (see {@link #computeNormals})
	 * @param out receives the three encoded normals
	 * @return true if any corner was smoothed, so the caller gives the face one colour over its rounded surface
	 */
	static boolean flatFaceNormals(Normals n, int face, int a, int b, int c, float orientSine, float orientCosine,
		int[] out)
	{
		float fl = n.faceArea[face];
		if (fl < 1e-6f)
		{
			out[0] = out[1] = out[2] = 0;
			return false;
		}
		float fx = n.faceX[face] / fl, fy = n.faceY[face] / fl, fz = n.faceZ[face] / fl;
		boolean smoothed = false;
		int[] idx = {a, b, c};
		for (int i = 0; i < 3; ++i)
		{
			int v = idx[i];
			float sx = n.faceX[face], sy = n.faceY[face], sz = n.faceZ[face];
			for (int k = n.adjacencyStart[v], end = n.adjacencyStart[v + 1]; k < end; ++k)
			{
				int g = n.adjacency[k];
				if (g == face)
				{
					continue;
				}
				float gl = n.faceArea[g];
				if (gl < 1e-6f)
				{
					continue;
				}
				float gx = n.faceX[g], gy = n.faceY[g], gz = n.faceZ[g];
				float cos = (fx * gx + fy * gy + fz * gz) / gl;
				if (cos > SMOOTH_COS)
				{
					sx += gx;
					sy += gy;
					sz += gz;
					smoothed |= cos < COPLANAR_COS;
				}
				else if (cos < -SMOOTH_COS)
				{
					sx -= gx;
					sy -= gy;
					sz -= gz;
					smoothed |= cos > -COPLANAR_COS;
				}
			}
			out[i] = rotatedNormal(sx, sy, sz, orientSine, orientCosine);
		}
		return smoothed;
	}

	private static int rotatedNormal(float x, float y, float z, float orientSine, float orientCosine)
	{
		if (orientSine != 0 || orientCosine != 1)
		{
			float x0 = x;
			x = z * orientSine + x0 * orientCosine;
			z = z * orientCosine - x0 * orientSine;
		}
		return encodeNormal(x, y, z);
	}

	/**
	 * Normals computed from the model's own geometry, into {@code n}: per face its unnormalised normal (the length
	 * is twice its area, the weight), per vertex the area-weighted sum of the visible faces around it (the smooth
	 * normal for faces the game shades smoothly, {@link #vertexNormal}), and which faces meet at each vertex (for
	 * {@link #flatFaceNormals}). Doesn't depend on the game providing normals, which it doesn't always do, and
	 * which 117 HD found to be inaccurate anyway. The sign follows the face winding, which is consistent within a
	 * model; the fragment shader aligns it with the triangle's facing regardless.
	 */
	static void computeNormals(int vertexCount, float[] vx, float[] vy, float[] vz,
		int faceCount, int[] i1, int[] i2, int[] i3, int[] colors3, Normals n)
	{
		n.ensure(vertexCount, faceCount);
		float[] nx = n.x, ny = n.y, nz = n.z;
		int[] start = n.adjacencyStart;
		java.util.Arrays.fill(nx, 0, vertexCount, 0);
		java.util.Arrays.fill(ny, 0, vertexCount, 0);
		java.util.Arrays.fill(nz, 0, vertexCount, 0);
		java.util.Arrays.fill(start, 0, vertexCount + 1, 0);
		for (int f = 0; f < faceCount; ++f)
		{
			if (colors3[f] == -2)
			{
				n.faceArea[f] = 0;
				continue;
			}
			int a = i1[f], b = i2[f], c = i3[f];
			float ux = vx[b] - vx[a], uy = vy[b] - vy[a], uz = vz[b] - vz[a];
			float wx = vx[c] - vx[a], wy = vy[c] - vy[a], wz = vz[c] - vz[a];
			// unnormalized cross product: its length weights the face by area
			float fx = uy * wz - uz * wy, fy = uz * wx - ux * wz, fz = ux * wy - uy * wx;
			n.faceX[f] = fx;
			n.faceY[f] = fy;
			n.faceZ[f] = fz;
			n.faceArea[f] = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
			nx[a] += fx;
			ny[a] += fy;
			nz[a] += fz;
			nx[b] += fx;
			ny[b] += fy;
			nz[b] += fz;
			nx[c] += fx;
			ny[c] += fy;
			nz[c] += fz;
			// counting sort of the faces by vertex: counts first, shifted so start[v + 1] ends up as v's first slot
			++start[a + 1];
			++start[b + 1];
			++start[c + 1];
		}
		for (int v = 0; v < vertexCount; ++v)
		{
			start[v + 1] += start[v];
		}
		int[] adjacency = n.adjacency;
		int[] fill = n.adjacencyFill;
		System.arraycopy(start, 0, fill, 0, vertexCount);
		for (int f = 0; f < faceCount; ++f)
		{
			if (colors3[f] == -2)
			{
				continue;
			}
			adjacency[fill[i1[f]]++] = f;
			adjacency[fill[i2[f]]++] = f;
			adjacency[fill[i3[f]]++] = f;
		}
	}

	/**
	 * Scratch for {@link #computeNormals}, grown as needed: per vertex the smooth normal (x, y, z), per face its
	 * unnormalised normal and that normal's length, and the faces around each vertex as a compressed list
	 * ({@code adjacency[adjacencyStart[v] .. adjacencyStart[v + 1])}).
	 */
	static final class Normals
	{
		float[] x = new float[0], y = new float[0], z = new float[0];
		float[] faceX = new float[0], faceY = new float[0], faceZ = new float[0], faceArea = new float[0];
		int[] adjacencyStart = new int[1], adjacencyFill = new int[0], adjacency = new int[0];

		void ensure(int vertices)
		{
			if (x.length < vertices)
			{
				int size = Math.max(vertices, x.length * 2);
				x = new float[size];
				y = new float[size];
				z = new float[size];
				adjacencyStart = new int[size + 1];
				adjacencyFill = new int[size];
			}
		}

		void ensure(int vertices, int faces)
		{
			ensure(vertices);
			if (faceArea.length < faces)
			{
				int size = Math.max(faces, faceArea.length * 2);
				faceX = new float[size];
				faceY = new float[size];
				faceZ = new float[size];
				faceArea = new float[size];
				adjacency = new int[size * 3];
			}
		}
	}

	/**
	 * Vanilla bakes lighting from a fixed direction into vertex colors. Like 117 HD's default shading mode, keep
	 * the vanilla colors (and their gradients) but brighten what that light darkened, so our own lighting does
	 * the directional shading. Untextured faces only: textured faces store a bare lightness that the fragment
	 * shader multiplies the texture by.
	 *
	 * @param nx unrotated model vertex normals (vanilla lights in model space), or null
	 */
	static int baseColor(int color, int face, int vertex, float[] nx, float[] ny, float[] nz)
	{
		if (!undoVanillaShading || nx == null)
		{
			return color;
		}
		// brighten what vanilla darkened, assuming its light direction (1, 1, 1); capped, as 117 HD does,
		// because models are not all lit the same way and full correction overshoots
		int s = color >> 7 & 0x7;
		int l = color & 0x7f;
		float adjust = BASE_LIGHTEN - l + (l < IGNORE_LOW_LIGHTNESS ? 0 : (l - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);
		float x = nx[vertex], y = ny[vertex], z = nz[vertex];
		float len = x * x + y * y + z * z;
		if (len > 0)
		{
			float dot = (x + y + z) * 0.57735026f / (float) Math.sqrt(len);
			if (dot > 0)
			{
				l += (int) (dot * adjust);
			}
		}
		l = Math.min(l, maxBrightness(s));
		return (color & 0xfc00) | (s << 7) | l;
	}

	private static int maxBrightness(int s)
	{
		switch (s)
		{
			case 0:
				return 127;
			case 1:
				return 61;
			case 2:
				return 59;
			case 3:
				return 57;
			case 4:
			case 5:
				return 56;
			default:
				return 55;
		}
	}
}
