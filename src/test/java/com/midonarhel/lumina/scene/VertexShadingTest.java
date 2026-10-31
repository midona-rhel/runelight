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
		 * The vertex normals the game would give this mesh, by the client's code (ModelData.calculateVertexNormals,
		 * as RuneLite's cache ModelDefinition.computeNormals has it): per face cross(B - A, C - A) in ints, halved
		 * while a component is past 8192, scaled to length 256; a smooth face (render type 0) adds it to each of its
		 * three vertices, a flat one (type 1, faceColors3 == -1 once lit) to none. Model.getVertexNormalsX/Y/Z hand
		 * these sums out (RSModelDataMixin.vertexNormals).
		 */
		int[][] gameNormals()
		{
			int[] nx = new int[vertices], ny = new int[vertices], nz = new int[vertices];
			for (int f = 0; f < faces; ++f)
			{
				int a = i1[f], b = i2[f], c = i3[f];
				int x1 = (int) vx[b] - (int) vx[a], y1 = (int) vy[b] - (int) vy[a], z1 = (int) vz[b] - (int) vz[a];
				int x2 = (int) vx[c] - (int) vx[a], y2 = (int) vy[c] - (int) vy[a], z2 = (int) vz[c] - (int) vz[a];
				int x = y1 * z2 - y2 * z1, y = z1 * x2 - z2 * x1, z = x1 * y2 - x2 * y1;
				while (x > 8192 || y > 8192 || z > 8192 || x < -8192 || y < -8192 || z < -8192)
				{
					x >>= 1;
					y >>= 1;
					z >>= 1;
				}
				int length = (int) Math.sqrt(x * x + y * y + z * z);
				if (length <= 0)
				{
					length = 1;
				}
				x = x * 256 / length;
				y = y * 256 / length;
				z = z * 256 / length;
				if (!smooth[f])
				{
					continue;
				}
				for (int v : new int[]{a, b, c})
				{
					nx[v] += x;
					ny[v] += y;
					nz[v] += z;
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

	/**
	 * A low-poly canopy: a dome of 8 segments, a band from a ring of radius 150 at y = 0 up to one of radius 100 at
	 * y = -150, closed by a fan to an apex at y = -200 (the game's y points down), wound outward.
	 */
	private static Mesh dome(boolean smooth)
	{
		Mesh m = new Mesh();
		for (int i = 0; i < 8; ++i)
		{
			double t = 2 * Math.PI * i / 8;
			m.vertex((float) Math.round(150 * Math.cos(t)), 0, (float) Math.round(150 * Math.sin(t)));
		}
		for (int i = 0; i < 8; ++i)
		{
			double t = 2 * Math.PI * i / 8;
			m.vertex((float) Math.round(100 * Math.cos(t)), -150, (float) Math.round(100 * Math.sin(t)));
		}
		int apex = m.vertex(0, -200, 0);
		for (int i = 0; i < 8; ++i)
		{
			int b0 = i, b1 = (i + 1) % 8, t0 = 8 + i, t1 = 8 + (i + 1) % 8;
			m.face(b0, b1, t1, smooth);
			m.face(b0, t1, t0, smooth);
		}
		for (int i = 0; i < 8; ++i)
		{
			m.face(apex, 8 + i, 8 + (i + 1) % 8, smooth);
		}
		return m;
	}

	/** A crate: a 100-unit cube on its 8 shared corners, two triangles a side wound outward. */
	private static Mesh crate(boolean smooth)
	{
		Mesh m = new Mesh();
		for (int i = 0; i < 8; ++i)
		{
			m.vertex((i & 1) * 100 - 50, -((i >> 1 & 1) * 100), (i >> 2 & 1) * 100 - 50);
		}
		// sides as corner quads (a, b, c, d), split a-b-c and a-c-d with the outward winding
		int[][] quads = {{0, 2, 6, 4}, {1, 5, 7, 3}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 6, 7, 5}};
		for (int[] q : quads)
		{
			m.face(q[0], q[1], q[2], smooth);
			m.face(q[0], q[2], q[3], smooth);
		}
		return m;
	}

	/** Direction from the point (cx, cy, cz) to the centroid of face f. */
	private static float[] outward(Mesh m, int f, float cx, float cy, float cz)
	{
		float x = (m.vx[m.i1[f]] + m.vx[m.i2[f]] + m.vx[m.i3[f]]) / 3 - cx;
		float y = (m.vy[m.i1[f]] + m.vy[m.i2[f]] + m.vy[m.i3[f]]) / 3 - cy;
		float z = (m.vz[m.i1[f]] + m.vz[m.i2[f]] + m.vz[m.i3[f]]) / 3 - cz;
		float l = (float) Math.sqrt(x * x + y * y + z * z);
		return new float[]{x / l, y / l, z / l};
	}

	@Test
	public void theTestDomeAndCrateAreWoundOutward()
	{
		Mesh d = dome(false), c = crate(false);
		for (int f = 0; f < d.faces; ++f)
		{
			assertTrue(dot(d.unit(f), outward(d, f, 0, -50, 0)) > 0.3f);
		}
		for (int f = 0; f < c.faces; ++f)
		{
			assertTrue(dot(c.unit(f), outward(c, f, 0, -50, 0)) > 0.5f);
		}
	}

	@Test
	public void aFlatCanopyIsFacetedAndASmoothOneRound()
	{
		int[] n = new int[9];
		// flat-marked: every face lit by its own normal, so neighbouring faces of the band step by about 45 degrees
		Mesh flat = dome(false);
		int[][] game = flat.gameNormals();
		for (int f = 0; f < flat.faces; ++f)
		{
			assertEquals(VertexShading.FACE, flat.normals(f, game, n));
			float[] u = flat.unit(f);
			for (int i = 0; i < 3; ++i)
			{
				assertTrue(dot(corner(n, i), u) > 0.9999f);
			}
		}
		flat.normals(0, game, n);
		float[] band0 = corner(n, 0);
		flat.normals(2, game, n);
		assertTrue(dot(band0, corner(n, 0)) < 0.75f);
		// smooth-marked: each corner the game's sum of the faces around it, pointing out from the dome's middle, so
		// the corners of one face differ and the light rounds over the facets
		Mesh smooth = dome(true);
		game = smooth.gameNormals();
		for (int f = 0; f < smooth.faces; ++f)
		{
			assertEquals(VertexShading.VERTEX, smooth.normals(f, game, n));
			int[] idx = {smooth.i1[f], smooth.i2[f], smooth.i3[f]};
			for (int i = 0; i < 3; ++i)
			{
				int v = idx[i];
				float x = smooth.vx[v], y = smooth.vy[v] + 50, z = smooth.vz[v];
				float l = (float) Math.sqrt(x * x + y * y + z * z);
				assertTrue(dot(corner(n, i), new float[]{x / l, y / l, z / l}) > 0.6f);
			}
			assertTrue(dot(corner(n, 0), corner(n, 1)) < 0.999f || dot(corner(n, 1), corner(n, 2)) < 0.999f);
		}
	}

	@Test
	public void aCrateIsABoxOnlyWhenTheGameMarksItFlat()
	{
		int[] n = new int[9];
		// flat-marked: each side its own axis normal, 90 degrees to the next: a box
		Mesh flat = crate(false);
		int[][] game = flat.gameNormals();
		for (int f = 0; f < flat.faces; ++f)
		{
			assertEquals(VertexShading.FACE, flat.normals(f, game, n));
			float[] c = corner(n, 0);
			assertEquals(1f, Math.max(Math.abs(c[0]), Math.max(Math.abs(c[1]), Math.abs(c[2]))), 1e-3f);
		}
		// smooth-marked on shared corners: each corner sums the sides around it, so it leans out along the diagonal
		// and the box shades round; 117 HD has no crease angle that would keep its edges
		Mesh smooth = crate(true);
		game = smooth.gameNormals();
		for (int f = 0; f < smooth.faces; ++f)
		{
			assertEquals(VertexShading.VERTEX, smooth.normals(f, game, n));
			int[] idx = {smooth.i1[f], smooth.i2[f], smooth.i3[f]};
			for (int i = 0; i < 3; ++i)
			{
				float[] c = corner(n, i);
				int v = idx[i];
				float[] toCorner = {Math.signum(smooth.vx[v]), Math.signum(smooth.vy[v] + 50), Math.signum(smooth.vz[v])};
				for (int k = 0; k < 3; ++k)
				{
					assertTrue(c[k] * toCorner[k] > 0.1f);
				}
			}
		}
	}

	/**
	 * A thatched roof slope: a 3x3 grid of corners (100 apart) on a 45-degree slope, its middle corner pushed out by
	 * 20 (the bumps a thatch model has), two triangles a cell, sharing the corners.
	 */
	private static Mesh roof(boolean smooth)
	{
		Mesh m = new Mesh();
		for (int j = 0; j < 3; ++j)
		{
			for (int i = 0; i < 3; ++i)
			{
				float bump = i == 1 && j == 1 ? 20 : 0;
				// the slope rises along z (y down): y = -z, pushed out along its normal (0, -1, 1)/sqrt2 by the bump
				m.vertex(i * 100, -(j * 100) - bump * 0.7071f, j * 100 - bump * 0.7071f);
			}
		}
		for (int j = 0; j < 2; ++j)
		{
			for (int i = 0; i < 2; ++i)
			{
				int a = j * 3 + i, b = a + 1, c = a + 3, d = c + 1;
				m.face(a, c, b, smooth);
				m.face(b, c, d, smooth);
			}
		}
		return m;
	}

	@Test
	public void aFlatMarkedRoofShowsItsTrianglesAndASmoothOneDoesNot()
	{
		int[] n = new int[9];
		// flat-marked (faceColors3 == -1): every triangle lit by its own normal. Those on the bump (they share the
		// middle corner) each point their own way, so each is its own tone, as 117 HD shows it too; those off it lie in
		// the slope and share its normal, so a flat roof slope shows no triangles
		Mesh flat = roof(false);
		int[][] game = flat.gameNormals();
		java.util.List<float[]> onBump = new java.util.ArrayList<>(), offBump = new java.util.ArrayList<>();
		for (int f = 0; f < flat.faces; ++f)
		{
			assertEquals(VertexShading.FACE, flat.normals(f, game, n));
			boolean bump = flat.i1[f] == 4 || flat.i2[f] == 4 || flat.i3[f] == 4;
			(bump ? onBump : offBump).add(corner(n, 0));
		}
		for (int f = 0; f < onBump.size(); ++f)
		{
			for (int g = f + 1; g < onBump.size(); ++g)
			{
				assertTrue(dot(onBump.get(f), onBump.get(g)) < 0.9999f);
			}
		}
		for (float[] o : offBump)
		{
			assertTrue(dot(o, offBump.get(0)) > 0.9999f);
		}
		// smooth-marked on shared corners: at every corner all its triangles carry the one game normal, so the light
		// runs across the triangle edges with no step
		Mesh smooth = roof(true);
		game = smooth.gameNormals();
		java.util.Map<Integer, float[]> at = new java.util.HashMap<>();
		for (int f = 0; f < smooth.faces; ++f)
		{
			assertEquals(VertexShading.VERTEX, smooth.normals(f, game, n));
			int[] idx = {smooth.i1[f], smooth.i2[f], smooth.i3[f]};
			for (int i = 0; i < 3; ++i)
			{
				float[] c = corner(n, i);
				float[] seen = at.putIfAbsent(idx[i], c);
				if (seen != null)
				{
					assertArrayEquals(seen, c, 0f);
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
	public void upwardsNormalsPointUpOnEveryCorner()
	{
		// 117 HD's upwardsNormals: (0, -1, 0), the game's y pointing down
		int[] n = {2048, 5, 0, 0, 7, 1000, -300, 9, 400};
		VertexShading.upwardsNormals(n);
		assertArrayEquals(new int[]{0, -2048, 0, 0, -2048, 0, 0, -2048, 0}, n);
		assertArrayEquals(new float[]{0, -1, 0}, VertexShading.decodeNormal(VertexShading.encodeNormal(n, 1)), 1e-6f);
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
	public void aFlatFaceIsUndoneByItsOwnNormalAndASmoothOneByTheGames()
	{
		// as the uploaders do: faceNormals first, then each corner undone with its normal from it
		int color = 20 << 10 | 1 << 7 | 30;
		Mesh flat = prism(8, false), smooth = prism(8, true);
		int[] n = new int[9];
		flat.normals(0, flat.gameNormals(), n);
		int[] undone = new int[3];
		for (int i = 0; i < 3; ++i)
		{
			undone[i] = VertexShading.undoVanillaShading(color, n, i);
		}
		// one colour over the facet, brightened by how much the face turns to (1, 1, 1)
		float[] f = corner(n, 0);
		int expected = Math.min(30 + (int) ((f[0] + f[1] + f[2]) * 0.57735026f * (10 - 30 + 27 * 3)), 61);
		assertArrayEquals(new int[]{20 << 10 | 1 << 7 | expected, 20 << 10 | 1 << 7 | expected, 20 << 10 | 1 << 7 | expected}, undone);
		// smooth: each corner by the game's vertex normal there, so the corners differ (a dark unsaturated colour,
		// which the cap leaves alone)
		int dark = 20 << 10 | 10;
		int[][] game = smooth.gameNormals();
		smooth.normals(0, game, n);
		assertTrue(VertexShading.undoVanillaShading(dark, n, 0) != VertexShading.undoVanillaShading(dark, n, 1));
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
		// a back face's normal: turned round, 0 kept
		for (float[] c : cases)
		{
			float[] d = VertexShading.decodeNormal(VertexShading.negateNormal(VertexShading.encodeNormal(c[0], c[1], c[2])));
			assertTrue(dot(c, d) < -0.99f);
		}
		assertEquals(0, VertexShading.negateNormal(0));
	}
}
