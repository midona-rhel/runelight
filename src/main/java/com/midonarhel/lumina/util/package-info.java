/**
 * Shared helpers, each the subset the ported code uses. From 117 HD's src/main/java/rs117/hd/utils/: MathUtils,
 * ColorUtils and GsonUtils (CC0), AstronomyUtils (suncalc, BSD, unchanged), ExpressionParser, ExpressionPredicate
 * and VariableSupplier (the data files' expressions), and ObjectOrientation (the model pre-orientation part of
 * HDUtils.java). Gamevals replaces 117 HD's GamevalManager and its gamevals.json by reading RuneLite's gameval
 * constants; IntIntMap is Lumina's boxing-free map for the per-vertex terrain data.
 * <p>
 * Nothing here knows about GL, the scene or the settings.
 */
package com.midonarhel.lumina.util;
