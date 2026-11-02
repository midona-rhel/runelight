package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.template.Template;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The clear night sky with no scene (sky_panorama_frag.glsl): the whole upper half of the sky, which OSRS's camera
 * never looks up into, drawn by the composite's own night_sky.glsl. -Dlumina.panorama=dir writes sky-&lt;t&gt;s.png for the
 * seconds in -Dlumina.times (default 100), and reference-&lt;t&gt;s.png, 117 HD's aurora over 117 HD's night sky.
 * -Dlumina.aurora is the auroraStrength uniform (default NightSky's at full strength), -Dlumina.alt the sun's
 * altitude (default -35, midnight), -Dlumina.nebula and -Dlumina.stars the nebulaStrength and starBrightness settings (default 1).
 */
public class SkyPanorama
{
	@Test
	public void panorama() throws Exception
	{
		String dir = System.getProperty("lumina.panorama");
		Assume.assumeTrue("no -Dlumina.panorama", dir != null && !dir.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		int w = 1440, h = 360;
		float aurora = Float.parseFloat(System.getProperty("lumina.aurora", "0.396"));
		float alt = Float.parseFloat(System.getProperty("lumina.alt", "-35"));
		float nebula = Float.parseFloat(System.getProperty("lumina.nebula", "1"));
		float stars = Float.parseFloat(System.getProperty("lumina.stars", "1"));
		new File(dir).mkdirs();
		try (HeadlessGl gl = new HeadlessGl())
		{
			Template template = new Template().addInclude(LuminaPlugin.class).addInclude(SkyPanorama.class);
			int program = new Shader()
				.add(GL_VERTEX_SHADER, "sky_panorama_vert.glsl")
				.add(GL_FRAGMENT_SHADER, "sky_panorama_frag.glsl")
				.compile(template);
			int fbo = glGenFramebuffers(), rb = glGenRenderbuffers(), vao = glGenVertexArrays();
			glBindRenderbuffer(GL_RENDERBUFFER, rb);
			glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
			glViewport(0, 0, w, h);
			glUseProgram(program);
			glBindVertexArray(vao);
			glUniform2f(glGetUniformLocation(program, "size"), w, h);
			glUniform1f(glGetUniformLocation(program, "alt"), alt);
			glUniformMatrix3fv(glGetUniformLocation(program, "starFrame"), false, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
			glUniform1f(glGetUniformLocation(program, "starBrightness"), stars);
			glUniform1f(glGetUniformLocation(program, "nebulaStrength"), nebula);
			glUniform1f(glGetUniformLocation(program, "auroraStrength"), aurora);
			glUniform1f(glGetUniformLocation(program, "starPixel"), (float) (Math.PI / 2 / h));
			for (String t : System.getProperty("lumina.times", "100").split(","))
			{
				for (int reference = 0; reference < 2; ++reference)
				{
					glUniform1f(glGetUniformLocation(program, "time"), Float.parseFloat(t));
					glUniform1i(glGetUniformLocation(program, "reference"), reference);
					glDrawArrays(GL_TRIANGLES, 0, 3);
					ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
					glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
					BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
					for (int y = 0; y < h; ++y)
					{
						for (int x = 0; x < w; ++x)
						{
							int i = ((h - 1 - y) * w + x) * 4;
							img.setRGB(x, y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | px.get(i + 2) & 255);
						}
					}
					ImageIO.write(img, "png", new File(dir, (reference == 1 ? "reference-" : "sky-") + t + "s.png"));
				}
			}
			glDeleteProgram(program);
		}
	}
}
