#!/usr/bin/env python3
"""
Make RuneLight's weather sounds (src/main/resources/com/midonarhel/runelight/sounds) from their CC0 sources.

  rain.wav          heavy rain, heard from afar: no single drop stands out, it is one even wash. The source's average
                    spectrum given to random-phase noise (its colour without its drops), with the recording's
                    steadiest stretch laid over itself RAIN_LAYERS times under it, levelled by a compressor
  rain_light.wav    light rain: the source lower and duller, laid over itself a few times, at half the loudness; the
                    rain's start is the same loop faded in (AudioMixer)
                    Both are seamless loops of LOOP_SECONDS, mono, 22.05 kHz, 16 bit, the source pitched down (a
                    sample-rate change: rain has no tempo to keep)
  thunder1..3.wav   the three claps, mono, 22.05 kHz, trimmed where they die away and faded out

The rain's indoor muffling and the thunder's distance are not made here: the player filters them as it mixes
(AudioMixer).

Usage: make_weather_sounds.py RAIN_FILE THUNDER_FILE... OUT_DIR (RAIN_FILE: DledeSOUND's "Rain 1", 48 kHz)
Needs ffmpeg and numpy.
"""
import subprocess
import sys
import wave

import numpy as np

RATE = 22050
LOOP_SECONDS = 10
FADE_SECONDS = 1
# the heavy rain's loudness (RMS) in the file; the light rain's is half of it
RAIN_RMS = 0.0225
RAIN_SOURCE_RATE = 48000
# heavy: pitched down this many semitones, cut above this (Hz), laid over itself this many times, and the share of
# the wash and of the layered recording in the mix; light: the same, lower and duller, fewer layers
HEAVY_SEMITONES, HEAVY_CUTOFF, RAIN_LAYERS, HEAVY_WASH, HEAVY_LAYERED = 1.5, 6500, 14, 0.8, 0.35
LIGHT_SEMITONES, LIGHT_CUTOFF, LIGHT_LAYERS = 3, 3500, 3
# no frequency of the rain source may rise above this many times its usual level (detonal)
TONE_CAP = 2.5
# the compressor that levels the heavy rain: above the median loudness, 1 / this of the excess gets through
LEVEL_RATIO = 4.0
# the odd drop is much louder than the hiss: peaks past this many times the loudness are rounded off
RAIN_PEAKS = 2.5
THUNDER_PEAK = 0.9
THUNDER_MAX_SECONDS = 5
THUNDER_END_DB = -48


def decode(path, filters=None):
    """Mono float samples at RATE, through the ffmpeg filters given (they must end at RATE)."""
    rate = ["-af", filters] if filters else ["-ar", str(RATE)]
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1"] + rate + ["-f", "f32le", "-"],
                         check=True, stdout=subprocess.PIPE).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def pitched(path, semitones, cutoff):
    """The rain source pitched down and low-passed, at RATE, without its tones (detonal)."""
    return detonal(decode(path, "asetrate=%d*%f,aresample=%d,lowpass=f=%d"
                          % (RAIN_SOURCE_RATE, 2 ** (-semitones / 12), RATE, cutoff)))


def detonal(x, n=2048, cap=TONE_CAP):
    """Rain is noise: anything with a pitch in it (a frog, a bird) is something else. Each frequency is held to cap
    times its usual (median) level over the recording, short-time, so a croak or a chirp sinks into the rain while
    the rain itself, never that far above its median, passes unchanged."""
    hop = n // 4
    window = np.hanning(n)
    pad = np.concatenate([np.zeros(n), x, np.zeros(n)])
    frames = np.array([np.fft.rfft(pad[i:i + n] * window) for i in range(0, len(pad) - n, hop)])
    magnitude = np.abs(frames)
    limit = cap * np.median(magnitude, axis=0)
    frames *= np.minimum(1.0, limit / np.maximum(magnitude, 1e-12))
    out = np.zeros(len(pad))
    norm = np.zeros(len(pad))
    for k, frame in enumerate(frames):
        out[k * hop:k * hop + n] += np.fft.irfft(frame, n) * window
        norm[k * hop:k * hop + n] += window ** 2
    return (out / np.maximum(norm, 1e-9))[n:n + len(x)]


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


def layered(x, count, seed):
    """The loop laid over itself count times at random offsets (circular, so it still loops): its drops blur together."""
    rng = np.random.default_rng(seed)
    out = np.zeros_like(x)
    for _ in range(count):
        out += np.roll(x, int(rng.integers(0, len(x))))
    out -= np.mean(out)
    return out / rms(out)


def wash(x, seed):
    """Noise of the source's average spectrum (Welch), LOOP_SECONDS long and periodic: its colour, no drops."""
    n = 4096
    window = np.hanning(n)
    power = np.mean([np.abs(np.fft.rfft(x[i:i + n] * window)) ** 2 for i in range(0, len(x) - n, n // 2)], axis=0)
    length = LOOP_SECONDS * RATE
    magnitude = np.interp(np.fft.rfftfreq(length, 1 / RATE), np.fft.rfftfreq(n, 1 / RATE), np.sqrt(power))
    spectrum = magnitude * np.exp(2j * np.pi * np.random.default_rng(seed).random(len(magnitude)))
    spectrum[0] = 0
    out = np.fft.irfft(spectrum, length)
    return out / rms(out)


def level(x, ratio, ms=10):
    """A smooth compressor on the 10 ms envelope (circular): above the median loudness only 1 / ratio of the excess gets
    through, so what still pokes out of the rain is pushed into it."""
    k = int(RATE * ms / 1000)
    padded = lambda v: np.concatenate([v[-k:], v, v[:k]])
    envelope = np.sqrt(np.convolve(padded(x) ** 2, np.ones(k) / k, mode="same")[k:-k])
    gain = np.maximum(envelope / np.median(envelope), 1.0) ** (1.0 / ratio - 1.0)
    return x * np.convolve(padded(gain), np.ones(k) / k, mode="same")[k:-k]


def heavy_rain(path):
    x = pitched(path, HEAVY_SEMITONES, HEAVY_CUTOFF)
    out = level(HEAVY_WASH * wash(x, 3) + HEAVY_LAYERED * layered(loop(x), RAIN_LAYERS, 7), LEVEL_RATIO)
    return out * (RAIN_RMS / rms(out))


def light_rain(path):
    out = layered(loop(pitched(path, LIGHT_SEMITONES, LIGHT_CUTOFF)), LIGHT_LAYERS, 11)
    return out * (0.5 * RAIN_RMS / rms(out))


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
    write(out + "/rain.wav", heavy_rain(rain_path))
    write(out + "/rain_light.wav", light_rain(rain_path))
    for i, path in enumerate(thunders, 1):
        write("%s/thunder%d.wav" % (out, i), thunder(decode(path)))


main()
