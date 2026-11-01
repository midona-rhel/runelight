package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaConfig;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.scene.ModelFake;
import java.awt.Dimension;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Preferences;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigItem;

/**
 * The client as a dump saw it, faked (Fakes): the client, its world views and scenes, the player, the textures, the
 * interface and the settings, answering from the manifest and the scene's recorded arrays. The camera, the hour and any
 * setting can be overridden (the offline renderer's options).
 */
public final class DumpWorld
{
	public final SceneDump.Loaded dump;
	public final SceneDump.Manifest m;
	public final Map<String, String> settings = new HashMap<>();
	public final Map<Integer, WorldView> worldViews = new HashMap<>();
	public final Map<Integer, Scene> scenes = new HashMap<>();
	public Client client;
	public LuminaConfig config;
	// the camera the client reports; overridable
	public int cameraYaw, cameraPitch, scale;
	private final Map<Integer, int[]> sceneOverrides = new HashMap<>();
	private final Map<Integer, Integer> drawDistance = new HashMap<>();
	private double brightness;

	public DumpWorld(SceneDump.Loaded dump)
	{
		this.dump = dump;
		this.m = dump.manifest;
		settings.putAll(m.settings);
		cameraYaw = m.cameraYaw;
		cameraPitch = m.cameraPitch;
		scale = m.scale;
		brightness = dump.textureBrightness;
		for (var e : dump.tape.events)
		{
			if (e.type == com.midonarhel.lumina.dev.FrameTape.PRE)
			{
				sceneOverrides.put(e.worldView, new int[]{e.ints[3], e.ints[4], e.ints[5], e.ints[6]});
			}
		}
		config = config();
		for (int[] view : dump.views)
		{
			int id = view[0];
			String suffix = id == WorldView.TOPLEVEL ? "" : String.valueOf(id);
			Scene scene = scene(id, "scene" + suffix + ".");
			scenes.put(id, scene);
			worldViews.put(id, worldView(id, scene, "wv" + suffix + "."));
		}
		client = client();
	}

	private Object derived(String key)
	{
		return dump.derived.get(key);
	}

	/** The recorded no-argument getters under prefix, as answers. */
	private Map<String, Object> getters(String prefix)
	{
		Map<String, Object> a = new HashMap<>();
		for (var e : dump.derived.entrySet())
		{
			if (e.getKey().startsWith(prefix) && e.getKey().indexOf('.', prefix.length()) < 0)
			{
				a.put(e.getKey().substring(prefix.length()), e.getValue());
			}
		}
		return a;
	}

	private Scene scene(int id, String prefix)
	{
		Map<String, Object> a = getters(prefix);
		int[] o = sceneOverrides.getOrDefault(id, new int[4]);
		a.put("getWorldViewId", id);
		a.put("getOverrideHue", o[0]);
		a.put("getOverrideSaturation", o[1]);
		a.put("getOverrideLuminance", o[2]);
		a.put("getOverrideAmount", o[3]);
		Object dd = a.get("getDrawDistance");
		drawDistance.put(id, dd instanceof Integer ? (Integer) dd : 25);
		a.put("getDrawDistance", (Fakes.Answer) args -> drawDistance.get(id));
		a.put("setDrawDistance", (Fakes.Answer) args ->
		{
			drawDistance.put(id, (Integer) args[0]);
			return null;
		});
		a.put("getSkybox", id == WorldView.TOPLEVEL && m.skyboxModel >= 0 ? ModelFake.of(dump.tape.models.get(m.skyboxModel)) : null);
		return Fakes.of(Scene.class, a);
	}

	private WorldView worldView(int id, Scene scene, String prefix)
	{
		Map<String, Object> a = getters(prefix);
		a.put("getId", id);
		a.put("getScene", scene);
		a.put("isTopLevel", id == WorldView.TOPLEVEL);
		if (id == WorldView.TOPLEVEL)
		{
			a.put("getPlane", m.plane);
			a.put("getBaseX", m.baseX);
			a.put("getBaseY", m.baseY);
		}
		return Fakes.of(WorldView.class, a);
	}

