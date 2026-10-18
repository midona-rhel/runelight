package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.midonarhel.runelight.config.WeatherMode;
import org.junit.BeforeClass;
import org.junit.Test;

public class WindTest
{
	private static final float DT = 1 / 60f;

	@BeforeClass
	public static void load()
	{
		WeatherTestData.load();
	}

	@Test
	public void continuousThroughWeatherChanges()
	{
		// two hours at 60 fps, the weather changing every three minutes between calm, gale and storm; the player
		// walking around
		String[] seq = {"CLEAR", "GALE", "MIST", "THUNDERSTORM", "HARSH_SUN", "BLIZZARD", "FOG", "SANDSTORM"};
		Wind w = new Wind(12345);
		int frames = 2 * 3600 * 60;
		float prevAngle = Float.NaN, prevStrength = 0, prevGust = 0;
		double prevTime = 0;
		float maxTurn = 0, maxStrengthStep = 0, maxGustStep = 0, maxTimeStep = 0;
		float minStrength = Float.MAX_VALUE, maxStrength = 0;
		int gusting = 0;
		for (int i = 0; i < frames; ++i)
		{
			double t = i * DT;
			WeatherType type = WeatherTestData.type(seq[(int) (t / 180) % seq.length]);
			float px = (float) (Math.sin(t / 200) * 6000), pz = (float) (Math.cos(t / 170) * 6000);
			w.update(t, DT, type.wind, type.gustiness, true, true, px, pz);
			float angle = (float) Math.atan2(w.getDirZ(), w.getDirX());
			assertEquals(1, Math.hypot(w.getDirX(), w.getDirZ()), 1e-5);
			if (!Float.isNaN(prevAngle))
			{
				float d = Math.abs(angle - prevAngle);
				maxTurn = Math.max(maxTurn, (float) Math.min(d, 2 * Math.PI - d));
				maxStrengthStep = Math.max(maxStrengthStep, Math.abs(w.getStrength() - prevStrength));
				maxGustStep = Math.max(maxGustStep, Math.abs(w.getGust() - prevGust));
				// the sway phase only moves forward, faster in stronger wind, never jumping
				assertTrue(w.getTime() > prevTime);
				maxTimeStep = Math.max(maxTimeStep, (float) (w.getTime() - prevTime));
			}
			assertTrue(w.getGust() >= 0 && w.getGust() <= 1);
			assertTrue(w.getStrength() >= 0 && w.getStrength() < 1.5f);
			if (w.getGust() > 0.2f)
			{
				gusting++;
			}
			minStrength = Math.min(minStrength, w.getStrength());
			maxStrength = Math.max(maxStrength, w.getStrength());
			prevAngle = angle;
			prevStrength = w.getStrength();
			prevGust = w.getGust();
			prevTime = w.getTime();
		}
		// a frame turns the wind by under a tenth of a degree, changes its strength by under a percent; gusts rise
		// over a fraction of a second, not in one frame
		assertTrue("turn " + maxTurn, maxTurn < Math.toRadians(0.1));
		assertTrue("strength step " + maxStrengthStep, maxStrengthStep < 0.01f);
		assertTrue("gust step " + maxGustStep, maxGustStep < 0.06f);
		assertTrue("time step " + maxTimeStep, maxTimeStep < 0.1f);
		// calm and gale both happen, and gusts come and go
		assertTrue(minStrength < 0.1f && maxStrength > 0.8f);
		float share = gusting / (float) frames;
		assertTrue("gusting " + share, share > 0.05f && share < 0.6f);
	}

	@Test
	public void directionWanders()
	{
		Wind w = new Wind(7);
		float minX = 1, maxX = -1;
		for (int i = 0; i < 3600 * 10; ++i)
		{
			w.update(i * 0.1, 0.1f, 0.5f, 0.5f, true, true, 0, 0);
			minX = Math.min(minX, w.getDirX());
			maxX = Math.max(maxX, w.getDirX());
		}
		// over an hour the direction swings well away from where it started
		assertTrue(maxX - minX > 0.5f);
		// steady: no wander, no gusts
		Wind s = new Wind(7);
		s.update(0, 0, 0.5f, 0.8f, false, true, 0, 0);
		float x = s.getDirX();
		for (int i = 1; i < 6000; ++i)
		{
			s.update(i * 0.1, 0.1f, 0.5f, 0.8f, false, true, 0, 0);
			assertEquals(x, s.getDirX(), 0);
			assertEquals(0, s.getGust(), 0);
			assertEquals(0.5f, s.getStrength(), 1e-6f);
		}
	}

