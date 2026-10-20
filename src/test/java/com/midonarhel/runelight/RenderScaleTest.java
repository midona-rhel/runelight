package com.midonarhel.runelight;

import com.midonarhel.runelight.config.RenderScale;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class RenderScaleTest
{
	@Test
	public void fullIsExactlyOneOnAnyDisplay()
	{
		// the scene targets are sized value * (backing scale * factor): 1 keeps the pre-render-scale sizes
		assertEquals(1.0, RenderScale.FULL.factor(1), 0);
		assertEquals(1.0, RenderScale.FULL.factor(2), 0);
		assertEquals(1.0, RenderScale.FULL.factor(1.25), 0);
	}

	@Test
	public void percentagesIgnoreTheBackingScale()
	{
		assertEquals(0.75, RenderScale.PERCENT_75.factor(2), 0);
		assertEquals(0.5, RenderScale.PERCENT_50.factor(1), 0);
		assertEquals(0.25, RenderScale.PERCENT_25.factor(2), 0);
	}

	@Test
	public void unscaledCancelsTheBackingScale()
	{
		assertEquals(0.5, RenderScale.UNSCALED.factor(2), 0);
		assertEquals(0.8, RenderScale.UNSCALED.factor(1.25), 1e-12);
		// no HiDPI: same as 100%
		assertEquals(1.0, RenderScale.UNSCALED.factor(1), 0);
		assertEquals((int) (1440 * 2.0 * RenderScale.UNSCALED.factor(2)), 1440);
	}
}
