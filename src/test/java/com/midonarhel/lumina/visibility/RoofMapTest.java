package com.midonarhel.lumina.visibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

/** The drawn roofs as lamp.glsl reads them: a roof the client hides over the player stops nothing. */
public class RoofMapTest
{
	private static final int SIZE = 8;

	@Test
	public void aRoofIsDrawnUnlessTheClientHidesItAboveThePlayer()
	{
		// a roof on level 1 over tile (3, 4), its lowest point 240 above the ground at 0 (y down), roof id 7
		RoofMap m = RoofMap.of(SIZE, 1, 3, 4, -240, 7);
		float[] out = new float[SIZE * SIZE];
		Set<Integer> hidden = Collections.singleton(7);

		m.bottoms(Collections.emptySet(), 0, out);
		assertEquals(-240 + RoofMap.BIAS, out[4 * SIZE + 3], 0);
		assertEquals(0, out[4 * SIZE + 4], 0);

		// the player under it on level 0: hidden, no roof
		m.bottoms(hidden, 0, out);
		assertEquals(0, out[4 * SIZE + 3], 0);

		// the player on its level: drawn again (Zone.renderOpaque hides only levels above the player's)
		m.bottoms(hidden, 1, out);
		assertEquals(-240 + RoofMap.BIAS, out[4 * SIZE + 3], 0);

		// another building's roof hidden: this one is drawn
		m.bottoms(Collections.singleton(8), 0, out);
		assertEquals(-240 + RoofMap.BIAS, out[4 * SIZE + 3], 0);

		assertTrue(m.any(3, 4));
		assertFalse(m.any(4, 4));
	}
}
