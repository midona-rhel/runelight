#!/usr/bin/env python3
"""
Make RuneLight's weather sounds (src/main/resources/com/midonarhel/runelight/sounds) from their CC0 sources.

  rain.wav          the outdoor rain loop: the quietest, most even stretch of the source, cross-faded into a seamless
                    loop, mono, 22.05 kHz, 16 bit
  rain_indoor.wav   the same loop heard from inside a building: low-passed, then run through a Schroeder reverb (four
                    combs and two all-passes) on the looped signal, so it stays seamless and the player's mixer needs
                    no reverb of its own. Same length as rain.wav, so the two crossfade in step.
  thunder1..3.wav   the three claps, mono, 22.05 kHz, trimmed where they die away and faded out

The thunder's distance and indoor muffling are not made here: the player filters them as it mixes (AudioMixer).

Usage: make_weather_sounds.py RAIN_WAV THUNDER_FILE... OUT_DIR
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
# the source has the odd drop 60 times as loud as its hiss: peaks past this many times the loudness are rounded off
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
    """Start of the stretch of the given length whose loudness varies least from 100 ms to the next."""
    block = RATE // 10
    best, best_start = None, 0
    for start in range(0, len(x) - length, RATE // 2):
        seg = x[start:start + length]
        levels = np.sqrt(np.mean(seg[:len(seg) // block * block].reshape(-1, block) ** 2, axis=1))
        score = np.std(levels) / max(np.mean(levels), 1e-9)
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


def lowpass(x, cutoff, poles=4):
    """Zero-phase low-pass in the frequency domain; x is a loop, so the wrap-around is right."""
    spectrum = np.fft.rfft(x)
    freq = np.fft.rfftfreq(len(x), 1 / RATE)
    return np.fft.irfft(spectrum / (1 + (freq / cutoff) ** (2 * poles)), len(x))


def comb(x, delay, feedback, damp):
    """Feedback comb with a damped loop, circular so a loop stays a loop."""
    n = len(x)
    y = np.zeros(n * 3)
    src = np.tile(x, 3)
    store = 0.0
    for i in range(n * 3):
        echo = y[i - delay] if i >= delay else 0.0
        store = echo * (1 - damp) + store * damp
        y[i] = src[i] + store * feedback
    return y[n:2 * n]


def allpass(x, delay, gain):
    n = len(x)
    src = np.tile(x, 3)
    y = np.zeros(n * 3)
    for i in range(n * 3):
        d = src[i - delay] if i >= delay else 0.0
        e = y[i - delay] if i >= delay else 0.0
        y[i] = -gain * src[i] + d + gain * e
    return y[n:2 * n]


def indoor(x):
    """Rain on a roof: dull, and in a room."""
    dull = lowpass(x, 1800)
    combs = [(1116, 0.84), (1188, 0.83), (1277, 0.82), (1356, 0.81)]
    wet = sum(comb(dull, int(d * RATE / 44100), g, 0.3) for d, g in combs) / len(combs)
    for d, g in [(556, 0.5), (441, 0.5)]:
        wet = allpass(wet, int(d * RATE / 44100), g)
    out = 0.35 * dull + 0.65 * wet
    return out - np.mean(out)


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
    outdoor = loop(decode(rain_path))
    outdoor *= RAIN_RMS / rms(outdoor)
    inside = indoor(outdoor)
    inside *= RAIN_RMS * 0.8 / rms(inside)
    write(out + "/rain.wav", outdoor)
    write(out + "/rain_indoor.wav", inside)
    for i, path in enumerate(thunders, 1):
        write("%s/thunder%d.wav" % (out, i), thunder(decode(path)))


main()
