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

	/** The light map's blocks in the light buffer now. */
	public static int blocksInPlace(LightMapBuffer b)
	{
		return b.blocksInPlace();
	}

	/**
	 * The baked visibility of the n lights on the map nearest a place (local world units), as text: per light and
	 * storey, per cell the most of its layers as a digit (0-9, '.' none), north up.
	 */
	public static String mapProbe(LightMap map, float x, float z, int n, int offsetCells)
	{
		java.util.List<LightMap.Source> all = map.sources();
		all.sort((a, b) -> Float.compare((a.x - x) * (a.x - x) + (a.z - z) * (a.z - z), (b.x - x) * (b.x - x) + (b.z - z) * (b.z - z)));
		StringBuilder out = new StringBuilder();
		for (int k = 0; k < Math.min(n, all.size()); ++k)
		{
			LightMap.Source s = all.get(k);
			out.append(String.format("lamp %d at %.0f, %.0f, y %.0f, storey %d, reach %.0f, owner %d, live %s; cell %.1f, %.1f%n", s.id, s.x, s.z,
				s.y, s.plane, s.radius, s.owner, s.live, s.x / 32 + offsetCells, s.z / 32 + offsetCells));
			if (s.grids == null)
			{
				continue;
			}
			for (LightMapBake.Grid g : s.grids)
			{
				out.append(String.format(" storey %d, cells %d..%d x %d..%d%n", g.plane, g.x0, g.x0 + g.w - 1, g.z0, g.z0 + g.h - 1));
				for (int j = g.h - 1; j >= 0; --j)
				{
					out.append("  ");
					for (int i = 0; i < g.w; ++i)
					{
						float most = 0;
						for (int l = 0; l < LightMapBake.HEIGHT_LAYERS; ++l)
						{
							most = Math.max(most, g.c[(j * g.w + i) * LightMapBake.HEIGHT_LAYERS + l]);
						}
						out.append(most <= 0 ? '.' : (char) ('0' + Math.min(9, (int) (most * 9.999f))));
					}
					out.append('\n');
				}
			}
		}
		return out.toString();
	}
}
