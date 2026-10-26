package com.midonarhel.runelight;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Clustered light culling. The view is split into a grid of CLUSTERS_X * CLUSTERS_Y screen tiles and
 * CLUSTERS_Z exponential depth slices; every light is listed only in the clusters its sphere overlaps.
 * The composite pass looks up the pixel's cluster and shades just those lights, so cost follows how many
 * lights overlap a pixel rather than how many exist.
 *
 * Built on the CPU because macOS OpenGL 4.1 has no compute shaders; for OSRS light counts this is cheap.
 * Both tables go to the GPU as buffer textures. A cluster lists its lights as a mask with a bit per light in view, in
 * RGBA32UI texels of 128 lights each: the shader walks the set bits instead of fetching an index per light, and the
 * table is a quarter of a list's size. As many texels per cluster as there are lights in view, in 128s.
 */
class LightClusters
{
	static final int CLUSTERS_X = 32;
	static final int CLUSTERS_Y = 18;
	static final int CLUSTERS_Z = 24;
	static final int MAX_LIGHTS_PER_CLUSTER = 31;
	static final int MAX_LIGHTS = 1024;
	static final int LIGHTS_PER_TEXEL = 128;
	static final float NEAR = 50;
	static final float FAR = 25600;
	// the light map's table follows (LightMap.table, light_map.glsl): TABLE_TEXELS texels, two to a baked light: its place
	// and radius now, and its colour times its strength now
	static final int LIGHT_MAP_TABLE_BASE = MAX_LIGHTS * 2;
	// the rooms' fill follows (RoomLight.table, rooms.glsl): a texel per room id, x the fill's luminance
	static final int ROOM_FILL_BASE = LIGHT_MAP_TABLE_BASE + LightMap.TABLE_TEXELS;
	// the room map (RoomMap.texels) follows, from this texel on (rooms.glsl)
	static final int ROOM_BASE = ROOM_FILL_BASE + RoomMap.ANY;

	private static final int CLUSTER_COUNT = CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z;
	// Fog columns, stored after the clusters: per screen tile, the lights that glow in the fog anywhere along its
	// rays (fog_frag.glsl integrates each once per pixel instead of looking up clusters at every step). Indoor
	// lights are left out unless their room has no roof right now (OpenRooms), and lights whose sphere misses the fog
	// (its cylinder or layer) or whose glow in it can't show (setFog). When a column is full, the brightest lights win,
	// in the same order for every column, so neighbouring columns keep the same lights instead of showing the tile grid.
	private static final int COLUMN_COUNT = CLUSTERS_X * CLUSTERS_Y;
	// entries of the light masks: the clusters, a column's depth slices next to each other as the culling walks them,
	// then the fog columns
	static final int CLUSTER_ENTRIES = CLUSTER_COUNT + COLUMN_COUNT;

	// Both buffers rotate through SETS copies, one written a frame: writing a buffer an earlier frame's draws still
	// read stalls or copies it on Apple's GL. Each copy is brought up to date when it comes round again.
	private static final int SETS = 3;
	private final int[] lightBuffers = new int[SETS], lightTextures = new int[SETS];
	private final int[] clusterBuffers = new int[SETS], clusterTextures = new int[SETS];
	private int set;
	// The light buffer: the lights and the room map after them. The map is sent to a copy when it changed since that
	// copy got it, each frame only the lights; the map's header goes to the shaders as a uniform (getRoomHeader).
	private RoomMap roomMap;
	private final RoomMap[] uploadedRooms = new RoomMap[SETS];
	private final int[] uploadedVersion = new int[SETS];
	// the rooms' fill, and the version each copy of the buffer has
	private RoomLight roomFill;
	private final int[] uploadedFill = new int[SETS];
	private int maxTexels;
	private FloatBuffer roomStaging, tableStaging, fillStaging;
	private final float[] roomHeader = new float[3];
	// the baked lights' table, null without the light map (setLightMap)
	private LightMap lightMap;
	// the view the cluster bounds were computed for: they only change with the camera
	private final float[] boundsViewProj = new float[16];
	private boolean boundsValid;

