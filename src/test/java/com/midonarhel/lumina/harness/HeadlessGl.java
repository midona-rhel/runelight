package com.midonarhel.lumina.harness;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.CGL;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

/**
 * A GL context with no window for the offline renderer: on macOS a CGL context of the 4.1 core profile (the client's
 * on the Mac too), current on the calling thread. Windows and Linux would take a hidden GLFW window; not done (the
 * offline renderer runs on the Mac).
 */
public final class HeadlessGl implements AutoCloseable
{
	private static final int CGL_CORE_4_1 = 0x4100;

	private final long context;
	public final GLCapabilities capabilities;

	public HeadlessGl()
	{
		if (Platform.get() != Platform.MACOSX)
		{
			throw new UnsupportedOperationException("the offline renderer makes its GL context with CGL (macOS) only");
		}
		try (MemoryStack s = MemoryStack.stackPush())
		{
			var attribs = s.ints(CGL.kCGLPFAOpenGLProfile, CGL_CORE_4_1, CGL.kCGLPFAAccelerated, CGL.kCGLPFAColorSize, 24,
				CGL.kCGLPFAAlphaSize, 8, CGL.kCGLPFADepthSize, 24, CGL.kCGLPFAStencilSize, 8, 0);
			PointerBuffer pix = s.mallocPointer(1);
			int e = CGL.CGLChoosePixelFormat(attribs, pix, s.mallocInt(1));
			if (e != 0 || pix.get(0) == 0)
			{
				throw new IllegalStateException("CGLChoosePixelFormat: " + e);
			}
			PointerBuffer ctx = s.mallocPointer(1);
			e = CGL.CGLCreateContext(pix.get(0), 0, ctx);
			CGL.CGLDestroyPixelFormat(pix.get(0));
			if (e != 0)
			{
				throw new IllegalStateException("CGLCreateContext: " + e);
			}
			context = ctx.get(0);
		}
		CGL.CGLSetCurrentContext(context);
		capabilities = GL.createCapabilities();
		if (!capabilities.OpenGL33)
		{
			throw new IllegalStateException("OpenGL 3.3 is required");
		}
	}

	@Override
	public void close()
	{
		GL.setCapabilities(null);
		CGL.CGLSetCurrentContext(0);
		CGL.CGLDestroyContext(context);
	}
}
