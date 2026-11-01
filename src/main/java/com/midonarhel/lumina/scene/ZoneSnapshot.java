package com.midonarhel.lumina.scene;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL33C.*;

/**
 * A zone as plain data, for the scene dump (dev/SceneDump): its vertices read back from its buffers, the level and roof
 * ranges, the swaying ranges and its static transparent models. {@link #restore} gives it buffers of the zone pool
 * again and fills them, so the offline renderer draws it with the real Zone code.
 */
public final class ZoneSnapshot
{
	public int x, z;
	public int sizeO, sizeA, bufLen, bufLenA;
	public int[] vertsO, vertsA;
	public int[] levelOffsets;
	public int[][] rids, roofStart, roofEnd;
	public int[] swayRanges;
	public int swayRangeCount;
	public final List<int[]> alphaModels = new ArrayList<>();
	public final List<int[]> alphaFaces = new ArrayList<>();

	/** Whether the zone is drawn and its buffers can be read back (unmapped). */
	public static boolean dumpable(Zone zone)
	{
		return zone != null && zone.initialized && (zone.vboO == null || !zone.vboO.mapped)
			&& (zone.vboA == null || !zone.vboA.mapped);
	}

	/** The drawn zone's data; GL, with the buffers unmapped (null for a zone not dumpable). */
	public static ZoneSnapshot of(Zone zone, int x, int z)
	{
		if (!dumpable(zone))
		{
			return null;
		}
		ZoneSnapshot s = new ZoneSnapshot();
		s.x = x;
		s.z = z;
		s.sizeO = zone.sizeO;
		s.sizeA = zone.sizeA;
		s.bufLen = zone.bufLen;
		s.bufLenA = zone.bufLenA;
		s.vertsO = readBack(zone.vboO);
		s.vertsA = readBack(zone.vboA);
		s.levelOffsets = zone.levelOffsets.clone();
		s.rids = zone.rids;
		s.roofStart = zone.roofStart;
		s.roofEnd = zone.roofEnd;
		s.swayRanges = zone.swayRanges.clone();
		s.swayRangeCount = zone.swayRangeCount;
		for (Zone.AlphaModel m : zone.alphaModels)
		{
			// the frame's temporary ones are in the frame tape
			if (m.isTemp() || (m.flags & Zone.AlphaModel.TEMP) != 0)
			{
				continue;
			}
			s.alphaModels.add(new int[]{m.id, m.startpos, m.endpos, m.x, m.y, m.z, m.rid, m.level, m.lx, m.lz, m.ux, m.uz,
				m.zofx, m.zofz, m.flags & ~Zone.AlphaModel.SKIP, m.radius});
			s.alphaFaces.add(m.packedFaces.clone());
		}
		return s;
	}