	private final FloatBuffer lightData = BufferUtils.createFloatBuffer(MAX_LIGHTS * 8);
	// per entry, texels * 4 words of light bits; light i is bit i & 31 of word i >> 5
	private final int[] masks = new int[CLUSTER_ENTRIES * MAX_LIGHTS / 32];
	private final IntBuffer maskData = BufferUtils.createIntBuffer(masks.length);
	// lights listed per entry, held to MAX_LIGHTS_PER_CLUSTER
	private final byte[] counts = new byte[CLUSTER_ENTRIES];
	private int texels = 1;
	// The table is only rebuilt when what it depends on changed, and each half only sent to a copy that has not had
	// that build; a copy is laid out again only when the texels per entry change (the shaders find them from its size).
	private int clustersBuilt, columnsBuilt;
	private final int[] bufferTexels = new int[SETS], clustersSent = new int[SETS], columnsSent = new int[SETS];
	// per light in view: camera-relative center and radius, as last culled, and its cluster range x0, y0, x1, y1, z0, z1
	private final float[] spheres = new float[MAX_LIGHTS * 4];
	private final int[] ranges = new int[MAX_LIGHTS * 6];

	// fog: camera-relative xz of the fog's center and its radius; columns are only filled while enabled
	private boolean fogColumns;
	// rooms whose roof is hidden: their lights glow in the fog (null: none)
	private OpenRooms openRooms;
	private float fogX, fogZ, fogRadius, fogTop, fogMinGlow;
	// candidates for the fog columns this frame, packed for sorting by brightness, and their lights
	private long[] fogOrder = new long[64];
	private int fogCandidates;
	private int[] fogLight = new int[64];
	// the candidates' lights brightest first, as the columns were last filled with them
	private int[] fogFilled = new int[64];
	private int fogFilledCount;
	private final float[] tile = new float[4];

	private int lightCount;
	private int overflows;

	// camera-relative world space bounds of every cluster, in the masks' order: minX, minY, minZ, maxX, maxY, maxZ
	private final float[] bounds = new float[CLUSTER_COUNT * 6];
	// view rays through the cluster grid corners, scaled to view depth 1
	private final float[] rays = new float[(CLUSTERS_X + 1) * (CLUSTERS_Y + 1) * 3];
	private final float[] columnPlanes = new float[COLUMN_COUNT * 16];
	// per column, the smallest and largest x, y, z of its four corner rays (computeBounds)
	private final float[] columnRays = new float[COLUMN_COUNT * 6];
	private final float[] sliceDepth = new float[CLUSTERS_Z + 1];

