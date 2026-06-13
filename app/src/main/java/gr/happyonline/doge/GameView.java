package gr.happyonline.doge;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

/**
 * SAVE THE DOGE - draw to defend.
 *
 * A doge sits in a pit. Angry bees pour out of a hive and swarm toward
 * its face. You have a limited budget of ink: drag to draw solid lines
 * that the bees cannot cross, walling the doge inside a shield. Keep
 * every bee off the doge until the timer runs out and the swarm gives
 * up. The real version of the game those lying ads keep showing.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAY = 1;
    private static final int STATE_WIN = 2;
    private static final int STATE_LOSE = 3;

    private static final float TAU = (float) (Math.PI * 2.0);
    private static final int SUBSTEPS = 3;

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;
    private float groundY;
    private float dogeX, dogeY, dogeR, stingDist;
    private float beeR, lineR;
    private float hiveX, hiveY;

    // progress
    private int level;
    private int best;

    // run state
    private int state = STATE_MENU;
    private float inkTotal, inkUsed;
    private float surviveTime, timeLeft;
    private int beesToSpawn;
    private float spawnTimer;
    private int beesSurvived;
    private float overTimer;
    private float shake, flash;
    private float menuT;
    private float stungAnim;     // doge reaction
    private float clearBtnFlash;

    // drawing
    private Stroke active;
    private float lastPx, lastPy;
    private boolean outOfInk;

    private final ArrayList<Stroke> strokes = new ArrayList<Stroke>();
    private final ArrayList<Bee> bees = new ArrayList<Bee>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<Cloud> clouds = new ArrayList<Cloud>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmpPath = new Path();
    private Shader skyShader;

    private static class Stroke {
        final ArrayList<float[]> pts = new ArrayList<float[]>();
    }

    private static class Bee {
        float x, y, vx, vy, phase;
        boolean settling; // just spawned, flying in
    }

    private static class Particle {
        float x, y, vx, vy, life, maxLife, size;
        int color;
    }

    private static class Cloud {
        float x, y, s, spd;
    }

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        setFocusable(true);

        prefs = context.getSharedPreferences("savedoge", Context.MODE_PRIVATE);
        level = Math.max(1, prefs.getInt("level", 1));
        best = prefs.getInt("best", 1);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        inkPaint.setColor(0xFF1A1A1A);
    }

    // ------------------------------------------------------------------ run

    private int beeCount() {
        return Math.min(4 + level * 2, 28);
    }

    private float beeSpeed() {
        return width * (0.17f + 0.012f * Math.min(level, 14));
    }

    private void startLevel() {
        synchronized (lock) {
            state = STATE_PLAY;
            strokes.clear();
            bees.clear();
            particles.clear();
            active = null;
            outOfInk = false;
            inkTotal = width * (2.4f - Math.min(0.8f, level * 0.04f));
            inkUsed = 0f;
            surviveTime = 11f + Math.min(6f, level * 0.4f);
            timeLeft = surviveTime;
            beesToSpawn = beeCount();
            spawnTimer = 0f;
            beesSurvived = 0;
            stungAnim = 0f;
            overTimer = 0f;
            sfx.play(SoundFx.START);
            sfx.buzzLoop(true);
        }
    }

    private void win() {
        state = STATE_WIN;
        overTimer = 0f;
        sfx.buzzLoop(false);
        sfx.play(SoundFx.WIN);
        buzz(40);
        for (int i = 0; i < 40; i++) {
            confetti(dogeX + (rng.nextFloat() - 0.5f) * dogeR * 4f, dogeY - dogeR);
        }
        if (level >= best) {
            best = level + 1;
        }
        level++;
        prefs.edit().putInt("level", level).putInt("best", best).apply();
    }

    private void lose() {
        state = STATE_LOSE;
        overTimer = 0f;
        stungAnim = 1f;
        shake = 1f;
        flash = 1f;
        sfx.buzzLoop(false);
        sfx.play(SoundFx.STING);
        buzz(200);
    }

    // ------------------------------------------------------------- spawning

    private void spawnBee() {
        Bee b = new Bee();
        b.x = hiveX + (rng.nextFloat() - 0.5f) * dogeR;
        b.y = hiveY + dogeR * 0.4f;
        b.vx = (rng.nextFloat() - 0.5f) * beeSpeed();
        b.vy = beeSpeed() * 0.4f;
        b.phase = rng.nextFloat() * TAU;
        b.settling = true;
        bees.add(b);
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            if (clearBtnFlash > 0f) clearBtnFlash = Math.max(0f, clearBtnFlash - rawDt * 3f);
            if (stungAnim > 0f && state != STATE_LOSE) {
                stungAnim = Math.max(0f, stungAnim - rawDt * 2f);
            }
            updateClouds(rawDt);
            updateParticles(rawDt);

            if (state == STATE_PLAY) {
                timeLeft -= rawDt;

                // pour bees out of the hive over the first stretch
                if (beesToSpawn > 0) {
                    spawnTimer -= rawDt;
                    if (spawnTimer <= 0f) {
                        spawnBee();
                        beesToSpawn--;
                        spawnTimer = 0.28f;
                    }
                }

                float sdt = rawDt / SUBSTEPS;
                for (int s = 0; s < SUBSTEPS; s++) {
                    stepBees(sdt);
                }

                if (timeLeft <= 0f && beesToSpawn == 0) {
                    win();
                }
            } else if (state == STATE_WIN || state == STATE_LOSE) {
                overTimer += rawDt;
                // bees keep buzzing around on the end screens
                float sdt = rawDt / SUBSTEPS;
                for (int s = 0; s < SUBSTEPS; s++) {
                    stepBees(sdt);
                }
            } else if (state == STATE_MENU) {
                float sdt = rawDt / SUBSTEPS;
                for (int s = 0; s < SUBSTEPS; s++) {
                    stepBees(sdt);
                }
            }
        }
    }

    private void stepBees(float dt) {
        boolean playing = state == STATE_PLAY;
        for (int i = 0; i < bees.size(); i++) {
            Bee b = bees.get(i);

            // steer toward the doge with a little wandering wobble
            float tx = dogeX, ty = dogeY;
            float dx = tx - b.x, dy = ty - b.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
            float spd = beeSpeed();
            b.phase += dt * 9f;
            float wob = (float) Math.sin(b.phase) * spd * 0.35f;
            float desX = dx / d * spd - dy / d * wob;
            float desY = dy / d * spd + dx / d * wob;
            float steer = playing ? 6f : 2f;
            b.vx += (desX - b.vx) * Math.min(1f, steer * dt);
            b.vy += (desY - b.vy) * Math.min(1f, steer * dt);

            float bx = b.x + b.vx * dt;
            float by = b.y + b.vy * dt;

            // collide with the drawn ink walls: slide along them
            for (int si = 0; si < strokes.size(); si++) {
                ArrayList<float[]> pts = strokes.get(si).pts;
                for (int p = 0; p + 1 < pts.size(); p++) {
                    float[] a = pts.get(p), c = pts.get(p + 1);
                    float[] near = closest(bx, by, a[0], a[1], c[0], c[1]);
                    float ndx = bx - near[0], ndy = by - near[1];
                    float nd = (float) Math.sqrt(ndx * ndx + ndy * ndy);
                    float minD = beeR + lineR;
                    if (nd < minD && nd > 0.0001f) {
                        float push = (minD - nd);
                        float nx = ndx / nd, ny = ndy / nd;
                        bx += nx * push;
                        by += ny * push;
                        float vn = b.vx * nx + b.vy * ny;
                        if (vn < 0f) {
                            b.vx -= nx * vn;
                            b.vy -= ny * vn;
                        }
                    }
                }
            }

            // the ground is solid: bees can't burrow under the doge
            if (by > groundY - beeR) { by = groundY - beeR; b.vy = -Math.abs(b.vy) * 0.6f; }
            // keep bees on screen so they keep pressing
            if (bx < beeR) { bx = beeR; b.vx = Math.abs(b.vx); }
            if (bx > width - beeR) { bx = width - beeR; b.vx = -Math.abs(b.vx); }
            if (by < beeR) { by = beeR; b.vy = Math.abs(b.vy); }

            b.x = bx;
            b.y = by;
            b.settling = false;

            // reached the doge?
            if (playing) {
                float fdx = b.x - dogeX, fdy = b.y - dogeY;
                if (fdx * fdx + fdy * fdy < stingDist * stingDist) {
                    lose();
                    return;
                }
            }
        }
    }

    private static float[] closest(float px, float py,
                                   float ax, float ay, float bx, float by) {
        float abx = bx - ax, aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 > 0f ? ((px - ax) * abx + (py - ay) * aby) / len2 : 0f;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return new float[]{ax + abx * t, ay + aby * t};
    }

    // ------------------------------------------------------------- drawing

    private void touchDown(float x, float y) {
        // tapping the clear button wipes all ink so you can replan
        if (state == STATE_PLAY && x > width * 0.78f && y < height * 0.12f
                && !strokes.isEmpty()) {
            strokes.clear();
            inkUsed = 0f;
            outOfInk = false;
            clearBtnFlash = 1f;
            sfx.play(SoundFx.CLEAR);
            return;
        }
        if (state != STATE_PLAY || inkUsed >= inkTotal) {
            return;
        }
        active = new Stroke();
        active.pts.add(new float[]{x, y});
        lastPx = x;
        lastPy = y;
        strokes.add(active);
        sfx.play(SoundFx.DRAW);
    }

    private void touchMove(float x, float y) {
        if (active == null) {
            return;
        }
        float dx = x - lastPx, dy = y - lastPy;
        float seg = (float) Math.sqrt(dx * dx + dy * dy);
        if (seg < width * 0.012f) {
            return;
        }
        if (inkUsed + seg > inkTotal) {
            // clamp the final segment to the remaining ink, then stop
            float remain = inkTotal - inkUsed;
            if (remain > 1f) {
                float k = remain / seg;
                float ex = lastPx + dx * k, ey = lastPy + dy * k;
                active.pts.add(new float[]{ex, ey});
                inkUsed = inkTotal;
            }
            active = null;
            outOfInk = true;
            return;
        }
        active.pts.add(new float[]{x, y});
        inkUsed += seg;
        lastPx = x;
        lastPy = y;
    }

    private void touchUp() {
        active = null;
    }

    // ------------------------------------------------------------ particles

    private void confetti(float x, float y) {
        Particle p = new Particle();
        p.x = x;
        p.y = y;
        float ang = -TAU / 4f + (rng.nextFloat() - 0.5f) * 1.6f;
        float spd = width * (0.3f + rng.nextFloat() * 0.6f);
        p.vx = (float) Math.cos(ang) * spd;
        p.vy = (float) Math.sin(ang) * spd;
        p.maxLife = p.life = 0.8f + rng.nextFloat() * 0.7f;
        p.size = width * (0.008f + rng.nextFloat() * 0.012f);
        float[] hsv = {rng.nextFloat() * 360f, 0.7f, 1f};
        p.color = Color.HSVToColor(hsv);
        particles.add(p);
    }

    private void updateParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                particles.remove(i);
                continue;
            }
            p.vy += height * 1.4f * dt; // gravity for confetti
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }
    }

    private void updateClouds(float dt) {
        for (int i = 0; i < clouds.size(); i++) {
            Cloud cl = clouds.get(i);
            cl.x += cl.spd * dt;
            if (cl.x > width + cl.s) {
                cl.x = -cl.s;
                cl.y = height * (0.06f + rng.nextFloat() * 0.30f);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private void render(Canvas c) {
        synchronized (lock) {
            drawSky(c);

            if (shake > 0f) {
                float m = shake * shake * width * 0.02f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            drawClouds(c);
            drawGround(c);
            drawHive(c);
            drawInk(c);
            drawDoge(c);
            drawBees(c);
            drawParticles(c);

            if (shake > 0f) {
                c.restore();
            }

            if (state == STATE_PLAY) {
                drawHud(c);
            } else if (state == STATE_MENU) {
                drawMenu(c);
            } else if (state == STATE_WIN) {
                drawWin(c);
            } else if (state == STATE_LOSE) {
                drawLose(c);
            }

            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (flash * 120f), 255, 60, 60));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawSky(Canvas c) {
        if (skyShader != null) {
            paint.setShader(skyShader);
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, width, height, paint);
            paint.setShader(null);
        } else {
            c.drawColor(0xFF8FD3F0);
        }
    }

    private void drawClouds(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < clouds.size(); i++) {
            Cloud cl = clouds.get(i);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cl.x, cl.y, cl.s * 0.6f, paint);
            c.drawCircle(cl.x + cl.s * 0.55f, cl.y + cl.s * 0.12f, cl.s * 0.45f, paint);
            c.drawCircle(cl.x - cl.s * 0.55f, cl.y + cl.s * 0.12f, cl.s * 0.42f, paint);
            c.drawRoundRect(cl.x - cl.s * 0.7f, cl.y + cl.s * 0.05f,
                    cl.x + cl.s * 0.7f, cl.y + cl.s * 0.5f, cl.s * 0.3f, cl.s * 0.3f, paint);
        }
    }

    private void drawGround(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        // dirt
        paint.setColor(0xFF8A5A33);
        c.drawRect(0, groundY, width, height, paint);
        // little dirt mound the doge sits on
        paint.setColor(0xFF7A4E2C);
        c.drawCircle(dogeX, groundY + dogeR * 0.6f, dogeR * 1.7f, paint);
        // grass lip with scalloped tufts
        paint.setColor(0xFF5DA52E);
        for (float gx = 0; gx < width + width * 0.045f; gx += width * 0.045f) {
            c.drawCircle(gx, groundY, width * 0.024f, paint);
        }
        paint.setColor(0xFF7DC242);
        c.drawRect(0, groundY, width, groundY + height * 0.01f, paint);
        // faint texture ticks in the dirt
        paint.setColor(0x33000000);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.003f);
        for (int i = 0; i < 10; i++) {
            float tx = (i * 521f % width);
            float ty = groundY + height * (0.05f + (i % 4) * 0.04f);
            c.drawLine(tx, ty, tx + width * 0.02f, ty - width * 0.01f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHive(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        float r = dogeR * 0.7f;
        // string
        paint.setColor(0xFF333333);
        paint.setStrokeWidth(width * 0.006f);
        c.drawLine(hiveX, 0, hiveX, hiveY - r * 0.8f, paint);
        // stacked hive
        paint.setColor(0xFFE6A93C);
        for (int i = 0; i < 3; i++) {
            float ry = hiveY + i * r * 0.5f;
            c.drawRoundRect(hiveX - r * (1f - i * 0.12f), ry - r * 0.3f,
                    hiveX + r * (1f - i * 0.12f), ry + r * 0.35f, r * 0.4f, r * 0.4f, paint);
        }
        paint.setColor(0xFFC8862A);
        for (int i = 0; i < 3; i++) {
            float ry = hiveY + i * r * 0.5f - r * 0.05f;
            c.drawLine(hiveX - r * 0.9f, ry, hiveX + r * 0.9f, ry, paint);
        }
        // entrance hole
        paint.setColor(0xFF3A2410);
        c.drawCircle(hiveX, hiveY + r * 0.55f, r * 0.28f, paint);
    }

    private void drawInk(Canvas c) {
        inkPaint.setStrokeWidth(lineR * 2f);
        for (int si = 0; si < strokes.size(); si++) {
            ArrayList<float[]> pts = strokes.get(si).pts;
            if (pts.size() == 1) {
                float[] a = pts.get(0);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF1A1A1A);
                c.drawCircle(a[0], a[1], lineR, paint);
                continue;
            }
            tmpPath.reset();
            float[] a0 = pts.get(0);
            tmpPath.moveTo(a0[0], a0[1]);
            for (int p = 1; p < pts.size(); p++) {
                float[] a = pts.get(p);
                tmpPath.lineTo(a[0], a[1]);
            }
            c.drawPath(tmpPath, inkPaint);
        }
    }

    private void drawDoge(Canvas c) {
        float scared = Math.max(stungAnim, state == STATE_PLAY ? threatLevel() : 0f);
        float jx = scared > 0.01f ? (rng.nextFloat() - 0.5f) * dogeR * 0.06f * scared : 0f;
        float cx = dogeX + jx, cy = dogeY;

        // ears
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE8A23C);
        tmpPath.reset();
        tmpPath.moveTo(cx - dogeR * 0.78f, cy - dogeR * 0.45f);
        tmpPath.lineTo(cx - dogeR * 0.35f, cy - dogeR * 1.15f);
        tmpPath.lineTo(cx - dogeR * 0.05f, cy - dogeR * 0.55f);
        tmpPath.close();
        c.drawPath(tmpPath, paint);
        tmpPath.reset();
        tmpPath.moveTo(cx + dogeR * 0.78f, cy - dogeR * 0.45f);
        tmpPath.lineTo(cx + dogeR * 0.35f, cy - dogeR * 1.15f);
        tmpPath.lineTo(cx + dogeR * 0.05f, cy - dogeR * 0.55f);
        tmpPath.close();
        c.drawPath(tmpPath, paint);

        // head
        paint.setColor(0xFFF6C544);
        c.drawCircle(cx, cy, dogeR, paint);
        paint.setColor(0xFFF1B82E);
        c.drawCircle(cx, cy + dogeR * 0.18f, dogeR * 0.82f, paint);
        paint.setColor(0xFFF9D560);
        c.drawCircle(cx, cy - dogeR * 0.12f, dogeR * 0.78f, paint);

        // cheeks
        paint.setColor(0x55FF8DA0);
        c.drawCircle(cx - dogeR * 0.5f, cy + dogeR * 0.25f, dogeR * 0.22f, paint);
        c.drawCircle(cx + dogeR * 0.5f, cy + dogeR * 0.25f, dogeR * 0.22f, paint);

        boolean dead = state == STATE_LOSE;
        // eyes
        paint.setColor(0xFF20140A);
        if (dead) {
            // shocked spirals -> simple big shocked eyes with cross
            float er = dogeR * 0.16f;
            drawX(c, cx - dogeR * 0.32f, cy - dogeR * 0.05f, er);
            drawX(c, cx + dogeR * 0.32f, cy - dogeR * 0.05f, er);
        } else {
            // smug half-lidded doge eyes, pupils glance at nearest bee
            float lookX = 0f, lookY = 0f;
            Bee nb = nearestBee();
            if (nb != null) {
                float ddx = nb.x - cx, ddy = nb.y - cy;
                float dd = (float) Math.sqrt(ddx * ddx + ddy * ddy) + 0.001f;
                lookX = ddx / dd * dogeR * 0.06f;
                lookY = ddy / dd * dogeR * 0.05f;
            }
            drawEye(c, cx - dogeR * 0.32f, cy - dogeR * 0.05f, lookX, lookY);
            drawEye(c, cx + dogeR * 0.32f, cy - dogeR * 0.05f, lookX, lookY);
        }

        // snout: nose + mouth
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF20140A);
        c.drawCircle(cx, cy + dogeR * 0.30f, dogeR * 0.07f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dogeR * 0.05f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (dead) {
            // open worried mouth
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF7A2030);
            c.drawCircle(cx, cy + dogeR * 0.52f, dogeR * 0.16f, paint);
        } else {
            tmpPath.reset();
            tmpPath.moveTo(cx - dogeR * 0.16f, cy + dogeR * 0.45f);
            tmpPath.quadTo(cx, cy + dogeR * 0.40f, cx, cy + dogeR * 0.46f);
            tmpPath.quadTo(cx, cy + dogeR * 0.40f, cx + dogeR * 0.16f, cy + dogeR * 0.45f);
            c.drawPath(tmpPath, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // freckle dots
        paint.setColor(0x66000000);
        for (int s = -1; s <= 1; s += 2) {
            float bx = cx + s * dogeR * 0.5f;
            c.drawCircle(bx - dogeR * 0.08f, cy + dogeR * 0.18f, dogeR * 0.015f, paint);
            c.drawCircle(bx + dogeR * 0.0f, cy + dogeR * 0.27f, dogeR * 0.015f, paint);
            c.drawCircle(bx + dogeR * 0.08f, cy + dogeR * 0.18f, dogeR * 0.015f, paint);
        }
    }

    private void drawEye(Canvas c, float ex, float ey, float lookX, float lookY) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(ex, ey, dogeR * 0.16f, paint);
        paint.setColor(0xFF20140A);
        c.drawCircle(ex + lookX, ey + lookY, dogeR * 0.085f, paint);
        // smug upper lid
        paint.setColor(0xFFF9D560);
        c.drawRect(ex - dogeR * 0.18f, ey - dogeR * 0.2f,
                ex + dogeR * 0.18f, ey - dogeR * 0.02f, paint);
    }

    private void drawX(Canvas c, float ex, float ey, float r) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dogeR * 0.05f);
        paint.setColor(0xFF20140A);
        c.drawLine(ex - r, ey - r, ex + r, ey + r, paint);
        c.drawLine(ex - r, ey + r, ex + r, ey - r, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBees(Canvas c) {
        for (int i = 0; i < bees.size(); i++) {
            Bee b = bees.get(i);
            float ang = (float) Math.atan2(b.vy, b.vx);
            c.save();
            c.translate(b.x, b.y);
            c.rotate((float) Math.toDegrees(ang));
            // wings
            float flap = 0.6f + 0.4f * (float) Math.abs(Math.sin(menuT * 40f + b.phase));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x88EAF6FF);
            c.drawCircle(-beeR * 0.1f, -beeR * 1.1f * flap, beeR * 0.7f, paint);
            c.drawCircle(-beeR * 0.1f, beeR * 1.1f * flap, beeR * 0.7f, paint);
            // body
            paint.setColor(0xFFF7C200);
            c.drawCircle(0, 0, beeR, paint);
            paint.setColor(0xFF222222);
            c.drawRect(-beeR * 0.25f, -beeR, beeR * 0.1f, beeR, paint);
            c.drawRect(beeR * 0.45f, -beeR * 0.8f, beeR * 0.75f, beeR * 0.8f, paint);
            // head + stinger
            paint.setColor(0xFF333333);
            c.drawCircle(-beeR * 0.9f, 0, beeR * 0.45f, paint);
            c.drawRect(beeR, -beeR * 0.08f, beeR * 1.5f, beeR * 0.08f, paint);
            c.restore();
        }
    }

    private void drawParticles(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float k = Math.min(1f, p.life / 0.4f);
            c.save();
            c.rotate(p.x + p.y, p.x, p.y);
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 255f)) << 24));
            c.drawRect(p.x - p.size, p.y - p.size * 0.5f,
                    p.x + p.size, p.y + p.size * 0.5f, paint);
            c.restore();
        }
    }

    private float threatLevel() {
        Bee nb = nearestBee();
        if (nb == null) return 0f;
        float d = (float) Math.hypot(nb.x - dogeX, nb.y - dogeY);
        return Math.max(0f, Math.min(1f, 1f - d / (dogeR * 4f)));
    }

    private Bee nearestBee() {
        Bee best = null;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < bees.size(); i++) {
            Bee b = bees.get(i);
            float d = (b.x - dogeX) * (b.x - dogeX) + (b.y - dogeY) * (b.y - dogeY);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    private void drawHud(Canvas c) {
        // timer
        textPaint.setColor(0xFF1A1A1A);
        textPaint.setTextSize(width * 0.11f);
        c.drawText(String.valueOf((int) Math.ceil(timeLeft)), width / 2f, height * 0.10f, textPaint);
        textPaint.setTextSize(width * 0.032f);
        textPaint.setColor(0x99000000);
        c.drawText("LEVEL " + level, width / 2f, height * 0.135f, textPaint);

        // ink bar
        float bw = width * 0.5f, bx = width / 2f - bw / 2f, by = height * 0.16f;
        float k = 1f - inkUsed / inkTotal;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        c.drawRoundRect(bx, by, bx + bw, by + height * 0.012f,
                height * 0.006f, height * 0.006f, paint);
        paint.setColor(k > 0.25f ? 0xFF2C2C2C : 0xFFD13030);
        c.drawRoundRect(bx, by, bx + bw * k, by + height * 0.012f,
                height * 0.006f, height * 0.006f, paint);
        textPaint.setTextSize(width * 0.026f);
        textPaint.setColor(0x99000000);
        c.drawText(outOfInk ? "OUT OF INK — tap CLEAR to redraw" : "INK",
                width / 2f, by - height * 0.006f, textPaint);

        // clear button
        float cbx = width * 0.88f, cby = height * 0.07f;
        paint.setColor(clearBtnFlash > 0f ? 0xFFFFFFFF : 0xCCFFFFFF);
        c.drawCircle(cbx, cby, width * 0.06f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.008f);
        paint.setColor(0xFF1A1A1A);
        c.drawArc(cbx - width * 0.03f, cby - width * 0.03f,
                cbx + width * 0.03f, cby + width * 0.03f, 40, 280, false, paint);
        // arrow head
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(cbx + width * 0.022f, cby - width * 0.018f, width * 0.012f, paint);

        if (!strokes.isEmpty() || active != null) {
            // nothing
        } else if (timeLeft > surviveTime - 2.2f) {
            textPaint.setTextSize(width * 0.04f);
            textPaint.setColor(0xCC000000);
            c.drawText("draw a wall to shield the doge!", width / 2f, height * 0.78f, textPaint);
        }
    }

    private void drawMenu(Canvas c) {
        // dim
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        c.drawRect(0, 0, width, height, paint);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(width * 0.13f);
        c.drawText("SAVE THE DOGE", width / 2f, height * 0.30f, textPaint);
        textPaint.setTextSize(width * 0.045f);
        textPaint.setColor(0xFFFFF2C0);
        c.drawText("draw lines to block the bees", width / 2f, height * 0.36f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.075f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText(level > 1 ? "TAP — LEVEL " + level : "TAP TO PLAY",
                width / 2f, height * 0.5f, textPaint);

        textPaint.setTextSize(width * 0.04f);
        textPaint.setColor(0xCCFFFFFF);
        c.drawText("best: level " + best, width / 2f, height * 0.86f, textPaint);
    }

    private void drawWin(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        c.drawRect(0, 0, width, height, paint);
        textPaint.setColor(0xFF7DFF8A);
        textPaint.setTextSize(width * 0.12f);
        c.drawText("SAVED!", width / 2f, height * 0.34f, textPaint);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(width * 0.05f);
        c.drawText("the bees gave up", width / 2f, height * 0.41f, textPaint);
        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.07f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP FOR LEVEL " + level, width / 2f, height * 0.55f, textPaint);
        }
    }

    private void drawLose(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x77000000);
        c.drawRect(0, 0, width, height, paint);
        textPaint.setColor(0xFFFF6B6B);
        textPaint.setTextSize(width * 0.12f);
        c.drawText("OUCH!", width / 2f, height * 0.34f, textPaint);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(width * 0.05f);
        c.drawText("a bee got through", width / 2f, height * 0.41f, textPaint);
        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.07f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", width / 2f, height * 0.55f, textPaint);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        float x = event.getX(), y = event.getY();
        synchronized (lock) {
            switch (state) {
                case STATE_MENU:
                    if (action == MotionEvent.ACTION_DOWN) {
                        startLevel();
                    }
                    break;
                case STATE_PLAY:
                    if (action == MotionEvent.ACTION_DOWN) {
                        touchDown(x, y);
                    } else if (action == MotionEvent.ACTION_MOVE) {
                        touchMove(x, y);
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        touchUp();
                    }
                    break;
                case STATE_WIN:
                case STATE_LOSE:
                    if (action == MotionEvent.ACTION_DOWN && overTimer > 0.4f) {
                        startLevel();
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
            groundY = hpx * 0.74f;
            dogeR = w * 0.085f;
            dogeX = w * 0.5f;
            dogeY = groundY - dogeR * 0.92f;   // sitting on the ground
            stingDist = dogeR * 0.98f;
            beeR = w * 0.019f;
            lineR = w * 0.013f;
            hiveX = w * 0.16f;
            hiveY = hpx * 0.09f;
            skyShader = new LinearGradient(0, 0, 0, groundY,
                    new int[]{0xFF7EC8E8, 0xFFA9DDF2}, null, Shader.TileMode.CLAMP);
            clouds.clear();
            for (int i = 0; i < 4; i++) {
                Cloud cl = new Cloud();
                cl.s = w * (0.06f + rng.nextFloat() * 0.05f);
                cl.x = rng.nextFloat() * w;
                cl.y = hpx * (0.06f + rng.nextFloat() * 0.30f);
                cl.spd = w * (0.01f + rng.nextFloat() * 0.02f);
                clouds.add(cl);
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
        if (state == STATE_PLAY) {
            sfx.buzzLoop(true);
        }
    }

    public void onPause() {
        stopThread();
        sfx.buzzLoop(false);
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
