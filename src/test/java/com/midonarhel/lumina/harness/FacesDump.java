package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.scene.ModelData;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import net.runelite.api.WorldView;

/**
 * The synthetic scene with real objects in it: the models of a `::runelight faces [radius] detail` file (each face's
 * colours, texture, and per corner its vertex, position and the game's normal) drawn as dynamic models through the
 * plugin's upload code, around the synthetic dump's middle, with the game's textures from a live dump. For looking at
 * how the uploader and the shaders shade those exact models, on any branch.
 * <pre>
 * gradle renderDump -Pdump=faces:FILE:CENTRE_ID[:TEXTURES_DUMP] -Pargs="yaw=.. pitch=.. scale=.."
 * </pre>
 * The object CENTRE_ID nearest the file's player tile is put at the middle; every model of the file within 4 tiles of
 * it is drawn, planes above the ground a storey (240) apart.
 */
public final class FacesDump
{
	private FacesDump()
	{
	}

	private static final Pattern TILE = Pattern.compile("tile WorldPoint\\(x=(\\d+), y=(\\d+), plane=(\\d+)\\)");
	private static final Pattern OBJECT = Pattern.compile("(?:orientation (\\d+) )?(\\w+) (\\d+) (.+?): faces .*");
	private static final Pattern FACE = Pattern.compile("  face (\\d+) c (-?\\d+) (-?\\d+) (-?\\d+) tex (-?\\d+)(.*)");
	private static final Pattern CORNER = Pattern.compile(
		"v(\\d+) \\((-?\\d+),(-?\\d+),(-?\\d+)\\)(?: n (-?\\d+),(-?\\d+),(-?\\d+))?");

	private static final class Obj
	{
		int tx, ty, plane, orient, id;
		final List<int[]> faces = new ArrayList<>(); // c1 c2 c3 tex, then per corner v x y z nx ny nz
	}

