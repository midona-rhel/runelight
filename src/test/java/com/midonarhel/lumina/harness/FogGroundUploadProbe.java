package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.fog.FogGround;
import com.midonarhel.lumina.fog.VolumetricFog;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The client thread's time in the fog ground's upload (VolumetricFog.upload, as sendGround makes it when the rooms
 * change) while the GPU is busy drawing the dump's frames, at most two in flight as the swap paces them, the ground sent
 * every fourth frame and the textures turned over as sendGround turns them (the build's own way: uploadSpare, or before
 * it upload into the back one), each send easing in over the last as the rooms' do (LayerEase: the back texture read
 * meanwhile). Prints the upload's median, p90 and max, and the same with the GPU idle.
 * ./gradlew fogGroundUpload -Pargs="fps1 100"
 */
public final class FogGroundUploadProbe
{
	public static void main(String[] args) throws Exception
	{
		SceneDump.Loaded dump = RenderDump.load(args[0]);
		int sends = args.length > 1 ? Integer.parseInt(args[1]) : 100;
		try (DumpRenderer r = new DumpRenderer(dump, new DumpRenderer.Options()))
		{
			r.render();
			VolumetricFog fog = r.plugin.volumetricFog;
			Method spare = null, upload = null;
			try
			{
				spare = VolumetricFog.class.getDeclaredMethod("uploadSpare", float[].class);
				spare.setAccessible(true);
			}
			catch (NoSuchMethodException ex)
			{
				upload = VolumetricFog.class.getDeclaredMethod("upload", int.class, float[].class);
				upload.setAccessible(true);
			}
			var front = VolumetricFog.class.getDeclaredField("texGround");
			var back = VolumetricFog.class.getDeclaredField("texGroundBack");
			front.setAccessible(true);
			back.setAccessible(true);
			java.lang.reflect.Field spareTex = spare != null ? VolumetricFog.class.getDeclaredField("texGroundSpare") : null;
			if (spareTex != null)
			{
				spareTex.setAccessible(true);
			}
			float[] texels = new float[FogGround.WIDTH * FogGround.CORNERS * FogGround.CHANNELS];
			double[] ms = new double[sends], idle = new double[sends];
			ArrayDeque<Long> fences = new ArrayDeque<>();
			for (int i = -5; i < sends; ++i)
			{
				for (int k = 0; k < 4; ++k)
				{
					while (fences.size() >= 2)
					{
						long f = fences.poll();
						glClientWaitSync(f, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
						glDeleteSync(f);
					}
					r.frame();
					fences.add(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
				}
				Arrays.fill(texels, i);
				long t0 = System.nanoTime();
				send(fog, spare, upload, front, back, spareTex, texels);
				long t1 = System.nanoTime();
				// the same with the GPU idle: the upload's own cost
				glFinish();
				long t2 = System.nanoTime();
				send(fog, spare, upload, front, back, spareTex, texels);
				long t3 = System.nanoTime();
				if (i >= 0)
				{
					ms[i] = (t1 - t0) / 1e6;
					idle[i] = (t3 - t2) / 1e6;
				}
			}
			Arrays.sort(ms);
			Arrays.sort(idle);
			System.out.printf("FOGGROUND %s: %dx%d RGBA32F; upload median %.2f ms, p90 %.2f, max %.2f; the GPU idle median %.2f,"
					+ " max %.2f%n", args[0], FogGround.WIDTH, FogGround.CORNERS, ms[sends / 2], ms[sends * 9 / 10], ms[sends - 1],
				idle[sends / 2], idle[sends - 1]);
		}
		System.exit(0);
	}

	/** As sendGround: the texels uploaded and the textures turned over. */
	private static void send(VolumetricFog fog, Method spare, Method upload, java.lang.reflect.Field front,
		java.lang.reflect.Field back, java.lang.reflect.Field spareTex, float[] texels) throws Exception
	{
		var ease = VolumetricFog.class.getDeclaredField("easeFromBack");
		var since = VolumetricFog.class.getDeclaredField("groundSince");
		ease.setAccessible(true);
		since.setAccessible(true);
		ease.setBoolean(fog, true);
		since.setLong(fog, com.midonarhel.lumina.common.Clock.nanos());
		if (spare != null)
		{
			int fresh = (int) spare.invoke(fog, (Object) texels);
			spareTex.setInt(fog, back.getInt(fog));
			back.setInt(fog, front.getInt(fog));
			front.setInt(fog, fresh);
		}
		else
		{
			int fresh = (int) upload.invoke(fog, back.getInt(fog), texels);
			back.setInt(fog, front.getInt(fog));
			front.setInt(fog, fresh);
		}
	}

	private FogGroundUploadProbe()
	{
	}
}
