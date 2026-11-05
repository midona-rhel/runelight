package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.dev.GpuSections;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.CardLamps;
import com.midonarhel.lumina.scene.CardZones;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.DoorAccess;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * Part 2's moving half, and part 4's changes: how still the lamps' light stays while the camera turns slowly, and what
 * a door and a roof do to the frames after them and to the client thread.
 *
 * <p>Camera: the lamps' light on a surface doesn't depend on where it is seen from. Each step of a slow turn is drawn with
 * the lamps and without, on the LIGHTING view; a point on one frame is found again on the next by its world position,
 * and its rise compared, the best of its 3 x 3 neighbours, so only a real change counts: a lamp popping, a cluster's cap,
 * a shadow map's slot moving.
 *
 * <p>Change: the same 60 frames at 60 FPS drawn twice from the same clock, without and with the change, so what moves
 * with time cancels. A door: a wall edge of the walls toggled, the rooms built again for it and its zone uploaded again
 * without the wall's triangles, as the client's would be: every method sees what it sees in the client. A roof: the
 * hidden roof nearest the player, shown. The rooms' fill is off here, so the lamps' light is all that changes; pixels
 * whose surface changed (the roof itself, what shows through the gap) don't count.
 */
final class CardMotion
{
	static final int CAMERA_STEPS = 12, CAMERA_STEP = 12;
	static final int CHANGE_FRAMES = 60;
	// doors by dump: world x, y, edge; others take the wall edge nearest the player that a lamp lights
	private static final Map<String, String> DOORS = Map.of("artifact1", "3208,3211,N");

	private CardMotion()
	{
	}

