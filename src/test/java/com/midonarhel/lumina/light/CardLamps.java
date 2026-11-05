package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;

/**
 * For the occlusion scorecard (harness/Scorecard): a lamp's plain numbers, and its strength set from outside the
 * package. A lamp is isolated by setting every other lamp's intensity to 0: it keeps its slots, its bake and its place
 * in every list, so any method that takes a lamp's light from PointLight.intensity is measured as it runs.
 */
public final class CardLamps
{
	public static final int X = 0, Y = 1, Z = 2, RADIUS = 3, OX = 4, OZ = 5, PLANE = 6, R = 7, G = 8, B = 9, FLOOR_Y = 10, CEIL_Y = 11;
	public static final int FIELDS = 12;

	private CardLamps()
	{
	}

	/** The fields above: position, reach, the origin its lines start from, its storey, its colour times strength. */
	public static float[] describe(LuminaPlugin plugin, PointLight l)
	{
		float[] o = new float[2];
		o[0] = l.ox;
		o[1] = l.oz;
		if (plugin.lampWalls != null)
		{
			plugin.lampWalls.origin(l.plane, l.ox, l.oz, o);
		}
		float s = l.intensity * l.mapFade;
		return new float[]{l.x, l.y, l.z, Math.abs(l.radius), o[0], o[1], l.plane, l.r * s, l.g * s, l.b * s, l.floorY, l.ceilY};
	}

	public static float intensity(PointLight l)
	{
		return l.intensity;
	}

	public static void intensity(PointLight l, float value)
	{
		l.intensity = value;
	}
}
