/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

// Lumina's sun glow: the wide warm glow around the sun, added to the sky gradient by the sky pass
// (lumina_sky_frag.glsl) and to the distance fog by the scene shader (frag.glsl), so the draw-distance edge meets the
// sky behind it. Needs the uniform skySunColor (the glow's colour with its visibility in it, linear). dir and sunDir
// are unit vectors, y down.
vec3 luminaSunGlow(vec3 dir, vec3 sunDir) {
  float mu = max(dot(dir, sunDir), 0.0);
  // mu^10 and mu^120, multiplied out
  float m2 = mu * mu;
  float m4 = m2 * m2;
  float m10 = m4 * m4 * m2;
  float m20 = m10 * m10;
  float m40 = m20 * m20;
  float m120 = m40 * m40 * m40;
  return skySunColor * (m10 * 0.30 + m120 * 0.50);
}
