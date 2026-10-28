package com.midonarhel.lumina;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LuminaPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LuminaPlugin.class);
		RuneLite.main(args);
	}
}
