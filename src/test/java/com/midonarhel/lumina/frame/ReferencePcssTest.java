package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

/** Raw PCSS invariants, without AO denoise or shadow upsampling. */
public class ReferencePcssTest {
  @Test public void planesStayLitAndPenumbraGrowsWithGapInBothClipConventions() throws Exception {
    org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get() != org.lwjgl.system.Platform.LINUX);
    String shaderPath = System.getProperty("lumina.pcssShader");
    String pcss = shaderPath != null ? Files.readString(Path.of(shaderPath))
      : new String(com.midonarhel.lumina.LuminaPlugin.class.getResourceAsStream("shadow_pcss.glsl").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    try (HeadlessGl context = new HeadlessGl()) {
      Template t = new Template();
      t.add(key -> key.equals("v") ? "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}"
        : key.equals("f") ? "#version 330\nuniform int depthZeroToOne;\n" + pcss +
        "\nuniform float planeSlope;out vec4 color;void main(){float u=.375+gl_FragCoord.x/1024.;float z=.6+planeSlope*(u-.5);vec3 p=vec3((u-.5)*256.,0.,(z-.5)*2000.);vec3 n=normalize(vec3(-planeSlope*2000./256.,0,1));float radius;float v=shadowFactor(p,n,0.,radius);color=vec4(v,radius,0,1);}"
        : null);
      int program = new Shader().add(GL_VERTEX_SHADER,"v").add(GL_FRAGMENT_SHADER,"f").compile(t);
      int map=glGenTextures(), out=glGenTextures(), fbo=glGenFramebuffers(), vao=glGenVertexArrays();
      int raw=glGenSamplers(), compare=glGenSamplers();
      try {
        glBindTexture(GL_TEXTURE_2D,out);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,256,1,0,GL_RGBA,GL_FLOAT,(float[])null);
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,out,0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));
        for(int sampler:new int[]{raw,compare}) {
          glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,sampler==raw?GL_NEAREST:GL_LINEAR);
          glSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER,sampler==raw?GL_NEAREST:GL_LINEAR);
          glSamplerParameteri(sampler,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glSamplerParameteri(sampler,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        }
        glSamplerParameteri(compare,GL_TEXTURE_COMPARE_MODE,GL_COMPARE_REF_TO_TEXTURE);
        glSamplerParameteri(compare,GL_TEXTURE_COMPARE_FUNC,GL_LEQUAL);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,map);glBindSampler(0,raw);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,map);glBindSampler(1,compare);
        glUseProgram(program);glBindVertexArray(vao);glViewport(0,0,256,1);
        glUniform1i(glGetUniformLocation(program,"shadowRaw"),0);glUniform1i(glGetUniformLocation(program,"shadowCompare"),1);
        glUniform1f(glGetUniformLocation(program,"shadowRadius"),128);
        glUniform1f(glGetUniformLocation(program,"shadowDepthRange"),1000);
        glUniform1f(glGetUniformLocation(program,"shadowMapSize"),1024);
        glUniform1i(glGetUniformLocation(program,"sunIsCaster"),1);
        for(int zero:new int[]{0,1}) {
          glUniform1i(glGetUniformLocation(program,"depthZeroToOne"),zero);
          float[] matrix=new float[16];matrix[0]=matrix[5]=1f/128;matrix[10]=zero==1?1f/2000:1f/1000;matrix[14]=zero==1?.5f:0;matrix[15]=1;
          glUniformMatrix4fv(glGetUniformLocation(program,"lightProjRel"),false,matrix);
          for(float slope:new float[]{0,.3f,-.3f}) {
            uploadPlane(slope,0,false);
            glUniform1f(glGetUniformLocation(program,"planeSlope"),slope);
            float[] values=draw();
            for(int x=0;x<256;x++) assertEquals("unoccluded plane, clip="+zero+", slope="+slope,1,values[x*4],.0001f);
          }
          glUniform1f(glGetUniformLocation(program,"planeSlope"),0);
          uploadPlane(0,8,true);float[] near=draw();
          uploadPlane(0,512,true);float[] far=draw();
          assertEquals("blocked interior",0,far[0],.0001f);
          assertEquals("open exterior",1,far[255*4],.0001f);
          int narrow=transitionWidth(near),wide=transitionWidth(far);
          assertTrue("contact remains sharp: "+narrow,narrow<=3);
          assertTrue("gap grows penumbra: "+narrow+" -> "+wide,wide>narrow+8);
          for(int x=1;x<256;x++) assertTrue("single edge has no reversing grain",far[x*4]+.0001f>=far[(x-1)*4]);
          // Fixed shadow-space sampling must not depend on screen-space sample rotation.
          float[] repeat=draw();assertArrayEquals(far,repeat,0);
        }
        assertEquals(GL_NO_ERROR,glGetError());
      } finally {
        glBindSampler(0,0);glBindSampler(1,0);glDeleteSamplers(raw);glDeleteSamplers(compare);
        glDeleteTextures(map);glDeleteTextures(out);glDeleteFramebuffers(fbo);glDeleteVertexArrays(vao);glDeleteProgram(program);
      }
    }
  }
  private static void uploadPlane(float slope,float gap,boolean edge) {
    float[] depth=new float[1024*1024];
    for(int y=0;y<1024;y++)for(int x=0;x<1024;x++)depth[y*1024+x]=edge?(x<512?.6f-gap/2000:1):.6f+slope*((x+.5f)/1024-.5f);
    glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,1024,1024,0,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
  }
  private static float[] draw(){glDrawArrays(GL_TRIANGLES,0,3);float[] p=new float[256*4];glReadPixels(0,0,256,1,GL_RGBA,GL_FLOAT,p);return p;}
  private static int transitionWidth(float[] p){int n=0;for(int x=0;x<256;x++)if(p[x*4]>.02&&p[x*4]<.98)n++;return n;}
}
