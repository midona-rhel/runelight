package com.midonarhel.lumina.shading;

import com.google.gson.Gson;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.materials.Material;
import com.midonarhel.lumina.scene.overrides.InheritTileColorType;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FaceShadingTest
{
	// one flat-shaded face: lit colour hue 20 sat 3 light 60, unlit colour hue 20 sat 3 light 90
	private static final int LIT = 20 << 10 | 3 << 7 | 60;
	private static final int UNLIT = 20 << 10 | 3 << 7 | 90;

	private final float[] px = {0, 128, 0}, py = {0, 0, 0}, pz = {0, 0, 128};

	private static Model model()
	{
		Map<String, Object> values = new HashMap<>();
		values.put("getFaceCount", 1);
		values.put("getFaceColors1", new int[]{LIT});
		values.put("getFaceColors2", new int[]{LIT});
		values.put("getFaceColors3", new int[]{-1});
		values.put("getUnlitFaceColors", new short[]{(short) UNLIT});
		values.put("getFaceIndices1", new int[]{0});
		values.put("getFaceIndices2", new int[]{1});
		values.put("getFaceIndices3", new int[]{2});
		values.put("getVerticesY", new float[]{0, 0, 0});
		return (Model) Proxy.newProxyInstance(Model.class.getClassLoader(), new Class<?>[]{Model.class}, (proxy, method, args) ->
		{
			Object v = values.get(method.getName());
			if (v != null)
			{
				return v;
			}
			Class<?> r = method.getReturnType();
			if (r == byte.class)
			{
				return (byte) 0;
			}
			if (r == int.class)
			{
				return 0;
			}
			if (r == boolean.class)
			{
				return false;
			}
			if (r == float.class)
			{
				return 0f;
			}
			return null;
		});
	}

	private static ModelOverride override(String json)
	{
		ModelOverride o = new Gson().fromJson(json, ModelOverride.class);
		o.normalize();
		return o;
	}

	@After
	public void restore()
	{
		DiffuseColors.colorMode = ColorMode.HD117;
		DiffuseColors.shadingMode = ShadingMode.DEFAULT;
		HdSettings.recolorGroundObjects = true;
	}

	@Test
	public void vanillaModeKeepsOnlyTheInheritedTileColor()
	{
		ModelOverride full = override("{\"description\":\"t\",\"hide\":true,\"setLightness\":5,\"flatNormals\":true,"
			+ "\"rotate\":90,\"heightOffset\":-10,\"doubleSidedFaces\":true,\"inheritTileColorType\":\"UNDERLAY\"}");
		ModelOverride plain = override("{\"description\":\"t\",\"hide\":true,\"setLightness\":5}");

		DiffuseColors.colorMode = ColorMode.HD117;
		assertSame(full, DiffuseColors.modelOverride(full));

		DiffuseColors.colorMode = ColorMode.VANILLA;
		ModelOverride reduced = DiffuseColors.modelOverride(full);
		assertFalse(reduced.hide);
		assertFalse(reduced.flatNormals);
		assertFalse(reduced.doubleSidedFaces);
		assertFalse(reduced.modifiesColor);
		assertEquals(0, reduced.rotate);
		assertEquals(0, reduced.heightOffset);
		assertEquals(InheritTileColorType.UNDERLAY, reduced.inheritTileColorType);
		assertSame(ModelOverride.NONE, DiffuseColors.modelOverride(plain));
	}

	@Test
	public void vanillaModeIgnoresOverridesAndUsesUnlitColors()
	{
		ModelOverride o = override("{\"description\":\"t\",\"hide\":true,\"setLightness\":5,\"doubleSidedFaces\":true}");
		FaceShading fs = new FaceShading();

		// 117 HD mode (shading mode Vanilla, so no undo of vanilla shading): hidden by the override
		DiffuseColors.shadingMode = ShadingMode.VANILLA;
		fs.setModel(model(), o, 0, null, false, px, py, pz);
		assertFalse(fs.shade(0));

		// Vanilla colour mode: drawn, the client's unlit colour untouched, single-sided, no material
		DiffuseColors.colorMode = ColorMode.VANILLA;
		fs.setModel(model(), o, 0, null, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(UNLIT, fs.color1);
		assertEquals(UNLIT, fs.color2);
		assertEquals(UNLIT, fs.color3);
		assertFalse(fs.doubleSided);
		assertSame(Material.NONE, fs.material);
	}

	@Test
	public void hd117ModeAppliesTheColorOverride()
	{
		ModelOverride o = override("{\"description\":\"t\",\"setLightness\":5}");
		FaceShading fs = new FaceShading();
		DiffuseColors.shadingMode = ShadingMode.VANILLA;
		fs.setModel(model(), o, 0, null, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(20 << 10 | 3 << 7 | 5, fs.color1);
	}

	@Test
	public void groundMatchedFoliageIsItsOwnSettingInBothModes()
	{
		ModelOverride grass = override("{\"description\":\"t\",\"inheritTileColorType\":\"UNDERLAY\"}");
		final int tile = 30 << 10 | 2 << 7 | 70;
		FaceShading.TileColorSource tiles = type -> tile;
		FaceShading fs = new FaceShading();
		DiffuseColors.shadingMode = ShadingMode.VANILLA;

		DiffuseColors.colorMode = ColorMode.VANILLA;
		fs.setModel(model(), grass, 0, tiles, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(tile, fs.color1);

		DiffuseColors.colorMode = ColorMode.HD117;
		fs.setModel(model(), grass, 0, tiles, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(tile, fs.color1);

		HdSettings.recolorGroundObjects = false;
		fs.setModel(model(), grass, 0, tiles, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(LIT, fs.color1);

		DiffuseColors.colorMode = ColorMode.VANILLA;
		fs.setModel(model(), grass, 0, tiles, false, px, py, pz);
		assertTrue(fs.shade(0));
		assertEquals(UNLIT, fs.color1);
	}
}
