#!/usr/bin/env python3
"""
Make RuneLight's weather sounds (src/main/resources/com/midonarhel/runelight/sounds) from their CC0 sources.

  rain.wav          the rain loop: the steadiest stretch of the source, cross-faded into a seamless loop, mono,
                    22.05 kHz, 16 bit
  thunder1..3.wav   the three claps, mono, 22.05 kHz, trimmed where they die away and faded out

The rain's indoor muffling and the thunder's distance are not made here: the player filters them as it mixes
(AudioMixer).

Usage: make_weather_sounds.py RAIN_FILE THUNDER_FILE... OUT_DIR
Needs ffmpeg and numpy.
"""
import subprocess
import sys
import wave

import numpy as np

RATE = 22050
LOOP_SECONDS = 10
FADE_SECONDS = 1
RAIN_RMS = 0.15
# the odd drop is much louder than the hiss: peaks past this many times the loudness are rounded off
RAIN_PEAKS = 2.5
THUNDER_PEAK = 0.9
THUNDER_MAX_SECONDS = 5
THUNDER_END_DB = -48


def decode(path):
    """Mono float samples at RATE."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
                         check=True, stdout=subprocess.PIPE).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def write(path, x):
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def rms(x):
    return float(np.sqrt(np.mean(x * x)))


def evenest(x, length):
    """Start of the stretch of the given length whose loudness varies least from 100 ms to the next and has no
    sound standing out of the rain (a drop from a roof, a bird, a voice): the loudest 100 ms stays near the median."""
    block = RATE // 10
    best, best_start = None, 0
    for start in range(0, len(x) - length, RATE // 2):
        seg = x[start:start + length]
        levels = np.sqrt(np.mean(seg[:len(seg) // block * block].reshape(-1, block) ** 2, axis=1))
        mean = max(np.mean(levels), 1e-9)
        score = np.std(levels) / mean + np.max(levels) / max(np.median(levels), 1e-9)
        if best is None or score < best:
            best, best_start = score, start
    return best_start


def loop(x):
    """A seamless loop of LOOP_SECONDS: the end is cross-faded (equal power) into the start."""
    length, fade = LOOP_SECONDS * RATE, FADE_SECONDS * RATE
    start = evenest(x, length + fade)
    seg = x[start:start + length + fade]
    out = seg[:length].copy()
    t = np.linspace(0, np.pi / 2, fade)
    out[:fade] = seg[:fade] * np.sin(t) + seg[length:length + fade] * np.cos(t)
    out -= np.mean(out)
    out /= rms(out)
    return np.tanh(out / RAIN_PEAKS) * RAIN_PEAKS


def thunder(x):
    x = x - np.mean(x)
    x = x[:THUNDER_MAX_SECONDS * RATE]
    block = RATE // 20
    levels = np.array([rms(x[i:i + block]) for i in range(0, len(x) - block, block)])
    peak = max(levels.max(), 1e-9)
    loud = np.nonzero(20 * np.log10(np.maximum(levels, 1e-9) / peak) > THUNDER_END_DB)[0]
    x = x[:(loud[-1] + 2) * block]
    fade = min(len(x), RATE)
    x[-fade:] *= np.linspace(1, 0, fade) ** 2
    return x * (THUNDER_PEAK / np.abs(x).max())


def main():
    rain_path, thunders, out = sys.argv[1], sys.argv[2:-1], sys.argv[-1]
    rain = loop(decode(rain_path))
    write(out + "/rain.wav", rain * (RAIN_RMS / rms(rain)))
    for i, path in enumerate(thunders, 1):
        write("%s/thunder%d.wav" % (out, i), thunder(decode(path)))


main()
