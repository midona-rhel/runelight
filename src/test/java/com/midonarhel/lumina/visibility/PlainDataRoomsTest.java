package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.dev.PlainData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The scene dump's rooms and walls (PlainData) read back answer as the ones written. */
public class PlainDataRoomsTest
{
	@Test
	public void roomsAndWallsSurviveTheDump() throws IOException
	{
		RoomMap m = RoomMapTest.house(RoomMapTest.walls());
		List<Object> both = new ArrayList<>();
		both.add(m);
		both.add(PlainData.get(m, "walls"));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		List<String> skipped;
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			PlainData.Writer w = new PlainData.Writer(out);
			w.write(both);
			skipped = w.skipped;
		}
		@SuppressWarnings("unchecked")
		List<Object> back = (List<Object>) new PlainData.Reader(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).read();
		RoomMap m2 = (RoomMap) back.get(0);
		assertTrue(skipped.toString(), skipped.isEmpty());
		assertSame(PlainData.get(m2, "walls"), back.get(1));
		assertArrayEquals(m.rooms, m2.rooms);
		assertArrayEquals(m.floors, m2.floors, 0);
		for (int x = 2; x < 16; ++x)
		{
			for (int y = 2; y < 16; ++y)
			{
				assertEquals(m.roomOfLight(RoomMapTest.world(x), RoomMapTest.world(y), 0),
					m2.roomOfLight(RoomMapTest.world(x), RoomMapTest.world(y), 0));
			}
		}
	}
}
