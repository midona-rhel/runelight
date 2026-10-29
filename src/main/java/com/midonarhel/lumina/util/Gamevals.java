/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.util;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Name to ID lookups for the gameval names used in the scene data files, read from RuneLite's
 * {@code net.runelite.api.gameval.*ID} constants. This replaces 117 HD's GamevalManager and its 5 MB gamevals.json:
 * every name the data files use is a constant name in those classes.
 */
@Slf4j
public final class Gamevals
{
	private static Map<String, Integer> npcs, objects, spotanims, varbits, varps;

	private Gamevals()
	{
	}

	public static synchronized Map<String, Integer> npcs()
	{
		if (npcs == null)
		{
			npcs = read(NpcID.class);
		}
		return npcs;
	}

	public static synchronized Map<String, Integer> objects()
	{
		if (objects == null)
		{
			objects = read(ObjectID.class);
		}
		return objects;
	}

	public static synchronized Map<String, Integer> spotanims()
	{
		if (spotanims == null)
		{
			spotanims = read(SpotanimID.class);
		}
		return spotanims;
	}

	public static synchronized Map<String, Integer> varbits()
	{
		if (varbits == null)
		{
			varbits = read(VarbitID.class);
		}
		return varbits;
	}

	public static synchronized Map<String, Integer> varps()
	{
		if (varps == null)
		{
			varps = read(VarPlayerID.class);
		}
		return varps;
	}

	/**
	 * Drops the lookup tables once the data files are parsed; they are rebuilt on demand.
	 */
	public static synchronized void clear()
	{
		npcs = objects = spotanims = varbits = varps = null;
	}

	private static Map<String, Integer> read(Class<?> cls)
	{
		Map<String, Integer> map = new HashMap<>();
		try
		{
			// The constants are compile-time constants, so their values are in the class files. Reading them there is
			// far faster than reflection (Field.getInt costs ~0.3 ms per field on Java 18+, ObjectID has 63k fields)
			ConstantReader.read(cls, map);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Unable to read gameval class file {}, using reflection", cls.getSimpleName(), ex);
			map.clear();
			readReflectively(cls, map);
		}
		log.debug("Read {} gamevals from {}", map.size(), cls.getSimpleName());
		return Collections.unmodifiableMap(map);
	}

	private static void readReflectively(Class<?> cls, Map<String, Integer> map)
	{
		// getFields includes the public constants inherited from package-private split classes (ObjectID extends ObjectID1)
		for (Field field : cls.getFields())
		{
			int mod = field.getModifiers();
			if (!Modifier.isStatic(mod) || field.getType() != int.class)
			{
				continue;
			}
			try
			{
				if (!Modifier.isPublic(field.getDeclaringClass().getModifiers()))
				{
					field.setAccessible(true);
				}
				map.put(field.getName(), field.getInt(null));
			}
			catch (IllegalAccessException | RuntimeException ex)
			{
				log.debug("Unable to read gameval {}.{}", cls.getSimpleName(), field.getName(), ex);
			}
		}
	}

	/**
	 * Reads the {@code static final int} constants (ConstantValue attributes) of a class and its superclasses from
	 * their class files.
	 */
	private static final class ConstantReader
	{
		static void read(Class<?> cls, Map<String, Integer> out) throws IOException
		{
			String name = cls.getName().replace('.', '/');
			while (name != null && !name.startsWith("java/"))
			{
				name = readClass(cls, name, out);
			}
		}

		/**
		 * @return the internal name of the superclass
		 */
		private static String readClass(Class<?> loader, String internalName, Map<String, Integer> out) throws IOException
		{
			byte[] bytes;
			try (InputStream in = loader.getResourceAsStream("/" + internalName + ".class"))
			{
				if (in == null)
				{
					throw new IOException("Missing class file " + internalName);
				}
				bytes = in.readAllBytes();
			}

			DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes));
			if (data.readInt() != 0xCAFEBABE)
			{
				throw new IOException("Not a class file: " + internalName);
			}
			data.readUnsignedShort(); // minor
			data.readUnsignedShort(); // major

