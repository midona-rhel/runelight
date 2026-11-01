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

	/** With the models that stop the light map's lamps (null: a dump from before they were dumped). */
	public static void restore(VoxelBuilder builder, Object voxels, Object models)
	{
		builder.restore((SceneVoxels) voxels, (ModelBlockers) models);
	}
}
