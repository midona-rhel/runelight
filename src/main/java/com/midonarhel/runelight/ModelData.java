package com.midonarhel.runelight;

import java.lang.reflect.Array;
import net.runelite.api.Model;

/**
 * The parts of a model the model uploader reads. {@link #wrap} points at the model's own arrays, for uploads made
 * inside the draw callback; {@link #copy} copies them for uploads made after it returns, when the client may have
 * rebuilt the model in place (animated models share the client's buffers).
 */
final class ModelData
{
	int vertexCount;
	float[] verticesX, verticesY, verticesZ;

	int faceCount;
	int[] indices1, indices2, indices3;
	int[] colors1, colors2, colors3;
	short[] unlitColors;
	byte[] priorities;
	short[] textures;
	byte[] transparencies;
	byte[] bias;
	byte[] textureFaces;
	int[] texIndices1, texIndices2, texIndices3;

	byte transparency;
	int radius, diameter;
	byte overrideAmount, overrideHue, overrideSaturation, overrideLuminance;

	// copy's arrays, one per array field, kept between models
	private final Object[] owned = new Object[18];

	ModelData wrap(Model m)
	{
		vertexCount = m.getVerticesCount();
		verticesX = m.getVerticesX();
		verticesY = m.getVerticesY();
		verticesZ = m.getVerticesZ();

		faceCount = m.getFaceCount();
		indices1 = m.getFaceIndices1();
		indices2 = m.getFaceIndices2();
		indices3 = m.getFaceIndices3();
		colors1 = m.getFaceColors1();
		colors2 = m.getFaceColors2();
		colors3 = m.getFaceColors3();
		unlitColors = m.getUnlitFaceColors();
		priorities = m.getFaceRenderPriorities();
		textures = m.getFaceTextures();
		transparencies = m.getFaceTransparencies();
		bias = m.getFaceBias();
		textureFaces = m.getTextureFaces();
		texIndices1 = m.getTexIndices1();
		texIndices2 = m.getTexIndices2();
		texIndices3 = m.getTexIndices3();

		scalars(m);
		return this;
	}

	ModelData copy(Model m)
	{
		int v = vertexCount = m.getVerticesCount();
		verticesX = copy(0, m.getVerticesX(), v);
		verticesY = copy(1, m.getVerticesY(), v);
		verticesZ = copy(2, m.getVerticesZ(), v);

		int f = faceCount = m.getFaceCount();
		indices1 = copy(3, m.getFaceIndices1(), f);
		indices2 = copy(4, m.getFaceIndices2(), f);
		indices3 = copy(5, m.getFaceIndices3(), f);
		colors1 = copy(6, m.getFaceColors1(), f);
		colors2 = copy(7, m.getFaceColors2(), f);
		colors3 = copy(8, m.getFaceColors3(), f);
		unlitColors = copy(9, m.getUnlitFaceColors(), f);
		priorities = copy(10, m.getFaceRenderPriorities(), f);
		textures = copy(11, m.getFaceTextures(), f);
		transparencies = copy(12, m.getFaceTransparencies(), f);
		bias = copy(13, m.getFaceBias(), f);
		textureFaces = copy(14, m.getTextureFaces(), f);
		// texture faces index these as unsigned bytes
		texIndices1 = copy(15, m.getTexIndices1(), 256);
		texIndices2 = copy(16, m.getTexIndices2(), 256);
		texIndices3 = copy(17, m.getTexIndices3(), 256);

		scalars(m);
		return this;
	}

	private void scalars(Model m)
	{
		transparency = m.getTransparency();
		radius = m.getRadius();
		diameter = m.getDiameter();
		overrideAmount = m.getOverrideAmount();
		overrideHue = m.getOverrideHue();
		overrideSaturation = m.getOverrideSaturation();
		overrideLuminance = m.getOverrideLuminance();
	}

	/** The first n elements of src (fewer if it is shorter) in this field's own array; null stays null. */
	@SuppressWarnings("unchecked")
	private <T> T copy(int field, T src, int n)
	{
		if (src == null)
		{
			return null;
		}
		n = Math.min(n, Array.getLength(src));
		Object dst = owned[field];
		if (dst == null || Array.getLength(dst) < n)
		{
			owned[field] = dst = Array.newInstance(src.getClass().getComponentType(), n);
		}
		System.arraycopy(src, 0, dst, 0, n);
		return (T) dst;
	}
}
