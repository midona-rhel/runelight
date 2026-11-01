package com.midonarhel.lumina.harness;

import java.awt.image.BufferedImage;

/** The automatic checks on rendered frames (docs/offline-render.md). */
public final class Checks
{
	private Checks()
	{
	}

	/** Two frames compared: the mean and the largest channel difference, the share of pixels off by more than 8. */
	public static final class Diff
	{
		public double mean;
		public int max;
		public double over8;
		public BufferedImage image;

		@Override
		public String toString()
		{
			return String.format("mean %.2f, max %d, %.2f %% of pixels off by more than 8", mean, max, over8 * 100);
		}
	}

	/** The difference of two frames of a size, per channel (0-255); image shows it, 4x brighter. */
	public static Diff diff(BufferedImage a, BufferedImage b)
	{
		if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
		{
			throw new IllegalArgumentException("sizes differ: " + a.getWidth() + "x" + a.getHeight() + " and "
				+ b.getWidth() + "x" + b.getHeight());
		}
		int w = a.getWidth(), h = a.getHeight();
		Diff d = new Diff();
		d.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		long sum = 0;
		long over = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int x = 0; x < w; ++x)
			{
				int p = a.getRGB(x, y), q = b.getRGB(x, y);
				int dr = Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF));
				int dg = Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF));
				int db = Math.abs((p & 0xFF) - (q & 0xFF));
				int m = Math.max(dr, Math.max(dg, db));
				sum += dr + dg + db;
				d.max = Math.max(d.max, m);
				if (m > 8)
				{
					over++;
				}
				d.image.setRGB(x, y, Math.min(255, dr * 4) << 16 | Math.min(255, dg * 4) << 8 | Math.min(255, db * 4));
			}
		}
		d.mean = sum / (3.0 * w * h);
		d.over8 = over / (double) (w * h);
		return d;
	}
}
