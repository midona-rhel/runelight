package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import java.lang.reflect.Method;

/** The GPU memory the zones' buffers take once a dump is restored (ZonePool's live bytes). ./gradlew zoneMemory -Pargs="nightref" */
public final class ZoneMemory
{
	public static void main(String[] args) throws Exception
	{
		for (String name : args)
		{
			SceneDump.Loaded dump = RenderDump.load(name);
			try (DumpRenderer r = new DumpRenderer(dump, new DumpRenderer.Options()))
			{
				java.lang.reflect.Field f = r.plugin.sceneLoader.getClass().getDeclaredField("pool");
				f.setAccessible(true);
				Object pool = f.get(r.plugin.sceneLoader);
				Method live = pool.getClass().getDeclaredMethod("liveBytes");
				live.setAccessible(true);
				System.out.printf("MEMORY %s: zone buffers %.1f MB%n", name, (long) live.invoke(pool) / 1e6);
			}
		}
		System.exit(0);
	}

	private ZoneMemory()
	{
	}
}
