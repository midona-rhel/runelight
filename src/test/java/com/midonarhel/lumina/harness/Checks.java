package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.light.LampsAccess;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The automatic checks on frames the offline renderer draws (docs/offline-render.md): an image difference with a
 * threshold, the flatness of a region in the normals view, no lamp light behind a wall, no tile-sized steps in the
 * skylight on open ground. Each returns its numbers and a picture of where it looked or failed; the tests decide.
 */
public final class Checks
{
	private Checks()
	{
	}

	/** Where a pixel of a frame is in the world (FrameProbe), local units, null for the sky. */
	public interface Positions
	{
		float[] world(int x, int y);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// image difference

	/** Two frames compared: the mean and the largest channel difference, the share of pixels off by more than 8. */
	public static final class Diff
	{
		public double mean;
		public int max;
		public double over8;
		public BufferedImage image;

		/** Within a mean channel difference and a share of pixels off by more than 8. */
		public boolean within(double meanLimit, double over8Limit)
		{
			return mean <= meanLimit && over8 <= over8Limit;
		}

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

	// ---------------------------------------------------------------------------------------------------------------
	// flatness

	/** How far the normals of a region turn from their mean, in degrees. */
	public static final class Flatness
	{
		public double meanDegrees, maxDegrees;
		public float[] meanNormal;
		public int pixels;

		@Override
		public String toString()
		{
			return String.format("%d pixels, normal (%.2f, %.2f, %.2f), mean %.2f°, max %.2f° from it", pixels,
				meanNormal[0], meanNormal[1], meanNormal[2], meanDegrees, maxDegrees);
		}
	}

	/** The normal a NORMALS view pixel shows (n * 0.5 + 0.5 in rgb), unit length. */
	public static float[] normal(int rgb)
	{
		float x = (rgb >> 16 & 0xFF) / 255f * 2 - 1, y = (rgb >> 8 & 0xFF) / 255f * 2 - 1, z = (rgb & 0xFF) / 255f * 2 - 1;
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		return l > 1e-6f ? new float[]{x / l, y / l, z / l} : new float[]{0, 0, 0};
	}

