package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
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
 * Both tables go to the GPU as buffer textures.
 */
class LightClusters
{
	static final int CLUSTERS_X = 32;
	static final int CLUSTERS_Y = 18;
	static final int CLUSTERS_Z = 24;
	static final int MAX_LIGHTS_PER_CLUSTER = 31;
	static final int MAX_LIGHTS = 1024;
	static final float NEAR = 50;
	static final float FAR = 25600;
	// a third texel per light after the two per light: its occlusion grid and whether it is covered
	// (LightOcclusion.texel, light_occlusion.glsl)
	static final int LIGHT_EXTRA_BASE = MAX_LIGHTS * 2;
	// the room map (RoomMap.texels) follows, from this texel on (rooms.glsl)
	static final int ROOM_BASE = MAX_LIGHTS * 3;

	private static final int CLUSTER_COUNT = CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z;
	// Fog columns, stored after the clusters: per screen tile, the lights that glow in the fog anywhere along its
	// rays (fog_frag.glsl integrates each once per pixel instead of looking up clusters at every step). Indoor
	// lights are left out unless their room has no roof right now (OpenRooms), and lights outside the fog. When a column is full, the brightest lights win, in the same
	// order for every column, so neighbouring columns keep the same lights instead of showing the tile grid.
	private static final int COLUMN_COUNT = CLUSTERS_X * CLUSTERS_Y;
	// per cluster: count, then up to MAX_LIGHTS_PER_CLUSTER indices
	private static final int CLUSTER_STRIDE = MAX_LIGHTS_PER_CLUSTER + 1;
	private static final int TABLE_SIZE = (CLUSTER_COUNT + COLUMN_COUNT) * CLUSTER_STRIDE;

	private int lightBuffer, lightTexture;
	private int clusterBuffer, clusterTexture;
	// The light buffer: the lights and the room map after them. The map is sent when it changes; each frame only the
	// lights and the map's header are.
	private RoomMap roomMap, uploadedRooms;
	private boolean roomLights;
	private int uploadedVersion = -1;
	private int maxTexels;
	private FloatBuffer roomStaging;
	private final FloatBuffer roomOff = BufferUtils.createFloatBuffer(4);
	// the static lights' occlusion grids (LightOcclusion), in a texture of their own: one filtered fetch a lookup
	private LightOcclusion.Atlas atlas, uploadedAtlas;
	private boolean roofLights;
	private int visTexture, visWidth = 1, visHeight = 1;
	private ByteBuffer visStaging;
	private final FloatBuffer extraData = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
	// the view the cluster bounds were computed for: they only change with the camera
	private final float[] boundsViewProj = new float[16];
	private boolean boundsValid;

	private final FloatBuffer lightData = BufferUtils.createFloatBuffer(MAX_LIGHTS * 8);
	private final short[] clusters = new short[TABLE_SIZE];
	private final ShortBuffer clusterData = BufferUtils.createShortBuffer(TABLE_SIZE);

	// fog: camera-relative xz of the fog's center and its radius; columns are only filled while enabled
	private boolean fogColumns;
	// rooms whose roof is hidden: their lights glow in the fog (null: none)
	private OpenRooms openRooms;
	private float fogX, fogZ, fogRadius;
	// candidates for the fog columns this frame: light index, tile rectangle, packed for sorting by brightness
	private long[] fogOrder = new long[64];
	private int[] fogRect = new int[64 * 4];
	private int fogCandidates;
	private int[] fogLight = new int[64];
	private final float[] tile = new float[4];

	private int lightCount;
	private int overflows;

	// camera-relative world space bounds of every cluster: minX, minY, minZ, maxX, maxY, maxZ
	private final float[] bounds = new float[CLUSTER_COUNT * 6];
	// view rays through the cluster grid corners, scaled to view depth 1
	private final float[] rays = new float[(CLUSTERS_X + 1) * (CLUSTERS_Y + 1) * 3];
	private final float[] columnPlanes = new float[COLUMN_COUNT * 16];
	private final float[] sliceDepth = new float[CLUSTERS_Z + 1];

	void init()
	{
		maxTexels = glGetInteger(GL_MAX_TEXTURE_BUFFER_SIZE);
		lightBuffer = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, lightBuffer);
		glBufferData(GL_TEXTURE_BUFFER, (long) (ROOM_BASE + RoomMap.HEADER_TEXELS) * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
		lightTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, lightTexture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, lightBuffer);

