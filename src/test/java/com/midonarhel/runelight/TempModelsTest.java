package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import org.junit.Test;

public class TempModelsTest
{
	private static final int FACE_SIZE = (VAO.VERT_SIZE >> 2) * 3;

	/** Perspective onto a camera looking along +z from 4000 units back. */
	private static final Projection PROJECTION = new Projection()
	{
		@Override
		public float[] project(float x, float y, float z)
		{
			return project(x, y, z, new float[3]);
		}

		@Override
		public float[] project(float x, float y, float z, float[] out)
		{
			out[0] = x * 512;
			out[1] = y * 512;
			out[2] = z + 4000;
			return out;
		}
	};

	/** A lumpy sphere with flat, hidden, textured and transparent faces, mixed priorities and an HSL override. */
	static Model sphere(long seed, int stacks, int slices, boolean transparent)
	{
		Random r = new Random(seed);
		int vc = (stacks + 1) * slices;
		float[] vx = new float[vc + 7], vy = new float[vc + 7], vz = new float[vc + 7]; // client arrays run long
		float maxR = 0;
		for (int i = 0; i <= stacks; ++i)
		{
			double phi = Math.PI * i / stacks;
			for (int j = 0; j < slices; ++j)
			{
				double theta = 2 * Math.PI * j / slices;
				float rad = 200 + r.nextInt(40);
				int v = i * slices + j;
				vx[v] = (float) (rad * Math.sin(phi) * Math.cos(theta));
				vy[v] = (float) (rad * Math.cos(phi));
				vz[v] = (float) (rad * Math.sin(phi) * Math.sin(theta));
				maxR = Math.max(maxR, rad);
			}
		}
		int fc = stacks * slices * 2;
		int[] i1 = new int[fc], i2 = new int[fc], i3 = new int[fc];
		int[] c1 = new int[fc], c2 = new int[fc], c3 = new int[fc];
		short[] unlit = new short[fc], tex = new short[fc];
		byte[] pri = new byte[fc], tr = new byte[fc], bias = new byte[fc], tf = new byte[fc];
		int f = 0;
		for (int i = 0; i < stacks; ++i)
		{
			for (int j = 0; j < slices; ++j)
			{
				int a = i * slices + j, b = i * slices + (j + 1) % slices;
				int c = a + slices, d = b + slices;
				int[][] tris = {{a, c, b}, {b, c, d}};
				for (int[] t : tris)
				{
					i1[f] = t[0];
					i2[f] = t[1];
					i3[f] = t[2];
					c1[f] = r.nextInt(65536);
					c2[f] = r.nextInt(65536);
					int kind = r.nextInt(20);
					c3[f] = kind == 0 ? -2 : kind < 6 ? -1 : r.nextInt(65536);
					unlit[f] = (short) r.nextInt(65536);
					tex[f] = (short) (r.nextInt(8) == 0 ? r.nextInt(6) : -1);
					if (tex[f] != -1 && r.nextInt(4) == 0)
					{
						c1[f] = -1 - r.nextInt(1000); // raw colors pass through on textured faces
					}
					tf[f] = (byte) (tex[f] != -1 && r.nextBoolean() ? r.nextInt(4) : -1);
					pri[f] = (byte) r.nextInt(12);
					tr[f] = (byte) (transparent && r.nextInt(3) == 0 ? r.nextInt(256) : 0);
					bias[f] = (byte) r.nextInt(4);
					++f;
				}
			}
		}
		int[] t1 = new int[4], t2 = new int[4], t3 = new int[4];
		for (int t = 0; t < 4; ++t)
		{
			t1[t] = r.nextInt(vc);
			t2[t] = r.nextInt(vc);
			t3[t] = r.nextInt(vc);
		}
		int radius = (int) Math.ceil(maxR) + 1;

		Map<String, Object> values = new HashMap<>();
		values.put("getVerticesCount", vc);
		values.put("getVerticesX", vx);
		values.put("getVerticesY", vy);
		values.put("getVerticesZ", vz);
		values.put("getFaceCount", fc);
		values.put("getFaceIndices1", i1);
		values.put("getFaceIndices2", i2);
		values.put("getFaceIndices3", i3);
		values.put("getFaceColors1", c1);
		values.put("getFaceColors2", c2);
		values.put("getFaceColors3", c3);
		values.put("getUnlitFaceColors", unlit);
		values.put("getFaceRenderPriorities", pri);
		values.put("getFaceTextures", tex);
		values.put("getFaceTransparencies", transparent ? tr : null);
		values.put("getFaceBias", bias);
		values.put("getTextureFaces", tf);
		values.put("getTexIndices1", t1);
		values.put("getTexIndices2", t2);
		values.put("getTexIndices3", t3);
		values.put("getTransparency", (byte) 0);
		values.put("getRadius", radius);
		values.put("getDiameter", radius * 2 + 1);
		values.put("getOverrideAmount", (byte) 40);
		values.put("getOverrideHue", (byte) 12);
		values.put("getOverrideSaturation", (byte) -1);
		values.put("getOverrideLuminance", (byte) 60);
		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
			(p, m, a) -> values.get(m.getName()));
	}

	/** What the client does to a model's arrays once the callback returns. */
	static void scramble(Model m, long seed)
	{
		Random r = new Random(seed);
		for (float[] a : new float[][]{m.getVerticesX(), m.getVerticesY(), m.getVerticesZ()})
		{
			for (int i = 0; i < a.length; ++i)
			{
				a[i] = r.nextFloat() * 100;
			}
		}
		for (int[] a : new int[][]{m.getFaceColors1(), m.getFaceColors2(), m.getFaceColors3()})
		{
			for (int i = 0; i < a.length; ++i)
			{
				a[i] = r.nextInt(65536);
			}
		}
		int[][] indices = {m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3()};
		for (int[] a : indices)
		{
			for (int i = 0; i < a.length; ++i)
			{
				a[i] = 0;
			}
		}
		if (m.getFaceTransparencies() != null)
		{
			Arrays.fill(m.getFaceTransparencies(), (byte) 0);
		}
		Arrays.fill(m.getFaceRenderPriorities(), (byte) 0);
	}

	private static int[][] uploadSorted(ModelUploader u, Model m, int orient, int x, boolean prioritySort)
	{
		int n = m.getFaceCount() * FACE_SIZE;
		IntBuffer o = IntBuffer.allocate(n), a = IntBuffer.allocate(n);
		u.uploadSortedModel(PROJECTION, m, orient, x, 0, 0, 100, -300, -4000, o, a, prioritySort, 0);
		return new int[][]{Arrays.copyOf(o.array(), o.position()), Arrays.copyOf(a.array(), a.position())};
	}

	private static int[] uploadTemp(ModelUploader u, Model m, int orient, int x)
	{
		IntBuffer o = IntBuffer.allocate(m.getFaceCount() * FACE_SIZE);
		u.uploadTempModel(m, orient, x, 0, 0, 100, -300, -4000, o, 0);
		return Arrays.copyOf(o.array(), o.position());
	}

	@Test
	public void copyUploadsLikeTheModelAfterTheClientReusesIt()
	{
		ModelUploader u = new ModelUploader();
		for (int seed = 0; seed < 4; ++seed)
		{
			Model m = sphere(seed, 20, 24, true);
			int[][] sorted = uploadSorted(u, m, 300 * seed, 0, true);
			int[] temp = uploadTemp(u, m, 300 * seed, 0);
			assertTrue(sorted[0].length > 0 && sorted[1].length > 0);

			ModelData d = new ModelData().copy(m);
			scramble(m, seed);

			IntBuffer o = IntBuffer.allocate(temp.length + 64 * FACE_SIZE), a = IntBuffer.allocate(o.capacity());
			u.uploadSortedModel(PROJECTION, d, 300 * seed, 0, 0, 0, 100, -300, -4000, o, a, true, 0);
			assertArrayEquals(sorted[0], Arrays.copyOf(o.array(), o.position()));
			assertArrayEquals(sorted[1], Arrays.copyOf(a.array(), a.position()));

			o.clear();
			u.uploadTempModel(d, 300 * seed, 0, 0, 0, 100, -300, -4000, o, 0);
			assertArrayEquals(temp, Arrays.copyOf(o.array(), o.position()));
		}
	}

	@Test
	public void alphaBoundCoversEveryAlphaFace()
	{
		ModelUploader u = new ModelUploader();
		for (int seed = 0; seed < 8; ++seed)
		{
			// without face transparency only the raw colors of textured faces go to the alpha buffer
			Model m = sphere(seed, 16, 16, seed % 2 == 0);
			ModelData d = new ModelData().wrap(m);
			for (int orient = 0; orient < 2048; orient += 256)
			{
				int[][] sorted = uploadSorted(u, m, orient, 0, false);
				int maxAlpha = ModelUploader.maxAlphaFaces(d);
				assertTrue(sorted[1].length <= maxAlpha * FACE_SIZE);
				assertTrue(sorted[0].length <= (m.getFaceCount() - maxAlpha) * FACE_SIZE);
			}
		}
	}

	/** Records what would go to the frame's buffers. */
	private static final class Recorder extends TempModels
	{
		final List<int[]> emitted = new ArrayList<>();

		@Override
		void emit(Job job)
		{
			emitted.add(Arrays.copyOf(job.out, job.opaqueLen));
			emitted.add(Arrays.copyOfRange(job.out, job.alphaStart, job.alphaStart + job.alphaLen));
		}
	}

	private static void packInOrder(int threads)
	{
		RuneLightPlugin.SceneContext ctx = new RuneLightPlugin.SceneContext(1, 1);
		ctx.cameraX = 100;
		ctx.cameraY = -300;
		ctx.cameraZ = -4000;
		ModelUploader reference = new ModelUploader();
		List<int[]> expected = new ArrayList<>();
		Recorder rec = new Recorder();
		rec.setThreads(threads);
		try
		{
			// more than fit in flight, so the oldest are finished while newer ones are added
			int n = TempModels.MAX_JOBS * 3 + 5;
			for (int i = 0; i < n; ++i)
			{
				boolean sorted = i % 3 != 0;
				Model m = sphere(i, 8 + i % 13, 10 + i % 7, i % 3 == 1);
				int orient = (i * 97) & 2047;
				if (sorted)
				{
					boolean noDepth = i % 5 == 1;
					int[][] out = uploadSorted(reference, m, orient, i, noDepth);
					expected.add(out[0]);
					expected.add(out[1]);
					rec.uploadSorted(null, ctx, null, PROJECTION, m, orient, i, 0, 0, 0, noDepth, 0, 0, 0, 0);
				}
				else
				{
					expected.add(uploadTemp(reference, m, orient, i));
					expected.add(new int[0]);
					rec.uploadTemp(null, ctx, null, m, orient, i, 0, 0, 0);
				}
				scramble(m, i);
			}
			rec.flush();
		}
		finally
		{
			rec.free();
		}
		assertEquals(expected.size(), rec.emitted.size());
		for (int i = 0; i < expected.size(); ++i)
		{
			assertArrayEquals("job " + i / 2, expected.get(i), rec.emitted.get(i));
		}
	}

	@Test
	public void workersPackEveryModelInCallOrder()
	{
		for (int run = 0; run < 5; ++run)
		{
			packInOrder(3);
		}
	}

	@Test
	public void withoutWorkersEachModelIsUploadedInItsCallback()
	{
		packInOrder(0);
	}
}
