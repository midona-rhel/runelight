package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.common.LogLimiter;
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
import com.midonarhel.lumina.LuminaPlugin.*;
import static com.midonarhel.lumina.LuminaPlugin.*;

/**
 * The shader programs: the template with the build's defines and includes, compiling every program (scene, UI,
 * composite passes, AO, denoise, shadow, fog, sky, weather, water, upscale), locating their uniforms into the plugin's
 * fields, hot reload, and freeing them. Split out of the plugin class verbatim; it writes the plugin's program and
 * uniform fields through {@code plugin}.
 */
public final class Programs
{
	private final LuminaPlugin plugin;

	public Programs(LuminaPlugin plugin)
	{
		this.plugin = plugin;
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + plugin.config.uiScalingMode().ordinal() + "\n";
				case "cluster_config":
					return "#define CLUSTERS_X " + LightClusters.CLUSTERS_X + "\n"
						+ "#define CLUSTERS_Y " + LightClusters.CLUSTERS_Y + "\n"
						+ "#define CLUSTERS_Z " + LightClusters.CLUSTERS_Z + "\n"
						+ "#define CLUSTER_ENTRIES " + LightClusters.CLUSTER_ENTRIES + "\n"
						+ "#define CLUSTER_NEAR " + LightClusters.NEAR + "\n"
						+ "#define CLUSTER_FAR " + LightClusters.FAR + "\n"
						+ "#define ROOM_FILL_BASE " + LightClusters.ROOM_FILL_BASE + "\n"
						+ "#define LAMP_WALLS_BASE " + LightClusters.LAMP_WALLS_BASE + "\n"
						+ "#define LAMP_WALL_LAYER_TEXELS " + LampWalls.LAYER_TEXELS + "\n"
						+ com.midonarhel.lumina.light.LightMapBuffer.defines();
				case "composite_mode":
					// Compile out the diagnostic branches and intermediate samplers in normal rendering.
					// Changing this diagnostic property requires a shader reload.
					return Boolean.parseBoolean(System.getProperty("runelight.compositeCombined", "true"))
						? "const int compositePart = 0;\n" : "uniform int compositePart;\n";
				case "msaa_config":
					// the scene targets' samples (scene_samplers.glsl): the programs are compiled for them
					return "#define MSAA_SAMPLES " + plugin.shaderSamples + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + plugin.config.colorBlindMode().ordinal() + "\n";
				case "debug_view":
					// the composite's debug branches compile out; changing the view reloads the shaders
					return "const int debugView = " + plugin.config.debugView().ordinal() + ";\n";
			}
			return null;
		});
		ShaderWatcher watcher = plugin.shaderWatcher;
		if (watcher != null)
		{
			template.addDirectory(watcher.getDirectory());
		}
		template.addInclude(LuminaPlugin.class);
		return template;
	}

	/** One of the composite's programs (composite_frag.glsl's COMPOSITE_PASS). */
	private int compileComposite(int pass) throws ShaderException
	{
		return COMPOSITE_PROGRAM.compile(createTemplate()
			.add(key -> key.equals("composite_pass") ? "#define COMPOSITE_PASS " + pass + "\n" : null));
	}

	public void initProgram() throws ShaderException
	{
		plugin.shaderSamples = plugin.sceneSamples();
		// macOS core profile has no default VAO, so the shaders won't validate unless a VAO is bound
		glBindVertexArray(plugin.vaoUiHandle);

		Template template = createTemplate();
		plugin.glProgram = PROGRAM.compile(template);
		plugin.glUiProgram = UI_PROGRAM.compile(template);
		for (Composite.CompositeProgram c : plugin.composite.composites)
		{
			c.program = compileComposite(c.pass);
		}
		plugin.glAoProgram = AO_PROGRAM.compile(template);
		plugin.glDenoiseProgram = DENOISE_PROGRAM.compile(template);
		plugin.glShadowProgram = SHADOW_PROGRAM.compile(template);
		plugin.glShadowCopyProgram = SHADOW_COPY_PROGRAM.compile(template);
		plugin.glShadowMaxProgram = SHADOW_MAX_PROGRAM.compile(template);
		plugin.setFogProgram(VolumetricFog.PROGRAM.compile(template));
		plugin.water.setSurfaceProgram(WaterSurface.PROGRAM.compile(template), COMPOSITE_TEXTURE_UNIT + 2, WATER_DATA_UNIT,
			WATER_NOISE_UNIT, WATER_CONTACT_UNIT, WATER_WAVES_UNIT_A, WATER_WAVES_UNIT_B);
		plugin.weather.setPrograms(plugin.weather.compile(template), COMPOSITE_TEXTURE_UNIT, MATERIAL_UNIT, WEATHER_OCCLUSION_UNIT, SHADOW_COMPARE_UNIT, ALPHA_LAYER_UNIT,
			LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, WATER_DATA_UNIT, WEATHER_FIELD_UNIT);
		plugin.skyCache.setPrograms(plugin.skyCache.compile(template), CLOUD_NOISE_UNIT, WEATHER_SKY_UNIT, SKY_CACHE_UNIT);
		plugin.upscaler.setProgram(Upscaler.PROGRAM.compile(template));

		glBindVertexArray(0);

		initUniforms();
	}

	public void initUniforms()
	{
		plugin.uniWorldProj = glGetUniformLocation(plugin.glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(plugin.glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(plugin.glProgram, "entityTint");
		plugin.uniSmoothBanding = glGetUniformLocation(plugin.glProgram, "smoothBanding");
		plugin.uniBrightness = glGetUniformLocation(plugin.glProgram, "brightness");
		plugin.uniFogColor = glGetUniformLocation(plugin.glProgram, "fogColor");
		plugin.uniDrawDistance = glGetUniformLocation(plugin.glProgram, "drawDistance");
		plugin.sceneEdgeUniforms.locate(plugin.glProgram);
		plugin.uniExpandedMapLoadingChunks = glGetUniformLocation(plugin.glProgram, "expandedMapLoadingChunks");
		plugin.uniTextureLightMode = glGetUniformLocation(plugin.glProgram, "textureLightMode");
		plugin.uniTick = glGetUniformLocation(plugin.glProgram, "tick");
		// program state, kept until the program is linked again
		glUniformBlockBinding(plugin.glProgram, glGetUniformBlockIndex(plugin.glProgram, "uniforms"), 0);
		glUseProgram(plugin.glProgram);
		glUniform1i(glGetUniformLocation(plugin.glProgram, "textures"), 1); // texture sampler array is bound to texture1
		glUseProgram(0);
		plugin.uniTextureAnimations = glGetUniformLocation(plugin.glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(plugin.glProgram, "base");
		plugin.uniColorblindIntensity = glGetUniformLocation(plugin.glProgram, "colorblindIntensity");

		plugin.uniTexTargetDimensions = glGetUniformLocation(plugin.glUiProgram, "targetDimensions");
		plugin.uniTexSourceDimensions = glGetUniformLocation(plugin.glUiProgram, "sourceDimensions");
		plugin.uniUiAlphaOverlay = glGetUniformLocation(plugin.glUiProgram, "alphaOverlay");
		plugin.uniUiColorblindIntensity = glGetUniformLocation(plugin.glUiProgram, "colorblindIntensity");
		glUseProgram(plugin.glUiProgram);
		glUniform1i(glGetUniformLocation(plugin.glUiProgram, "tex"), 0);
		glUseProgram(0);

		plugin.uniAlphaPass = glGetUniformLocation(plugin.glProgram, "alphaPass");
		plugin.uniAlphaShadows = glGetUniformLocation(plugin.glProgram, "alphaShadows");
		plugin.uniAlphaLightProj = glGetUniformLocation(plugin.glProgram, "alphaLightProj");
		plugin.uniAlphaShadowTexel = glGetUniformLocation(plugin.glProgram, "alphaShadowTexel");
		plugin.uniAlphaShadowDepthRange = glGetUniformLocation(plugin.glProgram, "alphaShadowDepthRange");
		plugin.uniAlphaDepthZeroToOne = glGetUniformLocation(plugin.glProgram, "alphaDepthZeroToOne");
		plugin.uniSceneOutdoors = glGetUniformLocation(plugin.glProgram, "outdoors");
		plugin.uniScenePointLights = glGetUniformLocation(plugin.glProgram, "pointLightsEnabled");
		plugin.sceneLampWalls.locate(plugin.glProgram);
		glUseProgram(plugin.glProgram);
		glUniform1i(glGetUniformLocation(plugin.glProgram, "lightData"), LIGHT_DATA_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glProgram, "clusterData"), CLUSTER_DATA_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glProgram, "alphaShadowCompare"), SHADOW_COMPARE_UNIT);
		plugin.uniSceneProceduralSky = glGetUniformLocation(plugin.glProgram, "proceduralSky");
		plugin.uniSceneCasterDir = glGetUniformLocation(plugin.glProgram, "casterDir");
		plugin.uniSceneSunIsCaster = glGetUniformLocation(plugin.glProgram, "sunIsCaster");
		plugin.uniSceneStrikeLight = glGetUniformLocation(plugin.glProgram, "strikeLight");
		plugin.uniSceneSunAltitude = glGetUniformLocation(plugin.glProgram, "sunAltitude");
		plugin.uniSceneOvercast = glGetUniformLocation(plugin.glProgram, "weatherOvercast");
		plugin.uniSceneNoiseOrigin = glGetUniformLocation(plugin.glProgram, "weatherNoiseOrigin");
		plugin.uniSceneWeatherLight = glGetUniformLocation(plugin.glProgram, "weatherLight");
		plugin.sceneWind = Wind.locate(plugin.glProgram);
		plugin.shadowWind = Wind.locate(plugin.glShadowProgram);
		plugin.sceneEnvUniforms.locate(plugin.glProgram);
		for (Composite.CompositeProgram c : plugin.composite.composites)
		{
			plugin.composite.locate(c);
		}

		plugin.uniDenoiseFog = glGetUniformLocation(plugin.glDenoiseProgram, "denoiseFog");
		glUseProgram(plugin.glDenoiseProgram);
		glUniform1i(glGetUniformLocation(plugin.glDenoiseProgram, "sceneAo"), AO_TEXTURE_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glDenoiseProgram, "sceneShadow"), SCENE_SHADOW_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glDenoiseProgram, "sceneFog"), FOG_TEXTURE_UNIT);
		glUseProgram(0);
		plugin.uniAoRadius = glGetUniformLocation(plugin.glAoProgram, "aoRadius");
		plugin.uniAoSamples = glGetUniformLocation(plugin.glAoProgram, "aoSamples");
		plugin.uniAoShadowsEnabled = glGetUniformLocation(plugin.glAoProgram, "shadowsEnabled");
		plugin.uniAoLightProjRel = glGetUniformLocation(plugin.glAoProgram, "lightProjRel");
		plugin.uniAoShadowRadius = glGetUniformLocation(plugin.glAoProgram, "shadowRadius");
		plugin.uniAoShadowDepthRange = glGetUniformLocation(plugin.glAoProgram, "shadowDepthRange");
		plugin.uniAoShadowMapSize = glGetUniformLocation(plugin.glAoProgram, "shadowMapSize");
		plugin.uniAoShadowMaxTile = glGetUniformLocation(plugin.glAoProgram, "shadowMaxTile");
		plugin.uniAoCasterDir = glGetUniformLocation(plugin.glAoProgram, "casterDir");
		plugin.uniAoSunIsCaster = glGetUniformLocation(plugin.glAoProgram, "sunIsCaster");
		plugin.uniAoStrikeLight = glGetUniformLocation(plugin.glAoProgram, "strikeLight");
		glUseProgram(plugin.glAoProgram);
		glUniform1i(glGetUniformLocation(plugin.glAoProgram, "shadowRaw"), SHADOW_RAW_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glAoProgram, "shadowCompare"), SHADOW_COMPARE_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glAoProgram, "shadowMax"), SHADOW_MAX_UNIT);
		glUniform1i(glGetUniformLocation(plugin.glAoProgram, "sceneMaterial"), MATERIAL_UNIT);
		glUseProgram(0);
		plugin.aoFrame.locate(plugin.glAoProgram);
		plugin.denoiseFrame.locate(plugin.glDenoiseProgram);
		bindSceneSamplers(plugin.glAoProgram);
		plugin.weatherGround.locate(plugin.glAoProgram, WEATHER_OCCLUSION_UNIT, WEATHER_FIELD_UNIT);
		plugin.skyGrid.locateAo(plugin.glAoProgram, SKY_GRID_UNIT);
		plugin.skyField.locateAo(plugin.glAoProgram, SKY_FIELD_UNIT);
		plugin.water.locateAo(plugin.glAoProgram, WATER_DATA_UNIT);
		// the shadow samplers' units hold an empty depth map until their maps are bound
		ShadowMap.unbind(SHADOW_RAW_UNIT, SHADOW_COMPARE_UNIT);
		ShadowMap.unbind(WEATHER_OCCLUSION_UNIT, WEATHER_OCCLUSION_UNIT + 1);

		plugin.sceneMaterials.locate(plugin.glProgram);
		plugin.sceneSway.locate(plugin.glProgram);

		plugin.uniShadowLightProj = glGetUniformLocation(plugin.glShadowProgram, "lightProj");
		plugin.uniShadowEntityProj = glGetUniformLocation(plugin.glShadowProgram, "entityProj");
		plugin.uniShadowBase = glGetUniformLocation(plugin.glShadowProgram, "base");
		plugin.uniShadowTexturesReady = glGetUniformLocation(plugin.glShadowProgram, "texturesReady");
		plugin.uniShadowEmissiveCasts = glGetUniformLocation(plugin.glShadowProgram, "emissiveCasts");
		plugin.shadowSway.locate(plugin.glShadowProgram);
		glUseProgram(plugin.glShadowProgram);
		glUniform1i(glGetUniformLocation(plugin.glShadowProgram, "textures"), 1); // game texture array lives on unit 1
		glUseProgram(plugin.glShadowCopyProgram);
		glUniform1i(glGetUniformLocation(plugin.glShadowCopyProgram, "staticDepth"), 0);
		glUseProgram(plugin.glShadowMaxProgram);
		glUniform1i(glGetUniformLocation(plugin.glShadowMaxProgram, "source"), 0);
		glUseProgram(0);
	}

	public void shutdownProgram()
	{
		glDeleteProgram(plugin.glProgram);
		plugin.glProgram = 0;

		glDeleteProgram(plugin.glUiProgram);
		plugin.glUiProgram = 0;

		for (Composite.CompositeProgram c : plugin.composite.composites)
		{
			glDeleteProgram(c.program);
			c.program = 0;
		}

		glDeleteProgram(plugin.glAoProgram);
		plugin.glAoProgram = 0;

		glDeleteProgram(plugin.glDenoiseProgram);
		plugin.glDenoiseProgram = 0;

		glDeleteProgram(plugin.glShadowProgram);
		plugin.glShadowProgram = 0;

		glDeleteProgram(plugin.glShadowCopyProgram);
		plugin.glShadowCopyProgram = 0;

		glDeleteProgram(plugin.glShadowMaxProgram);
		plugin.glShadowMaxProgram = 0;

		plugin.weather.freePrograms();
		plugin.water.freeSurfaceProgram();
		plugin.skyCache.freePrograms();
		plugin.upscaler.freeProgram();
	}

	/**
	 * Recompile every program. On failure the previous programs stay in use, so a typo while editing
	 * a shader shows an error instead of taking down the renderer.
	 */
	public boolean reloadShaders()
	{
		glBindVertexArray(plugin.vaoUiHandle);
		Template template = createTemplate();
		int scene = 0, ui = 0, ao = 0, denoise = 0, shadow = 0, shadowCopy = 0, shadowMax = 0, fog = 0, waterSurface = 0, upscale = 0;
		int[] programs = new int[plugin.composite.composites.length];
		Weather.Programs weatherPrograms;
		SkyCache.Programs skyCachePrograms;
		try
		{
			scene = PROGRAM.compile(template);
			ui = UI_PROGRAM.compile(template);
			for (int i = 0; i < plugin.composite.composites.length; ++i)
			{
				programs[i] = compileComposite(plugin.composite.composites[i].pass);
			}
			ao = AO_PROGRAM.compile(template);
			denoise = DENOISE_PROGRAM.compile(template);
			shadow = SHADOW_PROGRAM.compile(template);
			shadowCopy = SHADOW_COPY_PROGRAM.compile(template);
			shadowMax = SHADOW_MAX_PROGRAM.compile(template);
			fog = VolumetricFog.PROGRAM.compile(template);
			waterSurface = WaterSurface.PROGRAM.compile(template);
			upscale = Upscaler.PROGRAM.compile(template);
			weatherPrograms = plugin.weather.compile(template);
			try
			{
				skyCachePrograms = plugin.skyCache.compile(template);
			}
			catch (ShaderException ex)
			{
				glDeleteProgram(weatherPrograms.rain);
				glDeleteProgram(weatherPrograms.snow);
				throw ex;
			}
		}
		catch (ShaderException ex)
		{
			// glDeleteProgram ignores 0
			glDeleteProgram(scene);
			glDeleteProgram(ui);
			for (int program : programs)
			{
				glDeleteProgram(program);
			}
			glDeleteProgram(ao);
			glDeleteProgram(denoise);
			glDeleteProgram(shadow);
			glDeleteProgram(shadowCopy);
			glDeleteProgram(shadowMax);
			glDeleteProgram(fog);
			glDeleteProgram(waterSurface);
			glDeleteProgram(upscale);
			String msg = ex.getMessage();
			if (!msg.equals(plugin.shaderError))
			{
				plugin.shaderError = msg;
				log.error("Shader reload failed:\n{}", msg);
				plugin.chat("Shader error: " + firstLines(msg, 3));
			}
			return false;
		}
		finally
		{
			glBindVertexArray(0);
		}

		shutdownProgram();
		plugin.glProgram = scene;
		plugin.glUiProgram = ui;
		for (int i = 0; i < plugin.composite.composites.length; ++i)
		{
			plugin.composite.composites[i].program = programs[i];
		}
		plugin.glAoProgram = ao;
		plugin.glDenoiseProgram = denoise;
		plugin.glShadowProgram = shadow;
		plugin.glShadowCopyProgram = shadowCopy;
		plugin.glShadowMaxProgram = shadowMax;
		plugin.setFogProgram(fog);
		plugin.water.setSurfaceProgram(waterSurface, COMPOSITE_TEXTURE_UNIT + 2, WATER_DATA_UNIT, WATER_NOISE_UNIT,
			WATER_CONTACT_UNIT, WATER_WAVES_UNIT_A, WATER_WAVES_UNIT_B);
		plugin.weather.setPrograms(weatherPrograms, COMPOSITE_TEXTURE_UNIT, MATERIAL_UNIT, WEATHER_OCCLUSION_UNIT, SHADOW_COMPARE_UNIT, ALPHA_LAYER_UNIT,
			LIGHT_DATA_UNIT, CLUSTER_DATA_UNIT, WATER_DATA_UNIT, WEATHER_FIELD_UNIT);
		plugin.skyCache.setPrograms(skyCachePrograms, CLOUD_NOISE_UNIT, WEATHER_SKY_UNIT, SKY_CACHE_UNIT);
		plugin.upscaler.setProgram(upscale);
		initUniforms();

		if (plugin.textureAnimations != null)
		{
			glUseProgram(plugin.glProgram);
			glUniform2fv(plugin.uniTextureAnimations, plugin.textureAnimations);
			glUseProgram(0);
		}

		if (plugin.shaderError != null)
		{
			plugin.chat("Shaders OK again.");
		}
		plugin.shaderError = null;
		log.info("Shaders reloaded");
		return true;
	}

	private static String firstLines(String s, int n)
	{
		String[] lines = s.split("\n");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(n, lines.length); ++i)
		{
			if (i > 0)
			{
				sb.append(" | ");
			}
			sb.append(lines[i].trim());
		}
		return sb.toString();
	}
}
