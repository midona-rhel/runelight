package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * The scene dump's writer (the client's side, ::runelight scenedump) driven by the offline renderer's own frame: the
 * plugin records the frame through its hooks and writes it, the files read back draw the same frame, and the dump's
 * reference capture is that frame.
 */
public class SceneDumpRoundTripTest
{
	@Test
	public void aDumpOfARenderedFrameDrawsThatFrame() throws Exception
	{
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		Path root = Files.createTempDirectory("lumina-dumps");
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.warmupSeconds = 1;
		BufferedImage drawn;
		try (DumpRenderer r = new DumpRenderer(SyntheticDump.make(), o))
		{
			r.render();
			r.plugin.sceneDump.root = root;
			r.plugin.sceneDump.arm("roundtrip");
			drawn = r.renderAgain();
			r.plugin.sceneDump.writer.join(30_000);
		}
		Path dir = root.resolve("roundtrip");
		for (int i = 0; i < 100 && !Files.exists(dir.resolve("reference.txt")); ++i)
		{
			Thread.sleep(50);
		}
		BufferedImage reference = ImageIO.read(dir.resolve("reference.png").toFile());
		assertEquals("the reference is the frame drawn: " + Checks.diff(drawn, reference), 0, Checks.diff(drawn, reference).max);

		SceneDump.Loaded back = SceneDump.load(dir);
		assertEquals(SyntheticDump.WIDTH, back.manifest.canvasWidth);
		assertTrue(back.manifest.skipped.toString(), back.manifest.skipped.isEmpty());
		assertTrue("the tape has the zones and the box", back.tape.events.size() >= 25);
		BufferedImage again;
		try (DumpRenderer r = new DumpRenderer(back, o))
		{
			again = r.render();
		}
		Checks.Diff d = Checks.diff(reference, again);
		ImageIO.write(d.image, "png", dir.resolve("diff.png").toFile());
		// a step of one in a channel: the frame before it differs (the warm-up's, not the client's own)
		assertTrue("the dump drawn again: " + d, d.max <= 2);
	}
}
