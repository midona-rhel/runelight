package com.midonarhel.runelight;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which way the water flows across the whole world, worked out offline from the world map (tools/water/build_flow.py)
 * and shipped as water_flow.dat: per 64x64 region with water, a flow vector per tile. Scenes take their flow from
 * it, so a river runs from its real source to its real mouth even when the loaded scene only shows a stretch of it.
 *
 * Regions stay deflated in memory (about 300 kB for the world); the few a scene touches are unpacked on demand.
 */
final class WaterFlow
{
	private static final Logger log = LoggerFactory.getLogger(WaterFlow.class);
	static final String RESOURCE = "water_flow.dat";
	// flow bytes are hundredths of a tile per second; this marks tiles the map knows nothing about
	private static final byte UNKNOWN = -128;
	private static final int REGION_BYTES = 64 * 64 * 2;
	// unpacked regions kept: a scene spans up to 4 x 4
	private static final int CACHE = 32;

	private static volatile WaterFlow world;

	private final Map<Integer, byte[]> regions;
	private final Map<Integer, byte[]> unpacked = new LinkedHashMap<Integer, byte[]>(CACHE * 2, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest)
		{
			return size() > CACHE;
		}
	};

	WaterFlow(Map<Integer, byte[]> regions)
	{
		this.regions = regions;
	}

	/**
	 * The world's flow, loaded on first use. Never null: empty if the resource can't be read.
	 */
	static WaterFlow world()
	{
		WaterFlow w = world;
		if (w == null)
		{
			synchronized (WaterFlow.class)
			{
				w = world;
				if (w == null)
				{
					w = load();
					world = w;
				}
			}
		}
		return w;
	}

	private static WaterFlow load()
	{
		try (InputStream in = WaterFlow.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				log.warn("{} missing, water flow from the scene only", RESOURCE);
				return new WaterFlow(new HashMap<>());
			}
			return read(in);
		}
		catch (IOException e)
		{
			log.warn("Unable to read {}", RESOURCE, e);
			return new WaterFlow(new HashMap<>());
		}
	}

	static WaterFlow read(InputStream raw) throws IOException
	{
		DataInputStream in = new DataInputStream(raw);
		byte[] magic = new byte[4];
		in.readFully(magic);
		if (magic[0] != 'R' || magic[1] != 'L' || magic[2] != 'W' || magic[3] != 'F')
		{
			throw new IOException("not a water flow file");
		}
		int version = in.readUnsignedShort();
		if (version != 2)
		{
			throw new IOException("water flow version " + version);
		}
		int count = in.readUnsignedShort();
		Map<Integer, byte[]> regions = new HashMap<>(count * 2);
		for (int i = 0; i < count; ++i)
		{
			int id = in.readUnsignedShort();
			int length = in.readInt();
			if (length < 0 || length > REGION_BYTES * 2)
			{
				throw new IOException("bad region length " + length);
			}
			byte[] packed = new byte[length];
			in.readFully(packed);
			regions.put(id, packed);
		}
		return new WaterFlow(regions);
	}

	private synchronized byte[] region(int id)
	{
		byte[] r = unpacked.get(id);
		if (r != null)
		{
			return r;
		}
		byte[] packed = regions.get(id);
		if (packed == null)
		{
			return null;
		}
		r = new byte[REGION_BYTES];
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(packed);
			int n = 0;
			while (n < REGION_BYTES && !inflater.finished())
			{
				int got = inflater.inflate(r, n, REGION_BYTES - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary()))
				{
					break;
				}
				n += got;
			}
			if (n != REGION_BYTES)
			{
				log.warn("Water flow region {} is short", id);
				return null;
			}
		}
		catch (DataFormatException e)
		{
			log.warn("Water flow region {} is corrupt", id, e);
			return null;
		}
		finally
		{
			inflater.end();
		}
		unpacked.put(id, r);
		return r;
	}

	/**
	 * Flow at world tile (x, y), tiles per second east and north, into out[0], out[1].
	 *
	 * @return false where the map has nothing to say about the tile
	 */
	boolean flow(int x, int y, float[] out)
	{
		if (x < 0 || y < 0 || x >> 6 > 255 || y >> 6 > 255)
		{
			return false;
		}
		byte[] r = region((x >> 6) << 8 | (y >> 6));
		if (r == null)
		{
			return false;
		}
		int i = ((y & 63) * 64 + (x & 63)) * 2;
		if (r[i] == UNKNOWN)
		{
			return false;
		}
		out[0] = r[i] / 100f;
		out[1] = r[i + 1] / 100f;
		return true;
	}

	int regionCount()
	{
		return regions.size();
	}
}
