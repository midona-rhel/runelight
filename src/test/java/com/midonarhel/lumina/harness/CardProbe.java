package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.light.CardLamps;
import com.midonarhel.lumina.light.PointLight;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/**
 * What the oracle takes from one rendered frame of a dump, and nothing a lamp method draws: each window pixel's world
 * position (the frame's depth) and smooth normal (the NORMALS view), the camera, the levels and hidden roofs the frame
 * drew, and the frame's lamps. All of it follows from the dump, so it is taken once and shared by every method.
 */
final class CardProbe
{
	int width, height;
	float camX, camY, camZ;
	int minLevel, level, maxLevel;
	Set<Integer> hiddenRoofs = new HashSet<>();
	// per pixel, x y z (NaN for sky or under the interface), and the smooth normal
	float[] pos, normal;
	// per lamp: CardLamps' fields; whether its storey is one the client hides
	float[][] lamps;
	boolean[] onHidden;
	// per air ray (CardOracle.AIR_STRIDE): its unit direction from the camera and where it ends, far off for the sky
	float[] air;

	static CardProbe take(DumpRenderer r) throws Exception
	{
		LuminaPlugin plugin = r.plugin;
		CardProbe p = new CardProbe();
		p.width = r.width;
		p.height = r.height;
		LuminaPlugin.SceneContext ctx = plugin.root;
		p.minLevel = ctx.minLevel;
		p.level = ctx.level;
		p.maxLevel = ctx.maxLevel;
		p.hiddenRoofs.addAll(ctx.hideRoofIds);
		p.camX = plugin.frameCameraX;
		p.camY = plugin.frameCameraY;
		p.camZ = plugin.frameCameraZ;
		List<PointLight> lights = new ArrayList<>(plugin.frameLights);
		p.lamps = new float[lights.size()][];
		p.onHidden = new boolean[lights.size()];
		Scene top = r.world.scenes.get(WorldView.TOPLEVEL);
		int[][][] roofs = top.getRoofs();
		for (int i = 0; i < p.lamps.length; ++i)
		{
			p.lamps[i] = CardLamps.describe(plugin, lights.get(i));
			p.onHidden[i] = hiddenStorey(p.lamps[i], roofs, ctx.hideRoofIds, ctx.level);
		}
		FrameProbe depth = new FrameProbe(plugin, r.width, r.height);
		boolean[] ui = uiCovers(r);
		int n = r.width * r.height;
		p.pos = new float[n * 3];
		for (int y = 0; y < r.height; ++y)
		{
			for (int x = 0; x < r.width; ++x)
			{
				int i = (y * r.width + x) * 3;
				float[] w = ui[i / 3] ? null : depth.world(x, y);
				if (w == null)
				{
					p.pos[i] = p.pos[i + 1] = p.pos[i + 2] = Float.NaN;
				}
				else
				{
					System.arraycopy(w, 0, p.pos, i, 3);
				}
			}
		}
		int aw = r.width / CardOracle.AIR_STRIDE, ah = r.height / CardOracle.AIR_STRIDE;
		p.air = new float[aw * ah * 4];
		float[] inv = plugin.frameInvViewProj;
		int[] vp = plugin.frameViewport;
		for (int gy = 0; gy < ah; ++gy)
		{
			for (int gx = 0; gx < aw; ++gx)
			{
				int x = gx * CardOracle.AIR_STRIDE + CardOracle.AIR_STRIDE / 2, y = gy * CardOracle.AIR_STRIDE + CardOracle.AIR_STRIDE / 2;
				float[] w = depth.world(x, y);
				float dx, dy, dz, end;
				if (w != null)
				{
					dx = w[0] - p.camX;
					dy = w[1] - p.camY;
					dz = w[2] - p.camZ;
					end = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				}
				else
				{
					// a point on the near plane gives the ray, as frame.glsl has it
					float nx = (x + 0.5f - vp[0]) / vp[2] * 2 - 1, ny = (r.height - 1 - y + 0.5f - vp[1]) / vp[3] * 2 - 1;
					float hx = inv[0] * nx + inv[4] * ny + inv[8] + inv[12], hy = inv[1] * nx + inv[5] * ny + inv[9] + inv[13];
					float hz = inv[2] * nx + inv[6] * ny + inv[10] + inv[14], hw = inv[3] * nx + inv[7] * ny + inv[11] + inv[15];
					dx = hx / hw;
					dy = hy / hw;
					dz = hz / hw;
					end = 1e6f;
				}
				float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				int g = (gy * aw + gx) * 4;
				p.air[g] = dx / l;
				p.air[g + 1] = dy / l;
				p.air[g + 2] = dz / l;
				p.air[g + 3] = end;
			}
		}
		r.set("debugView", "NORMALS");
		r.frame();
		r.frame();
		int[] rgb = Card.pixels(r.readBack());
		r.set("debugView", "FINAL");
		r.frame();
		p.normal = new float[n * 3];
		for (int i = 0; i < n; ++i)
		{
			float nx = (rgb[i] >> 16 & 255) / 127.5f - 1, ny = (rgb[i] >> 8 & 255) / 127.5f - 1, nz = (rgb[i] & 255) / 127.5f - 1;
			float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			l = l < 1e-3f ? 0 : 1 / l;
			p.normal[i * 3] = nx * l;
			p.normal[i * 3 + 1] = ny * l;
			p.normal[i * 3 + 2] = nz * l;
		}
		return p;
	}

