package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.common.LogLimiter;
import com.midonarhel.lumina.light.LampFrame;
import com.midonarhel.lumina.visibility.VisibilityFrame;
import com.midonarhel.lumina.sky.SkyFrame;
import com.midonarhel.lumina.weather.WeatherFrame;
import com.midonarhel.lumina.common.Mat4;
import com.midonarhel.lumina.common.Rumble;
import com.midonarhel.lumina.common.Shader;
import com.midonarhel.lumina.common.ShaderException;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.OpenRooms;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.RoomTexture;
import com.midonarhel.lumina.visibility.SkyFieldMap;
import com.midonarhel.lumina.visibility.SkyGridMap;
import com.midonarhel.lumina.visibility.VoxelBuilder;
import com.midonarhel.lumina.fog.VolumetricFog;
import com.midonarhel.lumina.water.Water;
import com.midonarhel.lumina.water.WaterOverrides;
import com.midonarhel.lumina.water.WaterSurface;
import com.midonarhel.lumina.scene.AreaHiding;
import com.midonarhel.lumina.scene.EdgeFade;
import com.midonarhel.lumina.scene.GLBuffer;
import com.midonarhel.lumina.scene.GpuFloatBuffer;
import com.midonarhel.lumina.scene.GroundBlender;
import com.midonarhel.lumina.scene.Interactables;
import com.midonarhel.lumina.scene.ModelUploader;
import com.midonarhel.lumina.scene.RegionManager;
import com.midonarhel.lumina.scene.SceneJobs;
import com.midonarhel.lumina.scene.SceneLoadReport;
import com.midonarhel.lumina.scene.SceneLoader;
import com.midonarhel.lumina.scene.SceneUploader;
import com.midonarhel.lumina.scene.SurfaceMaterials;
import com.midonarhel.lumina.scene.TempModels;
import com.midonarhel.lumina.scene.TextureManager;
import com.midonarhel.lumina.scene.VAO;
import com.midonarhel.lumina.scene.VAOList;
import com.midonarhel.lumina.scene.VertexShading;
import com.midonarhel.lumina.scene.WindSway;
import com.midonarhel.lumina.scene.Zone;
import com.midonarhel.lumina.scene.ZonePool;
import com.midonarhel.lumina.shadow.ShadowMap;
import com.midonarhel.lumina.shadow.ShadowPass;
import com.midonarhel.lumina.weather.Climate;
import com.midonarhel.lumina.weather.WadeSteps;
import com.midonarhel.lumina.weather.Weather;
import com.midonarhel.lumina.weather.WeatherAudio;
import com.midonarhel.lumina.weather.WeatherData;
import com.midonarhel.lumina.weather.WeatherField;
import com.midonarhel.lumina.weather.WeatherGround;
import com.midonarhel.lumina.weather.Wind;
import com.midonarhel.lumina.sky.Clouds;
import com.midonarhel.lumina.sky.DayCycle;
import com.midonarhel.lumina.sky.EnvironmentManager;
import com.midonarhel.lumina.sky.EnvironmentUniforms;
import com.midonarhel.lumina.sky.GameClock;
import com.midonarhel.lumina.sky.Lightning;
import com.midonarhel.lumina.sky.NightSky;
import com.midonarhel.lumina.sky.SkyCache;
import com.midonarhel.lumina.dev.BuildingOverlay;
import com.midonarhel.lumina.dev.CommandFile;
import com.midonarhel.lumina.dev.Commands;
import com.midonarhel.lumina.dev.CpuTimer;
import com.midonarhel.lumina.dev.FrameCapture;
import com.midonarhel.lumina.dev.FrameSnapshot;
import com.midonarhel.lumina.dev.HitchMonitor;
import com.midonarhel.lumina.dev.LookMonitor;
import com.midonarhel.lumina.dev.ShaderWatcher;
import com.midonarhel.lumina.light.LampWalls;
import com.midonarhel.lumina.light.LightClusters;
import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.light.RoomLight;
import com.midonarhel.lumina.light.SceneLights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.zip.GZIPOutputStream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import net.runelite.api.BufferProvider;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Preferences;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.DebugView;
import com.midonarhel.lumina.config.UIScalingMode;
import com.midonarhel.lumina.config.WeatherMode;
import com.midonarhel.lumina.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The frame's GL objects: the vertex arrays, the mapped buffers and the render-target FBO, created at start-up and on a size or sample change, freed at shutdown. Split out of the plugin class verbatim; it reads the plugin's frame state through {@code plugin}.
 */
public final class GlObjects
{
	private final LuminaPlugin plugin;

	public GlObjects(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	public void initVao()
	{
		// Create UI VAO
		plugin.vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		plugin.vboUiHandle = glGenBuffers();
		glBindVertexArray(plugin.vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, plugin.vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	public void shutdownVao()
	{
		glDeleteBuffers(plugin.vboUiHandle);
		plugin.vboUiHandle = 0;

		glDeleteVertexArrays(plugin.vaoUiHandle);
		plugin.vaoUiHandle = 0;
	}

	public void initBuffers()
	{
		plugin.uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		plugin.initGlBuffer(plugin.glUniformBuffer);
		Zone.initBuffer();

		for (int i = 0; i < plugin.rts.length; ++i)
		{
			plugin.rts[i].vaoO = new VAOList(i > 0);
			plugin.rts[i].vaoA = new VAOList(i > 0);
		}
	}

	public void shutdownBuffers()
	{
		destroyGlBuffer(plugin.glUniformBuffer);
		plugin.uniformBuffer = null;
		Zone.freeBuffer();

		for (int i = 0; i < plugin.rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (plugin.rts[i].vaoO != null)
			{
				plugin.rts[i].vaoO.free();
				plugin.rts[i].vaoO = null;
			}
			if (plugin.rts[i].vaoA != null)
			{
				plugin.rts[i].vaoA.free();
				plugin.rts[i].vaoA = null;
			}
		}
	}

	public void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	public void initFbo(int width, int height, int aaSamples, double renderFactor)
	{
		final AffineTransform transform = plugin.dpiTransform();

		width = Math.max(1, plugin.getScaledValue(transform.getScaleX() * renderFactor, width));
		height = Math.max(1, plugin.getScaledValue(transform.getScaleY() * renderFactor, height));

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		plugin.sceneTargets.init(width, height, aaSamples, (float) Math.max(1, 2 * renderFactor));
		if (renderFactor != 1)
		{
			plugin.upscaler.init(width, height);
		}

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, plugin.windowFramebuffer());
	}
}