	/** The flatness of a region of a NORMALS view. */
	public static Flatness flatness(BufferedImage normals, Rectangle region)
	{
		double sx = 0, sy = 0, sz = 0;
		int n = 0;
		for (int y = region.y; y < region.y + region.height; ++y)
		{
			for (int x = region.x; x < region.x + region.width; ++x)
			{
				float[] v = normal(normals.getRGB(x, y));
				sx += v[0];
				sy += v[1];
				sz += v[2];
				n++;
			}
		}
		double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
		Flatness f = new Flatness();
		f.pixels = n;
		f.meanNormal = new float[]{(float) (sx / l), (float) (sy / l), (float) (sz / l)};
		double sum = 0;
		for (int y = region.y; y < region.y + region.height; ++y)
		{
			for (int x = region.x; x < region.x + region.width; ++x)
			{
				float[] v = normal(normals.getRGB(x, y));
				double dot = v[0] * f.meanNormal[0] + v[1] * f.meanNormal[1] + v[2] * f.meanNormal[2];
				double deg = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
				sum += deg;
				f.maxDegrees = Math.max(f.maxDegrees, deg);
			}
		}
		f.meanDegrees = sum / Math.max(1, n);
		return f;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// lamps behind walls

	/** The lamp light found where every lamp in reach is behind a wall. */
	public static final class LampLeak
	{
		// pixels behind a wall from every lamp that reaches them, and of those the ones lit by the lamps; pixels a lamp
		// reaches in the open, and of those the ones lit (the lamps are on at all)
		public int behind, lit, open, openLit;
		// the most a channel rose there with the lamps on (0-255), and the mean
		public int max;
		public double mean;
		// red: lit behind a wall; dark blue: behind a wall, unlit; green: in a lamp's open reach
		public BufferedImage image;

		@Override
		public String toString()
		{
			return String.format("%d pixels behind walls, %d lit (rise max %d, mean %.2f); %d in the open, %d lit", behind, lit,
				max, mean, open, openLit);
		}
	}

	/**
	 * Lamp light behind walls: the frame with the lamps on against the frame with them off; a pixel counts where
	 * every lamp whose reach takes it in is cut off from it by a wall (WallMap.blocked from the lamp's line origin, as
	 * lamp.glsl) and it is more than margin from the lamps' lines' walls. Its rise with the lamps on is the leak.
	 *
	 * @param threshold the rise in a channel that counts a pixel as lit
	 */
	public static LampLeak lampLeak(BufferedImage on, BufferedImage off, Positions probe, List<PointLight> lamps,
		WallMap walls, int plane, int threshold)
	{
		int w = on.getWidth(), h = on.getHeight();
		LampLeak r = new LampLeak();
		r.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		long sum = 0;
		for (int y = 0; y < h; ++y)
		{
			for (int x = 0; x < w; ++x)
			{
				float[] p = probe.world(x, y);
				if (p == null)
				{
					continue;
				}
				int reach = 0, cut = 0;
				for (PointLight lamp : lamps)
				{
					float[] l = LampsAccess.of(lamp);
					float dx = p[0] - l[0], dy = p[1] - l[1], dz = p[2] - l[2];
					if (dx * dx + dy * dy + dz * dz > l[3] * l[3])
					{
						continue;
					}
					reach++;
					// clearly behind: blocked from the origin, and still blocked a little to either side
					if (walls.blocked(plane, l[4], l[5], p[0], p[2]) && walls.blocked(plane, l[4], l[5], p[0] + 24, p[2] + 24)
						&& walls.blocked(plane, l[4], l[5], p[0] - 24, p[2] - 24))
					{
						cut++;
					}
				}
				if (reach == 0)
				{
					continue;
				}
				int a = on.getRGB(x, y), b = off.getRGB(x, y);
				int rise = Math.max(Math.max((a >> 16 & 0xFF) - (b >> 16 & 0xFF), (a >> 8 & 0xFF) - (b >> 8 & 0xFF)),
					(a & 0xFF) - (b & 0xFF));
				rise = Math.max(rise, 0);
				if (cut == 0)
				{
					r.open++;
					r.openLit += rise > threshold ? 1 : 0;
					r.image.setRGB(x, y, rise > threshold ? 0x00A000 : 0x003000);
					continue;
				}
				if (cut < reach)
				{
					continue;
				}
				r.behind++;
				sum += rise;
				r.max = Math.max(r.max, rise);
				if (rise > threshold)
				{
					r.lit++;
					r.image.setRGB(x, y, 0xFF0000);
				}
				else
				{
					r.image.setRGB(x, y, 0x000060);
				}
			}
		}
		r.mean = sum / (double) Math.max(1, r.behind);
		return r;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// skylight steps

	/** Steps in the skylight across open ground. */
	public static final class SkylightSteps
	{
		// neighbouring open ground pixel pairs compared, and those with a step between them; of the steps, those within
		// an eighth of a tile of a tile's edge
		public int pairs, steps, onTileEdges;
		// red: a step; grey: open ground compared
		public BufferedImage image;

		public double share()
		{
			return steps / (double) Math.max(1, pairs);
		}

		@Override
		public String toString()
		{
			return String.format("%d steps in %d open ground pairs (%.3f %%), %d on tile edges", steps, pairs, share() * 100,
				onTileEdges);
		}
	}

	/**
	 * Steps in a SKYLIGHT view over open ground: horizontal and vertical neighbours that are both open ground (a
	 * normal within 15° of straight up, sky visibility worked out, the two less than half a tile apart in the world)
	 * whose sky visibility differs by more than step (0-1). A smooth skylight has none; a skylight traced per tile
	 * shows them along the tiles' edges.
	 */
	public static SkylightSteps skylightSteps(BufferedImage skylight, BufferedImage normals, Positions probe, float step)
	{
		int w = skylight.getWidth(), h = skylight.getHeight();
		SkylightSteps s = new SkylightSteps();
		s.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		float[][] world = new float[w * h][];
		boolean[] open = new boolean[w * h];
		for (int y = 0; y < h; ++y)
		{
			for (int x = 0; x < w; ++x)
			{
				int sky = skylight.getRGB(x, y);
				// red alone: not worked out (composite_frag.glsl's DEBUG_SKYLIGHT)
				boolean worked = !((sky >> 16 & 0xFF) > 100 && (sky >> 8 & 0xFF) == 0 && (sky & 0xFF) == 0);
				float[] n = normal(normals.getRGB(x, y));
				if (worked && n[1] < -0.966f)
				{
					world[y * w + x] = probe.world(x, y);
					open[y * w + x] = world[y * w + x] != null;
					if (open[y * w + x])
					{
						s.image.setRGB(x, y, 0x404040);
					}
				}
			}
		}
		for (int y = 0; y < h; ++y)
		{
			for (int x = 0; x < w; ++x)
			{
				int i = y * w + x;
				if (!open[i])
				{
					continue;
				}
				int[][] neighbours = {{x + 1, y}, {x, y + 1}};
				for (int[] q : neighbours)
				{
					if (q[0] >= w || q[1] >= h)
					{
						continue;
					}
					int j = q[1] * w + q[0];
					if (!open[j])
					{
						continue;
					}
					float[] a = world[i], b = world[j];
					if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > 64)
					{
						continue;
					}
					s.pairs++;
					float va = (skylight.getRGB(x, y) >> 8 & 0xFF) / 255f, vb = (skylight.getRGB(q[0], q[1]) >> 8 & 0xFF) / 255f;
					if (Math.abs(va - vb) > step)
					{
						s.steps++;
						s.image.setRGB(x, y, 0xFF0000);
						float mx = (a[0] + b[0]) / 2, mz = (a[2] + b[2]) / 2;
						float ex = Math.abs(mx - Math.round(mx / 128) * 128), ez = Math.abs(mz - Math.round(mz / 128) * 128);
						if (Math.min(ex, ez) < 16)
						{
							s.onTileEdges++;
						}
					}
				}
			}
		}
		return s;
	}
}
