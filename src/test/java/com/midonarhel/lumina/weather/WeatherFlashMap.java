package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.dev.SceneDump;
import java.util.Map;

/** The weather field a dump holds, cell by cell: ./gradlew weatherFlashMap -Pargs="white1". */
public final class WeatherFlashMap
{
	public static void main(String[] args) throws Exception
	{
		java.io.File dir = new java.io.File(System.getProperty("user.home") + "/.runelite/lumina/dumps/" + args[0]);
		SceneDump.Loaded d = SceneDump.load(dir.toPath());
		Map<String, Object> m = d.derived;
		float[][] s = (float[][]) m.get("state.weatherField.state");
		String[] biomes = (String[]) m.get("state.weatherField.biomes");
		int n = (Integer) m.get("state.weatherField.sizeX");
		float ox = ((Number) m.get("state.weatherField.originX")).floatValue(), oz = ((Number) m.get("state.weatherField.originZ")).floatValue();
		System.out.println("size " + n + " origin " + ox + " " + oz + " base " + d.manifest.baseX + " " + d.manifest.baseY);
		int[] ch = {WeatherField.RAIN, WeatherField.SNOW, WeatherField.WET, WeatherField.SNOW_COVER};
		String[] names = {"RAIN", "SNOW", "WET", "SNOW_COVER"};
		for (int c = 0; c < 4; ++c)
		{
			StringBuilder b = new StringBuilder(names[c] + "\n");
			for (int z = n - 1; z >= 0; --z)
			{
				for (int x = 0; x < n; ++x)
				{
					int v = Math.round(s[ch[c]][z * n + x] * 9);
					b.append(v == 0 ? '.' : (char) ('0' + Math.min(v, 9)));
				}
				b.append('\n');
			}
			System.out.print(b);
		}
		java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
		for (String x : biomes)
		{
			counts.merge(String.valueOf(x), 1, Integer::sum);
		}
		System.out.println(counts);
		System.out.println("focus " + java.util.Arrays.toString((float[]) m.get("state.weatherField.focus")));
	}
}
