/**
 * The weather: which climate a world tile is in and what the weather there is at a moment of the game clock. Nothing
 * here touches the render path yet; the hook into the sky (a {@code sky.SkyModifiers} and a fog rule per frame) is
 * the next segment.
 * <ul>
 * <li>{@link com.midonarhel.lumina.weather.Pattern}: a weather pattern ({@code patterns.json}): its sky modifiers,
 * fog rule, precipitation, wind, ground effect and persistence</li>
 * <li>{@link com.midonarhel.lumina.weather.Climate}: a climate ({@code climates.json}): a real-world analogue with
 * a temperature and the share of each pattern; the snow line turns rain to snow and back</li>
 * <li>{@link com.midonarhel.lumina.weather.ClimateMap}: world tile to climate id ({@code climate_regions.json}, a
 * layer stack of 117 HD areas and tile rects over the overworld); underground, decided by 117 HD's environment, has
 * no climate</li>
 * <li>{@link com.midonarhel.lumina.weather.Forecast}: the weather of a climate in a slot of 8 game hours, a pure
 * function of the slot and the climate (a Markov chain restarted every 12 slots), so every client sees the same</li>
 * <li>{@link com.midonarhel.lumina.weather.ClimateReport}: the developer command's text</li>
 * </ul>
 */
package com.midonarhel.lumina.weather;
