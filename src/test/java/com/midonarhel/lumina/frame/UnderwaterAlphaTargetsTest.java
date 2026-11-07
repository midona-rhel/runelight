package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.harness.HeadlessGl;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

public class UnderwaterAlphaTargetsTest
{
	@Test public void alphaLayersRemainSeparateAndClearTogether()
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		try (HeadlessGl context = new HeadlessGl())
		{
			for (int samples : new int[]{0, 2})
			{
				SceneTargets targets = new SceneTargets();
				targets.init(17, 11, samples, 2);
				try
				{
					glBindFramebuffer(GL_FRAMEBUFFER, targets.getRenderFbo());
					assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
					targets.clear(0, 0, 0);
					targets.setAlphaPass(true);
					assertEquals(GL_COLOR_ATTACHMENT2, glGetInteger(GL_DRAW_BUFFER2));
					assertEquals(GL_COLOR_ATTACHMENT4, glGetInteger(GL_DRAW_BUFFER4));
					glClearBufferfv(GL_COLOR, 2, new float[]{1, 0, 0, 1});
					glClearBufferfv(GL_COLOR, 4, new float[]{0, 1, 0, 1});
					if (samples == 0)
					{
						assertPixel(GL_COLOR_ATTACHMENT2, 1, 0);
						assertPixel(GL_COLOR_ATTACHMENT4, 0, 1);
						targets.clear(0, 0, 0);
						assertPixel(GL_COLOR_ATTACHMENT2, 0, 0);
						assertPixel(GL_COLOR_ATTACHMENT4, 0, 0);
					}
					targets.setAlphaPass(false);
					assertEquals(GL_NONE, glGetInteger(GL_DRAW_BUFFER4));
					assertEquals(GL_NO_ERROR, glGetError());
				}
				finally { targets.free(); }
			}
		}
	}

	private static void assertPixel(int attachment, float r, float g)
	{
		glReadBuffer(attachment);
		float[] pixel = new float[4];
		glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixel);
		assertEquals(r, pixel[0], 0.001f);
		assertEquals(g, pixel[1], 0.001f);
	}
}
