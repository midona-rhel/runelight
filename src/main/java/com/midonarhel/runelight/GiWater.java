package com.midonarhel.runelight;

/**
 * The water surface for the global illumination's voxel grid. The surface is not geometry (the composite draws it,
 * water.glsl) and the bed under it is left out of the capture (GiCapture), so the grid gets the surface as one
 * layer of voxels at its height, colored by the water type: sunlight bounced off the water tints the shores and
 * walls beside it, and nothing bounces from under it. Copied from the scene's water map on the client thread, added
 * to the grid with each build (GiGrid.assemble).
 */
final class GiWater
{
	// Linear albedo per water type (WaterMap.NONE to CAVE): the surface color and the depth color of
	// water_common.glsl averaged in sRGB, in linear, times ALBEDO. What water throws back that isn't the sky's
	// reflection is mostly the light it scatters, in its depth color; the sky's is the sky light's business.
	static final float ALBEDO = 0.5f;
	private static final float[][] SURFACE_SRGB = {
		{0, 0, 0}, {0.412f, 0.502f, 0.612f}, {0.349f, 0.447f, 0.569f}, {0.239f, 0.294f, 0.443f},
		{0.533f, 0.600f, 0.678f}, {0.190f, 0.280f, 0.150f}, {0.050f, 0.100f, 0.140f}};
	private static final float[][] DEPTH_SRGB = {
		{0, 0, 0}, {0.000f, 0.459f, 0.557f}, {0.000f, 0.420f, 0.560f}, {0.000f, 0.360f, 0.520f},
		{0.060f, 0.520f, 0.600f}, {0.161f, 0.322f, 0.102f}, {0.042f, 0.144f, 0.162f}};

	// per base tile, [z * GiProbes.SIZE + x]: the water type (WaterMap.NONE for none) and the surface's world y
	final byte[] type = new byte[GiProbes.SIZE * GiProbes.SIZE];
	final float[] surface = new float[GiProbes.SIZE * GiProbes.SIZE];

	/**
	 * The water of a scene with water map map and terrain heights ([level][extended x][extended z]); null when it
	 * has none in the base scene.
	 */
	static GiWater of(WaterMap map, int[][][] heights, int offset)
	{
		if (map == null || !map.hasWater)
		{
			return null;
		}
		GiWater w = new GiWater();
		boolean any = false;
		for (int z = 0; z < GiProbes.SIZE; ++z)
		{
			for (int x = 0; x < GiProbes.SIZE; ++x)
			{
				int ex = x + offset, ez = z + offset;
				int i = ez * WaterMap.SIZE + ex;
				if (map.level[i] < 0 || map.type[i] == WaterMap.NONE)
				{
					continue;
				}
				int[][] h = heights[map.level[i]];
				// the surface lies at the tile's lowest corner (y down: the largest), as WaterMap.buildTexture takes it
				w.surface[z * GiProbes.SIZE + x] = Math.max(Math.max(h[ex][ez], h[ex + 1][ez]),
					Math.max(h[ex][ez + 1], h[ex + 1][ez + 1]));
				w.type[z * GiProbes.SIZE + x] = map.type[i];
				any = true;
			}
		}
		return any ? w : null;
	}

	/** Linear albedo of a water type into out[0..2]. */
	static void albedo(int type, float[] out)
	{
		for (int c = 0; c < 3; ++c)
		{
			out[c] = ALBEDO * GiVoxels.srgbToLinear((SURFACE_SRGB[type][c] + DEPTH_SRGB[type][c]) * 0.5f);
		}
	}

	/** The voxel layer the surface of base tile i lies in. */
	int layer(int i)
	{
		return Math.floorDiv((int) Math.floor(surface[i]), GiGrid.VOXEL);
	}
}
