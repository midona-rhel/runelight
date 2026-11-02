package com.midonarhel.lumina.harness;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The client thread's cost of uploading the skylight field as SkyFieldMap.upload does (a float buffer filled, then
 * glTexImage2D to RGBA16F, waited for with glFinish), on a headless GL context: the field's size before its gradient
 * rows (368 x 368) and after (368 x 736), median of 31. Appends to -Dlumina.out.
 */
public class FieldUploadProbe
{
	@Test
	public void probe() throws Exception
	{
		Assume.assumeTrue(System.getProperty("lumina.out") != null && Platform.get() == Platform.MACOSX);
		StringBuilder out = new StringBuilder();
		try (HeadlessGl gl = new HeadlessGl())
		{
			int w = 368;
			for (int rows : new int[]{w, 2 * w})
			{
				float[] texels = new float[w * rows * 4];
				for (int i = 0; i < texels.length; ++i) texels[i] = (i % 97) / 97f;
				int texture = glGenTextures();
				double[] ms = new double[31];
				for (int r = -5; r < ms.length; ++r)
				{
					glFinish();
					long t0 = System.nanoTime();
					FloatBuffer buf = BufferUtils.createFloatBuffer(texels.length);
					buf.put(texels).flip();
					glBindTexture(GL_TEXTURE_2D, texture);
					glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
					glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, rows, 0, GL_RGBA, GL_FLOAT, buf);
					glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
					glBindTexture(GL_TEXTURE_2D, 0);
					long t1 = System.nanoTime();
					glFinish();
					long t2 = System.nanoTime();
					if (r >= 0) ms[r] = (t1 - t0) / 1e6 + 0 * (t2 - t1);
				}
				glDeleteTextures(texture);
				Arrays.sort(ms);
				out.append(String.format("field upload %dx%d: %.2f ms on the client thread (median of %d; p10 %.2f, p90 %.2f)%n", w, rows, ms[ms.length / 2], ms.length, ms[3], ms[27]));
			}
		}
		System.out.print(out);
		Files.writeString(Path.of(System.getProperty("lumina.out")), out.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
