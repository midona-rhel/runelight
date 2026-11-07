package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.harness.HeadlessGl;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

public class WaterShadingTargetsTest
{
	@Test public void waterTracksSceneWhileCloudAndAoSizingStayUnchanged()
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		try (HeadlessGl context = new HeadlessGl())
		{
			for (float scale : new float[]{1f, .75f, .5f, .25f})
			{
				int width = Math.round(257 * scale), height = Math.round(145 * scale);
				float ratio = Math.max(1, 2 * scale);
				SceneTargets targets = new SceneTargets();
				targets.init(width, height, 0, ratio);
				try
				{
					assertEquals((int) Math.ceil(width / (double) ratio), targets.getAoWidth());
					targets.bindHalf(true, targets.waterSharesHalfSize());
					assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
					glBindTexture(GL_TEXTURE_2D, targets.cloudTexture());
					assertEquals(targets.getAoWidth(), glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH));
					targets.bindHalf(false, true);
					assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
					glBindTexture(GL_TEXTURE_2D, targets.waterShadedTexture());
					assertEquals(width, glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH));
					assertEquals(height, glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT));
					// Returning to clouds must not clip their framebuffer to the water attachment.
					targets.bindHalf(true, false);
					assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
					assertEquals(GL_NO_ERROR, glGetError());
				}
				finally { targets.free(); }
			}
		}
	}
}
