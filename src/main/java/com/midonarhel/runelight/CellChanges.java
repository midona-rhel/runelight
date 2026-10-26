package com.midonarhel.runelight;

/**
 * The cells of the light map's scene that changed between two snapshots of what stops light (LightBlockers), so a
 * light is baked again only when a cell inside its own reach has changed. Marked cell by cell, then asked for
 * rectangles.
 */
final class CellChanges
{
	private final int cells;
	private final boolean[] marked;
	private int count;
	// prefix sums of the marked cells, (cells + 1) squared, made on the first question
	private int[] sums;

	CellChanges(int cells)
	{
		this.cells = cells;
		marked = new boolean[cells * cells];
	}

	/** Mark a cell and the cells within margin of it. */
	void mark(int x, int z, int margin)
	{
		for (int j = Math.max(0, z - margin); j <= Math.min(cells - 1, z + margin); ++j)
		{
			for (int i = Math.max(0, x - margin); i <= Math.min(cells - 1, x + margin); ++i)
			{
				if (!marked[j * cells + i])
				{
					marked[j * cells + i] = true;
					count++;
				}
			}
		}
	}

	void markAll()
	{
		java.util.Arrays.fill(marked, true);
		count = cells * cells;
	}

	boolean isEmpty()
	{
		return count == 0;
	}

	int count()
	{
		return count;
	}

	/** Whether a cell of the rectangle (inclusive, cells) is marked. */
	boolean any(int x0, int z0, int x1, int z1)
	{
		if (count == 0)
		{
			return false;
		}
		if (sums == null)
		{
			int n = cells + 1;
			sums = new int[n * n];
			for (int j = 0; j < cells; ++j)
			{
				int row = 0;
				for (int i = 0; i < cells; ++i)
				{
					row += marked[j * cells + i] ? 1 : 0;
					sums[(j + 1) * n + i + 1] = sums[j * n + i + 1] + row;
				}
			}
		}
		x0 = Math.max(0, x0);
		z0 = Math.max(0, z0);
		x1 = Math.min(cells - 1, x1);
		z1 = Math.min(cells - 1, z1);
		if (x1 < x0 || z1 < z0)
		{
			return false;
		}
		int n = cells + 1;
		return sums[(z1 + 1) * n + x1 + 1] - sums[z0 * n + x1 + 1] - sums[(z1 + 1) * n + x0] + sums[z0 * n + x0] > 0;
	}
}
