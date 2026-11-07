package com.midonarhel.lumina.lava;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.harness.DumpRenderer;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Opt-in integration preview on an actual captured scene; never changes the running client or saved dump. */
public class LavaDumpPreviewTest
{
    @Test public void renderCapturedLavaWithExplicitTags() throws Exception
    {
        String input=System.getProperty("lumina.lavaDump", ""), shaders=System.getProperty("lumina.lavaShaders", "");
        Assume.assumeTrue("Supply a volcanic dump and staged shader directory", !input.isEmpty() && !shaders.isEmpty());
        SceneDump.Loaded dump=SceneDump.load(Path.of(input));
        int count=tagOldTerrain(dump)+tagCapturedLavaSheets(dump);
        assertTrue("No explicitly identified lava triangles in the captured scene",count>0);
        System.out.println("Lava preview: "+count+" exact material/mesh triangles tagged; rock faces retain their original material.");
        if(Boolean.getBoolean("lumina.lavaPalette")) recolorNamedFossilLava(dump);
        DumpRenderer.Options options=new DumpRenderer.Options();
        options.shaderDirectory=Path.of(shaders);
        options.warmupSeconds=.15;
        Path out=Path.of(System.getProperty("lumina.lavaOut", "build/lava-preview"));
        Files.createDirectories(out);
        try(DumpRenderer renderer=new DumpRenderer(dump,options))
        {
            ImageIO.write(renderer.render(),"png",out.resolve("captured-lava.png").toFile());
            if(Boolean.getBoolean("lumina.lavaAnimation"))
                for(int seconds:new int[]{20,40,60})
                    ImageIO.write(renderer.renderLater(seconds),"png",out.resolve("captured-lava-"+seconds+"s.png").toFile());
        }
    }

    // Identify every triangle by all three captured vertices belonging to the same explicitly known lava object.
    // Known lava object IDs plus their exact molten material family; rock faces in the same objects are excluded.
    static int tagCapturedLavaSheets(SceneDump.Loaded dump)
    {
        com.midonarhel.lumina.dev.SceneObjects objects=(com.midonarhel.lumina.dev.SceneObjects)dump.derived.get("sceneObjects");
        java.util.Map<String,java.util.Set<Integer>> owners=new java.util.HashMap<>();
        java.util.List<Integer> objectIds=new java.util.ArrayList<>();
        int owner=0;
        for(com.midonarhel.lumina.dev.SceneObjects.Obj object:objects.objects)
        {
            if(!LavaMaterials.fossilLavaObject(object.id) || object.a==null || object.a.none || object.orientation!=0)continue;
            int index=owner++;
            objectIds.add(object.id);
            for(int v=0;v<object.a.count;v++)
            {
                String key=vertexKey((int)object.a.x[v]+object.minX*128+64,
                    (int)object.a.y[v]+object.z,(int)object.a.z[v]+object.minY*128+64);
                owners.computeIfAbsent(key,k->new java.util.HashSet<>()).add(index);
            }
        }
        int offset=(((short[][][])dump.derived.get("scene.getOverlayIds"))[0].length-104)/2*128;
        int count=0;
        for(ZoneSnapshot zone:dump.zones.get(0))
        {
            int[] vertices=zone.vertsO;if(vertices==null)continue;
            for(int at=0;at+17<vertices.length;at+=18)
            {
                java.util.Set<Integer> common=null;
                for(int v=0;v<3;v++)
                {
                    int p=at+v*6;
                    java.util.Set<Integer> candidates=owners.get(vertexKey((short)vertices[p]+zone.x*1024-offset,
                        (short)(vertices[p]>>>16),(short)vertices[p+1]+zone.z*1024-offset));
                    if(candidates==null){common=null;break;}
                    if(common==null)common=new java.util.HashSet<>(candidates);else common.retainAll(candidates);
                    if(common.isEmpty())break;
                }
                if(common==null || common.isEmpty())continue;
                int variant=0;
                for(int candidate:common)
                    variant=Math.max(variant,LavaMaterials.objectFaceVariant(objectIds.get(candidate),(vertices[at+3]&511)-1,
                        vertices[at+2],vertices[at+8],vertices[at+14]));
                if(variant==0)continue;
                for(int v=0;v<3;v++){int p=at+v*6+3;vertices[p]=(vertices[p]&0xffff0000)|LavaMaterials.tag(vertices[p]&65535,1);}
                count++;
            }
        }
        System.out.println("Lava materials: "+owner+" captured volcanic object instances, "+count+" molten triangles exactly matched.");
        return count;
    }

    private static String vertexKey(int x,int y,int z){return x+","+y+","+z;}

