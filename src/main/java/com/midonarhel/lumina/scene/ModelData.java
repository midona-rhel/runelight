package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import java.lang.reflect.Array;
import net.runelite.api.DynamicObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;

/**
 * The parts of a model the model uploader reads. {@link #wrap} points at the model's own arrays, for uploads made
 * inside the draw callback; {@link #copy} copies them for uploads made after it returns, when the client may have
 * rebuilt the model in place (animated models share the client's buffers).
 */
public final class ModelData
{
	public int vertexCount;
	public float[] verticesX, verticesY, verticesZ;
	// the game's vertex normals (117 HD's source for smooth faces), null when the model has none
	public int[] normalsX, normalsY, normalsZ;

	public int faceCount;
	public int[] indices1, indices2, indices3;
	public int[] colors1, colors2, colors3;
	public short[] unlitColors;
	byte[] priorities;
	public short[] textures;
	byte[] transparencies;
	byte[] bias;
	byte[] textureFaces;
	int[] texIndices1, texIndices2, texIndices3;

	byte transparency;
	int radius, diameter;
	byte overrideAmount, overrideHue, overrideSaturation, overrideLuminance;

	// copy's arrays, one per array field, kept between models
	private final Object[] owned = new Object[21];

	/**
	 * The model a renderable draws, as SceneUploader finds it: the renderable itself, or an animated object's model;
	 * null when it has none (yet).
	 */
	public static Model modelOf(Renderable r)
	{
		if (r instanceof Model)
		{
			return (Model) r;
		}
		if (r instanceof DynamicObject)
		{
			Model m = ((DynamicObject) r).getModelZbuf();
			if (m != null)
			{
				return m;
			}
		}
		return r != null ? r.getModel() : null;
	}

	public ModelData wrap(Model m)
	{
		vertexCount = m.getVerticesCount();
		verticesX = m.getVerticesX();
		verticesY = m.getVerticesY();
		verticesZ = m.getVerticesZ();
		normalsX = m.getVertexNormalsX();
		normalsY = m.getVertexNormalsY();
		normalsZ = m.getVertexNormalsZ();

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
		normalsX = copy(18, m.getVertexNormalsX(), v);
		normalsY = copy(19, m.getVertexNormalsY(), v);
		normalsZ = copy(20, m.getVertexNormalsZ(), v);

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

	/** A copy of the model's data of its own (the scene dump's frame tape, dev/FrameTape). */
	public static ModelData copyOf(Model m)
	{
		return new ModelData().copy(m);
	}

	/** The data for the scene dump: every array (null kept) and the scalars. */
	public void write(java.io.DataOutputStream out) throws java.io.IOException
	{
		out.writeInt(vertexCount);
		out.writeInt(faceCount);
		Object[] arrays = {verticesX, verticesY, verticesZ, normalsX, normalsY, normalsZ, indices1, indices2, indices3,
			colors1, colors2, colors3, unlitColors, priorities, textures, transparencies, bias, textureFaces, texIndices1,
			texIndices2, texIndices3};
		for (Object a : arrays)
		{
			writeArray(out, a);
		}
		out.writeByte(transparency);
		out.writeInt(radius);
		out.writeInt(diameter);
		out.writeByte(overrideAmount);
		out.writeByte(overrideHue);
		out.writeByte(overrideSaturation);
		out.writeByte(overrideLuminance);
	}

	public static ModelData read(java.io.DataInputStream in) throws java.io.IOException
	{
		ModelData m = new ModelData();
		m.vertexCount = in.readInt();
		m.faceCount = in.readInt();
		m.verticesX = (float[]) readArray(in);
		m.verticesY = (float[]) readArray(in);
		m.verticesZ = (float[]) readArray(in);
		m.normalsX = (int[]) readArray(in);
		m.normalsY = (int[]) readArray(in);
		m.normalsZ = (int[]) readArray(in);
		m.indices1 = (int[]) readArray(in);
		m.indices2 = (int[]) readArray(in);
		m.indices3 = (int[]) readArray(in);
		m.colors1 = (int[]) readArray(in);
		m.colors2 = (int[]) readArray(in);
		m.colors3 = (int[]) readArray(in);
		m.unlitColors = (short[]) readArray(in);
		m.priorities = (byte[]) readArray(in);
		m.textures = (short[]) readArray(in);
		m.transparencies = (byte[]) readArray(in);
		m.bias = (byte[]) readArray(in);
		m.textureFaces = (byte[]) readArray(in);
		m.texIndices1 = (int[]) readArray(in);
		m.texIndices2 = (int[]) readArray(in);
		m.texIndices3 = (int[]) readArray(in);
		m.transparency = in.readByte();
		m.radius = in.readInt();
		m.diameter = in.readInt();
		m.overrideAmount = in.readByte();
		m.overrideHue = in.readByte();
		m.overrideSaturation = in.readByte();
		m.overrideLuminance = in.readByte();
		return m;
	}

	private static void writeArray(java.io.DataOutputStream out, Object a) throws java.io.IOException
	{
		if (a == null)
		{
			out.writeByte(0);
			return;
		}
		int n = Array.getLength(a);
		if (a instanceof float[])
		{
			out.writeByte('F');
			out.writeInt(n);
			for (float v : (float[]) a)
			{
				out.writeFloat(v);
			}
		}
		else if (a instanceof int[])
		{
			out.writeByte('I');
			out.writeInt(n);
			for (int v : (int[]) a)
			{
				out.writeInt(v);
			}
		}
		else if (a instanceof short[])
		{
			out.writeByte('S');
			out.writeInt(n);
			for (short v : (short[]) a)
			{
				out.writeShort(v);
			}
		}
		else
		{
			out.writeByte('B');
			out.writeInt(n);
			out.write((byte[]) a);
		}
	}

	private static Object readArray(java.io.DataInputStream in) throws java.io.IOException
	{
		int kind = in.readByte();
		if (kind == 0)
		{
			return null;
		}
		int n = in.readInt();
		switch (kind)
		{
			case 'F':
			{
				float[] a = new float[n];
				for (int i = 0; i < n; ++i)
				{
					a[i] = in.readFloat();
				}
				return a;
			}
			case 'I':
			{
				int[] a = new int[n];
				for (int i = 0; i < n; ++i)
				{
					a[i] = in.readInt();
				}
				return a;
			}
			case 'S':
			{
				short[] a = new short[n];
				for (int i = 0; i < n; ++i)
				{
					a[i] = in.readShort();
				}
				return a;
			}
			default:
			{
				byte[] a = new byte[n];
				in.readFully(a);
				return a;
			}
		}
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
