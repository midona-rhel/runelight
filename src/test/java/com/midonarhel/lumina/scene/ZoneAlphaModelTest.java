package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A static model's translucent faces in the zone's alpha buffer get one sort key per triangle written: a double-sided
 * face two (its back triangle follows its front one), a translucent face drawn as fake water (in the opaque buffer)
 * none. The teleport crash of 2026-10-31 14:32 was this count disagreeing with the triangles (Zone.addAlphaModel's
 * assertion), once double-sided faces came in.
 */
public class ZoneAlphaModelTest
{
	private static Model model()
	{
		// three triangles side by side, all translucent
		float[] vx = {0, 100, 0, 200, 300, 200, 400, 500, 400};
		float[] vy = {0, 0, -100, 0, 0, -100, 0, 0, -100};
		float[] vz = new float[9];
		Map<String, Object> values = new HashMap<>();
		values.put("getFaceCount", 3);
		values.put("getVerticesX", vx);
		values.put("getVerticesY", vy);
		values.put("getVerticesZ", vz);
		values.put("getFaceIndices1", new int[]{0, 3, 6});
		values.put("getFaceIndices2", new int[]{1, 4, 7});
		values.put("getFaceIndices3", new int[]{2, 5, 8});
		values.put("getFaceColors3", new int[]{100, 100, 100});
		values.put("getFaceTransparencies", new byte[]{50, 50, 50});
		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class},
			(p, m, a) -> values.get(m.getName()));
	}

	@Test
	public void oneSortKeyPerTriangleWritten()
	{
		Zone zone = new Zone();
		// faces 0 and 1 double-sided, face 2 drawn as fake water (opaque buffer): four triangles written, where one key
		// per translucent face would make three
		int[] alphaTriangles = {2, 2, 0};
		int triangle = (3 * Zone.VERT_SIZE) >> 2; // ints per triangle in the buffer
		zone.addAlphaModel(0, model(), 0, 4 * triangle, 0, 0, 0, -1, -1, -1, -1, 0, 0, 1, alphaTriangles);
		int[] keys = zone.alphaModels.get(0).packedFaces;
		assertEquals(4, keys.length);
		// each back triangle sorts with its front one; the two faces' middles differ
		assertEquals(keys[0], keys[1]);
		assertEquals(keys[2], keys[3]);
		assertEquals(false, keys[0] == keys[2]);
	}
}
