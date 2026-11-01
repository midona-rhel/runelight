package com.midonarhel.lumina.visibility;

/** The voxel builder's restore from outside the package (SceneVoxels is the package's own), for the offline renderer. */
public final class VoxelsRestore
{
	private VoxelsRestore()
	{
	}

	public static void restore(VoxelBuilder builder, Object voxels)
	{
		builder.restore((SceneVoxels) voxels);
	}
}
