package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.Commands;
import com.midonarhel.lumina.dev.SceneDump;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * Which system draws something: the dump named by -Dlumina.dump at its own camera, the final frame, then each variant
 * of -Dlumina.variants (name:key=value;key=value|..., the key "skylight" for Commands.skylightOff, "debugView" for a
 * view) drawn again with its settings and put back after. Writes to -Dlumina.out (default the dump's offline-systems/)
 * final.png (first-<view>.png with -Dlumina.view, the view of the first frame), <name>.png and <name>-diff.png (white where a channel is more than 2/255 off the final), and prints each
 * variant's difference.
 */
public class SystemsProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
		Path out = System.getProperty("lumina.out") != null ? Path.of(System.getProperty("lumina.out")) : dump.dir.resolve("offline-systems");
		Files.createDirectories(out);
		DumpRenderer.Options o = new DumpRenderer.Options();
		String first = System.getProperty("lumina.view", "FINAL");
		o.settings.put("debugView", first);
		// settings of the first frame, key=value,...; -Dlumina.lightMap=false draws without the light map
		for (String kv : System.getProperty("lumina.set", "").split(","))
		{
			if (kv.contains("="))
			{
				o.settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
			}
		}
		o.lightMap = Boolean.parseBoolean(System.getProperty("lumina.lightMap", "true"));
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			BufferedImage fin = r.render();
			ImageIO.write(fin, "png", out.resolve(first.equals("FINAL") ? "final.png" : "first-" + first.toLowerCase() + ".png").toFile());
			for (String variant : System.getProperty("lumina.variants", "").split("\\|"))
			{
				if (!variant.contains(":"))
				{
					continue;
				}
				String vname = variant.substring(0, variant.indexOf(':'));
				String[] kvs = variant.substring(variant.indexOf(':') + 1).split(";");
				String[] old = new String[kvs.length];
				for (int i = 0; i < kvs.length; ++i)
				{
					String k = kvs[i].substring(0, kvs[i].indexOf('=')), v = kvs[i].substring(kvs[i].indexOf('=') + 1);
					if (k.equals("skylight"))
					{
						Commands.skylightOff = v.equals("off");
					}
					else
					{
						old[i] = r.world.settings.get(k);
						r.set(k, v);
					}
				}
				BufferedImage im = r.renderAgain();
				ImageIO.write(im, "png", out.resolve(vname + ".png").toFile());
				ImageIO.write(diff(fin, im), "png", out.resolve(vname + "-diff.png").toFile());
				System.out.printf("%s: from the final %s%n", vname, DoorFadeProbe.summary(fin, im));
				Commands.skylightOff = false;
				for (int i = 0; i < kvs.length; ++i)
				{
					String k = kvs[i].substring(0, kvs[i].indexOf('='));
					if (!k.equals("skylight"))
					{
						r.set(k, old[i] != null ? old[i] : "");
					}
				}
			}
		}
	}

	/** White where a channel of b is more than 2/255 off a's. */
	static BufferedImage diff(BufferedImage a, BufferedImage b)
	{
		BufferedImage d = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < a.getHeight(); ++y)
			for (int x = 0; x < a.getWidth(); ++x)
			{
				int p = a.getRGB(x, y), q = b.getRGB(x, y), m = 0;
				for (int c = 0; c < 24; c += 8)
				{
					m = Math.max(m, Math.abs((p >> c & 255) - (q >> c & 255)));
				}
				d.setRGB(x, y, m > 2 ? 0xFFFFFF : 0);
			}
		return d;
	}
}
