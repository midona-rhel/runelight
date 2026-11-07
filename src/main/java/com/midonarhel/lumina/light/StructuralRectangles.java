package com.midonarhel.lumina.light;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Merge coplanar triangle pairs without filling holes. Other shapes remain exact triangles. */
final class StructuralRectangles
{
	private static final class Rectangle
	{
		int axis, cornerMask;
		float intercept, slopeU, slopeV, u0, u1, v0, v1;
		float[] triangle;

		String key()
		{
			return axis + ":" + intercept + ":" + slopeU + ":" + slopeV + ":" + u0 + ":" + u1 + ":" + v0 + ":" + v1;
		}

		float[] primitive()
		{
			// A parallelogram in 3D, rectangular in its projection onto the other two axes.
			float[] result = new float[10];
			int u = (axis + 1) % 3, v = (axis + 2) % 3;
			result[u] = u0;
			result[v] = v0;
			result[3 + u] = u1;
			result[3 + v] = v0;
			result[6 + u] = u0;
			result[6 + v] = v1;
			result[9] = 1; // primitive type: rectangle
			for (int vertex = 0; vertex < 9; vertex += 3)
			{
				result[vertex + axis] = intercept + slopeU * result[vertex + u] + slopeV * result[vertex + v];
			}
			return result;
		}
	}

	static List<float[]> simplify(List<float[]> input)
	{
		List<float[]> result = new ArrayList<>();
		Map<String, List<Rectangle>> waiting = new HashMap<>();
		List<Rectangle> rectangles = new ArrayList<>();
		for (float[] triangle : input)
		{
			Rectangle rectangle = recognize(triangle);
			if (rectangle == null)
			{
				result.add(triangle);
				continue;
			}
			List<Rectangle> candidates = waiting.computeIfAbsent(rectangle.key(), key -> new ArrayList<>());
			boolean paired = false;
			for (Iterator<Rectangle> iterator = candidates.iterator(); iterator.hasNext();)
			{
				Rectangle other = iterator.next();
				// Opposite missing corners mean they meet only along the rectangle's diagonal.
				int missing = Integer.numberOfTrailingZeros((~rectangle.cornerMask) & 15);
				int otherMissing = Integer.numberOfTrailingZeros((~other.cornerMask) & 15);
				if ((missing ^ otherMissing) == 3)
				{
					iterator.remove();
					rectangles.add(rectangle);
					paired = true;
					break;
				}
			}
			if (!paired)
			{
				candidates.add(rectangle);
			}
		}
		for (List<Rectangle> remaining : waiting.values())
		{
			for (Rectangle rectangle : remaining) result.add(rectangle.triangle);
		}
		int before;
		do
		{
			before = rectangles.size();
			rectangles = merge(rectangles, false);
			rectangles = merge(rectangles, true);
		}
		while (rectangles.size() < before);
		for (Rectangle rectangle : rectangles) result.add(rectangle.primitive());
		return result;
	}

	private static Rectangle recognize(float[] triangle)
	{
		float ax = triangle[3] - triangle[0], ay = triangle[4] - triangle[1], az = triangle[5] - triangle[2];
		float bx = triangle[6] - triangle[0], by = triangle[7] - triangle[1], bz = triangle[8] - triangle[2];
		float[] normal = {ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
		int axis = 0;
		for (int i = 1; i < 3; ++i)
		{
			if (Math.abs(normal[i]) > Math.abs(normal[axis])) axis = i;
		}
		if (Math.abs(normal[axis]) < 1e-6) return null;
		int u = (axis + 1) % 3, v = (axis + 2) % 3;
		Rectangle rectangle = new Rectangle();
		rectangle.axis = axis;
		rectangle.triangle = triangle;
		rectangle.slopeU = -normal[u] / normal[axis];
		rectangle.slopeV = -normal[v] / normal[axis];
		// Canonical zero keeps opposite windings in the same group.
		if (rectangle.slopeU == 0) rectangle.slopeU = 0;
		if (rectangle.slopeV == 0) rectangle.slopeV = 0;
		rectangle.intercept = triangle[axis] - rectangle.slopeU * triangle[u] - rectangle.slopeV * triangle[v];
		if (rectangle.intercept == 0) rectangle.intercept = 0;
		rectangle.u0 = Math.min(triangle[u], Math.min(triangle[u + 3], triangle[u + 6]));
		rectangle.u1 = Math.max(triangle[u], Math.max(triangle[u + 3], triangle[u + 6]));
		rectangle.v0 = Math.min(triangle[v], Math.min(triangle[v + 3], triangle[v + 6]));
		rectangle.v1 = Math.max(triangle[v], Math.max(triangle[v + 3], triangle[v + 6]));
		if (rectangle.u0 == rectangle.u1 || rectangle.v0 == rectangle.v1) return null;
		for (int vertex = 0; vertex < 9; vertex += 3)
		{
			float pu = triangle[vertex + u], pv = triangle[vertex + v];
			if ((pu != rectangle.u0 && pu != rectangle.u1) || (pv != rectangle.v0 && pv != rectangle.v1)) return null;
			float reconstructed = rectangle.intercept + rectangle.slopeU * pu + rectangle.slopeV * pv;
			if (Math.abs(reconstructed - triangle[vertex + axis]) > 0.0001f) return null;
			rectangle.cornerMask |= 1 << ((pu == rectangle.u1 ? 1 : 0) + (pv == rectangle.v1 ? 2 : 0));
		}
		return Integer.bitCount(rectangle.cornerMask) == 3 ? rectangle : null;
	}

	private static List<Rectangle> merge(List<Rectangle> input, boolean alongU)
	{
		input.sort(Comparator.comparingInt((Rectangle r) -> r.axis)
			.thenComparingDouble(r -> r.intercept).thenComparingDouble(r -> r.slopeU).thenComparingDouble(r -> r.slopeV)
			.thenComparingDouble(r -> alongU ? r.v0 : r.u0).thenComparingDouble(r -> alongU ? r.v1 : r.u1)
			.thenComparingDouble(r -> alongU ? r.u0 : r.v0));
		List<Rectangle> output = new ArrayList<>();
		for (Rectangle rectangle : input)
		{
			Rectangle last = output.isEmpty() ? null : output.get(output.size() - 1);
			boolean samePlane = last != null && last.axis == rectangle.axis && last.intercept == rectangle.intercept
				&& last.slopeU == rectangle.slopeU && last.slopeV == rectangle.slopeV;
			boolean joins = samePlane && (alongU
				? last.v0 == rectangle.v0 && last.v1 == rectangle.v1 && rectangle.u0 <= last.u1
				: last.u0 == rectangle.u0 && last.u1 == rectangle.u1 && rectangle.v0 <= last.v1);
			if (joins)
			{
				if (alongU) last.u1 = Math.max(last.u1, rectangle.u1);
				else last.v1 = Math.max(last.v1, rectangle.v1);
			}
			else output.add(rectangle);
		}
		return output;
	}
}
