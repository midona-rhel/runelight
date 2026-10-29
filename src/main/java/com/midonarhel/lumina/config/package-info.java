/**
 * The settings. {@link com.midonarhel.lumina.config.LuminaConfig} is the RuneLite config: the GPU plugin's keys
 * plus 117 HD's keys with their names, descriptions and defaults (rs117/hd/HdPluginConfig.java), under the group
 * {@code lumina}. {@link com.midonarhel.lumina.config.HdSettings} caches the 117 HD settings as 117 HD's
 * HdPlugin.updateCachedConfigs does, and is the only place the ported code reads a setting from. The enums are
 * 117 HD's (src/main/java/rs117/hd/config/) except AntiAliasingMode, ColorBlindMode and UIScalingMode, which are
 * the GPU plugin's.
 */
package com.midonarhel.lumina.config;
