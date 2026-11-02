package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.DoorAccess;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * A door on the bench: the dump named by -Dlumina.dump drawn, then a wall edge -Dlumina.door=x,y,N|E|S|W (world tile,
 * default the corridor door of artifact1, 3208,3211,N) toggled the way BuildingLights hands a door over (the walls,
 * the rooms built again for them), and the frames after it drawn at 60 FPS of the renderer's clock. Writes to
 * -Dlumina.out (default the dump's offline-door/<view>/): before.png, the frames at the steps (after-NNN.png), and steps.txt: per step
 * the mean difference (of 255) from the frame before the door and from the last, and the share of pixels more than
 * 2/255 off each. The camera -Dlumina.camera=yaw,pitch,scale, -Dlumina.hour, -Dlumina.view (debug view, default FINAL),
 * other settings -Dlumina.set=key=value,...
 */
public class DoorFadeProbe
{
	private static final int[] STEPS = {1, 2, 3, 4, 6, 8, 10, 13, 16, 20, 25, 30, 40, 60, 90, 120, 180};

	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
		DumpRenderer.Options o = new DumpRenderer.Options();
		String camera = System.getProperty("lumina.camera");
		if (camera != null)
		{
			String[] c = camera.split(",");
			o.cameraYaw = Integer.parseInt(c[0]);
			o.cameraPitch = Integer.parseInt(c[1]);
			o.scale = Integer.parseInt(c[2]);
		}
		if (System.getProperty("lumina.hour") != null)
		{
			o.hour = Integer.parseInt(System.getProperty("lumina.hour"));
		}
		String view = System.getProperty("lumina.view", "FINAL");
		o.settings.put("debugView", view);
		for (String kv : System.getProperty("lumina.set", "").split(","))
		{
			if (kv.contains("="))
			{
				o.settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
			}
		}
		String[] door = System.getProperty("lumina.door", "3208,3211,N").split(",");
		Path out = System.getProperty("lumina.out") != null ? Path.of(System.getProperty("lumina.out"))
			: dump.dir.resolve("offline-door").resolve(view.toLowerCase());
		Files.createDirectories(out);
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			SceneDump.Manifest m = dump.manifest;
			long millis = m.millis, nanos = Clock.nanos();
			int frame = 0;
			// settle at 60 FPS, then the frame before the door
			for (int i = 0; i < 60; ++i)
			{
				step(r, millis, nanos, ++frame);
			}
			BufferedImage before = r.readBack();
			ImageIO.write(before, "png", out.resolve("before.png").toFile());

			BuildingLights b = r.plugin.buildingLights;
			Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
			int x = Integer.parseInt(door[0]) - m.baseX + LuminaPlugin.SCENE_OFFSET, y = Integer.parseInt(door[1]) - m.baseY + LuminaPlugin.SCENE_OFFSET;
			int edge = "NESW".indexOf(door[2]);
			WallMap w = DoorAccess.toggle(b.getWalls(), 0, x, y, edge);
			RoomMap rooms = DoorAccess.rooms(b.getCoarse(), top.getExtendedTileSettings(), top.getTileHeights(), LuminaPlugin.SCENE_OFFSET, w, b.getFloors());
			System.out.printf("door: edge %s of world tile %s,%s %s%n", door[2], door[0], door[1], DoorAccess.isWall(w, 0, x, y, edge) ? "closed" : "opened");
			b.restore(top, b.getResult(), b.getCoarse(), rooms != null ? rooms : b.getRooms(), w, b.getFloors(), b.getRoofs(), b.getDrawnWalls());

			// each step's frame to disk (PNG, lossless), compared with the settled one once it is drawn
			String[] fromBefore = new String[STEPS.length];
			int last = STEPS[STEPS.length - 1];
			BufferedImage settled = null;
			for (int k = 1, s = 0; k <= last; ++k)
			{
				step(r, millis, nanos, ++frame);
				if (k == STEPS[s])
				{
					BufferedImage im = r.readBack();
					fromBefore[s] = summary(before, im);
					ImageIO.write(im, "png", out.resolve(String.format("after-%03d.png", k)).toFile());
					if (k == last)
					{
						settled = im;
					}
					s++;
				}
			}
			StringBuilder t = new StringBuilder(String.format("door %s %s,%s, view %s; the settled frame (step %d) from before: %s%n",
				door[2], door[0], door[1], view, last, summary(before, settled)));
			for (int s = 0; s < STEPS.length; ++s)
			{
				BufferedImage im = ImageIO.read(out.resolve(String.format("after-%03d.png", STEPS[s])).toFile());
				t.append(String.format("step %3d (%4.0f ms): from before %s; from settled %s%n", STEPS[s], STEPS[s] * 1000 / 60.0,
					fromBefore[s], summary(settled, im)));
			}
			System.out.print(t);
			Files.writeString(out.resolve("steps.txt"), t.toString());
		}
	}

	/** The mean difference of two frames (of 255, over the channels) and the share of pixels more than 2/255 off. */
	static String summary(BufferedImage a, BufferedImage b)
	{
		long sum = 0;
		int off = 0, n = a.getWidth() * a.getHeight();
		for (int y = 0; y < a.getHeight(); ++y)
			for (int x = 0; x < a.getWidth(); ++x)
			{
				int p = a.getRGB(x, y), q = b.getRGB(x, y), m = 0;
				for (int c = 0; c < 24; c += 8)
				{
					int d = Math.abs((p >> c & 255) - (q >> c & 255));
					sum += d;
					m = Math.max(m, d);
				}
				if (m > 2) off++;
			}
		return String.format("mean %.3f, %.2f %% over 2", sum / (3.0 * n), 100.0 * off / n);
	}

	private static void step(DumpRenderer r, long millis, long nanos, int frame)
	{
		long dt = frame * 1_000_000_000L / 60;
		Clock.set(millis + dt / 1_000_000, nanos + dt);
		r.frame();
	}
}
