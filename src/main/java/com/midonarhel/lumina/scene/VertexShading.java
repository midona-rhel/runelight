package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

/**
 * Per-vertex shading data for the model uploaders, by 117 HD's code (docs/117hd-model-shading.md): the normals (the
 * game's vertex normals, or the face's own for flat faces) and the face colors with vanilla's baked shading undone.
 *
 * Normals are octahedral-encoded into 16 bits, 8 per axis, and stored in the unused last short of the texture
 * attribute. 0 means "no vertex normal": the fragment shader uses the triangle's own normal instead. Encoded values
 * are 1-255 per axis so a valid normal is never 0.
 */
public final class VertexShading
{
	// 117 HD's constants for approximately undoing vanilla's directional shading
	private static final int BASE_LIGHTEN = 10;
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	private static final float LIGHTNESS_MULTIPLIER = 3;

	// 117 HD's colour for textured faces (zone SceneUploader 1722-1728, 2289-2290): lightness 90, no hue or
	// saturation, not undone. The texture is multiplied by it; the game's lit lightness would keep vanilla's shading.
	static final int TEXTURED_COLOR = 90;

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

	/** The encoded normal pointing the other way; 0 stays 0. */
	static int negateNormal(int packed)
	{
		if (packed == 0)
		{
			return 0;
		}
		float[] n = decodeNormal(packed);
		return encodeNormal(-n[0], -n[1], -n[2]);
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

	// what faceNormals put in its output
	static final int FACE = 0;        // the face's own normal, by the flat rule: already in rotated space
	static final int VERTEX = 1;      // the game's three vertex normals, in model space: rotate them after the undo
	static final int ZERO_VERTEX = 2; // the face's own normal, because the game's three were all zero

	/**
	 * A face's three corner normals as 117 HD makes them (zone SceneUploader, uploadStaticModel 1847-1876 and
	 * uploadTempModel 2320-2350): the face's own normal for every corner when the model has no normals, its override
	 * says flat, or the game marks the face flat (color3 == -1; 117 HD's preserveVanillaNormals is off by default);
	 * otherwise the game's vertex normals ({@code Model.getVertexNormalsX/Y/Z}, sums of the smooth faces' normals,
	 * model space), or the face's own normal again when all three are zero. A face that has one or two zero vertex
	 * normals keeps those zeros (the shader's fallback, frag.glsl). No smoothing of flat faces: 117 HD has none.
	 *
	 * @param out nine ints, x y z per corner, as 117 HD's (the face normal is unit length times 2048)
	 * @param nx the game's vertex normals, or null when the model has none
	 * @param x1 the corners' positions, rotated by the object's orientation as 117 HD's are
	 * @return {@link #FACE}, {@link #VERTEX} or {@link #ZERO_VERTEX}
	 */
	static int faceNormals(int[] out, int[] nx, int[] ny, int[] nz, boolean flat, int a, int b, int c,
		float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3)
	{
		if (flat || nx == null || ny == null || nz == null)
		{
			faceNormal(out, x1, y1, z1, x2, y2, z2, x3, y3, z3);
			return FACE;
		}
		boolean zero = (out[0] = nx[a]) == 0;
		zero &= (out[1] = ny[a]) == 0;
		zero &= (out[2] = nz[a]) == 0;
		zero &= (out[3] = nx[b]) == 0;
		zero &= (out[4] = ny[b]) == 0;
		zero &= (out[5] = nz[b]) == 0;
		zero &= (out[6] = nx[c]) == 0;
		zero &= (out[7] = ny[c]) == 0;
		zero &= (out[8] = nz[c]) == 0;
		if (zero)
		{
			faceNormal(out, x1, y1, z1, x2, y2, z2, x3, y3, z3);
			return ZERO_VERTEX;
		}
		return VERTEX;
	}

	/** 117 HD's face normal (zone SceneUploader.calculateFaceNormal): cross(B - A, C - A), unit length times 2048. */
	static void faceNormal(int[] out, float x1, float y1, float z1, float x2, float y2, float z2,
		float x3, float y3, float z3)
	{
		float e0x = x2 - x1, e0y = y2 - y1, e0z = z2 - z1;
		float e1x = x3 - x1, e1y = y3 - y1, e1z = z3 - z1;
		float nx = e0y * e1z - e0z * e1y;
		float ny = e0z * e1x - e0x * e1z;
		float nz = e0x * e1y - e0y * e1x;
		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx /= length;
		ny /= length;
		nz /= length;
		out[0] = out[3] = out[6] = (int) (nx * 2048);
		out[1] = out[4] = out[7] = (int) (ny * 2048);
		out[2] = out[5] = out[8] = (int) (nz * 2048);
	}

	/** 117 HD's rotateNormals: the three corner normals about y, by the game's 65536-scaled sine and cosine. */
	static void rotateNormals(int[] normals, int orientSin, int orientCos)
	{
		for (int i = 0; i < 9; i += 3)
		{
			int x = normals[i], z = normals[i + 2];
			normals[i] = z * orientSin + x * orientCos >> 16;
			normals[i + 2] = z * orientCos - x * orientSin >> 16;
		}
	}

	/**
	 * 117 HD's upwardsNormals (scene_frag.glsl 359): the face is lit as if it faced straight up, (0, -1, 0) in the
	 * game's y-down space, on all three corners. 117 HD swaps the normal in the shader, after undoing vanilla
	 * shading with the real one; here it replaces the uploaded normal at the same point.
	 */
	static void upwardsNormals(int[] normals)
	{
		for (int i = 0; i < 9; i += 3)
		{
			normals[i] = 0;
			normals[i + 1] = -2048;
			normals[i + 2] = 0;
		}
	}

	/** Corner {@code i}'s normal from {@link #faceNormals}, encoded; 0 for a zero normal. */
	static int encodeNormal(int[] normals, int i)
	{
		return encodeNormal(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
	}

	/**
	 * Vanilla bakes lighting from a fixed direction into vertex colors. Like 117 HD's default shading mode, keep
	 * the vanilla colors (and their gradients) but brighten what that light darkened, so our own lighting does
	 * the directional shading: 117 HD's undoVanillaShading (zone SceneUploader 2751-2793), for one corner of an
	 * untextured face with that corner's normal from {@link #faceNormals} before rotation: the game's vertex normal
	 * in model space, or the face's own normal (rotated space) for a flat face.
	 */
	static int undoVanillaShading(int color, int[] normals, int i)
	{
		return undoVanillaShading(color, normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
	}

	static int undoVanillaShading(int color, int nx, int ny, int nz)
	{
		if (!undoVanillaShading)
		{
			return color;
		}
		int s = color >> 7 & 0x7;
		int l = color & 0x7f;
		// brighten what vanilla darkened, assuming its light direction (1, 1, 1); capped, as 117 HD does,
		// because models are not all lit the same way and full correction overshoots
		float adjust = BASE_LIGHTEN - l + (l < IGNORE_LOW_LIGHTNESS ? 0f : (l - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);
		float len = nx * nx + ny * ny + nz * nz;
		if (len > 0f)
		{
			float dot = (nx + ny + nz) * 0.57735026f / (float) Math.sqrt(len);
			if (dot > 0f)
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
