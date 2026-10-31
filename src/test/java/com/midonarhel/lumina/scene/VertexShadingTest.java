package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import net.runelite.api.Perspective;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 117 HD's normals for model faces ({@link VertexShading#faceNormals}, docs/117hd-model-shading.md §1.2) on small
 * synthetic meshes: a face the game marks flat gets its own normal on all three corners, a smooth face the game's
 * vertex normals, a smooth face whose three are zero its own normal again, a model without normals all flat. Also
 * 117 HD's undo of vanilla shading and the octahedral encoding.
 */
public class VertexShadingTest
{
	/** A mesh built face by face: positions, triangle indices, and which faces the game shades smoothly. */
	private static final class Mesh
	{
		float[] vx = new float[64], vy = new float[64], vz = new float[64];
		int[] i1 = new int[64], i2 = new int[64], i3 = new int[64];
		boolean[] smooth = new boolean[64];
		int vertices, faces;

		int vertex(float x, float y, float z)
		{
			vx[vertices] = x;
			vy[vertices] = y;
			vz[vertices] = z;
			return vertices++;
		}

		int face(int a, int b, int c, boolean smooth)
		{
			i1[faces] = a;
			i2[faces] = b;
			i3[faces] = c;
			this.smooth[faces] = smooth;
			return faces++;
		}

		/**
		 * The vertex normals the game would give this mesh (the client's model lighting): per vertex the sum of the
		 * smooth faces' normals, each scaled to length 256; flat faces add nothing.
		 */
		int[][] gameNormals()
		{
			int[] nx = new int[vertices], ny = new int[vertices], nz = new int[vertices];
			for (int f = 0; f < faces; ++f)
			{
				if (!smooth[f])
				{
					continue;
				}
				float[] n = unit(f);
				for (int v : new int[]{i1[f], i2[f], i3[f]})
				{
					nx[v] += (int) (n[0] * 256);
					ny[v] += (int) (n[1] * 256);
					nz[v] += (int) (n[2] * 256);
				}
			}
			return new int[][]{nx, ny, nz};
		}

		/** The face's unit normal, cross(B - A, C - A). */
		float[] unit(int f)
		{
			int a = i1[f], b = i2[f], c = i3[f];
			float ux = vx[b] - vx[a], uy = vy[b] - vy[a], uz = vz[b] - vz[a];
			float wx = vx[c] - vx[a], wy = vy[c] - vy[a], wz = vz[c] - vz[a];
			float x = uy * wz - uz * wy, y = uz * wx - ux * wz, z = ux * wy - uy * wx;
			float l = (float) Math.sqrt(x * x + y * y + z * z);
			return new float[]{x / l, y / l, z / l};
		}

		/** faceNormals for face f with these vertex normals (null: the model has none); returns the kind, out filled. */
		int normals(int f, int[][] game, int[] out)
		{
			int a = i1[f], b = i2[f], c = i3[f];
			return VertexShading.faceNormals(out, game != null ? game[0] : null, game != null ? game[1] : null,
				game != null ? game[2] : null, !smooth[f], a, b, c,
				vx[a], vy[a], vz[a], vx[b], vy[b], vz[b], vx[c], vy[c], vz[c]);
		}
	}

	/**
	 * A closed n-sided prism around the y axis, radius 100, from y = 0 down to y = -200 (the game's y points down),
	 * each side a quad of two triangles wound outward, the caps fans; sides smooth or flat, caps flat.
	 */
	private static Mesh prism(int sides, boolean smoothSides)
	{
		Mesh m = new Mesh();
		for (int i = 0; i < sides; ++i)
		{
			double t = 2 * Math.PI * i / sides;
			m.vertex((float) (100 * Math.cos(t)), 0, (float) (100 * Math.sin(t)));
		}
		for (int i = 0; i < sides; ++i)
		{
			m.vertex(m.vx[i], -200, m.vz[i]);
		}
		for (int i = 0; i < sides; ++i)
		{
			int b0 = i, b1 = (i + 1) % sides, t0 = sides + i, t1 = sides + (i + 1) % sides;
			m.face(b0, b1, t1, smoothSides);
			m.face(b0, t1, t0, smoothSides);
		}
		for (int i = 1; i + 1 < sides; ++i)
		{
			m.face(0, i + 1, i, false); // bottom
			m.face(sides, sides + i, sides + i + 1, false); // top
		}
		return m;
	}

	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	/** Corner i of faceNormals' output as a unit vector. */
	private static float[] corner(int[] n, int i)
	{
		float x = n[i * 3], y = n[i * 3 + 1], z = n[i * 3 + 2];
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[]{x / l, y / l, z / l};
	}

