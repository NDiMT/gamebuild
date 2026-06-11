package gr.happyonline.orbit;

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
 * ORBIT - gravity golf.
 *
 * Sling a comet across a pocket universe. Planets bend its flight;
 * thread the pull of gravity, grab the stars on the way, and drop the
 * comet into the goal ring. Endless procedurally generated levels that
 * slowly add heavier planets, repulsors and drifting moons. The shot
 * count is your golf score - but the stars are your pride.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAY = 1;
    private static final int STATE_CLEAR = 2;

    private static final float TAU = (float) (Math.PI * 2.0);
    private static final int SUBSTEPS = 4;
    private static final float FLIGHT_TIMEOUT = 9f;
    private static final int SKIP_AFTER_SHOTS = 12;

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random fxRng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;
    private float ballR;

    // progress
    private int level;
    private int starsTotal;
    private int maxLevel;

    // level data
    private final ArrayList<Planet> planets = new ArrayList<Planet>();
    private final Star[] stars = new Star[3];
    private float startX, startY, goalX, goalY, goalR;

    // state
    private int state = STATE_MENU;
    private int shots;
    private int starsThisLevel;
    private float levelTime;
    private float clearTimer;

    // ball
    private float bx, by, bvx, bvy;
    private boolean flying;
    private float flightTime;
    private final float[] trailX = new float[18];
    private final float[] trailY = new float[18];
    private int trailHead;

    // aiming
    private boolean aiming;
    private float anchorX, anchorY, pullX, pullY;

    // presentation
    private float shake;
    private float menuT;

    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Shader spaceShader;

    private static class Planet {
        float baseX, y, r;
        float x;            // current (oscillators drift)
        boolean repulse;
        float oscAmp, oscSpeed, oscPhase;
        int hue;
    }

    private static class Star {
        float x, y;
        boolean taken;
        float anim;
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

        prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE);
        level = Math.max(1, prefs.getInt("level", 1));
        starsTotal = prefs.getInt("stars", 0);
        maxLevel = prefs.getInt("maxLevel", 1);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ---------------------------------------------------------- level setup

    private void buildLevel(int lvl) {
        Random rng = new Random(lvl * 1000003L + 17L);
        planets.clear();

        startX = width * (0.30f + rng.nextFloat() * 0.40f);
        startY = height * 0.86f;
        goalX = width * (0.18f + rng.nextFloat() * 0.64f);
        goalY = height * (0.12f + rng.nextFloat() * 0.08f);
        goalR = Math.max(width * 0.052f, width * (0.085f - lvl * 0.0012f));

        int count = Math.min(1 + (lvl + 2) / 3, 6);
        for (int i = 0; i < count; i++) {
            Planet p = new Planet();
            for (int attempt = 0; attempt < 80; attempt++) {
                p.r = width * (0.05f + rng.nextFloat() * 0.055f);
                p.baseX = width * 0.12f + rng.nextFloat() * width * 0.76f;
                p.y = height * 0.24f + rng.nextFloat() * height * 0.50f;
                if (dist(p.baseX, p.y, startX, startY) < p.r + width * 0.20f) continue;
                if (dist(p.baseX, p.y, goalX, goalY) < p.r + goalR + width * 0.10f) continue;
                boolean clear = true;
                for (int j = 0; j < planets.size(); j++) {
                    Planet q = planets.get(j);
                    if (dist(p.baseX, p.y, q.baseX, q.y) < p.r + q.r + width * 0.10f) {
                        clear = false;
                        break;
                    }
                }
                if (clear) break;
            }
            p.x = p.baseX;
            p.repulse = lvl >= 6 && rng.nextInt(4) == 0;
            if (lvl >= 12 && rng.nextInt(10) < 3) {
                p.oscAmp = width * (0.05f + rng.nextFloat() * 0.05f);
                p.oscSpeed = 0.6f + rng.nextFloat() * 0.5f;
                p.oscPhase = rng.nextFloat() * TAU;
            }
            p.hue = rng.nextInt(360);
            planets.add(p);
        }

        for (int i = 0; i < 3; i++) {
            Star s = new Star();
            for (int attempt = 0; attempt < 80; attempt++) {
                s.x = width * 0.10f + rng.nextFloat() * width * 0.80f;
                s.y = height * (0.22f + 0.18f * i) + rng.nextFloat() * height * 0.10f;
                boolean clear = dist(s.x, s.y, goalX, goalY) > goalR + width * 0.08f;
                for (int j = 0; clear && j < planets.size(); j++) {
                    Planet q = planets.get(j);
                    if (dist(s.x, s.y, q.baseX, q.y) < q.r + q.oscAmp + width * 0.07f) {
                        clear = false;
                    }
                }
                if (clear) break;
            }
            s.taken = false;
            s.anim = 0f;
            stars[i] = s;
        }

        shots = 0;
        starsThisLevel = 0;
        levelTime = 0f;
        resetBall();
    }

    private void resetBall() {
        bx = startX;
        by = startY;
        bvx = 0f;
        bvy = 0f;
        flying = false;
        flightTime = 0f;
        aiming = false;
        for (int i = 0; i < trailX.length; i++) {
            trailX[i] = bx;
            trailY[i] = by;
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // --------------------------------------------------------------- update

    private void update(float dt) {
        synchronized (lock) {
            menuT += dt;
            if (shake > 0f) shake = Math.max(0f, shake - dt * 2.2f);

            updateParticles(dt);
            updateTexts(dt);

            if (state != STATE_PLAY && state != STATE_CLEAR) {
                return;
            }
            levelTime += dt;

            // drifting moons
            for (int i = 0; i < planets.size(); i++) {
                Planet p = planets.get(i);
                if (p.oscAmp > 0f) {
                    p.x = p.baseX + p.oscAmp
                            * (float) Math.sin(levelTime * p.oscSpeed + p.oscPhase);
                } else {
                    p.x = p.baseX;
                }
            }

            for (int i = 0; i < 3; i++) {
                if (stars[i].taken && stars[i].anim < 1f) {
                    stars[i].anim = Math.min(1f, stars[i].anim + dt * 3f);
                }
            }

            if (state == STATE_CLEAR) {
                clearTimer += dt;
                return;
            }

            if (flying) {
                float sdt = dt / SUBSTEPS;
                for (int s = 0; s < SUBSTEPS && flying; s++) {
                    stepBall(sdt);
                }
                trailHead = (trailHead + 1) % trailX.length;
                trailX[trailHead] = bx;
                trailY[trailHead] = by;

                flightTime += dt;
                if (flying && flightTime > FLIGHT_TIMEOUT) {
                    fizzle();
                }
            }
        }
    }

    /** One physics substep; flips `flying` off on crash/goal/out. */
    private void stepBall(float dt) {
        float ax = 0f, ay = 0f;
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            float dx = p.x - bx, dy = p.y - by;
            float d2 = dx * dx + dy * dy;
            float minD = p.r * 0.9f;
            if (d2 < minD * minD) d2 = minD * minD;
            float d = (float) Math.sqrt(d2);
            // heavier with size; tuned so a mid planet bends a mid shot nicely
            float m = 0.075f * width * width * width
                    * (p.r / (0.08f * width)) * (p.r / (0.08f * width));
            float a = m / d2 * (p.repulse ? -1f : 1f);
            ax += dx / d * a;
            ay += dy / d * a;
        }
        bvx += ax * dt;
        bvy += ay * dt;
        bx += bvx * dt;
        by += bvy * dt;

        // stars
        for (int i = 0; i < 3; i++) {
            Star s = stars[i];
            if (!s.taken && dist(bx, by, s.x, s.y) < ballR + width * 0.030f) {
                s.taken = true;
                starsThisLevel++;
                starsTotal++;
                burst(s.x, s.y, 12, 0xFFFFD740, 0.8f);
                addText("★", s.x, s.y - width * 0.03f, 0xFFFFD740);
                sfx.play(SoundFx.STAR_0 + Math.min(starsThisLevel - 1, 2));
                buzz(15);
            }
        }

        // goal
        if (dist(bx, by, goalX, goalY) < goalR) {
            levelClear();
            return;
        }
        // crash
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            if (dist(bx, by, p.x, p.y) < p.r + ballR) {
                burst(bx, by, 20, 0xFFFF8A65, 1.1f);
                shake = Math.max(shake, 0.5f);
                sfx.play(SoundFx.CRASH);
                buzz(40);
                resetBall();
                return;
            }
        }
        // lost in space
        float m = width * 0.30f;
        if (bx < -m || bx > width + m || by < -m || by > height + m) {
            fizzle();
        }
    }

    private void fizzle() {
        addText("lost in space...", width / 2f, height * 0.5f, 0x99FFFFFF);
        sfx.play(SoundFx.FIZZLE);
        resetBall();
    }

    private void levelClear() {
        flying = false;
        state = STATE_CLEAR;
        clearTimer = 0f;
        burst(goalX, goalY, 36, 0xFF69F0AE, 1.4f);
        sfx.play(SoundFx.CLEAR);
        buzz(60);
        if (level >= maxLevel) {
            maxLevel = level + 1;
        }
        prefs.edit()
                .putInt("level", level + 1)
                .putInt("stars", starsTotal)
                .putInt("maxLevel", maxLevel)
                .apply();
    }

    private void nextLevel() {
        level++;
        state = STATE_PLAY;
        buildLevel(level);
    }

    private void skipLevel() {
        addText("SKIPPED", width / 2f, height * 0.5f, 0x99FFFFFF);
        level++;
        prefs.edit().putInt("level", level).apply();
        buildLevel(level);
        sfx.play(SoundFx.FIZZLE);
    }

    private void launch() {
        float k = 3.0f;
        bvx = pullX * k;
        bvy = pullY * k;
        float sp = (float) Math.sqrt(bvx * bvx + bvy * bvy);
        float maxV = width * 1.45f;
        if (sp > maxV) {
            bvx = bvx / sp * maxV;
            bvy = bvy / sp * maxV;
        }
        flying = true;
        flightTime = 0f;
        shots++;
        sfx.play(SoundFx.LAUNCH);
        buzz(15);
    }

    // ------------------------------------------------------------ particles

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = fxRng.nextFloat() * TAU;
            float spd = (60f + fxRng.nextFloat() * 340f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.35f + fxRng.nextFloat() * 0.45f;
            p.size = width * (0.004f + fxRng.nextFloat() * 0.007f);
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
            p.vx *= (1f - 2.0f * dt);
            p.vy *= (1f - 2.0f * dt);
        }
    }

    private void addText(String s, float x, float y, int color) {
        FloatText t = new FloatText();
        t.text = s;
        t.x = x;
        t.y = y;
        t.life = 1.1f;
        t.color = color;
        texts.add(t);
    }

    private void updateTexts(float dt) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            FloatText t = texts.get(i);
            t.life -= dt;
            t.y -= dt * height * 0.04f;
            if (t.life <= 0f) {
                texts.remove(i);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private void render(Canvas c) {
        synchronized (lock) {
            c.drawColor(0xFF070812);
            if (spaceShader != null) {
                paint.setShader(spaceShader);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            }
            drawStarsBg(c);

            if (shake > 0f) {
                float m = shake * shake * width * 0.02f;
                c.save();
                c.translate((fxRng.nextFloat() - 0.5f) * m, (fxRng.nextFloat() - 0.5f) * m);
            }

            if (state != STATE_MENU) {
                drawGoal(c);
                drawPlanets(c);
                drawPickups(c);
                drawPad(c);
                if (aiming && !flying) {
                    drawPreview(c);
                }
                drawBall(c);
            }
            drawParticles(c);
            drawTexts(c);

            if (shake > 0f) {
                c.restore();
            }

            if (state == STATE_MENU) {
                drawMenu(c);
            } else {
                drawHud(c);
            }
            if (state == STATE_CLEAR) {
                drawClear(c);
            }
        }
    }

    private void drawStarsBg(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 40; i++) {
            float sx = (i * 379f + 53f) % width;
            float sy = (i * 233f + 89f) % height;
            int a = 22 + (int) (16 * Math.sin(menuT * 1.4f + i));
            paint.setColor(Color.argb(Math.max(8, a), 200, 215, 255));
            c.drawCircle(sx, sy, Math.max(1.5f, width * 0.0014f), paint);
        }
    }

    private void drawGoal(Canvas c) {
        float pulse = 1f + 0.06f * (float) Math.sin(menuT * 4f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2069F0AE);
        c.drawCircle(goalX, goalY, goalR * 1.8f * pulse, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.008f);
        paint.setColor(0xFF69F0AE);
        c.drawCircle(goalX, goalY, goalR * pulse, paint);
        paint.setStrokeWidth(width * 0.018f);
        paint.setColor(0x3369F0AE);
        c.drawCircle(goalX, goalY, goalR * pulse, paint);
        textPaint.setTextSize(goalR * 0.7f);
        textPaint.setColor(0x8869F0AE);
        c.drawText("GOAL", goalX, goalY + goalR * 0.25f, textPaint);
    }

    private void drawPlanets(Canvas c) {
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            int body;
            int ring;
            if (p.repulse) {
                body = 0xFF8C2B3D;
                ring = 0x66FF5D75;
            } else {
                float[] hsv = {p.hue, 0.45f, 0.55f};
                body = Color.HSVToColor(hsv);
                float[] hsv2 = {p.hue, 0.6f, 0.9f};
                ring = (Color.HSVToColor(hsv2) & 0x00FFFFFF) | 0x55000000;
            }
            // gravity field hint
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, width * 0.0018f));
            for (int g = 1; g <= 2; g++) {
                paint.setColor((ring & 0x00FFFFFF) | (0x30 / g) << 24);
                c.drawCircle(p.x, p.y, p.r * (1.6f + g * 0.7f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(body);
            c.drawCircle(p.x, p.y, p.r, paint);
            // crescent shading
            paint.setColor(0x33000000);
            c.drawCircle(p.x + p.r * 0.25f, p.y + p.r * 0.25f, p.r * 0.8f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width * 0.004f);
            paint.setColor(ring);
            c.drawCircle(p.x, p.y, p.r, paint);
            if (p.repulse) {
                // a repulsor wears a warning dash
                paint.setStrokeWidth(width * 0.006f);
                paint.setColor(0xFFFF5D75);
                c.drawLine(p.x - p.r * 0.45f, p.y, p.x + p.r * 0.45f, p.y, paint);
            }
        }
    }

    private void drawPickups(Canvas c) {
        for (int i = 0; i < 3; i++) {
            Star s = stars[i];
            if (s.taken && s.anim >= 1f) continue;
            float k = s.taken ? 1f - s.anim : 1f;
            float pulse = 1f + 0.15f * (float) Math.sin(menuT * 5f + i * 2f);
            float sz = width * 0.020f * pulse * k;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (50 * k), 255, 215, 64));
            c.drawCircle(s.x, s.y, sz * 2.4f, paint);
            paint.setColor(Color.argb((int) (255 * k), 255, 215, 64));
            drawStarShape(c, s.x, s.y, sz);
        }
    }

    private void drawStarShape(Canvas c, float x, float y, float r) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            float ang = -TAU / 4f + i * TAU / 10f;
            float rr = (i % 2 == 0) ? r : r * 0.45f;
            float px2 = x + (float) Math.cos(ang) * rr;
            float py2 = y + (float) Math.sin(ang) * rr;
            if (i == 0) path.moveTo(px2, py2);
            else path.lineTo(px2, py2);
        }
        path.close();
        c.drawPath(path, paint);
    }

    private void drawPad(Canvas c) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.004f);
        paint.setColor(0x5540E5FF);
        c.drawCircle(startX, startY, width * 0.045f, paint);
        paint.setColor(0x2240E5FF);
        c.drawCircle(startX, startY, width * 0.075f, paint);
    }

    private void drawPreview(Canvas c) {
        // simulate a short throw with frozen planets
        float sx = bx, sy = by;
        float vx = pullX * 3.0f, vy = pullY * 3.0f;
        float sp = (float) Math.sqrt(vx * vx + vy * vy);
        float maxV = width * 1.45f;
        if (sp > maxV) {
            vx = vx / sp * maxV;
            vy = vy / sp * maxV;
        }
        paint.setStyle(Paint.Style.FILL);
        float dt = 1f / 90f;
        int dots = 0;
        for (int i = 0; i < 110 && dots < 26; i++) {
            float ax = 0f, ay = 0f;
            for (int j = 0; j < planets.size(); j++) {
                Planet p = planets.get(j);
                float dx = p.x - sx, dy = p.y - sy;
                float d2 = dx * dx + dy * dy;
                float minD = p.r * 0.9f;
                if (d2 < minD * minD) d2 = minD * minD;
                float d = (float) Math.sqrt(d2);
                float m = 0.075f * width * width * width
                        * (p.r / (0.08f * width)) * (p.r / (0.08f * width));
                float a = m / d2 * (p.repulse ? -1f : 1f);
                ax += dx / d * a;
                ay += dy / d * a;
            }
            vx += ax * dt;
            vy += ay * dt;
            sx += vx * dt;
            sy += vy * dt;
            boolean stop = false;
            for (int j = 0; j < planets.size(); j++) {
                Planet p = planets.get(j);
                if (dist(sx, sy, p.x, p.y) < p.r) {
                    stop = true;
                    break;
                }
            }
            if (stop || sx < -width * 0.1f || sx > width * 1.1f
                    || sy < -width * 0.1f || sy > height + width * 0.1f) {
                break;
            }
            if (i % 4 == 0) {
                float k = 1f - dots / 28f;
                paint.setColor(Color.argb((int) (200 * k), 255, 255, 255));
                c.drawCircle(sx, sy, ballR * 0.35f, paint);
                dots++;
            }
        }
        // slingshot band
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.005f);
        paint.setColor(0x6640E5FF);
        c.drawLine(bx, by, bx - pullX * 0.25f, by - pullY * 0.25f, paint);
    }

    private void drawBall(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        if (flying) {
            for (int s = 0; s < trailX.length; s++) {
                int idx = (trailHead - s + trailX.length * 2) % trailX.length;
                float k = 1f - s / (float) trailX.length;
                paint.setColor(Color.argb((int) (70f * k), 64, 229, 255));
                c.drawCircle(trailX[idx], trailY[idx], ballR * (0.3f + 0.6f * k), paint);
            }
        }
        paint.setColor(0x4640E5FF);
        c.drawCircle(bx, by, ballR * 2.2f, paint);
        paint.setColor(0xFFE8FBFF);
        c.drawCircle(bx, by, ballR, paint);
    }

    private void drawParticles(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float k = p.life / p.maxLife;
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 220f)) << 24));
            c.drawCircle(p.x, p.y, p.size * (0.4f + 0.6f * k), paint);
        }
    }

    private void drawTexts(Canvas c) {
        textPaint.setTextSize(width * 0.05f);
        for (int i = 0; i < texts.size(); i++) {
            FloatText t = texts.get(i);
            int a = (int) (Math.min(1f, t.life / 0.6f) * 255f);
            textPaint.setColor((t.color & 0x00FFFFFF) | (a << 24));
            c.drawText(t.text, t.x, t.y, textPaint);
        }
    }

    private void drawHud(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.062f);
        c.drawText("LEVEL " + level, width / 2f, height * 0.055f, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(width * 0.038f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText(shots + (shots == 1 ? " shot" : " shots"), width * 0.04f, height * 0.055f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(0xFFFFD740);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(stars[i].taken ? "★" : "☆");
        }
        c.drawText(sb.toString(), width * 0.96f, height * 0.055f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        if (state == STATE_PLAY && !flying && !aiming && shots == 0) {
            float blink = 0.4f + 0.4f * (float) Math.sin(menuT * 3f);
            textPaint.setTextSize(width * 0.038f);
            textPaint.setColor(Color.argb((int) (blink * 230f), 255, 255, 255));
            c.drawText("drag anywhere to aim — release to sling", width / 2f, height * 0.94f, textPaint);
        }
        if (state == STATE_PLAY && shots >= SKIP_AFTER_SHOTS) {
            textPaint.setTextSize(width * 0.042f);
            textPaint.setColor(0xAAFFFFFF);
            c.drawText("having a bad orbit?  TAP HERE to skip  →", width / 2f, height * 0.975f, textPaint);
        }
    }

    private void drawMenu(Canvas c) {
        // demo planet + orbiting ball
        float pcx = width / 2f, pcy = height * 0.52f, pr = width * 0.09f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3A4A7A);
        c.drawCircle(pcx, pcy, pr, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.0035f);
        paint.setColor(0x5540E5FF);
        c.drawCircle(pcx, pcy, pr * 2.3f, paint);
        float oa = menuT * 1.4f;
        float ox = pcx + (float) Math.cos(oa) * pr * 2.3f;
        float oy = pcy + (float) Math.sin(oa) * pr * 2.3f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x4640E5FF);
        c.drawCircle(ox, oy, ballR * 2f, paint);
        paint.setColor(0xFFE8FBFF);
        c.drawCircle(ox, oy, ballR, paint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.17f);
        c.drawText("ORBIT", width / 2f, height * 0.22f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0xFF40E5FF);
        c.drawText("gravity golf across a pocket universe", width / 2f, height * 0.27f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        textPaint.setTextSize(width * 0.038f);
        c.drawText("sling the comet  •  let the planets bend it  •  land the ring",
                width / 2f, height * 0.315f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText(level > 1 ? "TAP TO CONTINUE — LVL " + level : "TAP TO PLAY",
                width / 2f, height * 0.80f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("★ " + starsTotal + "      reached level " + maxLevel, width / 2f, height * 0.88f, textPaint);
    }

    private void drawClear(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA000000);
        c.drawRect(0, 0, width, height, paint);

        textPaint.setTextSize(width * 0.085f);
        textPaint.setColor(0xFF69F0AE);
        c.drawText("LEVEL " + level + " CLEAR!", width / 2f, height * 0.34f, textPaint);

        textPaint.setTextSize(width * 0.12f);
        textPaint.setColor(0xFFFFD740);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(stars[i].taken ? "★" : "☆");
        }
        c.drawText(sb.toString(), width / 2f, height * 0.46f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(0xAAFFFFFF);
        c.drawText(shots + (shots == 1 ? " shot" : " shots") + "  •  ★ " + starsTotal + " total",
                width / 2f, height * 0.53f, textPaint);

        if (clearTimer > 0.5f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.06f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP FOR LEVEL " + (level + 1), width / 2f, height * 0.66f, textPaint);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        synchronized (lock) {
            switch (state) {
                case STATE_MENU:
                    if (action == MotionEvent.ACTION_DOWN) {
                        state = STATE_PLAY;
                        buildLevel(level);
                        sfx.play(SoundFx.LAUNCH);
                    }
                    break;
                case STATE_PLAY:
                    if (shots >= SKIP_AFTER_SHOTS && action == MotionEvent.ACTION_DOWN
                            && event.getY() > height * 0.95f) {
                        skipLevel();
                        break;
                    }
                    handleAim(event, action);
                    break;
                case STATE_CLEAR:
                    if (action == MotionEvent.ACTION_DOWN && clearTimer > 0.5f) {
                        nextLevel();
                    }
                    break;
                default:
                    break;
            }
        }
        return true;
    }

    private void handleAim(MotionEvent event, int action) {
        if (flying) {
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            aiming = true;
            anchorX = event.getX();
            anchorY = event.getY();
            pullX = 0f;
            pullY = 0f;
        } else if (action == MotionEvent.ACTION_MOVE && aiming) {
            pullX = anchorX - event.getX();
            pullY = anchorY - event.getY();
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            if (aiming && action == MotionEvent.ACTION_UP) {
                float p = (float) Math.sqrt(pullX * pullX + pullY * pullY);
                if (p > width * 0.05f) {
                    launch();
                }
            }
            aiming = false;
        }
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
            boolean changed = w != width || hpx != height;
            width = w;
            height = hpx;
            ballR = w * 0.014f;
            spaceShader = new RadialGradient(w / 2f, hpx * 0.4f, Math.max(w, hpx) * 0.9f,
                    new int[]{0x221A2C55, 0x00000000, 0x77000000},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
            if (changed && state != STATE_MENU) {
                buildLevel(level); // re-fit the level to the new canvas
            }
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
