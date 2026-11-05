package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.light.CardLamps;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The oracle's rays on a scene made in code: a floor and a wall with a gap, a lamp beside it. */
public class CardOracleTest
{
	private static CardMesh mesh(float[][] quads, int category)
	{
		CardMesh m = new CardMesh();
		m.v = new float[quads.length * 2 * 9];
		m.cat = new byte[quads.length * 2];
		m.tex = new int[quads.length * 2];
		java.util.Arrays.fill(m.tex, -1);
		m.tris = quads.length * 2;
		for (int q = 0; q < quads.length; ++q)
		{
			float[] c = quads[q];
			System.arraycopy(new float[]{c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]}, 0, m.v, q * 18, 9);
			System.arraycopy(new float[]{c[0], c[1], c[2], c[6], c[7], c[8], c[9], c[10], c[11]}, 0, m.v, q * 18 + 9, 9);
			m.cat[q * 2] = m.cat[q * 2 + 1] = (byte) category;
		}
		return m;
	}

	// a wall in the plane z = 0, x from x0 to x1, y from 0 up to -300 (y is down)
	private static float[] wall(float x0, float x1)
	{
		return new float[]{x0, 0, 0, x1, 0, 0, x1, -300, 0, x0, -300, 0};
	}

	@Test
	public void wallStopsWhatIsBehindItAndAGapDoesNot()
	{
		CardBvh bvh = new CardBvh(mesh(new float[][]{wall(-1000, -40), wall(40, 1000)}, CardMesh.WALL));
		CardBvh.Scratch s = new CardBvh.Scratch();
		// from behind the wall, to a lamp in front of it: through the wall, then through the gap
		assertTrue(bvh.occluded(200, -100, -200, 0, 0, 400, 0, 1, s));
		assertFalse(bvh.occluded(0, -100, -200, 0, 0, 400, 0, 1, s));
		assertEquals(CardMesh.WALL, bvh.blockers(200, -100, -200, 0, 0, 400, 0, 1, s));
		assertEquals(0, bvh.blockers(0, -100, -200, 0, 0, 400, 0, 1, s));
		// over it
		assertFalse(bvh.occluded(200, -400, -200, 0, 0, 400, 0, 1, s));
		assertTrue(bvh.closest(200, -100, -200, 0, 0, 400, 0, 1, s));
		assertEquals(0.5f, s.t, 1e-3f);
	}

	@Test
	public void ballsPartBehindTheWallCountsForNothing()
	{
		// a lamp 24 off the wall with a ball of 40: its points past the wall aren't in sight of its centre
		CardBvh bvh = new CardBvh(mesh(new float[][]{wall(-1000, 1000)}, CardMesh.WALL));
		float[] lamp = new float[CardLamps.FIELDS];
		lamp[CardLamps.X] = lamp[CardLamps.OX] = 0;
		lamp[CardLamps.Y] = -100;
		lamp[CardLamps.Z] = lamp[CardLamps.OZ] = 24;
		lamp[CardLamps.RADIUS] = 500;
		float[][] ball = CardOracle.ballOf(bvh, lamp);
		assertTrue(ball.length > 3 && ball.length < CardOracle.BALL + 1);
		CardBvh.Scratch s = new CardBvh.Scratch();
		// from behind the wall nothing of the ball arrives
		for (float[] b : ball)
		{
			assertTrue(b[2] >= 0);
			assertTrue(bvh.occluded(0, -100, -100, b[0], b[1] + 100, b[2] + 100, 0, 1, s));
		}
	}

	@Test
	public void classOfSampleRoundTrips()
	{
		short c = (short) (CardOracle.BLOCKED | (CardMesh.HIDDEN | CardMesh.WALL) << 2 | CardOracle.OTHER);
		assertEquals(CardOracle.BLOCKED, CardOracle.classOf(c));
		assertEquals(CardMesh.HIDDEN | CardMesh.WALL, CardOracle.blockersOf(c));
		assertTrue((c & CardOracle.OTHER) != 0);
	}
}
