package gr.happyonline.kepler;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Procedural audio for KEPLER - including the soundtrack.
 *
 * The background music is J.S. Bach's Prelude in C major (BWV 846,
 * public domain), synthesized note by note into a looping PCM buffer
 * with a soft harp-like pluck and a faint string pad. The loop seam is
 * seamless because note tails wrap around to the start of the buffer.
 * No audio files ship with the APK.
 */
public class SoundFx {

    public static final int LAUNCH = 0;
    public static final int POOF = 1;
    public static final int CAPTURE = 2;
    public static final int CLEAR = 3;
    public static final int CLICK = 4;

    private static final int RATE = 44100;
    private static final int MUSIC_RATE = 22050;

    private final AudioTrack[] tracks = new AudioTrack[5];
    private volatile AudioTrack music;
    private volatile boolean musicWanted = true;
    private volatile boolean released;

    public SoundFx() {
        try {
            tracks[LAUNCH] = make(sweep(180f, 520f, 0.16f, 0.25f));
            tracks[POOF] = make(noise(0.25f, 0.30f));
            // a quick harp glissando: the sound of a moon falling asleep
            tracks[CAPTURE] = make(gliss(new float[]{523f, 659f, 784f, 1047f, 1319f}, 0.07f, 0.40f));
            tracks[CLEAR] = make(gliss(new float[]{523f, 659f, 784f, 1047f, 1319f, 1568f, 2093f}, 0.08f, 0.42f));
            tracks[CLICK] = make(sine(880f, 0.05f, 0.25f));
        } catch (Exception ignored) {
            // no audio is better than no game
        }

        // the prelude takes a moment to render; never block the UI for it
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    short[] m = buildPrelude();
                    AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC,
                            MUSIC_RATE, AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            m.length * 2, AudioTrack.MODE_STATIC);
                    at.write(m, 0, m.length);
                    at.setLoopPoints(0, m.length, -1);
                    synchronized (SoundFx.this) {
                        if (released) {
                            at.release();
                            return;
                        }
                        music = at;
                        if (musicWanted) {
                            at.play();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "prelude-synth");
        t.setDaemon(true);
        t.start();
    }

    public void play(int id) {
        AudioTrack t = id >= 0 && id < tracks.length ? tracks[id] : null;
        if (t == null) {
            return;
        }
        try {
            t.stop();
            t.reloadStaticData();
            t.play();
        } catch (Exception ignored) {
        }
    }