	/** Lamps on storeys the client hides light no surface: the lamp's storey hidden by a roof id one level under it. */
	private static boolean hiddenStorey(float[] lamp, int[][][] roofs, Set<Integer> ids, int level)
	{
		int plane = (int) lamp[CardLamps.PLANE];
		if (roofs == null || ids == null || ids.isEmpty() || plane <= level || plane < 1 || plane >= roofs.length)
		{
			return false;
		}
		int tx = (int) Math.floor(lamp[CardLamps.X] / 128) + LuminaPlugin.SCENE_OFFSET;
		int tz = (int) Math.floor(lamp[CardLamps.Z] / 128) + LuminaPlugin.SCENE_OFFSET;
		int[][] below = roofs[plane - 1];
		if (tx < 0 || tz < 0 || tx >= below.length || tz >= below[tx].length)
		{
			return false;
		}
		int rid = below[tx][tz];
		return rid > 0 && ids.contains(rid);
	}

	// the window pixels the interface covers: the dump's interface, up to the window's size
	private static boolean[] uiCovers(DumpRenderer r)
	{
		boolean[] out = new boolean[r.width * r.height];
		int[] ui = r.dump.uiPixels;
		int w = r.dump.uiWidth, h = r.dump.uiHeight;
		if (ui == null || w <= 0 || h <= 0)
		{
			return out;
		}
		for (int y = 0; y < r.height; ++y)
		{
			int uy = Math.min(h - 1, y * h / r.height);
			for (int x = 0; x < r.width; ++x)
			{
				int ux = Math.min(w - 1, x * w / r.width);
				out[y * r.width + x] = (ui[uy * w + ux] >>> 24) != 0;
			}
		}
		return out;
	}

	void save(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)))
		{
			o.writeInt(width);
			o.writeInt(height);
			o.writeFloat(camX);
			o.writeFloat(camY);
			o.writeFloat(camZ);
			o.writeInt(minLevel);
			o.writeInt(level);
			o.writeInt(maxLevel);
			o.writeInt(hiddenRoofs.size());
			for (int id : hiddenRoofs)
			{
				o.writeInt(id);
			}
			o.writeInt(lamps.length);
			for (int i = 0; i < lamps.length; ++i)
			{
				for (float f : lamps[i])
				{
					o.writeFloat(f);
				}
				o.writeBoolean(onHidden[i]);
			}
			writeFloats(o, pos);
			writeFloats(o, normal);
			o.writeInt(air.length);
			writeFloats(o, air);
		}
	}

	static CardProbe load(Path file) throws IOException
	{
		CardProbe p = new CardProbe();
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20)))
		{
			p.width = in.readInt();
			p.height = in.readInt();
			p.camX = in.readFloat();
			p.camY = in.readFloat();
			p.camZ = in.readFloat();
			p.minLevel = in.readInt();
			p.level = in.readInt();
			p.maxLevel = in.readInt();
			int ids = in.readInt();
			for (int i = 0; i < ids; ++i)
			{
				p.hiddenRoofs.add(in.readInt());
			}
			int n = in.readInt();
			p.lamps = new float[n][CardLamps.FIELDS];
			p.onHidden = new boolean[n];
			for (int i = 0; i < n; ++i)
			{
				for (int k = 0; k < CardLamps.FIELDS; ++k)
				{
					p.lamps[i][k] = in.readFloat();
				}
				p.onHidden[i] = in.readBoolean();
			}
			p.pos = readFloats(in, p.width * p.height * 3);
			p.normal = readFloats(in, p.width * p.height * 3);
			p.air = readFloats(in, in.readInt());
		}
		return p;
	}

	private static void writeFloats(DataOutputStream o, float[] a) throws IOException
	{
		ByteBuffer b = ByteBuffer.allocate(a.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.asFloatBuffer().put(a);
		o.write(b.array());
	}

	private static float[] readFloats(DataInputStream in, int n) throws IOException
	{
		byte[] raw = new byte[n * 4];
		in.readFully(raw);
		float[] a = new float[n];
		ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a);
		return a;
	}
}
