/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
import lombok.RequiredArgsConstructor;
import static net.runelite.api.Constants.MAX_Z;

/**
 * A box of map regions, from one region ID to another, optionally limited to planes.
 */
@RequiredArgsConstructor
public class RegionBox
{
	public final int from, to;
	public final int fromPlane, toPlane;

	public RegionBox(int regionId)
	{
		this(regionId, regionId);
	}

	public RegionBox(int from, int to)
	{
		this(from, to, 0, MAX_Z - 1);
	}

	public RegionBox(int from, int to, int plane)
	{
		this(from, to, plane, plane);
	}

	public AABB toAabb()
	{
		int x1 = from >>> 8;
		int y1 = from & 0xFF;
		int x2 = to >>> 8;
		int y2 = to & 0xFF;
		if (x1 > x2)
		{
			int temp = x1;
			x1 = x2;
			x2 = temp;
		}
		if (y1 > y2)
		{
			int temp = y1;
			y1 = y2;
			y2 = temp;
		}
		return new AABB(
			x1 << 6,
			y1 << 6,
			fromPlane,
			(x2 + 1 << 6) - 1,
			(y2 + 1 << 6) - 1,
			toPlane
		);
	}

	public static class Adapter extends TypeAdapter<RegionBox[]>
	{
		@Override
		public RegionBox[] read(JsonReader in) throws IOException
		{
			in.beginArray();
			ArrayList<RegionBox> list = new ArrayList<>();
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY)
			{
				if (in.peek() == JsonToken.NULL)
				{
					in.skipValue();
					continue;
				}

				in.beginArray();
				int[] ints = new int[4];
				int i = 0;
				while (in.hasNext())
				{
					if (in.peek() == JsonToken.NUMBER)
					{
						if (i >= ints.length)
						{
							throw new IOException("Too many numbers in RegionBox entry (> " + ints.length + ") at " + GsonUtils.location(in));
						}
						ints[i++] = in.nextInt();
					}
					else if (in.peek() == JsonToken.NULL)
					{
						in.skipValue();
					}
					else
					{
						throw new IOException("Malformed RegionBox entry. Unexpected token: " + in.peek() + " at " + GsonUtils.location(in));
					}
				}
				in.endArray();

				switch (i)
				{
					case 1:
						list.add(new RegionBox(ints[0]));
						break;
					case 2:
						list.add(new RegionBox(ints[0], ints[1]));
						break;
					case 3:
						list.add(new RegionBox(ints[0], ints[1], ints[2]));
						break;
					case 4:
						list.add(new RegionBox(ints[0], ints[1], ints[2], ints[3]));
						break;
				}
			}
			in.endArray();
			return list.toArray(new RegionBox[0]);
		}

		@Override
		public void write(JsonWriter out, RegionBox[] boxes)
		{
			throw new UnsupportedOperationException();
		}
	}
}
