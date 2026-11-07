package com.midonarhel.lumina.frame;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.lwjgl.opengl.GL33C.*;

/** Evaluate the actual sky shader rather than duplicating lighting equations on the CPU. */
public class NightLightingTest {
 @Test public void cloudsAttenuateMoonWithoutRemovingBlueOrReplacingItWithNightSky() throws Exception {
  org.junit.Assume.assumeTrue(org.lwjgl.system.Platform.get()!=org.lwjgl.system.Platform.LINUX);
  try(HeadlessGl context=new HeadlessGl()) {
   int vao=glGenVertexArrays(),fbo=glGenFramebuffers(),texture=glGenTextures();
   try {
    glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,3,1,0,GL_RGBA,GL_FLOAT,(float[])null);
    glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
    assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckFramebufferStatus(GL_FRAMEBUFFER));glBindVertexArray(vao);glViewport(0,0,3,1);
    String before=System.getProperty("lumina.nightBeforeShader");
    if(before!=null) evaluate(Files.readString(Path.of(before)),false);
    evaluate(null,true);
    assertEquals(GL_NO_ERROR,glGetError());
   }finally{glDeleteTextures(texture);glDeleteFramebuffers(fbo);glDeleteVertexArrays(vao);}
  }
 }
 private static void evaluate(String before,boolean verify) throws Exception {
  Template t=new Template();
  t.add(key->key.equals("v")?"#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
   :key.equals("f")?"#version 330\n#include \"sky.glsl\"\nuniform float altitude;out vec4 result;void main(){result=gl_FragCoord.x<1.?vec4(moonLightAt(altitude),skyBoostAt(altitude)):gl_FragCoord.x<2.?vec4(skyLightAt(altitude),nightGain(altitude,1.5)):vec4(overcastSkyBoost(altitude,.01,.01),nightGain(altitude,20.0),0,1);}"
   :key.equals("sky.glsl")&&before!=null?before:null);
  t.addInclude(LuminaPlugin.class);
  int program=new Shader().add(GL_VERTEX_SHADER,"v").add(GL_FRAGMENT_SHADER,"f").compile(t);
  try {
   glUseProgram(program);
   for(float alt:new float[]{-53.56f,-6,25}) {
    float clearBlue=0;
    for(float cover:new float[]{0,.54549f,1}) {
     glUniform1f(glGetUniformLocation(program,"altitude"),alt);
     glUniform3f(glGetUniformLocation(program,"weatherOvercast"),cover,0,0);
     glUniform4f(glGetUniformLocation(program,"weatherLight"),0,0,0,0);
     glDrawArrays(GL_TRIANGLES,0,3);float[] p=new float[12];glReadPixels(0,0,3,1,GL_RGBA,GL_FLOAT,p);
     System.out.printf("%s alt=%.2f cover=%.5f moon=(%.6f %.6f %.6f) boost=%.4f sky=(%.6f %.6f %.6f) nightGain=%.4f%n",verify?"AFTER":"BEFORE",alt,cover,p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7]);
     if(!verify)continue;
     if(alt<=-6) {
      assertEquals("night has no weather compensation",1,p[8],.00001f);
      assertEquals("sky boost is only explicit night strength",p[9],p[3],.00001f);
      assertEquals("red/blue ratio survives cover",.12f,p[0]/p[2],.0001f);
      assertEquals("green/blue ratio survives cover",.32f,p[1]/p[2],.0001f);
      if(cover==0)clearBlue=p[2];else assertTrue("cloud reduces direct moon",p[2]<clearBlue);
     }else if(cover>0)assertTrue("day retains sky compensation",p[3]>1);
     if(alt==-53.56f){assertEquals("deep-night gain",1.5f,p[7],.00001f);assertEquals("requested night skylight gain",20.0f,p[3],.00001f);}
     if(alt==25)assertEquals("day gain",1,p[7],.00001f);
    }
   }
   // Captured altitude/cover and the exact weather.light vector in derived.bin.gz.
   glUniform1f(glGetUniformLocation(program,"altitude"),-53.56f);
   glUniform3f(glGetUniformLocation(program,"weatherOvercast"),.54549f,0,0);
   glUniform4f(glGetUniformLocation(program,"weatherLight"),-.030637683f,.068083674f,.030637670f,-.088537186f);
   glDrawArrays(GL_TRIANGLES,0,3);float[] captured=new float[12];glReadPixels(0,0,3,1,GL_RGBA,GL_FLOAT,captured);
   System.out.printf("%s CAPTURE moon=(%.6f %.6f %.6f) skyBoost=%.4f sky=(%.6f %.6f %.6f)%n",verify?"AFTER":"BEFORE",captured[0],captured[1],captured[2],captured[3],captured[4],captured[5],captured[6]);
  } finally{glUseProgram(0);glDeleteProgram(program);}
 }
}
