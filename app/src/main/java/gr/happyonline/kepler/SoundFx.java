package gr.happyonline.kepler;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Procedural audio for KEPLER - including the soundtrack.
 *
 * The background music is Satie's Gymnopedie No. 1 (public domain):
 * the floating bass-then-chord sway in slow 3/4 under the famous
 * weightless melody, synthesized note by note into a looping PCM
 * buffer with a soft felt-piano tone. The second phrase settles on
 * F# over the returning G major seventh - the signature suspension -
 * so the loop folds back into itself seamlessly, with note tails
 * wrapping the seam. No audio files ship with the APK.
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

        // the gymnopedie takes a moment to render; never block the UI for it
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    short[] m = buildGymnopedie();
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
        }, "satie-synth");
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

    // ------------------------------------------ Satie, Gymnopedie No. 1

    /**
     * Sixteen bars of slow 3/4 (~66 bpm). The accompaniment sways between
     * G (with a Bm/D color chord) and D (with an A-C#-F# chord); the
     * famous melody enters on bar five and floats down from F#5. The
     * second phrase rises to a long F# suspended over the returning
     * G major seventh, which folds the loop seamlessly back to bar one.
     */
    private static short[] buildGymnopedie() {
        float beat = 60f / 66f;
        float barDur = beat * 3f;
        int bars = 16;
        int total = (int) (MUSIC_RATE * barDur * bars);
        float[] acc = new float[total];

        int[] bassG = {43};            // G2
        int[] bassD = {38};            // D2
        int[] chordG = {59, 62, 66};   // B3 D4 F#4
        int[] chordD = {57, 61, 66};   // A3 C#4 F#4

        for (int b = 0; b < bars; b++) {
            float t0 = b * barDur;
            boolean gBar = b % 2 == 0;
            int bass = gBar ? bassG[0] : bassD[0];
            int[] chord = gBar ? chordG : chordD;
            // beat 1: deep bass; beats 2 and 3: the floating chord
            pluck(acc, t0, midi(bass), 2.2f, 0.085f);
            for (int n = 0; n < 3; n++) {
                pluck(acc, t0 + beat, midi(chord[n]), 1.5f, 0.045f);
                pluck(acc, t0 + beat * 2f, midi(chord[n]), 1.5f, 0.038f);
            }
            // gentle pad on the bar's root
            pad(acc, t0, barDur, midi(bass + 12), 0.020f);
        }

        // the melody: {midi, bar, beatInBar, durationBeats}
        int[][] melody = {
                {78, 4, 0, 1}, {81, 4, 1, 1}, {79, 4, 2, 1},   // F#5 A5 G5
                {78, 5, 0, 1}, {73, 5, 1, 1}, {71, 5, 2, 1},   // F#5 C#5 B4
                {73, 6, 0, 1}, {74, 6, 1, 1}, {69, 6, 2, 1},   // C#5 D5 A4
                {69, 7, 0, 6},                                  // A4 floats
                {78, 10, 0, 1}, {81, 10, 1, 1}, {79, 10, 2, 1}, // F#5 A5 G5
                {78, 11, 0, 1}, {73, 11, 1, 1}, {71, 11, 2, 1}, // F#5 C#5 B4
                {73, 12, 0, 1}, {74, 12, 1, 1}, {76, 12, 2, 1}, // C#5 D5 E5
                {78, 13, 0, 8},                                 // F#5 suspended...
        };
        for (int n = 0; n < melody.length; n++) {
            float start = melody[n][1] * barDur + melody[n][2] * beat;
            pluck(acc, start, midi(melody[n][0]),
                    melody[n][3] * beat * 1.3f, 0.135f);
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

    /** Felt-piano pluck added into the loop buffer; tails wrap the seam. */
    private static void pluck(float[] acc, float start, float freq,
                              float dur, float vol) {
        int n = (int) (MUSIC_RATE * dur);
        int s0 = (int) (start * MUSIC_RATE);
        double w = 2 * Math.PI * freq / MUSIC_RATE;
        for (int i = 0; i < n; i++) {
            double t = i / (double) MUSIC_RATE;
            double env = Math.exp(-4.2 * t / dur) * Math.min(1.0, i / (MUSIC_RATE * 0.012));
            double s = Math.sin(w * i)
                    + 0.30 * Math.sin(2 * w * i) * Math.exp(-7.0 * t)
                    + 0.08 * Math.sin(3 * w * i) * Math.exp(-9.0 * t);
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
