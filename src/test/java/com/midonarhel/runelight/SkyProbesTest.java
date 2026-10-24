package com.midonarhel.runelight;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyProbesTest
{
	private static final String DIR = "src/main/resources/com/midonarhel/runelight/";

	private static String read(String name) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(DIR + name)));
	}

	private static double define(String glsl, String name)
	{
		Matcher m = Pattern.compile("#define " + name + " ([0-9.]+)").matcher(glsl);
		assertTrue(name + " is defined", m.find());
		return Double.parseDouble(m.group(1));
	}

	@Test
	public void layoutMatchesTheShaders() throws Exception
	{
		String glsl = read("sky_probe_layout.glsl");
		assertEquals(SkyProbes.PROBES, define(glsl, "SKY_PROBES"), 0);
		assertEquals(SkyProbes.SPACING, define(glsl, "SKY_PROBE_SPACING"), 0);
		assertEquals(SkyProbes.LAYERS, define(glsl, "SKY_LAYERS"), 0);
		assertEquals(SkyProbes.LAYER_BASE, define(glsl, "SKY_LAYER_BASE"), 0);
		assertEquals(SkyProbes.LAYER_STEP, define(glsl, "SKY_LAYER_STEP"), 0);
	}

	/** The open hemisphere's coefficients, evaluated as the lookup does, give 1 for every normal. */
	@Test
	public void openSkyIsOneForEveryNormal() throws Exception
	{
		String update = read("sky_probes_frag.glsl");
		String lookup = read("skylight_ao.glsl");
		double sh0 = define(update, "SH_0"), sh1 = define(update, "SH_1");
		double irradiance0 = define(lookup, "SKY_IRRADIANCE_0"), irradiance1 = define(lookup, "SKY_IRRADIANCE_1");
		// the constant term and the one along up of a hemisphere of ones: integrals of SH_0 and SH_1 cos(theta)
		double c0 = 2 * Math.PI * sh0, cUp = Math.PI * sh1;
		for (int degrees = 0; degrees <= 180; degrees += 15)
		{
			double up = Math.cos(Math.toRadians(degrees));
			double m = Math.max(up, 0);
			double seen = irradiance0 * c0 + irradiance1 * cUp * m;
			assertEquals(1, seen / (Math.PI / 2 * (1 + m)), 1e-4);
		}
	}

	/** The harmonics of the hemisphere match numeric integration of it, and the lookup's open term is the exact one. */
	@Test
	public void irradianceOfTheOpenHemisphereIsExact()
	{
		int n = 200;
		for (int degrees = 0; degrees <= 90; degrees += 30)
		{
			double nx = Math.sin(Math.toRadians(degrees)), nUp = Math.cos(Math.toRadians(degrees));
			double sum = 0;
			for (int i = 0; i < n; ++i)
			{
				double up = (i + 0.5) / n;
				for (int j = 0; j < n; ++j)
				{
					double x = Math.sqrt(1 - up * up) * Math.cos(2 * Math.PI * (j + 0.5) / n);
					sum += Math.max(0, nx * x + nUp * up);
				}
			}
			assertEquals(Math.PI / 2 * (1 + nUp), sum * 2 * Math.PI / (n * n), 2e-3);
		}
	}
}
