package gr.happyonline.pulse;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/**
 * PULSE - a one-tap arcade game.
 *
 * A ball orbits a ring. Tap to reverse its direction. Collect gems to score,
 * avoid the spikes. Chained gem pickups build a combo multiplier, and a
 * last-moment reversal right before a spike pays a "CLOSE!" bonus.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_OVER = 3;

    private static final int TYPE_GEM = 0;
    private static final int TYPE_SPIKE = 1;

    private static final float TAU = (float) (Math.PI * 2.0);

    private static final float BASE_SPEED = 1.7f;       // rad/s at game start
    private static final float SPEED_PER_GEM = 0.045f;  // ramp per collected gem
    private static final float MAX_EXTRA_SPEED = 2.1f;
    private static final float COMBO_WINDOW = 4.0f;     // seconds to keep the chain alive
    private static final int MAX_COMBO = 8;
    private static final float NEAR_MISS_ARC = 0.32f;   // rad ahead of ball
    private static final float DEATH_SLOWMO = 0.95f;    // seconds of slow motion

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;
    private float cx, cy, ringRadius, ballRadius;

    // run state
    private int state = STATE_MENU;
    private float ballAngle;
    private int dir = 1;
    private int score;
    private int gems;
    private int combo = 1;
    private float comboTimer;
    private float nearMissCooldown;
    private float deathTimer;
    private float overTimer;
    private float runTime;
    private boolean newBestShown;

    // persistent stats
    private int best;
    private int gamesPlayed;
    private int totalGems;

    // presentation
    private float hue = 185f;
    private float targetHue = 185f;
    private float shake;
    private float flash;
    private float ringPulse;
    private float menuT;

    private final ArrayList<Item> items = new ArrayList<Item>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path spikePath = new Path();
    private Shader vignette;

    private static class Item {
        int type;
        float angle;
        float born;
    }

    private static class Particle {
        float x, y, vx, vy, life, maxLife, size;
        int color;
    }

    private static class FloatText {
        String text;
        float x, y, life;
        int color;
    }

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);

        prefs = context.getSharedPreferences("pulse", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        gamesPlayed = prefs.getInt("games", 0);
        totalGems = prefs.getInt("gems", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ run

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            score = 0;
            gems = 0;
            combo = 1;
            comboTimer = 0f;
            nearMissCooldown = 0f;
            runTime = 0f;
            newBestShown = false;
            ballAngle = -TAU / 4f;
            dir = 1;
            items.clear();
            particles.clear();
            texts.clear();
            targetHue = 185f;
            spawnItem(TYPE_GEM);
            spawnItem(TYPE_SPIKE);
            sfx.play(SoundFx.START);
        }
    }

    private void die() {
        state = STATE_DYING;
        deathTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.DEATH);
        buzz(160);
        float bx = cx + (float) Math.cos(ballAngle) * ringRadius;
        float by = cy + (float) Math.sin(ballAngle) * ringRadius;
        burst(bx, by, 44, 0xFFFF3D58, 1.6f);

        gamesPlayed++;
        totalGems += gems;
        boolean isBest = score > best;
        if (isBest) {
            best = score;
        }
        prefs.edit()
                .putInt("best", best)
                .putInt("games", gamesPlayed)
                .putInt("gems", totalGems)
                .apply();
    }

    private void spawnItem(int type) {
        Item it = new Item();
        it.type = type;
        it.born = runTime;
        for (int attempt = 0; attempt < 60; attempt++) {
            float a = rng.nextFloat() * TAU;
            if (angularDist(a, ballAngle) < 1.1f) {
                continue;
            }
            boolean clear = true;
            for (int i = 0; i < items.size(); i++) {
                if (angularDist(a, items.get(i).angle) < 0.55f) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                it.angle = a;
                items.add(it);
                return;
            }
        }
        // crowded ring: drop the request rather than spawning something unfair
    }

    private int spikeTarget() {
        int n = 1 + gems / 5;
        return n > 6 ? 6 : n;
    }

    private static float angularDist(float a, float b) {
        float d = (a - b) % TAU;
        if (d < 0) {
            d += TAU;
        }
        return d > TAU / 2f ? TAU - d : d;
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            float timeScale = state == STATE_DYING ? 0.18f : 1f;
            float dt = rawDt * timeScale;

            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            if (ringPulse > 0f) ringPulse = Math.max(0f, ringPulse - rawDt * 3f);

            // color drifts toward the current level's hue
            float dh = targetHue - hue;
            if (dh > 180f) dh -= 360f;
            if (dh < -180f) dh += 360f;
            hue += dh * Math.min(1f, rawDt * 1.5f);
            if (hue < 0f) hue += 360f;
            if (hue >= 360f) hue -= 360f;

            updateParticles(rawDt);
            updateTexts(rawDt);

            if (state == STATE_PLAYING || state == STATE_DYING) {
                runTime += dt;

                float speed = BASE_SPEED + Math.min(MAX_EXTRA_SPEED, gems * SPEED_PER_GEM);
                if (state == STATE_PLAYING) {
                    ballAngle = (ballAngle + dir * speed * dt) % TAU;
                    if (nearMissCooldown > 0f) nearMissCooldown -= dt;

                    if (comboTimer > 0f) {
                        comboTimer -= dt;
                        if (comboTimer <= 0f) {
                            combo = 1;
                        }
                    }

                    spawnTrail();
                    checkCollisions();
                }
            }

            if (state == STATE_DYING) {
                deathTimer -= rawDt;
                if (deathTimer <= 0f) {
                    state = STATE_OVER;
                    overTimer = 0f;
                }
            }
            if (state == STATE_OVER) {
                overTimer += rawDt;
            }
        }
    }

    private void checkCollisions() {
        float hitArc = (ballRadius * 1.9f) / ringRadius;
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            // grace period so freshly spawned spikes can't ambush the ball
            if (it.type == TYPE_SPIKE && runTime - it.born < 0.25f) {
                continue;
            }
            if (angularDist(ballAngle, it.angle) >= hitArc) {
                continue;
            }
            if (it.type == TYPE_GEM) {
                items.remove(i);
                collectGem(it);
            } else {
                die();
                return;
            }
        }
    }

    private void collectGem(Item it) {
        if (comboTimer > 0f || combo == 1) {
            combo = Math.min(MAX_COMBO, combo + (comboTimer > 0f ? 1 : 0));
        }
        comboTimer = COMBO_WINDOW;
        int points = combo;
        score += points;
        gems++;
        ringPulse = 1f;
        targetHue = (185f + (gems / 10) * 47f) % 360f;

        float gx = cx + (float) Math.cos(it.angle) * ringRadius;
        float gy = cy + (float) Math.sin(it.angle) * ringRadius;
        burst(gx, gy, 14, gemColor(), 1f);
        addText("+" + points + (combo > 1 ? "  x" + combo : ""), gx, gy, gemColor());
        sfx.play(SoundFx.COLLECT_0 + Math.min(combo - 1, 7));
        buzz(18);

        if (!newBestShown && best > 0 && score > best) {
            newBestShown = true;
            addText("NEW BEST!", cx, cy - ringRadius * 0.45f, 0xFFFFD740);
            sfx.play(SoundFx.NEW_BEST);
        }

        spawnItem(TYPE_GEM);
        int spikes = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == TYPE_SPIKE) spikes++;
        }
        while (spikes < spikeTarget()) {
            spawnItem(TYPE_SPIKE);
            spikes++;
        }
    }

    private void tapWhilePlaying() {
        // reward a reversal made right before slamming into a spike
        if (nearMissCooldown <= 0f) {
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (it.type != TYPE_SPIKE) continue;
                float fd = ((it.angle - ballAngle) * dir) % TAU;
                if (fd < 0) fd += TAU;
                if (fd > 0.045f && fd < NEAR_MISS_ARC) {
                    score += 5;
                    comboTimer = COMBO_WINDOW;
                    nearMissCooldown = 0.6f;
                    float bx = cx + (float) Math.cos(ballAngle) * ringRadius;
                    float by = cy + (float) Math.sin(ballAngle) * ringRadius;
                    addText("CLOSE! +5", bx, by, 0xFF69F0AE);
                    sfx.play(SoundFx.CLOSE);
                    break;
                }
            }
        }
        dir = -dir;
        sfx.play(SoundFx.TICK);
        buzz(10);
    }

    // ------------------------------------------------------------ particles

    private void spawnTrail() {
        float bx = cx + (float) Math.cos(ballAngle) * ringRadius;
        float by = cy + (float) Math.sin(ballAngle) * ringRadius;
        Particle p = new Particle();
        p.x = bx;
        p.y = by;
        p.vx = (rng.nextFloat() - 0.5f) * 30f;
        p.vy = (rng.nextFloat() - 0.5f) * 30f;
        p.maxLife = p.life = 0.32f;
        p.size = ballRadius * 0.75f;
        p.color = ballColor();
        particles.add(p);
    }

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = rng.nextFloat() * TAU;
            float spd = (60f + rng.nextFloat() * 340f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.45f + rng.nextFloat() * 0.5f;
            p.size = ballRadius * (0.3f + rng.nextFloat() * 0.55f);
            p.color = color;
            particles.add(p);
        }
    }

    private void updateParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                particles.remove(i);
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= (1f - 1.8f * dt);
            p.vy *= (1f - 1.8f * dt);
        }
    }

    private void addText(String s, float x, float y, int color) {
        FloatText t = new FloatText();
        t.text = s;
        t.x = x;
        t.y = y;
        t.life = 0.9f;
        t.color = color;
        texts.add(t);
    }

    private void updateTexts(float dt) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            FloatText t = texts.get(i);
            t.life -= dt;
            t.y -= dt * height * 0.06f;
            if (t.life <= 0f) {
                texts.remove(i);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private int themeColor(float sat, float val) {
        float[] hsv = {hue, sat, val};
        return Color.HSVToColor(hsv);
    }

    private int ballColor() {
        float[] hsv = {(hue + 40f) % 360f, 0.25f, 1f};
        return Color.HSVToColor(hsv);
    }

    private int gemColor() {
        float[] hsv = {(hue + 160f) % 360f, 0.75f, 1f};
        return Color.HSVToColor(hsv);
    }

    private void render(Canvas c) {
        synchronized (lock) {
            // background: themed dark base with darkened corners
            c.drawColor(themeColor(0.55f, 0.11f));
            if (vignette != null) {
                paint.setShader(vignette);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            }

            if (shake > 0f) {
                float m = shake * shake * width * 0.03f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            drawRing(c);
            if (state != STATE_MENU) {
                drawItems(c);
            }
            drawParticles(c);
            if (state == STATE_PLAYING || state == STATE_DYING) {
                drawBall(c);
            }
            drawTexts(c);

            if (shake > 0f) {
                c.restore();
            }

            if (state == STATE_PLAYING || state == STATE_DYING) {
                drawHud(c);
            } else if (state == STATE_MENU) {
                drawMenu(c);
            }
            if (state == STATE_OVER) {
                drawGameOver(c);
            }

            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (flash * 170f), 255, 60, 80));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawRing(Canvas c) {
        float r = ringRadius * (1f + ringPulse * 0.025f);
        paint.setStyle(Paint.Style.STROKE);
        int ring = themeColor(0.6f, 0.95f);
        // cheap glow: widening translucent strokes
        paint.setStrokeWidth(ballRadius * 1.7f);
        paint.setColor((ring & 0x00FFFFFF) | 0x14000000);
        c.drawCircle(cx, cy, r, paint);
        paint.setStrokeWidth(ballRadius * 0.9f);
        paint.setColor((ring & 0x00FFFFFF) | 0x2E000000);
        c.drawCircle(cx, cy, r, paint);
        paint.setStrokeWidth(ballRadius * 0.22f);
        paint.setColor(ring);
        c.drawCircle(cx, cy, r, paint);
    }

    private void drawItems(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float x = cx + (float) Math.cos(it.angle) * ringRadius;
            float y = cy + (float) Math.sin(it.angle) * ringRadius;
            if (it.type == TYPE_GEM) {
                float s = ballRadius * (1.05f + 0.12f * (float) Math.sin(menuT * 6f));
                int col = gemColor();
                paint.setColor((col & 0x00FFFFFF) | 0x33000000);
                c.drawCircle(x, y, s * 2.0f, paint);
                paint.setColor(col);
                c.save();
                c.rotate(menuT * 90f, x, y);
                spikePath.reset();
                spikePath.moveTo(x, y - s);
                spikePath.lineTo(x + s * 0.72f, y);
                spikePath.lineTo(x, y + s);
                spikePath.lineTo(x - s * 0.72f, y);
                spikePath.close();
                c.drawPath(spikePath, paint);
                c.restore();
            } else {
                float appear = Math.min(1f, (runTime - it.born) / 0.25f);
                float s = ballRadius * 1.35f * appear;
                paint.setColor(0xFFFF3D58);
                float deg = (float) Math.toDegrees(it.angle) + 90f;
                c.save();
                c.rotate(deg, x, y);
                spikePath.reset();
                spikePath.moveTo(x - s * 0.8f, y);
                spikePath.lineTo(x + s * 0.8f, y);
                spikePath.lineTo(x, y - s * 1.5f);
                spikePath.close();
                c.drawPath(spikePath, paint);
                spikePath.reset();
                spikePath.moveTo(x - s * 0.8f, y);
                spikePath.lineTo(x + s * 0.8f, y);
                spikePath.lineTo(x, y + s * 1.5f);
                spikePath.close();
                c.drawPath(spikePath, paint);
                c.restore();
            }
        }
    }

    private void drawBall(Canvas c) {
        float bx = cx + (float) Math.cos(ballAngle) * ringRadius;
        float by = cy + (float) Math.sin(ballAngle) * ringRadius;
        int col = ballColor();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((col & 0x00FFFFFF) | 0x40000000);
        c.drawCircle(bx, by, ballRadius * 1.9f, paint);
        paint.setColor(col);
        c.drawCircle(bx, by, ballRadius, paint);
    }

    private void drawParticles(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float k = p.life / p.maxLife;
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 200f)) << 24));
            c.drawCircle(p.x, p.y, p.size * k, paint);
        }
    }

    private void drawTexts(Canvas c) {
        textPaint.setTextSize(width * 0.052f);
        for (int i = 0; i < texts.size(); i++) {
            FloatText t = texts.get(i);
            int a = (int) (Math.min(1f, t.life / 0.6f) * 255f);
            textPaint.setColor((t.color & 0x00FFFFFF) | (a << 24));
            c.drawText(t.text, t.x, t.y, textPaint);
        }
    }

    private void drawHud(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.16f);
        c.drawText(String.valueOf(score), cx, height * 0.16f, textPaint);

        if (combo > 1) {
            float k = Math.min(1f, comboTimer / COMBO_WINDOW);
            textPaint.setTextSize(width * 0.06f);
            textPaint.setColor(gemColor());
            c.drawText("COMBO x" + combo, cx, height * 0.215f, textPaint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(gemColor());
            float bw = width * 0.22f * k;
            c.drawRect(cx - bw / 2f, height * 0.228f, cx + bw / 2f, height * 0.234f, paint);
        }

        textPaint.setTextSize(width * 0.04f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, cx, height * 0.06f, textPaint);
    }

    private void drawMenu(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.21f);
        c.drawText("PULSE", cx, height * 0.30f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(themeColor(0.4f, 1f));
        c.drawText("tap to reverse  •  catch gems  •  dodge spikes", cx, height * 0.355f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO PLAY", cx, cy + ringRadius + height * 0.10f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), cx, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(gamesPlayed + " runs  •  " + totalGems + " gems", cx, height * 0.92f, textPaint);

        // demo ball circling the menu ring
        float a = menuT * 1.2f;
        float bx = cx + (float) Math.cos(a) * ringRadius;
        float by = cy + (float) Math.sin(a) * ringRadius;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ballColor());
        c.drawCircle(bx, by, ballRadius, paint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB0000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.085f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "GAME OVER", cx, height * 0.30f, textPaint);

        textPaint.setTextSize(width * 0.22f);
        textPaint.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), cx, height * 0.46f, textPaint);

        textPaint.setTextSize(width * 0.045f);
        textPaint.setColor(0xAAFFFFFF);
        String sub;
        if (!isBest && best > 0 && score >= best * 0.9f) {
            sub = "SO CLOSE!  best is " + best;
        } else {
            sub = "best " + best + "  •  " + rankFor(best);
        }
        c.drawText(sub, cx, height * 0.52f, textPaint);
        c.drawText(gems + " gems this run", cx, height * 0.565f, textPaint);

        if (overTimer > 0.35f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", cx, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 350) return "GOD MODE";
        if (s >= 200) return "LEGEND";
        if (s >= 100) return "MASTER";
        if (s >= 50) return "PRO";
        if (s >= 25) return "HUNTER";
        return "ROOKIE";
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return true;
        }
        synchronized (lock) {
            switch (state) {
                case STATE_MENU:
                    startRun();
                    break;
                case STATE_PLAYING:
                    tapWhilePlaying();
                    break;
                case STATE_OVER:
                    if (overTimer > 0.35f) {
                        startRun();
                    }
                    break;
                default:
                    break;
            }
        }
        return true;
    }

    private void buzz(int ms) {
        try {
            if (vibrator != null) {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        startThread();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hpx) {
        synchronized (lock) {
            width = w;
            height = hpx;
            cx = w / 2f;
            cy = hpx * 0.47f;
            ringRadius = Math.min(w, hpx) * 0.345f;
            ballRadius = ringRadius * 0.075f;
            vignette = new RadialGradient(cx, cy, Math.max(w, hpx) * 0.8f,
                    new int[]{0x00000000, 0x99000000}, new float[]{0.45f, 1f},
                    Shader.TileMode.CLAMP);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopThread();
    }

    public void onResume() {
        if (surfaceReady) {
            startThread();
        }
    }

    public void onPause() {
        stopThread();
    }

    public void shutdown() {
        stopThread();
        sfx.release();
    }

    private void startThread() {
        if (thread == null || !thread.isAlive()) {
            thread = new GameThread();
            thread.start();
        }
    }

    private void stopThread() {
        if (thread != null) {
            thread.running = false;
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    private class GameThread extends Thread {
        volatile boolean running = true;

        @Override
        public void run() {
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                float dt = (now - last) / 1.0e9f;
                last = now;
                if (dt > 0.05f) dt = 0.05f;

                update(dt);

                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if (c != null) {
                        render(c);
                    }
                } finally {
                    if (c != null) {
                        try {
                            holder.unlockCanvasAndPost(c);
                        } catch (Exception ignored) {
                        }
                    }
                }

                long frame = (System.nanoTime() - now) / 1000000L;
                if (frame < 16) {
                    try {
                        Thread.sleep(16 - frame);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}
