package com.midonarhel.lumina.dev;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes and reads the renderer's plain data (the scene's walls, rooms, voxels, water and fog ground, the lamps) field by
 * field, for the scene dump (SceneDump): whatever a class of ours holds in primitives, arrays, strings, enums, the common
 * collections, NIO buffers and other classes of ours, shared references and cycles kept. Anything else (the client's
 * objects, threads, lambdas, loggers) is written as null and named in {@link Writer#skipped}, so a dump says what it
 * left out. Objects are made without their constructors; their fields are set by name, those missing in the data keep
 * Java's defaults.
 */
public final class PlainData
{
	private static final String OURS = "com.midonarhel.lumina.";

	private static final int NULL = 0, REF = 1, BOOL = 2, BYTE = 3, SHORT = 4, CHAR = 5, INT = 6, LONG = 7, FLOAT = 8,
		DOUBLE = 9, STRING = 10, ENUM = 11, PRIM_ARRAY = 12, OBJ_ARRAY = 13, LIST = 14, MAP = 15, SET = 16, OBJECT = 17,
		BUFFER = 18, BITSET = 19, ATOMIC_INT = 20, ATOMIC_LONG = 21, QUEUE = 22;

	private PlainData()
	{
	}

	public static final class Writer
	{
		private final DataOutputStream out;
		private final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
		/** Where something was left out: the field's path and the type that was not written. */
		public final List<String> skipped = new ArrayList<>();

		public Writer(DataOutputStream out)
		{
			this.out = out;
		}

		public void write(Object v) throws IOException
		{
			write(v, "");
		}

		private void write(Object v, String path) throws IOException
		{
			if (v == null)
			{
				out.writeByte(NULL);
				return;
			}
			Class<?> c = v.getClass();
			if (c == Boolean.class)
			{
				out.writeByte(BOOL);
				out.writeBoolean((Boolean) v);
				return;
			}
			if (c == Byte.class)
			{
				out.writeByte(BYTE);
				out.writeByte((Byte) v);
				return;
			}
			if (c == Short.class)
			{
				out.writeByte(SHORT);
				out.writeShort((Short) v);
				return;
			}
			if (c == Character.class)
			{
				out.writeByte(CHAR);
				out.writeChar((Character) v);
				return;
			}
			if (c == Integer.class)
			{
				out.writeByte(INT);
				out.writeInt((Integer) v);
				return;
			}
			if (c == Long.class)
			{
				out.writeByte(LONG);
				out.writeLong((Long) v);
				return;
			}
			if (c == Float.class)
			{
				out.writeByte(FLOAT);
				out.writeFloat((Float) v);
				return;
			}
			if (c == Double.class)
			{
				out.writeByte(DOUBLE);
				out.writeDouble((Double) v);
				return;
			}
			if (c == String.class)
			{
				out.writeByte(STRING);
				out.writeUTF((String) v);
				return;
			}
			if (v instanceof Enum)
			{
				out.writeByte(ENUM);
				out.writeUTF(((Enum<?>) v).getDeclaringClass().getName());
				out.writeUTF(((Enum<?>) v).name());
				return;
			}
			if (!writable(c))
			{
				skipped.add(path + ": " + c.getName());
				out.writeByte(NULL);
				return;
			}
			Integer id = ids.get(v);
			if (id != null)
			{
				out.writeByte(REF);
				out.writeInt(id);
				return;
			}
			ids.put(v, ids.size());
			if (c.isArray())
			{
				Class<?> ct = c.getComponentType();
				int n = Array.getLength(v);
				if (ct.isPrimitive())
				{
					out.writeByte(PRIM_ARRAY);
					writePrimitiveArray(v, ct, n);
				}
				else
				{
					out.writeByte(OBJ_ARRAY);
					out.writeUTF(ct.getName());
					out.writeInt(n);
					for (int i = 0; i < n; ++i)
					{
						write(Array.get(v, i), path + "[" + i + "]");
					}
				}
			}
			else if (v instanceof Buffer)
			{
				out.writeByte(BUFFER);
				writeBuffer((Buffer) v);
			}
			else if (v instanceof BitSet)
			{
				out.writeByte(BITSET);
				long[] words = ((BitSet) v).toLongArray();
				out.writeInt(words.length);
				for (long w : words)
				{
					out.writeLong(w);
				}
			}
			else if (v instanceof AtomicInteger)
			{
				out.writeByte(ATOMIC_INT);
				out.writeInt(((AtomicInteger) v).get());
			}
			else if (v instanceof AtomicLong)
			{
				out.writeByte(ATOMIC_LONG);
				out.writeLong(((AtomicLong) v).get());
			}
			else if (v instanceof Map)
			{
				Map<?, ?> m = (Map<?, ?>) v;
				out.writeByte(MAP);
				out.writeUTF(c.getName());
				out.writeInt(m.size());
				int i = 0;
				for (Map.Entry<?, ?> e : m.entrySet())
				{
					write(e.getKey(), path + "{" + i + "}");
					write(e.getValue(), path + "{" + i + "}=");
					i++;
				}
			}
			else if (v instanceof Collection)
			{
				Collection<?> l = (Collection<?>) v;
				out.writeByte(v instanceof Set ? SET : v instanceof List ? LIST : QUEUE);
				out.writeUTF(c.getName());
				out.writeInt(l.size());
				int i = 0;
				for (Object e : l)
				{
					write(e, path + "[" + i++ + "]");
				}
			}
			else
			{
				out.writeByte(OBJECT);
				out.writeUTF(c.getName());
				List<Field> fields = fields(c);
				out.writeInt(fields.size());
				for (Field f : fields)
				{
					out.writeUTF(f.getName());
					Object fv;
					try
					{
						fv = f.get(v);
					}
					catch (IllegalAccessException ex)
					{
						throw new IOException(ex);
					}
					write(f.isSynthetic() ? null : fv, path + "." + f.getName());
				}
			}
		}

		private void writePrimitiveArray(Object v, Class<?> ct, int n) throws IOException
		{
			out.writeUTF(ct.getName());
			out.writeInt(n);
			if (ct == int.class)
			{
				for (int x : (int[]) v)
				{
					out.writeInt(x);
				}
			}
			else if (ct == float.class)
			{
				for (float x : (float[]) v)
				{
					out.writeFloat(x);
				}
			}
			else if (ct == byte.class)
			{
				out.write((byte[]) v);
			}
			else if (ct == short.class)
			{
				for (short x : (short[]) v)
				{
					out.writeShort(x);
				}
			}
			else if (ct == boolean.class)
			{
				for (boolean x : (boolean[]) v)
				{
					out.writeBoolean(x);
				}
			}
			else if (ct == long.class)
			{
				for (long x : (long[]) v)
				{
					out.writeLong(x);
				}
			}
			else if (ct == double.class)
			{
				for (double x : (double[]) v)
				{
					out.writeDouble(x);
				}
			}
			else if (ct == char.class)
			{
				for (char x : (char[]) v)
				{
					out.writeChar(x);
				}
			}
		}

		private void writeBuffer(Buffer b) throws IOException
		{
			// the whole buffer to its capacity, with its position and limit
			out.writeBoolean(b.isDirect());
			out.writeInt(b.position());
			out.writeInt(b.limit());
			out.writeInt(b.capacity());
			if (b instanceof FloatBuffer)
			{
				out.writeByte('F');
				FloatBuffer f = (FloatBuffer) b;
				for (int i = 0; i < f.capacity(); ++i)
				{
					out.writeFloat(f.get(i));
				}
			}
			else if (b instanceof IntBuffer)
			{
				out.writeByte('I');
				IntBuffer f = (IntBuffer) b;
				for (int i = 0; i < f.capacity(); ++i)
				{
					out.writeInt(f.get(i));
				}
			}
			else if (b instanceof ShortBuffer)
			{
				out.writeByte('S');
				ShortBuffer f = (ShortBuffer) b;
				for (int i = 0; i < f.capacity(); ++i)
				{
					out.writeShort(f.get(i));
				}
			}
			else if (b instanceof ByteBuffer)
			{
				out.writeByte('B');
				ByteBuffer f = (ByteBuffer) b;
				for (int i = 0; i < f.capacity(); ++i)
				{
					out.writeByte(f.get(i));
				}
			}
			else
			{
				throw new IOException("buffer type " + b.getClass());
			}
		}
	}

	public static final class Reader
	{
		private final DataInputStream in;
		private final List<Object> objects = new ArrayList<>();

		public Reader(DataInputStream in)
		{
			this.in = in;
		}

		public Object read() throws IOException
		{
			int tag = in.readByte();
			switch (tag)
			{
				case NULL:
					return null;
				case REF:
					return objects.get(in.readInt());
				case BOOL:
					return in.readBoolean();
				case BYTE:
					return in.readByte();
				case SHORT:
					return in.readShort();
				case CHAR:
					return in.readChar();
				case INT:
					return in.readInt();
				case LONG:
					return in.readLong();
				case FLOAT:
					return in.readFloat();
				case DOUBLE:
					return in.readDouble();
				case STRING:
					return in.readUTF();
				case ENUM:
				{
					Class<?> c = type(in.readUTF());
					String name = in.readUTF();
					for (Object e : c.getEnumConstants())
					{
						if (((Enum<?>) e).name().equals(name))
						{
							return e;
						}
					}
					throw new IOException("no " + name + " in " + c);
				}
				case PRIM_ARRAY:
					return register(readPrimitiveArray());
				case OBJ_ARRAY:
				{
					Class<?> ct = type(in.readUTF());
					int n = in.readInt();
					Object a = Array.newInstance(ct, n);
					register(a);
					for (int i = 0; i < n; ++i)
					{
						Array.set(a, i, read());
					}
					return a;
				}
				case BUFFER:
					return register(readBuffer());
				case BITSET:
				{
					long[] words = new long[in.readInt()];
					for (int i = 0; i < words.length; ++i)
					{
						words[i] = in.readLong();
					}
					return register(BitSet.valueOf(words));
				}
				case ATOMIC_INT:
					return register(new AtomicInteger(in.readInt()));
				case ATOMIC_LONG:
					return register(new AtomicLong(in.readLong()));
				case MAP:
				{
					Map<Object, Object> m = newMap(in.readUTF());
					register(m);
					int n = in.readInt();
					for (int i = 0; i < n; ++i)
					{
						Object k = read();
						m.put(k, read());
					}
					return m;
				}
				case LIST:
				case SET:
				case QUEUE:
				{
					Collection<Object> l = newCollection(in.readUTF(), tag);
					register(l);
					int n = in.readInt();
					for (int i = 0; i < n; ++i)
					{
						l.add(read());
					}
					return l;
				}
				case OBJECT:
				{
					Class<?> c = type(in.readUTF());
					Object o = instantiate(c);
					register(o);
					int n = in.readInt();
					Map<String, Field> byName = new HashMap<>();
					for (Field f : fields(c))
					{
						byName.putIfAbsent(f.getName(), f);
					}
					for (int i = 0; i < n; ++i)
					{
						String name = in.readUTF();
						Object v = read();
						Field f = byName.get(name);
						if (f == null || v == null && f.getType().isPrimitive())
						{
							continue;
						}
						try
						{
							f.set(o, v);
						}
						catch (IllegalAccessException | IllegalArgumentException ex)
						{
							throw new IOException("setting " + c.getName() + "." + name, ex);
						}
					}
					return o;
				}
				default:
					throw new IOException("bad tag " + tag);
			}
		}

		private Object register(Object o)
		{
			objects.add(o);
			return o;
		}

		private Object readPrimitiveArray() throws IOException
		{
			String type = in.readUTF();
			int n = in.readInt();
			switch (type)
			{
				case "int":
				{
					int[] a = new int[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readInt();
					}
					return a;
				}
				case "float":
				{
					float[] a = new float[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readFloat();
					}
					return a;
				}
				case "byte":
				{
					byte[] a = new byte[n];
					in.readFully(a);
					return a;
				}
				case "short":
				{
					short[] a = new short[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readShort();
					}
					return a;
				}
				case "boolean":
				{
					boolean[] a = new boolean[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readBoolean();
					}
					return a;
				}
				case "long":
				{
					long[] a = new long[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readLong();
					}
					return a;
				}
				case "double":
				{
					double[] a = new double[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readDouble();
					}
					return a;
				}
				case "char":
				{
					char[] a = new char[n];
					for (int i = 0; i < n; ++i)
					{
						a[i] = in.readChar();
					}
					return a;
				}
				default:
					throw new IOException("bad array type " + type);
			}
		}

		private Buffer readBuffer() throws IOException
		{
			boolean direct = in.readBoolean();
			int position = in.readInt(), limit = in.readInt(), capacity = in.readInt();
			int kind = in.readByte();
			int bytesPer = kind == 'F' || kind == 'I' ? 4 : kind == 'S' ? 2 : 1;
			ByteBuffer bytes = (direct ? ByteBuffer.allocateDirect(capacity * bytesPer) : ByteBuffer.allocate(capacity * bytesPer))
				.order(ByteOrder.nativeOrder());
			Buffer b;
			switch (kind)
			{
				case 'F':
				{
					FloatBuffer f = bytes.asFloatBuffer();
					for (int i = 0; i < capacity; ++i)
					{
						f.put(i, in.readFloat());
					}
					b = f;
					break;
				}
				case 'I':
				{
					IntBuffer f = bytes.asIntBuffer();
					for (int i = 0; i < capacity; ++i)
					{
						f.put(i, in.readInt());
					}
					b = f;
					break;
				}
				case 'S':
				{
					ShortBuffer f = bytes.asShortBuffer();
					for (int i = 0; i < capacity; ++i)
					{
						f.put(i, in.readShort());
					}
					b = f;
					break;
				}
				case 'B':
					for (int i = 0; i < capacity; ++i)
					{
						bytes.put(i, in.readByte());
					}
					b = bytes;
					break;
				default:
					throw new IOException("bad buffer kind " + kind);
			}
			b.limit(limit);
			b.position(position);
			return b;
		}
	}

	/** Whether a value of this class is written as data (else as null, named among the skipped). */
	static boolean writable(Class<?> c)
	{
		if (c.isArray())
		{
			Class<?> ct = c;
			while (ct.isArray())
			{
				ct = ct.getComponentType();
			}
			return ct.isPrimitive() || ct == Object.class || writable(ct) || ct.isInterface() || ct.isEnum()
				|| ct == String.class || Number.class.isAssignableFrom(ct) || ct == Boolean.class;
		}
		if (c.isSynthetic() || c.getName().contains("$$Lambda") || java.lang.reflect.Proxy.isProxyClass(c))
		{
			return false;
		}
		if (Buffer.class.isAssignableFrom(c) || c == BitSet.class || c == AtomicInteger.class || c == AtomicLong.class)
		{
			return true;
		}
		if (Map.class.isAssignableFrom(c) || Collection.class.isAssignableFrom(c))
		{
			return c.getName().startsWith("java.util.") && !c.getName().startsWith("java.util.concurrent.")
				|| c.getName().startsWith("java.util.concurrent.CopyOnWrite") || c.getName().startsWith("java.util.concurrent.Concurrent");
		}
		return c.getName().startsWith(OURS) && !c.isInterface();
	}

	/** The instance fields of c and its superclasses of ours, accessible. */
	public static List<Field> fields(Class<?> c)
	{
		List<Field> out = new ArrayList<>();
		for (Class<?> k = c; k != null && k.getName().startsWith(OURS); k = k.getSuperclass())
		{
			for (Field f : k.getDeclaredFields())
			{
				if (Modifier.isStatic(f.getModifiers()))
				{
					continue;
				}
				f.setAccessible(true);
				out.add(f);
			}
		}
		return out;
	}

	private static Class<?> type(String name) throws IOException
	{
		switch (name)
		{
			case "int":
				return int.class;
			case "float":
				return float.class;
			case "byte":
				return byte.class;
			case "short":
				return short.class;
			case "boolean":
				return boolean.class;
			case "long":
				return long.class;
			case "double":
				return double.class;
			case "char":
				return char.class;
			default:
				try
				{
					return Class.forName(name, false, PlainData.class.getClassLoader());
				}
				catch (ClassNotFoundException ex)
				{
					throw new IOException(ex);
				}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> newMap(String cls)
	{
		switch (cls)
		{
			case "java.util.TreeMap":
				return new TreeMap<>();
			case "java.util.IdentityHashMap":
				return new IdentityHashMap<>();
			case "java.util.HashMap":
				return new HashMap<>();
			default:
				return new LinkedHashMap<>();
		}
	}

	private static Collection<Object> newCollection(String cls, int tag)
	{
		if (tag == SET)
		{
			return cls.equals("java.util.TreeSet") ? new TreeSet<>() : new LinkedHashSet<>();
		}
		if (tag == QUEUE)
		{
			return new ArrayDeque<>();
		}
		if (cls.equals("java.util.LinkedList"))
		{
			return new java.util.LinkedList<>();
		}
		return new ArrayList<>();
	}

	private static Object instantiate(Class<?> c) throws IOException
	{
		try
		{
			// without running its constructor, as Java's serialization does
			Class<?> rf = Class.forName("sun.reflect.ReflectionFactory");
			Object factory = rf.getMethod("getReflectionFactory").invoke(null);
			Constructor<?> ctor = (Constructor<?>) rf.getMethod("newConstructorForSerialization", Class.class, Constructor.class)
				.invoke(factory, c, Object.class.getDeclaredConstructor());
			return ctor.newInstance();
		}
		catch (ReflectiveOperationException ex)
		{
			throw new IOException("can't make a " + c.getName(), ex);
		}
	}

	/** A field of ours by name, through superclasses, accessible: for the dump's few scalars of the frame's state. */
	public static Field field(Class<?> c, String name)
	{
		for (Class<?> k = c; k != null; k = k.getSuperclass())
		{
			try
			{
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			}
			catch (NoSuchFieldException ex)
			{
				// the superclass
			}
		}
		throw new IllegalArgumentException(c.getName() + " has no field " + name);
	}

	public static Object get(Object o, String name)
	{
		try
		{
			return field(o.getClass(), name).get(o);
		}
		catch (IllegalAccessException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	public static void set(Object o, String name, Object v)
	{
		try
		{
			field(o.getClass(), name).set(o, v);
		}
		catch (IllegalAccessException ex)
		{
			throw new IllegalStateException(ex);
		}
	}
}
