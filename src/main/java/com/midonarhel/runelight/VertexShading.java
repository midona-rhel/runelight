package com.midonarhel.runelight;

/**
 * Per-vertex shading data for the uploaders: smooth normals and the base (unshaded) face colors.
 *
 * Normals are octahedral-encoded into 16 bits, 8 per axis, and stored in the unused last short of the texture
 * attribute. 0 means "no vertex normal": the face is flat shaded and the fragment shader uses the triangle's
 * own normal instead. Encoded values are 1-255 per axis so a valid normal is never 0.
 */
final class VertexShading
{
	// 117 HD's constants for approximately undoing vanilla's directional shading
	private static final int BASE_LIGHTEN = 10;
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	private static final float LIGHTNESS_MULTIPLIER = 3;

	static boolean undoVanillaShading = true;
	// the shading mode's (ShadingMode): untextured faces take the model's unlit colors
	static boolean unlitFaceColors;
	// 117 HD's option: flat shaded faces take the game's normals too, not their own
	static boolean preserveVanillaNormals;

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

	// Flat-shaded faces whose vertex normals are within this angle of the face normal are shaded smoothly, so
	// low-poly round shapes (tree trunks, rocks) read as round while real corners (crates, walls) stay sharp.
	private static final float SMOOTH_COS = 0.643f; // cos(50 degrees): 5-sided and rounder prisms smooth, cubes don't

	/**
	 * Normals for a face the game marks as flat shaded: the smooth vertex normal where the fold is gentle,
	 * otherwise the face's own normal. Positions and normals are in unrotated model space.
	 *
	 * @param out receives the three encoded normals
	 * @return true if any vertex was smoothed
	 */
	static boolean flatFaceNormals(float[] vx, float[] vy, float[] vz, float[] nx, float[] ny, float[] nz,
		int a, int b, int c, float orientSine, float orientCosine, int[] out)
	{
		float ux = vx[b] - vx[a], uy = vy[b] - vy[a], uz = vz[b] - vz[a];
		float wx = vx[c] - vx[a], wy = vy[c] - vy[a], wz = vz[c] - vz[a];
		float fx = uy * wz - uz * wy, fy = uz * wx - ux * wz, fz = ux * wy - uy * wx;
		float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (fl < 1e-6f)
		{
			out[0] = out[1] = out[2] = 0;
			return false;
		}
		fx /= fl;
		fy /= fl;
		fz /= fl;
		// winding is not reliable for the sign; agree with the vertex normals
		float sx = nx[a] + nx[b] + nx[c], sy = ny[a] + ny[b] + ny[c], sz = nz[a] + nz[b] + nz[c];
		if (fx * sx + fy * sy + fz * sz < 0)
		{
			fx = -fx;
			fy = -fy;
			fz = -fz;
		}

		// all three corners must be gentle, or a box side would get a gradient from one leaning corner normal
		int[] idx = {a, b, c};
		for (int v : idx)
		{
			float x = nx[v], y = ny[v], z = nz[v];
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			if (l < 1e-6f || (x * fx + y * fy + z * fz) / l <= SMOOTH_COS)
			{
				int face = rotatedNormal(fx, fy, fz, orientSine, orientCosine);
				out[0] = out[1] = out[2] = face;
				return false;
			}
		}
		for (int i = 0; i < 3; ++i)
		{
			out[i] = vertexNormal(nx, ny, nz, idx[i], orientSine, orientCosine);
		}
		return true;
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
	 * Smooth vertex normals: the game's own where it gives one, as 117 HD uses them (the normals the vanilla shading
	 * was baked with, which keep foliage evenly lit; 117 HD only finds them inaccurate on flat shaded faces, which
	 * take their own normal: flatFaceNormals). Elsewhere the area-weighted average of the normals of the visible
	 * faces around the vertex. The sign follows the face winding, which is consistent within a model; the fragment
	 * shader aligns it with the triangle's facing regardless.
	 *
	 * @param gx the game's vertex normals, or null when the model has none
	 */
	static void computeNormals(int vertexCount, float[] vx, float[] vy, float[] vz, int[] gx, int[] gy, int[] gz,
		int faceCount, int[] i1, int[] i2, int[] i3, int[] colors3, float[] nx, float[] ny, float[] nz)
	{
		java.util.Arrays.fill(nx, 0, vertexCount, 0);
		java.util.Arrays.fill(ny, 0, vertexCount, 0);
		java.util.Arrays.fill(nz, 0, vertexCount, 0);
		for (int f = 0; f < faceCount; ++f)
		{
			if (colors3[f] == -2)
			{
				continue;
			}
			int a = i1[f], b = i2[f], c = i3[f];
			float ux = vx[b] - vx[a], uy = vy[b] - vy[a], uz = vz[b] - vz[a];
			float wx = vx[c] - vx[a], wy = vy[c] - vy[a], wz = vz[c] - vz[a];
			// unnormalized cross product: its length weights the face by area
			float fx = uy * wz - uz * wy, fy = uz * wx - ux * wz, fz = ux * wy - uy * wx;
			nx[a] += fx;
			ny[a] += fy;
			nz[a] += fz;
			nx[b] += fx;
			ny[b] += fy;
			nz[b] += fz;
			nx[c] += fx;
			ny[c] += fy;
			nz[c] += fz;
		}
		if (gx == null || gy == null || gz == null)
		{
			return;
		}
		int n = Math.min(vertexCount, Math.min(gx.length, Math.min(gy.length, gz.length)));
		for (int v = 0; v < n; ++v)
		{
			int x = gx[v], y = gy[v], z = gz[v];
			if (x != 0 || y != 0 || z != 0)
			{
				nx[v] = x;
				ny[v] = y;
				nz[v] = z;
			}
		}
	}

	/**
	 * Scratch arrays for computed normals, grown as needed.
	 */
	static final class Normals
	{
		float[] x = new float[0], y = new float[0], z = new float[0];

		void ensure(int n)
		{
			if (x.length < n)
			{
				int size = Math.max(n, x.length * 2);
				x = new float[size];
				y = new float[size];
				z = new float[size];
			}
		}
	}

	/**
	 * Vanilla bakes lighting from a fixed direction into vertex colors. Like 117 HD's default shading mode, keep
	 * the vanilla colors (and their gradients) but brighten what that light darkened, so our own lighting does
	 * the directional shading. Untextured faces only: textured faces store a bare lightness that the fragment
	 * shader multiplies the texture by.
	 *
	 * @param unlit the model's unlit face colors, or null
	 * @param nx unrotated model vertex normals (vanilla lights in model space), or null
	 */
	static int baseColor(int color, int face, int vertex, short[] unlit, float[] nx, float[] ny, float[] nz)
	{
		if (unlitFaceColors && unlit != null)
		{
			return unlit[face] & 0xffff;
		}
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
