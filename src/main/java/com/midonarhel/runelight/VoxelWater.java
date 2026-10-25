package com.midonarhel.runelight;

/**
 * The water surface for the voxel grid. The surface is not geometry (the composite draws it, water.glsl) and the bed
 * under it is left out of the capture (VoxelCapture), so the grid gets the surface as one layer of voxels at its
 * height, which the skylight's rays stop at as they would at a floor. Copied from the scene's water map on the client
 * thread, added to the grid with each build (VoxelGrid.assemble).
 */
final class VoxelWater
{
	// per base tile, [z * VoxelGrid.SCENE_TILES + x]: whether there is water, and the surface's world y
	final boolean[] wet = new boolean[VoxelGrid.SCENE_TILES * VoxelGrid.SCENE_TILES];
	final float[] surface = new float[VoxelGrid.SCENE_TILES * VoxelGrid.SCENE_TILES];

	/**
	 * The water of a scene with water map map and terrain heights ([level][extended x][extended z]); null when it
	 * has none in the base scene.
	 */
	static VoxelWater of(WaterMap map, int[][][] heights, int offset)
	{
		if (map == null || !map.hasWater)
		{
			return null;
		}
		VoxelWater w = new VoxelWater();
		boolean any = false;
		for (int z = 0; z < VoxelGrid.SCENE_TILES; ++z)
		{
			for (int x = 0; x < VoxelGrid.SCENE_TILES; ++x)
			{
				int ex = x + offset, ez = z + offset;
				int i = ez * WaterMap.SIZE + ex;
				if (map.level[i] < 0 || map.type[i] == WaterMap.NONE)
				{
					continue;
				}
				int[][] h = heights[map.level[i]];
				// the surface lies at the tile's lowest corner (y down: the largest), as WaterMap.buildTexture takes it
				w.surface[z * VoxelGrid.SCENE_TILES + x] = Math.max(Math.max(h[ex][ez], h[ex + 1][ez]),
					Math.max(h[ex][ez + 1], h[ex + 1][ez + 1]));
				w.wet[z * VoxelGrid.SCENE_TILES + x] = true;
				any = true;
			}
		}
		return any ? w : null;
	}

	/** The voxel layer the surface of base tile i lies in. */
	int layer(int i)
	{
		return Math.floorDiv((int) Math.floor(surface[i]), VoxelGrid.VOXEL);
	}
}
