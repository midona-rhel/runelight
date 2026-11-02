package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import com.midonarhel.lumina.visibility.WallMap;
import java.nio.FloatBuffer;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.Platform;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The light map as the shaders read it (light/lamp_map.glsl through lamp.glsl's lampMapVisibility), on the GPU, against
 * the bake read on the CPU: LightMapBuffer's packing, the sparse blocks and their index, the storeys' floors and the
 * filter between cells and layers (no walls bound: the plain filter) give what the old renderer's RGBA8 3D texture gave,
 * a lamp found in its cell by its id.
 */
public class LampMapShaderTest
{
	private static final String FRAG = "#version 330\n"
		+ "#include cluster_config\n"
		+ "uniform vec3 cameraPos;\n"
		+ "#include \"light/lamp.glsl\"\n"
		+ "uniform vec3 probeAt[" + 64 + "];\n"
		+ "uniform int probeId;\n"
		+ "out vec4 color;\n"
		+ "void main() {\n"
		+ "  vec3 w = probeAt[int(gl_FragCoord.x)];\n"
		+ "  vec4 h = lampMapHeader();\n"
		+ "  color = vec4(lampMapVisibility(probeId, w, w), float(lampMapPlane(w, h)), h.x, 1.0);\n"
		+ "}\n";
	private static final String VERT = "#version 330\n"
		+ "void main() {\n"
		+ "  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
		+ "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
		+ "}\n";

	/** The bake's filtered visibility of a light at a world point (the shader's plain filter, as the 3D texture's). */
	private static float expected(LightMap map, LightMap.Source s, int plane, float wx, float wy, float wz, BakeScene scene)
	{
		int off = BakeScene.OFFSET * 4;
		float cx = wx / 32 + off, cz = wz / 32 + off;
		LightMapBake.Grid g = null;
		for (LightMapBake.Grid k : s.grids)
		{
			if (k.plane == plane)
			{
				g = k;
			}
		}
		if (g == null)
		{
			return 0;
		}
		int tx = (int) Math.floor(cx / 4), tz = (int) Math.floor(cz / 4);
		float height = scene.floors[(plane * BakeScene.SIZE + tx) * BakeScene.SIZE + tz] - wy;
		float layer = Math.max(0, Math.min(LightMapBake.HEIGHT_LAYERS - 1, height / LightMapBake.LAYER_HEIGHT - 0.5f));
		int below = (int) Math.floor(layer), above = Math.min(below + 1, LightMapBake.HEIGHT_LAYERS - 1);
		float up = layer - below;
		float fx = cx - 0.5f, fz = cz - 0.5f;
		int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
		float ax = fx - ix, az = fz - iz;
		float v = 0;
		for (int dz = 0; dz < 2; ++dz)
		{
			for (int dx = 0; dx < 2; ++dx)
			{
				float weight = (dx == 0 ? 1 - ax : ax) * (dz == 0 ? 1 - az : az);
				int x = ix + dx - g.x0, z = iz + dz - g.z0;
				if (x < 0 || z < 0 || x >= g.w || z >= g.h)
				{
					continue;
				}
				int c = (z * g.w + x) * LightMapBake.HEIGHT_LAYERS;
				// as the map holds it: a byte
				float lo = Math.round(g.c[c + below] * 255) / 255f, hi = Math.round(g.c[c + above] * 255) / 255f;
				v += weight * (lo + (hi - lo) * up);
			}
		}
		return v;
	}

	/** A lamp east of a north-south wall with a doorway, a pillar between: baked, and put into a LightMapBuffer. */
	private static final class Baked
	{
		final BakeScene scene = new BakeScene().verticalWall(0, 20, 10, 30, 19, 20).solid(0, 16, 18, 16, 18);
		final LightBlockers blockers = scene.blockers();
		final LightMap map = new LightMap(0);
		final LightMap.Source s = new LightMap.Source(0, BakeScene.world(14.5f), -150, BakeScene.world(18.5f), 7 * 128, 1, 0.6f,
			0.3f, 5, false, 1, 0);
		final LightMapBuffer buffer = new LightMapBuffer();

		Baked()
		{
			map.setBlockers(blockers);
			assertTrue(map.add(s));
			map.runTasks();
			buffer.setScene(blockers, blockers.cells / LightMap.BLOCK);
			while (map.update(1000, buffer) > 0)
			{
				map.runTasks();
			}
			assertTrue(LightMap.isLive(s));
		}
	}

	/**
	 * What the shader reads at the probes (world x, y, z each) for the lamp: per probe the visibility, the storey and the
	 * header's blocks a side. With walls (null: none bound), they are bound as LampWalls packs them.
	 */
	private static float[] read(Baked b, float[] probes, LampWalls walls) throws Exception
	{
		int n = probes.length / 3;
		Template template = new Template();
		template.add(key -> key.equals("test_frag.glsl") ? FRAG : key.equals("test_vert.glsl") ? VERT : null);
		template.add(key -> key.equals("cluster_config") ? "#define CLUSTERS_X " + LightClusters.CLUSTERS_X + "\n"
			+ "#define CLUSTERS_Y " + LightClusters.CLUSTERS_Y + "\n"
			+ "#define CLUSTERS_Z " + LightClusters.CLUSTERS_Z + "\n"
			+ "#define CLUSTER_ENTRIES " + LightClusters.CLUSTER_ENTRIES + "\n"
			+ "#define CLUSTER_NEAR " + LightClusters.NEAR + "\n"
			+ "#define CLUSTER_FAR " + LightClusters.FAR + "\n"
			+ "#define ROOM_FILL_BASE " + LightClusters.ROOM_FILL_BASE + "\n"
			+ "#define LAMP_WALLS_BASE " + LightClusters.LAMP_WALLS_BASE + "\n"
			+ "#define LAMP_WALL_LAYER_TEXELS " + LampWalls.LAYER_TEXELS + "\n"
			+ "#define MAP_LAMPS_BASE " + LightClusters.MAP_LAMPS_BASE + "\n" + LampReach.defines() + LightMapBuffer.defines() : null);
		template.addInclude(LuminaPlugin.class);
		int program = new Shader().add(GL_VERTEX_SHADER, "test_vert.glsl").add(GL_FRAGMENT_SHADER, "test_frag.glsl")
			.compile(template);

		// the light buffer: the walls (or none) and the map after the fixed part
		int buf = glGenBuffers(), tex = glGenTextures();
		glBindBuffer(GL_TEXTURE_BUFFER, buf);
		glBufferData(GL_TEXTURE_BUFFER, (long) (LightClusters.LIGHT_MAP_BASE + b.buffer.texels()) * 16, GL_STATIC_DRAW);
		if (walls != null)
		{
			glBufferSubData(GL_TEXTURE_BUFFER, (long) LightClusters.LAMP_WALLS_BASE * 16, walls.texels());
		}
		b.buffer.invalidate(0);
		b.buffer.upload(0, LightClusters.LIGHT_MAP_BASE);
		glBindTexture(GL_TEXTURE_BUFFER, tex);
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buf);
		int vao = glGenVertexArrays();
		glBindVertexArray(vao);

		int fbo = glGenFramebuffers(), rb = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rb);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA32F, n, 1);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
		assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
		glViewport(0, 0, n, 1);

		glUseProgram(program);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_BUFFER, tex);
		glUniform1i(glGetUniformLocation(program, "lightData"), 0);
		glUniform3f(glGetUniformLocation(program, "cameraPos"), 0, 0, 0);
		float[] h = walls != null ? walls.header() : new float[5];
		glUniform4f(glGetUniformLocation(program, "lampWallsHeader"), h[0], h[1], h[2], h[3]);
		glUniform1f(glGetUniformLocation(program, "lampWallsEase"), 1);
		glUniform1i(glGetUniformLocation(program, "probeId"), b.s.id);
		glUniform3fv(glGetUniformLocation(program, "probeAt"), probes);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		FloatBuffer out = BufferUtils.createFloatBuffer(n * 4);
		glReadPixels(0, 0, n, 1, GL_RGBA, GL_FLOAT, out);
		assertEquals(GL_NO_ERROR, glGetError());
		float[] result = new float[n * 4];
		out.get(result);

		glDeleteFramebuffers(fbo);
		glDeleteRenderbuffers(rb);
		glDeleteVertexArrays(vao);
		glDeleteTextures(tex);
		glDeleteBuffers(buf);
		glDeleteProgram(program);
		return result;
	}

	@Test
	public void theShadersReadTheBakeAsItWasBaked() throws Exception
	{
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		Baked b = new Baked();
		// probes along a row through the pillar's shade and the doorway, at the ground and a little above it
		int n = 64;
		float[] probes = new float[n * 3];
		for (int k = 0; k < n; ++k)
		{
			float tile = 13f + k * 0.17f;
			probes[k * 3] = BakeScene.world(tile);
			probes[k * 3 + 1] = (k & 1) == 0 ? -10 : -95;
			probes[k * 3 + 2] = BakeScene.world(18.37f + (k % 3) * 0.29f);
		}
		float[] out;
		try (HeadlessGl gl = new HeadlessGl())
		{
			out = read(b, probes, null);
		}
		int lit = 0, shaded = 0;
		StringBuilder bad = new StringBuilder();
		for (int k = 0; k < n; ++k)
		{
			float gpu = out[k * 4];
			float cpu = expected(b.map, b.s, 0, probes[k * 3], probes[k * 3 + 1], probes[k * 3 + 2], b.scene);
			assertEquals(0, out[k * 4 + 1], 0);
			assertEquals(b.blockers.cells / LightMap.BLOCK, out[k * 4 + 2], 0);
			if (Math.abs(gpu - cpu) > 0.01f)
			{
				bad.append(String.format("probe %d at %.0f, %.0f, %.0f: shader %.3f, bake %.3f%n", k, probes[k * 3], probes[k * 3 + 1],
					probes[k * 3 + 2], gpu, cpu));
			}
			lit += cpu > 0.9f ? 1 : 0;
			shaded += cpu > 0.02f && cpu < 0.6f ? 1 : 0;
		}
		assertTrue(bad.toString(), bad.length() == 0);
		// the row holds full light, the shade's soft edge and what lies beyond the wall
		assertTrue("lit " + lit, lit > 5);
		assertTrue("shaded " + shaded, shaded > 2);
	}

	/**
	 * With the walls bound, a read beside a wall takes nothing from the cells across it: just past the wall, away from
	 * the doorway, it is the bake of the cells on its own side (the lamp's light filtered across the wall would show
	 * there), and just before it, its own side's.
	 */
	@Test
	public void aReadNeverTakesLightFromAcrossAWall() throws Exception
	{
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		Baked b = new Baked();
		LampWalls walls = new LampWalls();
		WallMap wallMap = b.blockers.walls;
		long until = System.nanoTime() + 10_000_000_000L;
		while (walls.header()[0] == 0)
		{
			assertTrue("the walls packed", System.nanoTime() < until);
			walls.update(wallMap, null, b.scene.filled, null, null, 0);
			Thread.sleep(2);
		}
		// across the wall at x = 20 tiles (cells 79 | 80), rows 13 to 16 (no doorway), at the ground
		int n = 16;
		float[] probes = new float[n * 3];
		for (int k = 0; k < n; ++k)
		{
			float tile = k < 8 ? 19.97f - (k & 3) * 0.02f : 20.03f + (k & 3) * 0.02f;
			probes[k * 3] = BakeScene.world(tile);
			probes[k * 3 + 1] = -10;
			probes[k * 3 + 2] = BakeScene.world(13.4f + (k % 8) * 0.37f);
		}
		float[] bound, unbound;
		try (HeadlessGl gl = new HeadlessGl())
		{
			bound = read(b, probes, walls);
			unbound = read(b, probes, null);
		}
		StringBuilder bad = new StringBuilder();
		float bleed = 0;
		for (int k = 0; k < n; ++k)
		{
			// the cell column on the probe's own side alone, filtered along the wall
			float cpu = expectedColumn(b, probes[k * 3], probes[k * 3 + 1], probes[k * 3 + 2]);
			if (Math.abs(bound[k * 4] - cpu) > 0.01f)
			{
				bad.append(String.format("probe %d at %.1f, %.1f: shader %.3f, own side %.3f%n", k, probes[k * 3] / 128 + BakeScene.OFFSET,
					probes[k * 3 + 2] / 128 + BakeScene.OFFSET, bound[k * 4], cpu));
			}
			if (k >= 8)
			{
				bleed = Math.max(bleed, unbound[k * 4] - bound[k * 4]);
			}
		}
		assertTrue(bad.toString(), bad.length() == 0);
		// without the walls the filter takes the lit side's light past the wall: the test sees the difference
		assertTrue("bleed " + bleed, bleed > 0.05f);
		walls.free();
	}

	/** The bake read along the column of cells the point is in only (the filter along z, and across the layers). */
	private static float expectedColumn(Baked b, float wx, float wy, float wz)
	{
		int off = BakeScene.OFFSET * 4;
		float cx = wx / 32 + off, cz = wz / 32 + off;
		LightMapBake.Grid g = null;
		for (LightMapBake.Grid k : b.s.grids)
		{
			if (k.plane == 0)
			{
				g = k;
			}
		}
		int tx = (int) Math.floor(cx / 4), tz = (int) Math.floor(cz / 4);
		float height = b.scene.floors[tx * BakeScene.SIZE + tz] - wy;
		float layer = Math.max(0, Math.min(LightMapBake.HEIGHT_LAYERS - 1, height / LightMapBake.LAYER_HEIGHT - 0.5f));
		int below = (int) Math.floor(layer), above = Math.min(below + 1, LightMapBake.HEIGHT_LAYERS - 1);
		float up = layer - below;
		int x = (int) Math.floor(cx) - g.x0;
		float fz = cz - 0.5f;
		int iz = (int) Math.floor(fz);
		float az = fz - iz;
		float v = 0;
		for (int dz = 0; dz < 2; ++dz)
		{
			int z = iz + dz - g.z0;
			if (x < 0 || z < 0 || x >= g.w || z >= g.h)
			{
				continue;
			}
			int c = (z * g.w + x) * LightMapBake.HEIGHT_LAYERS;
			float lo = Math.round(g.c[c + below] * 255) / 255f, hi = Math.round(g.c[c + above] * 255) / 255f;
			v += (dz == 0 ? 1 - az : az) * (lo + (hi - lo) * up);
		}
		return v;
	}
}
