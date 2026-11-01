package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.harness.Fakes;
import com.midonarhel.lumina.scene.ModelData;
import com.midonarhel.lumina.scene.ModelFake;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.FloatProjection;
import net.runelite.api.Model;
import net.runelite.api.Scene;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FrameTapeTest
{
	static Scene scene(int worldView)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getWorldViewId", worldView);
		a.put("getOverrideHue", 1);
		a.put("getOverrideSaturation", 2);
		a.put("getOverrideLuminance", 3);
		a.put("getOverrideAmount", 4);
		return Fakes.of(Scene.class, a);
	}

	static ModelData model()
	{
		ModelData d = new ModelData();
		d.vertexCount = 3;
		d.verticesX = new float[]{0, 128, 0};
		d.verticesY = new float[]{0, 0, -64};
		d.verticesZ = new float[]{0, 0, 128};
		d.faceCount = 1;
		d.indices1 = new int[]{0};
		d.indices2 = new int[]{1};
		d.indices3 = new int[]{2};
		d.colors1 = new int[]{1000};
		d.colors2 = new int[]{1001};
		d.colors3 = new int[]{-1};
		d.unlitColors = new short[]{5};
		d.textures = null;
		return d;
	}

	@Test
	public void recordsAndReadsBack() throws IOException
	{
		// a projection that is an affine map: the probe reads it back exactly
		float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		FloatProjection proj = Fakes.of(FloatProjection.class, Map.of(
			"getProjection", m,
			"project", (Fakes.Answer) args ->
			{
				float x = (float) args[0], y = (float) args[1], z = (float) args[2];
				float[] out = (float[]) args[3];
				out[0] = 2 * x + y + 5;
				out[1] = -y + 0.5f * z;
				out[2] = z + 100;
				return out;
			}));
		Model fake = ModelFake.of(model());
		FrameTape t = new FrameTape();
		Scene top = scene(-1);
		t.pre(top, proj, 1, 2, 3, 0.5f, 1.5f, 0, 1, 3, Set.of(7, 9));
		t.zoneOpaque(top, 4, 5);
		t.dynamic(0, proj, top, fake, 12, 259, 1, 512, 100, 200, 300);
		t.temp(proj, scene(3), fake, 0, 1, 2, 3, 4, 259, true, false, 5, 6, 1, 99);
		t.post(top);
		t.draw(0x80112233);

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			t.write(out);
		}
		FrameTape back = FrameTape.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

		assertEquals(6, back.events.size());
		assertEquals(1, back.projections.size());
		FrameTape.Proj p = back.projections.get(0);
		assertEquals(0, p.error, 1e-3);
		assertArrayEquals(m, p.matrix, 0);
		float[] got = FrameTape.project(p.affine, 10, 20, 30, new float[3]);
		assertArrayEquals(new float[]{45, -5, 130}, got, 1e-3f);

		FrameTape.Event pre = back.events.get(0);
		assertEquals(FrameTape.PRE, pre.type);
		assertEquals(-1, pre.worldView);
		assertArrayEquals(new float[]{1, 2, 3, 0.5f, 1.5f}, pre.floats, 0);
		assertEquals(2, pre.ints[7]);
		assertTrue(pre.ints[8] == 7 || pre.ints[8] == 9);
		assertEquals(4, pre.ints[6]);

		FrameTape.Event dyn = back.events.get(2);
		assertArrayEquals(new int[]{0, 12, 259, 1, 512, 100, 200, 300}, dyn.ints);
		assertEquals(0, dyn.projection);
		Object[] want = ModelFake.fields(model()), have = ModelFake.fields(back.models.get(dyn.model));
		for (int i = 0; i < want.length; ++i)
		{
			if (want[i] instanceof float[])
			{
				assertArrayEquals((float[]) want[i], (float[]) have[i], 0);
			}
			else if (want[i] instanceof int[])
			{
				assertArrayEquals((int[]) want[i], (int[]) have[i]);
			}
			else if (!(want[i] instanceof short[] || want[i] instanceof byte[] || want[i] == null))
			{
				assertEquals("field " + i, want[i], have[i]);
			}
		}
		assertEquals(3, back.events.get(3).worldView);
		assertEquals(0x80112233, back.events.get(5).ints[0]);
	}
}