	static Map<String, Object> run(SceneDump.Loaded dump, Path out, String name) throws Exception
	{
		Map<String, Object> res = Card.map();
		DumpRenderer.Options o = Card.options("CLEAR", false);
		o.cameraYaw = dump.manifest.cameraYaw;
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			res.put("camera", camera(r, out, name));
		}
		try (DumpRenderer r = new DumpRenderer(dump, changeOptions()))
		{
			r.render();
			res.put("door", door(r));
		}
		try (DumpRenderer r = new DumpRenderer(dump, changeOptions()))
		{
			r.render();
			res.put("roof", roof(r));
		}
		return res;
	}

	// a door moves the rooms, and so their fill, which is no lamp's: none here, only the lamps' light changes
	private static DumpRenderer.Options changeOptions()
	{
		DumpRenderer.Options o = Card.options("CLEAR", false);
		o.settings.put("buildingLightBrightness", "0");
		return o;
	}

	private static final class Step
	{
		int[] rise;
		float[] pos;
		float[] viewProj;
		float[] cam;
	}

	private static Map<String, Object> camera(DumpRenderer r, Path out, String name) throws Exception
	{
		LuminaPlugin plugin = r.plugin;
		r.set("debugView", "LIGHTING");
		r.frame();
		r.frame();
		Card.Lamps lamps = new Card.Lamps(plugin);
		int w = r.width, h = r.height, gw = w / 2, gh = h / 2;
		int yaw = r.world.cameraYaw;
		Step last = null;
		double sum = 0, worst = 0;
		int biggest = 0;
		int[] worstMask = new int[w * h];
		for (int s = 0; s <= CAMERA_STEPS; ++s)
		{
			r.world.cameraYaw = yaw + s * CAMERA_STEP;
			lamps.all();
			r.frame();
			r.frame();
			int[] on = Card.pixels(r.readBack()).clone();
			FrameProbe probe = new FrameProbe(plugin, w, h);
			Step now = new Step();
			now.viewProj = plugin.frameViewProj.clone();
			now.cam = new float[]{plugin.frameCameraX, plugin.frameCameraY, plugin.frameCameraZ};
			lamps.none();
			r.frame();
			int[] off = Card.pixels(r.readBack());
			now.rise = new int[gw * gh];
			now.pos = new float[gw * gh * 3];
			for (int y = 0; y < gh; ++y)
			{
				for (int x = 0; x < gw; ++x)
				{
					int c = y * gw + x, i = y * 2 * w + x * 2;
					now.rise[c] = Math.max(0, Card.rise(on[i], off[i]));
					float[] p = probe.world(x * 2, y * 2);
					now.pos[c * 3] = p == null ? Float.NaN : p[0];
					if (p != null)
					{
						now.pos[c * 3 + 1] = p[1];
						now.pos[c * 3 + 2] = p[2];
					}
				}
			}
			if (last != null)
			{
				boolean[] moved = new boolean[gw * gh];
				long compared = 0, changed = 0;
				float[] m = now.viewProj;
				for (int c = 0; c < gw * gh; ++c)
				{
					if (Float.isNaN(last.pos[c * 3]) || last.rise[c] <= 4)
					{
						continue;
					}
					float px = last.pos[c * 3] - now.cam[0], py = last.pos[c * 3 + 1] - now.cam[1], pz = last.pos[c * 3 + 2] - now.cam[2];
					float cw = m[3] * px + m[7] * py + m[11] * pz + m[15];
					if (cw <= 0)
					{
						continue;
					}
					float nx = (m[0] * px + m[4] * py + m[8] * pz + m[12]) / cw, ny = (m[1] * px + m[5] * py + m[9] * pz + m[13]) / cw;
					int qx = (int) ((nx * 0.5f + 0.5f) * plugin.frameViewport[2] + plugin.frameViewport[0]) / 2;
					int qy = (int) ((h - ((ny * 0.5f + 0.5f) * plugin.frameViewport[3] + plugin.frameViewport[1])) - 1) / 2;
					int best = Integer.MAX_VALUE;
					for (int y = Math.max(0, qy - 1); y <= Math.min(gh - 1, qy + 1); ++y)
					{
						for (int x = Math.max(0, qx - 1); x <= Math.min(gw - 1, qx + 1); ++x)
						{
							int d = y * gw + x;
							float a = now.pos[d * 3] - last.pos[c * 3], b = now.pos[d * 3 + 1] - last.pos[c * 3 + 1], e = now.pos[d * 3 + 2] - last.pos[c * 3 + 2];
							if (a * a + b * b + e * e < 36)
							{
								best = Math.min(best, Math.abs(now.rise[d] - last.rise[c]));
							}
						}
					}
					if (best == Integer.MAX_VALUE)
					{
						continue;
					}
					++compared;
					if (best > 6)
					{
						moved[c] = true;
					}
				}
				// a shape, not the thin lines a silhouette leaves as it slides over a pixel: cells with all four neighbours moved
				boolean[] core = new boolean[gw * gh];
				for (int y = 1; y + 1 < gh; ++y)
				{
					for (int x = 1; x + 1 < gw; ++x)
					{
						int c = y * gw + x;
						if (moved[c] && moved[c - 1] && moved[c + 1] && moved[c - gw] && moved[c + gw])
						{
							core[c] = true;
							++changed;
						}
					}
				}
				moved = core;
				double pct = 100.0 * changed / Math.max(1, compared);
				sum += pct;
				if (pct > worst)
				{
					worst = pct;
					Arrays.fill(worstMask, 0);
					for (int c = 0; c < gw * gh; ++c)
					{
						if (moved[c])
						{
							int i = (c / gw) * 2 * w + (c % gw) * 2;
							worstMask[i] = worstMask[i + 1] = worstMask[i + w] = worstMask[i + w + 1] = 0xFFFFFF;
						}
					}
				}
				for (int[] g : Card.groups(moved, gw, gh))
				{
					biggest = Math.max(biggest, g[0] * 4);
				}
			}
			last = now;
		}
		Card.png(worstMask, w, h, out.resolve("camera-" + name + ".png"));
		Map<String, Object> m = Card.map();
		m.put("steps", CAMERA_STEPS);
		m.put("yawUnitsAStep", CAMERA_STEP);
		m.put("unstablePctMean", Card.round(sum / CAMERA_STEPS, 3));
		m.put("unstablePctWorst", Card.round(worst, 3));
		m.put("biggestPopPx", biggest);
		return m;
	}

	private interface Change
	{
		void apply() throws Exception;
	}

	// the frames from the clock now, read back at every second pixel: their first channel (the lamps are warm) in linear
	// light, to 16 bits; ms on this thread and on the GPU
	private static char[][] frames(DumpRenderer r, long millis, long nanos, double[] cpu, double[] gpu) throws Exception
	{
		return frames(r, millis, nanos, cpu, gpu, null);
	}

	// the clock goes back to the start: frames of 60 FPS lead up to it first, so what steps by the clock steps as it does in
	// play from the first frame kept on. The change comes just before that frame.
	private static char[][] frames(DumpRenderer r, long millis, long nanos, double[] cpu, double[] gpu, Change change) throws Exception
	{
		char[][] out = new char[CHANGE_FRAMES][];
		String[] names = new String[64];
		double[] ms = new double[64];
		int gw = r.width / 2, gh = r.height / 2;
		for (int i = 4; i > 0; --i)
		{
			long dt = i * 1_000_000_000L / 60;
			Clock.set(millis - dt / 1_000_000, nanos - dt);
			r.frame();
		}
		if (change != null)
		{
			change.apply();
		}
		for (int i = 0; i < CHANGE_FRAMES; ++i)
		{
			long dt = i * 1_000_000_000L / 60;
			Clock.set(millis + dt / 1_000_000, nanos + dt);
			GpuSections.on = true;
			long t0 = System.nanoTime();
			r.frame();
			cpu[i] = (System.nanoTime() - t0) / 1e6;
			int n = GpuSections.read(names, ms);
			GpuSections.on = false;
			double sum = 0;
			for (int k = 0; k < n; ++k)
			{
				sum += ms[k];
			}
			gpu[i] = sum;
			int[] rgb = Card.pixels(r.readBack());
			char[] red = new char[gw * gh];
			for (int y = 0; y < gh; ++y)
			{
				for (int x = 0; x < gw; ++x)
				{
					red[y * gw + x] = (char) (Card.linear(rgb[y * 2 * r.width + x * 2] >> 16 & 255) * 65535);
				}
			}
			out[i] = red;
		}
		return out;
	}

	// the 8 bit value again, as the view encodes
	private static int encoded(char linear)
	{
		double v = linear / 65535.0;
		v = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
		return (int) Math.round(v * 255);
	}

	private static Map<String, Object> change(DumpRenderer r, Change change) throws Exception
	{
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		SceneDump.Manifest m = r.dump.manifest;
		r.set("debugView", "LIGHTING");
		r.frame();
		r.frame();
		GpuSections.reset();
		long millis = m.millis, nanos = Clock.nanos();
		double[] cpu0 = new double[CHANGE_FRAMES], gpu0 = new double[CHANGE_FRAMES], cpu1 = new double[CHANGE_FRAMES], gpu1 = new double[CHANGE_FRAMES];
		frames(r, millis, nanos, cpu0, gpu0); // the first pass warms what the others compare with
		char[][] onBefore = frames(r, millis, nanos, cpu0, gpu0);
		FrameProbe was = new FrameProbe(r.plugin, r.width, r.height);
		System.gc();
		Map<Long, Long> bg0 = Card.threadCpu(bean);
		char[][] onAfter = frames(r, millis, nanos, cpu1, gpu1, change);
		double background = Card.threadCpuSince(bean, bg0);
		FrameProbe now = new FrameProbe(r.plugin, r.width, r.height);

		int gw = r.width / 2, gh = r.height / 2, n = gw * gh;
		// the pixels that show what they showed before: a roof that appears or a door gone is not light changing
		boolean[] same = new boolean[n];
		for (int y = 0; y < gh; ++y)
		{
			for (int x = 0; x < gw; ++x)
			{
				float[] a = was.world(x * 2, y * 2), b = now.world(x * 2, y * 2);
				same[y * gw + x] = a != null && b != null && Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) < 4;
			}
		}
		int last = CHANGE_FRAMES - 1;
		short[] end = new short[n];
		long changed = 0;
		for (int i = 0; i < n; ++i)
		{
			end[i] = same[i] ? (short) (encoded(onAfter[last][i]) - encoded(onBefore[last][i])) : 0;
			changed += Math.abs(end[i]) > 2 ? 1 : 0;
		}
		short[] prev = new short[n];
		double total = 0, biggest = 0;
		long popPx = 0;
		int maxStep = 0, settle = 0;
		for (int f = 0; f < CHANGE_FRAMES; ++f)
		{
			long sum = 0, away = 0, big = 0;
			for (int i = 0; i < n; ++i)
			{
				int v = same[i] ? encoded(onAfter[f][i]) - encoded(onBefore[f][i]) : 0;
				int step = Math.abs(v - prev[i]);
				sum += step;
				if (step > 8)
				{
					++big;
				}
				maxStep = Math.max(maxStep, step);
				prev[i] = (short) v;
				away += Math.abs(v - end[i]) > 2 ? 1 : 0;
			}
			total += sum;
			biggest = Math.max(biggest, sum);
			popPx = Math.max(popPx, big);
			if (away > changed / 1000 + 4)
			{
				settle = f + 1;
			}
		}
		Map<String, Object> o = Card.map();
		o.put("changedPx", changed * 4);
		o.put("framesToSettle", settle);
		o.put("biggestFrameSharePct", Card.round(100 * biggest / Math.max(1, total), 1));
		o.put("worstStepOf255", maxStep);
		o.put("popPx", popPx * 4);
		o.put("clientThreadWorstFrameExtraMs", Card.round(worstExtra(cpu1, cpu0), 2));
		o.put("clientThreadExtraMsTotal", Card.round(sumExtra(cpu1, cpu0), 2));
		o.put("backgroundCpuMs", Card.round(background, 1));
		o.put("gpuWorstFrameExtraMs", Card.round(worstExtra(gpu1, gpu0), 2));
		o.put("steadyFrameCpuMs", Card.round(median(cpu0), 2));
		return o;
	}

	private static double median(double[] a)
	{
		double[] c = a.clone();
		Arrays.sort(c);
		return c[c.length / 2];
	}

	private static double worstExtra(double[] a, double[] base)
	{
		double m = median(base), worst = 0;
		for (double v : a)
		{
			worst = Math.max(worst, v - m);
		}
		return worst;
	}

	private static double sumExtra(double[] a, double[] base)
	{
		double m = median(base), s = 0;
		for (double v : a)
		{
			s += Math.max(0, v - m);
		}
		return s;
	}

	private static Map<String, Object> door(DumpRenderer r) throws Exception
	{
		SceneDump.Manifest m = r.dump.manifest;
		BuildingLights b = r.plugin.buildingLights;
		WallMap walls = b.getWalls();
		Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
		int[] edge = find(r, walls);
		Map<String, Object> o = Card.map();
		if (edge == null)
		{
			o.put("none", "no wall edge a lamp lights near the player");
			return o;
		}
		int x = edge[0], y = edge[1], side = edge[2];
		o.put("door", new int[]{x - LuminaPlugin.SCENE_OFFSET + m.baseX, y - LuminaPlugin.SCENE_OFFSET + m.baseY});
		o.put("edge", String.valueOf("NESW".charAt(side)));
		float[] box = box(x, y, side, top.getTileHeights());
		int[] removed = new int[1];
		Map<String, Object> c = change(r, () ->
		{
			WallMap w = DoorAccess.toggle(walls, m.plane, x, y, side);
			RoomMap rooms = DoorAccess.rooms(b.getCoarse(), top.getExtendedTileSettings(), top.getTileHeights(), LuminaPlugin.SCENE_OFFSET, w, b.getFloors());
			b.restore(top, b.getResult(), b.getCoarse(), rooms != null ? rooms : b.getRooms(), w, b.getFloors(), b.getRoofs(), b.getDrawnWalls());
			int offset = LuminaPlugin.SCENE_OFFSET >> 3;
			for (ZoneSnapshot s : topZones(r))
			{
				if (touches(s, box) && CardZones.inBox(s, (s.x - offset) << 10, (s.z - offset) << 10, box) > 0)
				{
					removed[0] += CardZones.replace(r.plugin, s, (s.x - offset) << 10, (s.z - offset) << 10, box);
				}
			}
		});
		o.put("trianglesTaken", removed[0]);
		o.putAll(c);
		return o;
	}

	private static List<ZoneSnapshot> topZones(DumpRenderer r)
	{
		for (int v = 0; v < r.dump.views.size(); ++v)
		{
			if (r.dump.views.get(v)[0] == WorldView.TOPLEVEL)
			{
				return r.dump.zones.get(v);
			}
		}
		return new ArrayList<>();
	}

	private static boolean touches(ZoneSnapshot s, float[] box)
	{
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		int x0 = (s.x - offset) << 10, z0 = (s.z - offset) << 10;
		return box[0] < x0 + 1024 + 256 && box[3] > x0 - 256 && box[2] < z0 + 1024 + 256 && box[5] > z0 - 256;
	}

	// the box of a tile's wall edge, local units: along it a tile, across it a little either side, the storey's height
	private static float[] box(int x, int y, int side, int[][][] heights)
	{
		float x0 = (x - LuminaPlugin.SCENE_OFFSET) * 128, z0 = (y - LuminaPlugin.SCENE_OFFSET) * 128;
		float h = heights[0][x][y];
		float across = 32, along = 4;
		switch (side)
		{
			case 0:
				return new float[]{x0 - along, h - 340, z0 + 128 - across, x0 + 128 + along, h + 16, z0 + 128 + across};
			case 1:
				return new float[]{x0 + 128 - across, h - 340, z0 - along, x0 + 128 + across, h + 16, z0 + 128 + along};
			case 2:
				return new float[]{x0 - along, h - 340, z0 - across, x0 + 128 + along, h + 16, z0 + across};
			default:
				return new float[]{x0 - across, h - 340, z0 - along, x0 + across, h + 16, z0 + 128 + along};
		}
	}

	// extended tile x, y and edge: the dump's door, or the wall edge nearest the player with triangles a lamp reaches
	private static int[] find(DumpRenderer r, WallMap walls) throws Exception
	{
		SceneDump.Manifest m = r.dump.manifest;
		int off = LuminaPlugin.SCENE_OFFSET;
		Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
		String[] known = DOORS.getOrDefault(r.dump.dir != null ? r.dump.dir.getFileName().toString() : "", "").split(",");
		if (known.length == 3)
		{
			int x = Integer.parseInt(known[0]) - m.baseX + off, y = Integer.parseInt(known[1]) - m.baseY + off;
			if (x >= 0 && y >= 0 && x < walls.size && y < walls.size)
			{
				return new int[]{x, y, "NESW".indexOf(known[2])};
			}
		}
		int[] sides = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};
		int px = (m.playerLocal[0] >> 7) + off, py = (m.playerLocal[1] >> 7) + off;
		int offset = off >> 3;
		float[][] lamps = new float[r.plugin.frameLights.size()][];
		for (int i = 0; i < lamps.length; ++i)
		{
			lamps[i] = CardLamps.describe(r.plugin, r.plugin.frameLights.get(i));
		}
		for (int ring = 0; ring < 48; ++ring)
		{
			for (int y = py - ring; y <= py + ring; ++y)
			{
				for (int x = px - ring; x <= px + ring; ++x)
				{
					if (Math.max(Math.abs(x - px), Math.abs(y - py)) != ring || x < 1 || y < 1 || x + 1 >= walls.size || y + 1 >= walls.size)
					{
						continue;
					}
					for (int s = 0; s < 4; ++s)
					{
						if (!walls.edge(m.plane, x, y, sides[s]))
						{
							continue;
						}
						float cx = (x - off + 0.5f) * 128, cz = (y - off + 0.5f) * 128;
						if (!onScreen(r, cx, top.getTileHeights()[0][x][y] - 100, cz))
						{
							continue;
						}
						// a lamp this edge alone keeps from the floor on its other side, in view
						int ox = x + (s == 1 ? 1 : s == 3 ? -1 : 0), oy = y + (s == 0 ? 1 : s == 2 ? -1 : 0);
						float qx = (ox - off + 0.5f) * 128, qz = (oy - off + 0.5f) * 128;
						if (!onScreen(r, qx, top.getTileHeights()[0][ox][oy], qz))
						{
							continue;
						}
						WallMap open = DoorAccess.toggle(walls, m.plane, x, y, s);
						boolean freed = false;
						for (float[] l : lamps)
						{
							float dx = l[CardLamps.OX] - qx, dz = l[CardLamps.OZ] - qz;
							freed |= (int) l[CardLamps.PLANE] == m.plane && dx * dx + dz * dz < l[CardLamps.RADIUS] * l[CardLamps.RADIUS] * 0.5f
								&& walls.blocked(m.plane, l[CardLamps.OX], l[CardLamps.OZ], qx, qz) && !open.blocked(m.plane, l[CardLamps.OX], l[CardLamps.OZ], qx, qz);
						}
						if (!freed)
						{
							continue;
						}
						float[] box = box(x, y, s, top.getTileHeights());
						for (ZoneSnapshot z : topZones(r))
						{
							if (touches(z, box) && CardZones.inBox(z, (z.x - offset) << 10, (z.z - offset) << 10, box) > 0)
							{
								return new int[]{x, y, s};
							}
						}
					}
				}
			}
		}
		return null;
	}

	private static boolean onScreen(DumpRenderer r, float x, float y, float z)
	{
		float[] m = r.plugin.frameViewProj;
		float px = x - r.plugin.frameCameraX, py = y - r.plugin.frameCameraY, pz = z - r.plugin.frameCameraZ;
		float cw = m[3] * px + m[7] * py + m[11] * pz + m[15];
		if (cw <= 0)
		{
			return false;
		}
		float nx = (m[0] * px + m[4] * py + m[8] * pz + m[12]) / cw, ny = (m[1] * px + m[5] * py + m[9] * pz + m[13]) / cw;
		return Math.abs(nx) < 0.9f && Math.abs(ny) < 0.9f;
	}

	// of the roofs the client hides, the one nearest the player
	private static int nearestRoof(DumpRenderer r)
	{
		SceneDump.Manifest m = r.dump.manifest;
		int[][][] roofs = r.world.scenes.get(WorldView.TOPLEVEL).getRoofs();
		int off = LuminaPlugin.SCENE_OFFSET;
		int px = (m.playerLocal[0] >> 7) + off, py = (m.playerLocal[1] >> 7) + off;
		long best = Long.MAX_VALUE;
		int id = 0;
		for (int[][] level : roofs)
		{
			for (int x = 0; x < level.length; ++x)
			{
				for (int y = 0; y < level[x].length; ++y)
				{
					long d = (long) (x - px) * (x - px) + (long) (y - py) * (y - py);
					if (level[x][y] != 0 && d < best && r.plugin.root.hideRoofIds.contains(level[x][y]))
					{
						best = d;
						id = level[x][y];
					}
				}
			}
		}
		return id;
	}

	private static Map<String, Object> roof(DumpRenderer r) throws Exception
	{
		Map<String, Object> o = Card.map();
		int id = nearestRoof(r);
		if (id == 0)
		{
			o.put("none", "the client hid no roof at this dump");
			return o;
		}
		o.put("roof", id);
		o.putAll(change(r, () -> r.options.showRoofIds.add(id)));
		return o;
	}
}
