package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.scene.ModelData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * A dump made in code, for the offline renderer's own test: an empty scene with a ground plate and a box drawn as
 * dynamic models, under the open sky at midday. It needs no client; what a live dump adds (zones, water, lamps) the
 * live dump's tests check.
 */
public final class SyntheticDump
{
	private SyntheticDump()
	{
	}

	public static final int WIDTH = 480, HEIGHT = 320;
	// the scene's middle, local units
	public static final int MID = 52 * 128;
	public static final int GREEN = 21 << 10 | 6 << 7 | 70, DARK_GREEN = 21 << 10 | 6 << 7 | 35, RED = 1 << 10 | 7 << 7 | 60;

	public static SceneDump.Loaded make()
	{
		SceneDump.Loaded l = new SceneDump.Loaded();
		SceneDump.Manifest m = new SceneDump.Manifest();
		m.name = "synthetic";
		m.millis = 1_800_000_000_000L;
		m.nanos = 5_000_000_000_000L;
		m.startMillis = m.millis - 60_000;
		m.cameraYaw = 0;
		m.cameraPitch = 256;
		m.scale = 600;
		m.focalX = MID;
		m.focalY = 0;
		m.focalZ = MID;
		m.canvasWidth = m.viewportWidth = WIDTH;
		m.canvasHeight = m.viewportHeight = HEIGHT;
		m.skyboxColor = 0x87CEEB;
		m.gameState = "LOGGED_IN";
		m.textureBrightness = 0.8;
		m.plane = 0;
		m.baseX = 3200;
		m.baseY = 3200;
		m.playerLocal = new int[]{MID, MID, WorldView.TOPLEVEL};
		m.playerWorld = new int[]{3200 + 52, 3200 + 52, 0};
		m.settings.put("dayCycleMinutes", "0");
		m.settings.put("fixedHour", "12");
		m.settings.put("weatherMode", "OFF");
		m.settings.put("areaEnvironments", "false");
		m.worldViews.add(WorldView.TOPLEVEL);
		l.manifest = m;

		double pitch = m.cameraPitch * Math.PI * 2 / 2048, yaw = 0;
		double[] o = DumpRenderer.orbit(yaw, pitch);
		float dist = 2400;
		float cx = (float) (MID + dist * o[0]), cy = (float) (dist * o[1]), cz = (float) (MID + dist * o[2]);
		m.cameraX = cx;
		m.cameraY = cy;
		m.cameraZ = cz;

		Map<String, Object> d = new LinkedHashMap<>();
		d.put("scene.getBaseX", m.baseX);
		d.put("scene.getBaseY", m.baseY);
		d.put("scene.isInstance", false);
		d.put("scene.getDrawDistance", 25);
		d.put("scene.getTileHeights", new int[4][184][184]);
		d.put("scene.getExtendedTileSettings", new byte[4][184][184]);
		d.put("scene.getTileSettings", new byte[4][104][104]);
		d.put("scene.getInstanceTemplateChunks", new int[4][13][13]);
		d.put("scene.getRoofs", new int[4][184][184]);
		d.put("wv.getTileHeights", new int[4][104][104]);
		d.put("wv.getTileSettings", new byte[4][104][104]);
		d.put("wv.getSizeX", 104);
		d.put("wv.getSizeY", 104);
		d.put("lamps.collected", new ArrayList<>());
		d.put("lamps.steady", new ArrayList<>());
		l.derived = d;

		l.views.add(new int[]{WorldView.TOPLEVEL, 23, 23});
		// ground zones around the middle, a checkerboard of two greens
		List<com.midonarhel.lumina.scene.ZoneSnapshot> zones = new ArrayList<>();
		for (int zx = 9; zx <= 13; ++zx)
		{
			for (int zz = 9; zz <= 13; ++zz)
			{
				zones.add(com.midonarhel.lumina.scene.ZoneFake.ground(zx, zz, 0, GREEN, DARK_GREEN));
			}
		}
		l.zones.add(zones);
		l.textureBrightness = 0.8;
		l.texturePixels = new int[0][];
		l.textureDirection = new int[0];
		l.textureSpeed = new int[0];
		l.uiWidth = WIDTH;
		l.uiHeight = HEIGHT;
		l.uiPixels = new int[WIDTH * HEIGHT];

		FrameTape t = new FrameTape();
		FrameTape.Proj identity = new FrameTape.Proj();
		identity.matrix = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		identity.affine[0] = identity.affine[5] = identity.affine[10] = 1;
		t.projections.add(identity);
		t.models.add(box(192, RED));
		FrameTape.Event pre = event(FrameTape.PRE, new int[]{0, 0, 3, 0, 0, 0, 0, -1});
		pre.floats = new float[]{cx, cy, cz, (float) pitch, (float) yaw};
		pre.projection = 0;
		t.events.add(pre);
		for (com.midonarhel.lumina.scene.ZoneSnapshot z : zones)
		{
			t.events.add(event(FrameTape.ZONE_OPAQUE, new int[]{z.x, z.z}));
		}
		FrameTape.Event box = event(FrameTape.DYNAMIC, new int[]{-1, 0, 0, 0, MID, 0, MID});
		box.model = 0;
		box.projection = 0;
		t.events.add(box);
		t.events.add(event(FrameTape.PASS, new int[]{DrawCallbacks.PASS_OPAQUE}));
		t.events.add(event(FrameTape.PASS, new int[]{DrawCallbacks.PRE_PASS_ALPHA}));
		t.events.add(event(FrameTape.PASS, new int[]{DrawCallbacks.PASS_ALPHA}));
		t.events.add(event(FrameTape.POST, new int[0]));
		FrameTape.Event draw = event(FrameTape.DRAW, new int[]{0});
		t.events.add(draw);
		l.tape = t;
		return l;
	}

