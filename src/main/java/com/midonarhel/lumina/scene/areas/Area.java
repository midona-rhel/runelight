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

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import lombok.NoArgsConstructor;

/**
 * A named set of world-space boxes from areas.json. Tile overrides, model overrides and environments are scoped to areas.
 * 117 HD's area hiding (hideOtherAreas, fillGaps) is not ported; those keys are ignored.
 */
@NoArgsConstructor // Required to make Gson respect initializers
public class Area
{
	public static final Area NONE = new Area("NONE", 0, 0, 0, 0);
	public static final Area ALL = new Area("ALL", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
	public static Area OVERWORLD = NONE;

	public String name;
	public boolean hideOtherAreas;
	public boolean fillGaps;

	public String[] areas;
	public int[] regions;

	@JsonAdapter(RegionBox.Adapter.class)
	public RegionBox[] regionBoxes;

	@JsonAdapter(AABB.ArrayAdapter.class)
	@SerializedName("aabbs")
	public AABB[] rawAabbs;

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] unhideAreas = {};

	public transient AABB[] aabbs;
	public transient AABB areaBounds;

	private transient AABB[] sortedAabbs;
	private transient boolean normalized;

	public Area(String name, int x1, int y1, int x2, int y2)
	{
		this.name = name;
		aabbs = new AABB[]{new AABB(x1, y1, x2, y2)};
		unhideAreas = new AABB[0];
		normalized = true;
	}

	public void normalize()
	{
		if (normalized)
		{
			return;
		}
		normalized = true;

		ArrayList<AABB> list = new ArrayList<>();

		if (rawAabbs != null)
		{
			Collections.addAll(list, rawAabbs);
		}

		if (regions != null)
		{
			for (int regionId : regions)
			{
				list.add(AABB.fromRegionId(regionId));
			}
		}

		if (regionBoxes != null)
		{
			for (var box : regionBoxes)
			{
				list.add(box.toAabb());
			}
		}

		if (areas != null)
		{
			for (String areaName : areas)
			{
				for (Area other : AreaManager.AREAS)
				{
					if (areaName.equals(other.name))
					{
						other.normalize();
						Collections.addAll(list, other.aabbs);
						break;
					}
				}
			}
		}

		aabbs = list.toArray(new AABB[0]);

		if (unhideAreas == null)
		{
			unhideAreas = new AABB[0];
		}

		if (aabbs.length > 0)
		{
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int maxY = Integer.MIN_VALUE;
			int maxZ = Integer.MIN_VALUE;

			for (AABB[] boxes : new AABB[][]{aabbs, unhideAreas})
			{
				for (AABB aabb : boxes)
				{
					minX = Math.min(minX, aabb.minX);
					minY = Math.min(minY, aabb.minY);
					minZ = Math.min(minZ, aabb.minZ);
					maxX = Math.max(maxX, aabb.maxX);
					maxY = Math.max(maxY, aabb.maxY);
					maxZ = Math.max(maxZ, aabb.maxZ);
				}
			}

			areaBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

			if (aabbs.length > 4)
			{
				// For each corner of the bounds, the boxes sorted by distance to that corner, so lookups near
				// a corner find their box early
				sortedAabbs = new AABB[4 * aabbs.length];
				buildCorner(0, areaBounds.minX, areaBounds.minY);
				buildCorner(1, areaBounds.minX, areaBounds.maxY);
				buildCorner(2, areaBounds.maxX, areaBounds.minY);
				buildCorner(3, areaBounds.maxX, areaBounds.maxY);
			}
		}
	}

	private void buildCorner(int c, int cx, int cy)
	{
		final int left = c * aabbs.length;
		System.arraycopy(aabbs, 0, sortedAabbs, left, aabbs.length);
		Arrays.sort(sortedAabbs, left, left + aabbs.length, Comparator.comparingDouble(a -> score(a, cx, cy)));
	}

	private static float score(AABB aabb, int cx, int cy)
	{
		float distance = aabb.distanceToPoint(cx, cy);
		int w = aabb.maxX - aabb.minX;
		int h = aabb.maxY - aabb.minY;
		final float SIZE_WEIGHT = 1e-6F;
		return distance - (float) w * h * SIZE_WEIGHT;
	}

	private int getClosestCorner(int x, int y)
	{
		int dx = x - areaBounds.minX;
		int dy = y - areaBounds.minY;
		int best = dx * dx + dy * dy;
		int corner = 0;

		dy = y - areaBounds.maxY;
		int d = dx * dx + dy * dy;
		if (d < best)
		{
			best = d;
			corner = 1;
		}

		dx = x - areaBounds.maxX;
		dy = y - areaBounds.minY;
		d = dx * dx + dy * dy;
		if (d < best)
		{
			best = d;
			corner = 2;
		}

		dy = y - areaBounds.maxY;
		d = dx * dx + dy * dy;
		if (d < best)
		{
			corner = 3;
		}

		return corner;
	}

	public boolean containsPoint(int... worldPoint)
	{
		return containsPoint(true, worldPoint);
	}

	public boolean containsPoint(boolean includeUnhiding, int... worldPoint)
	{
		if (areaBounds != null && !areaBounds.contains(worldPoint))
		{
			return false;
		}

		final int length = aabbs.length;
		if (sortedAabbs != null)
		{
			final int start = getClosestCorner(worldPoint[0], worldPoint[1]) * length;
			for (int i = start; i < start + length; i++)
			{
				if (sortedAabbs[i].contains(worldPoint))
				{
					return true;
				}
			}
		}
		else
		{
			for (int i = 0; i < length; i++)
			{
				if (aabbs[i].contains(worldPoint))
				{
					return true;
				}
			}
		}

		if (includeUnhiding)
		{
			for (AABB aabb : unhideAreas)
			{
				if (aabb.contains(worldPoint))
				{
					return true;
				}
			}
		}

		return false;
	}

	public boolean intersects(boolean includeUnhiding, int minX, int minY, int maxX, int maxY)
	{
		for (AABB aabb : aabbs)
		{
			if (aabb.intersects(minX, minY, maxX, maxY))
			{
				return true;
			}
		}

		if (includeUnhiding)
		{
			for (AABB aabb : unhideAreas)
			{
				if (aabb.intersects(minX, minY, maxX, maxY))
				{
					return true;
				}
			}
		}

		return false;
	}

	public boolean intersects(AABB... otherAabbs)
	{
		for (AABB aabb : aabbs)
		{
			if (aabb.intersects(otherAabbs))
			{
				return true;
			}
		}
		return false;
	}

	public boolean intersects(Area otherArea)
	{
		if (otherArea == null)
		{
			return false;
		}
		for (AABB aabb : aabbs)
		{
			if (aabb.intersects(otherArea.aabbs))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
