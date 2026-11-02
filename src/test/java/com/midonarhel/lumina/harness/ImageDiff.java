package com.midonarhel.lumina.harness;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * The pixel differences between the PNGs of the same name in two directories (PassCost render=DIR, before and after
 * a change): per image Checks.diff's numbers and the count of pixels that differ at all, and DIFF/<name> where any
 * do (the difference, amplified). ./gradlew imageDiff -Pargs="BEFORE AFTER DIFF"
 */
public final class ImageDiff
{
	public static void main(String[] args) throws Exception
	{
		Path a = Path.of(args[0]), b = Path.of(args[1]);
		Path out = args.length > 2 ? Path.of(args[2]) : null;
		int total = 0;
		try (Stream<Path> files = Files.list(a))
		{
			for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".png")).sorted()::iterator)
			{
				File other = b.resolve(f.getFileName()).toFile();
				if (!other.exists())
				{
					System.out.printf("%-24s missing in %s%n", f.getFileName(), b);
					continue;
				}
				BufferedImage x = ImageIO.read(f.toFile()), y = ImageIO.read(other);
				Checks.Diff d = Checks.diff(x, y);
				int differ = 0;
				BufferedImage amp = new BufferedImage(x.getWidth(), x.getHeight(), BufferedImage.TYPE_INT_RGB);
				for (int py = 0; py < x.getHeight(); ++py)
				{
					for (int px = 0; px < x.getWidth(); ++px)
					{
						int p = x.getRGB(px, py), q = y.getRGB(px, py);
						if ((p & 0xffffff) != (q & 0xffffff))
						{
							++differ;
							int m = 0;
							for (int s = 0; s < 24; s += 8)
							{
								m = Math.max(m, Math.abs((p >> s & 0xff) - (q >> s & 0xff)));
							}
							int v = Math.min(255, 64 + m * 16);
							amp.setRGB(px, py, v << 16 | v << 8 | v);
						}
					}
				}
				total += differ;
				System.out.printf("%-24s %8d pixels differ (%.4f %%); %s%n", f.getFileName(), differ,
					100.0 * differ / (x.getWidth() * x.getHeight()), d);
				if (out != null && differ > 0)
				{
					Files.createDirectories(out);
					ImageIO.write(amp, "png", out.resolve(f.getFileName()).toFile());
				}
			}
		}
		System.out.println("total pixels differing: " + total);
	}

	private ImageDiff()
	{
	}
}
