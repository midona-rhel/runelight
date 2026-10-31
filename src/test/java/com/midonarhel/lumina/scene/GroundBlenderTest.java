package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.*;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The terrain colour rule: 117 HD's undo of vanilla's baked ground light (ProceduralGenerator 726-756), applied to
 * each tile's own colours.
 */
public class GroundBlenderTest
{
	/** 117 HD's code, transcribed: its normal is (dhdx, -1, dhdz), y down. */
	private static int hd(int color, float nx, float ny, float nz)
	{
		float dot = nx * nx + ny * ny + nz * nz;
		dot = (nx + nz) / (float) Math.sqrt(2 * dot);
		int lightness = color & 0x7F;
		float lightFactor = Math.max(dot, 0f);
		int adjustedLight = (int) (Math.max(lightness - 3, 0) * 1.5f + 15);
		lightness = (int) (lightness * (1 - lightFactor) + adjustedLight * lightFactor);
		float darkFactor = -Math.min(dot, 0f);
		int darkened = (int) (lightness * 0.5f);
		lightness = (int) (1.25f * (lightness * (1 - darkFactor) + darkened * darkFactor));
		lightness = Math.min(lightness, 55);
		return (color & ~0x7F) | lightness;
	}

	@Test
	public void undoIs117HdsOnEveryLightnessAndSlope()
	{
		float[] slopes = {-1.5f, -0.7f, -0.25f, 0, 0.1f, 0.4f, 1, 2};
		for (int l = 0; l < 128; ++l)
		{
			int color = 21 << 10 | 3 << 7 | l;
			for (float dx : slopes)
			{
				for (float dz : slopes)
				{
					assertEquals(hd(color, dx, -1, dz), GroundBlender.undoShading(color, dx, dz));
				}
			}
		}
	}

	@Test
	public void flatGroundGainsAQuarterUpToTheCap()
	{
		// a flat tile faces straight up, away from neither side of vanilla's light: only the 1.25 gain and the cap
		assertEquals(50, GroundBlender.undoShading(40, 0, 0) & 0x7f);
		assertEquals(55, GroundBlender.undoShading(50, 0, 0) & 0x7f);
		assertEquals(21 << 10 | 3 << 7, GroundBlender.undoShading(21 << 10 | 3 << 7 | 40, 0, 0) & ~0x7f);
	}
}
