package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.Deflater;
import org.junit.Test;

/**
 * The shipped world flow map: rivers whose direction is known run that way.
 */
public class WaterFlowTest
{
	private static WaterFlow flow() throws IOException
	{
		try (InputStream in = WaterFlow.class.getResourceAsStream(WaterFlow.RESOURCE))
		{
			assertTrue("resource present", in != null);
			return WaterFlow.read(in);
		}
	}

	private static float[] at(WaterFlow f, int x, int y)
	{
		float[] v = new float[2];
		assertTrue("flow known at " + x + ", " + y, f.flow(x, y, v));
		return v;
	}

	private static void runs(WaterFlow f, int x, int y, double towardX, double towardY)
	{
		float[] v = at(f, x, y);
		double len = Math.hypot(v[0], v[1]);
		assertTrue("moving at " + x + ", " + y, len > 0.2);
		double cos = (v[0] * towardX + v[1] * towardY) / (len * Math.hypot(towardX, towardY));
		assertTrue("direction at " + x + ", " + y + ": " + v[0] + ", " + v[1], cos > 0.5);
	}

	@Test
	public void loads() throws IOException
	{
		assertTrue(flow().regionCount() > 1000);
	}

	@Test
	public void riverLumRunsSouthPastLumbridge() throws IOException
	{
		WaterFlow f = flow();
		runs(f, 3236, 3247, 0, -1);
		runs(f, 3109, 3426, 0, -1);
	}

	@Test
	public void riverElidRunsSouthThroughTheDesert() throws IOException
	{
		// the middle of the channel at Nardah (the banks run slower)
		runs(flow(), 3372, 3003, 0, -1);
	}

	@Test
	public void ardougneRiverRunsSouthWestToYanille() throws IOException
	{
		WaterFlow f = flow();
		runs(f, 2591, 3353, 0, -1);
		runs(f, 2390, 3100, -1, -2);
	}

	@Test
	public void riverSalveRunsSouth() throws IOException
	{
		runs(flow(), 3401, 3439, 0, -1);
	}

	@Test
	public void lunarIsleStreamRunsSouthEastToTheSouthCoast() throws IOException
	{
		WaterFlow f = flow();
		assertTrue(f.shaped());
		runs(f, 2116, 3870, 1, -1);
		runs(f, 2128, 3850, 0, -1);
	}

	private static double speed(WaterFlow f, int x, int y)
	{
		float[] v = at(f, x, y);
		return Math.hypot(v[0], v[1]);
	}

	@Test
	public void pondsBesideRiversAreCalm() throws IOException
	{
		WaterFlow f = flow();
		// the round pond off the Lunar Isle stream, away from where the stream passes through it
		assertTrue(speed(f, 2101, 3889) < 0.05);
		// the pond beside the Ardougne river in East Ardougne, while the river next to it runs
		assertTrue(speed(f, 2595, 3270) < 0.02);
		assertTrue(speed(f, 2588, 3274) > 0.3);
	}

	@Test
	public void lunarIsleCoastIsCalm() throws IOException
	{
		// the stream runs out into the sea, not along the coast
		assertTrue(speed(flow(), 2140, 3843) < 0.02);
	}

	/**
	 * A file of one region (3200, 3200) whose samples hold their own grid position: east = sample x, north = sample y
	 * (hundredths), unknown on the region's last sample column.
	 */
	private static WaterFlow synthetic(int version) throws IOException
	{
		int sub = version == 3 ? 2 : 1, side = 64 * sub;
		byte[] raw = new byte[side * side * 2];
		for (int y = 0; y < side; ++y)
		{
			for (int x = 0; x < side; ++x)
			{
				int i = (y * side + x) * 2;
				raw[i] = x == side - 1 ? (byte) -128 : (byte) (x % 100);
				raw[i + 1] = (byte) (y % 100);
			}
		}
		Deflater d = new Deflater();
		d.setInput(raw);
		d.finish();
		byte[] buf = new byte[raw.length * 2];
		int len = d.deflate(buf);
		d.end();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeBytes("RLWF");
		out.writeShort(version);
		out.writeShort(1);
		out.writeShort(50 << 8 | 50);
		out.writeInt(len);
		out.write(buf, 0, len);
		return WaterFlow.read(new ByteArrayInputStream(bytes.toByteArray()));
	}

	@Test
	public void subTileSamples() throws IOException
	{
		WaterFlow f = synthetic(3);
		assertTrue(f.shaped());
		float[] v = new float[2];
		// tile (3210, 3205) is samples x 20-21, y 10-11
		assertTrue(f.flow(3210, 3205, v));
		assertEquals(0.205f, v[0], 1e-6);
		assertEquals(0.105f, v[1], 1e-6);
		// its sw corner: samples x 19-20, y 9-10
		assertTrue(f.corner(3210, 3205, v));
		assertEquals(0.195f, v[0], 1e-6);
		assertEquals(0.095f, v[1], 1e-6);
		// the region's last tile column: its east samples are unknown, the west ones count
		assertTrue(f.flow(3263, 3205, v));
		assertEquals(0.26f, v[0], 1e-6);
		// the region's sw corner: only the sample inside it is known
		assertTrue(f.corner(3200, 3200, v));
		assertEquals(0, v[0], 1e-6);
		assertFalse(f.flow(3264, 3205, v));
	}

	@Test
	public void readsVersion2() throws IOException
	{
		WaterFlow f = synthetic(2);
		assertFalse(f.shaped());
		float[] v = new float[2];
		assertTrue(f.flow(3210, 3205, v));
		assertEquals(0.10f, v[0], 1e-6);
		assertEquals(0.05f, v[1], 1e-6);
		assertTrue(f.corner(3210, 3205, v));
		assertEquals(0.095f, v[0], 1e-6);
		assertEquals(0.045f, v[1], 1e-6);
	}

	@Test
	public void openSeaIsStill() throws IOException
	{
		float[] v = at(flow(), 3000, 3000);
		assertEquals(0, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
	}

	@Test
	public void dryLandIsUnknown() throws IOException
	{
		// Lumbridge castle courtyard
		assertFalse(flow().flow(3222, 3218, new float[2]));
	}

	@Test
	public void instanceRotationTurnsWorldDirections()
	{
		// a chunk placed with rotation 1 maps scene +x to world -y (EnvironmentManager.toWorld)
		float[] v = {0, -1};
		WaterMap.rotateToScene(1, v);
		assertEquals(1, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
		v = new float[]{0, 1};
		WaterMap.rotateToScene(3, v);
		assertEquals(1, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
		v = new float[]{1, 2};
		WaterMap.rotateToScene(2, v);
		assertEquals(-1, v[0], 1e-6);
		assertEquals(-2, v[1], 1e-6);
	}
}
