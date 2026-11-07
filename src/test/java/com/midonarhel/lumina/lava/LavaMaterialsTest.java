package com.midonarhel.lumina.lava;

import org.junit.Test;
import static org.junit.Assert.*;

public class LavaMaterialsTest
{
	@Test public void onlyExplicitLavaTerrainIsSelected()
	{
		assertEquals(1,LavaMaterials.variant(19,-1,true));
		assertEquals(0,LavaMaterials.variant(19,-1,false));
		assertEquals(2,LavaMaterials.variant(0,31,false));
		assertEquals(3,LavaMaterials.variant(0,40,true));
		assertEquals(0,LavaMaterials.variant(0,-1,true));
		assertEquals(0,LavaMaterials.variant(0,1,true));
		assertEquals(1,LavaMaterials.variant(152,-1,true,3801,3811));
		assertEquals(0,LavaMaterials.variant(152,-1,false,3801,3811));
		assertEquals(0,LavaMaterials.variant(152,-1,true,3200,3200));
	}

	@Test public void boundaryOverridesOnlyTheCapturedMoltenMaterial()
	{
		for(int id:new int[]{14417,14419,14426,14427,14429})
		{
			assertEquals(1,LavaMaterials.objectFaceVariant(id,-1,9143,9130,9120));
			assertEquals(0,LavaMaterials.objectFaceVariant(id,-1,904,903,902)); // dark rock
			assertEquals(0,LavaMaterials.objectFaceVariant(id,-1,9143,9143,8119)); // different face material
			assertEquals(0,LavaMaterials.objectFaceVariant(id,31,9143,9143,9143)); // no accidental texture override
		}
		assertEquals(0,LavaMaterials.objectFaceVariant(14428,-1,9143,9143,9143));
		assertEquals(0,LavaMaterials.objectFaceVariant(1,-1,9143,9143,9143));
	}

	@Test public void markerPreservesTextureAndTerrainFlags()
	{
		int original=41 | (16 << 9) | (2 << 14);
		for(int kind=1;kind<=3;kind++) {
			int tagged=LavaMaterials.tag(original,kind);
			assertEquals(41,tagged&511);
			assertEquals(16,(tagged>>9)&24);
			assertEquals(3,(tagged>>14)&3);
			assertEquals(kind-1,(tagged>>9)&3);
		}
		assertEquals(original,LavaMaterials.tag(original,0));
	}
}
