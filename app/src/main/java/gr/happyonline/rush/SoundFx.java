package gr.happyonline.rush;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Tiny procedural sound bank. Every effect is synthesized into a static
 * PCM buffer at startup, so the APK ships with zero audio assets.
 */
public class SoundFx {

    public static final int JUMP = 0;
    public static final int JUMP2 = 1;
    public static final int DEATH = 2;
    public static final int CLOSE = 3;
    public static final int START = 4;
    public static final int NEW_BEST = 5;
    public static final int SPEED = 6;
    public static final int COIN_0 = 7; // ..COIN_0 + 7, cycling pitches

    private static final int RATE = 44100;

    private final AudioTrack[] tracks = new AudioTrack[15];

    public SoundFx() {
        try {
            tracks[JUMP] = make(sweep(420f, 760f, 0.09f, 0.35f));
            tracks[JUMP2] = make(sweep(560f, 1050f, 0.09f, 0.35f));
            tracks[DEATH] = make(noise(0.4f, 0.5f));
            tracks[CLOSE] = make(concat(sine(880f, 0.06f, 0.4f), sine(1320f, 0.09f, 0.4f)));
            tracks[START] = make(sweep(330f, 880f, 0.18f, 0.35f));
            tracks[NEW_BEST] = make(concat(sine(660f, 0.09f, 0.45f),
                    sine(831f, 0.09f, 0.45f), sine(988f, 0.16f, 0.45f)));
            tracks[SPEED] = make(sweep(440f, 1760f, 0.22f, 0.4f));
            // pentatonic-ish ladder so coin streaks literally sound like climbing
            float[] steps = {523f, 587f, 659f, 784f, 880f, 1047f, 1175f, 1319f};
            for (int i = 0; i < 8; i++) {
                tracks[COIN_0 + i] = make(sine(steps[i], 0.09f, 0.4f));
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
