package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.harness.HeadlessGl;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

/** Sharing uploaded maps must not replace another program's uniform locations. */
public class SkylightUniformProgramsTest
{
	@Test public void locatingAlphaPreservesAoBindings()
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		try (HeadlessGl context = new HeadlessGl())
		{
			int ao = program(false), alpha = program(true);
			try
			{
				SkyGridMap grid = new SkyGridMap();
				SkyFieldMap field = new SkyFieldMap();
				grid.locateAo(ao, 0);
				field.locateAo(ao, 2, 3);
				SkyGridMap.Uniforms alphaGrid = grid.locate(alpha, 0);
				SkyFieldMap.Uniforms alphaField = field.locate(alpha, 2, 3);
				setOn(ao);
				setOn(alpha);
				grid.bind(alphaGrid, 0, null, 0, 0, 0, false);
				field.bind(alphaField, 2, 3, 0, 0);
				assertOn(alpha, 0);
				assertOn(ao, 1);
				setOn(alpha);
				glUseProgram(ao);
				grid.bindAo(0, null, 0, 0, 0);
				field.bindAo(2, 3, 0, 0);
				assertOn(ao, 0);
				assertOn(alpha, 1);
				assertEquals(GL_NO_ERROR, glGetError());
			}
			finally { glUseProgram(0); glDeleteProgram(ao); glDeleteProgram(alpha); }
		}
	}

	private static void setOn(int program)
	{
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "skylightOn"), 1);
		glUniform1i(glGetUniformLocation(program, "skyFieldOn"), 1);
	}

	private static void assertOn(int program, int value)
	{
		assertEquals(value, glGetUniformi(program, glGetUniformLocation(program, "skylightOn")));
		assertEquals(value, glGetUniformi(program, glGetUniformLocation(program, "skyFieldOn")));
	}

	private static int program(boolean extraUniform)
	{
		int vertex = shader(GL_VERTEX_SHADER, "#version 330\nvoid main(){gl_Position=vec4(0,0,0,1);}");
		int fragment = shader(GL_FRAGMENT_SHADER, "#version 330\n"
			+ (extraUniform ? "uniform vec4 extra[7];\n" : "")
			+ "uniform int skylightOn; uniform int skyFieldOn; out vec4 color;\n"
			+ "void main(){color=vec4(float(skylightOn),float(skyFieldOn),0,1)"
			+ (extraUniform ? "+extra[6]" : "") + ";}");
		int program = glCreateProgram();
		glAttachShader(program, vertex);
		glAttachShader(program, fragment);
		glLinkProgram(program);
		glDeleteShader(vertex);
		glDeleteShader(fragment);
		assertEquals(glGetProgramInfoLog(program), GL_TRUE, glGetProgrami(program, GL_LINK_STATUS));
		return program;
	}

	private static int shader(int type, String source)
	{
		int shader = glCreateShader(type);
		glShaderSource(shader, source);
		glCompileShader(shader);
		assertEquals(glGetShaderInfoLog(shader), GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS));
		return shader;
	}
}
