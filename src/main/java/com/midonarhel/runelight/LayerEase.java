package com.midonarhel.runelight;

/**
 * The one ease for a derived layer that cannot be swapped in with the scene it belongs to and arrives a few frames
 * after it (a lamp's light map, the skylight grid): from the moment it arrives its weight in the shading goes from 0
 * to 1 over {@link #NANOS}, smoothstep-shaped, and what stood in before it takes the rest. Nothing pops in one frame.
 *
 * 0.3 s: 18 frames at 60 FPS; smoothstep's steepest step is then 1.5 / 18 ≈ 8 % of the layer's change per frame, where
 * a one-frame swap was 100 % (the user, 2026-10-30: lamps snapping on a few frames after the scene, "looks pretty bad").
 */
final class LayerEase
{
	static final long NANOS = 300_000_000L;

	private LayerEase()
	{
	}

	/**
	 * The layer's weight now, 0 to 1.
	 *
	 * @param since when the layer arrived (System.nanoTime), 0 for long ago
	 */
	static float weight(long since, long now)
	{
		if (since == 0)
		{
			return 1;
		}
		float t = Math.min(Math.max((now - since) / (float) NANOS, 0), 1);
		return t * t * (3 - 2 * t);
	}
}