	/** The outward direction of a prism's side face f (of {@link #prism}'s): horizontal, through its quad's middle. */
	private static float[] radial(int sides, int f)
	{
		double t = 2 * Math.PI * (f / 2 + 0.5) / sides;
		return new float[]{(float) Math.cos(t), 0, (float) Math.sin(t)};
	}

	@Test
	public void theTestPrismsAreWoundOutward()
	{
		Mesh m = prism(8, false);
		for (int f = 0; f < 16; ++f)
		{
			assertTrue(dot(m.unit(f), radial(8, f)) > 0.9f);
		}
		assertTrue(m.unit(16)[1] > 0.99f); // bottom cap faces down (+y)
		assertTrue(m.unit(17)[1] < -0.99f); // top cap faces up (-y)
	}

	@Test
	public void aFlatCubeKeepsEachFacesOwnNormal()
	{
		// every face flat: all three corners carry the face's normal, unit length times 2048, sides 90 degrees apart
		Mesh m = prism(4, false);
		int[][] game = m.gameNormals();
		int[] n = new int[9];
		for (int f = 0; f < m.faces; ++f)
		{
			assertEquals(VertexShading.FACE, m.normals(f, game, n));
			float[] u = m.unit(f);
			for (int i = 0; i < 3; ++i)
			{
				assertArrayEquals(new int[]{(int) (u[0] * 2048), (int) (u[1] * 2048), (int) (u[2] * 2048)},
					new int[]{n[i * 3], n[i * 3 + 1], n[i * 3 + 2]});
			}
		}
	}

	@Test
	public void aFlatOctagonalTrunkStaysFaceted()
	{
		// 117 HD rounds no flat face: an 8-sided prism the game marks flat shades as eight facets, each side's
		// corners its own radial normal, neighbours 45 degrees apart
		Mesh m = prism(8, false);
		int[][] game = m.gameNormals();
		int[] n = new int[9];
		for (int f = 0; f < 16; ++f)
		{
			assertEquals(VertexShading.FACE, m.normals(f, game, n));
			for (int i = 0; i < 3; ++i)
			{
				assertTrue(dot(corner(n, i), radial(8, f)) > 0.999f);
			}
		}
		m.normals(0, game, n);
		float[] side0 = corner(n, 0);
		m.normals(2, game, n);
		assertEquals(Math.cos(Math.PI / 4), dot(side0, corner(n, 0)), 1e-3);
	}

	@Test
	public void aSmoothOctagonalTrunkTakesTheGamesVertexNormals()
	{
		// sides the game shades smoothly: each corner is the game's vertex normal, untouched: horizontal (the flat
		// caps add nothing to it) and between the two sides that meet there, so the corners of one face differ
		Mesh m = prism(8, true);
		int[][] game = m.gameNormals();
		int[] n = new int[9];
		for (int f = 0; f < 16; ++f)
		{
			assertEquals(VertexShading.VERTEX, m.normals(f, game, n));
			int[] idx = {m.i1[f], m.i2[f], m.i3[f]};
			for (int i = 0; i < 3; ++i)
			{
				assertArrayEquals(new int[]{game[0][idx[i]], game[1][idx[i]], game[2][idx[i]]},
					new int[]{n[i * 3], n[i * 3 + 1], n[i * 3 + 2]});
				float[] c = corner(n, i);
				assertEquals(0, c[1], 1e-6);
				assertTrue(dot(c, radial(8, f)) > Math.cos(Math.PI / 4));
			}
			assertTrue(dot(corner(n, 0), corner(n, 1)) < 0.99f);
		}
		// the caps stay flat: straight down and up
		assertEquals(VertexShading.FACE, m.normals(16, game, n));
		assertTrue(n[1] >= 2047);
		assertEquals(VertexShading.FACE, m.normals(17, game, n));
		assertTrue(n[1] <= -2047);
	}

