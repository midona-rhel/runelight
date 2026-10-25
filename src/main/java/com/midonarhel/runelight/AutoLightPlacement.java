package com.midonarhel.runelight;

import net.runelite.api.Perspective;

/**
 * Where an automatic light goes on an object, from its model: the middle of the faces colored like a flame, or
 * where there are none the middle of the upper part of its bounds.
 */
final class AutoLightPlacement
{
	/**
	 * A place in the model: game units from its origin, y down.
	 */
	static final class Spot
	{
		float x, y, z;
		// found by the flame colors, not the bounds
		boolean flame;
	}

	// colors are the game's 16 bit HSL: 6 bits of hue, 3 of saturation, 7 of lightness
	private static final int FLAME_MIN_HUE = 3, FLAME_MAX_HUE = 11;
	private static final int FLAME_MIN_SATURATION = 5;
	private static final int FLAME_MIN_LIGHTNESS = 56;
	// how far down from the top of the bounds, of their height
	private static final float UPPER_PART = 0.25f;
	// how far from the wall a wall decoration's light goes when it has no flame to be at
	private static final float WALL_CLEARANCE = 16;
	private static final float JAU_TO_RAD = (float) (Math.PI * 2 / 2048);

	private AutoLightPlacement()
	{
	}

	/**
	 * Whether a face of this HSL color is orange or yellow, saturated and bright: not the browns of wood, nor
	 * the grays and reds of stone and cloth.
	 */
	static boolean isFlame(int hsl)
	{
		int hue = hsl >> 10 & 63, saturation = hsl >> 7 & 7, lightness = hsl & 127;
		return hue >= FLAME_MIN_HUE && hue <= FLAME_MAX_HUE && saturation >= FLAME_MIN_SATURATION
			&& lightness >= FLAME_MIN_LIGHTNESS;
	}

	/**
	 * @return false for a model with nothing to put a light in
	 */
	static boolean locate(ModelData m, Spot out)
	{
		if (m.vertexCount == 0 || m.faceCount == 0)
		{
			return false;
		}
		return flames(m, out) || bounds(m, out);
	}

	// the colors the faces were made with when the model has them, else as lit: lighting keeps hue and saturation
	private static int color(ModelData m, int face)
	{
		return m.unlitColors != null ? m.unlitColors[face] & 0xFFFF : m.colors1[face];
	}

	private static boolean flames(ModelData m, Spot out)
	{
		double[] sum = new double[3];
		int corners = 0;
		for (int face = 0; face < m.faceCount; ++face)
		{
			// -2 is a face that is not drawn, and a textured face has no color of its own
			if (m.colors3[face] == -2 || m.textures != null && m.textures[face] != -1 || !isFlame(color(m, face)))
			{
				continue;
			}
			add(m, m.indices1[face], sum);
			add(m, m.indices2[face], sum);
			add(m, m.indices3[face], sum);
			corners += 3;
		}
		if (corners == 0)
		{
			return false;
		}
		out.x = (float) (sum[0] / corners);
		out.y = (float) (sum[1] / corners);
		out.z = (float) (sum[2] / corners);
		out.flame = true;
		return true;
	}

	private static void add(ModelData m, int vertex, double[] sum)
	{
		sum[0] += m.verticesX[vertex];
		sum[1] += m.verticesY[vertex];
		sum[2] += m.verticesZ[vertex];
	}

	private static boolean bounds(ModelData m, Spot out)
	{
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		// y is down, so the top has the least
		float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
		for (int v = 0; v < m.vertexCount; ++v)
		{
			minX = Math.min(minX, m.verticesX[v]);
			maxX = Math.max(maxX, m.verticesX[v]);
			minZ = Math.min(minZ, m.verticesZ[v]);
			maxZ = Math.max(maxZ, m.verticesZ[v]);
			top = Math.min(top, m.verticesY[v]);
			bottom = Math.max(bottom, m.verticesY[v]);
		}
		out.x = (minX + maxX) / 2;
		out.y = top + (bottom - top) * UPPER_PART;
		out.z = (minZ + maxZ) / 2;
		out.flame = false;
		return true;
	}

	/**
	 * Where a point of the model is across the ground once the model is turned, as SceneUploader turns it.
	 *
	 * @param orientation game units, 2048 per turn
	 * @param xz the point's x and z from the model's origin
	 */
	static void turn(Spot spot, int orientation, float[] xz)
	{
		float sin = Perspective.SINEF[orientation], cos = Perspective.COSINEF[orientation];
		xz[0] = spot.z * sin + spot.x * cos;
		xz[1] = spot.z * cos - spot.x * sin;
	}

	/**
	 * How far to move the light of a wall decoration from the wall, out into its tile: a decoration's model is
	 * against the wall, and its bounds are in it. The config says which wall (0 west, then clockwise) and, for the
	 * types 6 to 8, that it sits on a corner, an eighth of a turn on.
	 *
	 * @param xz the move in x and z
	 */
	static void awayFromWall(int config, float[] xz)
	{
		int type = config & 0x3F;
		float angle = ((config >> 6 & 3) * 512 + (type >= 6 && type <= 8 ? 256 : 0)) * JAU_TO_RAD;
		xz[0] = WALL_CLEARANCE * (float) Math.cos(angle);
		xz[1] = -WALL_CLEARANCE * (float) Math.sin(angle);
	}
}
