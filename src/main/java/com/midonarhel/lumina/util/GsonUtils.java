/*
 * Gson hacks.
 * Written in 2025 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.midonarhel.lumina.util;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The parts of 117 HD's GsonUtils the scene data files need: lenient parsing, readable error locations, and the
 * degrees-to-radians adapter used by environment sun angles. Exporting (writing JSON) is not supported.
 */
public final class GsonUtils
{
	private GsonUtils()
	{
	}

	public static String location(JsonReader in)
	{
		var str = in.toString();
		int i = str.indexOf(" at ");
		if (i != -1)
		{
			str = str.substring(i + 4);
		}
		return str;
	}

	/**
	 * Where the scene data files (areas, overrides, environments) live on the classpath.
	 */
	public static final String SCENE_DATA_PATH = "/com/midonarhel/lumina/scene/";

	/**
	 * Parses one of the scene data files leniently (they contain comments).
	 */
	public static <T> T loadResource(Gson gson, String fileName, Class<T> type) throws IOException
	{
		String path = SCENE_DATA_PATH + fileName;
		try (InputStream in = GsonUtils.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				throw new IOException("Missing resource: " + path);
			}
			var reader = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			reader.setLenient(true);
			T result = gson.fromJson(reader, type);
			if (result == null)
			{
				throw new IOException("Empty or invalid: " + path);
			}
			return result;
		}
	}

	/**
	 * Reads a float or float array given in degrees as radians.
	 */
	public static class DegreesToRadians implements TypeAdapterFactory
	{
		@Override
		@SuppressWarnings("unchecked")
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken)
		{
			Class<?> raw = typeToken.getRawType();
			if (raw == float[].class)
			{
				return (TypeAdapter<T>) new TypeAdapter<float[]>()
				{
					@Override
					public float[] read(JsonReader in) throws IOException
					{
						if (in.peek() == JsonToken.NULL)
						{
							in.nextNull();
							return null;
						}
						float[] values = new float[8];
						int n = 0;
						in.beginArray();
						while (in.hasNext())
						{
							float v = (float) Math.toRadians(in.nextDouble());
							if (n == values.length)
							{
								values = java.util.Arrays.copyOf(values, n * 2);
							}
							values[n++] = v;
						}
						in.endArray();
						return java.util.Arrays.copyOf(values, n);
					}

					@Override
					public void write(JsonWriter out, float[] value)
					{
						throw new UnsupportedOperationException();
					}
				};
			}
			if (raw == float.class || raw == Float.class)
			{
				return (TypeAdapter<T>) new TypeAdapter<Float>()
				{
					@Override
					public Float read(JsonReader in) throws IOException
					{
						if (in.peek() == JsonToken.NULL)
						{
							in.nextNull();
							return null;
						}
						return (float) Math.toRadians(in.nextDouble());
					}

					@Override
					public void write(JsonWriter out, Float value)
					{
						throw new UnsupportedOperationException();
					}
				};
			}
			return null;
		}
	}
}
