package com.midonarhel.runelight.scene;

import com.midonarhel.runelight.*;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The crease rule for faces the game marks flat ({@link VertexShading#flatFaceNormals}): corners smooth across
 * gentle folds and stay sharp across real corners, per corner, so a canopy face with one sharp corner still rounds
 * at the other two, and double-sided foliage does not cancel itself out.
 */
public class VertexShadingTest
{
	private static final int[] NO_ROTATION = new int[3];

	/** A mesh built face by face: positions and triangle indices, every face flat (color3 == -1). */
	private static final class Mesh
	{
		float[] vx = new float[64], vy = new float[64], vz = new float[64];
		int[] i1 = new int[64], i2 = new int[64], i3 = new int[64], colors3 = new int[64];
		int vertices, faces;
		final VertexShading.Normals normals = new VertexShading.Normals();

		int vertex(float x, float y, float z)
		{
			vx[vertices] = x;
			vy[vertices] = y;
			vz[vertices] = z;
			return vertices++;
		}

		int face(int a, int b, int c)
		{
			i1[faces] = a;
			i2[faces] = b;
			i3[faces] = c;
			colors3[faces] = -1;
			return faces++;
		}

		void compute()
		{
			VertexShading.computeNormals(vertices, vx, vy, vz, faces, i1, i2, i3, colors3, normals);
		}

		/** The face's three corner normals, decoded; whether any corner was smoothed is in smoothed[0]. */
		float[][] cornerNormals(int face, boolean[] smoothed)
		{
			int[] out = new int[3];
			smoothed[0] = VertexShading.flatFaceNormals(normals, face, i1[face], i2[face], i3[face], 0, 1, out);
			return new float[][]{VertexShading.decodeNormal(out[0]), VertexShading.decodeNormal(out[1]),
				VertexShading.decodeNormal(out[2])};
		}
	}

	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	/** A regular prism of {@code sides} sides around the y axis, radius 100, from y = 0 to y = 200, two faces a side. */
	private static Mesh prism(int sides)
	{
		Mesh m = new Mesh();
		for (int s = 0; s < sides; ++s)
		{
			double a = 2 * Math.PI * s / sides;
			m.vertex((float) (100 * Math.cos(a)), 0, (float) (100 * Math.sin(a)));
			m.vertex((float) (100 * Math.cos(a)), 200, (float) (100 * Math.sin(a)));
		}
		for (int s = 0; s < sides; ++s)
		{
			int lo = 2 * s, hi = 2 * s + 1, lo2 = 2 * ((s + 1) % sides), hi2 = lo2 + 1;
			m.face(lo, hi, lo2);
			m.face(hi, hi2, lo2);
		}
		m.compute();
		return m;
	}

	@Test
	public void aCubeStaysSharp()
	{
		// a box's sides meet at 90 degrees: no corner is smoothed, the face keeps its own normal
		Mesh m = prism(4);
		boolean[] smoothed = new boolean[1];
		for (int f = 0; f < m.faces; ++f)
		{
			float[][] n = m.cornerNormals(f, smoothed);
			assertFalse("face " + f, smoothed[0]);
			assertTrue(dot(n[0], n[1]) > 0.999f);
			assertTrue(dot(n[1], n[2]) > 0.999f);
		}
	}

	@Test
	public void anOctagonalTrunkRounds()
	{
		// sides 45 degrees apart: every corner averages its two sides, pointing radially out from the axis
		Mesh m = prism(8);
		boolean[] smoothed = new boolean[1];
		for (int f = 0; f < m.faces; ++f)
		{
			float[][] n = m.cornerNormals(f, smoothed);
			assertTrue("face " + f, smoothed[0]);
			for (int i = 0; i < 3; ++i)
			{
				int v = i == 0 ? m.i1[f] : i == 1 ? m.i2[f] : m.i3[f];
				float[] radial = {m.vx[v] / 100, 0, m.vz[v] / 100};
				assertTrue("face " + f + " corner " + i + " radial", Math.abs(dot(n[i], radial)) > 0.98f);
			}
		}
	}

	@Test
	public void aCornerOnASharpFoldKeepsTheOtherTwoSmooth()
	{
		// a strip of two gently folded faces (20 degrees) with a third face hinged at 90 degrees off one corner:
		// the old all-or-nothing rule flattened the whole middle face for that one corner; per corner it rounds
		// at the gentle fold and stays sharp only where the hinge is
		Mesh m = new Mesh();
		int a = m.vertex(0, 0, 0), b = m.vertex(100, 0, 0), c = m.vertex(0, 0, 100), d = m.vertex(100, 0, 100);
		float tilt = (float) Math.sin(Math.toRadians(20)) * 100, run = (float) Math.cos(Math.toRadians(20)) * 100;
		int e = m.vertex(-run, tilt, 0), g = m.vertex(-run, tilt, 100);
		int h = m.vertex(100, 100, 0), k = m.vertex(100, 100, 100);
		int middle = m.face(a, c, b);
		m.face(b, c, d);
		m.face(e, g, a); // gentle fold along a-c
		m.face(g, c, a);
		m.face(b, d, h); // 90-degree wall along b-d
		m.face(d, k, h);
		m.compute();
		boolean[] smoothed = new boolean[1];
		float[][] n = m.cornerNormals(middle, smoothed);
		assertTrue(smoothed[0]);
		float[] up = {0, 1, 0};
		// corners a and c lie on the gentle fold: tilted halfway toward the folded face, no longer straight up
		assertTrue(Math.abs(dot(n[0], up)) < 0.999f && Math.abs(dot(n[0], up)) > 0.9f);
		assertTrue(Math.abs(dot(n[1], up)) < 0.999f && Math.abs(dot(n[1], up)) > 0.9f);
		// corner b touches the wall only: the wall is beyond the crease angle, so it keeps the face's own normal
		assertTrue(Math.abs(dot(n[2], up)) > 0.999f);
	}

	@Test
	public void doubleSidedLeavesDoNotCancel()
	{
		// the same quad twice with opposite winding (two-sided foliage), beside a gently folded neighbour: the
		// twin counts flipped, so the corners still average toward the neighbour instead of summing to zero
		Mesh m = new Mesh();
		int a = m.vertex(0, 0, 0), b = m.vertex(100, 0, 0), c = m.vertex(0, 0, 100);
		float tilt = (float) Math.sin(Math.toRadians(20)) * 100, run = (float) Math.cos(Math.toRadians(20)) * 100;
		int e = m.vertex(-run, tilt, 0), g = m.vertex(-run, tilt, 100);
		int front = m.face(a, c, b);
		m.face(a, b, c); // its back
		m.face(e, g, a);
		m.face(g, c, a);
		m.compute();
		boolean[] smoothed = new boolean[1];
		float[][] n = m.cornerNormals(front, smoothed);
		assertTrue(smoothed[0]);
		for (float[] corner : n)
		{
			float l = dot(corner, corner);
			assertTrue("a unit normal, not the zero of a cancelled sum", l > 0.99f && l < 1.01f);
		}
		assertTrue(Math.abs(dot(n[0], new float[]{0, 1, 0})) < 0.999f);
	}

	@Test
	public void gouraudFacesKeepTheFullAverage()
	{
		// the vertex sums for faces the game shades smoothly are unchanged by the crease rule: a cube's corner
		// vertex still averages its three sides
		Mesh m = prism(4);
		float[] n = VertexShading.decodeNormal(VertexShading.vertexNormal(m.normals.x, m.normals.y, m.normals.z, 0, 0, 1));
		assertTrue(Math.abs(n[1]) < 1e-3f);
		// vertex 0 is on the sides whose outward normals are (1,0,1)/√2 and (1,0,-1)/√2: its average leans toward both
		// (the quads' triangle split weights the two sides unequally, so not exactly between them)
		assertTrue(dot(n, new float[]{0.707f, 0, 0.707f}) > 0.3f);
		assertTrue(dot(n, new float[]{0.707f, 0, -0.707f}) > 0.3f);
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
