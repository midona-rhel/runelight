package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.light.CardLamps;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Part 1 and the still half of part 2: each lamp's frame against the oracle. Every lamp is drawn alone, on the LIGHTING
 * view (light with no surface colour, no fog, not tone mapped) and on the final frame; what rose over the frame with no
 * lamps is what that lamp gave each pixel. LEAK: a sample the oracle has as BLOCKED that rose by more than Card.LIT.
 * OVERBLOCK: a sample it has as OPEN, whose expected light would show, that didn't rise.
 */
final class CardLeak
{
	private CardLeak()
	{
	}

	static Map<String, Object> run(DumpRenderer r, CardProbe p, CardOracle o, Path out, String dump) throws Exception
	{
		Map<String, Object> res = Card.map();
		LuminaPlugin plugin = r.plugin;
		Card.Lamps lamps = new Card.Lamps(plugin);
		int[] toProbe = Card.matchLamps(plugin, lamps.list, p.lamps);
		int w = p.width, h = p.height, n = w * h;
		long unmatched = Arrays.stream(toProbe).filter(i -> i < 0).count();
		int hidden = 0;
		for (boolean b : p.onHidden)
		{
			hidden += b ? 1 : 0;
		}
		res.put("lamps", lamps.list.size());
		res.put("lampsNotInOracle", unmatched);
		res.put("lampsOnHiddenStoreys", hidden);
		res.put("samples", samples(o));
		res.put("meshTriangles", o.meshTris);
		res.put("pixelsNotOnMesh", Card.round(100.0 * o.otherPixels / Math.max(1, o.otherPixels + o.matchedPixels), 2));

		// the frames: all on, none, none again (is a frame repeatable?), on each view
		r.set("debugView", "LIGHTING");
		r.frame();
		r.frame();
		lamps.none();
		r.frame();
		int[] off = Card.pixels(r.readBack()).clone();
		r.frame();
		int[] off2 = Card.pixels(r.readBack());
		res.put("repeatDiffPixels", count(off, off2));
		float[] sum = new float[n * 3];

		// does a lamp show in the frame it is set? (a build that delays it by a frame would need two)
		int test = busiest(o, toProbe);
		lamps.only(test);
		r.frame();
		int[] once = Card.pixels(r.readBack()).clone();
		r.frame();
		int settle = count(once, Card.pixels(r.readBack())) > 0 ? 2 : 1;
		res.put("framesToShowALamp", settle);

		long blockedSamples = 0, openSamples = 0, leakSamples = 0, leakModel = 0, leakOther = 0, leakCentreOnly = 0, overDark = 0, overPartial = 0;
		double leakEnergy = 0, allEnergy = 0;
		long[] leakBy = new long[CardMesh.NAMES.length];
		boolean[] leakMap = new boolean[o.gridW * o.gridH], overMap = new boolean[o.gridW * o.gridH];
		List<Float> residuals = new ArrayList<>();
		List<Map<String, Object>> worst = new ArrayList<>();
		float[] share = new float[o.gridW * o.gridH];
		boolean[] leakOne = new boolean[o.gridW * o.gridH];
		double ripple = 0;
		long rippleN = 0;
		long blotchCells = 0;
		int blotchMax = 0, blotchCount = 0;
		boolean[] blotch = new boolean[o.gridW * o.gridH];
		for (int k = 0; k < lamps.list.size(); ++k)
		{
			int q = toProbe[k];
			if (q < 0)
			{
				continue;
			}
			lamps.only(k);
			for (int f = 0; f < settle; ++f)
			{
				r.frame();
			}
			int[] img = Card.pixels(r.readBack());
			for (int i = 0; i < n; ++i)
			{
				int a = img[i], b = off[i];
				sum[i * 3] += Card.linear(a >> 16 & 255) - Card.linear(b >> 16 & 255);
				sum[i * 3 + 1] += Card.linear(a >> 8 & 255) - Card.linear(b >> 8 & 255);
				sum[i * 3 + 2] += Card.linear(a & 255) - Card.linear(b & 255);
			}
			float[] lamp = p.lamps[q];
			int[] px = o.px[q];
			short[] cls = o.cls[q];
			int ch = lamp[CardLamps.R] >= lamp[CardLamps.G] && lamp[CardLamps.R] >= lamp[CardLamps.B] ? 0 : lamp[CardLamps.G] >= lamp[CardLamps.B] ? 1 : 2;
			int shift = 16 - 8 * ch;
			float color = lamp[CardLamps.R + ch];
			float radius = lamp[CardLamps.RADIUS];
			Arrays.fill(share, Float.NaN);
			long lampLeak = 0;
			int lampBlockers = 0;
			Arrays.fill(leakOne, false);
			for (int s = 0; s < px.length; ++s)
			{
				int i = px[s], c = CardOracle.classOf(cls[s]), by = CardOracle.blockersOf(cls[s]);
				boolean other = (cls[s] & CardOracle.OTHER) != 0;
				int on = img[i], base = off[i];
				int rise = Card.rise(on, base);
				float got = (float) ((Card.linear(on >> shift & 255) - Card.linear(base >> shift & 255)) / Card.LIGHTING_SCALE);
				float want = expected(p, i, lamp, radius) * color;
				int cell = (i / w / CardOracle.STRIDE) * o.gridW + (i % w) / CardOracle.STRIDE;
				if (!other && want > 1e-4f && (on >> shift & 255) < 250)
				{
					share[cell] = Math.max(0, Math.min(1.5f, got / want));
				}
				allEnergy += Math.max(0, got);
				if (c == CardOracle.BLOCKED)
				{
					++blockedSamples;
					if (rise > Card.LIT)
					{
						if (other)
						{
							++leakOther;
						}
						else if ((by & CardMesh.RULE) == 0)
						{
							++leakModel;
						}
						else
						{
							++leakSamples;
							++lampLeak;
							lampBlockers |= by;
							leakEnergy += Math.max(0, got);
							leakMap[cell] = true;
							leakOne[cell] = true;
							for (int b = 0; b < leakBy.length; ++b)
							{
								leakBy[b] += (by >> b & 1);
							}
						}
					}
				}
				else if (c == CardOracle.OPEN && !other)
				{
					int shows = encode(base, shift, want) - (base >> shift & 255);
					if (shows > 8)
					{
						++openSamples;
						if (rise <= Card.LIT)
						{
							++overDark;
							overMap[cell] = true;
						}
						else if (got < 0.7f * want)
						{
							++overPartial;
							blotch[cell] = true;
						}
					}
				}
			}
			if (lampLeak > 0)
			{
				List<int[]> g = Card.groups(leakOne, o.gridW, o.gridH);
				Map<String, Object> m = Card.map();
				m.put("lamp", tile(r, lamp));
				m.put("plane", (int) lamp[CardLamps.PLANE]);
				m.put("reach", (int) radius);
				m.put("leakPx", lampLeak * CardOracle.STRIDE * CardOracle.STRIDE);
				m.put("biggestGroupPx", g.get(0)[0] * CardOracle.STRIDE * CardOracle.STRIDE);
				m.put("atPixel", new int[]{g.get(0)[1] * CardOracle.STRIDE, g.get(0)[2] * CardOracle.STRIDE});
				m.put("crossed", names(lampBlockers));
				worst.add(m);
			}
			CardEdges.residuals(share, o.plane, o.gridW, o.gridH, residuals);
			// the ripple of what should be flat: neighbouring open samples' shares on the same plane
			for (int s = 0; s < px.length; ++s)
			{
				if (CardOracle.classOf(cls[s]) != CardOracle.OPEN)
				{
					continue;
				}
				int i = px[s];
				int cell = (i / w / CardOracle.STRIDE) * o.gridW + (i % w) / CardOracle.STRIDE;
				int next = cell + 1;
				if (next < share.length && !Float.isNaN(share[cell]) && !Float.isNaN(share[next]) && o.plane[cell] != 0 && o.plane[cell] == o.plane[next])
				{
					ripple += Math.abs(share[cell] - share[next]);
					++rippleN;
				}
			}
			List<int[]> blotches = Card.groups(blotch, o.gridW, o.gridH);
			for (int[] b : blotches)
			{
				if (b[0] >= 16)
				{
					++blotchCount;
					blotchCells += b[0];
					blotchMax = Math.max(blotchMax, b[0]);
				}
			}
		}

		// the sum of the lamps alone against all together: every lamp's light gets there, or the budget cut it
		lamps.all();
		r.frame();
		int[] all = Card.pixels(r.readBack()).clone();
		long cut = 0, lit = 0;
		for (int i = 0; i < n; ++i)
		{
			float s = Math.max(sum[i * 3], Math.max(sum[i * 3 + 1], sum[i * 3 + 2]));
			if (s < 0.01f)
			{
				continue;
			}
			float got = Math.max(Card.linear(all[i] >> 16 & 255) - Card.linear(off[i] >> 16 & 255), Math.max(Card.linear(all[i] >> 8 & 255) - Card.linear(off[i] >> 8 & 255),
				Card.linear(all[i] & 255) - Card.linear(off[i] & 255)));
			++lit;
			cut += got < 0.8f * s ? 1 : 0;
		}
		res.put("litPixelsCutByBudgetPct", Card.round(100.0 * cut / Math.max(1, lit), 2));

		// with every lamp on: a pixel all of whose lamps the oracle blocks, risen
		boolean[] mayBeLit = new boolean[n], blocked = new boolean[n];
		for (int k = 0; k < p.lamps.length; ++k)
		{
			for (int s = 0; s < o.px[k].length; ++s)
			{
				if (CardOracle.classOf(o.cls[k][s]) == CardOracle.BLOCKED)
				{
					blocked[o.px[k][s]] = true;
				}
				else
				{
					mayBeLit[o.px[k][s]] = true;
				}
			}
		}
		long allLeak = 0, allBlocked = 0;
		for (int i = 0; i < n; ++i)
		{
			if (blocked[i] && !mayBeLit[i])
			{
				++allBlocked;
				allLeak += Card.rise(all[i], off[i]) > Card.LIT ? 1 : 0;
			}
		}

		// the same lamps on the final frame: surfaces of every kind, the transparent layer and the water among them
		r.set("debugView", "FINAL");
		lamps.none();
		r.frame();
		r.frame();
		int[] offF = Card.pixels(r.readBack()).clone();
		Card.png(offF, w, h, out.resolve("frames").resolve("nolamps-" + dump + ".png"));
		long leakFinal = 0, blockedFinal = 0;
		boolean[] leakFinalMap = new boolean[o.gridW * o.gridH];
		for (int k = 0; k < lamps.list.size(); ++k)
		{
			int q = toProbe[k];
			if (q < 0)
			{
				continue;
			}
			lamps.only(k);
			for (int f = 0; f < settle; ++f)
			{
				r.frame();
			}
			int[] img = Card.pixels(r.readBack());
			for (int s = 0; s < o.px[q].length; ++s)
			{
				int i = o.px[q][s];
				if (CardOracle.classOf(o.cls[q][s]) == CardOracle.BLOCKED && (CardOracle.blockersOf(o.cls[q][s]) & CardMesh.RULE) != 0)
				{
					++blockedFinal;
					if (Card.rise(img[i], offF[i]) > Card.LIT)
					{
						++leakFinal;
						leakFinalMap[(i / w / CardOracle.STRIDE) * o.gridW + (i % w) / CardOracle.STRIDE] = true;
					}
				}
			}
		}
		lamps.all();
		r.frame();
		int[] allF = Card.pixels(r.readBack()).clone();
		Card.png(allF, w, h, out.resolve("frames").resolve("final-" + dump + ".png"));
		int[] leakMask = mask(leakMap, leakFinalMap, o, w, h);
		Card.png(leakMask, w, h, out.resolve("leak-" + dump + ".png"));
		int[] over2 = allF.clone();
		for (int i = 0; i < n; ++i)
		{
			if (leakMask[i] != 0)
			{
				over2[i] = 0xFF2020;
			}
		}
		Card.png(over2, w, h, out.resolve("leakover-" + dump + ".png"));
		Card.png(mask(overMap, overMap, o, w, h), w, h, out.resolve("overblock-" + dump + ".png"));

		int scale = CardOracle.STRIDE * CardOracle.STRIDE;
		Map<String, Object> leak = Card.map();
		leak.put("px", leakSamples * scale);
		leak.put("ppmOfBlocked", Card.round(1e6 * leakSamples / Math.max(1, blockedSamples), 1));
		leak.put("energySharePct", Card.round(100 * leakEnergy / Math.max(1e-9, allEnergy), 3));
		Map<String, Object> by = Card.map();
		for (int b = 0; b < leakBy.length; ++b)
		{
			if (leakBy[b] > 0)
			{
				by.put(CardMesh.NAMES[b], leakBy[b] * scale);
			}
		}
		leak.put("byWhatItCrossed", by);
		leak.put("throughModelsOnlyPx", leakModel * scale);
		leak.put("onWaterTransparentAnimatedPx", leakOther * scale);
		leak.put("finalFramePx", leakFinal * scale);
		leak.put("allLampsOnPx", allLeak);
		leak.put("allLampsOnOfPx", allBlocked);
		worst.sort((a, b) -> Long.compare((Long) b.get("leakPx"), (Long) a.get("leakPx")));
		leak.put("worst", new ArrayList<>(worst.subList(0, Math.min(8, worst.size()))));
		res.put("leak", leak);

		Map<String, Object> over = Card.map();
		over.put("px", overDark * scale);
		over.put("ppmOfOpen", Card.round(1e6 * overDark / Math.max(1, openSamples), 1));
		over.put("dimmedPx", overPartial * scale);
		res.put("overblock", over);

		float[] rr = new float[residuals.size()];
		for (int i = 0; i < rr.length; ++i)
		{
			rr[i] = residuals.get(i) * CardOracle.STRIDE;
		}
		Arrays.sort(rr);
		Map<String, Object> edges = Card.map();
		edges.put("readings", rr.length);
		edges.put("p50px", rr.length == 0 ? 0 : Card.round(rr[rr.length / 2], 3));
		edges.put("p90px", rr.length == 0 ? 0 : Card.round(rr[rr.length * 9 / 10], 3));
		edges.put("p99px", rr.length == 0 ? 0 : Card.round(rr[rr.length * 99 / 100], 3));
		long stepped = 0;
		for (float f : rr)
		{
			stepped += f > 0.5f ? 1 : 0;
		}
		edges.put("steppedPct", Card.round(100.0 * stepped / Math.max(1, rr.length), 2));
		edges.put("openRipple", Card.round(ripple / Math.max(1, rippleN), 5));
		edges.put("blotches", blotchCount);
		edges.put("blotchPx", blotchCells * scale);
		edges.put("biggestBlotchPx", blotchMax * scale);
		res.put("edges", edges);
		return res;
	}

