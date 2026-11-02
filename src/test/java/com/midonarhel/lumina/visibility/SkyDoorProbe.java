package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.common.Half;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/**
 * A door on the sky grid, on the CPU: the scene dump named by -Dlumina.dump (a directory under
 * ~/.runelite/lumina/dumps) traced whole, then one wall edge near the player opened the way SkyGridMap's worker hands a
 * door over (setWalls, the voxels again, setRooms) and traced in SkyGridMap's batches. Prints what setGrid said, how
 * many units the door dirtied, and per batch how far the visibility texels stand from the state before and after.
 */
public class SkyDoorProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null);
		Path dir = Paths.get(System.getProperty("user.home"), ".runelite", "lumina", "dumps", name);
		Assume.assumeTrue(Files.isDirectory(dir));
		SceneDump.Loaded l = SceneDump.load(dir);
		Map<String, Object> derived = l.derived;
		SkyGridReplay.Dump d = new SkyGridReplay.Dump();
		d.voxels = (SceneVoxels) derived.get("voxels");
		d.walls = (WallMap) derived.get("buildings.walls");
		d.rooms = (RoomMap) derived.get("buildings.rooms");
		d.floors = (byte[]) derived.get("buildings.floors");
		d.resolution = 1;
		d.playerX = l.manifest.playerWorld[0] + 0.5f;
		d.playerZ = l.manifest.playerWorld[1] + 0.5f;
		SkyGrid s = SkyGridReplay.retrace(d);
		s.takeChanges();
		short[] before = visibility(s);

		// the corridor round the player: the skylight on the ground, and its slope (build/replay/)
		short[] all = new short[s.texels.capacity()];
		s.texels.duplicate().get(all);
		SkyGridReplay.Shader sh = new SkyGridReplay.Shader(all, 1, SkyGridReplay.field(d, 0), d.walls);
		int cx = l.manifest.playerWorld[0] - l.manifest.baseX, cz = l.manifest.playerWorld[1] - l.manifest.baseY;
		SkyGridReplay.regionImage(sh, d.walls, cx - 12, cx + 12, cz - 6, cz + 6, 0, name + "-skylight");
		SkyGridReplay.regionImage(sh, d.walls, cx - 12, cx + 12, cz - 6, cz + 6, 4, name + "-slope", 24, 4);
		SkyGridReplay.regionImage(sh, d.walls, cx - 12, cx + 12, cz - 6, cz + 6, 2, name + "-field");
		SkyGridReplay.regionImage(sh, d.walls, cx - 12, cx + 12, cz - 6, cz + 6, 1, name + "-trace");
		System.out.println(SkyGridReplay.regionMap(sh, d.walls, cx - 8, cx + 8, cz - 3, cz + 3, 0));

		// a wall edge near the player opened
		WallMap w = copy(d.walls);
		byte[] bits = (byte[]) field("bits").get(w);
		byte[] see = (byte[]) field("see").get(w);
		int px = l.manifest.playerWorld[0] - l.manifest.baseX + d.walls.offset, pz = l.manifest.playerWorld[1] - l.manifest.baseY + d.walls.offset;
		int doorX = -1, doorZ = -1;
		for (int r = 0; r < 6 && doorX < 0; ++r)
			for (int x = px - r; x <= px + r && doorX < 0; ++x)
				for (int z = pz - r; z <= pz + r && doorX < 0; ++z)
					if ((d.walls.get(0, x, z) & WallMap.NORTH) != 0)
					{
						doorX = x;
						doorZ = z;
					}
		System.out.printf("door: the north edge of extended tile %d,%d (player at %d,%d)%n", doorX, doorZ, px, pz);
		bits[w.index(0, doorX, doorZ)] &= ~WallMap.NORTH;
		bits[w.index(0, doorX, doorZ + 1)] &= ~WallMap.SOUTH;
		see[w.index(0, doorX, doorZ)] &= ~WallMap.NORTH;
		see[w.index(0, doorX, doorZ + 1)] &= ~WallMap.SOUTH;

		// the worker's order: the walls, then the voxels built again (a copy: the zone voxelized anew), the rooms
		s.setWalls(w);
		VoxelGrid g = d.voxels.grid;
		VoxelGrid g2 = new VoxelGrid(g.nx, g.ny, g.nz, g.layer0);
		System.arraycopy(g.cells, 0, g2.cells, 0, g.cells.length);
		System.arraycopy(g.top, 0, g2.top, 0, g.top.length);
		int dirtyWalls = s.dirtyCount();
		boolean fresh = s.setGrid(g2, d.voxels.heights, LuminaPlugin.SCENE_OFFSET, d.voxels.baseX, d.voxels.baseY, d.voxels.instance, d.floors);
		s.terrain = d.voxels.terrain;
		int dirtyVoxels = s.dirtyCount();
		s.setRooms(d.rooms);
		System.out.printf("setGrid fresh %b; dirty after setWalls %d, after setGrid %d, after setRooms %d; takeChanges %s%n",
			fresh, dirtyWalls, dirtyVoxels, s.dirtyCount(), java.util.Arrays.toString(s.takeChanges()));
		java.util.List<short[]> states = new java.util.ArrayList<>();
		states.add(visibility(s));
		int batches = 0;
		while (s.dirtyCount() > 0)
		{
			s.trace(SkyGridMap.RAY_BUDGET, d.playerX, d.playerZ);
			if (++batches % 8 == 0) states.add(visibility(s));
		}
		short[] after = visibility(s);
		System.out.printf("%d batches; final against before: %s%n", batches, diff(before, after));
		for (int i = 0; i < states.size(); ++i)
		{
			System.out.printf("  state %d: from before %s; from after %s%n", i, diff(before, states.get(i)), diff(after, states.get(i)));
		}
	}

	private static short[] visibility(SkyGrid s)
	{
		int from = s.texel(0, 0, SkyGrid.VISIBILITY) / 4 * 4, n = s.texels.capacity() - from;
		short[] a = new short[n];
		for (int i = 0; i < n; ++i) a[i] = s.texels.get(from + i);
		return a;
	}

	private static String diff(short[] a, short[] b)
	{
		int n = 0;
		float max = 0;
		for (int i = 0; i < a.length; ++i)
		{
			if (a[i] != b[i])
			{
				n++;
				max = Math.max(max, Math.abs(Half.fromHalf(a[i]) - Half.fromHalf(b[i])));
			}
		}
		return n + " texels differ, at most by " + max;
	}

	private static WallMap copy(WallMap o) throws Exception
	{
		WallMap w = WallMap.empty(o.size, o.offset, o.scene);
		for (String f : new String[]{"bits", "see"})
		{
			byte[] a = (byte[]) field(f).get(o);
			System.arraycopy(a, 0, (byte[]) field(f).get(w), 0, a.length);
		}
		boolean[] a = (boolean[]) field("full").get(o);
		System.arraycopy(a, 0, (boolean[]) field("full").get(w), 0, a.length);
		return w;
	}

	private static Field field(String name) throws Exception
	{
		Field f = WallMap.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
