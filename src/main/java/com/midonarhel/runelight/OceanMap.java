package com.midonarhel.runelight;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which world tiles are open sea, worked out offline from the world map (tools/water/build_ocean.py) and shipped as
 * ocean.dat. A scene sees only a slice of the world, so it can't tell a river running off its edge from the sea; the
 * map can. Only the surface (plane 0) is mapped.
 *
 * Per 64 x 64 region with sea, a bit per tile; the regions are unpacked once, about 700 kB for the world.
 */
final class OceanMap
{
	private static final Logger log = LoggerFactory.getLogger(OceanMap.class);
	static final String RESOURCE = "ocean.dat";
	private static final int VERSION = 1;
	private static final int REGION = 64;
	private static final int REGION_BYTES = REGION * REGION / 8;

	private static volatile OceanMap world;

	// region id (x >> 6 << 8 | y >> 6) to its tiles' bits, row by row from the south, x fastest, least significant
	// bit first
	private final Map<Integer, byte[]> regions;

	OceanMap(Map<Integer, byte[]> regions)
	{
		this.regions = regions;
	}

	/**
	 * The world's sea, loaded on first use. Never null: empty if the resource can't be read.
	 */
	static OceanMap world()
	{
		OceanMap m = world;
		if (m == null)
		{
			synchronized (OceanMap.class)
			{
				m = world;
				if (m == null)
				{
					m = load();
					world = m;
				}
			}
		}
		return m;
	}

	private static OceanMap load()
	{
		try (InputStream in = OceanMap.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} missing, no shore waves", RESOURCE);
				return new OceanMap(new HashMap<>());
			}
			return read(in);
		}
		catch (IOException e)
		{
			log.warn("Unable to read {}", RESOURCE, e);
			return new OceanMap(new HashMap<>());
		}
	}

	static OceanMap read(InputStream raw) throws IOException
	{
		DataInputStream in = new DataInputStream(raw);
		byte[] magic = new byte[4];
		in.readFully(magic);
		if (magic[0] != 'R' || magic[1] != 'L' || magic[2] != 'O' || magic[3] != 'M')
		{
			throw new IOException("not an ocean file");
		}
		int version = in.readUnsignedShort();
		if (version != VERSION)
		{
			throw new IOException("ocean version " + version);
		}
		int count = in.readUnsignedShort();
		Map<Integer, byte[]> regions = new HashMap<>(count * 2);
		for (int i = 0; i < count; ++i)
		{
			int id = in.readUnsignedShort();
			byte[] packed = new byte[in.readUnsignedShort()];
			in.readFully(packed);
			regions.put(id, inflate(packed));
		}
		return new OceanMap(regions);
	}

	private static byte[] inflate(byte[] packed) throws IOException
	{
		byte[] bits = new byte[REGION_BYTES];
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(packed);
			if (inflater.inflate(bits) != REGION_BYTES || !inflater.finished())
			{
				throw new IOException("bad ocean region");
			}
		}
		catch (DataFormatException e)
		{
			throw new IOException("corrupt ocean region", e);
		}
		finally
		{
			inflater.end();
		}
		return bits;
	}

	/**
	 * Whether world tile (x, y) of the surface is open sea.
	 */
	boolean contains(int x, int y)
	{
		if (x < 0 || y < 0 || x >> 14 != 0 || y >> 14 != 0)
		{
			return false;
		}
		byte[] bits = regions.get(x >> 6 << 8 | y >> 6);
		if (bits == null)
		{
			return false;
		}
		int i = (y & 63) * REGION + (x & 63);
		return (bits[i >> 3] >> (i & 7) & 1) != 0;
	}

	int regionCount()
	{
		return regions.size();
	}
}
