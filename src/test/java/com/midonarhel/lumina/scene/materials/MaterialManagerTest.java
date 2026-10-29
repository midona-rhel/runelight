package com.midonarhel.lumina.scene.materials;

import com.google.gson.Gson;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.InfernalCape;
import com.midonarhel.lumina.util.ColorUtils;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

/**
 * Materials whose 117 HD image Lumina does not ship resolve to their vanilla texture with neutral brightness and
 * texture scale (WEB, FORTIS_ROADS and its mirrored child); materials 117 HD has no image for either keep their
 * brightness and texture scale on the vanilla texture (LEAVES_SIDE, TROPICAL_LEAF); the infernal cape defaults to
 * vanilla texture 59 with the shipped lava flow map.
 */
public class MaterialManagerTest
{
	private static final int LAVA_FLOW_LAYER = 300;

	@Test
	public void missingImagesFallBackToVanillaTextures() throws Exception
	{
		assertEquals(InfernalCape.VANILLA, HdSettings.infernalCape);
		MaterialManager.load(new Gson());
		MaterialManager.setImageLayers(Map.of("lava_flow_map", LAVA_FLOW_LAYER));

		float[] web = slotOf("WEB");
		assertEquals(26, web[0], 0);
		assertEquals(1, (int) web[2] & 1); // hasTransparency
		assertNeutral(web);

		float[] roads = slotOf("FORTIS_ROADS");
		assertEquals(121, roads[0], 0);
		assertNeutral(roads);

		float[] mirrored = slotOf("FORTIS_ROADS_MIRRORED");
		assertEquals(122, mirrored[0], 0);
		assertNeutral(mirrored);

		Material cape = MaterialManager.getMaterial("INFERNAL_CAPE");
		assertNotNull(cape);
		assertEquals("INFERNAL_CAPE", cape.name);
		float[] capeSlot = slotOf("INFERNAL_CAPE");
		assertEquals(59, capeSlot[0], 0);
		assertEquals(LAVA_FLOW_LAYER, capeSlot[1], 0);
		assertEquals(0.02f, capeSlot[8], 1e-6);
		assertNeutral(capeSlot);

		// 117 HD has no image for these either: their brightness and texture scale apply to the vanilla texture
		float[] leaves = slotOf("LEAVES_SIDE");
		assertEquals(8, leaves[0], 0);
		assertEquals("texture scale x", 1 / 1.025f, leaves[4], 1e-6);
		assertEquals("texture scale y", 1 / 1.025f, leaves[5], 1e-6);
		float[] tropical = slotOf("TROPICAL_LEAF");
		assertEquals(29, tropical[0], 0);
		assertEquals("brightness", ColorUtils.srgbToLinear(0.735f), tropical[3], 1e-6);

		// a flow map whose image is missing does not flow
		float[] water = slotOf("WATER_FLAT_FLOW");
		assertEquals(-1, water[1], 0);
		assertEquals(0, water[8], 0);
	}

	// the slot's 12 floats (see MaterialManager.fillSlots) of the material used for the name
	private static float[] slotOf(String name)
	{
		Material m = MaterialManager.getMaterial(name);
		assertNotNull(name, m);
		int slot = MaterialManager.slot(m);
		float[] out = new float[MaterialManager.MAX_SLOTS * 12];
		MaterialManager.fillSlots(out, null);
		float[] result = new float[12];
		System.arraycopy(out, slot * 12, result, 0, 12);
		return result;
	}

	private static void assertNeutral(float[] slot)
	{
		assertEquals("brightness", 1, slot[3], 0);
		assertEquals("texture scale x", 1, slot[4], 0);
		assertEquals("texture scale y", 1, slot[5], 0);
	}
}
