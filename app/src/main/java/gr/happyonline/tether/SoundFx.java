package gr.happyonline.tether;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Tiny procedural sound bank. Every effect is synthesized into a static
 * PCM buffer at startup, so the APK ships with zero audio assets.
 */
public class SoundFx {

    public static final int START = 0;
    public static final int OVER = 1;
    public static final int HIT = 2;
    public static final int WAVE = 3;
    public static final int NEW_BEST = 4;
    public static final int GEM = 5;
    public static final int CLANG = 6;
    public static final int KILL_0 = 7; // ..KILL_0 + 7, rising with the combo

    private static final int RATE = 44100;

    private final AudioTrack[] tracks = new AudioTrack[15];

    public SoundFx() {
        try {
            tracks[START] = make(sweep(330f, 880f, 0.18f, 0.35f));
            tracks[OVER] = make(noise(0.5f, 0.5f));
            tracks[HIT] = make(thud());
            tracks[WAVE] = make(sweep(440f, 1760f, 0.22f, 0.4f));
            tracks[NEW_BEST] = make(concat(sine(660f, 0.09f, 0.45f),
                    sine(831f, 0.09f, 0.45f), sine(988f, 0.16f, 0.45f)));
            tracks[GEM] = make(sine(1175f, 0.09f, 0.4f));
            tracks[CLANG] = make(clang());
            // pentatonic-ish ladder so kill streaks literally sound like climbing
            float[] steps = {523f, 587f, 659f, 784f, 880f, 1047f, 1175f, 1319f};
            for (int i = 0; i < 8; i++) {
                tracks[KILL_0 + i] = make(slice(steps[i]));
            }
        } catch (Exception ignored) {
            // no audio is better than no game
        }
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

    public void release() {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                try {
                    tracks[i].release();
                } catch (Exception ignored) {
                }
                tracks[i] = null;
            }
        }
    }

    // ------------------------------------------------------------ synthesis

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

    /** A kill: bright tone with a whoosh of noise - the sound of a clean cut. */
    private static short[] slice(float freq) {
        int n = (int) (RATE * 0.11f);
        short[] out = new short[n];
        Random r = new Random(3);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double env = Math.exp(-5.0 * k);
            lp += ((r.nextDouble() * 2 - 1) - lp) * 0.5;
            double s = Math.sin(2 * Math.PI * freq * i / RATE) * 0.7 + lp * 0.5;
            out[i] = (short) (s * env * 0.4 * 32767);
        }
        return out;
    }

    /** Metallic tick for an armored shadow shrugging off a cut. */
    private static short[] clang() {
        int n = (int) (RATE * 0.08f);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double env = Math.exp(-8.0 * k);
            double s = Math.sin(2 * Math.PI * 1850 * i / (double) RATE) * 0.6
                    + Math.sin(2 * Math.PI * 2483 * i / (double) RATE) * 0.4;
            out[i] = (short) (s * env * 0.3 * 32767);
        }
        return out;
    }

    private static short[] thud() {
        int n = (int) (RATE * 0.22f);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double f = 160 - 90 * k;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-5.0 * k);
            out[i] = (short) (Math.sin(phase) * env * 0.55 * 32767);
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
            lp += ((r.nextDouble() * 2 - 1) - lp) * 0.25; // soften the hiss
            double env = Math.exp(-6.0 * i / n);
            out[i] = (short) (lp * env * vol * 32767);
        }
        return out;
    }

    private static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) {
            n += p.length;
        }
        short[] out = new short[n];
        int pos = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
