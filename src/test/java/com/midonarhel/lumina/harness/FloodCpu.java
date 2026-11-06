package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.CardLamps;
import com.midonarhel.lumina.light.LampVolume;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * The flood volume against the card's oracle with no GPU: an estimate of leak and overblock, for tuning while the GPU is
 * taken. Near level only, smooth normal for the step off the surface. Not a card: its numbers only compare with its own.
 */
public final class FloodCpu
{
	private static final float[] STEPS;

	static
	{
		String[] s = System.getenv().getOrDefault("LUMINA_STEPS", "1,2").split(",");
		STEPS = new float[s.length];
		for (int i = 0; i < s.length; ++i)
		{
			STEPS[i] = Float.parseFloat(s[i]);
		}
	}

	public static void main(String[] a) throws Exception
	{
		String name = a[0];
		Path cache = Paths.get(a[1]);
		SceneDump.Loaded dump = RenderDump.load(name);
		CardProbe p = CardProbe.load(cache.resolve(name).resolve("probe.bin"));
		CardOracle o = CardOracle.load(cache.resolve(name).resolve("oracle.bin"));
		List<ZoneSnapshot> zones = new ArrayList<>();
		for (int v = 0; v < dump.views.size(); ++v)
		{
			if (dump.views.get(v)[0] == WorldView.TOPLEVEL)
			{
				zones.addAll(dump.zones.get(v));
			}
		}
		float[][] lamps = new float[p.lamps.length][];
		for (int i = 0; i < lamps.length; ++i)
		{
			float[] l = p.lamps[i];
			lamps[i] = new float[]{l[CardLamps.X], l[CardLamps.Y], l[CardLamps.Z], l[CardLamps.RADIUS], l[CardLamps.OX], l[CardLamps.OZ]};
		}
		SceneDump.Manifest m = dump.manifest;
		DumpWorld world = new DumpWorld(dump);
		Scene top = world.scenes.get(WorldView.TOPLEVEL);
		int off = LuminaPlugin.SCENE_OFFSET;
		float ground = top.getTileHeights()[0][((int) m.focalX >> 7) + off][((int) m.focalZ >> 7) + off];
		LampVolume v = new LampVolume();
		long t0 = System.nanoTime();
		v.offline(zones, LuminaPlugin.SCENE_OFFSET >> 3, p.minLevel, p.level, p.maxLevel, p.hiddenRoofs, lamps, m.focalX, m.focalZ, ground);
		System.out.printf("%s: voxels and floods %.0f ms: %s%n", name, (System.nanoTime() - t0) / 1e6, LampVolume.lastJob);
		float cell = LampVolume.CELL, fineCell = LampVolume.FINE;
		if (System.getenv("LUMINA_FAR") != null)
		{
			System.out.println(v.offlineFar(Integer.parseInt(System.getenv("LUMINA_FAR"))));
		}
		if (System.getenv("LUMINA_SLICE") != null)
		{
			for (String at : System.getenv("LUMINA_SLICE").split(";"))
			{
				String[] c = at.split(",");
				System.out.println("slice:" + at + "\n" + v.offlineSlice(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
			}
			return;
		}
		final byte[] mask = new byte[p.pos.length / 3];
		long[] leak = new long[o.px.length], over = new long[o.px.length], dim = new long[o.px.length], noAir = new long[o.px.length], outside = new long[o.px.length];
		java.util.concurrent.atomic.AtomicLong nookBlocked = new java.util.concurrent.atomic.AtomicLong(), stepLeak = new java.util.concurrent.atomic.AtomicLong(), frontLeak = new java.util.concurrent.atomic.AtomicLong();
		IntStream.range(0, o.px.length).parallel().forEach(k ->
		{
			if (p.onHidden[k])
			{
				return;
			}
			float[] l = p.lamps[k];
			float r2 = l[CardLamps.RADIUS] * l[CardLamps.RADIUS];
			float lum = 0.2126f * l[CardLamps.R] + 0.7152f * l[CardLamps.G] + 0.0722f * l[CardLamps.B];
			for (int s = 0; s < o.px[k].length; ++s)
			{
				int i = o.px[k][s] * 3, c = CardOracle.classOf(o.cls[k][s]);
				if (c == CardOracle.PENUMBRA)
				{
					continue;
				}
				float x = p.pos[i], y = p.pos[i + 1], z = p.pos[i + 2], nx = p.normal[i], ny = p.normal[i + 1], nz = p.normal[i + 2];
				float dx = l[CardLamps.X] - x, dy = l[CardLamps.Y] - y, dz = l[CardLamps.Z] - z, d2 = dx * dx + dy * dy + dz * dz;
				float d = (float) Math.sqrt(d2);
				float cos = (dx * nx + dy * ny + dz * nz) / Math.max(d, 1);
				if (d2 >= r2 || cos <= 0)
				{
					continue;
				}
				float att = 1 - d / l[CardLamps.RADIUS];
				float light = lum * att * att * cos;
				float rv = -1;
				float bestAir = 0;
				for (float at : STEPS)
				{
					float r = v.offlineReadFine(k, x + nx * at * fineCell, y + ny * at * fineCell, z + nz * at * fineCell);
					r = r == -2 ? v.offlineRead(k, x + nx * at * cell, y + ny * at * cell, z + nz * at * cell) : r;
					if (r == -2)
					{
						rv = -2;
						break;
					}
					if (r != -1 && LampVolume.AIR.get()[0] > bestAir)
					{
						bestAir = LampVolume.AIR.get()[0];
						rv = r;
					}
					if (bestAir >= 0.06f)
					{
						break;
					}
				}
				if (rv == -2)
				{
					++outside[k];
					continue;
				}
				if (rv == -1 && c == CardOracle.BLOCKED && light > 0.003f)
				{
					if (nookBlocked.incrementAndGet() % 211 == 1)
					{
						System.out.printf("  nook lamp %d px %d,%d at %.0f %.0f %.0f n %.2f %.2f %.2f%n", k, o.px[k][s] % p.width, o.px[k][s] / p.width, x, y, z, nx, ny, nz);
					}
				}
				if (rv == -1)
				{
					mask[o.px[k][s]] |= 1;
					++noAir[k];
					rv = 0;
					if (c == CardOracle.OPEN && light > 0.01f && noAir[k] % 97 == 1)
					{
						System.out.printf("  noair lamp %d px %d,%d at %.0f %.0f %.0f n %.2f %.2f %.2f%n", k, o.px[k][s] % p.width, o.px[k][s] / p.width, x, y, z, nx, ny, nz);
					}
				}
				else if (c == CardOracle.OPEN && light > 0.01f && rv == 0 && over[k] % 197 == 1)
				{
					System.out.printf("  dark lamp %d px %d,%d at %.0f %.0f %.0f n %.2f %.2f %.2f d %.0f%n", k, o.px[k][s] % p.width, o.px[k][s] / p.width, x, y, z, nx, ny, nz, d);
					if (System.getenv("LUMINA_WAY") != null)
					{
						System.out.println(v.offlineWay(k, x, y, z));
					}
				}
				else if (c == CardOracle.BLOCKED && light * rv > 0.003f && (CardOracle.blockersOf(o.cls[k][s]) & CardMesh.RULE) != 0 && leak[k] % 197 == 1)
				{
					System.out.printf("  leak lamp %d px %d,%d at %.0f %.0f %.0f n %.2f %.2f %.2f d %.0f v %.2f%n", k, o.px[k][s] % p.width, o.px[k][s] / p.width, x, y, z, nx, ny, nz, d, rv);
				}
				if (c == CardOracle.BLOCKED && (CardOracle.blockersOf(o.cls[k][s]) & CardMesh.RULE) != 0 && light * rv > 0.003f)
				{
					++leak[k];
					mask[o.px[k][s]] |= 2;
					(v.offlineStepCrosses(x + nx * 2, y + ny * 2, z + nz * 2, nx, ny, nz, fineCell) ? stepLeak : frontLeak).incrementAndGet();
				}
				if (c == CardOracle.OPEN && light > 0.01f)
				{
					if (rv <= 0)
					{
						++over[k];
					}
					else if (rv < 0.7f)
					{
						++dim[k];
					}
				}
			}
		});
		{
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(p.width, mask.length / p.width, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int i = 0; i < mask.length; ++i)
			{
				int vl = mask[i];
				if (vl != 0)
				{
					int rgb = (vl & 1) != 0 ? 0xff0000 : 0;
					rgb |= (vl & 2) != 0 ? 0x00ff00 : 0;
					for (int a0 = 0; a0 < 2; ++a0)
					{
						for (int b0 = 0; b0 < 2; ++b0)
						{
							int xx = i % p.width + a0, yy = i / p.width + b0;
							if (xx < p.width && yy < img.getHeight())
							{
								img.setRGB(xx, yy, rgb);
							}
						}
					}
				}
			}
			javax.imageio.ImageIO.write(img, "png", new java.io.File("/private/tmp/claude-501/-Users-midona-Code-lumen/5ddba352-bd0f-485f-b534-84fd195347b4/scratchpad", "flood-" + name + ".png"));
		}
		long sl = 0, so = 0, sd = 0, sn = 0, st = 0;
		Map<Long, Integer> byLeak = new TreeMap<>(), byOver = new TreeMap<>();
		for (int k = 0; k < leak.length; ++k)
		{
			sl += leak[k];
			so += over[k];
			sd += dim[k];
			sn += noAir[k];
			st += outside[k];
			byLeak.put(-leak[k] * 1000 - k, k);
			byOver.put(-over[k] * 1000 - k, k);
		}
		System.out.printf("%s: leak %d px, overblock %d px, dimmed %d px, no air %d px, outside %d px%n", name, sl * 4, so * 4, sd * 4, sn * 4, st * 4);
		System.out.printf("%s: nook samples that must be dark %d px; leak where the step crosses solid %d, where not %d%n", name, nookBlocked.get() * 4, stepLeak.get() * 4, frontLeak.get() * 4);
		int shown = 0;
		for (int k : byLeak.values())
		{
			if (shown++ < 6 && leak[k] > 0)
			{
				System.out.printf(" leak lamp %d at %.0f %.0f %.0f r %.0f: %d px%n", k, lamps[k][0], lamps[k][1], lamps[k][2], lamps[k][3], leak[k] * 4);
			}
		}
		shown = 0;
		for (int k : byOver.values())
		{
			if (shown++ < 6 && over[k] > 0)
			{
				System.out.printf(" over lamp %d at %.0f %.0f %.0f r %.0f: %d px%n", k, lamps[k][0], lamps[k][1], lamps[k][2], lamps[k][3], over[k] * 4);
			}
		}
	}
}
