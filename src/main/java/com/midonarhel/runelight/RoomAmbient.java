package com.midonarhel.runelight;

/**
 * Baked wall distance at tile corners, outdoors too (rain puddles gather by walls). Six bits per corner fit exactly in
 * a float texture value.
 */
final class RoomAmbient
{
	private RoomAmbient() {}

	static int pack(WallMap walls, int plane, int x, int y)
	{
		int packed = 0;
		for (int corner = 0; corner < 4; corner++)
		{
			float px = x + (corner & 1), py = y + (corner >> 1);
			float distance = 1;
			for (int tx = Math.max(0, (int) px - 1); tx <= Math.min(walls.size - 1, (int) px); tx++)
			{
				for (int ty = Math.max(0, (int) py - 1); ty <= Math.min(walls.size - 1, (int) py); ty++)
				{
					int bits = walls.get(plane, tx, ty);
					if ((bits & WallMap.NORTH) != 0) distance = Math.min(distance, segment(px, py, tx, ty + 1, tx + 1, ty + 1));
					if ((bits & WallMap.EAST) != 0) distance = Math.min(distance, segment(px, py, tx + 1, ty, tx + 1, ty + 1));
					if ((bits & WallMap.SOUTH) != 0) distance = Math.min(distance, segment(px, py, tx, ty, tx + 1, ty));
					if ((bits & WallMap.WEST) != 0) distance = Math.min(distance, segment(px, py, tx, ty, tx, ty + 1));
					if ((bits & WallMap.DIAG_SW_NE) != 0) distance = Math.min(distance, segment(px, py, tx, ty, tx + 1, ty + 1));
					if ((bits & WallMap.DIAG_NW_SE) != 0) distance = Math.min(distance, segment(px, py, tx, ty + 1, tx + 1, ty));
				}
			}
			packed |= Math.round(distance * 63) << (corner * 6);
		}
		return packed;
	}

	private static float segment(float x, float y, float ax, float ay, float bx, float by)
	{
		float dx = bx - ax, dy = by - ay;
		float t = Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
		return (float) Math.hypot(x - ax - t * dx, y - ay - t * dy);
	}
}
