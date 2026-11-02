package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The triangles each section of a frame draws (GpuSections counting GL_PRIMITIVES_GENERATED), per dump at hour 21:
 * how much geometry a view sends, apart from what it costs. ./gradlew primCount -Pargs="nightref tree1"
 */
public final class PrimCount
{
	public static void main(String[] args) throws Exception
	{
		for (String name : args)
		{
			SceneDump.Loaded dump = RenderDump.load(name);
			DumpRenderer.Options o = new DumpRenderer.Options();
			o.hour = 21;
			try (DumpRenderer r = new DumpRenderer(dump, o))
			{
				r.render();
				com.midonarhel.lumina.dev.GpuSections.reset();
				com.midonarhel.lumina.dev.GpuSections.target = GL_PRIMITIVES_GENERATED;
				com.midonarhel.lumina.dev.GpuSections.on = true;
				r.frame();
				String[] names = new String[64];
				double[] n = new double[64];
				int c = com.midonarhel.lumina.dev.GpuSections.read(names, n);
				com.midonarhel.lumina.dev.GpuSections.on = false;
				for (int i = 0; i < c; ++i)
				{
					if (n[i] > 0) System.out.printf("PRIMS %s %-36s %,.0f%n", name, names[i], n[i]);
				}
			}
		}
		System.exit(0);
	}
}
