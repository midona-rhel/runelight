package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;
import static org.junit.Assert.assertEquals;
import static org.lwjgl.opengl.GL33C.*;

/** Exercise the real mist allocation/draw path with a small shader, independently of a saved scene. */
public class RainMistFramebufferTest
{
	@Test
	public void firstUseAndResizeReturnToTheSceneFramebuffer() throws Exception
	{
		Assume.assumeTrue("Headless GL supports Windows and macOS", Platform.get() != Platform.LINUX);
		try (HeadlessGl gl = new HeadlessGl())
		{
			int program = new Shader().add(GL_VERTEX_SHADER, "vertex").add(GL_FRAGMENT_SHADER, "fragment")
				.compile(new Template().add(name -> name.equals("vertex")
					? "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}\n"
					: "#version 330\nuniform int mistStage;uniform float mist;out vec4 color;void main(){color=vec4(float(mistStage)*mist*0.1);}\n"));
			int vao = glGenVertexArrays(), target = glGenFramebuffers(), texture = glGenTextures();
			Weather weather = new Weather();
			Field rain = Weather.class.getDeclaredField("rain");
			rain.setAccessible(true);
			Object uniforms = rain.get(weather);
			for (String name : new String[]{"mist", "mistStage"})
			{
				Field field = uniforms.getClass().getDeclaredField(name);
				field.setAccessible(true);
				field.setInt(uniforms, glGetUniformLocation(program, name));
			}
			Method draw = Weather.class.getDeclaredMethod("drawMist", float.class, int[].class);
			draw.setAccessible(true);
			Method free = Weather.class.getDeclaredMethod("freeMist");
			free.setAccessible(true);
			try
			{
				glBindVertexArray(vao);
				glUseProgram(program);
				glBindTexture(GL_TEXTURE_2D, texture);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 128, 128, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
				glBindFramebuffer(GL_FRAMEBUFFER, target);
				glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
				assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
				for (int size : new int[]{64, 64, 128})
				{
					glBindFramebuffer(GL_FRAMEBUFFER, target);
					draw.invoke(weather, 0.5f, new int[]{0, 0, size, size});
					assertEquals("Impacts after mist must draw into the scene, including allocation/resize", target,
						glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
					assertEquals(GL_NO_ERROR, glGetError());
				}
			}
			finally
			{
				free.invoke(weather);
				glDeleteFramebuffers(target);
				glDeleteTextures(texture);
				glDeleteVertexArrays(vao);
				glDeleteProgram(program);
			}
		}
	}
}
