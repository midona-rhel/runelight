package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * The offline renderer on a dump made in code: the plugin draws it headless, the plate and the box show, the frame is
 * the same every time, and a dump written to files and read back draws the same frame.
 */
public class OfflineRenderSyntheticTest
{
	@Before
	public void mac()
	{
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
	}

	static BufferedImage render(SceneDump.Loaded dump) throws Exception
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.warmupSeconds = 1;
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			return r.render();
		}
	}

	@Test
	public void drawsTheSyntheticScene() throws Exception
	{
		BufferedImage img = render(SyntheticDump.make());
		Path out = Paths.get("build", "offline", "synthetic");
		Files.createDirectories(out);
		ImageIO.write(img, "png", out.resolve("final.png").toFile());
		int w = img.getWidth(), h = img.getHeight();
		assertEquals(SyntheticDump.WIDTH, w);
		assertEquals(SyntheticDump.HEIGHT, h);
		// the box at the middle, the plate all around it
		int box = img.getRGB(w / 2, h / 2), plate = img.getRGB(w / 2, h - 20);
		assertTrue("the plate is green: " + Integer.toHexString(plate),
			(plate >> 8 & 0xFF) > 2 * (plate >> 16 & 0xFF) && (plate >> 8 & 0xFF) > 2 * (plate & 0xFF));
		assertTrue("the box is red: " + Integer.toHexString(box), (box >> 16 & 0xFF) > 2 * (box >> 8 & 0xFF));
	}

	@Test
	public void theSameFrameEveryTimeAndFromFiles() throws Exception
	{
		BufferedImage a = render(SyntheticDump.make());
		BufferedImage b = render(SyntheticDump.make());
		assertEquals("two renders: " + Checks.diff(a, b), 0, Checks.diff(a, b).max);

		Path dir = Files.createTempDirectory("lumina-dump");
		SceneDump.save(SyntheticDump.make(), dir);
		BufferedImage c = render(SceneDump.load(dir));
		Checks.Diff d = Checks.diff(a, c);
		assertEquals("from files: " + d, 0, d.max);
	}
}
