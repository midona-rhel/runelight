package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.visibility.ModelBlockers;
import com.midonarhel.lumina.visibility.WallMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.GameObject;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * What the light map's bake takes to stand in a lamp's way round a place of a dump: per light map cell (a quarter tile)
 * of a storey, the model blockers' heights (ModelBlockers: '.' none, a digit the lowest of its bins / 2, '^' only high
 * ones) and the solid tiles (WallMap.solid, '#'); then per tile the objects whose models are there (owner, bins) and the
 * walls, diagonal walls and roof pieces the dump knows on the tile (SceneObjects: types 9 to 21), with the owner each
 * would have. -Dlumina.dump, -Dlumina.at=x,y (world tile, default the dump's player), -Dlumina.radius (tiles, 8),
 * -Dlumina.plane (0).
 */
public class BakeBlockersProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
		try (DumpRenderer r = new DumpRenderer(dump, new DumpRenderer.Options()))
		{
			r.render();
			SceneDump.Manifest m = dump.manifest;
			String[] at = System.getProperty("lumina.at", "3210,3210").split(",");
			int radius = Integer.getInteger("lumina.radius", 8), plane = Integer.getInteger("lumina.plane", 0);
			int cx = Integer.parseInt(at[0].trim()) - m.baseX + LuminaPlugin.SCENE_OFFSET;
			int cy = Integer.parseInt(at[1].trim()) - m.baseY + LuminaPlugin.SCENE_OFFSET;
			ModelBlockers mb = r.plugin.voxelBuilder.getModelBlockers();
			WallMap walls = r.plugin.buildingLights.getWalls();
			System.out.printf("BAKE PROBE %s around %s,%s (extended %d,%d), plane %d; models %s%n", name, at[0], at[1], cx, cy, plane,
				mb != null ? "known" : "none");
			if (mb == null)
			{
				return;
			}
			Method record = ModelBlockers.class.getDeclaredMethod("record", int.class, int.class, int.class);
			record.setAccessible(true);
			Field fo = ModelBlockers.class.getDeclaredField("owners"), fn = ModelBlockers.class.getDeclaredField("next"),
				fm = ModelBlockers.class.getDeclaredField("masks");
			fo.setAccessible(true);
			fn.setAccessible(true);
			fm.setAccessible(true);
			int[] owners = (int[]) fo.get(mb), next = (int[]) fn.get(mb);
			short[] masks = (short[]) fm.get(mb);
			int c = ModelBlockers.CELLS_PER_TILE;
			// north up: rows from the top
			for (int ty = cy + radius; ty >= cy - radius; --ty)
			{
				for (int sub = c - 1; sub >= 0; --sub)
				{
					StringBuilder row = new StringBuilder(String.format("%5d ", ty - LuminaPlugin.SCENE_OFFSET + m.baseY));
					for (int tx = cx - radius; tx <= cx + radius; ++tx)
					{
						boolean solid = walls != null && walls.solid(plane, tx, ty);
						for (int sx = 0; sx < c; ++sx)
						{
							int h = mb.heights(plane, tx * c + sx, ty * c + sub);
							char ch = solid ? '#' : '.';
							if (h != 0)
							{
								int low = Integer.numberOfTrailingZeros(h);
								ch = low < 16 ? (low / 2 < 8 ? (char) ('0' + low / 2) : '^') : ch;
							}
							row.append(ch);
						}
						row.append(tx == cx - 1 ? '|' : ' ');
					}
					System.out.println(row);
				}
			}
			// per tile, the owners standing there and their bins
			Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
			Tile[][][] tiles = top.getExtendedTiles();
			for (int ty = cy + radius; ty >= cy - radius; --ty)
			{
				for (int tx = cx - radius; tx <= cx + radius; ++tx)
				{
					Map<Integer, Integer> bins = new TreeMap<>();
					for (int sx = 0; sx < c; ++sx)
					{
						for (int sz = 0; sz < c; ++sz)
						{
							for (int rr = (int) record.invoke(mb, plane, tx * c + sx, ty * c + sz); rr >= 0; rr = next[rr] - 1)
							{
								bins.merge(owners[rr], masks[rr] & 0xffff, (a, b) -> a | b);
							}
						}
					}
					if (bins.isEmpty())
					{
						continue;
					}
					StringBuilder known = new StringBuilder();
					int sx0 = tx - LuminaPlugin.SCENE_OFFSET, sy0 = ty - LuminaPlugin.SCENE_OFFSET;
					for (int p = 0; p < tiles.length; ++p)
					{
						Tile t = tiles[p][tx][ty];
						if (t == null)
						{
							continue;
						}
						WallObject w = t.getWallObject();
						if (w != null)
						{
							known.append(String.format(" [p%d wall %d owner %d]", p, w.getId(), owner(w.getId(), sx0, sy0, true)));
						}
						for (GameObject g : t.getGameObjects())
						{
							if (g != null)
							{
								int mx = g.getSceneMinLocation().getX(), my = g.getSceneMinLocation().getY();
								known.append(String.format(" [p%d object %d type %d owner %d]", p, g.getId(), g.getConfig() & 0x1F,
									owner(g.getId(), mx, my, false)));
							}
						}
					}
					StringBuilder line = new StringBuilder(String.format("tile %d,%d:", sx0 + m.baseX, sy0 + m.baseY));
					for (Map.Entry<Integer, Integer> e : bins.entrySet())
					{
						line.append(String.format(" owner %d bins %s", e.getKey(), Integer.toBinaryString(e.getValue())));
					}
					System.out.println(line + " |" + known);
				}
			}
		}
	}

	private static int owner(int id, int x, int y, boolean wall)
	{
		int w = 1 << 31;
		int o = (id * 31 + x) * 31 + y & ~w | (wall ? w : 0);
		return o == 0 ? 1 : o;
	}
}
