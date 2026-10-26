package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import org.junit.Test;

public class OceanMapTest
{
	private static final OceanMap WORLD = OceanMap.world();

	@Test
	public void theOpenSeaIsOcean()
	{
		// Port Sarim's bay, off Karamja's west coast, Catherby's bay, the sea off Rellekka, west of Rimmington
		assertTrue(WORLD.contains(3056, 3216));
		assertTrue(WORLD.contains(2969, 3135));
		assertTrue(WORLD.contains(2815, 3422));
		assertTrue(WORLD.contains(2620, 3755));
		assertTrue(WORLD.contains(2905, 3225));
	}

	@Test
	public void riversAndLakesAreNotOcean()
	{
		// the River Lum beside Lumbridge's bridge and a little above its mouth, the River Salve, Lake Molch
		assertFalse(WORLD.contains(3244, 3224));
		assertFalse(WORLD.contains(3240, 3147));
		assertFalse(WORLD.contains(3426, 3490));
		assertFalse(WORLD.contains(1376, 3659));
	}

	@Test
	public void landIsNotOcean()
	{
		// Lumbridge castle, Varrock, Port Sarim town
		assertFalse(WORLD.contains(3222, 3218));
		assertFalse(WORLD.contains(3213, 3428));
		assertFalse(WORLD.contains(3020, 3230));
	}

	@Test
	public void outsideTheMappedSurfaceIsNotOcean()
	{
		// the same spot as Port Sarim's bay, in the dungeon layer; off the world
		assertFalse(WORLD.contains(3056, 3216 + 6400));
		assertFalse(WORLD.contains(-1, 3216));
		assertFalse(WORLD.contains(3056, -5));
		assertFalse(WORLD.contains(1 << 14, 3216));
		assertFalse(WORLD.contains(3056, 1 << 14));
	}

	@Test
	public void theWorldIsLoaded()
	{
		assertTrue(WORLD.regionCount() > 1000);
	}

	@Test
	public void readsBitsRowByRowFromTheSouth() throws IOException
	{
		byte[] bits = new byte[512];
		// tile (5, 2) of region (3, 7): row 2 from the south, bit 5 of the row's first byte
		bits[(2 * 64 + 5) >> 3] |= 1 << 5;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeBytes("RLOM");
		out.writeShort(1);
		out.writeShort(1);
		Deflater deflater = new Deflater();
		deflater.setInput(bits);
		deflater.finish();
		byte[] packed = new byte[600];
		int n = deflater.deflate(packed);
		deflater.end();
		out.writeShort(3 << 8 | 7);
		out.writeShort(n);
		out.write(packed, 0, n);

		OceanMap m = OceanMap.read(new ByteArrayInputStream(bytes.toByteArray()));
		assertEquals(1, m.regionCount());
		assertTrue(m.contains(3 * 64 + 5, 7 * 64 + 2));
		assertFalse(m.contains(3 * 64 + 4, 7 * 64 + 2));
		assertFalse(m.contains(3 * 64 + 5, 7 * 64 + 3));
		assertFalse(m.contains(4 * 64 + 5, 7 * 64 + 2));
	}

	@Test(expected = IOException.class)
	public void rejectsOtherFiles() throws IOException
	{
		OceanMap.read(new ByteArrayInputStream("RLWF\0\1\0\0".getBytes()));
	}
}
