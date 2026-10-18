/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package com.midonarhel.lumen.template;

import com.google.common.io.CharStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands {@code #include} directives and remembers which file and line every output line came from,
 * so compiler errors can point at the original shader file instead of the flattened source.
 */
public class Template
{
	private static final Logger log = LoggerFactory.getLogger(Template.class);

	private final List<Function<String, String>> resourceLoaders = new ArrayList<>();

	public static final class Source
	{
		private final StringBuilder text = new StringBuilder();
		private final List<String> origins = new ArrayList<>();

		public String getText()
		{
			return text.toString();
		}

		/**
		 * @param line 1-based line in the flattened source
		 * @return "file:line" of where it came from, or null if out of range
		 */
		public String origin(int line)
		{
			return line >= 1 && line <= origins.size() ? origins.get(line - 1) : null;
		}
	}

	public Source loadMapped(String filename)
	{
		Source out = new Source();
		append(out, filename, 0);
		return out;
	}

	public String load(String filename)
	{
		return loadMapped(filename).getText();
	}

	private void append(Source out, String filename, int depth)
	{
		if (depth > 32)
		{
			throw new IllegalStateException("include depth exceeded at " + filename);
		}

		String str = loadRaw(filename);
		if (str == null)
		{
			log.warn("shader include not found: {}", filename);
			return;
		}

		int lineNo = 0;
		for (String line : str.split("\r?\n", -1))
		{
			++lineNo;
			if (line.startsWith("#include "))
			{
				String resource = line.substring(9).trim();
				if (resource.startsWith("\"") && resource.endsWith("\""))
				{
					resource = resource.substring(1, resource.length() - 1);
				}
				append(out, resource, depth + 1);
			}
			else
			{
				out.text.append(line).append('\n');
				out.origins.add(filename + ":" + lineNo);
			}
		}
	}

	private String loadRaw(String filename)
	{
		for (Function<String, String> loader : resourceLoaders)
		{
			String value = loader.apply(filename);
			if (value != null)
			{
				return value;
			}
		}
		return null;
	}

	public Template add(Function<String, String> fn)
	{
		resourceLoaders.add(fn);
		return this;
	}

	/**
	 * Load shader files straight from a directory on disk. Used for hot reloading during development so
	 * edits to src/main/resources are picked up without rebuilding.
	 */
	public Template addDirectory(Path dir)
	{
		return add(f ->
		{
			Path p = dir.resolve(f).normalize();
			if (!p.startsWith(dir) || !Files.isRegularFile(p))
			{
				return null;
			}
			try
			{
				return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
			}
			catch (IOException ex)
			{
				log.warn("unable to read shader {}", p, ex);
				return null;
			}
		});
	}

	public Template addInclude(Class<?> clazz)
	{
		return add(f ->
		{
			try (InputStream is = clazz.getResourceAsStream(f))
			{
				if (is != null)
				{
					return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
				}
			}
			catch (IOException ex)
			{
				log.warn(null, ex);
			}
			return null;
		});
	}
}