			int poolCount = data.readUnsignedShort();
			String[] utf8 = new String[poolCount];
			int[] ints = new int[poolCount];
			int[] classNames = new int[poolCount];
			for (int i = 1; i < poolCount; i++)
			{
				int tag = data.readUnsignedByte();
				switch (tag)
				{
					case 1: // Utf8
						utf8[i] = data.readUTF();
						break;
					case 3: // Integer
						ints[i] = data.readInt();
						break;
					case 4: // Float
					case 9: // Fieldref
					case 10: // Methodref
					case 11: // InterfaceMethodref
					case 12: // NameAndType
					case 17: // Dynamic
					case 18: // InvokeDynamic
						data.readInt();
						break;
					case 5: // Long
					case 6: // Double
						data.readLong();
						i++; // takes two entries
						break;
					case 7: // Class
						classNames[i] = data.readUnsignedShort();
						break;
					case 8: // String
					case 16: // MethodType
					case 19: // Module
					case 20: // Package
						data.readUnsignedShort();
						break;
					case 15: // MethodHandle
						data.readUnsignedByte();
						data.readUnsignedShort();
						break;
					default:
						throw new IOException("Unknown constant pool tag " + tag + " in " + internalName);
				}
			}

			data.readUnsignedShort(); // access flags
			data.readUnsignedShort(); // this class
			int superClass = data.readUnsignedShort();
			int interfaces = data.readUnsignedShort();
			for (int i = 0; i < interfaces; i++)
			{
				data.readUnsignedShort();
			}

			final int staticFinal = Modifier.STATIC | Modifier.FINAL;
			int fields = data.readUnsignedShort();
			for (int i = 0; i < fields; i++)
			{
				int access = data.readUnsignedShort();
				String fieldName = utf8[data.readUnsignedShort()];
				String descriptor = utf8[data.readUnsignedShort()];
				int attributes = data.readUnsignedShort();
				for (int a = 0; a < attributes; a++)
				{
					String attributeName = utf8[data.readUnsignedShort()];
					int length = data.readInt();
					if ("ConstantValue".equals(attributeName) && "I".equals(descriptor) && (access & staticFinal) == staticFinal)
					{
						out.putIfAbsent(fieldName, ints[data.readUnsignedShort()]);
					}
					else
					{
						data.skipBytes(length);
					}
				}
			}

			return superClass == 0 ? null : utf8[classNames[superClass]];
		}
	}

	private abstract static class Adapter extends TypeAdapter<Set<Integer>>
	{
		abstract Map<String, Integer> names();

		@Override
		public Set<Integer> read(JsonReader in) throws IOException
		{
			var map = names();
			Set<Integer> result = new HashSet<>();
			in.beginArray();
			while (in.hasNext())
			{
				switch (in.peek())
				{
					case NUMBER:
						result.add(in.nextInt());
						break;
					case STRING:
					{
						String name = in.nextString();
						Integer id = map.get(name);
						if (id == null)
						{
							log.warn("Unknown gameval {} at {}", name, GsonUtils.location(in));
						}
						else
						{
							result.add(id);
						}
						break;
					}
					default:
						log.warn("Unexpected gameval token {} at {}", in.peek(), GsonUtils.location(in));
						in.skipValue();
						break;
				}
			}
			in.endArray();
			return result;
		}

		@Override
		public void write(JsonWriter out, Set<Integer> value)
		{
			throw new UnsupportedOperationException();
		}
	}

	public static class NpcAdapter extends Adapter
	{
		@Override
		Map<String, Integer> names()
		{
			return npcs();
		}
	}

	public static class ObjectAdapter extends Adapter
	{
		@Override
		Map<String, Integer> names()
		{
			return objects();
		}
	}

	public static class SpotanimAdapter extends Adapter
	{
		@Override
		Map<String, Integer> names()
		{
			return spotanims();
		}
	}
}