		clusterBuffer = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffer);
		glBufferData(GL_TEXTURE_BUFFER, (long) TABLE_SIZE * Short.BYTES, GL_STREAM_DRAW);
		clusterTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, clusterTexture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_R16UI, clusterBuffer);

		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);

		visTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, visTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		uploadAtlas(null);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	void free()
	{
		if (lightBuffer != 0)
		{
			glDeleteTextures(new int[]{lightTexture, clusterTexture, visTexture});
			glDeleteBuffers(new int[]{lightBuffer, clusterBuffer});
			lightBuffer = lightTexture = clusterBuffer = clusterTexture = visTexture = 0;
		}
		uploadedRooms = null;
		uploadedAtlas = null;
		uploadedVersion = -1;
		boundsValid = false;
	}

	boolean isInitialized()
	{
		return lightBuffer != 0;
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
	 * Whether to fill the fog columns, and the fog's extent: camera-relative center (xz) and radius, world units.
	 */
	void setFog(boolean enabled, float centerX, float centerZ, float radius)
	{
		fogColumns = enabled;
		fogX = centerX;
		fogZ = centerZ;
		fogRadius = radius;
	}

	/**
	 * The rooms without a roof this frame, whose lights light the fog above their walls (null: none).
	 */
	void setOpenRooms(OpenRooms open)
	{
		openRooms = open != null && open.count() > 0 ? open : null;
	}

	/**
	 * The rooms lights are kept in (null: lights reach everywhere, as without rooms).
	 */
	void setRooms(RoomMap rooms, boolean lightsUseRooms)
	{
		roomMap = rooms;
		roomLights = lightsUseRooms;
	}

	/**
	 * The static lights' occlusion grids (null: none), and whether lights under a roof leave what is open to the sky
	 * above them dark.
	 */
	void setOcclusion(LightOcclusion.Atlas atlas, boolean roofLights)
	{
		this.atlas = atlas;
		this.roofLights = roofLights;
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
		// only the counts need clearing; indices past a count are never read
		for (int i = 0; i < clusters.length; i += CLUSTER_STRIDE)
		{
			clusters[i] = 0;
		}
		lightData.clear();
		extraData.clear();
		overflows = 0;
		fogCandidates = 0;
		if (!boundsValid || !java.util.Arrays.equals(viewProj, boundsViewProj))
		{
			computeBounds(viewProj);
			System.arraycopy(viewProj, 0, boundsViewProj, 0, 16);
			boundsValid = true;
		}
		RoomMap rooms = roomMap;
		LightOcclusion.Atlas grids = atlas;

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

			int x0 = clampi((int) Math.floor((tile[0] * 0.5f + 0.5f) * CLUSTERS_X), 0, CLUSTERS_X - 1);
			int x1 = clampi((int) Math.floor((tile[2] * 0.5f + 0.5f) * CLUSTERS_X), 0, CLUSTERS_X - 1);
			int y0 = clampi((int) Math.floor((tile[1] * 0.5f + 0.5f) * CLUSTERS_Y), 0, CLUSTERS_Y - 1);
			int y1 = clampi((int) Math.floor((tile[3] * 0.5f + 0.5f) * CLUSTERS_Y), 0, CLUSTERS_Y - 1);
			int z0 = slice(w - l.radius);
			int z1 = slice(w + l.radius);

			int index = kept++;
			float code = rooms != null && roomLights ? rooms.lightCode(l.x, l.y, l.z, l.plane) : l.roomCode;
			// a light only in building rooms counts as indoors even when it isn't a building light
			boolean indoor = l.indoor || RoomMap.indoor(code);
			if (l.indoor && !RoomMap.indoor(code))
			{
				// building lights stay out of the fog and the weather whatever their tile says
				code = Math.abs(code);
			}
			lightData.put(x).put(y).put(z).put(l.radius);
			lightData.put(l.r * l.intensity).put(l.g * l.intensity).put(l.b * l.intensity).put(code);
			LightOcclusion.texel(l.occlusion, grids,
				roofLights && rooms != null && rooms.covered(l.x, l.z, l.plane), l.plane, l.radius, extraData);

			if (fogColumns && (!indoor || openRooms != null && openRooms.isOpen(RoomMap.roomOf(code))))
			{
				float fx = x - fogX, fz = z - fogZ, reach = fogRadius + l.radius;
				if (fx * fx + fz * fz < reach * reach)
				{
					addFogCandidate(index, x0, y0, x1, y1, (l.r + l.g + l.b) * l.intensity * l.radius);
				}
			}

			for (int cy = y0; cy <= y1; ++cy)
			{
				for (int cx = x0; cx <= x1; ++cx)
				{
					if (!columnTouches(cx, cy, x, y, z, l.radius)) continue;
					for (int cz = z0; cz <= z1; ++cz)
					{
						int cluster = (cz * CLUSTERS_Y + cy) * CLUSTERS_X + cx;
						if (!sphereTouches(cluster, x, y, z, l.radius))
						{
							continue;
						}
						int base = cluster * CLUSTER_STRIDE;
						int count = clusters[base];
						if (count == MAX_LIGHTS_PER_CLUSTER)
						{
							++overflows;
							continue;
						}
						clusters[base + 1 + count] = (short) index;
						clusters[base] = (short) (count + 1);
					}
				}
			}
		}
		lightCount = kept;
		lightData.flip();
		extraData.flip();
		fillFogColumns();
	}

	private void addFogCandidate(int index, int x0, int y0, int x1, int y1, float brightness)
	{
		int c = fogCandidates++;
		if (c == fogOrder.length)
		{
			fogOrder = java.util.Arrays.copyOf(fogOrder, c * 2);
			fogRect = java.util.Arrays.copyOf(fogRect, c * 8);
			fogLight = java.util.Arrays.copyOf(fogLight, c * 2);
		}
		// brightest first; ties by index so the order is stable. Positive floats sort like their bits.
		long key = (long) Float.floatToIntBits(Math.max(brightness, 0)) << 32 | (0xFFFFFFFFL - c);
		fogOrder[c] = key;
		fogRect[c * 4] = x0;
		fogRect[c * 4 + 1] = y0;
		fogRect[c * 4 + 2] = x1;
		fogRect[c * 4 + 3] = y1;
		fogLight[c] = index;
	}

	private void fillFogColumns()
	{
		int columns = CLUSTER_COUNT * CLUSTER_STRIDE;
		for (int i = columns; i < TABLE_SIZE; i += CLUSTER_STRIDE)
		{
			clusters[i] = 0;
		}
		if (fogCandidates == 0)
		{
			return;
		}
		java.util.Arrays.sort(fogOrder, 0, fogCandidates);
		for (int k = fogCandidates - 1; k >= 0; --k)
		{
			int c = (int) (0xFFFFFFFFL - (fogOrder[k] & 0xFFFFFFFFL));
			int index = fogLight[c];
			float lx = lightData.get(index * 8), ly = lightData.get(index * 8 + 1);
			float lz = lightData.get(index * 8 + 2), radius = lightData.get(index * 8 + 3);
			for (int cy = fogRect[c * 4 + 1]; cy <= fogRect[c * 4 + 3]; ++cy)
			{
				for (int cx = fogRect[c * 4]; cx <= fogRect[c * 4 + 2]; ++cx)
				{
					if (!columnTouches(cx, cy, lx, ly, lz, radius)) continue;
					int base = columns + (cy * CLUSTERS_X + cx) * CLUSTER_STRIDE;
					int count = clusters[base];
					if (count < MAX_LIGHTS_PER_CLUSTER)
					{
						clusters[base + 1 + count] = (short) index;
						clusters[base] = (short) (count + 1);
					}
				}
			}
		}
	}

	short[] clusterTable()
	{
		return clusters;
	}

	void upload()
	{
		RoomMap rooms = roomMap;
		if (rooms != null && ROOM_BASE + rooms.texelCount() > maxTexels)
		{
			// the driver's buffer textures are too small for the map (GL 3.3 only promises 65536 texels)
			rooms = null;
		}
		int texels = rooms != null ? rooms.texelCount() : RoomMap.HEADER_TEXELS;
		LightOcclusion.Atlas grids = atlas;
		if (grids != uploadedAtlas)
		{
			glBindTexture(GL_TEXTURE_2D, visTexture);
			uploadAtlas(grids);
			glBindTexture(GL_TEXTURE_2D, 0);
			uploadedAtlas = grids;
		}
		glBindBuffer(GL_TEXTURE_BUFFER, lightBuffer);
		if (rooms != uploadedRooms || rooms != null && rooms.getVersion() != uploadedVersion)
		{
			// the map changed: lay the buffer out again and send it
			glBufferData(GL_TEXTURE_BUFFER, (long) (ROOM_BASE + texels) * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
			if (rooms != null)
			{
				roomStaging = staging(roomStaging, texels * 4);
				rooms.texels(roomStaging);
				roomStaging.flip();
				glBufferSubData(GL_TEXTURE_BUFFER, (long) ROOM_BASE * 4 * Float.BYTES, roomStaging);
			}
			uploadedRooms = rooms;
			uploadedVersion = rooms != null ? rooms.getVersion() : -1;
		}
		if (lightCount > 0)
		{
			glBufferSubData(GL_TEXTURE_BUFFER, 0, lightData);
			glBufferSubData(GL_TEXTURE_BUFFER, (long) LIGHT_EXTRA_BASE * 4 * Float.BYTES, extraData);
		}
		// the map's header: whether there is a map, and whether the lights keep to their rooms or only the fog uses it
		roomOff.clear();
		if (rooms != null)
		{
			roomOff.put(2).put(rooms.size).put(rooms.offset).put(roomLights ? 1 : 0);
		}
		else
		{
			roomOff.put(0).put(0).put(0).put(0);
		}
		roomOff.flip();
		glBufferSubData(GL_TEXTURE_BUFFER, (long) ROOM_BASE * 4 * Float.BYTES, roomOff);

		clusterData.clear();
		clusterData.put(clusters);
		clusterData.flip();
		glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffer);
		glBufferData(GL_TEXTURE_BUFFER, clusterData, GL_STREAM_DRAW);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
	}

	/** Send the occlusion grids' atlas into the bound 2D texture; a single fully visible texel when there is none. */
	private void uploadAtlas(LightOcclusion.Atlas grids)
	{
		int size = grids != null ? grids.texels.length : 1;
		if (visStaging == null || visStaging.capacity() < size)
		{
			visStaging = BufferUtils.createByteBuffer(size);
		}
		visStaging.clear();
		if (grids != null)
		{
			visStaging.put(grids.texels);
		}
		else
		{
			visStaging.put((byte) 255);
		}
		visStaging.flip();
		visWidth = grids != null ? grids.width : 1;
		visHeight = grids != null ? grids.height : 1;
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, visWidth, visHeight, 0, GL_RED, GL_UNSIGNED_BYTE, visStaging);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
	}

	/** The occlusion atlas' size in texels, for the composite's lookup (lightVisSize). */
	int getAtlasWidth()
	{
		return visWidth;
	}

	int getAtlasHeight()
	{
		return visHeight;
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

	void bind(int lightUnit, int clusterUnit, int visUnit)
	{
		glActiveTexture(GL_TEXTURE0 + lightUnit);
		glBindTexture(GL_TEXTURE_BUFFER, lightTexture);
		glActiveTexture(GL_TEXTURE0 + clusterUnit);
		glBindTexture(GL_TEXTURE_BUFFER, clusterTexture);
		glActiveTexture(GL_TEXTURE0 + visUnit);
		glBindTexture(GL_TEXTURE_2D, visTexture);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int lightUnit, int clusterUnit, int visUnit)
	{
		glActiveTexture(GL_TEXTURE0 + lightUnit);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glActiveTexture(GL_TEXTURE0 + clusterUnit);
		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glActiveTexture(GL_TEXTURE0 + visUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
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

		for (int cz = 0; cz < CLUSTERS_Z; ++cz)
		{
			float d0 = sliceDepth[cz], d1 = sliceDepth[cz + 1];
			for (int cy = 0; cy < CLUSTERS_Y; ++cy)
			{
				for (int cx = 0; cx < CLUSTERS_X; ++cx)
				{
					float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
					float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
					for (int c = 0; c < 4; ++c)
					{
						int i = ((cy + (c >> 1)) * (CLUSTERS_X + 1) + cx + (c & 1)) * 3;
						for (int e = 0; e < 2; ++e)
						{
							float d = e == 0 ? d0 : d1;
							float px = rays[i] * d, py = rays[i + 1] * d, pz = rays[i + 2] * d;
							minX = Math.min(minX, px);
							minY = Math.min(minY, py);
							minZ = Math.min(minZ, pz);
							maxX = Math.max(maxX, px);
							maxY = Math.max(maxY, py);
							maxZ = Math.max(maxZ, pz);
						}
					}
					int b = ((cz * CLUSTERS_Y + cy) * CLUSTERS_X + cx) * 6;
					bounds[b] = minX;
					bounds[b + 1] = minY;
					bounds[b + 2] = minZ;
					bounds[b + 3] = maxX;
					bounds[b + 4] = maxY;
					bounds[b + 5] = maxZ;
				}
			}
		}
	}

	/** Conservative sphere/frustum-side test; shared by surface, fog and particle light lists. */
	private boolean columnTouches(int cx, int cy, float x, float y, float z, float radius)
	{
		int base = (cy * CLUSTERS_X + cx) * 16;
		for (int edge = 0; edge < 4; edge++)
		{
			int plane = base + edge * 4;
			float distance = columnPlanes[plane] * x + columnPlanes[plane + 1] * y + columnPlanes[plane + 2] * z;
			if (distance < 0 && distance * distance > radius * radius * columnPlanes[plane + 3] * 1.0001f)
				return false;
		}
		return true;
	}

	private boolean sphereTouches(int cluster, float x, float y, float z, float r)
	{
		int b = cluster * 6;
		float dx = Math.max(Math.max(bounds[b] - x, 0), x - bounds[b + 3]);
		float dy = Math.max(Math.max(bounds[b + 1] - y, 0), y - bounds[b + 4]);
		float dz = Math.max(Math.max(bounds[b + 2] - z, 0), z - bounds[b + 5]);
		return dx * dx + dy * dy + dz * dz <= r * r;
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
