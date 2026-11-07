package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

public class ShadowDenoiseTest
{
	@Test public void broadNeighboursCannotBlurSharpContactButSoftRegionsStillDenoise() throws Exception
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		try (HeadlessGl context = new HeadlessGl())
		{
			Template template = new Template();
			template.add(key -> key.equals("probe_vert") ? "#version 330\nvoid main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); gl_Position = vec4(p * 2.0 - 1.0, 0, 1); }"
				: key.equals("msaa_config") ? "#define MSAA_SAMPLES 0\n" : null);
			template.addInclude(LuminaPlugin.class);
			int program = new Shader().add(GL_VERTEX_SHADER, "probe_vert").add(GL_FRAGMENT_SHADER, "denoise_frag.glsl").compile(template);
			int vao = glGenVertexArrays(), fbo = glGenFramebuffers();
			int geometry = glGenTextures(), shadows = glGenTextures(), output = glGenTextures();
			try
			{
				float[] g = new float[25 * 4], s = new float[25 * 4];
				for (int i = 0; i < 25; i++) { g[i * 4] = .5f; g[i * 4 + 1] = 2048 * 4096 + 2048; s[i * 4] = 7; s[i * 4 + 1] = .8f; }
				texture(geometry, g);
				texture(output, null);
				glBindFramebuffer(GL_FRAMEBUFFER, fbo);
				glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output, 0);
				glDrawBuffer(GL_COLOR_ATTACHMENT0);
				assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
				glUseProgram(program);
				glUniform1i(glGetUniformLocation(program, "sceneAo"), 0);
				glUniform1i(glGetUniformLocation(program, "sceneShadow"), 1);
				glUniform1i(glGetUniformLocation(program, "sceneFog"), 2);
				glBindVertexArray(vao);
				glViewport(0, 0, 5, 5);
				for (boolean sharp : new boolean[]{true, false})
				{
					s[12 * 4] = sharp ? 0 : 6; // Unlit center, sharp or broad, surrounded by broad lit pixels.
					glActiveTexture(GL_TEXTURE1);
					texture(shadows, s);
					glActiveTexture(GL_TEXTURE0);
					glBindTexture(GL_TEXTURE_2D, geometry);
					glDrawArrays(GL_TRIANGLES, 0, 3);
					float[] pixel = new float[4];
					glReadPixels(2, 2, 1, 1, GL_RGBA, GL_FLOAT, pixel);
					if (sharp) assertEquals("contact must stay fully shadowed", 0, pixel[0], .0001f);
					else assertTrue("broad penumbra must still denoise", pixel[0] > .5f && pixel[0] < 1);
					assertEquals("AO unaffected", .8f, pixel[1], .0001f);
				}
				assertEquals(GL_NO_ERROR, glGetError());
			}
			finally
			{
				glDeleteTextures(geometry); glDeleteTextures(shadows); glDeleteTextures(output);
				glDeleteFramebuffers(fbo); glDeleteVertexArrays(vao); glDeleteProgram(program);
			}
		}
	}

	private static void texture(int id, float[] pixels)
	{
		glBindTexture(GL_TEXTURE_2D, id);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 5, 5, 0, GL_RGBA, GL_FLOAT, pixels);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	}
}
