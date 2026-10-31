package com.midonarhel.lumina;

import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * macOS's GL 4.1 lets a program's fragment stage sample 16 textures, and a program over it does not link (9dcfa5c8: the
 * composite at 17, the plugin did not start). Counted here from the sources: the samplers a fragment shader declares
 * and uses anywhere, its includes expanded. That is an upper bound of what a variant uses: the composite's source holds
 * 19, of which the cloud and edge variants' own three (cloudNoise, weatherSkyTex, compositeParts) the full one doesn't
 * use, which is at 16. A new sampler in it must take one out.
 */
public class ShaderSamplersTest
{
	private static final Pattern INCLUDE = Pattern.compile("^#include\\s+\"([^\"]+)\"", Pattern.MULTILINE);
	private static final Pattern SAMPLER = Pattern.compile("uniform\\s+(\\w*sampler\\w*|SCENE_SAMPLER)\\s+(\\w+)\\s*;");

	private static String expand(String name, Set<String> seen) throws IOException
	{
		String source;
		try (InputStream in = ShaderSamplersTest.class.getResourceAsStream("/com/midonarhel/lumina/" + name))
		{
			if (in == null)
			{
				return "";
			}
			source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		StringBuffer out = new StringBuffer();
		Matcher m = INCLUDE.matcher(source);
		while (m.find())
		{
			String inner = seen.add(m.group(1)) ? expand(m.group(1), seen) : "";
			m.appendReplacement(out, Matcher.quoteReplacement(inner));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static List<String> samplers(String shader) throws IOException
	{
		String code = expand(shader, new HashSet<>()).replaceAll("//[^\n]*", "");
		List<String> used = new ArrayList<>();
		Matcher m = SAMPLER.matcher(code);
		while (m.find())
		{
			String name = m.group(2);
			Matcher refs = Pattern.compile("\\b" + name + "\\b").matcher(code);
			int n = 0;
			while (refs.find())
			{
				++n;
			}
			if (n > 1)
			{
				used.add(name);
			}
		}
		return used;
	}

	@Test
	public void everyFragmentShaderKeepsToSixteenSamplers() throws IOException
	{
		for (String shader : new String[]{"frag.glsl", "fog_frag.glsl", "ao_frag.glsl", "denoise_frag.glsl", "rain_frag.glsl",
			"snow_frag.glsl", "water_frag.glsl", "sky_cache_frag.glsl", "sky_irradiance_frag.glsl", "fragui.glsl"})
		{
			List<String> used = samplers(shader);
			assertTrue(shader + " " + used, used.size() <= 16);
		}
		List<String> composite = samplers("composite_frag.glsl");
		assertTrue("composite " + composite, composite.size() <= 19);
	}
}
