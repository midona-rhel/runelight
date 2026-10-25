package com.midonarhel.runelight;

/**
 * The base scene's voxels as a build left them (VoxelBuilder), for the skylight's grid (SkyGrid): the voxel grid and the
 * scene it was built from.
 */
final class SceneVoxels
{
	final VoxelGrid grid;
	SkyTerrain terrain;
	final int baseX, baseY;
	final boolean instance;
	// the scene's corner heights, [level][extended x][extended z]
	final int[][][] heights;

	SceneVoxels(VoxelGrid grid, int baseX, int baseY, boolean instance, int[][][] heights)
	{
		this.grid = grid;
		this.baseX = baseX;
		this.baseY = baseY;
		this.instance = instance;
		this.heights = heights;
	}
}
