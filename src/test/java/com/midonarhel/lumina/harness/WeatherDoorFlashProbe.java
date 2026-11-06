package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.DoorAccess;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The weather flash on the bench: the dump -Dlumina.dump drawn and settled, a wall edge near the world tile
 * -Dlumina.door=x,y toggled as BuildingLights hands a door over, then every frame at 60 FPS the cover the AO pass wrote
 * for the composite (the shadow target's blue: snow above 0, water below) and the final picture's brightness. A door
 * adds nothing to the weather, so any change of the cover is the fault. Writes frames-NNN.png to -Dlumina.out.
 */
public class WeatherDoorFlashProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
		DumpRenderer.Options o = new DumpRenderer.Options();
		Path out = Path.of(System.getProperty("lumina.out", "/tmp/weather-door"));
		Files.createDirectories(out);
		String[] near = System.getProperty("lumina.door", "").split(",");
		StringBuilder t = new StringBuilder();
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			t.append(samplerUnits());
			SceneDump.Manifest m = dump.manifest;
			long millis = m.millis, nanos = Clock.nanos();
			int frame = 0;
			// well past any ease the restore itself started
			for (int i = 0; i < 90; ++i)
			{
				step(r, millis, nanos, ++frame);
			}
			float[] settled = cover(r);
			BufferedImage before = r.readBack();
			double bright0 = brightness(before);
			t.append(String.format("%s settled before the door: %s, brightness %.2f%n", name, describe(settled), bright0));
			ImageIO.write(before, "png", out.resolve("before.png").toFile());

			BuildingLights b = r.plugin.buildingLights;
			Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
			int cx = (near.length == 2 ? Integer.parseInt(near[0]) : m.baseX + 52) - m.baseX + LuminaPlugin.SCENE_OFFSET;
			int cy = (near.length == 2 ? Integer.parseInt(near[1]) : m.baseY + 52) - m.baseY + LuminaPlugin.SCENE_OFFSET;
			int[] edge = wall(b.getWalls(), cx, cy);
			WallMap w = DoorAccess.toggle(b.getWalls(), 0, edge[0], edge[1], edge[2]);
			RoomMap rooms = DoorAccess.rooms(b.getCoarse(), top.getExtendedTileSettings(), top.getTileHeights(), LuminaPlugin.SCENE_OFFSET, w, b.getFloors());
			t.append(String.format("door: edge %d of world tile %d,%d toggled%n", edge[2], edge[0] + m.baseX - LuminaPlugin.SCENE_OFFSET,
				edge[1] + m.baseY - LuminaPlugin.SCENE_OFFSET));
			b.restore(top, b.getResult(), b.getCoarse(), rooms != null ? rooms : b.getRooms(), w, b.getFloors(), b.getRoofs(), b.getDrawnWalls());

			double hi = bright0, lo = bright0;
			int flashed = 0;
			for (int k = 1; k <= 45; ++k)
			{
				// the worker's field is taken up by the frame after it is done, as in the client: give it its time
				if (k == 1)
				{
					Thread.sleep(600);
				}
				step(r, millis, nanos, ++frame);
				float[] c = cover(r);
				BufferedImage im = r.readBack();
				double bright = brightness(im);
				hi = Math.max(hi, bright);
				lo = Math.min(lo, bright);
				if (changed(settled, c) > 0.02)
				{
					flashed++;
				}
				t.append(String.format("frame %2d: %s, %.1f %% of the view's cover changed, brightness %.2f%n", k, describe(c), 100 * changed(settled, c), bright));
				if (k % 5 == 1 || k == 2)
				{
					ImageIO.write(im, "png", out.resolve(String.format("frame-%03d.png", k)).toFile());
				}
			}
			t.append(String.format("frames with more than 2 %% of the cover changed: %d of 45; brightness %.2f -> %.2f .. %.2f%n", flashed, bright0, lo, hi));
		}
		System.out.print(t);
		Files.writeString(out.resolve("frames.txt"), t.toString());
	}

	/** Every program's samplers by unit: two names on one unit in a program read one texture. */
	private static String samplerUnits()
	{
		StringBuilder t = new StringBuilder();
		int programs = 0, collisions = 0;
		java.nio.IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
		for (int p = 1; p < 4096; ++p)
		{
			if (!glIsProgram(p) || glGetProgrami(p, GL_LINK_STATUS) == 0)
			{
				continue;
			}
			programs++;
			java.util.Map<Integer, java.util.List<String>> units = new java.util.TreeMap<>();
			int n = glGetProgrami(p, GL_ACTIVE_UNIFORMS);
			for (int i = 0; i < n; ++i)
			{
				String name = glGetActiveUniform(p, i, 128, size, type);
				int y = type.get(0);
				if (y >= 0x8B5D && y <= 0x8B62 || y >= 0x8DC0 && y <= 0x8DD8 || y >= 0x9108 && y <= 0x910D)
				{
					units.computeIfAbsent(glGetUniformi(p, glGetUniformLocation(p, name)), k -> new java.util.ArrayList<>()).add(name);
				}
			}
			for (java.util.Map.Entry<Integer, java.util.List<String>> e : units.entrySet())
			{
				if (e.getValue().size() > 1)
				{
					collisions++;
					t.append(String.format("  program %d unit %d: %s%n", p, e.getKey(), e.getValue()));
				}
			}
		}
		return String.format("sampler units: %d programs, %d units shared within a program%n", programs, collisions) + t;
	}

	private static int[] wall(WallMap w, int cx, int cy)
	{
		for (int d = 0; d < 40; ++d)
		{
			for (int y = cy - d; y <= cy + d; ++y)
			{
				for (int x = cx - d; x <= cx + d; ++x)
				{
					if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) != d)
					{
						continue;
					}
					for (int e = 0; e < 4; ++e)
					{
						if (DoorAccess.isWall(w, 0, x, y, e))
						{
							return new int[]{x, y, e};
						}
					}
				}
			}
		}
		throw new AssertionError("no wall near");
	}

	/** The blue of the AO pass's second target. */
	private static float[] cover(DumpRenderer r)
	{
		int w = r.plugin.sceneTargets.getAoWidth(), h = r.plugin.sceneTargets.getAoHeight();
		FloatBuffer b = BufferUtils.createFloatBuffer(w * h);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, r.plugin.sceneTargets.getAoFbo());
		glReadBuffer(GL_COLOR_ATTACHMENT1);
		glPixelStorei(GL_PACK_ALIGNMENT, 1);
		glReadPixels(0, 0, w, h, GL_BLUE, GL_FLOAT, b);
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		float[] out = new float[w * h];
		b.get(out);
		return out;
	}

	private static double changed(float[] a, float[] b)
	{
		int n = 0;
		for (int i = 0; i < a.length; ++i)
		{
			if (Math.abs(a[i] - b[i]) > 0.02f)
			{
				n++;
			}
		}
		return n / (double) a.length;
	}

	private static String describe(float[] c)
	{
		int snow = 0, water = 0;
		float max = 0, min = 0;
		for (float v : c)
		{
			snow += v > 0.01f ? 1 : 0;
			water += v < -0.01f ? 1 : 0;
			max = Math.max(max, v);
			min = Math.min(min, v);
		}
		return String.format("snow on %.1f %% (max %.2f), water on %.1f %% (max %.2f)", 100.0 * snow / c.length, max, 100.0 * water / c.length, -min);
	}

	private static double brightness(BufferedImage im)
	{
		long sum = 0;
		for (int y = 0; y < im.getHeight(); ++y)
		{
			for (int x = 0; x < im.getWidth(); ++x)
			{
				int p = im.getRGB(x, y);
				sum += (p >> 16 & 255) + (p >> 8 & 255) + (p & 255);
			}
		}
		return sum / (3.0 * im.getWidth() * im.getHeight());
	}

	private static void step(DumpRenderer r, long millis, long nanos, int frame)
	{
		long dt = frame * 1_000_000_000L / 60;
		Clock.set(millis + dt / 1_000_000, nanos + dt);
		r.frame();
	}
}
