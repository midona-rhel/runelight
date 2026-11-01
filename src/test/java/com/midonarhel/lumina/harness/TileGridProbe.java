package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;

/**
 * Where each pixel of a dump's frame lies on the tile grid, for marking tiles on renders of the same camera: renders
 * the dump named by -Dlumina.dump with the camera of -Dlumina.camera=yaw,pitch,scale (the dump's without), anti-aliasing
 * off for FrameProbe, and writes to -Dlumina.out (default the dump's offline-grid/): skylight.png, and tiles.bin.gz,
 * per window pixel (row by row) the world x and z in tiles (float, NaN for the sky), after a header of width, height,
 * base x, base y, and the walls' bits on the dump's plane per local tile (size, then bytes [x * size + y]).
 */
public class TileGridProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
		Path out = System.getProperty("lumina.out") != null ? Path.of(System.getProperty("lumina.out")) : dump.dir.resolve("offline-grid");
		Files.createDirectories(out);
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.settings.put("antiAliasingMode", "DISABLED");
		o.settings.put("debugView", "SKYLIGHT");
		String camera = System.getProperty("lumina.camera");
		if (camera != null)
		{
			String[] c = camera.split(",");
			o.cameraYaw = Integer.parseInt(c[0]);
			o.cameraPitch = Integer.parseInt(c[1]);
			o.scale = Integer.parseInt(c[2]);
		}
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			BufferedImage sky = r.render();
			ImageIO.write(sky, "png", out.resolve("skylight.png").toFile());
			FrameProbe probe = new FrameProbe(r.plugin, r.width, r.height);
			WallMap walls = (WallMap) dump.derived.get("buildings.walls");
			int plane = dump.manifest.plane;
			try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(out.resolve("tiles.bin.gz"))))))
			{
				d.writeInt(r.width);
				d.writeInt(r.height);
				d.writeInt(dump.manifest.baseX);
				d.writeInt(dump.manifest.baseY);
				int size = walls.size - 2 * walls.offset;
				d.writeInt(size);
				for (int x = 0; x < size; ++x)
					for (int y = 0; y < size; ++y)
						d.writeByte(walls.get(plane, x + walls.offset, y + walls.offset));
				for (int y = 0; y < r.height; ++y)
					for (int x = 0; x < r.width; ++x)
					{
						float[] w = probe.world(x, y);
						d.writeFloat(w == null ? Float.NaN : w[0] / 128);
						d.writeFloat(w == null ? Float.NaN : w[2] / 128);
					}
			}
		}
	}
}
