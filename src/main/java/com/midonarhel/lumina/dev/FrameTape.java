package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.scene.ModelData;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.FloatProjection;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Scene;

/**
 * One frame's draw callbacks as the client made them (the scene dump, SceneDump): the scene draws' arguments, the zones
 * drawn in order, the passes, and every dynamic and temporary model with its placement, its material and a copy of its
 * data. The offline renderer plays it back through the same callbacks, so the frame is drawn by the real upload and
 * shader code. Dynamic models come from the client's render threads: the tape is synchronized.
 */
public final class FrameTape
{
	public static final int PRE = 1, ZONE_OPAQUE = 2, ZONE_ALPHA = 3, PASS = 4, DYNAMIC = 5, TEMP = 6, POST = 7, DRAW = 8;

	/** A callback: its kind, its scene's world view, its integer and float arguments, its projection and model. */
	public static final class Event
	{
		public int type;
		public int worldView;
		public int[] ints = new int[0];
		public float[] floats = new float[0];
		// indices into projections and models, -1 for none
		public int projection = -1;
		public int model = -1;
	}

	/**
	 * A projection the client handed a callback. The client's FloatProjection gives its matrix; project() is what the
	 * model uploader calls (for the alpha faces' order), read back as an affine map of the point: rows x', y', z' of
	 * (x, y, z, 1). error is the largest difference between the map and project() on test points, in its units.
	 */
	public static final class Proj
	{
		public float[] matrix;
		public final float[] affine = new float[12];
		public float error;
	}

	public final List<Event> events = new ArrayList<>();
	public final List<Proj> projections = new ArrayList<>();
	public final List<ModelData> models = new ArrayList<>();
	private final Map<Projection, Integer> projectionIds = new IdentityHashMap<>();

	public synchronized void pre(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ,
		float cameraPitch, float cameraYaw, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		Event e = add(PRE, scene);
		int[] roofs = hideRoofIds == null ? new int[0] : hideRoofIds.stream().mapToInt(Integer::intValue).toArray();
		e.ints = new int[7 + 1 + roofs.length];
		e.ints[0] = minLevel;
		e.ints[1] = level;
		e.ints[2] = maxLevel;
		e.ints[3] = scene.getOverrideHue();
		e.ints[4] = scene.getOverrideSaturation();
		e.ints[5] = scene.getOverrideLuminance();
		e.ints[6] = scene.getOverrideAmount();
		// -1: no set of hidden roofs at all
		e.ints[7] = hideRoofIds == null ? -1 : roofs.length;
		System.arraycopy(roofs, 0, e.ints, 8, roofs.length);
		e.floats = new float[]{cameraX, cameraY, cameraZ, cameraPitch, cameraYaw};
		e.projection = projection(entityProjection);
	}

	public synchronized void zoneOpaque(Scene scene, int zx, int zz)
	{
		add(ZONE_OPAQUE, scene).ints = new int[]{zx, zz};
	}

	public synchronized void zoneAlpha(Scene scene, int level, int zx, int zz)
	{
		add(ZONE_ALPHA, scene).ints = new int[]{level, zx, zz};
	}

	public synchronized void pass(Scene scene, Projection projection, int pass)
	{
		Event e = add(PASS, scene);
		e.ints = new int[]{pass};
		e.projection = projection(projection);
	}

	public synchronized void dynamic(int renderThreadId, Projection worldProjection, Scene scene, Model m, int material,
		int objectPlane, int orient, int x, int y, int z)
	{
		Event e = add(DYNAMIC, scene);
		e.ints = new int[]{renderThreadId, material, objectPlane, orient, x, y, z};
		e.projection = projection(worldProjection);
		e.model = model(m);
	}

	public synchronized void temp(Projection worldProjection, Scene scene, Model m, int orient, int x, int y, int z,
		int material, boolean sorted, boolean noDepth, int zx, int zz, int plane, int alphaY)
	{
		Event e = add(TEMP, scene);
		e.ints = new int[]{orient, x, y, z, material, sorted ? 1 : 0, noDepth ? 1 : 0, zx, zz, plane, alphaY};
		e.projection = projection(worldProjection);
		e.model = model(m);
	}

	public synchronized void post(Scene scene)
	{
		add(POST, scene);
	}

	public synchronized void draw(int overlayColor)
	{
		Event e = new Event();
		e.type = DRAW;
		e.worldView = -1;
		e.ints = new int[]{overlayColor};
		events.add(e);
	}

	/** A model kept for the tape (the skybox): its index among the models. */
	public synchronized int model(Model m)
	{
		models.add(ModelData.copyOf(m));
		return models.size() - 1;
	}

