package com.midonarhel.lumina.dev;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.midonarhel.lumina.config.DebugView;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PlainDataTest
{
	static class Node
	{
		final int id;
		final float[][] grid;
		Node next;
		final List<Node> children = new ArrayList<>();
		final Map<Integer, String> names = new HashMap<>();
		DebugView view = DebugView.NORMALS;
		FloatBuffer packed;
		Thread worker = new Thread(() -> { });
		Runnable task = () -> { };
		long big = Long.MIN_VALUE;
		boolean flag = true;

		Node(int id)
		{
			this.id = id;
			grid = new float[][]{{id, 1.5f}, {2.5f}};
		}
	}

	static class Leaf extends Node
	{
		short extra = 7;

		Leaf(int id)
		{
			super(id);
		}
	}

	private static Object roundTrip(Object o, List<String> skipped) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			PlainData.Writer w = new PlainData.Writer(out);
			w.write(o);
			skipped.addAll(w.skipped);
		}
		return new PlainData.Reader(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).read();
	}

	@Test
	public void keepsFieldsSharedReferencesAndCycles() throws IOException
	{
		Node a = new Node(1);
		Leaf b = new Leaf(2);
		a.next = b;
		b.next = a;
		a.children.add(b);
		a.children.add(b);
		a.names.put(5, "five");
		a.packed = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();
		a.packed.put(0, 3.25f).put(3, -1f);
		a.packed.position(1);
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("a", a);
		root.put("ints", new int[][][]{{{1, 2}, {3}}});
		root.put("again", a);

		List<String> skipped = new ArrayList<>();
		@SuppressWarnings("unchecked")
		Map<String, Object> back = (Map<String, Object>) roundTrip(root, skipped);
		Node a2 = (Node) back.get("a");
		assertSame(a2, back.get("again"));
		assertEquals(1, a2.id);
		assertArrayEquals(new float[]{1, 1.5f}, a2.grid[0], 0);
		Leaf b2 = (Leaf) a2.next;
		assertSame(a2, b2.next);
		assertEquals(7, b2.extra);
		assertEquals(2, a2.children.size());
		assertSame(b2, a2.children.get(0));
		assertSame(b2, a2.children.get(1));
		assertEquals("five", a2.names.get(5));
		assertEquals(DebugView.NORMALS, a2.view);
		assertEquals(Long.MIN_VALUE, a2.big);
		assertTrue(a2.flag);
		assertTrue(a2.packed.isDirect());
		assertEquals(1, a2.packed.position());
		assertEquals(3.25f, a2.packed.get(0), 0);
		assertEquals(-1f, a2.packed.get(3), 0);
		assertArrayEquals(new int[]{1, 2}, ((int[][][]) back.get("ints"))[0][0]);
		// the thread and the lambda are left out, and named
		assertNull(a2.worker);
		assertNull(a2.task);
		assertTrue(skipped.toString(), skipped.stream().anyMatch(s -> s.contains(".worker")));
		assertTrue(skipped.toString(), skipped.stream().anyMatch(s -> s.contains(".task")));
	}
}
