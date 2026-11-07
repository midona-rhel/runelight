package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

public class LampBlockersTest
{
	@Test public void onlyStructuralObjectTypesBlock()
	{
		for (int type = 0; type <= 31; ++type)
			assertEquals("object type " + type, type <= 3 || type == 9 || type >= 12 && type <= 21, LampBlockers.blocks(type));
		assertFalse(LampBlockers.blocks(-1));
	}

	@Test public void capturePreservesSignedPositionsAndSkipsOtherVertexAttributes()
	{
		IntBuffer stream = IntBuffer.allocate(36);
		stream.position(18); // unrelated object before this wall
		for (int k = 0; k < 3; ++k)
			stream.put((-240 << 16) | (65535 & -32)).put(65535 & -64).put(12).put(13).put(14).put(15);
		List<Float> out = new ArrayList<>();
		LampBlockers.capture(stream, 18, out);
		assertEquals(Arrays.asList(-32f, -240f, -64f, -32f, -240f, -64f, -32f, -240f, -64f), out);
	}

	@Test public void gpuVisibilityUsesFiniteWallsDoorGapsAndSlopedRoofs() throws Exception
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		try (HeadlessGl context = new HeadlessGl())
		{
			// Two wall panels with a doorway between z=192 and z=320.
			List<float[]> walls = new ArrayList<>();
			panel(walls, 256, -200, 64, 0, 192);
			panel(walls, 256, -200, 320, 0, 448);
			assertVisibility(walls,
				new float[]{128,-100,128, 384,-100,128, 128,-240,128, 128,20,128, 128,-100,256, -100,-100,128, 256,-100,128},
				new float[]{384,-100,128, 128,-100,128, 384,-240,128, 384,20,128, 384,-100,256, 384,-100,128, 384,-100,128},
				new float[]{0,0,1,1,1,0,1});
			List<float[]> roof = Arrays.asList(
				new float[]{64,-200,64, 448,-400,64, 448,-400,448},
				new float[]{64,-200,64, 448,-400,448, 64,-200,448});
			assertVisibility(roof,
				new float[]{256,-100,256, 256,-500,256, 256,-100,256, 512,-100,256},
				new float[]{256,-500,256, 256,-100,256, 384,-100,256, 512,-500,256},
				new float[]{0,0,1,1});
			// Geometry replacement (e.g. removing/opening a door) has no baked-light handoff.
			assertVisibility(new ArrayList<>(), new float[]{128,-100,128}, new float[]{384,-100,128}, new float[]{1});
		}
	}

	@Test public void diagonalWallBlocksReceiversWithinOneUnitWithoutSelfBlocking() throws Exception
	{
		org.junit.Assume.assumeTrue("Boundary candidate is staged; set -Dlumina.blockerShader to test it",
			!System.getProperty("lumina.blockerShader", "").isEmpty());
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		List<float[]> wall = Arrays.asList(
			new float[]{0,-200,0, 512,-200,512, 512,0,512},
			new float[]{0,-200,0, 512,0,512, 0,0,0});
		try (HeadlessGl context = new HeadlessGl())
		{
			// The first receiver is only .25/sqrt(2) units behind the wall. A one-unit receiver slack leaked here.
			// A receiver on/in front of the wall stays open, and the existing lamp-end mounting tolerance stays intact.
			assertVisibility(wall,
				new float[]{256.25f,-100,256, 255.75f,-100,256, 256,-100,256, 320,-100,256, 320,-100,256},
				new float[]{192,-100,256, 192,-100,256, 192,-100,256, 255.75f,-100,256, 254,-100,256},
				new float[]{0,1,1,1,0});
		}
	}

	@Test public void simplifiedHierarchyMatchesExactGeometry() throws Exception
	{
		org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
		List<float[]> geometry = new ArrayList<>();
		panel(geometry, 256, -256, 0, 0, 192);
		panel(geometry, 256, -256, 320, 0, 512);
		// A tiled roof with a real hole: merging must never fill it.
		for (int x = 0; x < 512; x += 64) for (int z = 0; z < 512; z += 64)
		{
			if (x >= 192 && x < 320 && z >= 192 && z < 320) continue;
			geometry.add(new float[]{x,-300,z, x+64,-300,z, x+64,-300,z+64});
			geometry.add(new float[]{x,-300,z, x+64,-300,z+64, x,-300,z+64});
		}
		// Sloped and diagonal surfaces stay exact triangles.
		geometry.add(new float[]{0,-600,0, 512,-400,0, 512,-200,512});
		geometry.add(new float[]{0,-600,0, 512,-200,512, 0,-400,512});
		geometry.add(new float[]{0,0,0, 512,0,512, 512,-200,512});
		geometry.add(new float[]{0,0,0, 512,-200,512, 0,-200,0});
		java.util.Random random = new java.util.Random(117);
		try (HeadlessGl context = new HeadlessGl())
		{
			assertVisibility(geometry, new float[]{256,-280,256, 128,-280,256},
				new float[]{256,-320,256, 128,-320,256}, new float[]{1,0});
			for (int batch = 0; batch < 16; ++batch)
			{
				float[] from = new float[48], to = new float[48], expected = new float[16];
				for (int i = 0; i < 16; ++i)
				{
					for (int axis = 0; axis < 3; ++axis)
					{
						from[i*3+axis] = axis == 1 ? 100-random.nextFloat()*800 : random.nextFloat()*768-128;
						to[i*3+axis] = axis == 1 ? 100-random.nextFloat()*800 : random.nextFloat()*768-128;
					}
					expected[i] = exactOpen(geometry, from, to, i*3);
				}
				assertVisibility(geometry, from, to, expected);
			}
		}
	}

	// Independent plane intersection + oriented-edge containment, against unsimplified input.
	private static float exactOpen(List<float[]> triangles, float[] from, float[] to, int at)
	{
		double[] ray = {to[at]-from[at], to[at+1]-from[at+1], to[at+2]-from[at+2]};
		double margin = Math.min(0.49, 1 / Math.max(1, Math.sqrt(dot(ray, ray))));
		for (float[] t : triangles)
		{
			double[] e1 = {t[3]-t[0], t[4]-t[1], t[5]-t[2]}, e2 = {t[6]-t[0], t[7]-t[1], t[8]-t[2]};
			double[] normal = cross(e1, e2);
			double denominator = dot(normal, ray);
			if (Math.abs(denominator) < 1e-8) continue;
			double fraction = (normal[0]*(t[0]-from[at])+normal[1]*(t[1]-from[at+1])+normal[2]*(t[2]-from[at+2]))/denominator;
			if (fraction <= margin || fraction >= 1-margin) continue;
			boolean inside = true;
			for (int v=0; v<3; ++v)
			{
				int a=v*3, b=((v+1)%3)*3;
				double[] edge={t[b]-t[a],t[b+1]-t[a+1],t[b+2]-t[a+2]};
				double[] q={from[at]+ray[0]*fraction-t[a],from[at+1]+ray[1]*fraction-t[a+1],from[at+2]+ray[2]*fraction-t[a+2]};
				inside &= dot(cross(edge,q),normal) >= -1e-6;
			}
			if (inside) return 0;
		}
		return 1;
	}

	private static double[] cross(double[] a, double[] b)
	{
		return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]};
	}
	private static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }

	private static void panel(List<float[]> out, float x, float top, float z0, float bottom, float z1)
	{
		out.add(new float[]{x,top,z0, x,bottom,z0, x,bottom,z1});
		out.add(new float[]{x,top,z0, x,bottom,z1, x,top,z1});
	}

	private static void assertVisibility(List<float[]> triangles, float[] from, float[] to, float[] expected) throws Exception
	{
		Template template = new Template();
		String candidatePath = System.getProperty("lumina.blockerShader", "");
		if (!candidatePath.isEmpty())
		{
			String candidate = java.nio.file.Files.readString(java.nio.file.Paths.get(candidatePath));
			template.add(key -> key.equals("light/lamp.glsl") ? candidate : null);
		}
		template.add(key -> key.equals("probe.vert") ? "#version 330\nvoid main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0,1); }"
			: key.equals("probe.frag") ? "#version 330\nuniform vec3 cameraPos;\n#include cluster_config\n#include \"light/lamp.glsl\"\nuniform vec3 from[16], to[16]; out vec4 color; void main(){int i=int(gl_FragCoord.x); Lamp l=Lamp(to[i],1000.0,vec3(1),to[i].xz); color=vec4(lampOpen(l,from[i],from[i],true));}"
			: key.equals("cluster_config") ? "#define CLUSTERS_X 32\n#define CLUSTERS_Y 18\n#define CLUSTERS_Z 24\n#define CLUSTER_NEAR 50.0\n#define CLUSTER_FAR 25600.0\n#define CLUSTER_BASE 0\n" : null);
		template.addInclude(LuminaPlugin.class);
		int program = new Shader().add(GL_VERTEX_SHADER, "probe.vert").add(GL_FRAGMENT_SHADER, "probe.frag").compile(template);
		int buffer = glGenBuffers(), texture = glGenTextures(), vao = glGenVertexArrays(), fbo = glGenFramebuffers(), rb = glGenRenderbuffers();
		try
		{
			glBindBuffer(GL_TEXTURE_BUFFER, buffer);
			glBufferData(GL_TEXTURE_BUFFER, LampBlockers.pack(triangles), GL_STATIC_DRAW);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_BUFFER, texture);
			glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer);
			glBindVertexArray(vao);
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			glBindRenderbuffer(GL_RENDERBUFFER, rb);
			glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA32F, expected.length, 1);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
			assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
			glViewport(0, 0, expected.length, 1);
			glUseProgram(program);
			glUniform1i(glGetUniformLocation(program, "lampBlockers"), 0);
			glUniform3fv(glGetUniformLocation(program, "from"), from);
			glUniform3fv(glGetUniformLocation(program, "to"), to);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			FloatBuffer result = BufferUtils.createFloatBuffer(expected.length * 4);
			glReadPixels(0, 0, expected.length, 1, GL_RGBA, GL_FLOAT, result);
			assertEquals(GL_NO_ERROR, glGetError());
			for (int i = 0; i < expected.length; ++i) assertEquals("ray " + i, expected[i], result.get(i * 4), 0.001f);
		}
		finally
		{
			glUseProgram(0); glBindFramebuffer(GL_FRAMEBUFFER, 0);
			glDeleteProgram(program); glDeleteBuffers(buffer); glDeleteTextures(texture);
			glDeleteVertexArrays(vao); glDeleteFramebuffers(fbo); glDeleteRenderbuffers(rb);
		}
	}
}
