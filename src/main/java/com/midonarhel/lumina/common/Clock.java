package com.midonarhel.lumina.common;

/**
 * The renderer's time for what it draws: animations, eases, the day and the weather. The system's clocks, except
 * while the offline renderer (docs/offline-render.md) sets the time a dump was taken at, so that a frame drawn from
 * it is the frame the client drew. Budgets and timings (deadlines, CPU timers, the hitch monitor) keep reading
 * System.nanoTime: they measure work, not what is drawn.
 */
public final class Clock
{
	private static volatile boolean set;
	private static volatile long millis, nanos;

	private Clock()
	{
	}

	/** Like System.currentTimeMillis. */
	public static long millis()
	{
		return set ? millis : System.currentTimeMillis();
	}

	/** Like System.nanoTime. */
	public static long nanos()
	{
		return set ? nanos : System.nanoTime();
	}

	/** The time is this until {@link #release}: the offline renderer, a frame at a time. */
	public static void set(long millis, long nanos)
	{
		Clock.millis = millis;
		Clock.nanos = nanos;
		set = true;
	}

	/** The system's clocks again. */
	public static void release()
	{
		set = false;
	}
}
