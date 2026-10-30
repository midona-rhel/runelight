package com.midonarhel.runelight;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
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

	private static boolean fake(int model, int texture, int hsl, int opacity)
	{
		return SurfaceMaterials.isFake(SurfaceMaterials.face(model, texture, hsl, opacity));
	}

	@Test
	public void facesWithTheWaterTexturesAreFakeWaterOnAnyModel()
	{
		for (int texture : new int[]{1, 24, 25})
		{
			assertTrue(fake(0, texture, hsl(0, 0, 50), OPAQUE));
		}
		assertFalse(fake(0, 2, hsl(0, 0, 50), OPAQUE));
		assertFalse(fake(0, -1, hsl(42, 3, 50), OPAQUE));
		// fake water takes no snow
		int bits = SurfaceMaterials.face(0, 1, 0, OPAQUE);
		assertEquals(2, bits >> SurfaceMaterials.SNOW_SHIFT & 3);
	}

	@Test
	public void fakeWaterGroupsPickFacesByTheirRules()
	{
		// a model whose water is untextured blue (hue 42); its other faces stay what they were
		int fountain = SurfaceMaterials.forObject(878);
		assertTrue(fake(fountain, -1, hsl(42, 3, 50), OPAQUE));
		assertFalse(fake(fountain, -1, hsl(10, 3, 50), OPAQUE));
		// one whose water is the translucent faces of one hue
		int pool = SurfaceMaterials.forObject(2654);
		assertTrue(fake(pool, -1, hsl(32, 3, 50), 100));
		assertFalse(fake(pool, -1, hsl(32, 3, 50), OPAQUE));
	}

	@Test
	public void ordinaryModelsHaveNoFakeWater()
	{
		assertFalse(fake(SurfaceMaterials.forObject(PRIF_FURNACE), -1, hsl(42, 3, 50), OPAQUE));
		assertFalse(SurfaceMaterials.isFake(0));
		assertFalse(SurfaceMaterials.isFake(WaterMap.BED_FLAG));
		assertFalse(SurfaceMaterials.isFake(WaterMap.LAND_FLAG));
	}

	@Test
	public void theSpanIsTheLowestAndHighestFakeVertex()
	{
		// three faces, the second the only one that is not water; y is down, so the foot is the largest
		short[] textures = {1, -1, 1};
		int[] colors = {0, 0, 0};
		int[] a = {0, 3, 6}, b = {1, 4, 7}, c = {2, 5, 8};
		float[] y = {-100, -80, -60, 40, 50, 60, -40, -30, -20};
		float[] span = SurfaceMaterials.fakeSpan(0, textures, 3, colors, null, a, b, c, y);
		assertEquals(-20, span[0], 0);
		assertEquals(-100, span[1], 0);
		// no water faces and no group: no foot
		assertTrue(Float.isNaN(SurfaceMaterials.fakeSpan(0, new short[]{-1, -1, -1}, 3, colors, null, a, b, c, y)[0]));
		assertTrue(Float.isNaN(SurfaceMaterials.fakeSpan(0, null, 3, colors, null, a, b, c, y)[1]));
		assertEquals(80, SurfaceMaterials.fakeHeight(-20, -100));
		assertEquals(0, SurfaceMaterials.fakeHeight(-20, 40));
	}
}
