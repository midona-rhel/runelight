package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LogLimiterTest
{
	@Test
	public void logsOncePerIntervalWithCount()
	{
		long[] now = {0};
		LogLimiter l = new LogLimiter(1000, () -> now[0]);
		assertEquals(0, l.check("a"));
		for (int i = 0; i < 5; ++i)
		{
			now[0] += 100;
			assertEquals(-1, l.check("a"));
		}
		// another problem is logged on its own schedule
		assertEquals(0, l.check("b"));
		now[0] = 1000;
		assertEquals(5, l.check("a"));
		assertEquals(-1, l.check("a"));
		now[0] = 2500;
		assertEquals(1, l.check("a"));
	}
}
