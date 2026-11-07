package com.midonarhel.lumina.light;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.harness.HeadlessGl;
import com.midonarhel.lumina.template.Template;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.Assume;
import static org.lwjgl.opengl.GL33C.*;

/** Opt-in GPU microbenchmark, not a whole-game FPS benchmark. */
public class LampBlockersPerfTest {
 @Test public void measure() throws Exception {
  Assume.assumeTrue(Boolean.getBoolean("lumina.blockerPerf"));
  try (HeadlessGl context = new HeadlessGl()) {
   StringBuilder report = new StringBuilder("GPU: "+glGetString(GL_RENDERER)+"; driver: "+glGetString(GL_VERSION)+"\n");
   report.append("Synthetic 2048-unit interior; actual lamp.glsl; RGBA16F target; GPU elapsed queries; 60 FPS submission cap; GPU timers exclude pacing; 12 warmups, 40 interleaved samples per mode. External GPU activity is not controlled by this harness; record whether the client was stopped.\n");
   Template template = new Template();
   template.add(k -> k.equals("bench.vert") ? "#version 330\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.-1.,0,1);}"
    : k.equals("bench.frag") ? "#version 330\nuniform vec3 cameraPos;\n#include cluster_config\n#include \"light/lamp.glsl\"\nuniform vec2 resolution;uniform int count,occlude;out vec4 color;void main(){vec3 p=vec3(gl_FragCoord.x/resolution.x*2048.,-2.,gl_FragCoord.y/resolution.y*2048.);vec3 sum=vec3(0);for(int i=0;i<count;++i){vec3 pos=vec3(128.+float(i%8)*256.,-160.,256.+float(i/8)*512.);Lamp l=Lamp(pos,2200.,vec3(1.,0.6,0.2),pos.xz);vec3 ray=pos-p;float d2=dot(ray,ray);if(d2<l.radius*l.radius){float vis=occlude==1?lampOpen(l,p,p,false):1.;sum+=l.color*lampFalloff(d2,l.radius*l.radius)*max(-normalize(ray).y,0.)*vis;}}color=vec4(sum,1);}"
    : k.equals("cluster_config") ? "#define CLUSTERS_X 32\n#define CLUSTERS_Y 18\n#define CLUSTERS_Z 24\n#define CLUSTER_NEAR 50.0\n#define CLUSTER_FAR 25600.0\n#define CLUSTER_BASE 0\n" : null);
   template.addInclude(LuminaPlugin.class);
   int prog=new Shader().add(GL_VERTEX_SHADER,"bench.vert").add(GL_FRAGMENT_SHADER,"bench.frag").compile(template);
   int vao=glGenVertexArrays(),buf=glGenBuffers(),tex=glGenTextures(),fbo=glGenFramebuffers(),rb=glGenRenderbuffers(),query=glGenQueries();
   glBindVertexArray(vao);glUseProgram(prog);glUniform1i(glGetUniformLocation(prog,"lampBlockers"),0);
   glBindFramebuffer(GL_FRAMEBUFFER,fbo);glBindRenderbuffer(GL_RENDERBUFFER,rb);
   for(int fixture = 0; fixture < 3; ++fixture) {
    boolean detailed = fixture > 0;
    List<float[]> geometry=geometry(detailed);
    if(fixture==2) for(int i=0;i<(detailed?8192:128);++i) for(int k=0;k<9;k+=3) geometry.get(i)[k+1] -= geometry.get(i)[k]*0.125f;
    long start=System.nanoTime();float[] packed=null;
    for(int i=0;i<20;++i) packed=LampBlockers.pack(geometry);
    double packMs=(System.nanoTime()-start)/20e6;
    report.append(String.format(java.util.Locale.ROOT,"geometry=%s triangles=%d bufferKiB=%.1f packMeanMs=%.3f\n",fixture==2?"detailed-sloped":detailed?"detailed":"simple",geometry.size(),packed.length/256.,packMs));
    glBindBuffer(GL_TEXTURE_BUFFER,buf);glBufferData(GL_TEXTURE_BUFFER,packed,GL_STATIC_DRAW);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_BUFFER,tex);glTexBuffer(GL_TEXTURE_BUFFER,GL_RGBA32F,buf);
    for(int height : new int[]{1080,1440}) {
     int width=height*16/9;glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA16F,width,height);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,rb);
     if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("FBO");
     glViewport(0,0,width,height);glUniform2f(glGetUniformLocation(prog,"resolution"),width,height);
     for(int count : new int[]{1,4,16,32}) {
      glUniform1i(glGetUniformLocation(prog,"count"),count);
      double[][] times=new double[2][40];
      for(int frame=-12;frame<40;++frame) for(int mode=0;mode<2;++mode) {
       long frameStart=System.nanoTime();
       int occlude=(frame&1)==0?mode:1-mode;
       glUniform1i(glGetUniformLocation(prog,"occlude"),occlude);
       glBeginQuery(GL_TIME_ELAPSED,query);glDrawArrays(GL_TRIANGLES,0,3);glEndQuery(GL_TIME_ELAPSED);
       double ms=glGetQueryObjectui64(query,GL_QUERY_RESULT)/1e6;
       if(frame>=0)times[occlude][frame]=ms;
       long wait=16_666_667L-(System.nanoTime()-frameStart);
       if(wait>0)java.util.concurrent.locks.LockSupport.parkNanos(wait);
      }
      Arrays.sort(times[0]);Arrays.sort(times[1]);
      String line=String.format(java.util.Locale.ROOT,"%dx%d lights=%d baselineMedian=%.3f blockedMedian=%.3f added=%.3f blockedP95=%.3f ms\n",width,height,count,times[0][20],times[1][20],times[1][20]-times[0][20],times[1][37]);
      report.append(line);System.out.print(line);
      Files.createDirectories(Path.of("build/benchmarks"));Files.writeString(Path.of("build/benchmarks/point-light-occlusion.txt"),report);
      if(glGetError()!=GL_NO_ERROR)throw new AssertionError("GL error");
     }
    }
   }
   Files.createDirectories(Path.of("build/benchmarks"));Files.writeString(Path.of("build/benchmarks/point-light-occlusion.txt"),report);
   glUseProgram(0);glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteQueries(query);glDeleteProgram(prog);glDeleteVertexArrays(vao);glDeleteTextures(tex);glDeleteBuffers(buf);glDeleteFramebuffers(fbo);glDeleteRenderbuffers(rb);
  }
 }
 private static List<float[]> geometry(boolean detailed) {
  List<float[]> out=new ArrayList<>();
  int step=detailed?32:256;
  // Roof tessellation adds triangles but does not block horizontal rays: exercises unsuccessful tests.
  for(int x=0;x<2048;x+=step)for(int z=0;z<2048;z+=step){
   out.add(new float[]{x,-300,z,x+step,-300,z,x+step,-300,z+step});
   out.add(new float[]{x,-300,z,x+step,-300,z+step,x,-300,z+step});
  }
  // Three finite partition walls with door openings; mixed blocked and unblocked rays.
  for(int x=512;x<2048;x+=512)for(int z=0;z<2048;z+=128){
   if(z%512==128)continue;
   out.add(new float[]{x,0,z,x,-256,z,x,-256,z+128});
   out.add(new float[]{x,0,z,x,-256,z+128,x,0,z+128});
  }
  return out;
 }
}