	private Client client()
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getCameraYaw", (Fakes.Answer) args -> cameraYaw);
		a.put("getCameraPitch", (Fakes.Answer) args -> cameraPitch);
		a.put("getScale", (Fakes.Answer) args -> scale);
		a.put("getCameraFocalPointX", m.focalX);
		a.put("getCameraFocalPointY", m.focalY);
		a.put("getCameraFocalPointZ", m.focalZ);
		a.put("getCanvasWidth", m.canvasWidth);
		a.put("getCanvasHeight", m.canvasHeight);
		a.put("getViewportXOffset", m.viewportX);
		a.put("getViewportYOffset", m.viewportY);
		a.put("getViewportWidth", m.viewportWidth);
		a.put("getViewportHeight", m.viewportHeight);
		a.put("isStretchedEnabled", m.stretched);
		a.put("getStretchedDimensions", new Dimension(m.stretchedWidth, m.stretchedHeight));
		a.put("getSkyboxColor", m.skyboxColor);
		a.put("getExpandedMapLoading", m.expandedMapLoading);
		a.put("getGameCycle", m.gameCycle);
		a.put("getGameState", GameState.valueOf(m.gameState));
		a.put("isClientThread", true);
		a.put("getTopLevelWorldView", worldViews.get(WorldView.TOPLEVEL));
		a.put("getWorldView", (Fakes.Answer) args -> worldViews.get((Integer) args[0]));
		a.put("getLocalPlayer", player());
		a.put("getTextureProvider", textures());
		a.put("getBufferProvider", Fakes.of(BufferProvider.class, Map.of(
			"getPixels", dump.uiPixels, "getWidth", dump.uiWidth, "getHeight", dump.uiHeight)));
		a.put("getPreferences", Fakes.of(Preferences.class, Map.of("getSoundEffectVolume", m.soundEffectVolume)));
		return Fakes.of(Client.class, a);
	}

	private Player player()
	{
		if (m.playerLocal == null)
		{
			return null;
		}
		WorldView wv = worldViews.get(m.playerLocal[2]);
		LocalPoint lp = new LocalPoint(m.playerLocal[0], m.playerLocal[1], wv != null ? wv : worldViews.get(WorldView.TOPLEVEL));
		WorldPoint wp = new WorldPoint(m.playerWorld[0], m.playerWorld[1], m.playerWorld[2]);
		return Fakes.of(Player.class, Map.of("getLocalLocation", lp, "getWorldLocation", wp, "getWorldView", wv));
	}

	private TextureProvider textures()
	{
		int n = dump.texturePixels.length;
		Texture[] textures = new Texture[n];
		for (int i = 0; i < n; ++i)
		{
			if (dump.texturePixels[i] != null)
			{
				textures[i] = Fakes.of(Texture.class, Map.of("getAnimationDirection", dump.textureDirection[i],
					"getAnimationSpeed", dump.textureSpeed[i], "isLoaded", true, "getPixels", dump.texturePixels[i]));
			}
		}
		Map<String, Object> a = new HashMap<>();
		a.put("getTextures", n > 0 ? textures : null);
		// the pixels are the dump's at brightness 1, as the texture array is made; the shader applies the brightness
		a.put("load", (Fakes.Answer) args -> dump.texturePixels[(Integer) args[0]]);
		a.put("getBrightness", (Fakes.Answer) args -> brightness);
		a.put("setBrightness", (Fakes.Answer) args ->
		{
			brightness = (Double) args[0];
			return null;
		});
		return Fakes.of(TextureProvider.class, a);
	}

	/** The settings as the dump had them, with the overrides: each by its key, parsed to the setting's type. */
	private LuminaConfig config()
	{
		Map<String, Object> a = new HashMap<>();
		for (Method method : LuminaConfig.class.getMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item == null || method.getParameterCount() != 0)
			{
				continue;
			}
			String key = item.keyName();
			a.put(method.getName(), (Fakes.Answer) args ->
			{
				String v = settings.get(key);
				if (v == null)
				{
					try
					{
						return java.lang.invoke.MethodHandles.privateLookupIn(LuminaConfig.class, java.lang.invoke.MethodHandles.lookup())
							.unreflectSpecial(method, LuminaConfig.class).bindTo(config).invoke();
					}
					catch (Throwable ex)
					{
						throw new IllegalStateException(key, ex);
					}
				}
				return parse(v, method.getReturnType());
			});
		}
		return Fakes.of(LuminaConfig.class, a);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static Object parse(String v, Class<?> type)
	{
		if (type == boolean.class)
		{
			return Boolean.parseBoolean(v);
		}
		if (type == int.class)
		{
			return Integer.parseInt(v);
		}
		if (type == double.class)
		{
			return Double.parseDouble(v);
		}
		if (type == float.class)
		{
			return Float.parseFloat(v);
		}
		if (type == long.class)
		{
			return Long.parseLong(v);
		}
		if (type.isEnum())
		{
			return Enum.valueOf((Class<Enum>) type, v);
		}
		return v;
	}
}
