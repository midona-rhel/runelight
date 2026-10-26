package com.midonarhel.runelight;

import org.junit.Assert;
import org.junit.Test;

public class GlDebugLogTest
{
	@Test
	public void logsEachIdOnceAndCountsRepeats()
	{
		GlDebugLog l = new GlDebugLog();
		Assert.assertTrue(l.first(7));
		for (int i = 0; i < 1000; i++)
		{
			Assert.assertFalse(l.first(7));
		}
		Assert.assertTrue(l.first(8));
		Assert.assertEquals(1001, l.count(7));
		Assert.assertEquals(1, l.repeated().size());
		Assert.assertEquals(Integer.valueOf(1001), l.repeated().get(7));
	}
}
