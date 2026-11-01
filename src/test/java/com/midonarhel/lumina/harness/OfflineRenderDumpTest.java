package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.FrameTape;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.api.WorldView;
import static org.junit.Assert.assertTrue;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * A live dump drawn offline and checked: against the client's own frame (reference.png), and by the automatic checks.
 * Runs on the dump named by -Dlumina.dump=name (a name under ~/.runelite/lumina/dumps or a directory); skipped
 * without. Writes what it drew and found to the dump's offline-test/ directory. Tolerances: -Dlumina.mean (mean channel
 * difference, default 3), -Dlumina.over8 (share of pixels off by more than 8, default 0.05).
 */
public class OfflineRenderDumpTest
{
	private static SceneDump.Loaded dump;
	private static Path out;

	@BeforeClass
	public static void load() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		dump = RenderDump.load(name);
		out = dump.dir.resolve("offline-test");
		Files.createDirectories(out);
	}

	@Test
	public void matchesTheClientsFrame() throws Exception
	{
		BufferedImage img;
		try (DumpRenderer r = new DumpRenderer(dump, new DumpRenderer.Options()))
		{
			img = r.render();
		}
		ImageIO.write(img, "png", out.resolve("final.png").toFile());
		BufferedImage ref = ImageIO.read(dump.dir.resolve("reference.png").toFile());
		Checks.Diff d = Checks.diff(ref, img);
		ImageIO.write(d.image, "png", out.resolve("diff.png").toFile());
		double mean = Double.parseDouble(System.getProperty("lumina.mean", "3"));
		double over8 = Double.parseDouble(System.getProperty("lumina.over8", "0.05"));
		assertTrue("against reference.png: " + d, d.within(mean, over8));
	}

	@Test
	public void theCameraOrbitIsTheClients()
	{
		// the tape's camera is the focal point less its distance along the view direction (DumpRenderer.orbit)
		SceneDump.Manifest m = dump.manifest;
		for (FrameTape.Event e : dump.tape.events)
		{
			if (e.type == FrameTape.PRE && e.worldView == WorldView.TOPLEVEL)
			{
				float dx = e.floats[0] - m.focalX, dy = e.floats[1] - m.focalY, dz = e.floats[2] - m.focalZ;
				double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double[] o = DumpRenderer.orbit(e.floats[4], e.floats[3]);
				double err = Math.abs(dx / dist - o[0]) + Math.abs(dy / dist - o[1]) + Math.abs(dz / dist - o[2]);
				assertTrue("orbit off by " + err + ": offset " + dx / dist + ", " + dy / dist + ", " + dz / dist, err < 0.05);
				// and the angles are the client's units turned to radians (DumpRenderer.unitsPerTurn)
				double turn = DumpRenderer.unitsPerTurn(dump);
				double yaw = m.cameraYaw * Math.PI * 2 / turn, pitch = m.cameraPitch * Math.PI * 2 / turn;
				double yawErr = Math.abs(Math.IEEEremainder(e.floats[4] - yaw, Math.PI * 2));
				assertTrue("yaw " + e.floats[4] + " rad, " + m.cameraYaw + " of " + turn + " a turn", yawErr < 0.01);
				assertTrue("pitch " + e.floats[3] + " rad, " + m.cameraPitch + " of " + turn + " a turn", Math.abs(e.floats[3] - pitch) < 0.01);
				return;
			}
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theChecksPass() throws Exception
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.settings.put("antiAliasingMode", "DISABLED");
		o.settings.put("renderScale", "FULL");
		// -Dlumina.buildings=rebuild: the buildings built again with the code under test (DumpRenderer.Options)
		o.buildings = System.getProperty("lumina.buildings", "dumped");
		List<PointLight> lamps;
		WallMap walls;
		StringBuilder failures = new StringBuilder();
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			walls = r.plugin.buildingLights.getWalls();
			BufferedImage on = r.render();
			FrameProbe probe = new FrameProbe(r.plugin, r.width, r.height);
			// the lamps the frame drew: the dump's and the frame's own (the test light)
			lamps = new java.util.ArrayList<>(r.plugin.frameLights);
			r.set("debugView", "NORMALS");
			BufferedImage normals = r.renderAgain();
			r.set("debugView", "SKYLIGHT");
			BufferedImage skylight = r.renderAgain();
			r.set("debugView", "FINAL");
			r.set("pointLights", "false");
			BufferedImage off = r.renderAgain();
			ImageIO.write(normals, "png", out.resolve("normals.png").toFile());
			ImageIO.write(skylight, "png", out.resolve("skylight.png").toFile());

			Checks.SkylightSteps steps = Checks.skylightSteps(skylight, normals, probe, 0.06f);
			ImageIO.write(steps.image, "png", out.resolve("skylight-steps.png").toFile());
			System.out.println("skylight: " + steps);
			if (steps.pairs > 0 && steps.share() > 0.002)
			{
				failures.append("skylight steps: ").append(steps).append('\n');
			}
			if (lamps != null && !lamps.isEmpty() && walls != null)
			{
				Checks.LampLeak leak = Checks.lampLeak(on, off, probe, lamps, walls, dump.manifest.plane, 6);
				ImageIO.write(leak.image, "png", out.resolve("lamp-leak.png").toFile());
				System.out.println("lamps: " + leak);
				if (leak.lit > Math.max(20, leak.behind / 100))
				{
					failures.append("lamp light behind walls: ").append(leak).append('\n');
				}
			}
		}
		assertTrue(failures.toString(), failures.length() == 0);
	}
}
