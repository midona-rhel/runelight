package com.midonarhel.lumina.harness;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;

/**
 * The client's interfaces faked with java.lang.reflect.Proxy for the offline renderer and the tests: each method by
 * name answers a value (numbers converted to the method's type) or a function of its arguments. A method not given
 * throws, naming itself, so whatever a renderer path reads that the dump doesn't hold shows up at once.
 */
public final class Fakes
{
	private Fakes()
	{
	}

	/** An answer computed from the call's arguments. */
	public interface Answer extends Function<Object[], Object>
	{
	}

	@SuppressWarnings("unchecked")
	public static <T> T of(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			String name = method.getName();
			if (method.getDeclaringClass() == Object.class)
			{
				switch (name)
				{
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					default:
						return type.getSimpleName() + "(fake)";
				}
			}
			if (!answers.containsKey(name))
			{
				if (method.isDefault())
				{
					return java.lang.invoke.MethodHandles.privateLookupIn(type, java.lang.invoke.MethodHandles.lookup())
						.unreflectSpecial(method, type).bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
				}
				if (method.getReturnType() == void.class && name.startsWith("set"))
				{
					return null;
				}
				throw new UnsupportedOperationException(type.getSimpleName() + "." + name + " is not faked");
			}
			Object v = answers.get(name);
			if (v instanceof Answer)
			{
				v = ((Answer) v).apply(args == null ? new Object[0] : args);
			}
			return convert(v, method);
		});
	}

	static Object convert(Object v, Method method)
	{
		Class<?> r = method.getReturnType();
		if (!(v instanceof Number) || !r.isPrimitive() && !Number.class.isAssignableFrom(r))
		{
			return v;
		}
		Number n = (Number) v;
		if (r == int.class || r == Integer.class)
		{
			return n.intValue();
		}
		if (r == float.class || r == Float.class)
		{
			return n.floatValue();
		}
		if (r == double.class || r == Double.class)
		{
			return n.doubleValue();
		}
		if (r == long.class || r == Long.class)
		{
			return n.longValue();
		}
		if (r == short.class || r == Short.class)
		{
			return n.shortValue();
		}
		if (r == byte.class || r == Byte.class)
		{
			return n.byteValue();
		}
		return v;
	}
}
