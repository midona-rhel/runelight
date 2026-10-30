# Port map: 117 HD shading into Lumina (GPU plugin base)

H = C:\Users\Midona\source\runelight branch fork-117hd, src/main/java/rs117/hd (read with `git show fork-117hd:<path>`; the working tree is on branch lumina).
Lumina = same repo, branch lumina, src/main/java/com/midonarhel/lumina (copy of RuneLite GPU plugin 1.13.4).

## Colour source
- Models: request DrawCallbacks.UNLIT_FACE_COLORS; Model.getUnlitFaceColors() gives one HSL short per face. Use color1=color2=color3=unlit[face]&0xFFFF (H SceneUploader 1659-1660, 2277-2278). Hidden faces (color3 == -2) and transparency still come from the lit arrays.
- Model override colour predicates (colorOverrides; 285 of 4321 test l/hsl) were written against LIT colours: evaluate predicates on getFaceColors1() (lit), output the unlit colour.
- Brightness: unlit colours go to lightness 127; 117 HD compensates with Material.NONE.brightness = 0.8 (MaterialManager:264). Textured faces forced to lightness 90 (H SceneUploader 1723-1728, 1813-1816).
- Fake ground shadows (hideVanillaShadows -> HDUtils.isBakedGroundShading :506) still need hiding.
- Terrain: the API only exposes lit tile colours (SceneTilePaint Sw/Se/Ne/Nw, SceneTileModel triangle colours). 117 HD undoes the lighting heuristically (H ProceduralGenerator 712-755). Put terrain colour derivation behind ONE function so it can be replaced (a separate investigation looks for an exact inversion).
- Tile `replacements` predicates read the lit SW colour (TileOverrideVariables -> HDUtils.getSouthWesternMostTileColor :360): keep feeding them lit colours.

## Dependency closure (port, trimmed)
- Areas (needed by all): AreaManager 157, areas/Area 282, AABB 350, RegionBox 135 + areas.json. Tile overrides are area-scoped (override with Area.NONE skipped, TileOverrideManager:112); model overrides optionally (`areas`); environments too.
- Model overrides: ModelOverrideManager (~150 of 272: drop FishingSpotReplacer, sailing/detail culling), ModelOverride (~450 of 860: drop material, UV, wind, shadow-only fields as needed), InheritTileColorType; model_overrides.json whole (Gson ignores unknown fields).
- Tile overrides: TileOverrideManager (~230), TileOverride (~220), TileOverrideVariables, HDVariables (~40), ExpressionParser 753, ExpressionPredicate, VariableSupplier (full); tile_overrides.json whole (first match wins; do not trim). SeasonalTheme referenced by 84 replacements: keep as a constant (no seasonal textures).
- Gamevals: 117 HD uses GamevalManager + 5 MB gamevals.json; names match net.runelite.api.gameval.*ID constants -> ~60 lines of reflection instead.
- Environments: EnvironmentManager (~150 of 524: area pick + 3 s smoothstep transition :296-301; drop sky, fog, water, lightning), Environment (~120 of 222: ambient/sun colour, strengths, sun angles). Colours are sRGB hex -> linear at load (Environment 60-80), ambient derived when missing (143-150).
- Helpers: ColorUtils (~60: rgb, srgb<->linear, SrgbToLinearAdapter, deriveAmbientLight :153), GsonUtils (~100), SceneContext (~150), Int2IntHashMap, HDUtils (~80: tileVertexHash :62, isBakedGroundShading :506, sceneToWorld, getSouthWesternMostTileColor).
- ProceduralGenerator (~450 of 1165): MainTileOverridesGenerator (482-580), TerrainNormalGenerator (171-313, water depth 0), TerrainDataGenerator (583-785, shared per-vertex colour map), vertex-key helpers (108-480), useDefaultColor (315). Drop UnderwaterTerrainGenerator, TzHaar recolour, water types.
- SceneUploader logic to re-express in Lumina's uploader (~550): model face loop (1533-1953), tile paint (886-1207), tile model (1208-1532), calculateFaceNormal 2608, rotateNormals 2837, undoVanillaShading 2751-2814 (only where still needed).
- Drop: MaterialManager, GroundMaterial, WaterType, Light/Sky managers, config (a few booleans).
- Licences: GPU plugin BSD-2 (Adam); 117 HD BSD-2 (117, files without header fall under repo LICENSE); ColorUtils/GsonUtils/MathUtils/color_utils.glsl CC0. Keep headers; add 117 HD copyright notice where code is ported.

## Lumina vertex format hooks
- Static zone vertex 20 B (Zone.java:52-56, setupVao 153-169): shorts x,y,z,pad | int alpha/bias/hsl | shorts tex,u,v,pad. Pads at byte 6 and 18: pack an octahedral normal (8+8 bit) into one pad. Written by GpuIntBuffer.put22224/put2222.
- Dynamic vertex 24 B (VAO.java:44, 63-69): one pad short at offset 22 -> octahedral normal. Written at ModelUploader 618-625 (temp) and raw ints 250-270 (sorted).
- Flat normals: write the face normal into all three vertices (117 HD 1852-1875; also for faces with color3 == -1 and models without normals). Upward normals: write (0,-1,0) (Y is down).
- GPU flags: add NORMALS | UNLIT_FACE_COLORS at LuminaPlugin setGpuFlags (~420).
- Tiles: SceneUploader.upload(SceneTilePaint) ~443-509, upload(SceneTileModel) ~511-573: apply tile override, shared vertex colour, terrain normal.
- Static models: uploadStaticModel ~575-691; override lookup from the id passed by uploadZoneRenderable ~404 + world position; unlit colour, normals, hide, alpha, depthBias.
- Alpha faces: Zone.addAlphaModel ~375-413 must apply the same face skips (hide, fake shadow, changed alpha) as the uploader (117 HD Zone.java:539-640).
- Dynamic models: ModelUploader.uploadSortedModel ~86, uploadTempModel ~490 (from drawDynamic/drawTemp with tileObject/gameObject ids). Normals rotated by orientation on the CPU.
- Threading: run overrides + terrain normals inside loadScene (map-load thread) before zoneSize/uploadZone; mark zones dirty when JSON reloads; sub-scenes (boats) need world position without a scene base.

## Shader (117 HD look, ambient + sun, minimal)
```glsl
vec3 albedo = srgbToLinear(hslToSrgb(convertHsl(unpackRawHsl(hsl)))); // H color_utils.glsl 116,186,235,244
vec3 N = normalize(vNormal);                                           // Y is down
vec3 light = ambientColor*ambientStrength + sunColor*sunStrength*max(dot(N, sunDir), 0.);
vec3 c = linearToSrgb(albedo * light);
c = mix(c, fogSrgb, fog);                    // keep the GPU plugin's fog
c = pow(c, vec3(gamma)) + dither/255.;       // H applyOutputCorrection
```
Replace Lumina's hsl_to_rgb brightness pow path for scene geometry; pass the normal through instead of Gouraud RGB. sunDir from environment sun angles: -(m[2],m[6],m[10]) of rotateX(altitude)*rotateY(pi - azimuth) (H LegacyRenderer:1079-1084); default sun angles HDUtils.sunAngles(52, 235) (Environment.DEFAULT_SHADOW_ANGLES).
117 HD's sharp overlay/underlay mix needs three corner colours per fragment; plain interpolation of the shared vertex colours is acceptable for now.
