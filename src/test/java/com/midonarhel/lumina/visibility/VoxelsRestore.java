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

	/**
	 * The models that stop the light map's lamps, rasterized as VoxelBuilder does from the zones' opaque triangles as a
	 * dump holds them (ZoneSnapshot: the static vertices read back), for a dump made before it carried them
	 * (voxels.models). The triangles have no object of their own here (the dump does not know it): a lamp is kept from
	 * being shaded by its own fixture by the bake's FIXTURE distance alone, as a world light is.
	 *
	 * @param zones the top level's zones, by extended zone x and z
	 */
	public static ModelBlockers modelsFromZones(java.util.List<com.midonarhel.lumina.scene.ZoneSnapshot> zones, byte[][][] settings,
		int[][][] heights)
	{
		int baseZone = com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET >> 3;
		int count = VoxelGrid.SCENE_TILES / 8 + 2;
		java.util.List<ModelBlockers.Zone> made = new java.util.ArrayList<>();
		java.util.List<int[]> at = new java.util.ArrayList<>();
		for (com.midonarhel.lumina.scene.ZoneSnapshot z : zones)
		{
			if (z.vertsO == null || z.x < baseZone - 1 || z.z < baseZone - 1 || z.x >= baseZone - 1 + count || z.z >= baseZone - 1 + count)
			{
				continue;
			}
			VoxelCapture cap = new VoxelCapture();
			int[] v = z.vertsO;
			// the static vertex: (y << 16 | x), (w << 16 | z), abhsl, (uv << 16 | texture), (uv, uv); three to a triangle
			int vertices = Math.min(z.sizeO * 3, v.length / 5);
			for (int t = 0; t + 2 < vertices; t += 3)
			{
				int o = t * 5;
				if ((v[o + 2] >>> 24) > 127 || ((short) v[o + 3] & com.midonarhel.lumina.water.WaterMap.BED_FLAG) != 0)
				{
					continue;
				}
				int[] p = new int[9];
				for (int k = 0; k < 3; ++k)
				{
					int q = o + k * 5;
					p[k * 3] = (short) v[q];
					p[k * 3 + 1] = (short) (v[q] >> 16);
					p[k * 3 + 2] = (short) v[q + 1];
				}
				cap.add(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], false);
			}
			float[] floors = new float[ModelBlockers.PLANES * 64];
			for (int pl = 0; pl < ModelBlockers.PLANES; ++pl)
			{
				for (int tx = 0; tx < 8; ++tx)
				{
					for (int tz = 0; tz < 8; ++tz)
					{
						floors[pl * 64 + tx * 8 + tz] = RoomMap.floorHeight(settings, heights, pl, z.x * 8 + tx, z.z * 8 + tz);
					}
				}
			}
			made.add(ModelBlockers.rasterize(cap, floors));
			at.add(new int[]{z.x, z.z});
		}
		int[] zx = new int[made.size()], zz = new int[made.size()];
		for (int i = 0; i < made.size(); ++i)
		{
			zx[i] = at.get(i)[0];
			zz[i] = at.get(i)[1];
		}
		return ModelBlockers.assemble(made.toArray(new ModelBlockers.Zone[0]), zx, zz, made.size(),
			net.runelite.api.Constants.EXTENDED_SCENE_SIZE * ModelBlockers.CELLS_PER_TILE);
	}
}
