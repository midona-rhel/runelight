package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;

import com.midonarhel.lumina.common.LayerEase;
import com.midonarhel.lumina.visibility.RoofMap;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Constants;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The walls that stop lamps (light/lamp.glsl), and nothing else does: WallMap's walls (the edges the collision map says
 * block sight, doors as the game has them, and the diagonal walls), in the light buffer after the lamps (LightClusters):
 * a float per tile holding its six wall bits per storey, four tiles to a texel. A tile's bits are its walls in WallMap's
 * bits, its edges toward each side whichever of the two tiles holds it, so a line across the edge reads one tile. Not a
 * texture of its own: on macOS's GL 4.1 a program may sample 16 textures, and the composite is at that.
 *
 * And the ceilings: per tile, a bit per storey where its ceiling covers the tile, so a lamp's line leaving its storey up
 * or down stops there: a floor on the storey above (a tile paint or model there, BuildingLights.floorTiles). A tile with
 * no floor amid floor above (a ladder's hole) is open, as is open ground.
 *
 * And the roofs, as the client draws them (RoofMap): per tile the lowest point of its roof objects, eaves included, so
 * a lamp's line through that height on the tile goes through the roof. The roofs the client hides over the player
 * stop nothing; the layer is redone when they change.
 *
 * Also, per storey, how many walls lie in every rectangle of tiles (a summed-area table), so a lamp with no wall in its
 * reach is marked as such and the shader doesn't walk its lines at all.
 *
 * Both are made on a worker when the walls change (a new WallMap: a door, a new scene); the light buffer takes them once
 * they are ready. A change eases in (LayerEase): a second layer holds the walls before it, and for 0.3 s the shader
 * takes both, weighted by the ease (the header's last number), so a door's light never snaps on in one frame. The
 * first walls of a scene ease in over none.
 */
public final class LampWalls
{
	/** The layers: the walls now and before, the ceilings now and before, the drawn roofs now. */
	public static final int LAYERS = 5;
	static final int ROOF_LAYER = 4;
	/** Texels of a layer: the largest scene's tiles, four to a texel. */
	public static final int LAYER_TEXELS = (Constants.EXTENDED_SCENE_SIZE * Constants.EXTENDED_SCENE_SIZE + 3) / 4;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "Lumina lamp walls");
		t.setDaemon(true);
		return t;
	});

	/** The tiles and counts of one WallMap, made on the worker. */
	private static final class Packed
	{
		final WallMap walls;
		final RoomMap rooms;
		final byte[] floors;
		// a float per tile, rows from the south: the walls (pack), the ceilings (ceilings)
		final float[] tiles, ceilings;
		// per storey, (size + 1)^2 counts of wall tiles south and west of each tile corner
		final int[][] counts;
		final RoofMap roofs;

		Packed(WallMap walls, RoomMap rooms, byte[] floors, RoofMap roofs)
		{
			this.walls = walls;
			this.rooms = rooms;
			this.floors = floors;
			this.roofs = roofs != null && roofs.size == walls.size ? roofs : null;
			tiles = pack(walls);
			ceilings = ceilings(walls.size, floors);
			counts = counts(walls, ceilings, this.roofs);
		}
	}

	// the layers as the light buffer takes them, and a number that changes with them
	private final float[] texels = new float[LAYERS * LAYER_TEXELS * 4];
	private int version;
	// the walls, rooms and floors asked for; those shown (layers 0 and 2) and the ones before them (1 and 3, null: none),
	// and since when
	private WallMap wanted;
	private RoomMap wantedRooms;
	private byte[] wantedFloors;
	private RoofMap wantedRoofs;
	// the roofs' layer as sent: for which packed walls, hidden roofs and player level
	private Packed roofsFor;
	private int roofsHidden, roofsLevel;
	private float[] roofBottoms;
	private Packed shown, before;
	private long since;
	private final AtomicReference<Packed> ready = new AtomicReference<>();
	// lamp.glsl's lampWallsHeader: the shown walls' tiles a side (0: none) and extended tiles before local tile 0, the
	// same of the walls before, and the shown walls' weight
	private final float[] header = new float[5];

	/**
	 * The walls, rooms, floors and roofs now (BuildingLights; walls null: none, nothing stops a lamp): packed on the worker
	 * when they changed, taken when packed. The roofs the client hides (its hidden roof ids, over the player's level)
	 * stop nothing: the roofs' layer follows them.
	 */
	public void update(WallMap walls, RoomMap rooms, byte[] floors, RoofMap roofs, Set<Integer> hiddenRoofs, int level)
	{
		if (walls != null && walls.size * walls.size > LAYER_TEXELS * 4)
		{
			// larger than any scene: none
			walls = null;
		}
		if (walls != wanted || rooms != wantedRooms || floors != wantedFloors || roofs != wantedRoofs)
		{
			wanted = walls;
			wantedRooms = rooms;
			wantedFloors = floors;
			wantedRoofs = roofs;
			if (walls == null)
			{
				shown = before = null;
			}
			else
			{
				WallMap packing = walls;
				WORKER.execute(() -> ready.set(new Packed(packing, rooms, floors, roofs)));
			}
		}
		Packed p = ready.get();
		if (p != null && p.walls == wanted && p.rooms == wantedRooms && p.floors == wantedFloors && p.roofs == wantedRoofs)
		{
			ready.compareAndSet(p, null);
			// another scene's walls stand somewhere else: the new ones ease in over none
			before = shown != null && shown.walls.scene == p.walls.scene ? shown : null;
			shown = p;
			since = com.midonarhel.lumina.common.Clock.nanos();
			Arrays.fill(texels, 0);
			System.arraycopy(shown.tiles, 0, texels, 0, shown.tiles.length);
			System.arraycopy(shown.ceilings, 0, texels, 2 * LAYER_TEXELS * 4, shown.ceilings.length);
			if (before != null)
			{
				System.arraycopy(before.tiles, 0, texels, LAYER_TEXELS * 4, before.tiles.length);
				System.arraycopy(before.ceilings, 0, texels, 3 * LAYER_TEXELS * 4, before.ceilings.length);
			}
			roofsFor = null;
			++version;
		}
		int hiddenHash = hiddenRoofs == null ? 0 : hiddenRoofs.hashCode();
		if (shown != null && (shown != roofsFor || hiddenHash != roofsHidden || level != roofsLevel))
		{
			roofsFor = shown;
			roofsHidden = hiddenHash;
			roofsLevel = level;
			int n = shown.walls.size * shown.walls.size;
			if (roofBottoms == null || roofBottoms.length < n)
			{
				roofBottoms = new float[n];
			}
			if (shown.roofs != null)
			{
				shown.roofs.bottoms(hiddenRoofs, level, roofBottoms);
			}
			else
			{
				Arrays.fill(roofBottoms, 0);
			}
			System.arraycopy(roofBottoms, 0, texels, ROOF_LAYER * LAYER_TEXELS * 4, n);
			++version;
		}
		float weight = shown != null ? LayerEase.weight(since, com.midonarhel.lumina.common.Clock.nanos()) : 1;
		if (weight >= 1)
		{
			before = null;
		}
		header[0] = shown != null ? shown.walls.size : 0;
		header[1] = shown != null ? shown.walls.offset : 0;
		header[2] = before != null ? before.walls.size : 0;
		header[3] = before != null ? before.walls.offset : 0;
		header[4] = weight;
	}

	/** The layers, the walls and ceilings now and before them, as lamp.glsl reads them: LAYER_TEXELS RGBA texels each. */
	public float[] texels()
	{
		return texels;
	}

	/** The shown walls' bits and ceiling bit for a storey's extended tile, as the shader reads them (LampTiles). */
	int[] tileBits(int plane, int x, int y)
	{
		Packed s = shown;
		if (s == null || x < 0 || y < 0 || x >= s.walls.size || y >= s.walls.size)
		{
			return new int[2];
		}
		int i = y * s.walls.size + x;
		return new int[]{(int) s.tiles[i] >> (6 * plane) & 63, (int) s.ceilings[i] >> plane & 1};
	}

	/** Changes whenever the texels do. */
	public int version()
	{
		return version;
	}

	/**
	 * The tiles: rows from the south (y), east along each (x), a float per tile holding each storey's bits (bits) in six
	 * bits, the first storey lowest: 24 bits, which a float holds exactly.
	 */
	static float[] pack(WallMap walls)
	{
		int size = walls.size;
		float[] out = new float[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int v = 0;
				for (int plane = 0; plane < WallMap.PLANES; ++plane)
				{
					v |= bits(walls, plane, x, y) << (6 * plane);
				}
				out[y * size + x] = v;
			}
		}
		return out;
	}

	/**
	 * The ceilings: rows from the south, east along each, a float per tile with bit p set where storey p's ceiling covers
	 * the tile: a floor on storey p + 1 there. Roofs are the roofs' layer's, as drawn.
	 */
	static float[] ceilings(int size, byte[] floors)
	{
		float[] out = new float[size * size];
		if (floors == null || floors.length != size * size)
		{
			return out;
		}
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int v = 0;
				for (int p = 0; p + 1 < WallMap.PLANES; ++p)
				{
					if ((floors[x * size + y] >> (p + 1) & 1) != 0)
					{
						v |= 1 << p;
					}
				}
				out[y * size + x] = v;
			}
		}
		return out;
	}

	/** A tile's bits as the shader reads them. */
	static int bits(WallMap walls, int plane, int x, int y)
	{
		int b = walls.get(plane, x, y) & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE);
		for (int side = WallMap.NORTH; side <= WallMap.WEST; side <<= 1)
		{
			if (walls.edge(plane, x, y, side))
			{
				b |= side;
			}
		}
		return b;
	}

	/**
	 * Per storey, the tiles with a wall, or a ceiling above or below the storey, or a roof (drawn or not), south and west
	 * of each tile corner.
	 */
	static int[][] counts(WallMap walls, float[] ceilings, RoofMap roofs)
	{
		int size = walls.size, n = size + 1;
		int[][] out = new int[WallMap.PLANES][];
		for (int plane = 0; plane < WallMap.PLANES; ++plane)
		{
			int[] c = new int[n * n];
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int ceiling = ceilings != null ? (int) ceilings[y * size + x] : 0;
					int below = plane > 0 ? ceiling >> (plane - 1) & 1 : 0;
					int wall = walls.get(plane, x, y) != 0 || (ceiling >> plane & 1) != 0 || below != 0
						|| roofs != null && roofs.any(x, y) ? 1 : 0;
					c[(x + 1) * n + y + 1] = wall + c[x * n + y + 1] + c[(x + 1) * n + y] - c[x * n + y];
				}
			}
			out[plane] = c;
		}
		return out;
	}

	// A lamp's lines start this far (world units) off any wall of its own tile, on the side it stands: never on a
	// wall's line, where which side they start on would be a matter of rounding and turn with the camera (lamps-14:
	// torches on the diagonal walls of Lumbridge Castle's towers)
	static final float ORIGIN_OFF_WALL = 24;
	private static final float DIAGONAL = (float) Math.sqrt(0.5);

	/**
	 * Where a lamp at local world position (x, z) on a storey starts its lines (into out: x, z): off the walls of its
	 * own tile, on the side of each it stands, by ORIGIN_OFF_WALL, as the walls shown now have them.
	 */
	public void origin(int plane, float x, float z, float[] out)
	{
		Packed s = shown;
		out[0] = x;
		out[1] = z;
		if (s == null || plane < 0 || plane >= WallMap.PLANES)
		{
			return;
		}
		offWalls(s.walls, plane, x, z, out);
	}

	/** origin, for the given walls. */
	static void offWalls(WallMap walls, int plane, float x, float z, float[] out)
	{
		float tx = (float) Math.floor(x / 128), tz = (float) Math.floor(z / 128);
		int b = bits(walls, plane, (int) tx + walls.offset, (int) tz + walls.offset);
		// within the tile, 0 to 128 east and north
		float fx = x - tx * 128, fz = z - tz * 128;
		float off = ORIGIN_OFF_WALL;
		if ((b & WallMap.WEST) != 0) fx = Math.max(fx, off);
		if ((b & WallMap.EAST) != 0) fx = Math.min(fx, 128 - off);
		if ((b & WallMap.SOUTH) != 0) fz = Math.max(fz, off);
		if ((b & WallMap.NORTH) != 0) fz = Math.min(fz, 128 - off);
		if ((b & WallMap.DIAG_SW_NE) != 0)
		{
			// the line fz = fx, its normal toward the north-west half
			float d = (fz - fx) * DIAGONAL;
			if (Math.abs(d) < off)
			{
				float move = (d >= 0 ? off : -off) - d;
				fx -= move * DIAGONAL;
				fz += move * DIAGONAL;
			}
		}
		if ((b & WallMap.DIAG_NW_SE) != 0)
		{
			// the line fx + fz = 128, its normal toward the north-east half
			float d = (fx + fz - 128) * DIAGONAL;
			if (Math.abs(d) < off)
			{
				float move = (d >= 0 ? off : -off) - d;
				fx += move * DIAGONAL;
				fz += move * DIAGONAL;
			}
		}
		out[0] = tx * 128 + Math.max(1, Math.min(127, fx));
		out[1] = tz * 128 + Math.max(1, Math.min(127, fz));
	}

	/**
	 * The storey whose walls and floors stop a lamp at local world position (x, z) of this radius on the given storey,
	 * or -1 when no wall or ceiling lies within its reach there, in the walls shown or those easing out (or nothing is known), so nothing
	 * stops it: what the light buffer says of it.
	 */
	int wallsFor(int plane, float x, float z, float radius)
	{
		if (plane < 0 || plane >= WallMap.PLANES)
		{
			return -1;
		}
		return anyIn(shown, plane, x, z, radius) || anyIn(before, plane, x, z, radius) ? plane : -1;
	}

	private static boolean anyIn(Packed s, int plane, float x, float z, float radius)
	{
		return s != null && anyIn(s.walls, s.counts, plane, x, z, radius);
	}

	/** Whether any wall of the storey lies within radius of local world position (x, z), by the walls' counts. */
	static boolean anyIn(WallMap walls, int[][] counts, int plane, float x, float z, float radius)
	{
		int size = walls.size, n = size + 1;
		// a wall on the edge of a tile one past the reach still bounds it: one tile of margin
		int x0 = Math.max((int) Math.floor((x - radius) / 128) + walls.offset - 1, 0);
		int y0 = Math.max((int) Math.floor((z - radius) / 128) + walls.offset - 1, 0);
		int x1 = Math.min((int) Math.floor((x + radius) / 128) + walls.offset + 1, size - 1);
		int y1 = Math.min((int) Math.floor((z + radius) / 128) + walls.offset + 1, size - 1);
		if (x1 < x0 || y1 < y0)
		{
			return false;
		}
		int[] p = counts[plane];
		return p[(x1 + 1) * n + y1 + 1] - p[x0 * n + y1 + 1] - p[(x1 + 1) * n + y0] + p[x0 * n + y0] > 0;
	}

	/** lamp.glsl's lampWallsHeader as of the last update. */
	public float[] header()
	{
		return header;
	}

	public void free()
	{
		wanted = null;
		shown = before = null;
		ready.set(null);
		Arrays.fill(header, 0);
		Arrays.fill(texels, 0);
		++version;
	}

	/** What a program that reads the lamps needs besides the light buffers: the walls' header and ease. */
	public static final class Uniforms
	{
		private int header = -1, ease = -1;

		public void locate(int program)
		{
			header = glGetUniformLocation(program, "lampWallsHeader");
			ease = glGetUniformLocation(program, "lampWallsEase");
		}

		/** @param walls LampWalls.header */
		public void apply(float[] walls)
		{
			if (header >= 0)
			{
				glUniform4f(header, walls[0], walls[1], walls[2], walls[3]);
			}
			if (ease >= 0)
			{
				glUniform1f(ease, walls[4]);
			}
		}
	}
}
