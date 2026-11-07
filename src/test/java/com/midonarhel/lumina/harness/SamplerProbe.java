package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.frame.Composite;
import java.nio.IntBuffer;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/** The active samplers of each composite program (-Dlumina.dump), against the 16 a macOS fragment shader may use. */
public class SamplerProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		try (DumpRenderer r = new DumpRenderer(RenderDump.load(name), new DumpRenderer.Options()))
		{
			r.render();
			for (Composite.CompositeProgram c : r.plugin.composite.composites)
			{
				if (c.program == 0) continue; // inactive MSAA passes are not compiled
				int p = c.program, n = glGetProgrami(p, GL_ACTIVE_UNIFORMS);
				StringBuilder s = new StringBuilder();
				int count = 0;
				for (int i = 0; i < n; ++i)
				{
					IntBuffer size = BufferUtils.createIntBuffer(1), type = BufferUtils.createIntBuffer(1);
					String u = glGetActiveUniform(p, i, size, type);
					int t = type.get(0);
					boolean sampler = t >= GL_SAMPLER_1D && t <= GL_SAMPLER_2D_RECT_SHADOW || t >= GL_SAMPLER_1D_ARRAY && t <= GL_UNSIGNED_INT_SAMPLER_BUFFER
						|| t == 0x9108 || t == 0x9109 || t == 0x910A || t == 0x910B || t == 0x910C || t == 0x910D;
					if (sampler)
					{
						++count;
						s.append(' ').append(u);
					}
				}
				System.out.printf("SAMPLERS pass %d: %d:%s%n", c.pass, count, s);
			}
		}
	}
}
