package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AutoLightPlacementTest
{
	private static final float EPS = 1e-3f;

	private static int hsl(int hue, int saturation, int lightness)
	{
		return hue << 10 | saturation << 7 | lightness;
	}

	private static final int ORANGE = hsl(5, 7, 70), YELLOW = hsl(10, 6, 80), BROWN = hsl(6, 4, 30);
	private static final int GRAY = hsl(0, 0, 60), RED = hsl(0, 7, 60), PALE = hsl(6, 2, 100);

	/**
	 * A model of triangles, each given as its three corners (x, y, z) and its color.
	 */
	private static ModelData model(int[] colors, float[]... triangles)
	{
		ModelData m = new ModelData();
		m.faceCount = triangles.length;
		m.vertexCount = triangles.length * 3;
		m.verticesX = new float[m.vertexCount];
		m.verticesY = new float[m.vertexCount];
		m.verticesZ = new float[m.vertexCount];
		m.indices1 = new int[m.faceCount];
		m.indices2 = new int[m.faceCount];
		m.indices3 = new int[m.faceCount];
		m.colors1 = colors;
		// flat shaded
		m.colors3 = new int[m.faceCount];
		java.util.Arrays.fill(m.colors3, -1);
		for (int f = 0; f < triangles.length; ++f)
		{
			for (int c = 0; c < 3; ++c)
			{
				m.verticesX[f * 3 + c] = triangles[f][c * 3];
				m.verticesY[f * 3 + c] = triangles[f][c * 3 + 1];
				m.verticesZ[f * 3 + c] = triangles[f][c * 3 + 2];
			}
			m.indices1[f] = f * 3;
			m.indices2[f] = f * 3 + 1;
			m.indices3[f] = f * 3 + 2;
		}
		return m;
	}

	// a triangle with corners at x, y, z and 30 units to the side, around (cx, cy, cz)
	private static float[] around(float cx, float cy, float cz)
	{
		return new float[]{cx - 10, cy, cz, cx + 10, cy, cz - 10, cx, cy, cz + 10};
	}

	@Test
	public void flameColorsAreSaturatedOrangeAndYellow()
	{
		assertTrue(AutoLightPlacement.isFlame(ORANGE));
		assertTrue(AutoLightPlacement.isFlame(YELLOW));
		assertFalse("wood", AutoLightPlacement.isFlame(BROWN));
		assertFalse("stone", AutoLightPlacement.isFlame(GRAY));
		assertFalse("cloth", AutoLightPlacement.isFlame(RED));
		assertFalse("pale", AutoLightPlacement.isFlame(PALE));
		assertFalse("green", AutoLightPlacement.isFlame(hsl(20, 7, 70)));
	}

	@Test
	public void lightGoesAtTheMiddleOfTheFlameFaces()
	{
		// a post of wood faces from the ground to -100, and flame faces at the top, off to one side
		ModelData m = model(new int[]{BROWN, BROWN, ORANGE, YELLOW},
			around(0, 0, 0), around(0, -100, 0), around(40, -120, 20), around(60, -140, 40));
		AutoLightPlacement.Spot spot = new AutoLightPlacement.Spot();
		assertTrue(AutoLightPlacement.locate(m, spot));
		assertTrue(spot.flame);
		// the corners of the two flame faces
		assertEquals(50, spot.x, EPS);
		assertEquals(-130, spot.y, EPS);
		assertEquals(((20 + 20 - 10 + 20 + 10) + (40 + 40 - 10 + 40 + 10)) / 6f, spot.z, EPS);
	}

	@Test
	public void facesThatAreNotDrawnOrTexturedAreNotFlames()
	{
		ModelData m = model(new int[]{ORANGE, ORANGE, YELLOW}, around(0, -10, 0), around(100, -50, 0), around(200, -30, 0));
		// the first is not drawn, the second has a texture
		m.colors3[0] = -2;
		m.textures = new short[]{-1, 12, -1};
		AutoLightPlacement.Spot spot = new AutoLightPlacement.Spot();
		assertTrue(AutoLightPlacement.locate(m, spot));
		assertTrue(spot.flame);
		assertEquals(200, spot.x, EPS);
		assertEquals(-30, spot.y, EPS);
	}

	@Test
	public void theColorsTheFacesWereMadeWithCountWhenTheModelHasThem()
	{
		// lit, the face looks brown; made, it is orange
		ModelData m = model(new int[]{BROWN}, around(10, -80, 0));
		m.unlitColors = new short[]{(short) ORANGE};
		AutoLightPlacement.Spot spot = new AutoLightPlacement.Spot();
		assertTrue(AutoLightPlacement.locate(m, spot));
		assertTrue(spot.flame);
		m.unlitColors = new short[]{(short) BROWN};
		m.colors1 = new int[]{ORANGE};
		assertTrue(AutoLightPlacement.locate(m, spot));
		assertFalse(spot.flame);
	}

	@Test
	public void withoutFlamesLightGoesInTheUpperPartOfTheBounds()
	{
		// a lantern 40 wide and 100 tall, y down: from 0 to -100
		ModelData m = model(new int[]{GRAY, BROWN, GRAY},
			new float[]{-20, 0, -20, 20, 0, -20, 0, 0, 20},
			new float[]{-20, -50, 0, 20, -50, 0, 0, -100, 10},
			new float[]{0, -90, -20, 30, -80, 30, 10, -100, 0});
		AutoLightPlacement.Spot spot = new AutoLightPlacement.Spot();
		assertTrue(AutoLightPlacement.locate(m, spot));
		assertFalse(spot.flame);
		// across the extent in x from -20 to 30 and in z from -20 to 30
		assertEquals(5, spot.x, EPS);
		assertEquals(5, spot.z, EPS);
		// a quarter of the height below the top
		assertEquals(-100 + 25, spot.y, EPS);
	}

	@Test
	public void aModelWithNothingInItGetsNoLight()
	{
		assertFalse(AutoLightPlacement.locate(new ModelData(), new AutoLightPlacement.Spot()));
	}

	@Test
	public void turningTheModelMovesThePointAsTheUploaderDoes()
	{
		AutoLightPlacement.Spot spot = new AutoLightPlacement.Spot();
		spot.x = 30;
		spot.z = 10;
		float[] xz = new float[2];
		AutoLightPlacement.turn(spot, 0, xz);
		assertEquals(30, xz[0], EPS);
		assertEquals(10, xz[1], EPS);
		// a quarter turn: x goes to z, z to -x
		AutoLightPlacement.turn(spot, 512, xz);
		assertEquals(10, xz[0], EPS);
		assertEquals(-30, xz[1], EPS);
		AutoLightPlacement.turn(spot, 1024, xz);
		assertEquals(-30, xz[0], EPS);
		assertEquals(-10, xz[1], EPS);
	}

	@Test
	public void wallDecorationsMoveAwayFromTheirWall()
	{
		float[] xz = new float[2];
		// types 4 and 5 with the wall on the west, north, east, south: out to the east, south, west, north
		AutoLightPlacement.awayFromWall(4, xz);
		assertEquals(16, xz[0], EPS);
		assertEquals(0, xz[1], EPS);
		AutoLightPlacement.awayFromWall(5 | 1 << 6, xz);
		assertEquals(0, xz[0], EPS);
		assertEquals(-16, xz[1], EPS);
		AutoLightPlacement.awayFromWall(4 | 2 << 6, xz);
		assertEquals(-16, xz[0], EPS);
		assertEquals(0, xz[1], EPS);
		AutoLightPlacement.awayFromWall(5 | 3 << 6, xz);
		assertEquals(0, xz[0], EPS);
		assertEquals(16, xz[1], EPS);
		// a decoration on a corner is an eighth round
		AutoLightPlacement.awayFromWall(7, xz);
		assertEquals(16 * Math.sqrt(0.5), xz[0], EPS);
		assertEquals(-16 * Math.sqrt(0.5), xz[1], EPS);
	}
}