    @SuppressWarnings("unchecked")
    private static void recolorNamedFossilLava(SceneDump.Loaded dump) throws Exception
    {
        com.midonarhel.lumina.light.LightDefinition[] definitions;
        try(java.io.Reader reader=new java.io.InputStreamReader(com.midonarhel.lumina.light.SceneLights.class.getResourceAsStream("lights.json"),java.nio.charset.StandardCharsets.UTF_8))
        {definitions=new com.google.gson.Gson().fromJson(reader,com.midonarhel.lumina.light.LightDefinition[].class);}
        int changed=0;
        for(Object item:(java.util.List<?>)dump.derived.get("lamps.collected"))
        {
            com.midonarhel.lumina.light.PointLight lamp=(com.midonarhel.lumina.light.PointLight)item;
            float[] position=com.midonarhel.lumina.light.LampsAccess.of(lamp);
            for(com.midonarhel.lumina.light.LightDefinition definition:definitions)
            {
                if(definition.description==null || !definition.description.matches("FOSSIL_ISLAND_LAVA_[0-9]+"))continue;
                float x=(definition.worldX-dump.manifest.baseX)*128+64,z=(definition.worldY-dump.manifest.baseY)*128+64;
                if(Math.abs(position[0]-x)>1 || Math.abs(position[2]-z)>1)continue;
                String[] names={"r","g","b"};
                float[] color={LavaMaterials.EMISSION_RED,LavaMaterials.EMISSION_GREEN,LavaMaterials.EMISSION_BLUE};
                for(int channel=0;channel<3;channel++)
                {
                    java.lang.reflect.Field field=lamp.getClass().getDeclaredField(names[channel]);
                    field.setAccessible(true);field.setFloat(lamp,color[channel]);
                }
                changed++;break;
            }
        }
        System.out.println("Lava preview: "+changed+" exact-position named Fossil Island lava lamps use the staged emission palette.");
    }

    // Old captures predate the new upload tag. Retag only explicit lava textures or complete lava overlays.
    // Scene tileShapes uses the cache shape: 0 is full overlay (SceneTileModel shape 1).
    // Mixed shaped overlays are deliberately left alone: old vertex streams did not preserve face provenance.
    static int tagOldTerrain(SceneDump.Loaded dump)
    {
        short[][][] overlays=(short[][][])dump.derived.get("scene.getOverlayIds");
        byte[][][] shapes=(byte[][][])dump.derived.get("scene.getTileShapes");
        int tagged=0;
        java.util.Map<Integer,Integer> materials=new java.util.TreeMap<>(), overlayCounts=new java.util.TreeMap<>();
        if(overlays!=null) for(short[][] plane:overlays) for(short[] row:plane) for(short value:row) overlayCounts.merge(value&0xffff,1,Integer::sum);
        System.out.println("Lava diagnostic overlay dimensions="+(overlays==null?"missing":overlays.length+"x"+overlays[0].length)+" shapes="+(shapes==null?"missing":shapes.length)+" overlays="+overlayCounts);
        for(ZoneSnapshot zone:dump.zones.get(0))
        {
            int[] vertices=zone.vertsO;
            if(vertices==null)continue;
            for(int at=0;at+17<vertices.length;at+=18)
            {
                int original=vertices[at+3]&0xffff;
                materials.merge(original,1,Integer::sum);
                if((original & (24<<9)) != (16<<9))continue; // strictly land terrain, never objects or water
                int plane=0;
                while(plane<3 && at>=zone.levelOffsets[plane])plane++;
                double x=0,z=0;
                for(int v=0;v<3;v++){x+=(short)vertices[at+v*6];z+=(short)vertices[at+v*6+1];}
                int tx=(int)Math.floor((x/3+zone.x*1024)/128),tz=(int)Math.floor((z/3+zone.z*1024)/128);
                int overlay=0;boolean full=false;
                if(overlays!=null && shapes!=null && plane<overlays.length && plane<shapes.length
                    && overlays[plane]!=null && shapes[plane]!=null && tx>=0 && tx<overlays[plane].length && tx<shapes[plane].length
                    && tz>=0 && tz<overlays[plane][tx].length && tz<shapes[plane][tx].length)
                {overlay=overlays[plane][tx][tz]&0xffff;full=shapes[plane][tx][tz]==0;}
                int offset=(overlays[0].length-104)/2;
                int variant=LavaMaterials.variant(overlay,(original&511)-1,full,
                    dump.manifest.baseX+tx-offset,dump.manifest.baseY+tz-offset);
                if(variant==0)continue;
                for(int v=0;v<3;v++)
                {
                    int index=at+v*6+3;
                    vertices[index]=(vertices[index]&0xffff0000)|LavaMaterials.tag(vertices[index]&0xffff,variant);
                }
                tagged++;
            }
        }
        System.out.println("Lava diagnostic material words="+materials);
        return tagged;
    }
}
