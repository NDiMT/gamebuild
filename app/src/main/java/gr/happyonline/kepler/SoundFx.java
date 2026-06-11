package gr.happyonline.kepler;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Procedural audio for KEPLER - including the soundtrack.
 *
 * The background music is Chopin's Prelude in E minor, Op. 28 No. 4
 * (public domain): the slow chromatic lament of repeated chords under
 * a long singing melody, synthesized note by note into a looping PCM
 * buffer with a felt-piano tone and a faint pad. The closing dominant
 * bar resolves back into E minor as the loop wraps, and note tails wrap
 * around the seam, so the loop is endless and seamless. No audio files
 * ship with the APK.
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
                    short[] m = buildChopin();
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
        }, "chopin-synth");
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

    // -------------------------------------- Chopin, Prelude Op. 28 No. 4

    /**
     * Twelve slow bars (~56 bpm). The left hand pulses soft repeated
     * chords that sink one chromatic step at a time - the famous lament -
     * while the right hand holds a long melody that sighs downward from B.
     * The last bar (B major, the dominant) pulls the loop home to E minor.
     */
    private static short[] buildChopin() {
        int[][] chords = {
                {59, 64, 67},  // B3 E4 G4   (Em)
                {58, 64, 67},  // Bb3 E4 G4
                {57, 64, 67},  // A3 E4 G4
                {57, 62, 66},  // A3 D4 F#4
                {57, 62, 65},  // A3 D4 F4
                {56, 62, 65},  // G#3 D4 F4
                {55, 60, 64},  // G3 C4 E4
                {54, 60, 64},  // F#3 C4 E4
                {54, 59, 62},  // F#3 B3 D4
                {53, 59, 62},  // F3 B3 D4
                {52, 59, 64},  // E3 B3 E4   (Em)
                {47, 54, 63},  // B2 F#3 D#4 (B major - back to the top)
        };
        // melody per bar: {midi, startBeat, durationBeats}
        int[][][] melody = {
                {{71, 0, 4}},
                {{71, 0, 2}, {72, 2, 1}, {71, 3, 1}},
                {{71, 0, 4}},
                {{69, 0, 4}},
                {{69, 0, 2}, {71, 2, 1}, {69, 3, 1}},
                {{67, 0, 4}},
                {{67, 0, 2}, {69, 2, 1}, {67, 3, 1}},
                {{66, 0, 4}},
                {{66, 0, 4}},
                {{64, 0, 4}},
                {{64, 0, 4}},
                {{63, 0, 2}, {66, 2, 1}, {71, 3, 1}},
        };

        float beat = 60f / 56f;
        float barDur = beat * 4f;
        int total = (int) (MUSIC_RATE * barDur * chords.length);
        float[] acc = new float[total];

        for (int b = 0; b < chords.length; b++) {
            float barStart = b * barDur;
            // pulsing chords, eight per bar, barely breathing
            for (int i = 0; i < 8; i++) {
                float t0 = barStart + i * beat / 2f;
                for (int n = 0; n < 3; n++) {
                    pluck(acc, t0, midi(chords[b][n]), 0.8f, 0.045f);
                }
            }
            // the singing line
            int[][] line = melody[b];
            for (int n = 0; n < line.length; n++) {
                pluck(acc, barStart + line[n][1] * beat,
                        midi(line[n][0]), line[n][2] * beat * 1.25f, 0.155f);
            }
            // faint pad an octave below the bass note
            pad(acc, barStart, barDur, midi(chords[b][0] - 12), 0.022f);
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
