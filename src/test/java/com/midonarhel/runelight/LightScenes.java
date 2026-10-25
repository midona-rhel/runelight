package com.midonarhel.runelight;

/**
 * Voxel scenes for the light bake's tests, built from triangles as the scene's zones give them: the ground, a
 * roofed room on it (RoofTest's), and a small solid. World units, y down; the grid starts at the world's origin.
 */
final class LightScenes
{
	// the room's walls are on x and z = LO and HI, WALL tall, and its roof at -ROOF
	static final int LO = 256, HI = 768, WALL = 256, ROOF = 256;

	private LightScenes()
	{
	}

	private static void quad(VoxelCapture cap, int[] a, int[] b, int[] c, int[] d)
	{
		cap.add(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], false);
		cap.add(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], false);
	}

	private static int[] p(int x, int y, int z)
	{
		return new int[]{x, y, z};
	}

	private static void ground(VoxelCapture cap)
	{
		quad(cap, p(0, 0, 0), p(1000, 0, 0), p(1000, 0, 1000), p(0, 0, 1000));
	}

	private static void walls(VoxelCapture cap, boolean door)
	{
		for (int t = LO; t < HI; t += 128)
		{
			quad(cap, p(LO, 0, t), p(LO, 0, t + 128), p(LO, -WALL, t + 128), p(LO, -WALL, t));
			quad(cap, p(HI, 0, t), p(HI, 0, t + 128), p(HI, -WALL, t + 128), p(HI, -WALL, t));
			quad(cap, p(t, 0, LO), p(t + 128, 0, LO), p(t + 128, -WALL, LO), p(t, -WALL, LO));
			if (!door || t != 512)
			{
				quad(cap, p(t, 0, HI), p(t + 128, 0, HI), p(t + 128, -WALL, HI), p(t, -WALL, HI));
			}
		}
	}

	private static void roof(VoxelCapture cap)
	{
		for (int x = LO - 128; x < HI + 128; x += 128)
		{
			for (int z = LO - 128; z < HI + 128; z += 128)
			{
				quad(cap, p(x, -ROOF, z), p(x + 128, -ROOF, z), p(x + 128, -ROOF, z + 128), p(x, -ROOF, z + 128));
			}
		}
	}

	private static VoxelGrid grid(VoxelCapture cap)
	{
		return VoxelGrid.assemble(new Voxelizer.Zone[]{Voxelizer.voxelize(cap)}, new int[]{5}, new int[]{5}, 5, null);
	}

	/** Open ground. */
	static VoxelGrid open()
	{
		VoxelCapture cap = new VoxelCapture();
		ground(cap);
		return grid(cap);
	}

	/** A closed room with a roof on the ground. */
	static VoxelGrid room()
	{
		return room(false, true);
	}

	/** A room, with a doorway in its far wall (a tile wide at x 512 to 640) or without a roof. */
	static VoxelGrid room(boolean door, boolean roof)
	{
		VoxelCapture cap = new VoxelCapture();
		ground(cap);
		walls(cap, door);
		if (roof)
		{
			roof(cap);
		}
		return grid(cap);
	}

	/** The room with one more wall across the middle of it, at x = 512. */
	static VoxelGrid roomDivided()
	{
		VoxelCapture cap = new VoxelCapture();
		ground(cap);
		walls(cap, false);
		roof(cap);
		for (int t = LO; t < HI; t += 128)
		{
			quad(cap, p(512, 0, t), p(512, 0, t + 128), p(512, -WALL, t + 128), p(512, -WALL, t));
		}
		return grid(cap);
	}

	/** Open ground with a brazier on it: a closed box, 60 units tall and 56 on a side, filling the voxel at x and z 512 to 576. */
	static VoxelGrid brazier()
	{
		VoxelCapture cap = new VoxelCapture();
		ground(cap);
		cube(cap, 516, 572, -60);
		return grid(cap);
	}

	private static void cube(VoxelCapture cap, int lo, int hi, int top)
	{
		quad(cap, p(lo, 0, lo), p(hi, 0, lo), p(hi, top, lo), p(lo, top, lo));
		quad(cap, p(lo, 0, hi), p(hi, 0, hi), p(hi, top, hi), p(lo, top, hi));
		quad(cap, p(lo, 0, lo), p(lo, 0, hi), p(lo, top, hi), p(lo, top, lo));
		quad(cap, p(hi, 0, lo), p(hi, 0, hi), p(hi, top, hi), p(hi, top, lo));
		quad(cap, p(lo, top, lo), p(hi, top, lo), p(hi, top, hi), p(lo, top, hi));
	}

	/** Open ground with a hollow box on it, 128 units on a side, at x and z 448 to 576. */
	static VoxelGrid box()
	{
		VoxelCapture cap = new VoxelCapture();
		ground(cap);
		cube(cap, 448, 576, -128);
		return grid(cap);
	}
}
