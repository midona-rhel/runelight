package com.midonarhel.runelight.dev;

import com.midonarhel.runelight.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Developer commands from a file, so a tool outside the client can drive it without typing in the game: each line
 * of the file is run as if typed as "::<command> <line>", then the file is deleted. Polled once per game tick in
 * developer mode only.
 */
public final class CommandFile
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CommandFile.class);

	private final Path file;
	private final Consumer<String[]> run;

	public CommandFile(Path file, Consumer<String[]> run)
	{
		this.file = file;
		this.run = run;
	}

	/** Call on the client thread. */
	public void poll()
	{
		if (!Files.exists(file))
		{
			return;
		}
		List<String> lines;
		try
		{
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			Files.delete(file);
		}
		catch (IOException ex)
		{
			log.warn("Couldn't read command file {}", file, ex);
			return;
		}
		for (String line : lines)
		{
			line = line.trim();
			if (line.isEmpty())
			{
				continue;
			}
			log.info("Command file: {}", line);
			try
			{
				run.accept(line.split("\\s+"));
			}
			catch (RuntimeException ex)
			{
				log.warn("Command failed: {}", line, ex);
			}
		}
	}
}
