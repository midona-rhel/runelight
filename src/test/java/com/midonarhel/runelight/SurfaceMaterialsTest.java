package com.midonarhel.runelight;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import org.junit.BeforeClass;
import org.junit.Test;

public class SurfaceMaterialsTest
{
	private static final int PRIF_FURNACE = 36555, TOA_NEXUS_DOOR = 46155, MACRO_SPOTLIGHT = 15625;
	private static final int GARDEN_FOUNTAIN = 4528, FALADOR_FURNACE_GLOW = 24012, POSH_STOVE = 7421, MIND_PORTAL = 2842;
	private static final int OPAQUE = 255;

	@BeforeClass
	public static void load()
	{
		SurfaceMaterials.load(new Gson());
	}

	private static int hsl(int h, int s, int l)
	{
		return h << 10 | s << 7 | l;
	}

	private static int emissive(int objectId, int texture, int hsl, int opacity)
	{
		return SurfaceMaterials.face(SurfaceMaterials.forObject(objectId), texture, hsl, opacity) >> SurfaceMaterials.SHIFT
			& SurfaceMaterials.EMISSIVE_MASK;
	}

	@Test
	public void colorRulesLightOnlyTheFacesTheyNameAndOnlyUntexturedOnes()
	{
		// Prifddinas furnace: hot faces (hue <= 4, saturation > 4) are magma, the metal is not
		assertEquals(3, emissive(PRIF_FURNACE, -1, hsl(3, 6, 60), OPAQUE));
		assertEquals(0, emissive(PRIF_FURNACE, -1, hsl(3, 4, 60), OPAQUE));
		assertEquals(0, emissive(PRIF_FURNACE, -1, hsl(20, 6, 60), OPAQUE));
		assertEquals(0, emissive(PRIF_FURNACE, 12, hsl(3, 6, 60), OPAQUE));
		// the doors of the Tombs of Amascut nexus: only their flames (hue 63)
		assertEquals(3, emissive(TOA_NEXUS_DOOR, -1, hsl(63, 7, 60), OPAQUE));
		assertEquals(0, emissive(TOA_NEXUS_DOOR, -1, hsl(30, 7, 60), OPAQUE));
	}

	@Test
	public void alphaConditionsSeeTheFacesOpacity()
	{
		// the spotlight's translucent beam
		assertEquals(3, emissive(MACRO_SPOTLIGHT, -1, hsl(0, 0, 100), 100));
		assertEquals(0, emissive(MACRO_SPOTLIGHT, -1, hsl(0, 0, 100), OPAQUE));
		assertEquals(100, SurfaceMaterials.opacity(new byte[]{0, (byte) 155}, 1));
		assertEquals(OPAQUE, SurfaceMaterials.opacity(null, 7));
	}

	@Test
	public void theFirstMatchingRuleDecides()
	{
		// h == 13 is not lit, even translucent; translucent or hue 37 otherwise is lava, hue 36 magma
		assertEquals(0, emissive(GARDEN_FOUNTAIN, -1, hsl(13, 2, 50), 100));
		assertEquals(3, emissive(GARDEN_FOUNTAIN, -1, hsl(20, 2, 50), 100));
		assertEquals(3, emissive(GARDEN_FOUNTAIN, -1, hsl(37, 2, 50), OPAQUE));
		assertEquals(3, emissive(GARDEN_FOUNTAIN, -1, hsl(36, 2, 50), OPAQUE));
		assertEquals(0, emissive(GARDEN_FOUNTAIN, -1, hsl(20, 2, 50), OPAQUE));
	}

	@Test
	public void unlitModelsGlowOnEveryFace()
	{
		// UNLIT is built into 117 HD, so only its overrides name it
		assertEquals(3, emissive(MIND_PORTAL, -1, hsl(10, 2, 50), OPAQUE));
		assertEquals(3, emissive(MIND_PORTAL, 12, 0, OPAQUE));
	}

	@Test
	public void cookingFiresGlowByTheirHotColorsOnly()
	{
		for (int id : new int[]{POSH_STOVE, FALADOR_FURNACE_GLOW})
		{
			assertEquals(3, emissive(id, -1, hsl(7, 6, 60), OPAQUE));
			assertEquals(0, emissive(id, -1, hsl(10, 1, 60), OPAQUE));
			assertEquals(0, emissive(id, -1, hsl(7, 6, 20), OPAQUE));
		}
	}

	@Test
	public void unlistedObjectsGlowNowhere()
	{
		assertEquals(0, emissive(1, -1, hsl(7, 6, 60), 100));
		assertEquals(0, emissive(-1, -1, hsl(7, 6, 60), 100));
	}
}
