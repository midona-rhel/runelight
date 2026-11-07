package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.system.Platform;

/** The checks on frames the plugin draws offline, on the synthetic scene: flat ground, no lamp light behind a wall. */
public class OfflineChecksSyntheticTest
{
	private static final Path OUT = Paths.get("build", "offline", "checks");

	@Before
	public void supportedPlatform()
	{
		Assume.assumeTrue("Headless GL supports Windows and macOS", Platform.get() != Platform.LINUX);
	}

	static DumpRenderer.Options options()
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.warmupSeconds = 1;
		// the depth is read back (FrameProbe): one sample, the window's resolution
		o.settings.put("antiAliasingMode", "DISABLED");
		o.settings.put("renderScale", "FULL");
		return o;
	}

	@Test
	public void theGroundIsFlat() throws Exception
	{
		try (DumpRenderer r = new DumpRenderer(SyntheticDump.make(), options()))
		{
			r.render();
			r.set("debugView", "NORMALS");
			BufferedImage normals = r.renderAgain();
			Files.createDirectories(OUT);
			ImageIO.write(normals, "png", OUT.resolve("normals.png").toFile());
			// a band of ground below the box
			Checks.Flatness f = Checks.flatness(normals, new Rectangle(10, normals.getHeight() - 70, normals.getWidth() - 20, 60));
			assertTrue(f.toString(), f.maxDegrees < 2);
			assertEquals(f.toString(), -1, f.meanNormal[1], 0.01);
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void noLampLightThroughAZigzagFront() throws Exception
	{
		// lamps over a joint where the front peaks, over one where it dips, and between: lines through the joints
		int k = 0;
		StringBuilder failures = new StringBuilder();
		for (double[] at : new double[][]{{57.0, 54.5}, {58.0, 54.5}, {57.5, 55.2}, {55.3, 53.9}})
		{
			SceneDump.Loaded dump = SyntheticDump.withZigzagAndLamp(at[0], at[1]);
			DumpRenderer.Options o = options();
			o.hour = 0;
			try (DumpRenderer r = new DumpRenderer(dump, o))
			{
				BufferedImage on = r.render();
				FrameProbe probe = new FrameProbe(r.plugin, r.width, r.height);
				r.set("pointLights", "false");
				BufferedImage off = r.renderAgain();
				Checks.LampLeak leak = Checks.lampLeak(on, off, probe, (List<PointLight>) dump.derived.get("lamps.collected"),
					(WallMap) dump.derived.get("buildings.walls"), 0, 6);
				Files.createDirectories(OUT);
				ImageIO.write(on, "png", OUT.resolve("zigzag-" + k + "-on.png").toFile());
				ImageIO.write(leak.image, "png", OUT.resolve("zigzag-" + k + "-leak.png").toFile());
				System.out.println("zigzag lamp at " + at[0] + ", " + at[1] + ": " + leak);
				if (leak.openLit <= 1000 || leak.behind <= 1000 || leak.lit != 0)
				{
					failures.append(at[0]).append(", ").append(at[1]).append(": ").append(leak).append('\n');
				}
			}
			++k;
		}
		assertTrue(failures.toString(), failures.length() == 0);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void noLampLightBehindTheWall() throws Exception
	{
		SceneDump.Loaded dump = SyntheticDump.withWallAndLamp();
		DumpRenderer.Options o = options();
		o.hour = 0;
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			BufferedImage on = r.render();
			FrameProbe probe = new FrameProbe(r.plugin, r.width, r.height);
			r.set("pointLights", "false");
			BufferedImage off = r.renderAgain();
			Checks.LampLeak leak = Checks.lampLeak(on, off, probe, (List<PointLight>) dump.derived.get("lamps.collected"),
				(WallMap) dump.derived.get("buildings.walls"), 0, 6);
			Files.createDirectories(OUT);
			ImageIO.write(on, "png", OUT.resolve("lamp-on.png").toFile());
			ImageIO.write(off, "png", OUT.resolve("lamp-off.png").toFile());
			ImageIO.write(leak.image, "png", OUT.resolve("lamp-leak.png").toFile());
			// the lamp lights its own side, and nothing behind the wall
			assertTrue(leak.toString(), leak.openLit > 1000);
			assertTrue(leak.toString(), leak.behind > 1000);
			assertEquals(leak.toString(), 0, leak.lit);
		}
	}
}
