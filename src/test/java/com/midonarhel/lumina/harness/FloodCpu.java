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

	private static final float TOWARD_A = env("LUMINA_TA", 0), TOWARD_B = env("LUMINA_TB", 0), TRUST_LO = env("LUMINA_TL", 0.3f), TRUST_HI = env("LUMINA_TH", 0.6f);

	private static final java.util.concurrent.atomic.AtomicIntegerArray WAYS = new java.util.concurrent.atomic.AtomicIntegerArray(6);

	private static float env(String name, float value)
	{
		String v = System.getenv(name);
		return v != null ? Float.parseFloat(v) : value;
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
		{
			double all = 0, far = 0;
			for (float[] l : lamps)
			{
				double w = 2 * Math.ceil(l[3] / 16) + 3, c = w * w * w;
				all += c;
				float e = Math.max(Math.abs(l[0] - m.focalX), Math.abs(l[2] - m.focalZ));
				far += e > 1280 + l[3] + 256 ? c : 0;
			}
			System.out.printf("%s: flood cells %.1f M, of lamps clear of the fine window %.1f M%n", name, all / 1e6, far / 1e6);
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
				if (TOWARD_B > 0 && rv > 0)
				{
					// read again toward the lamp, what lamp_volume.glsl does with F (math.md 13)
					float lx = dx / d, ly = dy / d, lz = dz / d;
					float size = fineCell;
					float qx = x + (nx * TOWARD_A + lx * TOWARD_B) * size, qy = y + (ny * TOWARD_A + ly * TOWARD_B) * size, qz = z + (nz * TOWARD_A + lz * TOWARD_B) * size;
					float r = v.offlineReadFine(k, qx, qy, qz);
					if (r == -2)
					{
						size = cell;
						qx = x + (nx * TOWARD_A + lx * TOWARD_B) * size;
						qy = y + (ny * TOWARD_A + ly * TOWARD_B) * size;
						qz = z + (nz * TOWARD_A + lz * TOWARD_B) * size;
						r = v.offlineRead(k, qx, qy, qz);
					}
					float air = r >= 0 ? LampVolume.AIR.get()[0] : 0;
					if (air >= 0.06f)
					{
						rv = r;
					}
					else
					{
						float u = Math.max(0, Math.min(1, (cos - TRUST_LO) / (TRUST_HI - TRUST_LO)));
						rv *= 1 - u * u * (3 - 2 * u);
					}
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
					float[] way = v.offlineLine(x + nx * 2, y + ny * 2, z + nz * 2, l[CardLamps.OX], l[CardLamps.Y], l[CardLamps.OZ]);
					int bin = way[1] < 0 ? 0 : way[2] < 72 && way[1] > 0 && way[2] > 0 && d - way[1] < 72 ? 1 : way[1] < 32 ? 2 : way[1] < 64 ? 3 : way[1] < 128 ? 4 : 5;
					WAYS.incrementAndGet(bin);
					if (bin == 0 && WAYS.get(0) % 53 == 1 || bin == 5 && WAYS.get(5) % 53 == 1)
					{
						System.out.printf("  way %d lamp %d px %d,%d at %.0f %.0f %.0f n %.2f %.2f %.2f d %.0f v %.2f line %.0f %.0f %.0f%n", bin, k, o.px[k][s] % p.width, o.px[k][s] / p.width, x, y, z, nx, ny, nz, d, rv, way[0], way[1], way[2]);
					}
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
		System.out.printf("%s: leak by way: no solid %d, only near the lamp %d, solid under 32 %d, under 64 %d, under 128 %d, further %d%n", name, WAYS.get(0) * 4, WAYS.get(1) * 4, WAYS.get(2) * 4, WAYS.get(3) * 4, WAYS.get(4) * 4, WAYS.get(5) * 4);
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
