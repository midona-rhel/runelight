package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.WallMap;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.runelite.api.Constants;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The light map (LightMap) as the light buffer holds it, after its fixed part (LightClusters.LIGHT_MAP_BASE): not a
 * texture of its own, since the composite is at macOS's 16 samplers. Sparse: only the blocks of 32 x 32 cells that hold
 * a light are stored, each found through an index per storey; the shaders filter its cells by hand (light/lamp_map.glsl).
 * From LIGHT_MAP_BASE, in RGBA32F texels:
 *
 * - HEADER (1): the blocks a side (0: no map), the cells before local cell 0, the tiles a side, the tiles before local
 *   tile 0.
 * - FLOORS (a texel per extended tile, x * size + y): the four storeys' floor heights (world y, down) there, the floor of a
 *   storey above the first NO_FLOOR where it has no floor tile: which storey a point is on, and its height above it.
 * - INDEX: per storey and block ((storey * blocks + bz) * blocks + bx), four to a texel, the block's place + 1 (0: none).
 * - BLOCKS: per block in place, BLOCK_TEXELS: two texels per cell (z * BLOCK + x), eight numbers of 24 bits each (exact in
 *   a float): the cell's visibility bytes, layer * SLOTS + slot (0 to 255 for 0 to 1), three to a number in numbers 0 to 5,
 *   then the table ids of the slots' lights, two of 12 bits to a number in numbers 6 and 7.
 *
 * The CPU keeps a copy; each of the light buffer's copies (LightClusters) is brought up to date when it comes round,
 * with what changed since it last was. Blocks are put in by LightMap.update, the render thread's only part.
 */
public final class LightMapBuffer implements LightMap.Sink
{
	static final int BLOCK = LightMap.BLOCK;
	static final int CELLS = BLOCK * BLOCK;
	static final int BLOCK_TEXELS = CELLS * 2;
	static final int MAX_TILES = Constants.EXTENDED_SCENE_SIZE;
	static final int MAX_BLOCKS_PER_SIDE = (MAX_TILES * LightBlockers.CELLS_PER_TILE + BLOCK - 1) / BLOCK;
	public static final int HEADER = 0;
	public static final int FLOORS = 1;
	public static final int INDEX = FLOORS + MAX_TILES * MAX_TILES;
	static final int INDEX_TEXELS = (WallMap.PLANES * MAX_BLOCKS_PER_SIDE * MAX_BLOCKS_PER_SIDE + 3) / 4;
	public static final int BLOCKS = INDEX + INDEX_TEXELS;
	/** The floor of a storey above the first where it has no floor tile: no point is above it. */
	static final float NO_FLOOR = -1e9f;
	private static final int SLOTS = LightMap.SLOTS, LAYERS = LightMapBake.HEIGHT_LAYERS;
	private static final int SETS = 3;

	private final float[] header = new float[4];
	private final float[] floors = new float[MAX_TILES * MAX_TILES * 4];
	private final float[] index = new float[INDEX_TEXELS * 4];
	// the blocks in place, BLOCK_TEXELS * 4 floats each
	private float[] blocks = new float[0];
	// per storey and block, its place + 1 (0: none)
	private int[] placeOf = new int[0];
	private final ArrayDeque<Integer> freePlaces = new ArrayDeque<>();
	private int places;
	private int perSide;
	private LightBlockers scene;
	// what changed, and what each copy of the light buffer has: the header and index, the floors, each place's block
	private int headerVersion = 1, floorsVersion = 1;
	private int[] placeVersion = new int[0];
	private final int[] sentHeader = new int[SETS], sentFloors = new int[SETS];
	private final int[][] sentPlace = new int[SETS][0];
	private final FloatBuffer staging = BufferUtils.createFloatBuffer(Math.max(BLOCK_TEXELS, MAX_TILES * MAX_TILES) * 4);