	@Test
	public void gustFrontsTravelWithTheWind()
	{
		float dx = 0.6f, dz = 0.8f;
		// the pattern moves downwind with the front: a gust here now is a gust further on later
		for (float front = 0; front < 50000; front += 1234)
		{
			for (float x = -8000; x < 8000; x += 913)
			{
				float z = x * 0.37f + 1000;
				float g = Wind.gustAt(x, z, dx, dz, front, 1);
				float d = 1700;
				float later = Wind.gustAt(x + dx * d, z + dz * d, dx, dz, front + d, 1);
				assertEquals(g, later, 2e-3f);
			}
		}
		// at one spot, gusts come and go as fronts pass: some of the time, not always
		int on = 0, n = 0;
		float prev = 0, maxStep = 0;
		for (float front = 0; front < Wind.GUST_LENGTH * Wind.GUST_PERIOD; front += 20)
		{
			float g = Wind.gustAt(500, -300, dx, dz, front, 1);
			assertTrue(g >= 0 && g <= 1);
			if (g > 0.2f)
			{
				on++;
			}
			maxStep = Math.max(maxStep, Math.abs(g - prev));
			prev = g;
			n++;
		}
		assertTrue("on " + on / (float) n, on > n * 0.1f && on < n * 0.6f);
		// smooth: 20 units of travel change it by little
		assertTrue("step " + maxStep, maxStep < 0.08f);
		// it wraps seamlessly: the front can be kept modulo the period
		float period = Wind.GUST_LENGTH * Wind.GUST_PERIOD;
		assertEquals(Wind.gustAt(321, 654, dx, dz, 100, 1), Wind.gustAt(321, 654, dx, dz, 100 + period, 1), 1e-3f);
		// no gustiness, no gusts
		assertEquals(0, Wind.gustAt(321, 654, dx, dz, 100, 0), 0);
	}

	@Test
	public void weatherDrivesTheWind()
	{
		Weather calm = new Weather();
		calm.update(WeatherMode.CLEAR, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		Weather gale = new Weather();
		gale.update(WeatherMode.GALE, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		assertTrue(gale.getWind().getStrength() > 3 * calm.getWind().getStrength());
		assertTrue(gale.getWind().getGustiness() > calm.getWind().getGustiness());
		// the water follows it
		float[] a = new float[3], b = new float[3];
		calm.getWaterWind(a);
		gale.getWaterWind(b);
		assertTrue(b[2] > a[2]);
		assertEquals(1, Math.hypot(b[0], b[1]), 1e-5);
		// underground the air is still for anything that sways
		Weather cave = new Weather();
		cave.update(WeatherMode.GALE, null, 60, false, false, 100, true, false, 12, null, false, 0, 0, 0, 0);
		assertTrue(cave.isWindStill());
		cave.getWaterWind(a);
		assertEquals(0, a[2], 0);
	}

	@Test
	public void weatherSetsItsLook()
	{
		Weather w = new Weather();
		float[] o = new float[7];
		w.update(WeatherMode.HARSH_SUN, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		w.getOvercast(o);
		// stronger, crisper sun, not hazy
		assertTrue(o[3] > 0.2f);
		assertTrue(o[4] < 0.2f);
		w.update(WeatherMode.SANDSTORM, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		w.getOvercast(o);
		// weaker, hazy, warm sun; dust tints the fog; dust is not snow: the overcast does not whiten
		assertTrue(o[3] < 0 && o[4] > 0.5f && o[5] > 0.5f);
		assertTrue(w.getFogTint()[0] > w.getFogTint()[2]);
		assertEquals(0, o[1], 1e-6f);
		w.update(WeatherMode.HIGH_CLOUDS, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		assertTrue(w.cloudWisp() > 0.9f && w.cloudDark() < 0);
		// Off is neutral
		w.update(WeatherMode.OFF, null, 60, false, false, 100, true, true, 12, null, false, 0, 0, 0, 0);
		w.getOvercast(o);
		for (int i = 3; i < 7; ++i)
		{
			assertEquals(0, o[i], 1e-6f);
		}
		assertEquals(0, w.cloudCover(), 0);
		// and so is everything underground
		w.update(WeatherMode.SANDSTORM, null, 60, false, false, 100, true, false, 12, null, false, 0, 0, 0, 0);
		w.getOvercast(o);
		for (int i = 3; i < 7; ++i)
		{
			assertEquals(0, o[i], 1e-6f);
		}
	}

	@Test
	public void showersComeAndGo()
	{
		int on = 0, off = 0, n = 0;
		for (double t = 0; t < 7200; t += 0.5)
		{
			float s = Weather.showers(t);
			assertTrue(s >= 0 && s <= 1);
			on += s > 0.9f ? 1 : 0;
			off += s < 0.1f ? 1 : 0;
			n++;
		}
		// rain most of the time, with real breaks of sun between
		assertTrue("on " + on / (float) n, on > n * 0.4f);
		assertTrue("off " + off / (float) n, off > n * 0.2f);
	}
}
