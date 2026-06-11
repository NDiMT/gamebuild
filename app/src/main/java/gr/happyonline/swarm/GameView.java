package gr.happyonline.swarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * SWARM - shepherd a living cloud of fireflies.
 *
 * Hold the screen and the swarm flows toward your finger like a single
 * organism (real flocking: attraction + separation + wander). Thread it
 * through the sweeping obstacles: every firefly that clips one dies.
 * Glowing orbs grow the swarm - your health bar is alive, and the bigger
 * it gets, the harder it is to squeeze through the gaps. Lose every
 * firefly and the night goes dark.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_OVER = 3;

    private static final int OB_WALL = 0;    // full-height wall with a gap
    private static final int OB_BLOCK = 1;   // floating slab
    private static final int OB_BAR = 2;     // rotating laser bar
    private static final int OB_PISTON = 3;  // slab that slides up and down

    private static final int START_FLIES = 12;
    private static final int MAX_FLIES = 60;
    private static final float DEATH_SLOWMO = 1.0f;

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;

    // run state
    private int state = STATE_MENU;
    private float distance;
    private int score;
    private int bonus;
    private int orbsRun;
    private int level;
    private int perfectChain;
    private int maxSwarm;
    private float spawnTimer;
    private float overTimer;
    private float runTime;
    private boolean newBestShown;

    // input
    private boolean touching;
    private float touchX, touchY;

    // persistent stats
    private int best;
    private int games;
    private int orbsTotal;

    // presentation
    private float shake;
    private float flash;
    private float dangerT;
    private float menuT;

    private final ArrayList<Fly> flies = new ArrayList<Fly>();
    private final ArrayList<Obstacle> obstacles = new ArrayList<Obstacle>();
    private final ArrayList<Orb> orbs = new ArrayList<Orb>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader nightShader;

    private static class Fly {
        float x, y, vx, vy, phase;
    }

    private static class Obstacle {
        int type;
        float x, w, y, h;       // body rect (WALL: gap geometry below instead)
        float gapY, gapH;
        float angle, omega, len; // rotating bar
        float baseY, oscAmp, oscPhase;
        boolean killedAny;
        boolean scored;
    }

    private static class Orb {
        float x, y;
        boolean caught;
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

        prefs = context.getSharedPreferences("swarm", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        games = prefs.getInt("games", 0);
        orbsTotal = prefs.getInt("orbs", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ run

    private float scrollSpeed() {
        return width * (0.30f + 0.035f * Math.min(level, 10));
    }

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            distance = 0f;
            score = 0;
            bonus = 0;
            orbsRun = 0;
            level = 0;
            perfectChain = 0;
            spawnTimer = 1.2f;
            runTime = 0f;
            overTimer = 0f;
            newBestShown = false;
            touching = false;
            flies.clear();
            obstacles.clear();
            orbs.clear();
            particles.clear();
            texts.clear();
            for (int i = 0; i < START_FLIES; i++) {
                spawnFly(width * 0.35f, height * 0.5f);
            }
            maxSwarm = flies.size();
            sfx.play(SoundFx.START);
        }
    }

    private void spawnFly(float x, float y) {
        if (flies.size() >= MAX_FLIES) {
            return;
        }
        Fly f = new Fly();
        f.x = x + (rng.nextFloat() - 0.5f) * width * 0.06f;
        f.y = y + (rng.nextFloat() - 0.5f) * width * 0.06f;
        f.vx = (rng.nextFloat() - 0.5f) * width * 0.2f;
        f.vy = (rng.nextFloat() - 0.5f) * width * 0.2f;
        f.phase = rng.nextFloat() * 6.28f;
        flies.add(f);
    }

    private void allDead() {
        state = STATE_DYING;
        overTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.OVER);
        buzz(180);

        games++;
        orbsTotal += orbsRun;
        if (score > best) {
            best = score;
        }
        prefs.edit()
                .putInt("best", best)
                .putInt("games", games)
                .putInt("orbs", orbsTotal)
                .apply();
    }

    // ------------------------------------------------------------- spawning

    private void spawnObstacle() {
        Obstacle o = new Obstacle();
        float x = width * 1.05f;
        int roll = rng.nextInt(10);

        if (roll < 4 || level == 0) {
            o.type = OB_WALL;
            o.x = x;
            o.w = width * 0.07f;
            o.gapH = height * (0.30f - 0.014f * Math.min(level, 8));
            o.gapY = o.gapH / 2f + height * 0.06f
                    + rng.nextFloat() * (height - o.gapH - height * 0.12f);
        } else if (roll < 7) {
            o.type = OB_BLOCK;
            o.x = x;
            o.w = width * (0.12f + rng.nextFloat() * 0.10f);
            o.h = height * (0.16f + rng.nextFloat() * 0.14f);
            o.y = height * 0.08f + rng.nextFloat() * (height * 0.84f - o.h);
        } else if (roll < 9 && level >= 2) {
            o.type = OB_BAR;
            o.x = x;
            o.w = 0f;
            o.y = height * (0.25f + rng.nextFloat() * 0.5f);
            o.len = height * 0.20f;
            o.omega = (rng.nextBoolean() ? 1f : -1f) * (1.4f + rng.nextFloat() * 1.2f);
            o.angle = rng.nextFloat() * 6.28f;
        } else {
            o.type = OB_PISTON;
            o.x = x;
            o.w = width * 0.12f;
            o.h = height * 0.30f;
            o.baseY = height * 0.35f;
            o.oscAmp = height * 0.28f;
            o.oscPhase = rng.nextFloat() * 6.28f;
            o.y = o.baseY;
        }
        obstacles.add(o);

        // breathing room ahead of the new obstacle, sometimes with a snack
        if (rng.nextInt(10) < 6) {
            Orb orb = new Orb();
            orb.x = x + width * (0.35f + rng.nextFloat() * 0.25f);
            orb.y = height * (0.15f + rng.nextFloat() * 0.7f);
            orbs.add(orb);
        }
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            float timeScale = state == STATE_DYING ? 0.2f : 1f;
            float dt = rawDt * timeScale;

            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            boolean danger = state == STATE_PLAYING && flies.size() <= 3;
            dangerT = danger ? Math.min(1f, dangerT + rawDt * 3f)
                    : Math.max(0f, dangerT - rawDt * 3f);

            updateParticles(rawDt);
            updateTexts(rawDt);

            if (state == STATE_MENU) {
                updateMenuFlies(rawDt);
                return;
            }

            if (state == STATE_PLAYING || state == STATE_DYING) {
                runTime += dt;
                float v = scrollSpeed();
                distance += v * dt;
                score = (int) (distance / (width * 0.1f)) + orbsRun * 5 + bonus;

                int newLevel = (int) (distance / (width * 12f));
                if (newLevel > level) {
                    level = newLevel;
                    addText("LEVEL " + (level + 1), width / 2f, height * 0.3f, 0xFFFFD740);
                    sfx.play(SoundFx.LEVEL);
                }

                if (state == STATE_PLAYING) {
                    spawnTimer -= dt;
                    if (spawnTimer <= 0f) {
                        spawnObstacle();
                        spawnTimer = Math.max(0.95f, 1.55f - 0.05f * level)
                                + rng.nextFloat() * 0.4f;
                    }
                }

                stepObstacles(dt, v);
                stepOrbs(dt, v);
                stepFlies(dt);

                if (state == STATE_PLAYING) {
                    checkDeaths();
                    if (!newBestShown && best > 0 && score > best) {
                        newBestShown = true;
                        addText("NEW BEST!", width / 2f, height * 0.24f, 0xFFFFD740);
                        sfx.play(SoundFx.NEW_BEST);
                    }
                }
            }

            if (state == STATE_DYING) {
                overTimer -= rawDt;
                if (overTimer <= 0f) {
                    state = STATE_OVER;
                    overTimer = 0f;
                }
            } else if (state == STATE_OVER) {
                overTimer += rawDt;
            }
        }
    }

    private void updateMenuFlies(float dt) {
        // idle attractor figure-eights across the title screen
        while (flies.size() < 18) {
            spawnFly(width / 2f, height * 0.55f);
        }
        float t = menuT * 0.9f;
        float ax = width * (0.5f + 0.3f * (float) Math.sin(t));
        float ay = height * (0.55f + 0.14f * (float) Math.sin(t * 2f));
        moveSwarm(dt, true, ax, ay);
    }

    private void stepFlies(float dt) {
        moveSwarm(dt, touching, touchX, touchY);
    }

    /** Flocking: finger attraction (or centroid drift), separation, wander. */
    private void moveSwarm(float dt, boolean attract, float ax, float ay) {
        int n = flies.size();
        if (n == 0) {
            return;
        }
        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) {
            cx += flies.get(i).x;
            cy += flies.get(i).y;
        }
        cx /= n;
        cy /= n;

        float maxV = width * 1.05f;
        float sepR = width * 0.032f;
        float sepR2 = sepR * sepR;

        for (int i = 0; i < n; i++) {
            Fly f = flies.get(i);
            float fax = 0f, fay = 0f;

            if (attract) {
                float dx = ax - f.x, dy = ay - f.y;
                float d = (float) Math.sqrt(dx * dx + dy * dy) + 1f;
                float k = Math.min(1f, d / (width * 0.18f)) * width * 3.4f;
                fax += dx / d * k;
                fay += dy / d * k;
            } else {
                float dx = cx - f.x, dy = cy - f.y;
                fax += dx * 1.2f;
                fay += dy * 1.2f;
                fax += (float) Math.sin(menuT * 2.1f + f.phase) * width * 0.8f;
                fay += (float) Math.cos(menuT * 1.7f + f.phase * 1.3f) * width * 0.8f;
            }

            // personal space
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                Fly g = flies.get(j);
                float dx = f.x - g.x, dy = f.y - g.y;
                float d2 = dx * dx + dy * dy;
                if (d2 < sepR2 && d2 > 0.01f) {
                    float d = (float) Math.sqrt(d2);
                    float push = (sepR - d) / sepR * width * 4.5f;
                    fax += dx / d * push;
                    fay += dy / d * push;
                }
            }

            // soft walls
            float m = width * 0.04f;
            if (f.x < m) fax += (m - f.x) * 60f;
            if (f.x > width - m) fax -= (f.x - (width - m)) * 60f;
            if (f.y < m) fay += (m - f.y) * 60f;
            if (f.y > height - m) fay -= (f.y - (height - m)) * 60f;

            f.vx = (f.vx + fax * dt) * (1f - 1.6f * dt);
            f.vy = (f.vy + fay * dt) * (1f - 1.6f * dt);
            float sp2 = f.vx * f.vx + f.vy * f.vy;
            if (sp2 > maxV * maxV) {
                float sp = (float) Math.sqrt(sp2);
                f.vx = f.vx / sp * maxV;
                f.vy = f.vy / sp * maxV;
            }
            f.x += f.vx * dt;
            f.y += f.vy * dt;
            if (f.x < 0f) f.x = 0f;
            if (f.x > width) f.x = width;
            if (f.y < 0f) f.y = 0f;
            if (f.y > height) f.y = height;
        }
    }

    private void stepObstacles(float dt, float v) {
        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            o.x -= v * dt;
            if (o.type == OB_BAR) {
                o.angle += o.omega * dt;
            } else if (o.type == OB_PISTON) {
                o.y = o.baseY + o.oscAmp * (float) Math.sin(runTime * 1.8f + o.oscPhase);
            }

            float right = o.type == OB_BAR ? o.x + o.len : o.x + o.w;
            if (!o.scored && right < 0f) {
                o.scored = true;
                if (!o.killedAny && state == STATE_PLAYING) {
                    perfectChain++;
                    int pts = 10 * perfectChain;
                    bonus += pts;
                    addText("PERFECT x" + perfectChain + "  +" + pts,
                            width * 0.30f, height * 0.12f, 0xFF69F0AE);
                    sfx.play(SoundFx.PERFECT_0 + Math.min(perfectChain - 1, 5));
                }
            }
            if (right < -width * 0.1f) {
                obstacles.remove(i);
            }
        }
    }

    private void stepOrbs(float dt, float v) {
        for (int i = orbs.size() - 1; i >= 0; i--) {
            Orb o = orbs.get(i);
            o.x -= v * dt;
            if (o.caught) {
                o.anim += dt * 4f;
                if (o.anim >= 1f) {
                    orbs.remove(i);
                }
                continue;
            }
            if (o.x < -width * 0.05f) {
                orbs.remove(i);
                continue;
            }
            float r = width * 0.035f;
            for (int j = 0; j < flies.size(); j++) {
                Fly f = flies.get(j);
                float dx = f.x - o.x, dy = f.y - o.y;
                if (dx * dx + dy * dy < r * r) {
                    o.caught = true;
                    orbsRun++;
                    int before = flies.size();
                    spawnFly(o.x, o.y);
                    spawnFly(o.x, o.y);
                    int gained = flies.size() - before;
                    if (flies.size() > maxSwarm) maxSwarm = flies.size();
                    if (gained > 0) {
                        addText("+" + gained, o.x, o.y - width * 0.04f, 0xFF40E5FF);
                    } else {
                        bonus += 15;
                        addText("FULL +15", o.x, o.y - width * 0.04f, 0xFF40E5FF);
                    }
                    burst(o.x, o.y, 12, 0xFF40E5FF, 0.8f);
                    sfx.play(SoundFx.ORB_0 + Math.min(orbsRun % 8, 7));
                    buzz(16);
                    break;
                }
            }
        }
    }

    private void checkDeaths() {
        float killR = width * 0.008f;
        for (int i = flies.size() - 1; i >= 0; i--) {
            Fly f = flies.get(i);
            Obstacle hit = null;
            for (int j = 0; j < obstacles.size(); j++) {
                Obstacle o = obstacles.get(j);
                if (collides(o, f.x, f.y, killR)) {
                    hit = o;
                    break;
                }
            }
            if (hit != null) {
                hit.killedAny = true;
                if (perfectChain > 0) {
                    perfectChain = 0;
                }
                flies.remove(i);
                burst(f.x, f.y, 8, 0xFFFFB300, 0.6f);
                shake = Math.max(shake, 0.35f);
                sfx.play(SoundFx.POP);
                buzz(20);
            }
        }
        if (flies.isEmpty()) {
            allDead();
        }
    }

    private boolean collides(Obstacle o, float x, float y, float r) {
        if (o.type == OB_WALL) {
            if (x + r < o.x || x - r > o.x + o.w) {
                return false;
            }
            return y - r < o.gapY - o.gapH / 2f || y + r > o.gapY + o.gapH / 2f;
        }
        if (o.type == OB_BLOCK || o.type == OB_PISTON) {
            float nx = Math.max(o.x, Math.min(x, o.x + o.w));
            float ny = Math.max(o.y, Math.min(y, o.y + o.h));
            float dx = x - nx, dy = y - ny;
            return dx * dx + dy * dy < r * r;
        }
        // OB_BAR: distance from the spinning segment
        float cxp = o.x, cyp = o.y;
        float hx = (float) Math.cos(o.angle) * o.len;
        float hy = (float) Math.sin(o.angle) * o.len;
        float ax = cxp - hx, ay = cyp - hy;
        float abx = hx * 2f, aby = hy * 2f;
        float t = ((x - ax) * abx + (y - ay) * aby) / (abx * abx + aby * aby);
        t = Math.max(0f, Math.min(1f, t));
        float px = ax + abx * t, py = ay + aby * t;
        float dx = x - px, dy = y - py;
        float barR = r + width * 0.008f;
        return dx * dx + dy * dy < barR * barR;
    }

    // ------------------------------------------------------------ particles

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
            float spd = (60f + rng.nextFloat() * 320f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.35f + rng.nextFloat() * 0.4f;
            p.size = width * (0.004f + rng.nextFloat() * 0.006f);
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
        t.life = 1.0f;
        t.color = color;
        texts.add(t);
    }

    private void updateTexts(float dt) {
        for (int i = texts.size() - 1; i >= 0; i--) {
            FloatText t = texts.get(i);
            t.life -= dt;
            t.y -= dt * height * 0.05f;
            if (t.life <= 0f) {
                texts.remove(i);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private void render(Canvas c) {
        synchronized (lock) {
            c.drawColor(0xFF060810);
            if (nightShader != null) {
                paint.setShader(nightShader);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            }
            drawStars(c);

            if (shake > 0f) {
                float m = shake * shake * width * 0.02f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            drawObstacles(c);
            drawOrbs(c);
            drawParticles(c);
            drawFlies(c);
            if (touching && state == STATE_PLAYING) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.004f);
                paint.setColor(0x5540E5FF);
                c.drawCircle(touchX, touchY, width * 0.05f
                        * (1f + 0.1f * (float) Math.sin(menuT * 8f)), paint);
            }
            drawTexts(c);

            if (shake > 0f) {
                c.restore();
            }

            if (state == STATE_MENU) {
                drawMenu(c);
            } else {
                drawHud(c);
            }
            if (state == STATE_OVER) {
                drawGameOver(c);
            }

            if (dangerT > 0f) {
                float pulse = dangerT * (0.5f + 0.5f * (float) Math.sin(menuT * 7f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.02f);
                paint.setColor(Color.argb((int) (pulse * 120f), 255, 61, 88));
                c.drawRect(0, 0, width, height, paint);
            }
            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (flash * 150f), 255, 60, 80));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawStars(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        float drift = state == STATE_MENU ? menuT * width * 0.02f : distance * 0.12f;
        for (int i = 0; i < 36; i++) {
            float sx = ((i * 379f + 53f) - drift * (1 + i % 3) * 0.4f) % width;
            if (sx < 0) sx += width;
            float sy = (i * 233f + 89f) % height;
            int a = 30 + (int) (25 * Math.sin(menuT * 1.5f + i));
            paint.setColor(Color.argb(Math.max(10, a), 200, 220, 255));
            c.drawCircle(sx, sy, Math.max(1.5f, width * 0.0015f), paint);
        }
    }

    private void drawFlies(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        float r = width * 0.0065f;
        for (int i = 0; i < flies.size(); i++) {
            Fly f = flies.get(i);
            float flicker = 0.7f + 0.3f * (float) Math.sin(menuT * 6f + f.phase);
            paint.setColor(Color.argb((int) (60 * flicker), 255, 235, 130));
            c.drawCircle(f.x, f.y, r * 3.4f, paint);
            paint.setColor(Color.argb((int) (110 * flicker), 255, 240, 160));
            c.drawCircle(f.x, f.y, r * 1.9f, paint);
            paint.setColor(0xFFFFF6D0);
            c.drawCircle(f.x, f.y, r, paint);
        }
    }

    private void drawObstacles(Canvas c) {
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            if (o.type == OB_WALL) {
                float g0 = o.gapY - o.gapH / 2f;
                float g1 = o.gapY + o.gapH / 2f;
                drawSlab(c, o.x, 0, o.w, g0);
                drawSlab(c, o.x, g1, o.w, height - g1);
            } else if (o.type == OB_BLOCK || o.type == OB_PISTON) {
                drawSlab(c, o.x, o.y, o.w, o.h);
            } else {
                float hx = (float) Math.cos(o.angle) * o.len;
                float hy = (float) Math.sin(o.angle) * o.len;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.030f);
                paint.setColor(0x40FF3D58);
                c.drawLine(o.x - hx, o.y - hy, o.x + hx, o.y + hy, paint);
                paint.setStrokeWidth(width * 0.014f);
                paint.setColor(0xFFFF3D58);
                c.drawLine(o.x - hx, o.y - hy, o.x + hx, o.y + hy, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFF8093);
                c.drawCircle(o.x, o.y, width * 0.012f, paint);
            }
        }
    }

    private void drawSlab(Canvas c, float x, float y, float w, float h) {
        if (h <= 0f) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1A1430);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.006f);
        paint.setColor(0xFFFF3D58);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setStrokeWidth(width * 0.014f);
        paint.setColor(0x33FF3D58);
        c.drawRect(x, y, x + w, y + h, paint);
    }

    private void drawOrbs(Canvas c) {
        for (int i = 0; i < orbs.size(); i++) {
            Orb o = orbs.get(i);
            if (o.caught) {
                float k = 1f - o.anim;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.004f * k);
                paint.setColor(Color.argb((int) (k * 255f), 64, 229, 255));
                c.drawCircle(o.x, o.y, width * 0.025f * (1f + o.anim * 2f), paint);
                continue;
            }
            float pulse = 1f + 0.12f * (float) Math.sin(menuT * 5f + i);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x3340E5FF);
            c.drawCircle(o.x, o.y, width * 0.035f * pulse, paint);
            paint.setColor(0xFF40E5FF);
            c.drawCircle(o.x, o.y, width * 0.013f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width * 0.0045f);
            paint.setColor(0xAA40E5FF);
            c.drawCircle(o.x, o.y, width * 0.024f * pulse, paint);
        }
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
        textPaint.setTextSize(width * 0.13f);
        c.drawText(String.valueOf(score), width / 2f, height * 0.115f, textPaint);

        textPaint.setTextSize(width * 0.038f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, width / 2f, height * 0.045f, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(flies.size() <= 3 ? 0xFFFF3D58 : 0xFFFFE082);
        c.drawText("✦ " + flies.size(), width * 0.04f, height * 0.045f, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(0xFF40E5FF);
        c.drawText("LVL " + (level + 1), width * 0.96f, height * 0.045f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawMenu(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.19f);
        c.drawText("SWARM", width / 2f, height * 0.25f, textPaint);

        textPaint.setTextSize(width * 0.040f);
        textPaint.setColor(0xFFFFE082);
        c.drawText("hold to guide the fireflies", width / 2f, height * 0.305f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("every one that survives is a life  •  orbs grow the swarm",
                width / 2f, height * 0.345f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO PLAY", width / 2f, height * 0.80f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), width / 2f, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(games + " nights  •  " + orbsTotal + " orbs", width / 2f, height * 0.92f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.082f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "THE NIGHT GOES DARK", width / 2f, height * 0.30f, textPaint);

        textPaint.setTextSize(width * 0.20f);
        textPaint.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), width / 2f, height * 0.45f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(0xAAFFFFFF);
        String sub;
        if (!isBest && best > 0 && score >= best * 0.9f) {
            sub = "SO CLOSE!  best is " + best;
        } else {
            sub = "best " + best + "  •  " + rankFor(best);
        }
        c.drawText(sub, width / 2f, height * 0.51f, textPaint);
        c.drawText("biggest swarm ✦ " + maxSwarm + "  •  " + orbsRun + " orbs",
                width / 2f, height * 0.555f, textPaint);

        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", width / 2f, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 600) return "GOD MODE";
        if (s >= 400) return "LEGEND";
        if (s >= 250) return "MASTER";
        if (s >= 120) return "SHEPHERD";
        if (s >= 50) return "KEEPER";
        return "ROOKIE";
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        synchronized (lock) {
            switch (state) {
                case STATE_MENU:
                    if (action == MotionEvent.ACTION_DOWN) {
                        startRun();
                    }
                    break;
                case STATE_PLAYING:
                    if (action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_MOVE) {
                        touching = true;
                        touchX = event.getX();
                        touchY = event.getY();
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        touching = false;
                    }
                    break;
                case STATE_OVER:
                    if (action == MotionEvent.ACTION_DOWN && overTimer > 0.4f) {
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
            nightShader = new RadialGradient(w / 2f, hpx * 0.45f, Math.max(w, hpx) * 0.8f,
                    new int[]{0x221A2C50, 0x00000000, 0x66000000},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
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
