package com.midonarhel.lumina.visibility;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;

/**
 * What the skylight's field and room flags cost on the CPU (their workers: SkyFieldMap's, SkyGridMap's), on every
 * skygrid-*.bin.gz in ~/.runelite/lumina/dumps: the field (covered, the flood, the packing; with cornerLight where this
 * build has it) and SkyGrid.setRooms, the median of -Dlumina.runs runs (default 15) each after two untimed, and the
 * textures' sizes. Appends to -Dlumina.out.
 */
public class CpuCostProbe
{
	@Test
	public void probe() throws Exception
	{
		Path dir = Paths.get(System.getProperty("user.home"), ".runelite", "lumina", "dumps");
		Assume.assumeTrue(System.getProperty("lumina.out") != null && Files.isDirectory(dir));
		int runs = Integer.parseInt(System.getProperty("lumina.runs", "15"));
		Method cornerLight = null, cornerRoots = null, pack6 = null;
		for (Method m : SkyField.class.getDeclaredMethods())
		{
			if (m.getName().equals("cornerLight") && m.getParameterCount() == 6) cornerLight = m;
			if (m.getName().equals("cornerRoots")) cornerRoots = m;
			if (m.getName().equals("pack") && m.getParameterCount() == 6) pack6 = m;
		}
		StringBuilder out = new StringBuilder();
		try (java.util.stream.Stream<Path> files = Files.list(dir))
		{
			for (Path file : (Iterable<Path>) files.filter(f -> f.getFileName().toString().startsWith("skygrid-")).sorted()::iterator)
			{
				SkyGridReplay.Dump d = SkyGridReplay.read(file);
				float falloff = SkyField.falloffOf(com.midonarhel.lumina.LuminaPlugin.SKYLIGHT_REACH);
				double[] field = new double[runs], corners = new double[runs], rooms = new double[runs];
				SkyGrid s = SkyGridReplay.retrace(d);
				float[] packed = null;
				for (int r = -2; r < runs; ++r)
				{
					long t0 = System.nanoTime();
					boolean[] covered = SkyField.covered(d.rooms, d.floors, 0);
					float[] light = SkyField.propagate(d.walls, covered, 0, falloff);
					long t1 = System.nanoTime();
					int[] groups = cornerRoots != null ? (int[]) cornerRoots.invoke(null, d.walls, 0) : null;
					float[] c = cornerLight != null ? (float[]) cornerLight.invoke(null, d.walls, 0, covered, falloff, groups, null) : null;
					long t2 = System.nanoTime();
					packed = pack6 != null ? (float[]) pack6.invoke(null, d.walls, 0, light, covered, c, groups) : SkyField.pack(d.walls, 0, light, covered);
					long t3 = System.nanoTime();
					s.setRooms(d.rooms);
					long t4 = System.nanoTime();
					if (r >= 0)
					{
						field[r] = (t3 - t0) / 1e6;
						corners[r] = (t2 - t1) / 1e6;
						rooms[r] = (t4 - t3) / 1e6;
					}
				}
				Arrays.sort(field);
				Arrays.sort(corners);
				Arrays.sort(rooms);
				long fieldBytes = (long) packed.length / 4 * 8, gridBytes = (long) s.texels.capacity() * 2;
				out.append(String.format("%s: field %.2f ms (of it cornerLight %.2f), setRooms %.2f ms; field texture %dx%d RGBA16F %.2f MB, sky grid texture %.2f MB%n",
					file.getFileName(), field[runs / 2], corners[runs / 2], rooms[runs / 2],
					d.walls.size * SkyField.TEXELS_PER_TILE, packed.length / 4 / (d.walls.size * SkyField.TEXELS_PER_TILE),
					fieldBytes / 1048576.0, gridBytes / 1048576.0));
			}
		}
		System.out.print(out);
		Files.writeString(Path.of(System.getProperty("lumina.out")), out.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