	@Test
	public void aTwoSidedLeafQuadLightsEachSideByItsOwnNormal()
	{
		// a vertical leaf: one quad twice, the second wound the other way, sharing the four vertices
		for (boolean smooth : new boolean[]{false, true})
		{
			Mesh m = new Mesh();
			int a = m.vertex(0, 0, 0), b = m.vertex(100, 0, 0), c = m.vertex(100, -100, 0), d = m.vertex(0, -100, 0);
			int front1 = m.face(a, b, c, smooth), front2 = m.face(a, c, d, smooth);
			int back1 = m.face(a, c, b, smooth), back2 = m.face(a, d, c, smooth);
			int[][] game = m.gameNormals();
			if (smooth)
			{
				// the game's sums cancel to zero at every vertex
				for (int v = 0; v < 4; ++v)
				{
					assertEquals(0, game[0][v] | game[1][v] | game[2][v]);
				}
			}
			int[] n = new int[9];
			for (int f : new int[]{front1, front2, back1, back2})
			{
				// flat: the face normal by the flat rule; smooth: the face normal standing in for three zeros
				assertEquals(smooth ? VertexShading.ZERO_VERTEX : VertexShading.FACE, m.normals(f, game, n));
				float expected = f == front1 || f == front2 ? -1 : 1;
				for (int i = 0; i < 3; ++i)
				{
					assertArrayEquals(new float[]{0, 0, expected}, corner(n, i), 1e-6f);
				}
			}
		}
	}

	@Test
	public void aModelWithoutNormalsIsFlat()
	{
		Mesh m = prism(8, true);
		int[] n = new int[9];
		assertEquals(VertexShading.FACE, m.normals(0, null, n));
		assertTrue(dot(corner(n, 0), radial(8, 0)) > 0.999f);
	}

	@Test
	public void aPartlyZeroFaceKeepsItsZeros()
	{
		// one corner without a normal stays without one (the shader's triangle normal), the others are the game's
		Mesh m = prism(8, true);
		int[][] game = m.gameNormals();
		int zeroed = m.i2[0];
		game[0][zeroed] = game[1][zeroed] = game[2][zeroed] = 0;
		int[] n = new int[9];
		assertEquals(VertexShading.VERTEX, m.normals(0, game, n));
		assertEquals(0, VertexShading.encodeNormal(n, 1));
		assertTrue(VertexShading.encodeNormal(n, 0) != 0);
	}

	@Test
	public void rotationTurnsTheNormalsAboutY()
	{
		// a quarter turn (orientation 512): x' = z, z' = -x, y kept, on all three corners
		// (the game's table has sin = 65535/65536 there, and >> 16 floors: within 1 of the exact turn)
		int[] n = {2048, 5, 0, 0, 7, 1000, -300, 9, 400};
		VertexShading.rotateNormals(n, Perspective.SINE[512], Perspective.COSINE[512]);
		int[] exact = {0, 5, -2048, 1000, 7, 0, 400, 9, 300};
		for (int i = 0; i < 9; ++i)
		{
			assertEquals(exact[i], n[i], 1);
		}
	}

	@Test
	public void undoBrightensWhatVanillaDarkenedWith117HdsConstants()
	{
		int color = 10 << 10 | 0 << 7 | 40; // hue 10, saturation 0, lightness 40
		// lit along x only: dot with (1,1,1)/√3 is 0.577; adjust = 10 - 40 + (40 - 3) * 3 = 81 → +46
		assertEquals(10 << 10 | 86, VertexShading.undoVanillaShading(color, 2048, 0, 0));
		// facing away from vanilla's light: unchanged
		assertEquals(color, VertexShading.undoVanillaShading(color, -2048, 0, 0));
		// no normal: unchanged
		assertEquals(color, VertexShading.undoVanillaShading(color, 0, 0, 0));
		// below IGNORE_LOW_LIGHTNESS only BASE_LIGHTEN counts: adjust = 10 - 2 = 8 → +4
		assertEquals(10 << 10 | 6, VertexShading.undoVanillaShading(10 << 10 | 2, 2048, 0, 0));
		// saturated colours are capped (saturation 2: 59)
		assertEquals(10 << 10 | 2 << 7 | 59, VertexShading.undoVanillaShading(10 << 10 | 2 << 7 | 40, 2048, 2048, 2048));
		VertexShading.undoVanillaShading = false;
		try
		{
			assertEquals(color, VertexShading.undoVanillaShading(color, 2048, 0, 0));
		}
		finally
		{
			VertexShading.undoVanillaShading = true;
		}
	}

	@Test
	public void normalsRoundTripThroughTheOctahedralEncoding()
	{
		float[][] cases = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {0, 0, -1}, {0.577f, 0.577f, 0.577f}, {-0.3f, 0.2f, -0.933f}};
		for (float[] c : cases)
		{
			float[] d = VertexShading.decodeNormal(VertexShading.encodeNormal(c[0], c[1], c[2]));
			assertTrue(dot(c, d) > 0.995f);
		}
		assertEquals(0, VertexShading.encodeNormal(0, 0, 0));
	}
}
