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
	// replace colors with the model's unlit colors instead; flattens color gradients (117 HD's "Experimental")
	static boolean unlitColors = false;

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
	static int vertexNormal(int[] nx, int[] ny, int[] nz, int v, float orientSine, float orientCosine)
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

	static boolean hasNormals(int[] nx, int[] ny, int[] nz)
	{
		return nx != null && ny != null && nz != null;
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
	static int baseColor(int color, int face, int vertex, short[] unlit, int[] nx, int[] ny, int[] nz)
	{
		if (unlitColors && unlit != null)
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
