package com.midonarhel.lumina.scene;

/** A zone made in code for the offline renderer's synthetic dump: flat ground tiles in two colors, a checkerboard. */
public final class ZoneFake
{
	private ZoneFake()
	{
	}

	/** The zone at extended zone index x, z: 8 x 8 tiles at height y, colors a and b (HSL) alternating. */
	public static ZoneSnapshot ground(int x, int z, int y, int a, int b)
	{
		int tiles = 64;
		int faces = tiles * 2;
		int[] v = new int[faces * 3 * Zone.VERT_SIZE / 4];
		int n = VertexShading.encodeNormal(0, -1, 0);
		int i = 0;
		for (int tx = 0; tx < 8; ++tx)
		{
			for (int tz = 0; tz < 8; ++tz)
			{
				int c = (tx + tz & 1) == 0 ? a : b;
				int lx = tx * 128, lz = tz * 128, s = 128;
				int[][] corners = {{lx + s, lz + s}, {lx, lz + s}, {lx + s, lz}, {lx, lz}, {lx + s, lz}, {lx, lz + s}};
				for (int[] k : corners)
				{
					v[i++] = (y & 0xffff) << 16 | (k[0] & 0xffff);
					v[i++] = k[1] & 0xffff;
					v[i++] = c;
					v[i++] = 0;
					v[i++] = (n & 0xffff) << 16;
					v[i++] = 1; // the normal's length (VertexShading.normalLength)
				}
			}
		}
		ZoneSnapshot s = new ZoneSnapshot();
		s.x = x;
		s.z = z;
		s.sizeO = faces;
		s.bufLen = v.length / (Zone.VERT_SIZE / 4);
		s.vertsO = v;
		s.levelOffsets = new int[]{v.length, v.length, v.length, v.length};
		s.rids = new int[4][0];
		s.roofStart = new int[4][0];
		s.roofEnd = new int[4][0];
		s.swayRanges = new int[0];
		return s;
	}
}
