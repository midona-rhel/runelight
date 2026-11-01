package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.harness.Fakes;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;

/** A model faked from its data (ModelData), as the upload code reads it: the frame tape's models played back. */
public final class ModelFake
{
	private ModelFake()
	{
	}

	public static Model of(ModelData d)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getVerticesCount", d.vertexCount);
		a.put("getVerticesX", d.verticesX);
		a.put("getVerticesY", d.verticesY);
		a.put("getVerticesZ", d.verticesZ);
		a.put("getVertexNormalsX", d.normalsX);
		a.put("getVertexNormalsY", d.normalsY);
		a.put("getVertexNormalsZ", d.normalsZ);
		a.put("getFaceCount", d.faceCount);
		a.put("getFaceIndices1", d.indices1);
		a.put("getFaceIndices2", d.indices2);
		a.put("getFaceIndices3", d.indices3);
		a.put("getFaceColors1", d.colors1);
		a.put("getFaceColors2", d.colors2);
		a.put("getFaceColors3", d.colors3);
		a.put("getUnlitFaceColors", d.unlitColors);
		a.put("getFaceRenderPriorities", d.priorities);
		a.put("getFaceTextures", d.textures);
		a.put("getFaceTransparencies", d.transparencies);
		a.put("getFaceBias", d.bias);
		a.put("getTextureFaces", d.textureFaces);
		a.put("getTexIndices1", d.texIndices1);
		a.put("getTexIndices2", d.texIndices2);
		a.put("getTexIndices3", d.texIndices3);
		a.put("getTransparency", d.transparency);
		a.put("getRadius", d.radius);
		a.put("getDiameter", d.diameter);
		a.put("getOverrideAmount", d.overrideAmount);
		a.put("getOverrideHue", d.overrideHue);
		a.put("getOverrideSaturation", d.overrideSaturation);
		a.put("getOverrideLuminance", d.overrideLuminance);
		// the bounds were worked out before the tape copied the model
		a.put("calculateBoundsCylinder", (Fakes.Answer) args -> null);
		return Fakes.of(Model.class, a);
	}

	/** The fields the tape keeps, for the tests. */
	public static Object[] fields(ModelData d)
	{
		return new Object[]{d.vertexCount, d.verticesX, d.verticesY, d.verticesZ, d.normalsX, d.normalsY, d.normalsZ,
			d.faceCount, d.indices1, d.indices2,
			d.indices3, d.colors1, d.colors2, d.colors3, d.unlitColors, d.priorities, d.textures, d.transparencies, d.bias,
			d.textureFaces, d.texIndices1, d.texIndices2, d.texIndices3, d.transparency, d.radius, d.diameter,
			d.overrideAmount, d.overrideHue, d.overrideSaturation, d.overrideLuminance};
	}
}
