package com.midonarhel.lumina.config;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class AntiAliasingModeTest
{
	@Test
	public void supportedIsTheHighestTheDriverHas()
	{
		// Apple's GL gives at most 8 samples
		assertEquals(AntiAliasingMode.MSAA_8, AntiAliasingMode.MSAA_16.supported(8));
		assertEquals(AntiAliasingMode.MSAA_8, AntiAliasingMode.MSAA_8.supported(8));
		assertEquals(AntiAliasingMode.MSAA_4, AntiAliasingMode.MSAA_4.supported(8));
		assertEquals(AntiAliasingMode.MSAA_4, AntiAliasingMode.MSAA_16.supported(4));
		assertEquals(AntiAliasingMode.MSAA_16, AntiAliasingMode.MSAA_16.supported(32));
		assertEquals(AntiAliasingMode.DISABLED, AntiAliasingMode.MSAA_2.supported(1));
		assertEquals(AntiAliasingMode.DISABLED, AntiAliasingMode.DISABLED.supported(8));
	}
}
