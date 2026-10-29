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

import java.util.Arrays;

/**
 * Open-addressing int to int hash map (linear probing) for the per-vertex terrain data, avoiding boxing.
 * Not thread safe; a map is filled on the map-load thread and only read afterwards.
 */
public final class IntIntMap
{
	private static final int EMPTY = Integer.MIN_VALUE;

	private int[] keys;
	private int[] values;
	private int size;
	private int mask;
	private boolean hasEmptyKey;
	private int emptyKeyValue;

	public IntIntMap(int expectedSize)
	{
		int capacity = Integer.highestOneBit(Math.max(16, expectedSize * 2) - 1) << 1;
		keys = new int[capacity];
		values = new int[capacity];
		Arrays.fill(keys, EMPTY);
		mask = capacity - 1;
	}

	private static int hash(int key)
	{
		key ^= key >>> 16;
		key *= 0x85ebca6b;
		key ^= key >>> 13;
		return key;
	}

	private int slot(int key)
	{
		int i = hash(key) & mask;
		while (keys[i] != EMPTY && keys[i] != key)
		{
			i = (i + 1) & mask;
		}
		return i;
	}

	public int getOrDefault(int key, int defaultValue)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey ? emptyKeyValue : defaultValue;
		}
		int i = slot(key);
		return keys[i] == EMPTY ? defaultValue : values[i];
	}

	public boolean containsKey(int key)
	{
		if (key == EMPTY)
		{
			return hasEmptyKey;
		}
		return keys[slot(key)] != EMPTY;
	}

	public void put(int key, int value)
	{
		if (key == EMPTY)
		{
			hasEmptyKey = true;
			emptyKeyValue = value;
			return;
		}
		int i = slot(key);
		values[i] = value;
		if (keys[i] == EMPTY)
		{
			keys[i] = key;
			if (++size * 2 > keys.length)
			{
				grow();
			}
		}
	}

	public void putIfAbsent(int key, int value)
	{
		if (!containsKey(key))
		{
			put(key, value);
		}
	}

	/**
	 * Sets {@code bits} in the value stored for {@code key}, starting from 0 if absent.
	 */
	public void or(int key, int bits)
	{
		put(key, getOrDefault(key, 0) | bits);
	}

	public boolean test(int key, int bits)
	{
		return (getOrDefault(key, 0) & bits) != 0;
	}

	private void grow()
	{
		int[] oldKeys = keys;
		int[] oldValues = values;
		keys = new int[oldKeys.length * 2];
		values = new int[oldValues.length * 2];
		Arrays.fill(keys, EMPTY);
		mask = keys.length - 1;
		for (int i = 0; i < oldKeys.length; i++)
		{
			if (oldKeys[i] != EMPTY)
			{
				int j = slot(oldKeys[i]);
				keys[j] = oldKeys[i];
				values[j] = oldValues[i];
			}
		}
	}
}