	void init()
	{
		maxTexels = glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE);
		glGenBuffers(lightBuffers);
		glGenTextures(lightTextures);
		glGenBuffers(clusterBuffers);
		glGenTextures(clusterTextures);
		for (int i = 0; i < SETS; ++i)
		{
			glBindBuffer(GL_TEXTURE_BUFFER, lightBuffers[i]);
			glBufferData(GL_TEXTURE_BUFFER, (long) (ROOM_BASE + RoomMap.HEADER_TEXELS) * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
			glBindTexture(GL_TEXTURE_BUFFER, lightTextures[i]);
			glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, lightBuffers[i]);
			uploadedRooms[i] = null;
			uploadedVersion[i] = -1;
			uploadedFill[i] = -1;

			glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffers[i]);
			bufferTexels[i] = texels;
			glBufferData(GL_TEXTURE_BUFFER, (long) CLUSTER_ENTRIES * texels * 4 * Integer.BYTES, GL_DYNAMIC_DRAW);
			// the masks as they are, whether built yet or not, have not been sent
			clustersSent[i] = clustersBuilt - 1;
			columnsSent[i] = columnsBuilt - 1;
			glBindTexture(GL_TEXTURE_BUFFER, clusterTextures[i]);
			glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32UI, clusterBuffers[i]);
		}

		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);

	}

	void free()
	{
		if (lightBuffers[0] != 0)
		{
			glDeleteTextures(lightTextures);
			glDeleteTextures(clusterTextures);
			glDeleteBuffers(lightBuffers);
			glDeleteBuffers(clusterBuffers);
			Arrays.fill(lightBuffers, 0);
			Arrays.fill(lightTextures, 0);
			Arrays.fill(clusterBuffers, 0);
			Arrays.fill(clusterTextures, 0);
		}
		boundsValid = false;
	}

	boolean isInitialized()
	{
		return lightBuffers[0] != 0;
	}

	int getLightCount()
	{
		return lightCount;
	}

	/**
	 * Clusters that hit the per-cluster limit last frame; lights beyond it were dropped there.
	 */
	int getOverflows()
	{
		return overflows;
	}

	/**
	 * Whether to fill the fog columns, and the fog's extent: camera-relative center (xz) and radius and the top of its
	 * layer (y, pointing down), world units; and the least brightest color channel times radius a light needs for its
	 * glow to show (VolumetricFog.getMinGlow).
	 */
	void setFog(boolean enabled, float centerX, float centerZ, float radius, float top, float minGlow)
	{
		fogColumns = enabled;
		fogX = centerX;
		fogZ = centerZ;
		fogRadius = radius;
		fogTop = top;
		fogMinGlow = minGlow;
	}

	/** Whether any light can glow in the fog this frame, as of {@link #cull}. */
	boolean hasFogLights()
	{
		return fogCandidates > 0;
	}

	/**
	 * The rooms without a roof this frame, whose lights light the fog above their walls (null: none).
	 */
	void setOpenRooms(OpenRooms open)
	{
		openRooms = open != null && open.count() > 0 ? open : null;
	}

	/** The rooms: the fog stays out of them and the light map knows the floors (null: none). */
	void setRooms(RoomMap rooms)
	{
		roomMap = rooms;
	}

	/** The fill the rooms get (null: none), which goes up with the lights. */
	void setRoomFill(RoomLight fill)
	{
		roomFill = fill;
	}

	/**
	 * The light map the shaders read (null: none, the lights are all the clusters'). The lights in the frame's list are
	 * then the ones it doesn't carry: the moving ones, and the static ones not baked yet, which keep to their storey,
	 * and the map's table goes up with them.
	 */
	void setLightMap(LightMap map)
	{
		lightMap = map;
	}

	/**
	 * Cull lights into clusters and upload both tables.
	 *
	 * @param viewProj camera-relative world to clip, as used by the composite pass
	 */
	void update(List<PointLight> lights, float[] viewProj, float camX, float camY, float camZ)
	{
		cull(lights, viewProj, camX, camY, camZ);
		upload();
	}

	/**
	 * Fill the light and cluster tables on the CPU, without touching GL.
	 */
	void cull(List<PointLight> lights, float[] viewProj, float camX, float camY, float camZ)
	{
		lightData.clear();
		fogCandidates = 0;
		// the clusters only change with the view and the spheres of the lights in it
		boolean changed = !boundsValid || !Arrays.equals(viewProj, boundsViewProj);
		if (changed)
		{
			computeBounds(viewProj);
			System.arraycopy(viewProj, 0, boundsViewProj, 0, 16);
			boundsValid = true;
		}
		RoomMap rooms = roomMap;

		int n = Math.min(lights.size(), MAX_LIGHTS);
		int kept = 0;
		for (int i = 0; i < n; ++i)
		{
			PointLight l = lights.get(i);
			float x = l.x - camX, y = l.y - camY, z = l.z - camZ;

			// view depth of the sphere: clip w is the distance along the view axis
			float w = viewProj[3] * x + viewProj[7] * y + viewProj[11] * z + viewProj[15];
			if (w + l.radius < NEAR || w - l.radius > FAR)
			{
				continue;
			}
			if (!screenBounds(viewProj, x, y, z, l.radius, tile))
			{
				continue;
			}

			int index = kept++;
			int s = index * 4;
			if (spheres[s] != x || spheres[s + 1] != y || spheres[s + 2] != z || spheres[s + 3] != l.radius)
			{
				spheres[s] = x;
				spheres[s + 1] = y;
				spheres[s + 2] = z;
				spheres[s + 3] = l.radius;
				changed = true;
			}
			int r = index * 6;
			ranges[r] = clampi((int) Math.floor((tile[0] * 0.5f + 0.5f) * CLUSTERS_X), 0, CLUSTERS_X - 1);
			ranges[r + 1] = clampi((int) Math.floor((tile[1] * 0.5f + 0.5f) * CLUSTERS_Y), 0, CLUSTERS_Y - 1);
			ranges[r + 2] = clampi((int) Math.floor((tile[2] * 0.5f + 0.5f) * CLUSTERS_X), 0, CLUSTERS_X - 1);
			ranges[r + 3] = clampi((int) Math.floor((tile[3] * 0.5f + 0.5f) * CLUSTERS_Y), 0, CLUSTERS_Y - 1);
			ranges[r + 4] = slice(w - l.radius);
			ranges[r + 5] = slice(w + l.radius);

			int room = rooms != null ? rooms.roomOfLight(l.x, l.z, l.plane) : 0;
			// a light only in a building room counts as indoors
			boolean indoor = room != 0;
			lightData.put(x).put(y).put(z).put(l.radius);
			lightData.put(l.r * l.intensity).put(l.g * l.intensity).put(l.b * l.intensity).put(storeyCode(l.plane, indoor));

			if (fogColumns && (!indoor || openRooms != null && openRooms.isOpen(room)))
			{
				// the fog's rays are clipped to its cylinder and layer: a sphere outside either has no glow in them
				float fx = x - fogX, fz = z - fogZ, reach = fogRadius + l.radius;
				if (fx * fx + fz * fz < reach * reach && y + l.radius > fogTop
					&& Math.max(l.r, Math.max(l.g, l.b)) * l.intensity * l.radius >= fogMinGlow)
				{
					addFogCandidate(index, (l.r + l.g + l.b) * l.intensity * l.radius);
				}
			}
		}
		changed |= kept != lightCount;
		lightCount = kept;
		lightData.flip();
		if (changed)
		{
			texels = Math.max(1, (kept + LIGHTS_PER_TEXEL - 1) / LIGHTS_PER_TEXEL);
			fillClusters();
		}
		fillFogColumns(changed);
	}

	private void fillClusters()
	{
		int words = texels * 4;
		Arrays.fill(masks, 0, CLUSTER_COUNT * words, 0);
		Arrays.fill(counts, 0, CLUSTER_COUNT, (byte) 0);
		overflows = 0;
		for (int index = 0; index < lightCount; ++index)
		{
			int s = index * 4, r = index * 6;
			float x = spheres[s], y = spheres[s + 1], z = spheres[s + 2], r2 = spheres[s + 3] * spheres[s + 3];
			int x0 = ranges[r], y0 = ranges[r + 1], x1 = ranges[r + 2], y1 = ranges[r + 3];
			int z0 = ranges[r + 4], z1 = ranges[r + 5];
			int word = index >> 5, bit = 1 << (index & 31);
			for (int cy = y0; cy <= y1; ++cy)
			{
				for (int cx = x0; cx <= x1; ++cx)
				{
					int column = cy * CLUSTERS_X + cx;
					if (!columnTouches(column, x, y, z, r2)) continue;
					for (int cz = z0; cz <= z1; ++cz)
					{
						int cluster = column * CLUSTERS_Z + cz;
						// The sphere against the cluster's box. Comparisons rather than Math.max, whose NaN and -0 handling
						// made it twice as slow here.
						int b = cluster * 6;
						float below = bounds[b] - x, above = x - bounds[b + 3];
						float dx = below > 0 ? below : above > 0 ? above : 0;
						below = bounds[b + 1] - y;
						above = y - bounds[b + 4];
						float dy = below > 0 ? below : above > 0 ? above : 0;
						below = bounds[b + 2] - z;
						above = z - bounds[b + 5];
						float dz = below > 0 ? below : above > 0 ? above : 0;
						if (dx * dx + dy * dy + dz * dz > r2)
						{
							continue;
						}
						if (counts[cluster] == MAX_LIGHTS_PER_CLUSTER)
						{
							++overflows;
							continue;
						}
						++counts[cluster];
						masks[cluster * words + word] |= bit;
					}
				}
			}
		}
		++clustersBuilt;
	}

	private void addFogCandidate(int index, float brightness)
	{
		int c = fogCandidates++;
		if (c == fogOrder.length)
		{
			fogOrder = Arrays.copyOf(fogOrder, c * 2);
			fogLight = Arrays.copyOf(fogLight, c * 2);
		}
		// brightest first; ties by index so the order is stable. Positive floats sort like their bits.
		long key = (long) Float.floatToIntBits(Math.max(brightness, 0)) << 32 | (0xFFFFFFFFL - c);
		fogOrder[c] = key;
		fogLight[c] = index;
	}

	/**
	 * Fill the fog columns, unless the lights' spheres and the candidates, brightest first, are as they were.
	 */
	private void fillFogColumns(boolean changed)
	{
		Arrays.sort(fogOrder, 0, fogCandidates);
		if (fogFilled.length < fogCandidates)
		{
			fogFilled = Arrays.copyOf(fogFilled, fogOrder.length);
		}
		boolean same = !changed && fogCandidates == fogFilledCount;
		for (int k = 0; k < fogCandidates; ++k)
		{
			int c = (int) (0xFFFFFFFFL - (fogOrder[fogCandidates - 1 - k] & 0xFFFFFFFFL));
			int index = fogLight[c];
			same &= fogFilled[k] == index;
			fogFilled[k] = index;
		}
		fogFilledCount = fogCandidates;
		if (same)
		{
			return;
		}
		int words = texels * 4;
		Arrays.fill(masks, CLUSTER_COUNT * words, CLUSTER_ENTRIES * words, 0);
		Arrays.fill(counts, CLUSTER_COUNT, CLUSTER_ENTRIES, (byte) 0);
		for (int k = 0; k < fogCandidates; ++k)
		{
			int index = fogFilled[k];
			int s = index * 4, r = index * 6;
			float x = spheres[s], y = spheres[s + 1], z = spheres[s + 2], r2 = spheres[s + 3] * spheres[s + 3];
			int word = index >> 5, bit = 1 << (index & 31);
			for (int cy = ranges[r + 1]; cy <= ranges[r + 3]; ++cy)
			{
				for (int cx = ranges[r]; cx <= ranges[r + 2]; ++cx)
				{
					int column = cy * CLUSTERS_X + cx;
					if (!columnTouches(column, x, y, z, r2)) continue;
					int entry = CLUSTER_COUNT + column;
					if (counts[entry] < MAX_LIGHTS_PER_CLUSTER)
					{
						++counts[entry];
						masks[entry * words + word] |= bit;
					}
				}
			}
		}
		++columnsBuilt;
	}

	/** The light masks as cull left them, CLUSTER_ENTRIES entries of {@link #clusterTexels} * 4 words. */
	int[] clusterTable()
	{
		return masks;
	}

	int clusterTexels()
	{
		return texels;
	}

	void upload()
	{
		RoomMap rooms = roomMap;
		if (rooms != null && ROOM_BASE + rooms.texelCount() > maxTexels)
		{
			// the driver's buffer textures are too small for the map (GL 3.3 only promises 65536 texels)
			rooms = null;
		}
		int roomTexels = rooms != null ? rooms.texelCount() : RoomMap.HEADER_TEXELS;
		set = (set + 1) % SETS;
		glBindBuffer(GL_TEXTURE_BUFFER, lightBuffers[set]);
		if (rooms != uploadedRooms[set] || rooms != null && rooms.getVersion() != uploadedVersion[set])
		{
			// the map changed since this copy got it: lay the buffer out again and send it
			glBufferData(GL_TEXTURE_BUFFER, (long) (ROOM_BASE + roomTexels) * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
			if (rooms != null)
			{
				roomStaging = staging(roomStaging, roomTexels * 4);
				rooms.texels(roomStaging);
				roomStaging.flip();
				glBufferSubData(GL_TEXTURE_BUFFER, (long) ROOM_BASE * 4 * Float.BYTES, roomStaging);
			}
			uploadedRooms[set] = rooms;
			uploadedVersion[set] = rooms != null ? rooms.getVersion() : -1;
			// the buffer was laid out again, and its fill is gone
			uploadedFill[set] = -1;
		}
		RoomLight fill = roomFill;
		if (fill != null && rooms != null && fill.version() != uploadedFill[set])
		{
			fillStaging = staging(fillStaging, fill.used() * 4);
			fillStaging.put(fill.table(), 0, fill.used() * 4).flip();
			glBufferSubData(GL_TEXTURE_BUFFER, (long) ROOM_FILL_BASE * 4 * Float.BYTES, fillStaging);
			uploadedFill[set] = fill.version();
		}
		if (lightCount > 0)
		{
			glBufferSubData(GL_TEXTURE_BUFFER, 0, lightData);
		}
		LightMap baked = lightMap;
		if (baked != null)
		{
			int floats = baked.tableUsed() * LightMap.TABLE_STRIDE * 4;
			tableStaging = staging(tableStaging, floats);
			tableStaging.put(baked.table(), 0, floats).flip();
			glBufferSubData(GL_TEXTURE_BUFFER, (long) LIGHT_MAP_TABLE_BASE * 4 * Float.BYTES, tableStaging);
		}
		// the map's header: whether there is a map (3: and the light map), its size and its offset
		roomHeader[0] = rooms != null ? (baked != null ? 3 : 2) : 0;
		roomHeader[1] = rooms != null ? rooms.size : 0;
		roomHeader[2] = rooms != null ? rooms.offset : 0;

		glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffers[set]);
		int words = texels * 4;
		boolean clusters = clustersSent[set] != clustersBuilt, columns = columnsSent[set] != columnsBuilt;
		if (texels != bufferTexels[set])
		{
			glBufferData(GL_TEXTURE_BUFFER, (long) CLUSTER_ENTRIES * words * Integer.BYTES, GL_DYNAMIC_DRAW);
			bufferTexels[set] = texels;
			clusters = columns = true;
		}
		// the columns follow the clusters: both halves in one call
		if (clusters || columns)
		{
			int from = clusters ? 0 : CLUSTER_COUNT * words;
			sendMasks(from, (columns ? CLUSTER_ENTRIES : CLUSTER_COUNT) * words - from);
		}
		clustersSent[set] = clustersBuilt;
		columnsSent[set] = columnsBuilt;
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
	}

	private void sendMasks(int from, int words)
	{
		maskData.clear();
		maskData.put(masks, from, words);
		maskData.flip();
		glBufferSubData(GL_TEXTURE_BUFFER, (long) from * Integer.BYTES, maskData);
	}

	/**
	 * What the light buffer says of a light besides its colour (the fourth number of its second texel, rooms.glsl): the
	 * storey it lights, plus one, 0 when unknown (every storey); negative when it is outdoors.
	 */
	static float storeyCode(int plane, boolean indoor)
	{
		float storey = plane >= 0 && plane < WallMap.PLANES ? plane + 1 : 0;
		return indoor ? storey : -storey;
	}

	private static FloatBuffer staging(FloatBuffer b, int floats)
	{
		if (b == null || b.capacity() < floats)
		{
			b = BufferUtils.createFloatBuffer(floats);
		}
		b.clear();
		return b;
	}

	/**
	 * The room map's header as of the last upload, rooms.glsl's roomHeader: its version (0 no map, 3 with the light
	 * map), size and offset.
	 */
	float[] getRoomHeader()
	{
		return roomHeader;
	}

	void bind(int lightUnit, int clusterUnit)
	{
		glActiveTexture(GL_TEXTURE0 + lightUnit);
		glBindTexture(GL_TEXTURE_BUFFER, lightTextures[set]);
		glActiveTexture(GL_TEXTURE0 + clusterUnit);
		glBindTexture(GL_TEXTURE_BUFFER, clusterTextures[set]);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int lightUnit, int clusterUnit)
	{
		glActiveTexture(GL_TEXTURE0 + lightUnit);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glActiveTexture(GL_TEXTURE0 + clusterUnit);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Unproject the cluster grid into camera-relative world space. The projection maps view depth z to
	 * ndc depth 2 * near / z in both clip conventions, so ndc depth 1 is view depth 2 * near.
	 */
	private void computeBounds(float[] viewProj)
	{
		float[] inv = Mat4.inverse(viewProj);
		float refDepth = 2 * NEAR;
		for (int gy = 0; gy <= CLUSTERS_Y; ++gy)
		{
			for (int gx = 0; gx <= CLUSTERS_X; ++gx)
			{
				float nx = gx * 2f / CLUSTERS_X - 1;
				float ny = gy * 2f / CLUSTERS_Y - 1;
				float px = inv[0] * nx + inv[4] * ny + inv[8] + inv[12];
				float py = inv[1] * nx + inv[5] * ny + inv[9] + inv[13];
				float pz = inv[2] * nx + inv[6] * ny + inv[10] + inv[14];
				float pw = inv[3] * nx + inv[7] * ny + inv[11] + inv[15];
				int i = (gy * (CLUSTERS_X + 1) + gx) * 3;
				float s = 1 / (pw * refDepth);
				rays[i] = px * s;
				rays[i + 1] = py * s;
				rays[i + 2] = pz * s;
			}
		}
		// Side planes shared by every light. Conservative sphere tests avoid the very
		// loose projected boxes near the camera without rejecting any contributing ray.
		for (int cy = 0; cy < CLUSTERS_Y; cy++)
		{
			for (int cx = 0; cx < CLUSTERS_X; cx++)
			{
				int origin = (cy * (CLUSTERS_X + 1) + cx) * 3;
				int opposite = origin + (CLUSTERS_X + 2) * 3;
				for (int edge = 0; edge < 4; edge++)
				{
					int next = (edge + 1) & 3;
					int a = origin + ((edge == 1 || edge == 2 ? 1 : 0) + (edge >= 2 ? CLUSTERS_X + 1 : 0)) * 3;
					int b = origin + ((next == 1 || next == 2 ? 1 : 0) + (next >= 2 ? CLUSTERS_X + 1 : 0)) * 3;
					float nx = rays[a + 1] * rays[b + 2] - rays[a + 2] * rays[b + 1];
					float ny = rays[a + 2] * rays[b] - rays[a] * rays[b + 2];
					float nz = rays[a] * rays[b + 1] - rays[a + 1] * rays[b];
					float facing = nx * (rays[origin] + rays[opposite]) + ny * (rays[origin + 1] + rays[opposite + 1])
						+ nz * (rays[origin + 2] + rays[opposite + 2]);
					int plane = ((cy * CLUSTERS_X + cx) * 4 + edge) * 4;
					float sign = facing < 0 ? -1 : 1;
					columnPlanes[plane] = nx * sign;
					columnPlanes[plane + 1] = ny * sign;
					columnPlanes[plane + 2] = nz * sign;
					columnPlanes[plane + 3] = nx * nx + ny * ny + nz * nz;
				}
			}
		}
		for (int k = 0; k <= CLUSTERS_Z; ++k)
		{
			sliceDepth[k] = (float) (NEAR * Math.pow(FAR / NEAR, (double) k / CLUSTERS_Z));
		}
		sliceDepth[0] = 0;

		// A cluster is its column's four corner rays between two slice depths, both positive, so per axis its box is
		// the rays' smallest and largest component times the near or the far depth, whichever keeps it extreme
		for (int cy = 0; cy < CLUSTERS_Y; ++cy)
		{
			for (int cx = 0; cx < CLUSTERS_X; ++cx)
			{
				int r = (cy * CLUSTERS_X + cx) * 6;
				for (int axis = 0; axis < 3; ++axis)
				{
					float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
					for (int c = 0; c < 4; ++c)
					{
						float v = rays[((cy + (c >> 1)) * (CLUSTERS_X + 1) + cx + (c & 1)) * 3 + axis];
						lo = Math.min(lo, v);
						hi = Math.max(hi, v);
					}
					columnRays[r + axis] = lo;
					columnRays[r + 3 + axis] = hi;
				}
			}
		}
		for (int cz = 0; cz < CLUSTERS_Z; ++cz)
		{
			float d0 = sliceDepth[cz], d1 = sliceDepth[cz + 1];
			for (int column = 0; column < COLUMN_COUNT; ++column)
			{
				int r = column * 6;
				int b = (column * CLUSTERS_Z + cz) * 6;
				for (int axis = 0; axis < 3; ++axis)
				{
					float lo = columnRays[r + axis], hi = columnRays[r + 3 + axis];
					bounds[b + axis] = lo * (lo >= 0 ? d0 : d1);
					bounds[b + 3 + axis] = hi * (hi >= 0 ? d1 : d0);
				}
			}
		}
	}

	/** Conservative sphere/frustum-side test (r2: the radius squared); shared by surface and fog light lists. */
	private boolean columnTouches(int column, float x, float y, float z, float r2)
	{
		int base = column * 16;
		for (int edge = 0; edge < 4; edge++)
		{
			int plane = base + edge * 4;
			float distance = columnPlanes[plane] * x + columnPlanes[plane + 1] * y + columnPlanes[plane + 2] * z;
			if (distance < 0 && distance * distance > r2 * columnPlanes[plane + 3] * 1.0001f)
				return false;
		}
		return true;
	}

	static int slice(float viewDepth)
	{
		if (viewDepth <= NEAR)
		{
			return 0;
		}
		int s = (int) Math.floor(Math.log(viewDepth / NEAR) / Math.log(FAR / NEAR) * CLUSTERS_Z);
		return clampi(s, 0, CLUSTERS_Z - 1);
	}

	/**
	 * Conservative NDC rectangle of a sphere: project the corners of its bounding box. If the box reaches
	 * behind the near plane the whole screen is used.
	 *
	 * @return false if the sphere is entirely off screen
	 */
	private static boolean screenBounds(float[] m, float x, float y, float z, float r, float[] out)
	{
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int c = 0; c < 8; ++c)
		{
			float px = x + ((c & 1) == 0 ? -r : r);
			float py = y + ((c & 2) == 0 ? -r : r);
			float pz = z + ((c & 4) == 0 ? -r : r);
			float cw = m[3] * px + m[7] * py + m[11] * pz + m[15];
			if (cw < NEAR)
			{
				out[0] = -1;
				out[1] = -1;
				out[2] = 1;
				out[3] = 1;
				return true;
			}
			float cx = (m[0] * px + m[4] * py + m[8] * pz + m[12]) / cw;
			float cy = (m[1] * px + m[5] * py + m[9] * pz + m[13]) / cw;
			minX = Math.min(minX, cx);
			maxX = Math.max(maxX, cx);
			minY = Math.min(minY, cy);
			maxY = Math.max(maxY, cy);
		}
		if (maxX < -1 || minX > 1 || maxY < -1 || minY > 1)
		{
			return false;
		}
		out[0] = minX;
		out[1] = minY;
		out[2] = maxX;
		out[3] = maxY;
		return true;
	}

	private static int clampi(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}
}
