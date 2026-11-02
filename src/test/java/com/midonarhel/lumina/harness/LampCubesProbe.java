package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.LampCubes;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.WorldView;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * Which lamps' shadow cubes (LampCubes) a change draws again: the dump named by -Dlumina.dump drawn and settled, then
 * one at a time each of the first -Dlumina.roofs (default 6) roofs the client hid shown, and hidden again; then the
 * zone the player stands in uploaded again, as the client does for a door. Per change: the zones whose drawing changed,
 * the lamps whose cubes are drawn again, of the frame's lamps with a cube, and the frames until all are drawn.
 */
public class LampCubesProbe
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
			LampCubes cubes = r.plugin.lampCubes;
			LuminaPlugin.SceneContext ctx = r.plugin.root;
			int withCube = Math.min(r.plugin.lightClusters.getLightCount(), LampCubes.SLOTS);
			System.out.printf("LAMP CUBES %s: %d lamps with a cube, %d roof ids hidden, %d zones%n", name, withCube,
				ctx.hideRoofIds.size(), ctx.sizeX * ctx.sizeZ);
			List<Integer> ids = new ArrayList<>(ctx.hideRoofIds);
			ids.sort(null);
			int n = Math.min(ids.size(), Integer.getInteger("lumina.roofs", 6));
			for (int i = 0; i < n; ++i)
			{
				int id = ids.get(i);
				r.options.showRoofIds.add(id);
				report(r, cubes, "roof " + id + " shown");
				// the frame with only the cubes the toggle found redrawn, against every cube redrawn: the same if the
				// ones it left alone needed nothing
				java.awt.image.BufferedImage some = r.readBack();
				cubes.invalidateAll();
				report(r, cubes, "  every cube drawn again");
				java.awt.image.BufferedImage all = r.readBack();
				int off = 0, most = 0;
				for (int y = 0; y < some.getHeight(); ++y)
				{
					for (int x = 0; x < some.getWidth(); ++x)
					{
						int a = some.getRGB(x, y), b = all.getRGB(x, y), d = 0;
						for (int c = 0; c < 24; c += 8)
						{
							d = Math.max(d, Math.abs((a >> c & 255) - (b >> c & 255)));
						}
						off += d > 2 ? 1 : 0;
						most = Math.max(most, d);
					}
				}
				System.out.printf("    selective against all redrawn: %d pixels more than 2 off, most %d%n", off, most);
				r.options.showRoofIds.remove(id);
				report(r, cubes, "roof " + id + " hidden again");
			}
			// what a finer test would give: the lamps whose reach takes in a tile of the roof (any level), of those with a cube
			int[][][] sceneRoofs = r.world.scenes.get(WorldView.TOPLEVEL).getRoofs();
			com.midonarhel.lumina.light.PointLight[] lamps = r.plugin.lightClusters.keptLights();
			java.lang.reflect.Field fx = com.midonarhel.lumina.light.PointLight.class.getDeclaredField("x"),
				fz = com.midonarhel.lumina.light.PointLight.class.getDeclaredField("z"),
				fr = com.midonarhel.lumina.light.PointLight.class.getDeclaredField("radius");
			fx.setAccessible(true);
			fz.setAccessible(true);
			fr.setAccessible(true);
			for (int i = 0; i < n; ++i)
			{
				int id = ids.get(i), reach = 0, tiles = 0;
				for (int[][] level : sceneRoofs)
				{
					for (int[] col : level)
					{
						for (int v : col)
						{
							tiles += v == id ? 1 : 0;
						}
					}
				}
				for (int k = 0; k < withCube; ++k)
				{
					float x = fx.getFloat(lamps[k]), z = fz.getFloat(lamps[k]), rad = Math.abs(fr.getFloat(lamps[k]));
					boolean hit = false;
					for (int lv = 0; lv < sceneRoofs.length && !hit; ++lv)
					{
						for (int tx = 0; tx < sceneRoofs[lv].length && !hit; ++tx)
						{
							for (int ty = 0; ty < sceneRoofs[lv][tx].length && !hit; ++ty)
							{
								if (sceneRoofs[lv][tx][ty] != id)
								{
									continue;
								}
								// extended scene tiles to local units
								float x0 = (tx - LuminaPlugin.SCENE_OFFSET) * 128, z0 = (ty - LuminaPlugin.SCENE_OFFSET) * 128;
								float cx = Math.max(x0, Math.min(x, x0 + 128)), cz = Math.max(z0, Math.min(z, z0 + 128));
								hit = (cx - x) * (cx - x) + (cz - z) * (cz - z) < rad * rad;
							}
						}
					}
					reach += hit ? 1 : 0;
				}
				System.out.printf("  roof %d: %d tiles; %d of the %d lamps reach one of them%n", id, tiles, reach, withCube);
			}
			int[] p = dump.manifest.playerLocal;
			int offset = LuminaPlugin.SCENE_OFFSET >> 3;
			int zx = (p[0] >> 10) + offset, zz = (p[1] >> 10) + offset;
			try
			{
				ctx.zones[zx][zz].invalidate = true;
				r.plugin.sceneLoader.rebuild(r.world.worldViews.get(WorldView.TOPLEVEL));
				report(r, cubes, "zone " + zx + "," + zz + " uploaded again (a door)");
			}
			catch (UnsupportedOperationException e)
			{
				System.out.println("  a zone uploaded again: not on the bench (" + e.getMessage() + ")");
			}
		}
	}

	private static void report(DumpRenderer r, LampCubes cubes, String what)
	{
		r.frame();
		int zones = cubes.lastChangedZones(), lamps = cubes.lastInvalidated();
		int frames = 1;
		while (cubes.lastPending() > 0 && frames < 60)
		{
			r.frame();
			++frames;
		}
		System.out.printf("  %s: %d zones or roof tiles changed, %d cubes drawn again over %d frames%n", what, zones, lamps, frames);
	}
}
