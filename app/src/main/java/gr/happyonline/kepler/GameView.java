package gr.happyonline.kepler;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/**
 * KEPLER - lull the comets into orbit.
 *
 * Sling a comet into the pull of a planet and keep it inside the soft
 * shimmering band until it completes one full revolution: then it falls
 * asleep and becomes a moon, circling forever. Each level asks for a few
 * moons around each planet. Bach's Prelude in C plays underneath - this
 * is a lullaby, not a battle.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAY = 1;
    private static final int STATE_CLEAR = 2;

    private static final float TAU = (float) (Math.PI * 2.0);
    private static final int SUBSTEPS = 4;
    private static final float FLIGHT_TIMEOUT = 14f;
    private static final float BAND_GRACE = 0.9f;
    private static final float CAPTURE_SWEEP = TAU * 0.8f; // ~290° is enough

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
    private int moonsTotal;
    private int maxLevel;

    // level
    private final ArrayList<Planet> planets = new ArrayList<Planet>();
    private final ArrayList<Moon> moons = new ArrayList<Moon>();
    private float startX, startY;

    // state
    private int state = STATE_MENU;
    private int launches;
    private float levelTime;
    private float clearTimer;

    // comet
    private float bx, by, bvx, bvy;
    private boolean flying;
    private float flightTime;
    private final float[] trailX = new float[22];
    private final float[] trailY = new float[22];
    private int trailHead;

    // capture tracking
    private int capP = -1;
    private float capSweep;
    private float capLastTheta;
    private float capGrace;
    private boolean capRebase;

    // aiming
    private boolean aiming;
    private float anchorX, anchorY, pullX, pullY;

    private float menuT;

    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private Shader nebulaA, nebulaB, nebulaC, nebulaD;
    private BlurMaskFilter blurSmall, blurMed, blurBig;

    private static class Planet {
        float baseX, y, r, x;
        float bandIn, bandOut;
        int needed;
        int captured;
        boolean repulse;
        float oscAmp, oscSpeed, oscPhase;
        float hue;
        float bloom;      // flash when it gains a moon
        Shader glow;      // soft radial body, no hard edges
    }

    private static class Moon {
        int planet;
        float radius, angle, omega;
        float hue;
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

        prefs = context.getSharedPreferences("kepler", Context.MODE_PRIVATE);
        level = Math.max(1, prefs.getInt("level", 1));
        moonsTotal = prefs.getInt("moons", 0);
        maxLevel = prefs.getInt("maxLevel", 1);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ---------------------------------------------------------- level setup

    private void buildLevel(int lvl) {
        Random rng = new Random(lvl * 1000003L + 41L);
        planets.clear();
        moons.clear();

        startX = width * (0.30f + rng.nextFloat() * 0.40f);
        startY = height * 0.88f;

        int count = 1 + (lvl >= 3 ? 1 : 0) + (lvl >= 8 ? 1 : 0);
        for (int i = 0; i < count; i++) {
            Planet p = new Planet();
            for (int attempt = 0; attempt < 90; attempt++) {
                p.r = width * (0.055f + rng.nextFloat() * 0.035f);
                p.baseX = width * 0.22f + rng.nextFloat() * width * 0.56f;
                p.y = height * (0.16f + 0.22f * i) + rng.nextFloat() * height * 0.10f;
                float bandOut = p.r * (3.3f - Math.min(0.8f, lvl * 0.03f));
                if (dist(p.baseX, p.y, startX, startY) < bandOut + width * 0.16f) continue;
                boolean clear = true;
                for (int j = 0; j < planets.size(); j++) {
                    Planet q = planets.get(j);
                    if (dist(p.baseX, p.y, q.baseX, q.y)
                            < (p.r + q.r) * 3.1f) {
                        clear = false;
                        break;
                    }
                }
                if (clear) break;
            }
            p.x = p.baseX;
            p.bandIn = p.r * 1.30f;
            p.bandOut = p.r * (3.3f - Math.min(0.8f, lvl * 0.03f));
            p.needed = 1;
            if (lvl >= 5 && rng.nextInt(3) == 0) p.needed = 2;
            if (lvl >= 10 && rng.nextInt(4) == 0) p.needed = 3;
            if (lvl >= 12 && rng.nextInt(10) < 3) {
                p.oscAmp = width * (0.04f + rng.nextFloat() * 0.04f);
                p.oscSpeed = 0.5f + rng.nextFloat() * 0.4f;
                p.oscPhase = rng.nextFloat() * TAU;
            }
            p.hue = rng.nextFloat() * 360f; // the whole rainbow is welcome
            p.glow = planetGlow(p.hue, p.r, false);
            planets.add(p);
        }

        // a repulsor wanders in on later levels: pure obstacle, no moons
        if (lvl >= 7 && rng.nextInt(3) != 0) {
            Planet p = new Planet();
            for (int attempt = 0; attempt < 90; attempt++) {
                p.r = width * (0.035f + rng.nextFloat() * 0.02f);
                p.baseX = width * 0.15f + rng.nextFloat() * width * 0.70f;
                p.y = height * (0.30f + rng.nextFloat() * 0.35f);
                boolean clear = dist(p.baseX, p.y, startX, startY) > width * 0.22f;
                for (int j = 0; clear && j < planets.size(); j++) {
                    Planet q = planets.get(j);
                    if (dist(p.baseX, p.y, q.baseX, q.y) < q.bandOut + p.r + width * 0.05f) {
                        clear = false;
                    }
                }
                if (clear) break;
            }
            p.x = p.baseX;
            p.repulse = true;
            p.needed = 0;
            p.hue = 340f;
            p.glow = planetGlow(p.hue, p.r, true);
            planets.add(p);
        }

        launches = 0;
        levelTime = 0f;
        resetComet();
    }

    private void resetComet() {
        bx = startX;
        by = startY;
        bvx = 0f;
        bvy = 0f;
        flying = false;
        flightTime = 0f;
        aiming = false;
        capP = -1;
        capSweep = 0f;
        for (int i = 0; i < trailX.length; i++) {
            trailX[i] = bx;
            trailY[i] = by;
        }
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float mass(Planet p) {
        return 0.075f * width * width * width
                * (p.r / (0.08f * width)) * (p.r / (0.08f * width));
    }

    private Shader planetGlow(float hue, float r, boolean repulse) {
        int core, mid;
        if (repulse) {
            core = 0xFFFF6E8C;
            mid = 0xAAC22B55;
        } else {
            float[] h0 = {hue, 0.45f, 1f};
            float[] h1 = {hue, 0.80f, 0.75f};
            core = Color.HSVToColor(h0);
            mid = (Color.HSVToColor(h1) & 0x00FFFFFF) | 0xBB000000;
        }
        return new RadialGradient(0, 0, r * 2.4f,
                new int[]{core, mid, (mid & 0x00FFFFFF) | 0x33000000, 0x00000000},
                new float[]{0f, 0.42f, 0.62f, 1f}, Shader.TileMode.CLAMP);
    }

    // --------------------------------------------------------------- update

    private void update(float dt) {
        synchronized (lock) {
            menuT += dt;
            updateParticles(dt);
            updateTexts(dt);

            if (state == STATE_MENU) {
                return;
            }
            levelTime += dt;

            for (int i = 0; i < planets.size(); i++) {
                Planet p = planets.get(i);
                if (p.oscAmp > 0f) {
                    p.x = p.baseX + p.oscAmp
                            * (float) Math.sin(levelTime * p.oscSpeed + p.oscPhase);
                } else {
                    p.x = p.baseX;
                }
                if (p.bloom > 0f) p.bloom = Math.max(0f, p.bloom - dt * 1.6f);
            }
            for (int i = 0; i < moons.size(); i++) {
                Moon m = moons.get(i);
                m.angle += m.omega * dt;
            }

            if (state == STATE_CLEAR) {
                clearTimer += dt;
                return;
            }

            if (flying) {
                float sdt = dt / SUBSTEPS;
                for (int s = 0; s < SUBSTEPS && flying; s++) {
                    stepComet(sdt);
                }
                if (flying) {
                    trackCapture(dt);
                    trailHead = (trailHead + 1) % trailX.length;
                    trailX[trailHead] = bx;
                    trailY[trailHead] = by;
                    flightTime += dt;
                    if (flightTime > FLIGHT_TIMEOUT) {
                        addText("drifted away...", width / 2f, height * 0.5f, 0x88FFFFFF);
                        sfx.play(SoundFx.POOF);
                        resetComet();
                    }
                }
            }
        }
    }

    private void stepComet(float dt) {
        float ax = 0f, ay = 0f;
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            float dx = p.x - bx, dy = p.y - by;
            float d2 = dx * dx + dy * dy;
            float minD = p.r * 0.9f;
            if (d2 < minD * minD) d2 = minD * minD;
            float d = (float) Math.sqrt(d2);
            float a = mass(p) / d2 * (p.repulse ? -1.2f : 1f);
            ax += dx / d * a;
            ay += dy / d * a;
        }
        bvx += ax * dt;
        bvy += ay * dt;
        bx += bvx * dt;
        by += bvy * dt;

        // the band cradles the comet: inside it, gravity gently rounds the
        // path toward a circular orbit so capture is forgiving, not fussy
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            if (p.repulse || p.captured >= p.needed) continue;
            float d = dist(bx, by, p.x, p.y);
            if (d < p.bandIn || d > p.bandOut) continue;
            float ux = (bx - p.x) / d, uy = (by - p.y) / d;
            float vr = bvx * ux + bvy * uy;
            float vtx = bvx - vr * ux, vty = bvy - vr * uy;
            float vt = (float) Math.sqrt(vtx * vtx + vty * vty) + 0.001f;
            float vCirc = (float) Math.sqrt(mass(p) / d);
            vr *= (1f - 2.4f * dt);
            float scale = 1f + (vCirc - vt) / vt * Math.min(1f, 1.6f * dt);
            bvx = vtx * scale + vr * ux;
            bvy = vty * scale + vr * uy;
            break;
        }

        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            if (dist(bx, by, p.x, p.y) < p.r + ballR) {
                burst(bx, by, 16, 0xFFB39DDB, 0.9f);
                sfx.play(SoundFx.POOF);
                buzz(25);
                resetComet();
                return;
            }
        }
        float m = width * 0.30f;
        if (bx < -m || bx > width + m || by < -m || by > height + m) {
            addText("lost to the void...", width / 2f, height * 0.5f, 0x88FFFFFF);
            sfx.play(SoundFx.POOF);
            resetComet();
        }
    }

    /** One full revolution inside a planet's band turns the comet into a moon. */
    private void trackCapture(float dt) {
        int inside = -1;
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            if (p.repulse || p.captured >= p.needed) continue;
            float d = dist(bx, by, p.x, p.y);
            if (d >= p.bandIn && d <= p.bandOut) {
                inside = i;
                break;
            }
        }

        if (inside < 0) {
            if (capP >= 0) {
                capGrace += dt;
                capRebase = true;
                if (capGrace > BAND_GRACE) {
                    capP = -1;
                    capSweep = 0f;
                }
            }
            return;
        }

        Planet p = planets.get(inside);
        float theta = (float) Math.atan2(by - p.y, bx - p.x);
        if (inside != capP) {
            capP = inside;
            capSweep = 0f;
            capLastTheta = theta;
            capGrace = 0f;
            capRebase = false;
            return;
        }
        capGrace = 0f;
        if (capRebase) {
            capLastTheta = theta;
            capRebase = false;
            return;
        }
        float d = theta - capLastTheta;
        while (d > TAU / 2f) d -= TAU;
        while (d < -TAU / 2f) d += TAU;
        capSweep += d;
        capLastTheta = theta;

        if (Math.abs(capSweep) >= CAPTURE_SWEEP) {
            captureMoon(p, inside);
        }
    }

    private void captureMoon(Planet p, int pi) {
        Moon m = new Moon();
        m.planet = pi;
        m.radius = dist(bx, by, p.x, p.y);
        m.angle = (float) Math.atan2(by - p.y, bx - p.x);
        float speed = (float) Math.sqrt(bvx * bvx + bvy * bvy);
        float dir = Math.signum(capSweep);
        m.omega = dir * Math.max(0.5f, speed / m.radius);
        m.hue = (p.hue + 140f + fxRng.nextFloat() * 60f) % 360f;
        m.born = levelTime;
        moons.add(m);

        p.captured++;
        p.bloom = 1f;
        moonsTotal++;
        burst(bx, by, 26, moonColor(m, 1f), 1.2f);
        addText("a moon is born", p.x, p.y - p.bandOut - width * 0.03f, 0xCCFFFFFF);
        sfx.play(SoundFx.CAPTURE);
        buzz(40);

        resetComet();

        boolean done = true;
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).captured < planets.get(i).needed) {
                done = false;
                break;
            }
        }
        if (done) {
            state = STATE_CLEAR;
            clearTimer = 0f;
            sfx.play(SoundFx.CLEAR);
            if (level >= maxLevel) {
                maxLevel = level + 1;
            }
            prefs.edit()
                    .putInt("level", level + 1)
                    .putInt("moons", moonsTotal)
                    .putInt("maxLevel", maxLevel)
                    .apply();
        } else {
            prefs.edit().putInt("moons", moonsTotal).apply();
        }
    }

    private int moonColor(Moon m, float alpha) {
        float[] hsv = {m.hue, 0.35f, 1f};
        int c = Color.HSVToColor(hsv);
        return (c & 0x00FFFFFF) | (((int) (alpha * 255f)) << 24);
    }

    private void launch() {
        float k = 3.0f;
        bvx = pullX * k;
        bvy = pullY * k;
        float sp = (float) Math.sqrt(bvx * bvx + bvy * bvy);
        float maxV = width * 1.35f;
        if (sp > maxV) {
            bvx = bvx / sp * maxV;
            bvy = bvy / sp * maxV;
        }
        flying = true;
        flightTime = 0f;
        launches++;
        sfx.play(SoundFx.LAUNCH);
        buzz(12);
    }

    private void nextLevel() {
        level++;
        state = STATE_PLAY;
        buildLevel(level);
        sfx.play(SoundFx.CLICK);
    }

    // ------------------------------------------------------------ particles

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = fxRng.nextFloat() * TAU;
            float spd = (50f + fxRng.nextFloat() * 280f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.4f + fxRng.nextFloat() * 0.5f;
            p.size = width * (0.004f + fxRng.nextFloat() * 0.006f);
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
            p.vx *= (1f - 1.6f * dt);
            p.vy *= (1f - 1.6f * dt);
        }
    }

    private void addText(String s, float x, float y, int color) {
        FloatText t = new FloatText();
        t.text = s;
        t.x = x;
        t.y = y;
        t.life = 1.4f;
        t.color = color;
        texts.add(t);
    }

    private void updateTexts(float dt) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            FloatText t = texts.get(i);
            t.life -= dt;
            t.y -= dt * height * 0.025f;
            if (t.life <= 0f) {
                texts.remove(i);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private void render(Canvas c) {
        synchronized (lock) {
            drawSky(c);

            if (state != STATE_MENU) {
                drawPlanets(c);
                drawMoons(c);
                drawPad(c);
                if (aiming && !flying) {
                    drawPreview(c);
                }
                if (flying && capP >= 0) {
                    drawCaptureArc(c);
                }
                drawComet(c);
            }
            drawParticles(c);
            drawTexts(c);

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

    private void drawSky(Canvas c) {
        c.drawColor(0xFF0A0A1E);
        // drifting nebulas - violet, teal, magenta, gold
        drawNebula(c, nebulaA, width * (0.30f + 0.06f * (float) Math.sin(menuT * 0.11f)),
                height * (0.25f + 0.04f * (float) Math.cos(menuT * 0.13f)));
        drawNebula(c, nebulaB, width * (0.75f + 0.05f * (float) Math.cos(menuT * 0.09f)),
                height * (0.62f + 0.05f * (float) Math.sin(menuT * 0.07f)));
        drawNebula(c, nebulaC, width * (0.15f + 0.05f * (float) Math.sin(menuT * 0.08f + 2f)),
                height * (0.85f + 0.03f * (float) Math.cos(menuT * 0.12f)));
        drawNebula(c, nebulaD, width * (0.85f + 0.05f * (float) Math.sin(menuT * 0.06f + 4f)),
                height * (0.10f + 0.04f * (float) Math.sin(menuT * 0.10f)));

        // stars in warm and cool tints
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 60; i++) {
            float sx = (i * 379f + 53f) % width;
            float sy = (i * 233f + 89f) % height;
            float tw = 0.5f + 0.5f * (float) Math.sin(menuT * (0.8f + i % 5 * 0.3f) + i);
            int a = (int) (16 + 46 * tw);
            int col;
            switch (i % 4) {
                case 0: col = Color.argb(a, 255, 220, 180); break;
                case 1: col = Color.argb(a, 190, 215, 255); break;
                case 2: col = Color.argb(a, 255, 190, 230); break;
                default: col = Color.argb(a, 200, 255, 230);
            }
            paint.setColor(col);
            float r = Math.max(1.2f, width * 0.0013f) * (i % 3 == 0 ? 1.8f : 1f);
            c.drawCircle(sx, sy, r, paint);
            if (i % 6 == 0) {
                paint.setColor((col & 0x00FFFFFF) | ((a / 3) << 24));
                c.drawCircle(sx, sy, r * 3f, paint);
            }
        }
    }

    private void drawNebula(Canvas c, Shader s, float x, float y) {
        if (s == null) return;
        c.save();
        c.translate(x, y);
        paint.setShader(s);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(0, 0, width * 0.85f, paint);
        paint.setShader(null);
        c.restore();
    }

    private void drawPlanets(Canvas c) {
        for (int i = 0; i < planets.size(); i++) {
            Planet p = planets.get(i);
            float[] bandHsv = {p.hue, 0.55f, 1f};
            int bandCol = Color.HSVToColor(bandHsv);

            if (!p.repulse) {
                // the capture band: a soft shimmering annulus in the
                // planet's own color
                float mid = (p.bandIn + p.bandOut) / 2f;
                float bw = p.bandOut - p.bandIn;
                float shimmer = 0.75f + 0.25f * (float) Math.sin(menuT * 2f + i);
                glowPaint.setStyle(Paint.Style.STROKE);
                glowPaint.setMaskFilter(blurBig);
                glowPaint.setStrokeWidth(bw);
                glowPaint.setColor((bandCol & 0x00FFFFFF)
                        | (((int) (22 * shimmer + p.bloom * 60)) << 24));
                c.drawCircle(p.x, p.y, mid, glowPaint);
                glowPaint.setMaskFilter(blurMed);
                glowPaint.setStrokeWidth(width * 0.004f);
                glowPaint.setColor((bandCol & 0x00FFFFFF)
                        | (((int) (80 * shimmer)) << 24));
                c.drawCircle(p.x, p.y, p.bandIn, glowPaint);
                c.drawCircle(p.x, p.y, p.bandOut, glowPaint);
                glowPaint.setMaskFilter(null);
                // fairy dust circling the band
                glowPaint.setStyle(Paint.Style.FILL);
                glowPaint.setMaskFilter(blurSmall);
                for (int d = 0; d < 16; d++) {
                    float a = menuT * 0.35f + d * TAU / 16f;
                    float rr = mid + bw * 0.30f * (float) Math.sin(menuT * 0.8f + d * 2f);
                    float[] dustHsv = {(p.hue + d * 12f) % 360f, 0.4f, 1f};
                    glowPaint.setColor((Color.HSVToColor(dustHsv) & 0x00FFFFFF)
                            | (((int) (90 * shimmer)) << 24));
                    c.drawCircle(p.x + (float) Math.cos(a) * rr,
                            p.y + (float) Math.sin(a) * rr, width * 0.003f, glowPaint);
                }
                glowPaint.setMaskFilter(null);
            }

            // body: pure gradient orb, no outlines
            if (p.glow != null) {
                c.save();
                c.translate(p.x, p.y);
                paint.setShader(p.glow);
                paint.setStyle(Paint.Style.FILL);
                float bloomK = 1f + p.bloom * 0.25f;
                c.drawCircle(0, 0, p.r * 2.4f * bloomK, paint);
                paint.setShader(null);
                c.restore();
            }
            if (p.repulse) {
                glowPaint.setStyle(Paint.Style.STROKE);
                glowPaint.setMaskFilter(blurSmall);
                glowPaint.setStrokeWidth(width * 0.006f);
                glowPaint.setColor(0xCCFFAFC2);
                c.drawLine(p.x - p.r * 0.45f, p.y, p.x + p.r * 0.45f, p.y, glowPaint);
                glowPaint.setMaskFilter(null);
            }

            // moon requirement pips: little sleeping lights
            if (p.needed > 0) {
                glowPaint.setStyle(Paint.Style.FILL);
                glowPaint.setMaskFilter(blurSmall);
                float px0 = p.x - (p.needed - 1) * width * 0.018f;
                for (int k = 0; k < p.needed; k++) {
                    boolean got = k < p.captured;
                    glowPaint.setColor(got ? 0xFFFFE6A0 : 0x66FFFFFF);
                    c.drawCircle(px0 + k * width * 0.036f, p.y,
                            width * (got ? 0.010f : 0.007f), glowPaint);
                }
                glowPaint.setMaskFilter(null);
            }
        }
    }

    private void drawMoons(Canvas c) {
        glowPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < moons.size(); i++) {
            Moon m = moons.get(i);
            Planet p = planets.get(m.planet);
            float mx = p.x + (float) Math.cos(m.angle) * m.radius;
            float my = p.y + (float) Math.sin(m.angle) * m.radius;

            // sleepy luminous trail
            glowPaint.setMaskFilter(blurSmall);
            for (int s = 1; s <= 9; s++) {
                float a = m.angle - Math.signum(m.omega) * s * 0.085f;
                float tx = p.x + (float) Math.cos(a) * m.radius;
                float ty = p.y + (float) Math.sin(a) * m.radius;
                glowPaint.setColor(moonColor(m, 0.13f * (1f - s / 10f)));
                c.drawCircle(tx, ty, ballR * 1.2f * (1f - s * 0.08f), glowPaint);
            }
            float pulse = 1f + 0.1f * (float) Math.sin(menuT * 2.4f + i);
            glowPaint.setMaskFilter(blurMed);
            glowPaint.setColor(moonColor(m, 0.45f));
            c.drawCircle(mx, my, ballR * 3.2f * pulse, glowPaint);
            glowPaint.setMaskFilter(blurSmall);
            glowPaint.setColor(moonColor(m, 1f));
            c.drawCircle(mx, my, ballR * 1.3f, glowPaint);
            glowPaint.setColor(0xCCFFFFFF);
            c.drawCircle(mx, my, ballR * 0.55f, glowPaint);
            glowPaint.setMaskFilter(null);
        }
    }

    private void drawCaptureArc(Canvas c) {
        Planet p = planets.get(capP);
        float r = dist(bx, by, p.x, p.y);
        arcRect.set(p.x - r, p.y - r, p.x + r, p.y + r);
        float sweepDeg = capSweep * 360f / TAU;
        float startDeg = (capLastTheta * 360f / TAU) - sweepDeg;
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setMaskFilter(blurMed);
        glowPaint.setStrokeWidth(width * 0.020f);
        glowPaint.setColor(0x55FFE6A0);
        c.drawArc(arcRect, startDeg, sweepDeg, false, glowPaint);
        glowPaint.setMaskFilter(blurSmall);
        glowPaint.setStrokeWidth(width * 0.006f);
        glowPaint.setColor(0xEEFFE6A0);
        c.drawArc(arcRect, startDeg, sweepDeg, false, glowPaint);
        glowPaint.setMaskFilter(null);

        // progress whisper near the planet
        float k = Math.min(1f, Math.abs(capSweep) / TAU);
        textPaint.setTextSize(width * 0.034f);
        textPaint.setColor(Color.argb((int) (120 + 100 * k), 255, 230, 160));
        c.drawText((int) (k * 100) + "%", p.x, p.y - p.bandOut - width * 0.012f, textPaint);
    }

    private void drawPad(Canvas c) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.003f);
        paint.setColor(0x44E8FBFF);
        c.drawCircle(startX, startY, width * 0.05f, paint);
        paint.setColor(0x22E8FBFF);
        c.drawCircle(startX, startY, width * 0.08f, paint);
    }

    private void drawPreview(Canvas c) {
        float sx = bx, sy = by;
        float vx = pullX * 3.0f, vy = pullY * 3.0f;
        float sp = (float) Math.sqrt(vx * vx + vy * vy);
        float maxV = width * 1.35f;
        if (sp > maxV) {
            vx = vx / sp * maxV;
            vy = vy / sp * maxV;
        }
        paint.setStyle(Paint.Style.FILL);
        float dt = 1f / 90f;
        int dots = 0;
        for (int i = 0; i < 120 && dots < 28; i++) {
            float ax = 0f, ay = 0f;
            for (int j = 0; j < planets.size(); j++) {
                Planet p = planets.get(j);
                float dx = p.x - sx, dy = p.y - sy;
                float d2 = dx * dx + dy * dy;
                float minD = p.r * 0.9f;
                if (d2 < minD * minD) d2 = minD * minD;
                float d = (float) Math.sqrt(d2);
                float a = mass(p) / d2 * (p.repulse ? -1.2f : 1f);
                ax += dx / d * a;
                ay += dy / d * a;
            }
            vx += ax * dt;
            vy += ay * dt;
            sx += vx * dt;
            sy += vy * dt;
            boolean stop = false;
            for (int j = 0; j < planets.size(); j++) {
                if (dist(sx, sy, planets.get(j).x, planets.get(j).y) < planets.get(j).r) {
                    stop = true;
                    break;
                }
            }
            if (stop || sx < -width * 0.1f || sx > width * 1.1f
                    || sy < -width * 0.1f || sy > height + width * 0.1f) {
                break;
            }
            if (i % 4 == 0) {
                float k = 1f - dots / 30f;
                paint.setColor(Color.argb((int) (190 * k), 235, 245, 255));
                c.drawCircle(sx, sy, ballR * 0.35f, paint);
                dots++;
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.004f);
        paint.setColor(0x55E8FBFF);
        c.drawLine(bx, by, bx - pullX * 0.25f, by - pullY * 0.25f, paint);
    }

    private void drawComet(Canvas c) {
        glowPaint.setStyle(Paint.Style.FILL);
        if (flying) {
            glowPaint.setMaskFilter(blurSmall);
            for (int s = 0; s < trailX.length; s++) {
                int idx = (trailHead - s + trailX.length * 2) % trailX.length;
                float k = 1f - s / (float) trailX.length;
                // the tail shifts from white-hot to violet as it fades
                int col = Color.argb((int) (80f * k),
                        (int) (180 + 75 * k), (int) (160 + 95 * k), 255);
                glowPaint.setColor(col);
                c.drawCircle(trailX[idx], trailY[idx], ballR * (0.3f + 0.9f * k), glowPaint);
            }
        }
        float breathe = 1f + 0.06f * (float) Math.sin(menuT * 3f);
        glowPaint.setMaskFilter(blurBig);
        glowPaint.setColor(0x4480C8FF);
        c.drawCircle(bx, by, ballR * 4.2f * breathe, glowPaint);
        glowPaint.setMaskFilter(blurMed);
        glowPaint.setColor(0x66BFE8FF);
        c.drawCircle(bx, by, ballR * 2.4f * breathe, glowPaint);
        glowPaint.setMaskFilter(blurSmall);
        glowPaint.setColor(0xFFF4FBFF);
        c.drawCircle(bx, by, ballR, glowPaint);
        glowPaint.setMaskFilter(null);
    }

    private void drawParticles(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float k = p.life / p.maxLife;
            // halo + core, soft without a mask filter (cheap for many dots)
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 70f)) << 24));
            c.drawCircle(p.x, p.y, p.size * 2.4f * (0.4f + 0.6f * k), paint);
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 210f)) << 24));
            c.drawCircle(p.x, p.y, p.size * (0.4f + 0.6f * k), paint);
        }
    }

    private void drawTexts(Canvas c) {
        textPaint.setTextSize(width * 0.045f);
        for (int i = 0; i < texts.size(); i++) {
            FloatText t = texts.get(i);
            int a = (int) (Math.min(1f, t.life / 0.7f) * 255f);
            textPaint.setColor((t.color & 0x00FFFFFF) | (a << 24));
            c.drawText(t.text, t.x, t.y, textPaint);
        }
    }

    private void drawHud(Canvas c) {
        textPaint.setColor(0xEEFFFFFF);
        textPaint.setTextSize(width * 0.055f);
        c.drawText("LEVEL " + level, width / 2f, height * 0.05f, textPaint);

        int need = 0, got = 0;
        for (int i = 0; i < planets.size(); i++) {
            need += planets.get(i).needed;
            got += planets.get(i).captured;
        }
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(width * 0.038f);
        textPaint.setColor(0xFFFFE6A0);
        c.drawText("☾ " + got + "/" + need, width * 0.96f, height * 0.05f, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0x88FFFFFF);
        c.drawText(launches + (launches == 1 ? " throw" : " throws"),
                width * 0.04f, height * 0.05f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        if (!flying && !aiming && launches == 0) {
            float blink = 0.35f + 0.35f * (float) Math.sin(menuT * 2.5f);
            textPaint.setTextSize(width * 0.036f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("sling the comet into the shimmering band", width / 2f, height * 0.93f, textPaint);
            c.drawText("one full circle... and it falls asleep", width / 2f, height * 0.965f, textPaint);
        }
    }

    private void drawMenu(Canvas c) {
        // a sleeping system: glowing planet, tinted band, one golden moon
        float pcx = width / 2f, pcy = height * 0.52f, pr = width * 0.085f;
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setMaskFilter(blurBig);
        glowPaint.setStrokeWidth(pr * 1.1f);
        glowPaint.setColor(0x2270C8FF);
        c.drawCircle(pcx, pcy, pr * 2.1f, glowPaint);
        glowPaint.setMaskFilter(null);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setMaskFilter(blurMed);
        glowPaint.setColor(0x664A6AE0);
        c.drawCircle(pcx, pcy, pr * 1.5f, glowPaint);
        glowPaint.setColor(0xFF5A74C8);
        c.drawCircle(pcx, pcy, pr, glowPaint);
        glowPaint.setMaskFilter(null);
        float ma = menuT * 0.9f;
        float mx = pcx + (float) Math.cos(ma) * pr * 2.1f;
        float my = pcy + (float) Math.sin(ma) * pr * 2.1f;
        glowPaint.setMaskFilter(blurMed);
        glowPaint.setColor(0x55FFE6A0);
        c.drawCircle(mx, my, ballR * 3.2f, glowPaint);
        glowPaint.setMaskFilter(blurSmall);
        glowPaint.setColor(0xFFFFE6A0);
        c.drawCircle(mx, my, ballR * 1.2f, glowPaint);
        glowPaint.setMaskFilter(null);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.16f);
        c.drawText("KEPLER", width / 2f, height * 0.21f, textPaint);

        textPaint.setTextSize(width * 0.040f);
        textPaint.setColor(0xFFC8D7FF);
        c.drawText("lull the comets into orbit", width / 2f, height * 0.26f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        textPaint.setTextSize(width * 0.036f);
        c.drawText("sling  •  let gravity cradle it  •  one full circle = a moon",
                width / 2f, height * 0.305f, textPaint);
        c.drawText("to the sound of Bach's Prelude in C", width / 2f, height * 0.34f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 3f);
        textPaint.setTextSize(width * 0.065f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText(level > 1 ? "TAP TO CONTINUE — LVL " + level : "TAP TO BEGIN",
                width / 2f, height * 0.80f, textPaint);

        textPaint.setTextSize(width * 0.04f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("☾ " + moonsTotal + " moons born      reached level " + maxLevel,
                width / 2f, height * 0.88f, textPaint);
    }

    private void drawClear(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        c.drawRect(0, 0, width, height, paint);

        textPaint.setTextSize(width * 0.075f);
        textPaint.setColor(0xFFFFE6A0);
        c.drawText("the system sleeps", width / 2f, height * 0.36f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(0xAAFFFFFF);
        c.drawText("level " + level + "  •  " + launches
                + (launches == 1 ? " throw" : " throws")
                + "  •  ☾ " + moonsTotal + " total", width / 2f, height * 0.44f, textPaint);

        if (clearTimer > 0.6f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
            textPaint.setTextSize(width * 0.058f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP FOR LEVEL " + (level + 1), width / 2f, height * 0.62f, textPaint);
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
                        sfx.play(SoundFx.CLICK);
                    }
                    break;
                case STATE_PLAY:
                    handleAim(event, action);
                    break;
                case STATE_CLEAR:
                    if (action == MotionEvent.ACTION_DOWN && clearTimer > 0.6f) {
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
            ballR = w * 0.013f;
            nebulaA = new RadialGradient(0, 0, w * 0.85f,
                    new int[]{0x4456409C, 0x22303878, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            nebulaB = new RadialGradient(0, 0, w * 0.75f,
                    new int[]{0x3C20888C, 0x18205868, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            nebulaC = new RadialGradient(0, 0, w * 0.65f,
                    new int[]{0x38883A80, 0x16402458, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            nebulaD = new RadialGradient(0, 0, w * 0.6f,
                    new int[]{0x2E946A30, 0x14583820, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            blurSmall = new BlurMaskFilter(w * 0.008f, BlurMaskFilter.Blur.NORMAL);
            blurMed = new BlurMaskFilter(w * 0.022f, BlurMaskFilter.Blur.NORMAL);
            blurBig = new BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL);
            for (int i = 0; i < planets.size(); i++) {
                Planet p = planets.get(i);
                p.glow = planetGlow(p.hue, p.r, p.repulse);
            }
            if (changed && state != STATE_MENU) {
                buildLevel(level);
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
        sfx.musicResume();
    }

    public void onPause() {
        stopThread();
        sfx.musicPause();
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
