package com.midonarhel.lumina.visibility;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * The sky grid's inputs as the worker was handed them (voxels, terrain, walls, rooms, floors) and the texels it traced,
 * written by `::runelight skydump name` to ~/.runelite/lumina/dumps, so a spot in the client can be traced again
 * offline (SkyGridReplay in the tests) and a change to the trace compared there before it runs in the client.
 */
final class SkyGridDump
{
	static final int VERSION = 1;

	private SkyGridDump()
	{
	}

	static void write(Path file, SceneVoxels voxels, WallMap walls, RoomMap rooms, byte[] floors, SkyGrid sky,
		float playerTileX, float playerTileZ) throws IOException
	{
		Files.createDirectories(file.getParent());
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))))
		{
			out.writeInt(VERSION);
			out.writeFloat(playerTileX);
			out.writeFloat(playerTileZ);
			out.writeInt(sky.resolution);
			out.writeInt(voxels.baseX);
			out.writeInt(voxels.baseY);
			out.writeBoolean(voxels.instance);
			writeHeights(out, voxels.heights);
			VoxelGrid g = voxels.grid;
			out.writeInt(g.nx);
			out.writeInt(g.ny);
			out.writeInt(g.nz);
			out.writeInt(g.layer0);
			for (int c : g.cells) out.writeInt(c);
			for (float t : g.top) out.writeFloat(t);
			SkyTerrain terrain = voxels.terrain;
			out.writeBoolean(terrain != null);
			if (terrain != null)
			{
				for (int[] t : terrain.triangles)
				{
					out.writeInt(t == null ? -1 : t.length);
					if (t != null) for (int v : t) out.writeInt(v);
				}
			}
			out.writeBoolean(walls != null);
			if (walls != null) walls.write(out);
			out.writeBoolean(rooms != null);
			if (rooms != null)
			{
				out.writeInt(rooms.size);
				out.writeInt(rooms.offset);
				for (float f : rooms.floors) out.writeFloat(f);
				for (boolean r : rooms.roofed) out.writeBoolean(r);
			}
			out.writeInt(floors == null ? -1 : floors.length);
			if (floors != null) out.write(floors);
			short[] texels = new short[sky.texels.capacity()];
			java.nio.ShortBuffer copy = sky.texels.duplicate();
			copy.position(0);
			copy.get(texels);
			out.writeInt(texels.length);
			for (short s : texels) out.writeShort(s);
		}
	}

	private static void writeHeights(DataOutputStream out, int[][][] h) throws IOException
	{
		out.writeInt(h == null ? -1 : h.length);
		if (h == null) return;
		for (int[][] level : h)
		{
			out.writeInt(level.length);
			for (int[] row : level)
			{
				out.writeInt(row.length);
				for (int v : row) out.writeInt(v);
			}
		}
	}
}
