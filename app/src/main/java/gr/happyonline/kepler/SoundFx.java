package gr.happyonline.kepler;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Procedural audio for KEPLER - including the soundtrack.
 *
 * The background music is Pachelbel's Canon in D (public domain),
 * synthesized note by note into a looping PCM buffer: the famous ground
 * bass, two harp-like arpeggio voices an octave apart, and a faint
 * string pad. The final A major bar resolves straight back into D as
 * the loop wraps, and note tails wrap around the seam, so the loop is
 * endless and seamless. No audio files ship with the APK.
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

        // the canon takes a moment to render; never block the UI for it
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    short[] m = buildCanon();
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
        }, "canon-synth");
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

    // ------------------------------------------------- Pachelbel, Canon in D

    /**
     * Eight bars, one chord each, ~70 bpm: D - A - Bm - F#m - G - D - G - A.
     * Ground bass on the downbeat, a flowing eighth-note arpeggio, and the
     * same arpeggio echoed an octave higher between the beats.
     */
    private static short[] buildCanon() {
        // chord tones as [root, third, fifth, octave] in a singing register
        int[][] chords = {
                {62, 66, 69, 74},  // D major
                {57, 61, 64, 69},  // A major
                {59, 62, 66, 71},  // B minor
                {54, 57, 61, 66},  // F# minor
                {55, 59, 62, 67},  // G major
                {62, 66, 69, 74},  // D major
                {55, 59, 62, 67},  // G major
                {57, 61, 64, 69},  // A major
        };
        int[] bass = {50, 45, 47, 42, 43, 50, 43, 45}; // the famous ground
        int[] figure = {0, 2, 3, 2, 1, 2, 3, 2};       // gentle rise and fall

        float beat = 60f / 70f;
        float barDur = beat * 4f;
        float eighth = beat / 2f;
        int total = (int) (MUSIC_RATE * barDur * chords.length);
        float[] acc = new float[total];

        for (int b = 0; b < chords.length; b++) {
            float barStart = b * barDur;
            // ground bass: one long warm note per bar
            pluck(acc, barStart, midi(bass[b]), 2.4f, 0.105f);
            // main arpeggio voice
            for (int i = 0; i < 8; i++) {
                float t0 = barStart + i * eighth;
                pluck(acc, t0, midi(chords[b][figure[i]]), 1.0f, 0.115f);
                // echo voice: an octave up, floating between the beats
                pluck(acc, t0 + eighth * 0.5f,
                        midi(chords[b][figure[i]] + 12), 0.8f, 0.052f);
            }
            // faint string pad: root + fifth
            pad(acc, barStart, barDur, midi(chords[b][0] - 12), 0.026f);
            pad(acc, barStart, barDur, midi(chords[b][2]), 0.016f);
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