	private static String names(int mask)
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < CardMesh.NAMES.length; ++i)
		{
			if ((mask >> i & 1) != 0)
			{
				b.append(b.length() > 0 ? ", " : "").append(CardMesh.NAMES[i]);
			}
		}
		return b.toString();
	}

	// the world tile a lamp stands on
	private static int[] tile(DumpRenderer r, float[] lamp)
	{
		return new int[]{(int) Math.floor(lamp[CardLamps.X] / 128) + r.dump.manifest.baseX, (int) Math.floor(lamp[CardLamps.Z] / 128) + r.dump.manifest.baseY};
	}

	// the falloff times the cosine at pixel i: what the lamp gives there, of its colour, with nothing in the way
	private static float expected(CardProbe p, int i, float[] lamp, float radius)
	{
		float x = lamp[CardLamps.X] - p.pos[i * 3], y = lamp[CardLamps.Y] - p.pos[i * 3 + 1], z = lamp[CardLamps.Z] - p.pos[i * 3 + 2];
		float d = (float) Math.sqrt(x * x + y * y + z * z);
		float att = 1 - d / radius;
		float nl = (p.normal[i * 3] * x + p.normal[i * 3 + 1] * y + p.normal[i * 3 + 2] * z) / Math.max(1, d);
		return att <= 0 || nl <= 0 ? 0 : att * att * nl;
	}

	// the LIGHTING view's value of a pixel with this much more light
	private static int encode(int base, int shift, float want)
	{
		double v = Card.linear(base >> shift & 255) + want * Card.LIGHTING_SCALE;
		v = Math.min(1, v);
		v = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
		return (int) Math.round(v * 255);
	}

	private static int count(int[] a, int[] b)
	{
		int c = 0;
		for (int i = 0; i < a.length; ++i)
		{
			c += a[i] != b[i] ? 1 : 0;
		}
		return c;
	}

	// the lamp with the most samples, in the frame's list
	private static int busiest(CardOracle o, int[] toProbe)
	{
		int best = 0;
		long most = -1;
		for (int k = 0; k < toProbe.length; ++k)
		{
			if (toProbe[k] >= 0 && o.px[toProbe[k]].length > most)
			{
				most = o.px[toProbe[k]].length;
				best = k;
			}
		}
		return best;
	}

	// the samples of the classes, of the lamps' reach
	private static Map<String, Object> samples(CardOracle o)
	{
		long[] c = new long[3];
		for (short[] a : o.cls)
		{
			for (short s : a)
			{
				++c[CardOracle.classOf(s)];
			}
		}
		Map<String, Object> m = Card.map();
		m.put("open", c[0]);
		m.put("penumbra", c[1]);
		m.put("blocked", c[2]);
		return m;
	}

	// white where either map is set, a stride's block a sample
	private static int[] mask(boolean[] a, boolean[] b, CardOracle o, int w, int h)
	{
		int[] out = new int[w * h];
		for (int gy = 0; gy < o.gridH; ++gy)
		{
			for (int gx = 0; gx < o.gridW; ++gx)
			{
				int c = gy * o.gridW + gx;
				if (a[c] || b[c])
				{
					for (int y = 0; y < CardOracle.STRIDE; ++y)
					{
						for (int x = 0; x < CardOracle.STRIDE; ++x)
						{
							out[(gy * CardOracle.STRIDE + y) * w + gx * CardOracle.STRIDE + x] = 0xFFFFFF;
						}
					}
				}
			}
		}
		return out;
	}
}
