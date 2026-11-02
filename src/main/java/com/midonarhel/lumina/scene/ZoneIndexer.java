package com.midonarhel.lumina.scene;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_SHORT;

/**
 * A zone's opaque triangles as distinct vertices and an index list. SceneUploader writes a zone's triangles as a
 * stream, three vertices each, and the corners a model's faces share repeat the same vertex again and again: drawn as
 * a stream every repeat is fetched and shaded anew. Here each distinct vertex (all its ints equal) is kept once, in
 * the order it first comes, and the stream becomes indices into them in its own order: index k is the stream's vertex
 * k, so every range the zone keeps of its stream (levels, roofs, swaying models) is the same range of the indices
 * (Zone.renderOpaque). Pure data: on the upload threads.
 *
 * The buffer then holds the distinct vertices, then the indices (16 bits with at most 65536 vertices, else 32) from
 * a 4-byte boundary. When that would not fit the buffer, which the stream was sized for, the stream is written as it
 * was and drawn as before.
 */
public final class ZoneIndexer
{
	static final int INTS = Zone.VERT_SIZE / Integer.BYTES;
	private static final boolean LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

	/** How a buffer was written: indexType 0 for the stream as it was. */
	public static final class Result
	{
		public int indexType;
		// distinct vertices, the stream's vertices, the byte offset of the indices, ints written
		public int vertices, streamVertices, indexOffset, ints;

		public int indexSize()
		{
			return indexType == GL_UNSIGNED_SHORT ? 2 : 4;
		}
	}

	// reused from zone to zone (one indexer per upload thread)
	private int[] table = new int[0];
	private int[] first = new int[0];
	private int[] indices = new int[0];

	/**
	 * Write the stream (ints [0, length) of stream, whole vertices) into out from position 0, indexed if that fits
	 * out's capacity; out's position is left after what was written.
	 */
	public Result write(int[] stream, int length, IntBuffer out)
	{
		Result r = new Result();
		int n = length / INTS;
		r.streamVertices = n;
		out.clear();
		if (n == 0)
		{
			return r;
		}
		int size = Integer.highestOneBit(Math.max(n * 2, 16) - 1) << 1;
		if (table.length < size)
		{
			table = new int[size];
		}
		Arrays.fill(table, 0, size, -1);
		int mask = size - 1;
		if (first.length < n)
		{
			first = new int[n];
			indices = new int[n];
		}
		int u = 0;
		for (int i = 0; i < n; ++i)
		{
			int o = i * INTS;
			int h = hash(stream, o) & mask;
			int found = -1;
			for (int j; (j = table[h]) != -1; h = h + 1 & mask)
			{
				if (same(stream, o, first[j] * INTS))
				{
					found = j;
					break;
				}
			}
			if (found < 0)
			{
				found = u;
				table[h] = u;
				first[u++] = i;
			}
			indices[i] = found;
		}
		boolean shorts = u <= 65536;
		int indexInts = shorts ? (n + 1) / 2 : n;
		if ((long) u * INTS + indexInts > out.capacity())
		{
			out.put(stream, 0, n * INTS);
			r.ints = n * INTS;
			return r;
		}
		for (int j = 0; j < u; ++j)
		{
			out.put(stream, first[j] * INTS, INTS);
		}
		if (shorts)
		{
			for (int i = 0; i < n; i += 2)
			{
				int a = indices[i], b = i + 1 < n ? indices[i + 1] : 0;
				out.put(LITTLE ? (a & 0xffff) | b << 16 : a << 16 | (b & 0xffff));
			}
		}
		else
		{
			out.put(indices, 0, n);
		}
		r.indexType = shorts ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;
		r.vertices = u;
		r.indexOffset = u * Zone.VERT_SIZE;
		r.ints = u * INTS + indexInts;
		return r;
	}

	/** The stream again, from the ints {@link #write} wrote (as read back from the buffer) and its result. */
	public static int[] expand(int[] written, Result r)
	{
		if (r.indexType == 0)
		{
			return Arrays.copyOf(written, r.streamVertices * INTS);
		}
		int[] stream = new int[r.streamVertices * INTS];
		int base = r.indexOffset / Integer.BYTES;
		for (int i = 0; i < r.streamVertices; ++i)
		{
			int v;
			if (r.indexType == GL_UNSIGNED_SHORT)
			{
				int packed = written[base + i / 2];
				boolean low = ((i & 1) == 0) == LITTLE;
				v = (low ? packed : packed >>> 16) & 0xffff;
			}
			else
			{
				v = written[base + i];
			}
			System.arraycopy(written, v * INTS, stream, i * INTS, INTS);
		}
		return stream;
	}

	private static int hash(int[] s, int o)
	{
		int h = s[o];
		for (int k = 1; k < INTS; ++k)
		{
			h = h * 0x9E3779B1 + s[o + k];
		}
		return h ^ h >>> 15;
	}

	private static boolean same(int[] s, int a, int b)
	{
		for (int k = 0; k < INTS; ++k)
		{
			if (s[a + k] != s[b + k])
			{
				return false;
			}
		}
		return true;
	}
}