	/**
	 * The scene the map is baked for (LightMap's blockers): a scene of another size or place starts the buffer over
	 * (LightMap starts over then too). Client thread.
	 */
	public void setScene(LightBlockers b, int blocksPerSide)
	{
		if (b == scene)
		{
			return;
		}
		LightBlockers old = scene;
		scene = b;
		if (b == null || old == null || old.size != b.size || old.offset != b.offset || perSide != blocksPerSide)
		{
			clearBlocks(b == null ? 0 : blocksPerSide);
		}
		header[0] = b == null ? 0 : perSide;
		header[1] = b == null ? 0 : b.offset * LightBlockers.CELLS_PER_TILE;
		header[2] = b == null ? 0 : b.size;
		header[3] = b == null ? 0 : b.offset;
		++headerVersion;
		if (b != null)
		{
			int size = Math.min(b.size, MAX_TILES);
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int o = (x * b.size + y) * 4;
					if (o + 3 >= floors.length)
					{
						continue;
					}
					for (int p = 0; p < WallMap.PLANES; ++p)
					{
						floors[o + p] = p == 0 || b.hasFloor(p, x, y) ? b.floor(p, x, y) : NO_FLOOR;
					}
				}
			}
			++floorsVersion;
		}
	}

	private void clearBlocks(int blocksPerSide)
	{
		perSide = Math.min(blocksPerSide, MAX_BLOCKS_PER_SIDE);
		placeOf = new int[WallMap.PLANES * perSide * perSide];
		Arrays.fill(index, 0);
		freePlaces.clear();
		for (int i = places - 1; i >= 0; --i)
		{
			freePlaces.push(i);
		}
		++headerVersion;
	}

	@Override
	public void block(int plane, int bx, int bz, byte[] vis, byte[] ids)
	{
		if (bx >= perSide || bz >= perSide || plane < 0 || plane >= WallMap.PLANES)
		{
			return;
		}
		int key = (plane * perSide + bz) * perSide + bx;
		int place = placeOf[key] - 1;
		if (vis == null)
		{
			if (place >= 0)
			{
				placeOf[key] = 0;
				index[key] = 0;
				freePlaces.push(place);
				++headerVersion;
			}
			return;
		}
		if (place < 0)
		{
			place = freePlaces.isEmpty() ? grow() : freePlaces.pop();
			placeOf[key] = place + 1;
			index[key] = place + 1;
			++headerVersion;
		}
		pack(vis, ids, blocks, place * BLOCK_TEXELS * 4);
		++placeVersion[place];
	}

	/** One more place for a block. */
	private int grow()
	{
		int place = places++;
		if ((long) places * BLOCK_TEXELS * 4 > blocks.length)
		{
			int more = Math.max(16, places / 2);
			blocks = Arrays.copyOf(blocks, (places + more) * BLOCK_TEXELS * 4);
			placeVersion = Arrays.copyOf(placeVersion, places + more);
			for (int s = 0; s < SETS; ++s)
			{
				sentPlace[s] = Arrays.copyOf(sentPlace[s], places + more);
			}
		}
		return place;
	}

	/**
	 * A block of LightMap.Sink's layout (vis: per layer a slice of RGBA bytes, a channel per slot; ids: two slices, the
	 * slots' ids 16 bits each) packed as the buffer holds it, at offset (floats) of out.
	 */
	static void pack(byte[] vis, byte[] ids, float[] out, int offset)
	{
		int[] bytes = new int[LAYERS * SLOTS];
		for (int i = 0; i < CELLS; ++i)
		{
			for (int layer = 0; layer < LAYERS; ++layer)
			{
				for (int slot = 0; slot < SLOTS; ++slot)
				{
					bytes[layer * SLOTS + slot] = vis[(layer * CELLS + i) * 4 + slot] & 0xff;
				}
			}
			int[] id = new int[SLOTS];
			for (int slot = 0; slot < SLOTS; ++slot)
			{
				int at = ((slot >> 1) * CELLS + i) * 4 + (slot & 1) * 2;
				id[slot] = (ids[at] & 0xff | (ids[at + 1] & 0xff) << 8) & 0xfff;
			}
			int o = offset + i * 8;
			for (int w = 0; w < 6; ++w)
			{
				int v = 0;
				for (int k = 0; k < 3; ++k)
				{
					int b = w * 3 + k;
					if (b < bytes.length)
					{
						v |= bytes[b] << (8 * k);
					}
				}
				out[o + w] = v;
			}
			out[o + 6] = id[0] | id[1] << 12;
			out[o + 7] = id[2] | id[3] << 12;
		}
	}

	/** The texels the map takes after LIGHT_MAP_BASE, with room for every block place made so far. */
	int texels()
	{
		return BLOCKS + blocks.length / 4;
	}

	/** A copy of the light buffer was made anew: it has none of the map. */
	void invalidate(int set)
	{
		sentHeader[set] = sentFloors[set] = 0;
		Arrays.fill(sentPlace[set], -1);
	}

	/**
	 * Bring a copy of the light buffer, bound to GL_TEXTURE_BUFFER and large enough (texels), up to date: what changed
	 * since it last was.
	 *
	 * @param base the map's first texel in it
	 */
	void upload(int set, int base)
	{
		long at = (long) base * 4 * Float.BYTES;
		if (sentHeader[set] != headerVersion)
		{
			send(at + (long) HEADER * 16, header, 0, 4);
			send(at + (long) INDEX * 16, index, 0, index.length);
			sentHeader[set] = headerVersion;
		}
		if (sentFloors[set] != floorsVersion)
		{
			send(at + (long) FLOORS * 16, floors, 0, floors.length);
			sentFloors[set] = floorsVersion;
		}
		int[] sent = sentPlace[set];
		for (int p = 0; p < places; ++p)
		{
			if (sent[p] != placeVersion[p])
			{
				send(at + ((long) BLOCKS + (long) p * BLOCK_TEXELS) * 16, blocks, p * BLOCK_TEXELS * 4, BLOCK_TEXELS * 4);
				sent[p] = placeVersion[p];
			}
		}
	}

	private void send(long byteOffset, float[] data, int from, int floats)
	{
		staging.clear();
		staging.put(data, from, floats).flip();
		glBufferSubData(GL_TEXTURE_BUFFER, byteOffset, staging);
	}

	@Override
	public void floor(int plane, int bx, int bz, float[] floor)
	{
		// the rooms' light comes from the lamps on the CPU (RoomLight.followLamps), not from the map
	}

	/** The layout's numbers for the shaders (cluster_config; light/lamp_map.glsl). */
	public static String defines()
	{
		int base = LightClusters.LIGHT_MAP_BASE;
		return "#define LIGHT_MAP_HEADER " + (base + HEADER) + "\n"
			+ "#define LIGHT_MAP_FLOORS " + (base + FLOORS) + "\n"
			+ "#define LIGHT_MAP_INDEX " + (base + INDEX) + "\n"
			+ "#define LIGHT_MAP_BLOCKS " + (base + BLOCKS) + "\n"
			+ "#define LIGHT_MAP_BLOCK_TEXELS " + BLOCK_TEXELS + "\n"
			+ "#define LIGHT_MAP_LAYERS " + LAYERS + "\n"
			+ "#define LIGHT_MAP_LAYER_HEIGHT " + (float) LightMapBake.LAYER_HEIGHT + "\n";
	}

	/** Blocks in place now, for the log. */
	int blocksInPlace()
	{
		return places - freePlaces.size();
	}
}