	public static SceneDump.Loaded make(Path faces, int centreId, Path texturesDump) throws IOException
	{
		List<Obj> objs = new ArrayList<>();
		Obj cur = null;
		int tx = 0, ty = 0, plane = 0, px = 0, py = 0;
		for (String line : Files.readAllLines(faces))
		{
			Matcher m;
			if (line.startsWith("player "))
			{
				Matcher p = Pattern.compile("x=(\\d+), y=(\\d+)").matcher(line);
				if (p.find())
				{
					px = Integer.parseInt(p.group(1));
					py = Integer.parseInt(p.group(2));
				}
			}
			else if ((m = TILE.matcher(line)).matches())
			{
				tx = Integer.parseInt(m.group(1));
				ty = Integer.parseInt(m.group(2));
				plane = Integer.parseInt(m.group(3));
			}
			else if ((m = OBJECT.matcher(line)).matches())
			{
				cur = new Obj();
				cur.tx = tx;
				cur.ty = ty;
				cur.plane = plane;
				cur.orient = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
				cur.id = Integer.parseInt(m.group(3));
				objs.add(cur);
			}
			else if (cur != null && (m = FACE.matcher(line)).matches())
			{
				List<Integer> f = new ArrayList<>(List.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
					Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
				Matcher c = CORNER.matcher(m.group(6));
				while (c.find())
				{
					for (int g = 1; g <= 7; ++g)
					{
						f.add(c.group(g) != null ? Integer.parseInt(c.group(g)) : 0);
					}
				}
				if (f.size() == 4 + 3 * 7)
				{
					cur.faces.add(f.stream().mapToInt(Integer::intValue).toArray());
				}
			}
		}
		Obj centre = null;
		for (Obj o : objs)
		{
			if (o.id == centreId && (centre == null
				|| Math.abs(o.tx - px) + Math.abs(o.ty - py) < Math.abs(centre.tx - px) + Math.abs(centre.ty - py)))
			{
				centre = o;
			}
		}
		if (centre == null)
		{
			throw new IOException("no object " + centreId + " in " + faces);
		}

		SceneDump.Loaded l = SyntheticDump.make();
		l.manifest.name = "faces-" + centreId;
		l.manifest.focalY = -240 * centre.plane - 200;
		// larger than the synthetic scene's, to see a model's shading
		l.manifest.canvasWidth = l.manifest.viewportWidth = l.uiWidth = 1200;
		l.manifest.canvasHeight = l.manifest.viewportHeight = l.uiHeight = 800;
		l.uiPixels = new int[1200 * 800];
		if (texturesDump != null)
		{
			textures(texturesDump, l);
		}
		FrameTape t = l.tape;
		List<FrameTape.Event> tail = new ArrayList<>();
		// the synthetic box goes; the passes and the draw stay
		while (t.events.get(t.events.size() - 1).type != FrameTape.DYNAMIC)
		{
			tail.add(0, t.events.remove(t.events.size() - 1));
		}
		t.events.remove(t.events.size() - 1);
		t.models.clear();
		for (Obj o : objs)
		{
			if (Math.abs(o.tx - centre.tx) > 4 || Math.abs(o.ty - centre.ty) > 4 || o.faces.isEmpty())
			{
				continue;
			}
			t.models.add(model(o));
			int x = SyntheticDump.MID + (o.tx - centre.tx) * 128, z = SyntheticDump.MID + (o.ty - centre.ty) * 128;
			int y = -240 * o.plane;
			FrameTape.Event e = new FrameTape.Event();
			e.type = FrameTape.DYNAMIC;
			e.worldView = WorldView.TOPLEVEL;
			// thread, material, [shading group,] the object's plane, orientation, position: as FrameTape.dynamic writes
			e.ints = withShading() ? new int[]{-1, 0, 0, o.plane, o.orient, x, y, z}
				: new int[]{-1, 0, o.plane, o.orient, x, y, z};
			e.model = t.models.size() - 1;
			e.projection = 0;
			t.events.add(e);
		}
		t.events.addAll(tail);
		return l;
	}

	/** Whether this branch's tape keeps a shading group with each dynamic model (FrameTape.dynamic's arguments). */
	private static boolean withShading()
	{
		for (java.lang.reflect.Method m : FrameTape.class.getMethods())
		{
			if (m.getName().equals("dynamic"))
			{
				return m.getParameterCount() == 11;
			}
		}
		return false;
	}

	private static ModelData model(Obj o)
	{
		TreeMap<Integer, int[]> verts = new TreeMap<>();
		for (int[] f : o.faces)
		{
			for (int k = 0; k < 3; ++k)
			{
				verts.put(f[4 + k * 7], java.util.Arrays.copyOfRange(f, 5 + k * 7, 11 + k * 7));
			}
		}
		int nv = verts.lastKey() + 1;
		ModelData d = new ModelData();
		d.vertexCount = nv;
		d.verticesX = new float[nv];
		d.verticesY = new float[nv];
		d.verticesZ = new float[nv];
		int[] nx = new int[nv], ny = new int[nv], nz = new int[nv];
		for (var e : verts.entrySet())
		{
			int v = e.getKey();
			int[] p = e.getValue();
			d.verticesX[v] = p[0];
			d.verticesY[v] = p[1];
			d.verticesZ[v] = p[2];
			nx[v] = p[3];
			ny[v] = p[4];
			nz[v] = p[5];
		}
		normals(d, nx, ny, nz);
		int n = o.faces.size();
		d.faceCount = n;
		d.indices1 = new int[n];
		d.indices2 = new int[n];
		d.indices3 = new int[n];
		d.colors1 = new int[n];
		d.colors2 = new int[n];
		d.colors3 = new int[n];
		short[] tex = new short[n];
		boolean textured = false;
		for (int i = 0; i < n; ++i)
		{
			int[] f = o.faces.get(i);
			d.colors1[i] = f[0];
			d.colors2[i] = f[1];
			d.colors3[i] = f[2];
			tex[i] = (short) f[3];
			textured |= f[3] != -1;
			d.indices1[i] = f[4];
			d.indices2[i] = f[11];
			d.indices3[i] = f[18];
		}
		d.textures = textured ? tex : null;
		return d;
	}

	/** The game's vertex normals onto the model, where this branch's ModelData has them. */
	private static void normals(ModelData d, int[] nx, int[] ny, int[] nz)
	{
		try
		{
			ModelData.class.getField("normalsX").set(d, nx);
			ModelData.class.getField("normalsY").set(d, ny);
			ModelData.class.getField("normalsZ").set(d, nz);
		}
		catch (NoSuchFieldException | IllegalAccessException e)
		{
			// a branch that makes its own normals
		}
	}

	/** The game's textures from a live dump's textures.bin.gz (as SceneDump.load reads them). */
	private static void textures(Path dump, SceneDump.Loaded l) throws IOException
	{
		Path file = dump.resolve("textures.bin.gz");
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(
			new BufferedInputStream(Files.newInputStream(file)), 1 << 16), 1 << 16)))
		{
			boolean present = in.readBoolean();
			l.textureBrightness = present ? in.readDouble() : 0;
			int n = present ? in.readInt() : 0;
			l.texturePixels = new int[n][];
			l.textureDirection = new int[n];
			l.textureSpeed = new int[n];
			for (int i = 0; i < n; ++i)
			{
				if (in.readBoolean())
				{
					l.textureDirection[i] = in.readInt();
					l.textureSpeed[i] = in.readInt();
					l.texturePixels[i] = com.midonarhel.lumina.scene.ZoneSnapshot.readInts(in);
				}
			}
		}
	}
}
