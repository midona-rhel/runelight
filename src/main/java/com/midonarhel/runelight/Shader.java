/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2020 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.runelight;

import com.midonarhel.runelight.template.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.lwjgl.opengl.GL33C.*;

class Shader
{
	// Driver log line prefixes: "ERROR: 0:42:" (Apple/AMD/Intel), "0(42) :" (NVIDIA), "0:42(5):" (Mesa)
	private static final Pattern LOG_LINE = Pattern.compile("^(\\s*(?:ERROR|WARNING|error|warning)?:?\\s*)\\d+[:(](\\d+)\\)?");

	private static final class Unit
	{
		final int type;
		final String filename;

		Unit(int type, String filename)
		{
			this.type = type;
			this.filename = filename;
		}
	}

	private final List<Unit> units = new ArrayList<>();

	public Shader add(int type, String name)
	{
		units.add(new Unit(type, name));
		return this;
	}

	int compile(Template template) throws ShaderException
	{
		int program = glCreateProgram();
		int[] shaders = new int[units.size()];
		int i = 0;
		boolean ok = false;
		try
		{
			while (i < shaders.length)
			{
				Unit unit = units.get(i);
				int shader = glCreateShader(unit.type);
				if (shader == 0)
				{
					throw new ShaderException("Unable to create shader of type " + unit.type);
				}

				Template.Source source = template.loadMapped(unit.filename);
				glShaderSource(shader, source.getText());
				glCompileShader(shader);

				if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE)
				{
					String err = glGetShaderInfoLog(shader);
					glDeleteShader(shader);
					throw new ShaderException(unit.filename + " failed to compile:\n" + remap(err, source));
				}
				glAttachShader(program, shader);
				shaders[i++] = shader;
			}

			glLinkProgram(program);

			if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
			{
				throw new ShaderException(describe() + " failed to link:\n" + glGetProgramInfoLog(program));
			}

			// No glValidateProgram: it checks the current GL state, not the program. Right after linking every
			// sampler still points at unit 0, and Apple's driver rejects programs mixing sampler types there.

			ok = true;
		}
		finally
		{
			while (i > 0)
			{
				int shader = shaders[--i];
				glDetachShader(program, shader);
				glDeleteShader(shader);
			}

			if (!ok)
			{
				glDeleteProgram(program);
			}
		}

		return program;
	}

	private String describe()
	{
		StringBuilder sb = new StringBuilder();
		for (Unit u : units)
		{
			if (sb.length() > 0)
			{
				sb.append('+');
			}
			sb.append(u.filename);
		}
		return sb.toString();
	}

	/**
	 * Rewrite line numbers in a driver log from the flattened source back to the original file:line.
	 */
	static String remap(String log, Template.Source source)
	{
		StringBuilder sb = new StringBuilder();
		for (String line : log.split("\r?\n"))
		{
			if (line.isEmpty())
			{
				continue;
			}
			Matcher m = LOG_LINE.matcher(line);
			if (m.find())
			{
				String origin = source.origin(Integer.parseInt(m.group(2)));
				if (origin != null)
				{
					line = m.group(1) + origin + line.substring(m.end());
				}
			}
			sb.append(line).append('\n');
		}
		return sb.toString().trim();
	}
}
