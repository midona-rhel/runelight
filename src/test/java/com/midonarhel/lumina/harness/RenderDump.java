package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.config.DebugView;
import com.midonarhel.lumina.dev.SceneDump;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The offline renderer from the command line (gradle renderDump -Pdump=name): draws a dump to final.png and each debug
 * view to view-NAME.png, and compares final.png with the dump's reference.png (diff.png, the numbers on stdout).
 * <pre>
 * args: dump (a name under ~/.runelite/lumina/dumps, a directory, or "synthetic")
 *       [out=dir] [views=NORMALS,SKYLIGHT|all|none] [hour=h] [yaw=n] [pitch=n] [scale=n] [warmup=s] [step=s]
 *       [key=value ...]  any setting by its key
 * </pre>
 */
public final class RenderDump
{
	private RenderDump()
	{
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length < 1)
		{
			System.err.println("usage: RenderDump dump [out=dir] [views=...] [hour=h] [yaw=n] [pitch=n] [scale=n] [key=value...]");
			System.exit(2);
		}
		SceneDump.Loaded dump = load(args[0]);
		Path out = dump.dir != null ? dump.dir.resolve("offline") : Paths.get("build", "offline", "synthetic");
		DumpRenderer.Options o = new DumpRenderer.Options();
		int timeFrames = 0;
		List<Double> later = new ArrayList<>();
		List<DebugView> views = new ArrayList<>(List.of(DebugView.NORMALS, DebugView.SKYLIGHT, DebugView.LIGHTING,
			DebugView.SHADOWS, DebugView.AMBIENT_OCCLUSION, DebugView.DEPTH));
		for (int i = 1; i < args.length; ++i)
		{
			String a = args[i];
			int eq = a.indexOf('=');
			if (eq < 0)
			{
				throw new IllegalArgumentException("not key=value: " + a);
			}
			String k = a.substring(0, eq), v = a.substring(eq + 1);
			switch (k)
			{
				case "out":
					out = Paths.get(v);
					break;
				case "views":
					views.clear();
					if (v.equals("all"))
					{
						views.addAll(List.of(DebugView.values()));
						views.remove(DebugView.FINAL);
					}
					else if (!v.equals("none"))
					{
						for (String s : v.split(","))
						{
							views.add(DebugView.valueOf(s.trim().toUpperCase()));
						}
					}
					break;
				case "hour":
					o.hour = Integer.parseInt(v);
					break;
				case "yaw":
					o.cameraYaw = Integer.parseInt(v);
					break;
				case "pitch":
					o.cameraPitch = Integer.parseInt(v);
					break;
				case "scale":
					o.scale = Integer.parseInt(v);
					break;
				case "time":
					timeFrames = Integer.parseInt(v);
					break;
				case "weatherState":
					o.restoreWeather = !v.equals("off") && !v.equals("false");
					break;
				case "snowCover":
					o.snowCover = Float.parseFloat(v);
					break;
				case "warmup":
					o.warmupSeconds = Double.parseDouble(v);
					break;
				case "step":
					o.frameSeconds = Double.parseDouble(v);
					break;
				case "focus":
				{
					String[] xy = v.split(",");
					o.focus = new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
					break;
				}
				case "buildings":
					o.buildings = v;
					break;
				case "mapProbe":
				{
					String[] xy = v.split(",");
					o.mapProbe = new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())};
					break;
				}
				case "mapModels":
					o.mapModels = v;
					break;
				case "lightMap":
					o.lightMap = !v.equals("off") && !v.equals("false");
					break;
				case "lampRoofs":
					o.lampRoofs = !v.equals("off") && !v.equals("false");
					break;
				case "aurora":
					// the aurora held at this strength (NightSky.auroraHold), wherever the dump is
					com.midonarhel.lumina.sky.NightSky.auroraHold = Float.parseFloat(v);
					break;
				case "later":
					// also frames this many seconds after the dump's, final-<s>s.png: what moves with time
					for (String t : v.split(","))
					{
						later.add(Double.parseDouble(t.trim()));
					}
					break;
				case "probe":
					for (String t : v.split(";"))
					{
						String[] xy = t.split(",");
						o.probes.add(new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
					}
					break;
				default:
					o.settings.put(k, v);
			}
		}
		Files.createDirectories(out);
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			long t0 = System.nanoTime();
			BufferedImage img = r.render();
			System.out.printf("final.png: %dx%d in %.1f s; top %06x middle %06x bottom %06x%n", img.getWidth(), img.getHeight(),
				(System.nanoTime() - t0) / 1e9, img.getRGB(img.getWidth() / 2, 5) & 0xFFFFFF,
				img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF, img.getRGB(img.getWidth() / 2, img.getHeight() - 6) & 0xFFFFFF);
			ImageIO.write(img, "png", out.resolve("final.png").toFile());
			if (dump.dir != null && Files.exists(dump.dir.resolve("reference.png")))
			{
				BufferedImage ref = ImageIO.read(dump.dir.resolve("reference.png").toFile());
				Checks.Diff diff = Checks.diff(ref, img);
				ImageIO.write(diff.image, "png", out.resolve("diff.png").toFile());
				System.out.println("against reference.png: " + diff);
			}
			if (timeFrames > 0)
			{
				double[] t = r.time(timeFrames);
				System.out.printf("frame time over %d frames: GPU median %.3f ms (min %.3f), CPU median %.3f ms (min %.3f)%n",
					timeFrames, t[0], t[1], t[2], t[3]);
			}
			for (double t : later)
			{
				ImageIO.write(r.renderLater(t), "png", out.resolve(String.format("final-%ss.png", t)).toFile());
			}
			for (DebugView view : views)
			{
				r.set("debugView", view.name());
				ImageIO.write(r.renderAgain(), "png", out.resolve("view-" + view.name().toLowerCase() + ".png").toFile());
			}
			System.out.println("written to " + out.toAbsolutePath());
		}
		System.exit(0);
	}

	/** A dump by name (under SceneDump.DIR), by directory, the synthetic one, or a faces file's models. */
	public static SceneDump.Loaded load(String name) throws IOException
	{
		if (name.equals("synthetic"))
		{
			return SyntheticDump.make();
		}
		if (name.startsWith("faces:"))
		{
			// faces:FILE:CENTRE_ID[:TEXTURES_DUMP], the models of a `faces [radius] detail` file (FacesDump)
			String[] p = name.split(":");
			return FacesDump.make(Paths.get(p[1]), Integer.parseInt(p[2]), p.length > 3 ? Paths.get(p[3]) : null);
		}
		Path dir = Paths.get(name);
		if (!Files.isDirectory(dir))
		{
			dir = SceneDump.DIR.resolve(name);
		}
		return SceneDump.load(dir);
	}
}
