package com.midonarhel.lumina.frame;

/**
 * Frame pacing. With FPS unlocked and vsync off the client's own limiter sleeps whole milliseconds and lands late
 * every sixth frame (a 14/21 ms saw-tooth at a 60 target), so the frame is paced here instead: sleep to within
 * 1.5 ms of a deadline on a fixed grid, then spin; the client gets no target. {@code ::runelight pace off} hands
 * the target back to the client, to measure the difference.
 */
public final class FramePacer
{
	private long frameTargetNanos;
	private long nextFrameDeadline;
	private boolean paceHere = true;

	/**
	 * Take the frame target for this pacer (or hand it to the client), as the sync mode settles it.
	 *
	 * @param target frames per second wanted, 0 for none (vsync is on)
	 * @return the target the client's own limiter should get: 0 while pacing here, else {@code target}
	 */
	public int apply(int target)
	{
		nextFrameDeadline = 0;
		if (paceHere)
		{
			frameTargetNanos = target > 0 ? 1_000_000_000L / target : 0;
			return 0;
		}
		frameTargetNanos = 0;
		return target;
	}

	public boolean isPaceHere()
	{
		return paceHere;
	}

	public void setPaceHere(boolean here)
	{
		paceHere = here;
	}

	/** After the buffer swap: wait out the rest of this frame's slot. */
	public void pace()
	{
		if (frameTargetNanos == 0)
		{
			return;
		}
		long now = System.nanoTime();
		if (nextFrameDeadline == 0 || now - nextFrameDeadline > frameTargetNanos)
		{
			// first frame, or so late the grid is lost: start it again from here
			nextFrameDeadline = now + frameTargetNanos;
			return;
		}
		long remaining = nextFrameDeadline - now;
		while (remaining > 1_500_000L)
		{
			try
			{
				Thread.sleep(1);
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				break;
			}
			remaining = nextFrameDeadline - System.nanoTime();
		}
		while (System.nanoTime() < nextFrameDeadline)
		{
			Thread.onSpinWait();
		}
		nextFrameDeadline += frameTargetNanos;
	}
}
