package com.midonarhel.runelight.config;

public enum RenderScale
{
	FULL("100%", 1),
	PERCENT_75("75%", 0.75),
	PERCENT_50("50%", 0.5),
	PERCENT_25("25%", 0.25),
	// cancels the display's backing scale (a Retina screen's 2x), so one scene pixel is one window point
	UNSCALED("1x (no HiDPI)", 0);

	private final String name;
	private final double factor;

	RenderScale(String name, double factor)
	{
		this.name = name;
		this.factor = factor;
	}

	/** The share of the drawable's resolution the scene renders at, 1 for the whole of it. */
	public double factor(double backingScale)
	{
		if (this == UNSCALED)
		{
			return backingScale > 1 ? 1 / backingScale : 1;
		}
		return factor;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
