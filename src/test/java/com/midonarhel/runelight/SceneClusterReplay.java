package com.midonarhel.runelight;

import com.google.gson.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Offline benchmark of the production culler using an actual scene-perf capture, without GL. */
public final class SceneClusterReplay {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]);
        Files.createDirectories(out);
        JsonObject meta = new JsonParser().parse(Files.readString(source.resolve("scene.json"))).getAsJsonObject();
        float[] matrix = new Gson().fromJson(meta.get("frameViewProj"), float[].class);
        FloatBuffer raw = ByteBuffer.wrap(Files.readAllBytes(source.resolve("lights.f32"))).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        List<PointLight> lights = new ArrayList<>();
        while (raw.remaining() >= 8) {
            float x=raw.get(), y=raw.get(), z=raw.get(), r=raw.get(), red=raw.get(), green=raw.get(), blue=raw.get(); raw.get();
            lights.add(new PointLight().set(x,y,z,r,red,green,blue,1));
        }
        LightClusters c = new LightClusters();
        // Surface lists only: fog membership depends on live roof state, unavailable in this dump.
        for(int i=0;i<300;i++) c.cull(lights,matrix,0,0,0);
        double[] stable = new double[300], moving = new double[100];
        for(int i=0;i<stable.length;i++) { long t=System.nanoTime(); c.cull(lights,matrix,0,0,0); stable[i]=(System.nanoTime()-t)/1e6; }
        float[] changed=matrix.clone();
        for(int i=0;i<moving.length;i++) { changed[0]=matrix[0]*(i%2==0?1.000001f:1f);long t=System.nanoTime();c.cull(lights,changed,0,0,0);moving[i]=(System.nanoTime()-t)/1e6; }
        c.cull(lights,matrix,0,0,0);
        if(c.getLightCount()!=lights.size())throw new IllegalStateException("Replay changed light indices");
        int[] masks=Arrays.copyOf(c.clusterTable(),LightClusters.CLUSTER_ENTRIES*c.clusterTexels()*4);
        ByteBuffer bytes=ByteBuffer.allocate(masks.length*4).order(ByteOrder.LITTLE_ENDIAN);bytes.asIntBuffer().put(masks);Files.write(out.resolve("clusters.u32"),bytes.array());
        for(String f:new String[]{"depth.f32","normal.f32","material.f32","color.f32","lights.f32","light-extra.f32","timings.csv"})
            Files.copy(source.resolve(f),out.resolve(f),StandardCopyOption.REPLACE_EXISTING);
        meta.addProperty("CLUSTER_TEXELS",c.clusterTexels());meta.addProperty("CLUSTERS_X",LightClusters.CLUSTERS_X);meta.addProperty("CLUSTERS_Y",LightClusters.CLUSTERS_Y);
        meta.addProperty("replay",true);
        Files.writeString(out.resolve("scene.json"),new Gson().toJson(meta));
        Arrays.sort(stable);Arrays.sort(moving);
        String result=String.format(Locale.ROOT,"Grid %dx%dx%d; stable cull median %.3f ms p95 %.3f ms; camera rebuild median %.3f ms p95 %.3f ms; overflow %d%n",LightClusters.CLUSTERS_X,LightClusters.CLUSTERS_Y,LightClusters.CLUSTERS_Z,stable[150],stable[285],moving[50],moving[95],c.getOverflows());
        Files.writeString(out.resolve("cull-benchmark.txt"),result);System.out.print(result);
    }
}
