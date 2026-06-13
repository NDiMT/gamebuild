package gr.happyonline.doge;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Random;

/**
 * Tiny procedural sound bank. Every effect is synthesized into a static
 * PCM buffer at startup, so the APK ships with zero audio assets. A
 * separate looping track provides the angry-bee buzz during play.
 */
public class SoundFx {

    public static final int START = 0;
    public static final int DRAW = 1;
    public static final int STING = 2;
    public static final int WIN = 3;
    public static final int CLEAR = 4;

    private static final int RATE = 44100;

    private final AudioTrack[] tracks = new AudioTrack[5];
    private AudioTrack buzz;
    private boolean buzzOn;

    public SoundFx() {
        try {
            tracks[START] = make(sweep(330f, 760f, 0.16f, 0.3f));
            tracks[DRAW] = make(scratch(0.10f, 0.22f));
            tracks[STING] = make(sting());
            tracks[WIN] = make(concat(tone(523f, 0.10f, 0.4f), tone(659f, 0.10f, 0.4f),
                    tone(784f, 0.10f, 0.4f), tone(1047f, 0.22f, 0.4f)));
            tracks[CLEAR] = make(sweep(700f, 300f, 0.12f, 0.25f));
            buzz = makeLoop(buzzBuffer());
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

    public synchronized void buzzLoop(boolean on) {
        buzzOn = on;
        if (buzz == null) {
            return;
        }
        try {
            if (on) {
                buzz.play();
            } else {
                buzz.pause();
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void release() {
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] != null) {
                try {
                    tracks[i].release();
                } catch (Exception ignored) {
                }
                tracks[i] = null;
            }
        }
        if (buzz != null) {
            try {
                buzz.release();
            } catch (Exception ignored) {
            }
            buzz = null;
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

    private static AudioTrack makeLoop(short[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                pcm.length * 2, AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        t.setLoopPoints(0, pcm.length, -1);
        return t;
    }

    /** Angry hive: two detuned saw-ish tones with a slow tremolo. */
    private static short[] buzzBuffer() {
        int n = RATE; // 1 second, loops seamlessly
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) RATE;
            double a = saw(220f, i) * 0.5 + saw(223f, i) * 0.5;
            double trem = 0.7 + 0.3 * Math.sin(2 * Math.PI * 18 * t);
            out[i] = (short) (a * trem * 0.16 * 32767);
        }
        return out;
    }

    private static double saw(float freq, int i) {
        double period = RATE / freq;
        double ph = (i % period) / period;
        return 2.0 * ph - 1.0;
    }

    private static short[] tone(float freq, float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.exp(-3.5 * i / n) * Math.min(1.0, i / (RATE * 0.004));
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

    /** Pencil-on-paper: short filtered noise burst. */
    private static short[] scratch(float dur, float vol) {
        int n = (int) (RATE * dur);
        short[] out = new short[n];
        Random r = new Random(11);
        double lp = 0;
        for (int i = 0; i < n; i++) {
            lp += ((r.nextDouble() * 2 - 1) - lp) * 0.4;
            double env = Math.sin(Math.PI * i / n);
            out[i] = (short) (lp * env * vol * 32767);
        }
        return out;
    }

    /** A bee sting: quick zap down plus a yelp. */
    private static short[] sting() {
        int n = (int) (RATE * 0.4f);
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double k = (double) i / n;
            double f = 1200 - 900 * k;
            phase += 2 * Math.PI * f / RATE;
            double env = Math.exp(-4.0 * k);
            out[i] = (short) (Math.sin(phase) * env * 0.4 * 32767);
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
