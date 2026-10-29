/**
 * Developer tools, used only in developer mode ({@code --developer-mode}; the plugin gates every entry point).
 * {@link com.midonarhel.lumina.dev.LuminaCommands} is the ::lumina command: camera placement and frame capture
 * ({@link com.midonarhel.lumina.dev.FrameCapture}), the lighting dump, the shader debug views and frame-time
 * snapshots ({@link com.midonarhel.lumina.dev.Snapshot}, fed by {@code frame.FrameTiming}).
 * {@link com.midonarhel.lumina.dev.CommandFile} runs the same commands from .runelite/lumina/command.txt, so a tool
 * outside the client can drive it.
 */
package com.midonarhel.lumina.dev;