	private Event add(int type, Scene scene)
	{
		Event e = new Event();
		e.type = type;
		e.worldView = scene.getWorldViewId();
		events.add(e);
		return e;
	}

	private int projection(Projection p)
	{
		if (p == null)
		{
			return -1;
		}
		Integer id = projectionIds.get(p);
		if (id != null)
		{
			return id;
		}
		Proj r = new Proj();
		if (p instanceof FloatProjection)
		{
			r.matrix = ((FloatProjection) p).getProjection().clone();
		}
		// the map's columns from the origin and three points along the axes, far enough to keep their digits
		final float s = 1024;
		float[] o = p.project(0, 0, 0, new float[3]).clone();
		float[] ax = p.project(s, 0, 0, new float[3]).clone();
		float[] ay = p.project(0, s, 0, new float[3]).clone();
		float[] az = p.project(0, 0, s, new float[3]).clone();
		for (int row = 0; row < 3; ++row)
		{
			r.affine[row * 4] = (ax[row] - o[row]) / s;
			r.affine[row * 4 + 1] = (ay[row] - o[row]) / s;
			r.affine[row * 4 + 2] = (az[row] - o[row]) / s;
			r.affine[row * 4 + 3] = o[row];
		}
		float[][] tests = {{3000, -900, 5000}, {-2500, 400, 12000}, {7000, -2000, -3000}};
		for (float[] t : tests)
		{
			float[] want = p.project(t[0], t[1], t[2], new float[3]);
			float[] got = project(r.affine, t[0], t[1], t[2], new float[3]);
			for (int i = 0; i < 3; ++i)
			{
				r.error = Math.max(r.error, Math.abs(want[i] - got[i]));
			}
		}
		projections.add(r);
		projectionIds.put(p, projections.size() - 1);
		return projections.size() - 1;
	}

	/** The affine map of a recorded projection applied to a point, into out. */
	public static float[] project(float[] affine, float x, float y, float z, float[] out)
	{
		for (int row = 0; row < 3; ++row)
		{
			out[row] = affine[row * 4] * x + affine[row * 4 + 1] * y + affine[row * 4 + 2] * z + affine[row * 4 + 3];
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// file

	public void write(DataOutputStream out) throws IOException
	{
		out.writeInt(projections.size());
		for (Proj p : projections)
		{
			out.writeBoolean(p.matrix != null);
			if (p.matrix != null)
			{
				writeFloats(out, p.matrix);
			}
			writeFloats(out, p.affine);
			out.writeFloat(p.error);
		}
		out.writeInt(models.size());
		for (ModelData m : models)
		{
			m.write(out);
		}
		out.writeInt(events.size());
		for (Event e : events)
		{
			out.writeByte(e.type);
			out.writeInt(e.worldView);
			out.writeInt(e.ints.length);
			for (int v : e.ints)
			{
				out.writeInt(v);
			}
			writeFloats(out, e.floats);
			out.writeInt(e.projection);
			out.writeInt(e.model);
		}
	}

	public static FrameTape read(DataInputStream in) throws IOException
	{
		FrameTape t = new FrameTape();
		int n = in.readInt();
		for (int i = 0; i < n; ++i)
		{
			Proj p = new Proj();
			if (in.readBoolean())
			{
				p.matrix = readFloats(in);
			}
			float[] affine = readFloats(in);
			System.arraycopy(affine, 0, p.affine, 0, 12);
			p.error = in.readFloat();
			t.projections.add(p);
		}
		n = in.readInt();
		for (int i = 0; i < n; ++i)
		{
			t.models.add(ModelData.read(in));
		}
		n = in.readInt();
		for (int i = 0; i < n; ++i)
		{
			Event e = new Event();
			e.type = in.readByte();
			e.worldView = in.readInt();
			e.ints = new int[in.readInt()];
			for (int k = 0; k < e.ints.length; ++k)
			{
				e.ints[k] = in.readInt();
			}
			e.floats = readFloats(in);
			e.projection = in.readInt();
			e.model = in.readInt();
			t.events.add(e);
		}
		return t;
	}

	static void writeFloats(DataOutputStream out, float[] a) throws IOException
	{
		out.writeInt(a.length);
		for (float v : a)
		{
			out.writeFloat(v);
		}
	}

	static float[] readFloats(DataInputStream in) throws IOException
	{
		float[] a = new float[in.readInt()];
		for (int i = 0; i < a.length; ++i)
		{
			a[i] = in.readFloat();
		}
		return a;
	}
}
