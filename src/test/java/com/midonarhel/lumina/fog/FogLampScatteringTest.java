package com.midonarhel.lumina.fog;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.lwjgl.BufferUtils;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

public class FogLampScatteringTest
{
    @Test public void scatteringUsesExtinctionAcrossShelteredAndDenseSegments() throws Exception
    {
        org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
        String source;
        try (InputStream in = LuminaPlugin.class.getResourceAsStream("fog_frag.glsl"))
        {
            assertNotNull(in);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String helper = source.substring(source.indexOf("float fogLampExtinction["),
            source.indexOf("// Light from the lamps scattered"));
        String fragment = "#version 330\n#define FOG_MAX_STEPS 16\n" + helper
            + "uniform float sigma[4]; uniform vec2 intervals[8]; out vec4 color;\n"
            + "void main(){fogLampT0=100.0; fogLampDt=1000.0; fogLampSegments=4; float trans=1.0;"
            + "for(int i=0;i<4;i++){fogLampExtinction[i]=sigma[i];fogLampIncoming[i]=trans;trans*=exp(-sigma[i]*fogLampDt);}"
            + "vec2 span=intervals[int(gl_FragCoord.x)];color=vec4(fogLampScattering(span.x,span.y));}";
        Template template = new Template();
        template.add(key -> key.equals("fog-test.vert") ? "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}"
            : key.equals("fog-test.frag") ? fragment : null);
        try (HeadlessGl context = new HeadlessGl())
        {
            int program = new Shader().add(GL_VERTEX_SHADER,"fog-test.vert").add(GL_FRAGMENT_SHADER,"fog-test.frag").compile(template);
            int vao=glGenVertexArrays(), fbo=glGenFramebuffers(), rb=glGenRenderbuffers();
            try
            {
                glBindVertexArray(vao); glBindFramebuffer(GL_FRAMEBUFFER,fbo); glBindRenderbuffer(GL_RENDERBUFFER,rb);
                glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA32F,8,1);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,rb);
                assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
                glViewport(0,0,8,1); glUseProgram(program);
                float[] intervals={-100,5000, 100,1100, 1100,2100, 1600,3600, 200,300, 3500,9000, -100,50, 5000,6000};
                glUniform2fv(glGetUniformLocation(program,"intervals"),intervals);
                // Empty/sheltered fog; thin homogeneous fog; optically thick long path; mixed shelter and density.
                for(float[] sigma : new float[][]{{0,0,0,0},{.0001f,.0001f,.0001f,.0001f},{.01f,.01f,.01f,.01f},{.0002f,0,.001f,.0001f}})
                {
                    glUniform1fv(glGetUniformLocation(program,"sigma"),sigma);
                    glDrawArrays(GL_TRIANGLES,0,3);
                    FloatBuffer pixels=BufferUtils.createFloatBuffer(32);
                    glReadPixels(0,0,8,1,GL_RGBA,GL_FLOAT,pixels);
                    assertEquals(GL_NO_ERROR,glGetError());
                    for(int i=0;i<8;i++)
                    {
                        double expected=Math.exp(-opticalDepth(sigma,intervals[i*2]))-Math.exp(-opticalDepth(sigma,intervals[i*2+1]));
                        float actual=pixels.get(i*4);
                        assertEquals("interval "+i,expected,actual,0.00001);
                        assertTrue("scattering is bounded",actual>=0 && actual<=1.000001f);
                    }
                }
            }
            finally
            {
                glUseProgram(0);glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteProgram(program);
                glDeleteVertexArrays(vao);glDeleteFramebuffers(fbo);glDeleteRenderbuffers(rb);
            }
        }
    }

    // Independent oracle: integrate optical depth by explicit overlap with every density segment.
    private static double opticalDepth(float[] sigma,float distance)
    {
        double depth=0;
        for(int i=0;i<sigma.length;i++) depth+=sigma[i]*Math.max(0,Math.min(1000,distance-(100+i*1000)));
        return depth;
    }
}
