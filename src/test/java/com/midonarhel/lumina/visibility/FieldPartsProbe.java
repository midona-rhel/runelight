package com.midonarhel.lumina.visibility;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;

/** Scratch: the field's parts timed, median of 15, on every skygrid dump. */
public class FieldPartsProbe
{
	@Test
	public void parts() throws Exception
	{
		Path dir = Paths.get(System.getProperty("user.home"), ".runelite", "lumina", "dumps");
		Assume.assumeTrue(Files.isDirectory(dir));
		try (java.util.stream.Stream<Path> files = Files.list(dir))
		{
			for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().startsWith("skygrid-")).sorted()::iterator)
			{
				SkyGridReplay.Dump d = SkyGridReplay.read(file);
				float falloff = SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH);
				double[][] t = new double[5][15];
				for (int r = -3; r < 15; ++r)
				{
					long a = System.nanoTime();
					boolean[] covered = SkyField.covered(d.rooms, d.floors, 0);
					float[] light = SkyField.propagate(d.walls, covered, 0, falloff);
					long b = System.nanoTime();
					int[] g = SkyField.cornerRoots(d.walls, 0);
					long c = System.nanoTime();
					float[] cl = SkyField.cornerLight(d.walls, 0, covered, falloff, g, null);
					long e = System.nanoTime();
					SkyField.pack(d.walls, 0, light, covered, cl, g);
					long f = System.nanoTime();
					if (r >= 0)
					{
						t[0][r] = (b - a) / 1e6;
						t[1][r] = (c - b) / 1e6;
						t[2][r] = (e - c) / 1e6;
						t[3][r] = (f - e) / 1e6;
						t[4][r] = (f - a) / 1e6;
					}
				}
				for (double[] x : t) Arrays.sort(x);
				System.out.printf("%s: flood %.2f, groups %.2f, distances %.2f, pack %.2f, all %.2f ms%n", file.getFileName(), t[0][7], t[1][7], t[2][7], t[3][7], t[4][7]);
			}
		}
	}
}
