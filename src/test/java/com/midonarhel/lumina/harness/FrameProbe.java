package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Where each pixel of the last frame is in the world: the scene's depth read back and turned into positions as
 * frame.glsl's reconstructPosition does. The scene targets must not be multisampled (antiAliasingMode DISABLED);
 * positions are in local world units (128 per tile, y down), null for the sky.
 */
public final class FrameProbe implements Checks.Positions
{
	public final int targetWidth, targetHeight, windowWidth, windowHeight;
	private final float[] depth;
	private final float[] inv;
	private final int[] viewport;
	private final float camX, camY, camZ;
	private final boolean zeroToOne;

	public FrameProbe(LuminaPlugin plugin, int windowWidth, int windowHeight)
	{
		targetWidth = plugin.sceneTargets.getWidth();
		targetHeight = plugin.sceneTargets.getHeight();
		this.windowWidth = windowWidth;
		this.windowHeight = windowHeight;
		FloatBuffer buf = BufferUtils.createFloatBuffer(targetWidth * targetHeight);
		// what the frame left in the error queue is not this read's
		for (int i = 0; i < 64 && glGetError() != GL_NO_ERROR; ++i)
		{
		}
		glBindFramebuffer(GL_READ_FRAMEBUFFER, plugin.sceneTargets.getRenderFbo());
		glReadPixels(0, 0, targetWidth, targetHeight, GL_DEPTH_COMPONENT, GL_FLOAT, buf);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		int err = glGetError();
		if (err != GL_NO_ERROR)
		{
			throw new IllegalStateException("depth read back: GL error " + err + " (multisampled targets? antiAliasingMode=DISABLED)");
		}
		depth = new float[targetWidth * targetHeight];
		buf.get(depth);
		inv = plugin.frameInvViewProj.clone();
		viewport = plugin.frameViewport.clone();
		camX = plugin.frameCameraX;
		camY = plugin.frameCameraY;
		camZ = plugin.frameCameraZ;
		zeroToOne = plugin.glCapabilities.OpenGL45;
	}

	/** The world position under a window pixel (x right, y down, as in the images), null for the sky. */
	@Override
	public float[] world(int x, int y)
	{
		int tx = Math.min(targetWidth - 1, x * targetWidth / windowWidth);
		int ty = Math.min(targetHeight - 1, (windowHeight - 1 - y) * targetHeight / windowHeight);
		float d = depth[ty * targetWidth + tx];
		if (zeroToOne ? d <= 0 : d <= 0.5f)
		{
			return null;
		}
		float nx = (tx + 0.5f - viewport[0]) / viewport[2] * 2 - 1;
		float ny = (ty + 0.5f - viewport[1]) / viewport[3] * 2 - 1;
		float nz = zeroToOne ? d : d * 2 - 1;
		float[] m = inv;
		float hx = m[0] * nx + m[4] * ny + m[8] * nz + m[12];
		float hy = m[1] * nx + m[5] * ny + m[9] * nz + m[13];
		float hz = m[2] * nx + m[6] * ny + m[10] * nz + m[14];
		float hw = m[3] * nx + m[7] * ny + m[11] * nz + m[15];
		return new float[]{hx / hw + camX, hy / hw + camY, hz / hw + camZ};
	}
}
