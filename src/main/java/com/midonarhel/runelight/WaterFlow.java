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
 * and shipped as water_flow.dat: per 64x64 region with water, flow vectors on a grid of 2 x 2 samples per tile
 * (version 3; version 2 files have one per tile). Scenes take their flow from it, so a river runs from its real source
 * to its real mouth even when the loaded scene only shows a stretch of it.
 *
 * Version 3 speeds already follow the channel's shape (fast in the middle and through narrows, slow in ponds),
 * worked out from the map at full resolution; version 2 gives one speed across a channel for the scene to shape.
 *
 * Regions stay deflated in memory (about 550 kB for the world); the few a scene touches are unpacked on demand.
 */
final class WaterFlow
{
	private static final Logger log = LoggerFactory.getLogger(WaterFlow.class);
	static final String RESOURCE = "water_flow.dat";
	// flow bytes are hundredths of a tile per second; this marks tiles the map knows nothing about
	private static final byte UNKNOWN = -128;
	// unpacked regions kept: a scene spans up to 4 x 4
	private static final int CACHE = 32;

	private static volatile WaterFlow world;

	private final Map<Integer, byte[]> regions;
	// samples per tile each way, and per region side
	private final int sub;
	private final int side;
	private final int regionBytes;
	private final Map<Integer, byte[]> unpacked = new LinkedHashMap<Integer, byte[]>(CACHE * 2, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest)
		{
			return size() > CACHE;
		}
	};

	WaterFlow(Map<Integer, byte[]> regions, int sub)
	{
		this.regions = regions;
		this.sub = sub;
		this.side = 64 * sub;
		this.regionBytes = side * side * 2;
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
				return new WaterFlow(new HashMap<>(), 1);
			}
			return read(in);
		}
		catch (IOException e)
		{
			log.warn("Unable to read {}", RESOURCE, e);
			return new WaterFlow(new HashMap<>(), 1);
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
		if (version != 2 && version != 3)
		{
			throw new IOException("water flow version " + version);
		}
		int sub = version == 3 ? 2 : 1;
		int regionBytes = 64 * sub * 64 * sub * 2;
		int count = in.readUnsignedShort();
		Map<Integer, byte[]> regions = new HashMap<>(count * 2);
		for (int i = 0; i < count; ++i)
		{
			int id = in.readUnsignedShort();
			int length = in.readInt();
			if (length < 0 || length > regionBytes * 2)
			{
				throw new IOException("bad region length " + length);
			}
			byte[] packed = new byte[length];
			in.readFully(packed);
			regions.put(id, packed);
		}
		return new WaterFlow(regions, sub);
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
		r = new byte[regionBytes];
		Inflater inflater = new Inflater();
		try
		{
			inflater.setInput(packed);
			int n = 0;
			while (n < regionBytes && !inflater.finished())
			{
				int got = inflater.inflate(r, n, regionBytes - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary()))
				{
					break;
				}
				n += got;
			}
			if (n != regionBytes)
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
	 * Whether the speeds already follow the channel's shape (middle, narrows, ponds), so the scene shouldn't shape
	 * them again.
	 */
	boolean shaped()
	{
		return sub > 1;
	}

	/**
	 * Adds the flow of sample (sx, sy) of the world's sample grid ({@link #sub} per tile each way) to out[0], out[1].
	 *
	 * @return false where the map has nothing to say about it
	 */
	private boolean addSample(int sx, int sy, float[] out)
	{
		if (sx < 0 || sy < 0 || sx / side > 255 || sy / side > 255)
		{
			return false;
		}
		byte[] r = region((sx / side) << 8 | (sy / side));
		if (r == null)
		{
			return false;
		}
		int i = ((sy % side) * side + sx % side) * 2;
		if (r[i] == UNKNOWN)
		{
			return false;
		}
		out[0] += r[i] / 100f;
		out[1] += r[i + 1] / 100f;
		return true;
	}

	/**
	 * Mean flow of the samples in sample rectangle [sx0, sx0 + w) x [sy0, sy0 + h) the map knows, into out.
	 */
	private boolean mean(int sx0, int sy0, int w, int h, float[] out)
	{
		out[0] = 0;
		out[1] = 0;
		int n = 0;
		for (int sy = sy0; sy < sy0 + h; ++sy)
		{
			for (int sx = sx0; sx < sx0 + w; ++sx)
			{
				if (addSample(sx, sy, out))
				{
					++n;
				}
			}
		}
		if (n == 0)
		{
			return false;
		}
		out[0] /= n;
		out[1] /= n;
		return true;
	}

	/**
	 * Flow at world tile (x, y), its samples averaged, tiles per second east and north, into out[0], out[1].
	 *
	 * @return false where the map has nothing to say about the tile
	 */
	boolean flow(int x, int y, float[] out)
	{
		return mean(x * sub, y * sub, sub, sub, out);
	}

	/**
	 * Flow at the south-west corner of world tile (x, y): the samples touching the corner averaged (finer than
	 * averaging the four tiles around it), tiles per second east and north, into out[0], out[1].
	 *
	 * @return false where the map has nothing to say about the corner
	 */
	boolean corner(int x, int y, float[] out)
	{
		return mean(x * sub - 1, y * sub - 1, 2, 2, out);
	}

	int regionCount()
	{
		return regions.size();
	}
}
