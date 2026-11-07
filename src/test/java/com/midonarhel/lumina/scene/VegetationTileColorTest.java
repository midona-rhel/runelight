package com.midonarhel.lumina.scene;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.junit.Test;
import static org.junit.Assert.*;

/** Compare inherited vegetation colors to the actual terrain vertices sent to the renderer. */
public class VegetationTileColorTest
{
    private static <T> T fake(Class<T> type, Map<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (values.containsKey(m.getName())) return values.get(m.getName());
            if (m.getReturnType() == boolean.class) return false;
            if (m.getReturnType() == int.class) return 0;
            return null;
        }));
    }

    private static final class Fixture
    {
        final SceneUploader uploader = new SceneUploader(null);
        final int[][][] heights = new int[1][4][4];
        final short[][][] overlays = new short[1][4][4];
        final Map<String, Object> tileValues = new HashMap<>();
        final Scene scene = fake(Scene.class, Map.of("getWorldViewId", 1, "getTileHeights", heights,
            "getOverlayIds", overlays, "isInstance", true));
        final Tile tile = fake(Tile.class, tileValues);

        Fixture(boolean slope, int overlay) throws Exception
        {
            tileValues.put("getSceneLocation", new Point(1, 1));
            tileValues.put("getRenderLevel", 0);
            overlays[0][1][1] = (short) overlay;
            if (slope) for (int x=0;x<4;x++) for (int y=0;y<4;y++) heights[0][x][y]=x*x*32-y*24;
            Field f=SceneUploader.class.getDeclaredField("groundBlender"); f.setAccessible(true);
            Field ids=GroundBlender.class.getDeclaredField("overlayIds"); ids.setAccessible(true);
            ids.set(f.get(uploader), overlays);
        }

        IntBuffer upload(Object surface, Class<?> type) throws Exception
        {
            IntBuffer vertices=IntBuffer.allocate(256);
            Method m;
            if (type==SceneTilePaint.class)
            {
                m=SceneUploader.class.getDeclaredMethod("upload",Scene.class,type,int.class,int.class,int.class,
                    GpuIntBuffer.class,int.class,int.class);
                m.setAccessible(true);
                m.invoke(uploader,scene,surface,0,1,1,new GpuIntBuffer(vertices),128,128);
            }
            else
            {
                m=SceneUploader.class.getDeclaredMethod("upload",Scene.class,type,int.class,int.class,int.class,GpuIntBuffer.class);
                m.setAccessible(true);
                m.invoke(uploader,scene,surface,0,1,1,new GpuIntBuffer(vertices));
            }
            return vertices;
        }
    }

    @Test public void paintInheritanceMatchesUploadedFlatSlopedAndRawGround() throws Exception
    {
        for(boolean slope:new boolean[]{false,true}) for(int overlay:new int[]{0,13})
        {
            Fixture f=new Fixture(slope,overlay);
            SceneTilePaint paint=fake(SceneTilePaint.class,Map.of("getTexture",-1,"getRBG",1,
                "getSwColor",40,"getSeColor",50,"getNwColor",30,"getNeColor",45));
            f.tileValues.put("getSceneTilePaint",paint);
            IntBuffer vertices=f.upload(paint,SceneTilePaint.class);
            int expected=(vertices.get(2)+vertices.get(8)+vertices.get(14)+vertices.get(20))/4;
            assertEquals(expected,f.uploader.tileColor(f.scene,f.tile,false));
            if(!slope && overlay==0) assertEquals(49,expected);
            if(overlay==13) assertEquals((40+50+30+45)/4,expected);
        }
    }

    @Test public void modelInheritanceMatchesSelectedUploadedUnderlayAndOverlay() throws Exception
    {
        for(boolean slope:new boolean[]{false,true}) for(int overlay:new int[]{0,13})
        {
            Fixture f=new Fixture(slope,overlay);
            SceneTileModel model=fake(SceneTileModel.class,Map.ofEntries(
                Map.entry("getShape",2),Map.entry("getModelOverlay",1),
                Map.entry("getFaceX",new int[]{0,1}),Map.entry("getFaceY",new int[]{1,2}),Map.entry("getFaceZ",new int[]{2,3}),
                Map.entry("getVertexX",new int[]{128,256,128,256}),Map.entry("getVertexY",new int[]{0,0,0,0}),
                Map.entry("getVertexZ",new int[]{128,128,256,256}),
                Map.entry("getTriangleColorA",new int[]{40,30}),Map.entry("getTriangleColorB",new int[]{40,30}),
                Map.entry("getTriangleColorC",new int[]{40,30})));
            f.tileValues.put("getSceneTileModel",model);
            IntBuffer vertices=f.upload(model,SceneTileModel.class);
            assertEquals(vertices.get(2),f.uploader.tileColor(f.scene,f.tile,false));
            assertEquals(vertices.get(20),f.uploader.tileColor(f.scene,f.tile,true));
            if(overlay==13) assertEquals(30,vertices.get(20));
        }
    }

    @Test public void texturedOrHiddenGroundDoesNotSupplyAnInheritedColor() throws Exception
    {
        Fixture f=new Fixture(false,0);
        for(int texture:new int[]{3,-1})
        {
            SceneTilePaint paint=fake(SceneTilePaint.class,Map.of("getTexture",texture,"getRBG",1,
                "getSwColor",40,"getSeColor",40,"getNwColor",40,"getNeColor",12345678));
            f.tileValues.put("getSceneTilePaint",paint);
            assertEquals(-1,f.uploader.tileColor(f.scene,f.tile,false));
        }
        f.tileValues.clear(); f.tileValues.put("getSceneLocation",new Point(1,1));
        f.tileValues.put("getSceneTileModel",fake(SceneTileModel.class,Map.of("getTriangleTextureId",new int[]{3})));
        assertEquals(-1,f.uploader.tileColor(f.scene,f.tile,false));
    }
}
