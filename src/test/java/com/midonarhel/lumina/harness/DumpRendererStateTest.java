package com.midonarhel.lumina.harness;

import org.junit.Test;
import static org.junit.Assert.*;

public class DumpRendererStateTest
{
	@Test public void capturedWeatherGpuNamesMustNotEnterAnotherContext()
	{
		for (String field : new String[]{"fieldSets", "groundSets", "skySets", "unpackBuffer", "set"})
		{
			assertFalse(field, DumpRenderer.replayableState("weatherField", field));
		}
		for (String field : new String[]{"state", "sizeX", "sizeZ", "originX", "originZ", "smooth", "focus"})
		{
			assertTrue(field, DumpRenderer.replayableState("weatherField", field));
		}
		assertTrue(DumpRenderer.replayableState("water", "driftX"));
		assertTrue(DumpRenderer.replayableState("wind", "time"));
	}
}
