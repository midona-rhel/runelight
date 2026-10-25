package com.midonarhel.runelight;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AutoLightRulesTest
{
	private static final AutoLightRules RULES = new AutoLightRules(new Gson(),
		AutoLightRulesTest.class.getResourceAsStream("/com/midonarhel/runelight/autolights.json"));

	private static String preset(String name)
	{
		LightDefinition d = RULES.match(name);
		return d != null ? d.description : null;
	}

	@Test
	public void namesMatchWholeWordsWhateverTheirCase()
	{
		assertEquals("brazier", preset("Brazier"));
		assertEquals("brazier", preset("BRAZIER"));
		assertEquals("torch", preset("Wall torch"));
		assertEquals("torch", preset("Torches"));
		assertEquals("torch", preset("Wall-mounted torch"));
		assertEquals("lantern", preset("Wall lantern"));
		assertEquals("lantern", preset("Jack-o'-lantern"));
		assertEquals("lantern", preset("Lamp"));
		assertEquals("candle", preset("Candles"));
		assertEquals("candle", preset("Chandelier"));
		assertEquals("furnace", preset("Furnace"));
		assertEquals("range", preset("Cooking range"));
		assertNull("not a whole word", preset("Firebrand"));
		assertNull("not a whole word", preset("Orange tree"));
		assertNull(preset("Torchbearer"));
		assertNull(preset("Table"));
		assertNull(preset(null));
		assertNull(preset(""));
	}

	@Test
	public void namesOfSeveralWordsNeedThemInOrder()
	{
		assertEquals("brazier", preset("Fire pit"));
		assertEquals("lantern", preset("Street light"));
		assertEquals("lantern", preset("Old STREET   light (lit)"));
		assertNull(preset("Light street"));
		assertNull(preset("Street of light"));
	}

	@Test
	public void unlitAndNonLightsGetNothing()
	{
		assertNull(preset("Unlit torch"));
		assertNull(preset("Torch (unlit)"));
		assertNull(preset("Extinguished brazier"));
		assertNull(preset("Burnt out fire"));
		assertNull(preset("Lantern shop sign"));
		assertNull(preset("Candle maker's stall"));
		assertNull(preset("Broken lamp"));
		assertNull(preset("Fire altar"));
		assertNull(preset("Empty fire pit"));
	}

	@Test
	public void theFirstRuleThatFitsWins()
	{
		assertEquals("torch", preset("Torch lantern"));
		assertEquals("brazier", preset("Fire torch"));
		assertSame(RULES.match("Brazier"), RULES.match("Fire pit"));
	}

	@Test
	public void presetsAreLikeTheHandMadeLights()
	{
		LightDefinition brazier = RULES.match("Brazier"), torch = RULES.match("Torch"), lantern = RULES.match("Lantern");
		LightDefinition candle = RULES.match("Candle"), furnace = RULES.match("Furnace");
		for (LightDefinition d : new LightDefinition[]{brazier, torch, lantern, candle, furnace, RULES.match("Range")})
		{
			assertNotNull(d);
			assertEquals(LightDefinition.FLICKER, d.kind);
			assertTrue(d.range > 0 && d.range <= 30);
			assertTrue(d.strength >= 5 && d.strength <= 15);
			// warm: red over green over blue
			assertTrue(d.color[0] > d.color[1] && d.color[1] > d.color[2]);
		}
		assertTrue("braziers reach further than torches", brazier.radius > torch.radius);
		assertTrue("torches further than candles", torch.radius > candle.radius);
		assertTrue("lanterns are the more yellow", lantern.color[1] > torch.color[1]);
		assertTrue("lanterns flicker the least of the three", lantern.range < torch.range);
		assertTrue(furnace.color[1] < torch.color[1]);
		assertTrue("prepared", brazier.red > 0 && brazier.red > brazier.blue);
	}
}
