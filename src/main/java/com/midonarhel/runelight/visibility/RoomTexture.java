package com.midonarhel.runelight.visibility;

import com.midonarhel.runelight.*;

import java.nio.FloatBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The room map on the GPU (rooms.glsl): an RGBA32F texture array of RoomMap.LAYERS layers, a texel per tile. Its own
 * texture, not part of the light buffer, whose size GL 3.3 holds to 65536 texels (a scene's map is 135424 texels).
 * Sent when the map or its version changes, from the texels RoomMap packs on its worker; the map sent before stands
 * until they are ready.
 */
public final class RoomTexture
{
	private int texture;
	private RoomMap sent;
	private int sentVersion = -1;
	private FloatBuffer staging;
	private final float[] header = new float[3];

	/** The rooms now (null: none). */
	public void update(RoomMap rooms)
	{
		if (rooms == null)
		{
			sent = null;
			header[0] = header[1] = header[2] = 0;
			return;
		}
		if (rooms == sent && rooms.getVersion() == sentVersion)
		{
			return;
		}
		if (!rooms.packedReady())
		{
			rooms.packAsync();
			return;
		}
		int floats = rooms.floatCount();
		if (staging == null || staging.capacity() < floats)
		{
			staging = BufferUtils.createFloatBuffer(floats);
		}
		staging.clear();
		rooms.texels(staging);
		staging.flip();
		if (texture == 0)
		{
			texture = glGenTextures();
		}
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA32F, rooms.size, rooms.size, RoomMap.LAYERS, 0, GL_RGBA, GL_FLOAT,
			staging);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		sent = rooms;
		sentVersion = rooms.getVersion();
		header[0] = 1;
		header[1] = rooms.size;
		header[2] = rooms.offset;
	}

	/** rooms.glsl's roomHeader: 1 when there is a map, its size, its offset. */
	public float[] header()
	{
		return header;
	}

	public void bind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		glActiveTexture(GL_TEXTURE0);
	}

	public static void unbind(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	public void free()
	{
		if (texture != 0)
		{
			glDeleteTextures(texture);
			texture = 0;
		}
		sent = null;
		sentVersion = -1;
		header[0] = header[1] = header[2] = 0;
	}
}