	/**
	 * The synthetic scene with a wall and a lamp: a north-south wall between local tiles 54 and 55 from z 46 to 58,
	 * and east of it a warm lamp of six tiles' reach a little above the ground; the box's east face and the ground west
	 * of the wall are behind it.
	 */
	public static SceneDump.Loaded withWallAndLamp()
	{
		SceneDump.Loaded l = make();
		int[][][] flags = new int[4][104][104];
		for (int z = 46; z < 58; ++z)
		{
			flags[0][55][z] |= com.midonarhel.lumina.visibility.WallMap.LOS_WEST;
			flags[0][54][z] |= com.midonarhel.lumina.visibility.WallMap.LOS_EAST;
		}
		l.derived.put("buildings.walls", com.midonarhel.lumina.visibility.WallMap.build(flags, null, 184, 40, 1));
		List<com.midonarhel.lumina.light.PointLight> lamps = new ArrayList<>();
		lamps.add(new com.midonarhel.lumina.light.PointLight().set(LAMP_X, -120, MID, 6 * 128, 1f, 0.75f, 0.45f, 6f).onPlane(0));
		l.derived.put("lamps.collected", lamps);
		l.derived.put("lamps.steady", lamps);
		return l;
	}

	public static final int LAMP_X = (int) (57.5 * 128);

	private static FrameTape.Event event(int type, int[] ints)
	{
		FrameTape.Event e = new FrameTape.Event();
		e.type = type;
		e.worldView = WorldView.TOPLEVEL;
		e.ints = ints;
		return e;
	}

	/** A flat square of half size s at y 0, both sides. */
	static ModelData plate(int s, int hsl)
	{
		List<float[]> v = new ArrayList<>();
		v.add(new float[]{-s, 0, -s});
		v.add(new float[]{s, 0, -s});
		v.add(new float[]{s, 0, s});
		v.add(new float[]{-s, 0, s});
		int[][] faces = {{0, 1, 2}, {0, 2, 3}, {2, 1, 0}, {3, 2, 0}};
		return model(v, faces, hsl);
	}

	/** A box of half size s standing on y 0 (y is down). */
	static ModelData box(int s, int hsl)
	{
		List<float[]> v = new ArrayList<>();
		for (int i = 0; i < 8; ++i)
		{
			v.add(new float[]{(i & 1) != 0 ? s : -s, (i & 2) != 0 ? -2 * s : 0, (i & 4) != 0 ? s : -s});
		}
		int[][] quads = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
		List<int[]> faces = new ArrayList<>();
		for (int[] q : quads)
		{
			// both windings: whichever the cull keeps
			faces.add(new int[]{q[0], q[1], q[2]});
			faces.add(new int[]{q[0], q[2], q[3]});
			faces.add(new int[]{q[2], q[1], q[0]});
			faces.add(new int[]{q[3], q[2], q[0]});
		}
		return model(v, faces.toArray(new int[0][]), hsl);
	}

	private static ModelData model(List<float[]> verts, int[][] faces, int hsl)
	{
		ModelData d = new ModelData();
		d.vertexCount = verts.size();
		d.verticesX = new float[verts.size()];
		d.verticesY = new float[verts.size()];
		d.verticesZ = new float[verts.size()];
		for (int i = 0; i < verts.size(); ++i)
		{
			d.verticesX[i] = verts.get(i)[0];
			d.verticesY[i] = verts.get(i)[1];
			d.verticesZ[i] = verts.get(i)[2];
		}
		int n = faces.length;
		d.faceCount = n;
		d.indices1 = new int[n];
		d.indices2 = new int[n];
		d.indices3 = new int[n];
		d.colors1 = new int[n];
		d.colors2 = new int[n];
		d.colors3 = new int[n];
		d.unlitColors = new short[n];
		for (int f = 0; f < n; ++f)
		{
			d.indices1[f] = faces[f][0];
			d.indices2[f] = faces[f][1];
			d.indices3[f] = faces[f][2];
			d.colors1[f] = hsl;
			d.colors2[f] = hsl;
			d.colors3[f] = hsl;
			d.unlitColors[f] = (short) hsl;
		}
		return d;
	}
}
