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
	static final int CLUSTERS_X = 16;
	static final int CLUSTERS_Y = 9;
	static final int CLUSTERS_Z = 24;
	static final int MAX_LIGHTS_PER_CLUSTER = 31;
	static final int MAX_LIGHTS = 1024;
	static final float NEAR = 50;
	static final float FAR = 25600;

	private static final int CLUSTER_COUNT = CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z;
	// per cluster: count, then up to MAX_LIGHTS_PER_CLUSTER indices
	private static final int CLUSTER_STRIDE = MAX_LIGHTS_PER_CLUSTER + 1;

	private int lightBuffer, lightTexture;
	private int clusterBuffer, clusterTexture;

	private final FloatBuffer lightData = BufferUtils.createFloatBuffer(MAX_LIGHTS * 8);
	private final short[] clusters = new short[CLUSTER_COUNT * CLUSTER_STRIDE];
	private final ShortBuffer clusterData = BufferUtils.createShortBuffer(CLUSTER_COUNT * CLUSTER_STRIDE);

	private int lightCount;
	private int overflows;

	// camera-relative world space bounds of every cluster: minX, minY, minZ, maxX, maxY, maxZ
	private final float[] bounds = new float[CLUSTER_COUNT * 6];
	// view rays through the cluster grid corners, scaled to view depth 1
	private final float[] rays = new float[(CLUSTERS_X + 1) * (CLUSTERS_Y + 1) * 3];
	private final float[] sliceDepth = new float[CLUSTERS_Z + 1];

	void init()
	{
		lightBuffer = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, lightBuffer);
		glBufferData(GL_TEXTURE_BUFFER, (long) MAX_LIGHTS * 8 * Float.BYTES, GL_STREAM_DRAW);
		lightTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, lightTexture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, lightBuffer);

		clusterBuffer = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffer);
		glBufferData(GL_TEXTURE_BUFFER, (long) CLUSTER_COUNT * CLUSTER_STRIDE * Short.BYTES, GL_STREAM_DRAW);
		clusterTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, clusterTexture);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_R16UI, clusterBuffer);

		glBindTexture(GL_TEXTURE_BUFFER, 0);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
	}

	void free()
	{
		if (lightBuffer != 0)
		{
			glDeleteTextures(new int[]{lightTexture, clusterTexture});
			glDeleteBuffers(new int[]{lightBuffer, clusterBuffer});
			lightBuffer = lightTexture = clusterBuffer = clusterTexture = 0;
		}
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
		overflows = 0;
		computeBounds(viewProj);

		int n = Math.min(lights.size(), MAX_LIGHTS);
		int kept = 0;
		float[] tile = new float[4];
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
			lightData.put(x).put(y).put(z).put(l.radius);
			lightData.put(l.r * l.intensity).put(l.g * l.intensity).put(l.b * l.intensity).put(l.indoor ? 1 : 0);

			for (int cz = z0; cz <= z1; ++cz)
			{
				for (int cy = y0; cy <= y1; ++cy)
				{
					for (int cx = x0; cx <= x1; ++cx)
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
	}

	short[] clusterTable()
	{
		return clusters;
	}

	private void upload()
	{
		glBindBuffer(GL_TEXTURE_BUFFER, lightBuffer);
		glBufferData(GL_TEXTURE_BUFFER, (long) MAX_LIGHTS * 8 * Float.BYTES, GL_STREAM_DRAW);
		if (lightCount > 0)
		{
			glBufferSubData(GL_TEXTURE_BUFFER, 0, lightData);
		}

		clusterData.clear();
		clusterData.put(clusters);
		clusterData.flip();
		glBindBuffer(GL_TEXTURE_BUFFER, clusterBuffer);
		glBufferData(GL_TEXTURE_BUFFER, clusterData, GL_STREAM_DRAW);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
	}

	void bind(int lightUnit, int clusterUnit)
	{
		glActiveTexture(GL_TEXTURE0 + lightUnit);
		glBindTexture(GL_TEXTURE_BUFFER, lightTexture);
		glActiveTexture(GL_TEXTURE0 + clusterUnit);
		glBindTexture(GL_TEXTURE_BUFFER, clusterTexture);
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
