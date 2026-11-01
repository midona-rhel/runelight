package com.midonarhel.lumina.light;

/** A lamp's position, reach and line origin from outside the package, for the offline renderer's checks. */
public final class LampsAccess
{
	private LampsAccess()
	{
	}

	/** x, y, z, radius, the lines' origin ox, oz, plane. */
	public static float[] of(PointLight p)
	{
		return new float[]{p.x, p.y, p.z, p.radius, p.ox, p.oz, p.plane};
	}
}
