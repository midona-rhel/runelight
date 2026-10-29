package com.midonarhel.lumina.dev;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/**
 * Developer tools for comparing renderers frame by frame: put the camera at exact values, and save the next rendered
 * frame to a PNG with the conditions beside it. The same class (in its own package) is in the 117 HD reference build,
 * so both renderers can be captured identically.
 *
 * ::lumina cam yaw pitch zoom   (yaw 0-2047, pitch 128-512, zoom as the zoom slider's script value)
 * ::lumina capture name
 */
@Slf4j
final class FrameCapture
{
	static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("captures");

	private FrameCapture()
	{
	}

	/** Call on the client thread. */
	static void setCamera(Client client, int yaw, int pitch, int zoom)
	{
		client.setCameraYawTarget(yaw);
		client.setCameraPitchTarget(pitch);
		client.runScript(ScriptID.CAMERA_DO_ZOOM, zoom, zoom);
	}

	/** Save the next frame as DIR/name.png, with name.txt holding the camera and location. Call on the client thread. */
	static void capture(Client client, DrawManager drawManager, String renderer, String name)
	{
		var player = client.getLocalPlayer();
		String info = "renderer: " + renderer + "\n"
			+ "camera: yaw " + client.getCameraYaw() + ", pitch " + client.getCameraPitch() + ", scale " + client.getScale() + "\n"
			+ "location: " + (player == null ? "none" : player.getWorldLocation()) + "\n"
			+ "canvas: " + client.getCanvasWidth() + "x" + client.getCanvasHeight() + "\n";
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage copy = toBufferedImage(image);
			new Thread(() ->
			{
				try
				{
					Files.createDirectories(DIR);
					ImageIO.write(copy, "png", DIR.resolve(name + ".png").toFile());
					Files.write(DIR.resolve(name + ".txt"), info.getBytes(StandardCharsets.UTF_8));
					log.info("Captured {}", DIR.resolve(name + ".png"));
				}
				catch (IOException ex)
				{
					log.warn("Couldn't save capture {}", name, ex);
				}
			}, "Frame capture").start();
		});
	}

	private static BufferedImage toBufferedImage(Image image)
	{
		BufferedImage out = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_RGB);
		var g = out.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return out;
	}
}
