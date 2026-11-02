package com.midonarhel.lumina.scene;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_SHORT;

public class ZoneIndexerTest
{
	private static final int INTS = ZoneIndexer.INTS;

	/** A stream of n vertices drawn from `distinct` different ones. */
	private static int[] stream(int n, int distinct, long seed)
	{
		Random r = new Random(seed);
		int[][] pool = new int[distinct][INTS];
		for (int[] v : pool)
		{
			for (int k = 0; k < INTS; ++k)
			{
				v[k] = r.nextInt();
			}
		}
		int[] s = new int[n * INTS];
		for (int i = 0; i < n; ++i)
		{
			System.arraycopy(pool[r.nextInt(distinct)], 0, s, i * INTS, INTS);
		}
		return s;
	}

	private static ZoneIndexer.Result roundTrip(int[] s, int capacity)
	{
		IntBuffer out = IntBuffer.allocate(capacity);
		ZoneIndexer.Result r = new ZoneIndexer().write(s, s.length, out);
		assertEquals(r.ints, out.position());
		assertArrayEquals(s, ZoneIndexer.expand(Arrays.copyOf(out.array(), out.position()), r));
		return r;
	}

	@Test
	public void sharedVerticesAreKeptOnceAndTheStreamComesBack()
	{
		int[] s = stream(3000, 400, 1);
		ZoneIndexer.Result r = roundTrip(s, s.length);
		assertEquals(GL_UNSIGNED_SHORT, r.indexType);
		assertEquals(3000, r.streamVertices);
		assertEquals(r.vertices * Zone.VERT_SIZE, r.indexOffset);
		assertEquals(r.vertices * INTS + 1500, r.ints);
	}

	@Test
	public void manyVerticesTake32BitIndices()
	{
		int[] s = stream(200_000, 70_000, 2);
		ZoneIndexer.Result r = roundTrip(s, s.length);
		assertEquals(GL_UNSIGNED_INT, r.indexType);
	}

	@Test
	public void anOddCountAndNoRepeats()
	{
		// every vertex distinct: the indexed form is larger than the stream, so the stream is written as it was
		int[] s = stream(3, 1 << 20, 3);
		ZoneIndexer.Result r = roundTrip(s, s.length);
		assertEquals(0, r.indexType);
		assertEquals(s.length, r.ints);
		// with room to spare it is indexed all the same
		assertEquals(GL_UNSIGNED_SHORT, roundTrip(s, s.length * 2).indexType);
	}

	@Test
	public void objectsWithOtherAnchorsKeepTheirOwnCorners()
	{
		// two objects' triangles meeting at the same corners, alike in everything but the code in the position's w
		// (SceneUploader's swayCodes: the snow's anchor tile in its low six bits): each keeps its own vertices, so
		// each keeps its own snow
		int[][] corners = {{0, 0}, {128, 0}, {0, 128}, {128, 128}};
		int[][] triangles = {{0, 1, 2, 5}, {1, 3, 2, 12}};
		int[] s = new int[6 * INTS];
		int i = 0;
		for (int[] t : triangles)
		{
			for (int c = 0; c < 3; ++c, ++i)
			{
				int[] p = corners[t[c]];
				// put22224(x, y, z, code, color); the second half (texture, uv, normal) alike
				s[i * INTS] = p[1] << 16 | p[0];
				s[i * INTS + 1] = t[3] << 16 | 64;
				s[i * INTS + 2] = 0x1234;
			}
		}
		ZoneIndexer.Result r = roundTrip(s, s.length * 2);
		assertEquals(GL_UNSIGNED_SHORT, r.indexType);
		assertEquals("no corner of one object shared with the other", 6, r.vertices);
	}

	@Test
	public void anEmptyStream()
	{
		ZoneIndexer.Result r = roundTrip(new int[0], 0);
		assertEquals(0, r.ints);
	}
}
