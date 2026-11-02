package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.harness.RenderDump;
import java.nio.IntBuffer;
import java.util.Arrays;
import net.runelite.api.FloatProjection;

/**
 * The CPU the dynamic and temporary models of a dump's frame cost to upload (ModelUploader), alone: every DYNAMIC and
 * TEMP draw of the frame tape, with its recorded model, placement and projection, into scratch buffers, frame after
 * frame on one thread. Prints the median per frame and the faces drawn. ./gradlew uploadBench -Pargs="nightref 400"
 */
public final class UploadBench
{
	public static void main(String[] args) throws Exception
	{
		SceneDump.Loaded dump = RenderDump.load(args[0]);
		int frames = args.length > 1 ? Integer.parseInt(args[1]) : 400;
		FrameTape tape = dump.tape;
		ModelUploader uploader = new ModelUploader();
		IntBuffer opaque = IntBuffer.allocate(8 << 20), alpha = IntBuffer.allocate(8 << 20);
		float[] camera = new float[3];
		long faces = 0;
		double[] ms = new double[frames];
		for (int f = -20; f < frames; ++f)
		{
			long t0 = System.nanoTime();
			long n = 0;
			for (FrameTape.Event e : tape.events)
			{
				int[] a = e.ints;
				if (e.type == FrameTape.PRE)
				{
					camera[0] = e.floats[0];
					camera[1] = e.floats[1];
					camera[2] = e.floats[2];
					continue;
				}
				if (e.type != FrameTape.DYNAMIC && e.type != FrameTape.TEMP)
				{
					continue;
				}
				ModelData m = tape.models.get(e.model);
				int orient, x, y, z, material, shading;
				if (e.type == FrameTape.DYNAMIC)
				{
					material = a[1];
					shading = a[2];
					orient = a[4];
					x = a[5];
					y = a[6];
					z = a[7];
				}
				else
				{
					orient = a[0];
					x = a[1];
					y = a[2];
					z = a[3];
					material = a[4];
					shading = a[5];
				}
				opaque.clear();
				alpha.clear();
				int cx = (int) camera[0], cy = (int) camera[1], cz = (int) camera[2];
				if (m.transparencies != null || e.type == FrameTape.TEMP && a[6] != 0)
				{
					uploader.uploadSortedModel(projection(tape, e.projection), m, orient, x, y, z, cx, cy, cz, opaque, alpha,
						e.type == FrameTape.TEMP && a[7] != 0, material, shading);
				}
				else
				{
					uploader.uploadTempModel(m, orient, x, y, z, cx, cy, cz, opaque, material, shading);
				}
				n += m.faceCount;
			}
			if (f >= 0)
			{
				ms[f] = (System.nanoTime() - t0) / 1e6;
				faces = n;
			}
		}
		Arrays.sort(ms);
		System.out.printf("UPLOAD %s: %,d faces a frame; %.3f ms a frame (min %.3f, p90 %.3f)%n", args[0], faces, ms[frames / 2],
			ms[0], ms[frames * 9 / 10]);
		System.exit(0);
	}

	private static FloatProjection projection(FrameTape tape, int index)
	{
		FrameTape.Proj p = tape.projections.get(index);
		return new FloatProjection()
		{
			@Override
			public float[] getProjection()
			{
				return p.matrix;
			}

			@Override
			public float[] project(float x, float y, float z)
			{
				return FrameTape.project(p.affine, x, y, z, new float[3]);
			}

			@Override
			public float[] project(float x, float y, float z, float[] out)
			{
				return FrameTape.project(p.affine, x, y, z, out);
			}
		};
	}

	private UploadBench()
	{
	}
}
