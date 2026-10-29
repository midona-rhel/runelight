package com.midonarhel.lumina.sky;

import com.google.gson.Gson;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.scene.areas.AreaManager;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.util.GsonUtils;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * area_looks.json parses and has an entry for every environment 117 HD's environments.json defines (by the name
 * Lumina's Environment.toString gives it), with underground agreeing with 117 HD's isOverworld. A failure here after
 * a 117 HD data update means: run tools/import_area_looks.py.
 */
public class AreaLooksDataTest
{
	@Test
	public void everyEnvironmentHasALook() throws Exception
	{
		Gson gson = new Gson();
		AreaLook.Table looks = AreaLook.Table.load(gson);
		assertTrue(looks.size() > 100);
		assertNotNull(looks.get("OVERWORLD"));
		assertEquals(0, looks.get("OVERWORLD").underground, 0);
		assertEquals(0, looks.get("OVERWORLD").fogWeight, 0);

		AreaManager.load(gson);
		Environment[] environments = GsonUtils.loadResource(gson, "environments.json", Environment[].class);
		List<String> missing = new ArrayList<>();
		List<String> wrongDepth = new ArrayList<>();
		int checked = 0;
		for (Environment env : environments)
		{
			env.normalize();
			String name = env.toString();
			AreaLook look = looks.get(name);
			if (look == null)
			{
				missing.add(name);
				continue;
			}
			checked++;
			boolean underground = look.underground > 0;
			// the seasonal bases AUTUMN and WINTER have no area, so 117 HD leaves isOverworld false; they stand in for the
			// overworld and are never underground
			if (env.area == Area.NONE ? underground : underground == env.isOverworld)
			{
				wrongDepth.add(name + (env.isOverworld ? " is overworld in 117 HD" : " is underground in 117 HD"));
			}
		}
		assertTrue("area_looks.json lacks " + missing, missing.isEmpty());
		assertTrue("underground disagrees with 117 HD's isOverworld: " + wrongDepth, wrongDepth.isEmpty());
		assertTrue(checked > 100);
	}
}
