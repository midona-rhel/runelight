/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
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
package com.midonarhel.lumina.scene.areas;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import static net.runelite.api.Constants.REGION_SIZE;

/**
 * An axis-aligned box in world coordinates (x, y and optionally plane).
 */
public class AABB
{
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int maxX;
	public final int maxY;
	public final int maxZ;

	public AABB(int x, int y)
	{
		minX = maxX = x;
		minY = maxY = y;
		minZ = Integer.MIN_VALUE;
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x, int y, int z)
	{
		minX = maxX = x;
		minY = maxY = y;
		minZ = maxZ = z;
	}

	public AABB(int x1, int y1, int x2, int y2)
	{
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		minZ = Integer.MIN_VALUE;
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		maxZ = Integer.MAX_VALUE;
	}

	public AABB(int x1, int y1, int x2, int y2, int z1)
	{
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		minZ = maxZ = z1;
	}

	public AABB(int x1, int y1, int z1, int x2, int y2, int z2)
	{
		minX = Math.min(x1, x2);
		minY = Math.min(y1, y2);
		minZ = Math.min(z1, z2);
		maxX = Math.max(x1, x2);
		maxY = Math.max(y1, y2);
		maxZ = Math.max(z1, z2);
	}

	public static AABB fromRegionId(int regionId)
	{
		int minX = (regionId >>> 8) << 6;
		int minY = (regionId & 0xFF) << 6;
		int maxX = minX + REGION_SIZE - 1;
		int maxY = minY + REGION_SIZE - 1;
		return new AABB(minX, minY, maxX, maxY);
	}

	public boolean hasZ()
	{
		return minZ != Integer.MIN_VALUE || maxZ != Integer.MAX_VALUE;
	}

	public boolean contains(int... worldPos)
	{
		final int length = worldPos.length;
		final int x = worldPos[0];
		final int y = worldPos[1];
		final int z = length > 2 ? worldPos[2] : 0;
		return
			minX <= x && x <= maxX &&
			minY <= y && y <= maxY &&
			(length < 3 || minZ <= z && z <= maxZ);
	}

	public boolean intersects(int minX, int minY, int maxX, int maxY)
	{
		return
			minX <= this.maxX && maxX >= this.minX &&
			minY <= this.maxY && maxY >= this.minY;
	}

	public boolean intersects(AABB other)
	{
		return
			other.minX <= maxX && other.maxX >= minX &&
			other.minY <= maxY && other.maxY >= minY &&
			other.minZ <= maxZ && other.maxZ >= minZ;
	}

	public boolean intersects(AABB... aabbs)
	{
		for (var aabb : aabbs)
		{
			if (intersects(aabb))
			{
				return true;
			}
		}
		return false;
	}

	public int distanceToPoint(int x, int y)
	{
		int dx = Math.max(Math.max(minX - x, 0), x - maxX);
		int dy = Math.max(Math.max(minY - y, 0), y - maxY);
		return dx * dx + dy * dy;
	}

	@Override
	public String toString()
	{
		if (hasZ())
		{
			return String.format("AABB{min=(%d,%d,%d), max=(%d,%d,%d)}", minX, minY, minZ, maxX, maxY, maxZ);
		}
		return String.format("AABB{min=(%d,%d), max=(%d,%d)}", minX, minY, maxX, maxY);
	}

	/**
	 * Reads an array of AABBs given as 2 to 6 numbers each, or area names which expand to the area's AABBs.
	 */
	@Slf4j
	public static class ArrayAdapter extends TypeAdapter<AABB[]>
	{
		@Override
		public AABB[] read(JsonReader in) throws IOException
		{
			in.beginArray();
			ArrayList<AABB> list = new ArrayList<>();
			outer:
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY)
			{
				if (in.peek() == JsonToken.NULL)
				{
					in.skipValue();
					continue;
				}

				if (in.peek() == JsonToken.NUMBER)
				{
					log.warn("AABBs are specified by two or more numbers. Did you forget to add an array at {}?", GsonUtils.location(in));
					in.skipValue();
					continue;
				}

				if (in.peek() == JsonToken.STRING)
				{
					String name = in.nextString();
					for (var area : AreaManager.AREAS)
					{
						if (name.equals(area.name))
						{
							Collections.addAll(list, area.aabbs);
							continue outer;
						}
					}

					log.warn("No area exists with the name '{}' at {}", name, GsonUtils.location(in));
					continue;
				}

				in.beginArray();
				int[] ints = new int[6];
				int i = 0;
				while (in.hasNext())
				{
					if (in.peek() == JsonToken.NUMBER)
					{
						if (i >= ints.length)
						{
							throw new IOException("Too many numbers in AABB entry (> " + ints.length + ") at " + GsonUtils.location(in));
						}
						ints[i++] = in.nextInt();
					}
					else if (in.peek() == JsonToken.NULL)
					{
						in.skipValue();
					}
					else
					{
						throw new IOException("Malformed AABB entry. Unexpected token: " + in.peek() + " at " + GsonUtils.location(in));
					}
				}
				in.endArray();

				switch (i)
				{
					case 2:
						list.add(new AABB(ints[0], ints[1]));
						break;
					case 3:
						list.add(new AABB(ints[0], ints[1], ints[2]));
						break;
					case 4:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3]));
						break;
					case 5:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3], ints[4]));
						break;
					case 6:
						list.add(new AABB(ints[0], ints[1], ints[2], ints[3], ints[4], ints[5]));
						break;
					default:
						log.warn("AABBs are specified by two or more numbers, got {} at {}", i, GsonUtils.location(in));
						break;
				}
			}
			in.endArray();
			return list.toArray(new AABB[0]);
		}

		@Override
		public void write(JsonWriter out, AABB[] aabbs)
		{
			throw new UnsupportedOperationException();
		}
	}
}
