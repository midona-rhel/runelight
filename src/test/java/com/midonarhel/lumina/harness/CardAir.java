package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.PlainData;
import com.midonarhel.lumina.light.CardLamps;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Part 3, the air: the glow in fog and the light on rain and snow against the oracle's air rays.
 *
 * <p>Glow: the fog target (SceneTargets' texFog: scattered light, linear, half float) is read with each lamp alone and
 * with none; the difference is that lamp's glow alone, no surface in it. The oracle gives each ray the lamp's falloff
 * along it as let through by what is drawn; a scale per lamp (the fog's density there) is fitted over rays nothing stops.
 * A HOLE is a stretch of cells with well under what the oracle has, even by the least of its 3 x 3 neighbours, a shape
 * cut out of open air. A LEAK is a stretch of cells with well over the most of its neighbours has: glow through a wall.
 *
 * <p>Rain and snow: FINAL frames with each lamp alone, the fog off, against none. A particle lit by a lamp shows as a
 * rise; where the oracle lets none of the lamp into any of the air along the ray, none may show (its air rays are blocked
 * all the way to the surface, so that surface is dark too: nothing else rises there).
 */
final class CardAir
{
	// linear scattered light that shows on the dark night (about 2/255 over a dark surface)
	static final float SHOWS = 0.0015f;
	static final float HOLE = 0.4f, LEAK = 2.5f;

	private CardAir()
	{
	}

	private static Map<String, Object> proof(LuminaPlugin plugin, String asked) throws Exception
	{
		Map<String, Object> m = Card.map();
		Object type = PlainData.get(plugin.weather, "type");
		String id = String.valueOf(PlainData.get(type, "id"));
		m.put("asked", asked);
		m.put("type", id);
		m.put("held", id.equals(asked));
		m.put("active", plugin.weather.isActive());
		m.put("fogRequest", Card.round(plugin.weather.fogRequest(), 3));
		return m;
	}

	static float[] readFog(LuminaPlugin plugin) throws Exception
	{
		int w = plugin.sceneTargets.getFogWidth(), h = plugin.sceneTargets.getFogHeight();
		int tex = (Integer) PlainData.get(plugin.sceneTargets, "texFog");
		int was = glGetInteger(GL_TEXTURE_BINDING_2D);
		glBindTexture(GL_TEXTURE_2D, tex);
		ByteBuffer raw = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder());
		FloatBuffer f = raw.asFloatBuffer();
		glPixelStorei(GL_PACK_ALIGNMENT, 4);
		glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_FLOAT, f);
		glBindTexture(GL_TEXTURE_2D, was);
		float[] out = new float[w * h * 4];
		f.get(out);
		return out;
	}

	private static float lum(float r, float g, float b)
	{
		return 0.2126f * r + 0.7152f * g + 0.0722f * b;
	}

	static Map<String, Object> fog(DumpRenderer r, CardProbe p, CardOracle o, Path out, String dump) throws Exception
	{
		Map<String, Object> res = Card.map();
		LuminaPlugin plugin = r.plugin;
		res.put("weather", proof(plugin, "FOG"));
		Card.Lamps lamps = new Card.Lamps(plugin);
		int[] toProbe = Card.matchLamps(plugin, lamps.list, p.lamps);
		int aw = o.airW, ah = o.airH, cells = aw * ah;
		int fw = plugin.sceneTargets.getFogWidth(), fh = plugin.sceneTargets.getFogHeight();
		res.put("fogTarget", new int[]{fw, fh});
		// a cell's texel in the fog target
		int[] texel = new int[cells];
		for (int gy = 0; gy < ah; ++gy)
		{
			for (int gx = 0; gx < aw; ++gx)
			{
				int x = gx * CardOracle.AIR_STRIDE + CardOracle.AIR_STRIDE / 2, y = gy * CardOracle.AIR_STRIDE + CardOracle.AIR_STRIDE / 2;
				int tx = Math.min(fw - 1, x * fw / p.width), ty = Math.min(fh - 1, (p.height - 1 - y) * fh / p.height);
				texel[gy * aw + gx] = ty * fw + tx;
			}
		}
		lamps.none();
		r.frame();
		r.frame();
		float[] off = readFog(plugin);
		float[] sumGot = new float[cells], sumWant = new float[cells];
		boolean[] holes = new boolean[cells], leaks = new boolean[cells];
		boolean[] hole = new boolean[cells], leak = new boolean[cells];
		float[] got = new float[cells], want = new float[cells], whole = new float[cells];
		long holePx = 0, leakPx = 0, lit = 0;
		int biggestHole = 0, biggestLeak = 0, holeGroups = 0, leakGroups = 0;
		double logSum = 0;
		long logN = 0;
		int cellPx = CardOracle.AIR_STRIDE * CardOracle.AIR_STRIDE;
		java.util.List<Map<String, Object>> worst = new java.util.ArrayList<>();
		double noise = 0;
		long noiseN = 0;
		for (int k = 0; k < lamps.list.size(); ++k)
		{
			int q = toProbe[k];
			if (q < 0 || o.airIdx[q].length == 0)
			{
				continue;
			}
			lamps.only(k);
			r.frame();
			float[] on = readFog(plugin);
			float[] lamp = p.lamps[q];
			float cl = lum(lamp[CardLamps.R], lamp[CardLamps.G], lamp[CardLamps.B]);
			java.util.Arrays.fill(got, 0);
			java.util.Arrays.fill(want, 0);
			java.util.Arrays.fill(whole, 0);
			double fitTop = 0, fitBottom = 0;
			int[] idx = o.airIdx[q];
			for (int s = 0; s < idx.length; ++s)
			{
				int c = idx[s], t = texel[c] * 4;
				float g = lum(on[t] - off[t], on[t + 1] - off[t + 1], on[t + 2] - off[t + 2]);
				float w = o.airOpen[q][s] * cl;
				got[c] = g;
				want[c] = w;
				whole[c] = o.airWhole[q][s] * cl;
				if (o.airOpen[q][s] >= 0.95f * o.airWhole[q][s])
				{
					fitTop += g * w;
					fitBottom += w * w;
				}
			}
			// what shows far from every ray the lamp reaches: the frames' own difference
			for (int c = 0; c < cells; ++c)
			{
				if (whole[c] == 0)
				{
					int t = texel[c] * 4;
					noise += Math.abs(lum(on[t] - off[t], on[t + 1] - off[t + 1], on[t + 2] - off[t + 2]));
					++noiseN;
				}
			}
			float scale = fitBottom > 0 ? (float) (fitTop / fitBottom) : 0;
			if (scale <= 0)
			{
				continue;
			}
			java.util.Arrays.fill(hole, false);
			java.util.Arrays.fill(leak, false);
			for (int gy = 0; gy < ah; ++gy)
			{
				for (int gx = 0; gx < aw; ++gx)
				{
					int c = gy * aw + gx;
					float lo = Float.MAX_VALUE, hi = 0;
					for (int y = Math.max(0, gy - 1); y <= Math.min(ah - 1, gy + 1); ++y)
					{
						for (int x = Math.max(0, gx - 1); x <= Math.min(aw - 1, gx + 1); ++x)
						{
							float v = want[y * aw + x] * scale;
							lo = Math.min(lo, v);
							hi = Math.max(hi, v);
						}
					}
					float g = got[c], w = want[c] * scale;
					sumGot[c] += g;
					sumWant[c] += w;
					if (lo > SHOWS && g < HOLE * lo)
					{
						hole[c] = true;
						holes[c] = true;
					}
					if (g > SHOWS && g > LEAK * hi)
					{
						leak[c] = true;
						leaks[c] = true;
					}
					if (w > SHOWS && g > 0)
					{
						logSum += Math.abs(Math.log(g / w));
						++logN;
						++lit;
					}
				}
			}
			for (int[] g : Card.groups(hole, aw, ah))
			{
				if (g[0] >= 3)
				{
					++holeGroups;
					holePx += g[0] * cellPx;
					biggestHole = Math.max(biggestHole, g[0] * cellPx);
					if (g[0] >= 8)
					{
						worst.add(where("hole", lamp, g, cellPx, r));
					}
				}
			}
			for (int[] g : Card.groups(leak, aw, ah))
			{
				if (g[0] >= 3)
				{
					++leakGroups;
					leakPx += g[0] * cellPx;
					biggestLeak = Math.max(biggestLeak, g[0] * cellPx);
					if (g[0] >= 8)
					{
						worst.add(where("glow through", lamp, g, cellPx, r));
					}
				}
			}
		}
		// all the lamps together: what the budget of the ray left out shows here, as it does on screen
		lamps.all();
		r.frame();
		float[] on = readFog(plugin);
		long allHolePx = 0, allLeakPx = 0;
		java.util.Arrays.fill(hole, false);
		java.util.Arrays.fill(leak, false);
		for (int c = 0; c < cells; ++c)
		{
			int t = texel[c] * 4;
			float g = lum(on[t] - off[t], on[t + 1] - off[t + 1], on[t + 2] - off[t + 2]);
			hole[c] = sumWant[c] > SHOWS && g < HOLE * sumWant[c];
			leak[c] = g > SHOWS && g > LEAK * sumWant[c] + SHOWS;
		}
		for (int[] g : Card.groups(hole, aw, ah))
		{
			allHolePx += g[0] >= 3 ? g[0] * cellPx : 0;
		}
		for (int[] g : Card.groups(leak, aw, ah))
		{
			allLeakPx += g[0] >= 3 ? g[0] * cellPx : 0;
		}
		Card.png(Card.pixels(r.readBack()), p.width, p.height, out.resolve("frames").resolve("fog-" + dump + ".png"));
		int[] mask = new int[p.width * p.height];
		for (int gy = 0; gy < ah; ++gy)
		{
			for (int gx = 0; gx < aw; ++gx)
			{
				int c = gy * aw + gx;
				int color = leaks[c] ? 0xFF4040 : holes[c] ? 0xFFFFFF : 0;
				for (int y = 0; y < CardOracle.AIR_STRIDE && color != 0; ++y)
				{
					java.util.Arrays.fill(mask, (gy * CardOracle.AIR_STRIDE + y) * p.width + gx * CardOracle.AIR_STRIDE,
						(gy * CardOracle.AIR_STRIDE + y) * p.width + (gx + 1) * CardOracle.AIR_STRIDE, color);
				}
			}
		}
		Card.png(mask, p.width, p.height, out.resolve("fogholes-" + dump + ".png"));
		res.put("holePx", holePx);
		res.put("holeShapes", holeGroups);
		res.put("biggestHolePx", biggestHole);
		res.put("glowThroughPx", leakPx);
		res.put("glowThroughShapes", leakGroups);
		res.put("biggestGlowThroughPx", biggestLeak);
		res.put("meanLogError", logN == 0 ? 0 : Card.round(logSum / logN, 3));
		res.put("cellsLit", lit);
		res.put("allLampsHolePx", allHolePx);
		res.put("allLampsGlowThroughPx", allLeakPx);
		res.put("frameNoise", noiseN == 0 ? 0 : Card.round(noise / noiseN, 6));
		worst.sort((a, b) -> (Integer) b.get("px") - (Integer) a.get("px"));
		res.put("worst", new java.util.ArrayList<>(worst.subList(0, Math.min(8, worst.size()))));
		return res;
	}

	private static Map<String, Object> where(String what, float[] lamp, int[] g, int cellPx, DumpRenderer r)
	{
		Map<String, Object> m = Card.map();
		m.put("what", what);
		m.put("lamp", new int[]{(int) Math.floor(lamp[CardLamps.X] / 128) + r.dump.manifest.baseX, (int) Math.floor(lamp[CardLamps.Z] / 128) + r.dump.manifest.baseY});
		m.put("px", g[0] * cellPx);
		m.put("atPixel", new int[]{g[1] * CardOracle.AIR_STRIDE, g[2] * CardOracle.AIR_STRIDE});
		return m;
	}

	static Map<String, Object> particles(DumpRenderer r, CardProbe p, CardOracle o, Path out, String dump, String weather) throws Exception
	{
		Map<String, Object> res = Card.map();
		LuminaPlugin plugin = r.plugin;
		res.put("weather", proof(plugin, weather));
		Card.Lamps lamps = new Card.Lamps(plugin);
		int[] toProbe = Card.matchLamps(plugin, lamps.list, p.lamps);
		int aw = o.airW, ah = o.airH, w = p.width, h = p.height;
		lamps.none();
		r.frame();
		r.frame();
		int[] off = Card.pixels(r.readBack()).clone();
		boolean[] open = new boolean[aw * ah];
		boolean[] leakCells = new boolean[w * h];
		boolean[] surface = new boolean[w * h];
		long litOpen = 0, litClosed = 0, closedPx = 0, litAirOnly = 0, airOnlyPx = 0;
		List<Map<String, Object>> worst = new java.util.ArrayList<>();
		for (int k = 0; k < lamps.list.size(); ++k)
		{
			int q = toProbe[k];
			if (q < 0 || o.airIdx[q].length == 0)
			{
				continue;
			}
			lamps.only(k);
			r.frame();
			int[] img = Card.pixels(r.readBack());
			java.util.Arrays.fill(open, false);
			int[] idx = o.airIdx[q];
			for (int s = 0; s < idx.length; ++s)
			{
				open[idx[s]] = o.airOpen[q][s] > 0;
			}
			// the pixels whose surface the lamp may light (dilated: the samples are every second pixel)
			java.util.Arrays.fill(surface, false);
			for (int s = 0; s < o.px[q].length; ++s)
			{
				if (CardOracle.classOf(o.cls[q][s]) != CardOracle.BLOCKED)
				{
					int i = o.px[q][s];
					for (int y = Math.max(0, i / w - 3); y <= Math.min(h - 1, i / w + 3); ++y)
					{
						for (int x = Math.max(0, i % w - 3); x <= Math.min(w - 1, i % w + 3); ++x)
						{
							surface[y * w + x] = true;
						}
					}
				}
			}
			long lampLeak = 0;
			for (int y = 0; y < h; ++y)
			{
				int gy = y / CardOracle.AIR_STRIDE;
				for (int x = 0; x < w; ++x)
				{
					int i = y * w + x;
					int gx = x / CardOracle.AIR_STRIDE;
					boolean any = false;
					for (int yy = Math.max(0, gy - 1); yy <= Math.min(ah - 1, gy + 1) && !any; ++yy)
					{
						for (int xx = Math.max(0, gx - 1); xx <= Math.min(aw - 1, gx + 1) && !any; ++xx)
						{
							any = open[yy * aw + xx];
						}
					}
					boolean lit = Card.rise(img[i], off[i]) > Card.LIT;
					if (any)
					{
						litOpen += lit ? 1 : 0;
						if (!surface[i])
						{
							++airOnlyPx;
							litAirOnly += lit ? 1 : 0;
						}
					}
					else
					{
						++closedPx;
						if (lit)
						{
							++litClosed;
							++lampLeak;
							leakCells[i] = true;
						}
					}
				}
			}
			if (lampLeak > 0)
			{
				Map<String, Object> m = Card.map();
				m.put("lamp", new int[]{(int) Math.floor(p.lamps[q][CardLamps.X] / 128) + r.dump.manifest.baseX, (int) Math.floor(p.lamps[q][CardLamps.Z] / 128) + r.dump.manifest.baseY});
				m.put("px", lampLeak);
				worst.add(m);
			}
		}
		lamps.all();
		r.frame();
		Card.png(Card.pixels(r.readBack()), w, h, out.resolve("frames").resolve(weather.toLowerCase() + "-" + dump + ".png"));
		int[] mask = new int[w * h];
		for (int i = 0; i < mask.length; ++i)
		{
			mask[i] = leakCells[i] ? 0xFFFFFF : 0;
		}
		Card.png(mask, w, h, out.resolve(weather.toLowerCase() + "leak-" + dump + ".png"));
		res.put("litWhereAirOpenPx", litOpen);
		res.put("litWhereAirClosedPx", litClosed);
		res.put("leakSharePct", Card.round(100.0 * litClosed / Math.max(1, litOpen + litClosed), 3));
		res.put("litInOpenAirOverDarkSurfacesPx", litAirOnly);
		res.put("ofOpenAirOverDarkSurfacesPx", airOnlyPx);
		worst.sort((a, b) -> Long.compare((Long) b.get("px"), (Long) a.get("px")));
		res.put("worst", new java.util.ArrayList<>(worst.subList(0, Math.min(6, worst.size()))));
		return res;
	}
}