	private static int[] readBack(VBO vbo)
	{
		if (vbo == null)
		{
			return null;
		}
		int[] data = new int[vbo.len];
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);
		glGetBufferSubData(GL_ARRAY_BUFFER, 0, data);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		return data;
	}

	/**
	 * Into the zone (a new one), with buffers of the loader's pool filled with the vertices; GL. It is drawable at the
	 * loader's swap, as an uploaded zone is.
	 */
	public void restore(Zone zone, SceneLoader loader)
	{
		zone.init(upload(vertsO, sizeO, loader), upload(vertsA, sizeA, loader));
		zone.sizeO = sizeO;
		zone.sizeA = sizeA;
		zone.bufLen = bufLen;
		zone.bufLenA = bufLenA;
		zone.levelOffsets = levelOffsets.clone();
		zone.rids = rids;
		zone.roofStart = roofStart;
		zone.roofEnd = roofEnd;
		zone.swayRanges = swayRanges.clone();
		zone.swayRangeCount = swayRangeCount;
		for (int i = 0; i < alphaModels.size(); ++i)
		{
			int[] a = alphaModels.get(i);
			Zone.AlphaModel m = new Zone.AlphaModel();
			m.id = a[0];
			m.startpos = a[1];
			m.endpos = a[2];
			m.x = (short) a[3];
			m.y = (short) a[4];
			m.z = (short) a[5];
			m.rid = (short) a[6];
			m.level = (byte) a[7];
			m.lx = (byte) a[8];
			m.lz = (byte) a[9];
			m.ux = (byte) a[10];
			m.uz = (byte) a[11];
			m.zofx = (byte) a[12];
			m.zofz = (byte) a[13];
			m.flags = (byte) a[14];
			m.radius = a[15];
			m.packedFaces = alphaFaces.get(i).clone();
			m.vao = zone.glVaoA;
			zone.alphaModels.add(m);
		}
	}

	private static VBO upload(int[] verts, int faces, SceneLoader loader)
	{
		if (verts == null)
		{
			return null;
		}
		int bytes = faces * Zone.VERT_SIZE * 3;
		VBO vbo = loader.pool.acquire(Math.max(bytes, verts.length * Integer.BYTES));
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);
		glBufferSubData(GL_ARRAY_BUFFER, 0, verts);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		vbo.len = verts.length;
		return vbo;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// file

	public void write(DataOutputStream out) throws IOException
	{
		out.writeInt(x);
		out.writeInt(z);
		out.writeInt(sizeO);
		out.writeInt(sizeA);
		out.writeInt(bufLen);
		out.writeInt(bufLenA);
		writeInts(out, vertsO);
		writeInts(out, vertsA);
		writeInts(out, levelOffsets);
		writeInts2(out, rids);
		writeInts2(out, roofStart);
		writeInts2(out, roofEnd);
		writeInts(out, swayRanges);
		out.writeInt(swayRangeCount);
		out.writeInt(alphaModels.size());
		for (int i = 0; i < alphaModels.size(); ++i)
		{
			writeInts(out, alphaModels.get(i));
			writeInts(out, alphaFaces.get(i));
		}
	}

	public static ZoneSnapshot read(DataInputStream in) throws IOException
	{
		ZoneSnapshot s = new ZoneSnapshot();
		s.x = in.readInt();
		s.z = in.readInt();
		s.sizeO = in.readInt();
		s.sizeA = in.readInt();
		s.bufLen = in.readInt();
		s.bufLenA = in.readInt();
		s.vertsO = readInts(in);
		s.vertsA = readInts(in);
		s.levelOffsets = readInts(in);
		s.rids = readInts2(in);
		s.roofStart = readInts2(in);
		s.roofEnd = readInts2(in);
		s.swayRanges = readInts(in);
		s.swayRangeCount = in.readInt();
		int n = in.readInt();
		for (int i = 0; i < n; ++i)
		{
			s.alphaModels.add(readInts(in));
			s.alphaFaces.add(readInts(in));
		}
		return s;
	}

	/** The array's length (-1 for null) and its ints big-endian, as DataOutputStream would, in one write. */
	public static void writeInts(DataOutputStream out, int[] a) throws IOException
	{
		if (a == null)
		{
			out.writeInt(-1);
			return;
		}
		out.writeInt(a.length);
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(a.length * Integer.BYTES);
		b.asIntBuffer().put(a);
		out.write(b.array());
	}

	public static int[] readInts(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		byte[] bytes = new byte[n * Integer.BYTES];
		in.readFully(bytes);
		int[] a = new int[n];
		java.nio.ByteBuffer.wrap(bytes).asIntBuffer().get(a);
		return a;
	}

	private static void writeInts2(DataOutputStream out, int[][] a) throws IOException
	{
		if (a == null)
		{
			out.writeInt(-1);
			return;
		}
		out.writeInt(a.length);
		for (int[] row : a)
		{
			writeInts(out, row);
		}
	}

	private static int[][] readInts2(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		int[][] a = new int[n][];
		for (int i = 0; i < n; ++i)
		{
			a[i] = readInts(in);
		}
		return a;
	}
}
