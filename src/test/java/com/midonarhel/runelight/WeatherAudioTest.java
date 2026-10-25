package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherAudioTest
{
	private static final int RATE = AudioMixer.RATE;

	private static AudioMixer mixer() throws Exception
	{
		float[][] thunder = {WeatherAudio.load("thunder1.wav"), WeatherAudio.load("thunder2.wav"), WeatherAudio.load("thunder3.wav")};
		return new AudioMixer(WeatherAudio.load("rain_light.wav"), WeatherAudio.load("rain.wav"), thunder);
	}

	/** A mixer with rain loops of constant samples (light 0.4, heavy 0.2) to read the levels off. */
	private static AudioMixer flat()
	{
		float[] light = new float[1000], heavy = new float[1000];
		java.util.Arrays.fill(light, 0.4f);
		java.util.Arrays.fill(heavy, 0.2f);
		return new AudioMixer(light, heavy, new float[][]{new float[RATE]});
	}

	private static float[] noise(int length, long seed)
	{
		float[] x = new float[length];
		java.util.Random r = new java.util.Random(seed);
		for (int i = 0; i < x.length; ++i)
		{
			x[i] = r.nextFloat() * 2 - 1;
		}
		return x;
	}

	private static float[] render(AudioMixer m, int frames)
	{
		float[] out = new float[frames];
		for (int at = 0; at < frames; at += 512)
		{
			float[] block = new float[Math.min(512, frames - at)];
			m.render(block, block.length);
			System.arraycopy(block, 0, out, at, block.length);
		}
		return out;
	}

	private static float energy(float[] x, int from, int to)
	{
		float e = 0;
		for (int i = from; i < to; ++i)
		{
			e += x[i] * x[i];
		}
		return e;
	}

	@Test
	public void bundledSoundsLoad() throws Exception
	{
		float[] rain = WeatherAudio.load("rain.wav"), light = WeatherAudio.load("rain_light.wav");
		// a few seconds long, and the same length so that the two stay in step
		assertTrue(rain.length >= 5 * RATE && rain.length <= 15 * RATE);
		assertEquals(rain.length, light.length);
		// the light rain is the quieter
		assertTrue(energy(light, 0, light.length) < 0.5f * energy(rain, 0, rain.length));
		for (String name : new String[]{"rain.wav", "rain_light.wav", "thunder1.wav", "thunder2.wav", "thunder3.wav"})
		{
			float[] x = WeatherAudio.load(name);
			float peak = 0;
			for (float v : x)
			{
				peak = Math.max(peak, Math.abs(v));
			}
			assertTrue(name + " peak " + peak, peak > (name.startsWith("rain") ? 0.02f : 0.2f) && peak < 1);
		}
	}

	/** Steady rain: no 100 ms of a loop stands out of the rest (a drop from a roof, a wiper, a voice), and it loops without a step. */
	@Test
	public void rainLoopsAreSteady() throws Exception
	{
		for (String name : new String[]{"rain.wav", "rain_light.wav"})
		{
			float[] rain = WeatherAudio.load(name);
			int block = RATE / 10, n = rain.length / block;
			float[] levels = new float[n];
			for (int k = 0; k < n; ++k)
			{
				levels[k] = (float) Math.sqrt(energy(rain, k * block, (k + 1) * block) / block);
			}
			float[] sorted = levels.clone();
			java.util.Arrays.sort(sorted);
			float median = sorted[n / 2];
			assertTrue(name + " loudest " + sorted[n - 1] + " median " + median, sorted[n - 1] < 1.3f * median && sorted[0] > 0.75f * median);
			// the seam is a step like any between neighbouring samples of the noise
			float step = Math.abs(rain[0] - rain[rain.length - 1]);
			assertTrue(name + " seam " + step, step < 4 * median);
		}
	}

	@Test
	public void rainBlendsFromLightToHeavy()
	{
		// light only up to LIGHT_UNTIL, heavy only from HEAVY_FROM, the same power all the way between
		assertEquals(1, AudioMixer.lightShare(0), 1e-6f);
		assertEquals(1, AudioMixer.lightShare(AudioMixer.LIGHT_UNTIL), 1e-6f);
		assertEquals(0, AudioMixer.heavyShare(AudioMixer.LIGHT_UNTIL), 1e-6f);
		assertEquals(1, AudioMixer.heavyShare(AudioMixer.HEAVY_FROM), 1e-6f);
		assertEquals(0, AudioMixer.lightShare(1), 1e-6f);
		float last = 0;
		for (float h = 0; h <= 1; h += 0.05f)
		{
			float l = AudioMixer.lightShare(h), v = AudioMixer.heavyShare(h);
			assertEquals(1, l * l + v * v, 1e-5f);
			assertTrue(v >= last);
			last = v;
		}
		// through the mixer: light's 0.4 at a low loudness, heavy's 0.2 at the top, both at half the level
		AudioMixer m = flat();
		m.setTargets(0.5f, 0.2f, 0, 1);
		assertEquals(0.2f, render(m, 3 * RATE)[3 * RATE - 1], 0.005f);
		m.setTargets(0.5f, 1, 0, 1);
		assertEquals(0.1f, render(m, 3 * RATE)[3 * RATE - 1], 0.005f);
	}

	@Test
	public void rainCutoffClosesIndoors()
	{
		float open = AudioMixer.rainCutoff(0), shut = AudioMixer.rainCutoff(1);
		assertTrue(open > 10000);
		assertEquals(2000, shut, 1);
		float last = open;
		for (float indoor = 0.1f; indoor <= 1; indoor += 0.1f)
		{
			float c = AudioMixer.rainCutoff(indoor);
			assertTrue(c < last);
			last = c;
		}
	}

	@Test
	public void rainIsMuffledAndMadeUpIndoors() throws Exception
	{
		float[] loop = WeatherAudio.load("rain.wav");
		float[][] seen = new float[2][];
		for (int indoors = 0; indoors < 2; ++indoors)
		{
			AudioMixer m = new AudioMixer(loop, loop, new float[][]{new float[RATE]});
			m.setTargets(0.5f, 1, indoors, 1);
			render(m, 4 * RATE);
			seen[indoors] = render(m, RATE);
		}
		float outdoorsEnergy = energy(seen[0], 0, RATE), indoorsEnergy = energy(seen[1], 0, RATE);
		float outdoorsHigh = 0, indoorsHigh = 0;
		for (int i = 1; i < RATE; ++i)
		{
			outdoorsHigh += (seen[0][i] - seen[0][i - 1]) * (seen[0][i] - seen[0][i - 1]);
			indoorsHigh += (seen[1][i] - seen[1][i - 1]) * (seen[1][i] - seen[1][i - 1]);
		}
		// about 60% as loud (the low-pass takes most of the energy, the makeup gain gives some back), and far duller:
		// most of the hiss above the cutoff is gone
		float loudness = (float) Math.sqrt(indoorsEnergy / outdoorsEnergy);
		assertTrue("indoors " + loudness, loudness > 0.5f && loudness < 0.7f);
		assertTrue(indoorsHigh / indoorsEnergy < 0.5f * outdoorsHigh / outdoorsEnergy);
	}

	@Test
	public void rainFollowsItsTargetsSmoothly()
	{
		AudioMixer m = flat();
		m.setTargets(0.5f, 0, 0, 1);
		float[] out = render(m, 3 * RATE);
		// outdoors at 0.5: the light loop's 0.4 times the level, once it has settled
		assertEquals(0.2f, out[out.length - 1], 0.005f);
		// it came up without a jump
		float jump = 0;
		for (int i = 1; i < out.length; ++i)
		{
			jump = Math.max(jump, Math.abs(out[i] - out[i - 1]));
		}
		assertTrue("jump " + jump, jump < 0.001f);
		// indoors a steady level passes the low-pass and gets the makeup gain: 1.8 of it
		m.setTargets(0.5f, 0, 1, 1);
		out = render(m, 4 * RATE);
		assertEquals(0.36f, out[out.length - 1], 0.005f);
	}

	@Test
	public void levelsSettleInTheirTime()
	{
		float k = AudioMixer.smoothing(0.5f);
		float v = 0;
		for (int i = 0; i < RATE / 2; ++i)
		{
			v += (1 - v) * k;
		}
		// most of a change (95%) in the time given
		assertEquals(0.95f, v, 0.01f);
	}

	@Test
	public void lowpassPassesTheLowsAndCutsTheHighs()
	{
		float a = AudioMixer.lowpassCoefficient(1000, RATE);
		assertTrue(a > 0 && a < 1);
		// 100 Hz and 8 kHz sines through the filter
		float low = amplitude(a, 100), high = amplitude(a, 8000);
		assertTrue("low " + low, low > 0.95f);
		assertTrue("high " + high, high < 0.2f);
		// a cutoff past the band passes nearly everything
		assertTrue(amplitude(AudioMixer.lowpassCoefficient(9500, RATE), 3000) > 0.8f);
	}

	private static float amplitude(float a, float hz)
	{
		float y = 0, peak = 0;
		for (int i = 0; i < RATE; ++i)
		{
			y += a * ((float) Math.sin(2 * Math.PI * hz * i / RATE) - y);
			if (i > RATE / 2)
			{
				peak = Math.max(peak, Math.abs(y));
			}
		}
		return peak;
	}

	@Test
	public void softClipRoundsOffWithoutBreaking()
	{
		assertEquals(0.5f, AudioMixer.softClip(0.5f), 0);
		assertEquals(-0.7f, AudioMixer.softClip(-0.7f), 0);
		float previous = 0;
		for (float x = 0; x < 20; x += 0.01f)
		{
			float y = AudioMixer.softClip(x);
			assertTrue(y >= previous && y < 1.0001f);
			previous = y;
		}
		assertEquals(-AudioMixer.softClip(3), AudioMixer.softClip(-3), 0);
	}

	@Test
	public void thunderIsQuieterAndDullerFarAway()
	{
		float[] near = WeatherAudio.thunderShape(2000, 1), mid = WeatherAudio.thunderShape(6000, 1),
			far = WeatherAudio.thunderShape(10000, 1);
		assertTrue(near[0] > mid[0] && mid[0] > far[0]);
		assertTrue(near[1] > mid[1] && mid[1] > far[1]);
		assertTrue(near[2] > far[2]);
		assertEquals(0.45f, near[0], 1e-5f);
		assertEquals(2500, near[1], 1);
		assertEquals(700, far[1], 1);
		// closer than the nearest strike is the same as the nearest, farther the same as the farthest
		assertArrayEquals(near, WeatherAudio.thunderShape(0, 1), 1e-5f);
		assertArrayEquals(far, WeatherAudio.thunderShape(50000, 1), 1e-5f);
		// a weaker flash is quieter
		assertEquals(0.5f * near[0], WeatherAudio.thunderShape(2000, 0.5f)[0], 1e-5f);
	}

	@Test
	public void thunderPlaysOnceAndIsMuffledIndoorsAndFar() throws Exception
	{
		// a fixed clap: loud noise, so its highs are easy to see
		float[] noise = new float[RATE];
		java.util.Random r = new java.util.Random(1);
		for (int i = 0; i < noise.length; ++i)
		{
			noise[i] = r.nextFloat() * 2 - 1;
		}
		float[] energies = new float[3];
		for (int case_ = 0; case_ < 3; ++case_)
		{
			AudioMixer m = new AudioMixer(new float[10], new float[10], new float[][]{noise});
			m.setTargets(0, 0, case_ == 2 ? 1 : 0, 1);
			// let the master and the indoor mix settle first
			render(m, RATE);
			m.thunder(0, 0.8f, case_ == 1 ? 700 : 9500, 1);
			assertTrue(m.audible());
			float[] out = render(m, RATE / 2);
			float d = 0;
			for (int i = 1; i < out.length; ++i)
			{
				d += (out[i] - out[i - 1]) * (out[i] - out[i - 1]);
			}
			energies[case_] = d;
		}
		// far (cutoff 700) and indoors are both much duller than near and outdoors
		assertTrue(energies[1] < 0.1f * energies[0]);
		assertTrue(energies[2] < 0.1f * energies[0]);
		// and it is over: the mixer goes quiet
		AudioMixer m = new AudioMixer(new float[10], new float[10], new float[][]{noise});
		m.setTargets(0, 0, 0, 1);
		m.thunder(0, 1, 9500, 1);
		render(m, 2 * RATE);
		assertFalse(m.audible());
	}

	@Test
	public void manyClapsAreCapped() throws Exception
	{
		AudioMixer m = mixer();
		m.setTargets(0, 0, 0, 1);
		for (int i = 0; i < 20; ++i)
		{
			m.thunder(i, 1, 9500, 1);
		}
		float[] out = render(m, RATE);
		for (float v : out)
		{
			assertTrue(Math.abs(v) <= 1);
		}
	}

	@Test
	public void silenceWhenThereIsNothing()
	{
		AudioMixer m = flat();
		assertFalse(m.audible());
		m.setTargets(0.3f, 0.3f, 0, 1);
		assertTrue(m.audible());
		render(m, RATE);
		m.setTargets(0, 0, 0, 1);
		render(m, 4 * RATE);
		assertFalse(m.audible());
		float[] out = render(m, 512);
		assertEquals(0, energy(out, 0, out.length), 1e-6f);
	}

	@Test
	public void rainGainRisesWithLoudnessFromNothing()
	{
		assertEquals(0, WeatherAudio.rainGain(0), 0);
		float previous = 0;
		for (float l = 0; l <= 1; l += 0.01f)
		{
			float g = WeatherAudio.rainGain(l);
			assertTrue(g >= previous && g <= 0.8f + 1e-5f);
			previous = g;
		}
		assertEquals(0.8f, WeatherAudio.rainGain(1), 1e-5f);
		assertEquals(0.8f, WeatherAudio.rainGain(3), 1e-5f);
		// a drizzle is already heard
		assertTrue(WeatherAudio.rainGain(0.2f) > 0.3f * WeatherAudio.rainGain(1));
	}

	@Test
	public void nearbyRainSwellsAsYouApproach()
	{
		// nothing here, rain a distance away: heard, and more the closer
		float far = WeatherAudio.rainLoudness(0, 1 * WeatherAudio.distantWeight(4800));
		float near = WeatherAudio.rainLoudness(0, 1 * WeatherAudio.distantWeight(1200));
		assertTrue(near > far && far >= 0);
		assertEquals(0, WeatherAudio.distantWeight(WeatherAudio.RAIN_REACH), 0);
		assertEquals(1, WeatherAudio.distantWeight(0), 0);
		float previous = 1;
		for (float d = 0; d <= WeatherAudio.RAIN_REACH; d += 100)
		{
			float w = WeatherAudio.distantWeight(d);
			assertTrue(w <= previous);
			previous = w;
		}
		// the rain on you is never quieter for rain elsewhere, and never past full
		assertEquals(0.7f, WeatherAudio.rainLoudness(0.7f, 0.2f), 0);
		assertEquals(1, WeatherAudio.rainLoudness(1.2f, 1), 0);
	}

	@Test
	public void indoorMixFollowsTheSkyOverhead()
	{
		// a room sees none of it: fully indoors; open ground sees all; a canopy or a gateway in between is partly
		assertEquals(1, WeatherAudio.indoorMix(0, null, 0, 0, 0), 0);
		assertEquals(1, WeatherAudio.indoorMix(WeatherAudio.FULLY_INDOORS, null, 0, 0, 0), 0);
		assertEquals(0, WeatherAudio.indoorMix(WeatherAudio.FULLY_OUTDOORS, null, 0, 0, 0), 0);
		assertEquals(0, WeatherAudio.indoorMix(1, null, 0, 0, 0), 0);
		float middle = (WeatherAudio.FULLY_INDOORS + WeatherAudio.FULLY_OUTDOORS) / 2;
		assertEquals(0.5f, WeatherAudio.indoorMix(middle, null, 0, 0, 0), 1e-5f);
		float last = 1;
		for (float v = 0; v <= 1; v += 0.02f)
		{
			float m = WeatherAudio.indoorMix(v, null, 0, 0, 0);
			assertTrue(m <= last + 1e-6f);
			last = m;
		}
	}

	@Test
	public void withoutTheSkyTheRoomMapDecides()
	{
		RoomMap m = RoomMapTest.house(RoomMapTest.walls());
		// inside the house, a little above its floor; its yard; and a place with no room map
		assertEquals(1, WeatherAudio.indoorMix(-1, m, RoomMapTest.world(10), -40, RoomMapTest.world(10)), 0);
		assertEquals(0, WeatherAudio.indoorMix(-1, m, RoomMapTest.world(14), -40, RoomMapTest.world(14)), 0);
		assertEquals(0, WeatherAudio.indoorMix(-1, null, RoomMapTest.world(10), -40, RoomMapTest.world(10)), 0);
		// off the map it is outdoors
		assertEquals(0, WeatherAudio.indoorMix(-1, m, -100000, -40, 100000), 0);
		// and a known sky overrides the room map: open sky over a room's tile is outdoors
		assertEquals(0, WeatherAudio.indoorMix(1, m, RoomMapTest.world(10), -40, RoomMapTest.world(10)), 0);
	}

	/** Write one level's up visibility at a tile's four corners (SW, SE, NW, NE). */
	private static void putLevel(SkyGrid g, int tx, int tz, int level, float... corners)
	{
		for (int k = 0; k < 4; ++k)
		{
			g.texels.put(g.texel(2 * tx + (k & 1), 2 * tz + (k >> 1), level + 1), Half.toHalf(corners[k]));
		}
	}

	@Test
	public void skyLookupReadsTheGridsTexels()
	{
		SkyGrid g = new SkyGrid();
		int tx = 10, tz = 20;
		// one floor, its ground at y 0; no upper floors
		for (int k = 0; k < 4; ++k)
		{
			g.texels.put(g.texel(2 * tx + (k & 1), 2 * tz + (k >> 1), 0), Half.toHalf(0));
		}
		// level 0 (40 up) sees none at its west corners and all at its east; level 1 (112 up) sees all everywhere
		putLevel(g, tx, tz, 0, 0, 1, 0, 1);
		putLevel(g, tx, tz, 1, 1, 1, 1, 1);
		float x = (tx + 0.25f) * SkyGrid.TILE, z = (tz + 0.5f) * SkyGrid.TILE;
		// at a level's own height: bilinear over the corners, a quarter of the way east
		assertEquals(0.25f, g.upVisibility(x, -40, z), 1e-3f);
		// at the next level's: all of it
		assertEquals(1, g.upVisibility(x, -112, z), 1e-3f);
		// halfway between: halfway
		assertEquals(0.625f, g.upVisibility(x, -76, z), 1e-3f);
		// lower than the first level: that level's
		assertEquals(0.25f, g.upVisibility(x, -10, z), 1e-3f);
		// a corner without a value drops out of the blend: the east ones are all that is left on the north side
		putLevel(g, tx, tz, 0, -1, 1, -1, 1);
		assertEquals(1, g.upVisibility(x, -40, z), 1e-3f);
		// all four without: the other level's, where the point lies between the two
		putLevel(g, tx, tz, 0, -1, -1, -1, -1);
		assertEquals(1, g.upVisibility(x, -60, z), 1e-3f);
		// outside the grid, nothing to say
		assertEquals(-1, g.upVisibility(-5, -40, z), 0);
		assertEquals(-1, g.upVisibility(x, -40, SkyGrid.SIZE * SkyGrid.TILE + 1), 0);
	}

	@Test
	public void samplesBecomeLittleEndianPcm()
	{
		byte[] out = new byte[8];
		WeatherAudio.toBytes(new float[]{0, 1, -1, 2}, 4, out);
		assertArrayEquals(new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x01, (byte) 0x80, (byte) 0xFF, 0x7F}, out);
	}
}
