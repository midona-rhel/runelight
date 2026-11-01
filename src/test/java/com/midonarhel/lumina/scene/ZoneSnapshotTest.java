package com.midonarhel.lumina.scene;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class ZoneSnapshotTest
{
	@Test
	public void writesAndReadsBack() throws IOException
	{
		ZoneSnapshot s = new ZoneSnapshot();
		s.x = 3;
		s.z = 9;
		s.sizeO = 2;
		s.sizeA = 0;
		s.bufLen = 6;
		s.vertsO = new int[]{1, 2, 3, 4, 5, -6};
		s.vertsA = null;
		s.levelOffsets = new int[]{6, 6, 6, 6};
		s.rids = new int[][]{{}, {4}, {}, {}};
		s.roofStart = new int[][]{{}, {0}, {}, {}};
		s.roofEnd = new int[][]{{}, {6}, {}, {}};
		s.swayRanges = new int[]{0, 6, 0, 0};
		s.swayRangeCount = 2;
		s.alphaModels.add(new int[]{1, 0, 15, 64, -10, 64, -1, 0, -1, -1, -1, -1, 0, 0, 0, 40});
		s.alphaFaces.add(new int[]{123});

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			s.write(out);
		}
		ZoneSnapshot b = ZoneSnapshot.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
		assertEquals(3, b.x);
		assertEquals(9, b.z);
		assertArrayEquals(s.vertsO, b.vertsO);
		assertNull(b.vertsA);
		assertArrayEquals(s.rids[1], b.rids[1]);
		assertArrayEquals(s.roofEnd[1], b.roofEnd[1]);
		assertEquals(2, b.swayRangeCount);
		assertArrayEquals(s.alphaModels.get(0), b.alphaModels.get(0));
		assertArrayEquals(s.alphaFaces.get(0), b.alphaFaces.get(0));
	}
}
