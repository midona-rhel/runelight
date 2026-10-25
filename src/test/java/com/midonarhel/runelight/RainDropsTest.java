package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.*;

public class RainDropsTest
{
	@Test
	public void lightRainKeepsItsDropsAndTheHeaviestIsEased()
	{
		for (float rain : new float[]{0, 0.25f, 0.45f, 0.55f, 0.6f, 0.85f})
		{
			assertEquals(rain, Weather.dropRain(rain), 0);
		}
		float thunder = Weather.dropRain(1.0f), monsoon = Weather.dropRain(1.2f);
		assertTrue(thunder < 1.0f && monsoon < 1.2f);
		assertTrue("still more rain, more drops", monsoon > thunder && thunder > 0.85f);
		assertTrue("continuous at the knee", Math.abs(Weather.dropRain(0.8501f) - 0.85f) < 1e-3);
	}
}
