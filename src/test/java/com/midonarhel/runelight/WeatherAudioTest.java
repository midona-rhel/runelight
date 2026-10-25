package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherAudioTest
{
	private static final int RATE = AudioMixer.RATE;

	private static AudioMixer mixer() throws Exception
	{
		float[][] thunder = {WeatherAudio.load("thunder1.wav"), WeatherAudio.load("thunder2.wav"), WeatherAudio.load("thunder3.wav")};
		return new AudioMixer(WeatherAudio.load("rain.wav"), WeatherAudio.load("rain_indoor.wav"), thunder);
	}

	/** A mixer with loops of constant samples (outdoors 0.4, indoors -0.2) to read the levels off. */
	private static AudioMixer flat()
	{
		float[] outdoor = new float[1000], indoor = new float[1000];
		java.util.Arrays.fill(outdoor, 0.4f);
		java.util.Arrays.fill(indoor, -0.2f);
		return new AudioMixer(outdoor, indoor, new float[][]{new float[RATE]});
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
		float[] rain = WeatherAudio.load("rain.wav"), indoor = WeatherAudio.load("rain_indoor.wav");
		// the two loops are the same length, so that they crossfade in step, and a few seconds long
		assertEquals(rain.length, indoor.length);
		assertTrue(rain.length >= 5 * RATE && rain.length <= 15 * RATE);
		for (String name : new String[]{"rain.wav", "rain_indoor.wav", "thunder1.wav", "thunder2.wav", "thunder3.wav"})
		{
			float[] x = WeatherAudio.load(name);
			float peak = 0;
			for (float v : x)
			{
				peak = Math.max(peak, Math.abs(v));
			}
			assertTrue(name + " peak " + peak, peak > 0.2f && peak < 1);
		}
		// the indoor loop is duller: much less of its energy is in the highs
		assertTrue(highShare(indoor) < 0.2f * highShare(rain));
	}

	private static float highShare(float[] x)
	{
		float high = 0, all = 0;
		for (int i = 1; i < x.length; ++i)
		{
			float d = x[i] - x[i - 1];
			high += d * d;
			all += x[i] * x[i];
		}
		return high / all;
	}

	@Test
	public void crossfadeKeepsItsPower()
	{
		for (float indoor = 0; indoor <= 1; indoor += 0.05f)
		{
			float o = AudioMixer.outdoorShare(indoor), i = AudioMixer.indoorShare(indoor);
			assertEquals(1, o * o + i * i, 1e-5f);
		}
		assertEquals(1, AudioMixer.outdoorShare(0), 1e-6f);
		assertEquals(0, AudioMixer.indoorShare(0), 1e-6f);
		assertEquals(1, AudioMixer.indoorShare(1), 1e-6f);
	}

	@Test
	public void rainFollowsItsTargetsSmoothly()
	{
		AudioMixer m = flat();
		m.setTargets(0.5f, 0, 1);
		float[] out = render(m, 3 * RATE);
		// outdoors at 0.5: the loop's 0.4 times the level, once it has settled
		assertEquals(0.2f, out[out.length - 1], 0.005f);
		// it came up without a jump
		float jump = 0;
		for (int i = 1; i < out.length; ++i)
		{
			jump = Math.max(jump, Math.abs(out[i] - out[i - 1]));
		}
		assertTrue("jump " + jump, jump < 0.001f);
		// indoors: the other loop, 0.5 * -0.2
		m.setTargets(0.5f, 1, 1);
		out = render(m, 4 * RATE);
		assertEquals(-0.1f, out[out.length - 1], 0.005f);
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
		assertEquals(1, near[0], 1e-5f);
		assertEquals(9500, near[1], 1);
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
			m.setTargets(0, case_ == 2 ? 1 : 0, 1);
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
		m.setTargets(0, 0, 1);
		m.thunder(0, 1, 9500, 1);
		render(m, 2 * RATE);
		assertFalse(m.audible());
	}

	@Test
	public void manyClapsAreCapped() throws Exception
	{
		AudioMixer m = mixer();
		m.setTargets(0, 0, 1);
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
		m.setTargets(0.3f, 0, 1);
		assertTrue(m.audible());
		render(m, RATE);
		m.setTargets(0, 0, 1);
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
	public void insideARoomIsIndoorsButAGatewayIsNot()
	{
		RoomMap m = RoomMapTest.house(RoomMapTest.walls());
		// inside the house, a little above its floor; its yard; and a place with no room map
		assertTrue(WeatherAudio.indoors(m, RoomMapTest.world(10), -40, RoomMapTest.world(10)));
		assertFalse(WeatherAudio.indoors(m, RoomMapTest.world(14), -40, RoomMapTest.world(14)));
		assertFalse(WeatherAudio.indoors(null, RoomMapTest.world(10), -40, RoomMapTest.world(10)));
		// off the map it is outdoors
		assertFalse(WeatherAudio.indoors(m, -100000, -40, 100000));
	}

	@Test
	public void samplesBecomeLittleEndianPcm()
	{
		byte[] out = new byte[8];
		WeatherAudio.toBytes(new float[]{0, 1, -1, 2}, 4, out);
		assertArrayEquals(new byte[]{0, 0, (byte) 0xFF, 0x7F, 0x01, (byte) 0x80, (byte) 0xFF, 0x7F}, out);
	}
}
