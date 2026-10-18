package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class InterfaceUploadTest
{
	@Test
	public void bandsCoverEveryRowOnce()
	{
		for (int rows = 0; rows < 50; ++rows)
		{
			for (int n = 1; n <= 4; ++n)
			{
				assertEquals(0, InterfaceUpload.bandStart(0, n, rows));
				assertEquals(rows, InterfaceUpload.bandStart(n, n, rows));
				for (int t = 0; t < n; ++t)
				{
					assertTrue(InterfaceUpload.bandStart(t, n, rows) <= InterfaceUpload.bandStart(t + 1, n, rows));
				}
			}
		}
	}

	@Test
	public void untrackedIsTheFrameLessItsSteps()
	{
		CpuTimer c = new CpuTimer();
		assertEquals(5.0, c.untrackedMs(5.0), 1e-9);
		assertEquals(0.0, c.untrackedMs(-1.0), 1e-9);
	}
}
