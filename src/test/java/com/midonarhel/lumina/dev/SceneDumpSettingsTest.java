package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.harness.Fakes;
import java.lang.reflect.Method;
import java.util.Map;
import net.runelite.client.config.ConfigItem;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SceneDumpSettingsTest
{
	@Test
	public void everySettingIsWritten()
	{
		int items = 0;
		for (Method m : LuminaConfig.class.getMethods())
		{
			if (m.getAnnotation(ConfigItem.class) != null && m.getParameterCount() == 0)
			{
				items++;
			}
		}
		// a config of the defaults: every setting answers its default method
		Map<String, String> settings = SceneDump.settings(Fakes.of(LuminaConfig.class, Map.of()));
		assertEquals(items, settings.size());
		assertEquals("FINAL", settings.get("debugView"));
	}
}