    public synchronized void musicResume() {
        musicWanted = true;
        if (music != null) {
            try {
                music.play();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void musicPause() {
        musicWanted = false;
        if (music != null) {
            try {
                music.pause();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void release() {
        released = true;
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                try {
                    tracks[i].release();
                } catch (Exception ignored) {
                }
                tracks[i] = null;
            }
        }
        if (music != null) {
            try {
                music.release();
            } catch (Exception ignored) {
            }
            music = null;
        }
    }

    // ----------------------------------------------------- Bach, BWV 846

    /**
     * First eight bars of the Prelude in C, ~72 bpm. Each bar is five
     * pitches arpeggiated in Bach's fixed sixteenth-note figuration.
     * The final bar (Cmaj7 over B) leads the loop gently back to C.
     */
    private static short[] buildPrelude() {
        int[][] bars = {
                {60, 64, 67, 72, 76},  // C major
                {60, 62, 69, 74, 77},  // Dm7 / C
                {59, 62, 67, 74, 77},  // G7 / B
                {60, 64, 67, 72, 76},  // C major
                {60, 64, 69, 76, 81},  // Am / C
                {60, 62, 66, 69, 74},  // D7 / C
                {59, 62, 67, 74, 79},  // G / B
                {59, 60, 64, 67, 72},  // Cmaj7 / B
        };
        int[] figure = {0, 1, 2, 3, 4, 2, 3, 4, 0, 1, 2, 3, 4, 2, 3, 4};

        float barDur = 3.33f;            // ~72 bpm, 4/4
        float sixteenth = barDur / 16f;
        int total = (int) (MUSIC_RATE * barDur * bars.length);
        float[] acc = new float[total];

        for (int b = 0; b < bars.length; b++) {
            float barStart = b * barDur;
            for (int i = 0; i < 16; i++) {
                float t0 = barStart + i * sixteenth;
                float freq = midi(bars[b][figure[i]]);
                pluck(acc, t0, freq, 0.9f, 0.135f);
            }
            // faint string pad: root an octave down + the fifth
            pad(acc, barStart, barDur, midi(bars[b][0] - 12), 0.030f);
            pad(acc, barStart, barDur, midi(bars[b][2]), 0.018f);
        }

        short[] out = new short[total];
        for (int i = 0; i < total; i++) {
            float v = acc[i];
            v = v / (1f + Math.abs(v) * 0.4f); // soft knee, no harsh clipping
            out[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 32767f * 0.85f);
        }
        return out;
    }

    private static float midi(int m) {
        return (float) (440.0 * Math.pow(2.0, (m - 69) / 12.0));
    }

    /** Harp-like pluck added into the loop buffer; tails wrap the seam. */
    private static void pluck(float[] acc, float start, float freq,
                              float dur, float vol) {
        int n = (int) (MUSIC_RATE * dur);
        int s0 = (int) (start * MUSIC_RATE);
        double w = 2 * Math.PI * freq / MUSIC_RATE;
        for (int i = 0; i < n; i++) {
            double t = i / (double) MUSIC_RATE;
            double env = Math.exp(-4.2 * t / dur) * Math.min(1.0, i / (MUSIC_RATE * 0.003));
            double s = Math.sin(w * i)
                    + 0.38 * Math.sin(2 * w * i) * Math.exp(-7.0 * t)
                    + 0.12 * Math.sin(3 * w * i) * Math.exp(-9.0 * t);
            acc[(s0 + i) % acc.length] += (float) (s * env * vol);
        }
    }

    /** Sustained quiet sine with slow attack/release. */
    private static void pad(float[] acc, float start, float dur,
                            float freq, float vol) {
        int n = (int) (MUSIC_RATE * dur);
        int s0 = (int) (start * MUSIC_RATE);
        double w = 2 * Math.PI * freq / MUSIC_RATE;
        for (int i = 0; i < n; i++) {
            double k = i / (double) n;
            double env = Math.sin(Math.PI * k);
            acc[(s0 + i) % acc.length] += (float) (Math.sin(w * i) * env * vol);
        }
    }

    // ----------------------------------------------------------- sfx tones

    private static AudioTrack make(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static short[] sine(float freq, float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.exp(-4.0 * i / n) * Math.min(1.0, i / (RATE * 0.004));
            out[i] = (short) (Math.sin(2 * Math.PI * freq * i / RATE) * env * vol * 32767);
        }
        return out;
    }

    /** Overlapping rising notes - a tiny harp glissando. */
    private static short[] gliss(float[] freqs, float step, float vol) {
        float tail = 0.5f;
        int n = (int) (RATE * (step * freqs.length + tail));
        float[] acc = new float[n];
        for (int k = 0; k < freqs.length; k++) {
            int s0 = (int) (k * step * RATE);
            double w = 2 * Math.PI * freqs[k] / RATE;
            int m = (int) (RATE * tail);
            for (int i = 0; i < m && s0 + i < n; i++) {
                double t = i / (double) RATE;
                double env = Math.exp(-6.0 * t) * Math.min(1.0, i / (RATE * 0.003));
                acc[s0 + i] += (float) ((Math.sin(w * i)
                        + 0.3 * Math.sin(2 * w * i) * Math.exp(-8.0 * t)) * env);
            }
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float v = acc[i] * vol;
            out[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 32767f);
        }
        return out;
    }

    private static short[] sweep(float f0, float f1, float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double f = f0 + (f1 - f0) * k;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.sin(Math.PI * k);
            out[i] = (short) (Math.sin(phase) * env * vol * 32767);
        }
        return out;
    }

    private static short[] noise(float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        Random r = new Random(7);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            lp += ((r.nextDouble() * 2 - 1) - lp) * 0.18;
            double env = Math.exp(-6.0 * i / n);
            out[i] = (short) (lp * env * vol * 32767);
        }
        return out;
    }
}
