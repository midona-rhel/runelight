package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.light.LampCubes;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/** The lamps' shadow cube atlas of the dump named by -Dlumina.dump, read back to -Dlumina.out/atlas.png (near white). */
public class CubeAtlasProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		try (DumpRenderer r = new DumpRenderer(RenderDump.load(name), new DumpRenderer.Options()))
		{
			r.render();
			int w = LampCubes.WIDTH, h = LampCubes.HEIGHT;
			FloatBuffer px = BufferUtils.createFloatBuffer(w * h);
			glBindTexture(GL_TEXTURE_2D, r.plugin.lampCubes.atlas());
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
			glGetTexImage(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT, GL_FLOAT, px);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; ++y)
			{
				for (int x = 0; x < w; ++x)
				{
					float d = px.get(y * w + x);
					// the depth is near 1 for most of the reach: spread its last part
					int v = d >= 1f ? 255 : (int) Math.max(0, Math.min(254, (d - 0.9f) * 10 * 254));
					img.setRGB(x, h - 1 - y, v << 16 | v << 8 | v);
				}
			}
			ImageIO.write(img, "png", new File(System.getProperty("lumina.out", "."), "atlas.png"));
			System.out.println("ATLAS written, lamps " + r.plugin.lightClusters.getLightCount());
		}
	}
}
