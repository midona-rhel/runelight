package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.EnumSet;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The forecast's hard rules on the real data: the climate map's promises (Wilderness never clear, never snow; the
 * desert mostly clear and the only place with sandstorms; Fremennik snow and no rain) and that it is a pure function.
 */
public class ForecastTest
{
	private static final int SLOTS = 1000;

	private static Climate.Table climates;
	private static Forecast forecast;

	@BeforeClass
	public static void load() throws IOException
	{
		Gson gson = new Gson();
		climates = Climate.Table.load(gson);
		forecast = new Forecast(Pattern.Table.load(gson));
	}

	@Test
	public void wildernessIsNeverClearAndNeverSnows()
	{
		Climate wilderness = climates.get("WILDERNESS");
		assertTrue(wilderness.alwaysDim);
		EnumSet<Pattern.Kind> never = EnumSet.of(Pattern.Kind.CLEAR, Pattern.Kind.PARTLY_CLOUDY, Pattern.Kind.SNOW);
		for (long slot = 0; slot < SLOTS; slot++)
		{
			Forecast.Slot s = forecast.at(slot, wilderness);
			assertFalse("slot " + slot + ": " + s, never.contains(s.pattern.kind) || never.contains(s.after.kind));
		}
	}

	@Test
	public void desertIsMostlyClearAndSpecialsStayHome()
	{
		int clear = 0;
		for (long slot = 0; slot < SLOTS; slot++)
		{
			if (forecast.at(slot, climates.get("DESERT")).pattern.kind == Pattern.Kind.CLEAR)
			{
				clear++;
			}
		}
		assertTrue("desert clear share " + clear / (double) SLOTS, clear >= 0.75 * SLOTS);

		for (Climate climate : climates.all())
		{
			for (long slot = 0; slot < SLOTS; slot++)
			{
				Forecast.Slot s = forecast.at(slot, climate);
				// a kind the climate gives weight 0 never occurs: that is also what keeps SANDSTORM in the desert and
				// ASH_FALL on the volcanoes
				assertTrue(climate + " slot " + slot + ": " + s, climate.odds()[s.pattern.kind.ordinal()] > 0);
				assertTrue(climate + " slot " + slot + ": " + s, climate.odds()[s.after.kind.ordinal()] > 0);
				if (s.pattern.kind == Pattern.Kind.SANDSTORM)
				{
					assertEquals("DESERT", climate.id);
				}
				if (s.pattern.kind == Pattern.Kind.ASH_FALL)
				{
					assertEquals("VOLCANIC", climate.id);
				}
			}
		}
	}

	@Test
	public void fremennikSnowsAndNeverRains()
	{
		Climate alpine = climates.get("ALPINE");
		assertTrue(alpine.snows());
		assertTrue(alpine.permanentSnowCover);
		int snow = 0;
		for (long slot = 0; slot < SLOTS; slot++)
		{
			Forecast.Slot s = forecast.at(slot, alpine);
			for (Pattern p : new Pattern[]{s.pattern, s.after})
			{
				assertNotEquals("slot " + slot + ": " + s, Pattern.Precipitation.Kind.RAIN, p.precipitation.kind);
			}
			if (s.pattern.kind == Pattern.Kind.SNOW)
			{
				snow++;
			}
		}
		assertTrue("alpine snow share " + snow / (double) SLOTS, snow > 0.15 * SLOTS);
	}

	@Test
	public void isDeterministicAndStormsAreShort() throws IOException
	{
		Forecast other = new Forecast(Pattern.Table.load(new Gson()));
		for (Climate climate : climates.all())
		{
			Pattern.Kind previous = null;
			for (long slot = -SLOTS; slot < SLOTS; slot++)
			{
				Forecast.Slot a = forecast.at(slot, climate), b = other.at(slot, climate);
				assertEquals(a.pattern.kind, b.pattern.kind);
				assertEquals(a.intensity, b.intensity, 0);
				assertEquals(a.wind, b.wind, 0);
				assertEquals(a.hours, b.hours);
				assertEquals(a.after.kind, b.after.kind);
				if (a.pattern.kind == Pattern.Kind.STORM)
				{
					assertTrue(a.hours >= 4 && a.hours <= 8);
					if (a.hours < Forecast.SLOT_HOURS)
					{
						assertNotEquals(Pattern.Kind.STORM, a.after.kind);
					}
					// never two storm slots in a row within a chain; at a restart (slot % EPOCH == 0) the first slot
					// is not conditioned on the real previous one, so only the weight-0 rule holds there
					if (Math.floorMod(slot, Forecast.EPOCH) != 0)
					{
						assertNotEquals(climate + " slot " + slot, Pattern.Kind.STORM, previous);
					}
				}
				previous = a.after.kind;
			}
		}
	}
}
