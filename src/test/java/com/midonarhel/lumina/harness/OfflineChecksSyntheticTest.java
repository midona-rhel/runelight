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
	public void mac()
	{
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
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
