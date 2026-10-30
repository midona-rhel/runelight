# Exact unlit terrain colour (research result, deob rev 223 jbx5/devious-client class36.method709)

Lit corner = (base & 0xFF80) | clamp((base & 127) * I / 128, 2, 126); textured overlay: clamp(I, 2, 126).
I = I_h - S. I_h from heights: L0=71, D=213; dx=h[x+1][y]-h[x-1][y]; dz=h[x][y+1]-h[x][y-1]; len=(int)sqrt(dx*dx+dz*dz+65536);
nx=(dx<<8)/len; ny=65536/len; nz=(dz<<8)/len; I_h=(nx*-50+nz*-50+ny*-10)/D+96 (flat = 84). S = shadow bytes from walls/objects (not exposed).
Inverting is NOT exact (floor, clamps, unknown S).

Recompute exactly instead (deterministic; random hue/lightness offsets only affect minimap getRBG):
- underlay: 11x11 window (radius 5) clipped to scene: hue = sum(def.hue)*256 / sum(def.hueMultiplier); sat = sum/n; light = sum/n; base = packHsl(hue, sat, light)
- overlay: textured -> TEXTURE; primaryRgb 0xFF00FF -> hidden; else packHsl(def.hue, def.sat, def.light) (no multiplier)
- packHsl: halve sat when light > 179, 192, 217, 243 (each); return (sat/32<<7)|(hue/4<<10)|light/2
- FloorUnderlayDefinition.setHsl(rgb) / FloorOverlayDefinition.setHsl: copy from deob (scratchpad/src/dev_*.java)
- Data: Scene.getUnderlayIds/getOverlayIds (id+1, 0 none), getTileShapes, client.getIndexConfig().loadData(1, id) underlay, loadData(4, id) overlay (verify group 4). Underlay opcode 1 rgb; overlay 1 primaryRgb, 2 texture, 5 hideUnderlay=false, 7 secondaryRgb.
- Unlit base is constant per tile per layer: smooth colour needs our own vertex averaging (117 HD's vertex hash).
- Live check on current revision: on textured overlay tiles corner colour == clamp(I_h - S, 2, 126); S readable there.
