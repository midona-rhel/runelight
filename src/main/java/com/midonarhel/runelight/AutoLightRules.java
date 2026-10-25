package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which things that burn or glow get a light that 117 HD does not give them (autolights.json): the rules look at the
 * name of a game object, whole words and case aside, and give a preset, a light with the strength and color of the
 * 117 HD lights of the same kind. A name with a word of the exclusions (unlit, extinguished, a shop sign) gets none.
 * The first rule that fits wins.
 */
final class AutoLightRules
{
	/**
	 * The file as it is.
	 */
	private static final class Data
	{
		Map<String, LightDefinition> presets;
		List<Rule> rules;
		List<String> exclude;
	}

	private static final class Rule
	{
		String preset;
		List<String> names;
	}

	private final Set<String> excluded = new HashSet<>();
	private final List<String[]> patterns = new ArrayList<>();
	private final List<LightDefinition> presets = new ArrayList<>();

	AutoLightRules(Gson gson, InputStream is)
	{
		Data data = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class);
		for (LightDefinition preset : data.presets.values())
		{
			preset.prepare();
		}
		for (String word : data.exclude)
		{
			excluded.add(word.toLowerCase(Locale.ROOT));
		}
		for (Rule rule : data.rules)
		{
			LightDefinition preset = data.presets.get(rule.preset);
			if (preset == null)
			{
				throw new IllegalStateException("autolights.json: no preset " + rule.preset);
			}
			preset.description = rule.preset;
			for (String name : rule.names)
			{
				String[] pattern = words(name);
				if (pattern.length == 0)
				{
					throw new IllegalStateException("autolights.json: empty name in " + rule.preset);
				}
				patterns.add(pattern);
				presets.add(preset);
			}
		}
	}

	/**
	 * The light for something with this name, null for none.
	 */
	LightDefinition match(String name)
	{
		if (name == null)
		{
			return null;
		}
		String[] words = words(name);
		for (String word : words)
		{
			if (excluded.contains(word))
			{
				return null;
			}
		}
		for (int i = 0; i < patterns.size(); ++i)
		{
			if (contains(words, patterns.get(i)))
			{
				return presets.get(i);
			}
		}
		return null;
	}

	private static String[] words(String name)
	{
		return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(w -> !w.isEmpty())
			.toArray(String[]::new);
	}

	/**
	 * Whether the pattern's words come one after the other in the name's.
	 */
	private static boolean contains(String[] words, String[] pattern)
	{
		for (int from = 0; from + pattern.length <= words.length; ++from)
		{
			int i = 0;
			while (i < pattern.length && words[from + i].equals(pattern[i]))
			{
				++i;
			}
			if (i == pattern.length)
			{
				return true;
			}
		}
		return false;
	}
}
