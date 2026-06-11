package gr.happyonline.tether;

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
 * TETHER - the bond is the blade.
 *
 * Two orbs joined by a cord of light. You drag the white one; its cyan
 * partner swings behind with real momentum, like a flail. Anything that
 * crosses the cord is cut in half, and the partner orb itself crushes
 * shadows when it is flying fast enough. Keep your own orb out of their
 * claws, spin up the partner, and mow through the waves.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_OVER = 3;

    private static final int E_CHASER = 0;   // walks at you
    private static final int E_DARTER = 1;   // pauses, then lunges
    private static final int E_SPLITTER = 2; // splits into two minis
    private static final int E_MINI = 3;     // fast little fragment
    private static final int E_TANK = 4;     // needs two cuts

    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float COMBO_WINDOW = 1.2f;
    private static final int MAX_COMBO = 8;
    private static final int MAX_HEARTS = 3;
    private static final float WAVE_LEN = 15f;
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
    private float playerR, partnerR, cordR, restLen, killSpeed;

    // run state
    private int state = STATE_MENU;
    private float runTime;
    private int wave;
    private int score;
    private int kills;
    private int hearts;
    private int combo;
    private float comboTimer;
    private float hurtInvuln;
    private float spawnTimer;
    private float overTimer;
    private boolean newBestShown;

    // the pair
    private float px, py;          // player orb (dragged)
    private float qx, qy, qvx, qvy; // partner orb (physics)
    private float lastTx, lastTy;
    private boolean dragging;
    private final float[] trailX = new float[12];
    private final float[] trailY = new float[12];
    private int trailHead;

    // persistent stats
    private int best;
    private int games;
    private int killsTotal;

    // presentation
    private float shake;
    private float flash;
    private float menuT;

    private final ArrayList<Enemy> enemies = new ArrayList<Enemy>();
    private final ArrayList<Gem> gems = new ArrayList<Gem>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cordPath = new Path();
    private Shader arenaShader;

    private static class Enemy {
        int type;
        float x, y, vx, vy;
        int hp;
        float phase;       // darter rhythm / wobble
        float hitCooldown; // i-frames after a cut (tanks)
    }

    private static class Gem {
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

        prefs = context.getSharedPreferences("tether", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        games = prefs.getInt("games", 0);
        killsTotal = prefs.getInt("kills", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ run

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            runTime = 0f;
            wave = 1;
            score = 0;
            kills = 0;
            hearts = MAX_HEARTS;
            combo = 0;
            comboTimer = 0f;
            hurtInvuln = 1.0f;
            spawnTimer = 0.8f;
            overTimer = 0f;
            newBestShown = false;
            dragging = false;
            px = width / 2f;
            py = height * 0.6f;
            qx = px;
            qy = py - restLen;
            qvx = 0f;
            qvy = 0f;
            enemies.clear();
            gems.clear();
            particles.clear();
            texts.clear();
            resetTrail();
            addText("WAVE 1", width / 2f, height * 0.3f, 0xFF40E5FF);
            sfx.play(SoundFx.START);
        }
    }

    private void resetTrail() {
        for (int i = 0; i < trailX.length; i++) {
            trailX[i] = qx;
            trailY[i] = qy;
        }
        trailHead = 0;
    }

    private void gameOver() {
        state = STATE_DYING;
        overTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.OVER);
        buzz(200);
        burst(px, py, 46, 0xFFFFFFFF, 1.7f);

        games++;
        killsTotal += kills;
        if (score > best) {
            best = score;
        }
        prefs.edit()
                .putInt("best", best)
                .putInt("games", games)
                .putInt("kills", killsTotal)
                .apply();
    }

    // ------------------------------------------------------------- spawning

    private void spawnEnemy() {
        Enemy e = new Enemy();
        // spawn just beyond a random edge
        float m = width * 0.06f;
        switch (rng.nextInt(4)) {
            case 0: e.x = -m; e.y = rng.nextFloat() * height; break;
            case 1: e.x = width + m; e.y = rng.nextFloat() * height; break;
            case 2: e.x = rng.nextFloat() * width; e.y = -m; break;
            default: e.x = rng.nextFloat() * width; e.y = height + m;
        }
        int roll = rng.nextInt(10);
        if (wave >= 4 && roll < 2) {
            e.type = E_TANK;
            e.hp = 2;
        } else if (wave >= 3 && roll < 4) {
            e.type = E_DARTER;
            e.hp = 1;
        } else if (wave >= 2 && roll < 6) {
            e.type = E_SPLITTER;
            e.hp = 1;
        } else {
            e.type = E_CHASER;
            e.hp = 1;
        }
        e.phase = rng.nextFloat() * TAU;
        enemies.add(e);
    }

    private void spawnMini(float x, float y) {
        Enemy e = new Enemy();
        e.type = E_MINI;
        e.hp = 1;
        e.x = x + (rng.nextFloat() - 0.5f) * width * 0.04f;
        e.y = y + (rng.nextFloat() - 0.5f) * width * 0.04f;
        e.phase = rng.nextFloat() * TAU;
        enemies.add(e);
    }

    private float enemySpeed(Enemy e) {
        float s;
        switch (e.type) {
            case E_DARTER: s = 0.055f; break;
            case E_SPLITTER: s = 0.085f; break;
            case E_MINI: s = 0.155f; break;
            case E_TANK: s = 0.048f; break;
            default: s = 0.085f;
        }
        return width * s * (1f + 0.06f * (wave - 1));
    }

    private float enemyRadius(Enemy e) {
        switch (e.type) {
            case E_TANK: return width * 0.040f;
            case E_SPLITTER: return width * 0.028f;
            case E_MINI: return width * 0.016f;
            case E_DARTER: return width * 0.022f;
            default: return width * 0.024f;
        }
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            float timeScale = state == STATE_DYING ? 0.18f : 1f;
            float dt = rawDt * timeScale;

            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            if (hurtInvuln > 0f) hurtInvuln -= rawDt;

            updateParticles(rawDt);
            updateTexts(rawDt);

            if (state == STATE_MENU) {
                // idle demo: the pair waltzes by itself
                px = width * (0.5f + 0.22f * (float) Math.sin(menuT * 1.3f));
                py = height * (0.58f + 0.10f * (float) Math.cos(menuT * 1.7f));
                stepPartner(dt);
                pushTrail();
                return;
            }

            if (state == STATE_PLAYING || state == STATE_DYING) {
                stepPartner(dt);
                pushTrail();
            }

            if (state == STATE_PLAYING) {
                runTime += dt;

                int newWave = 1 + (int) (runTime / WAVE_LEN);
                if (newWave > wave) {
                    wave = newWave;
                    addText("WAVE " + wave, width / 2f, height * 0.3f, 0xFF40E5FF);
                    sfx.play(SoundFx.WAVE);
                }

                if (combo > 0) {
                    comboTimer -= dt;
                    if (comboTimer <= 0f) {
                        combo = 0;
                    }
                }

                spawnTimer -= dt;
                if (spawnTimer <= 0f) {
                    spawnEnemy();
                    if (wave >= 3 && rng.nextInt(10) < 3) {
                        spawnEnemy();
                    }
                    spawnTimer = Math.max(0.45f, 1.5f - 0.1f * wave)
                            * (0.8f + rng.nextFloat() * 0.4f);
                }

                stepEnemies(dt);
                stepGems(dt);

                if (!newBestShown && best > 0 && score > best) {
                    newBestShown = true;
                    addText("NEW BEST!", width / 2f, height * 0.24f, 0xFFFFD740);
                    sfx.play(SoundFx.NEW_BEST);
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

    /** Rope physics: tension only, low damping, so the partner whips. */
    private void stepPartner(float dt) {
        float dx = px - qx, dy = py - qy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
        if (dist > restLen) {
            // tension accelerates the partner along the cord
            float pull = (dist - restLen) * 30f;
            qvx += dx / dist * pull * dt;
            qvy += dy / dist * pull * dt;
        }
        qvx *= (1f - 0.55f * dt);
        qvy *= (1f - 0.55f * dt);
        qx += qvx * dt;
        qy += qvy * dt;

        // soft wall bounce keeps the flail in play
        if (qx < partnerR) { qx = partnerR; qvx = Math.abs(qvx) * 0.55f; }
        if (qx > width - partnerR) { qx = width - partnerR; qvx = -Math.abs(qvx) * 0.55f; }
        if (qy < partnerR) { qy = partnerR; qvy = Math.abs(qvy) * 0.55f; }
        if (qy > height - partnerR) { qy = height - partnerR; qvy = -Math.abs(qvy) * 0.55f; }

        // hard rope limit so it can never stretch absurdly
        dx = qx - px;
        dy = qy - py;
        dist = (float) Math.sqrt(dx * dx + dy * dy);
        float maxLen = restLen * 1.9f;
        if (dist > maxLen) {
            qx = px + dx / dist * maxLen;
            qy = py + dy / dist * maxLen;
        }
    }

    private void pushTrail() {
        trailHead = (trailHead + 1) % trailX.length;
        trailX[trailHead] = qx;
        trailY[trailHead] = qy;
    }

    private float partnerSpeed() {
        return (float) Math.sqrt(qvx * qvx + qvy * qvy);
    }

    private void stepEnemies(float dt) {
        boolean lethalOrb = partnerSpeed() > killSpeed;

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            if (e.hitCooldown > 0f) {
                e.hitCooldown -= dt;
            }

            // movement: hunt the white orb
            float dx = px - e.x, dy = py - e.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
            float v = enemySpeed(e);
            if (e.type == E_DARTER) {
                e.phase += dt * 2.6f;
                float k = (float) Math.sin(e.phase);
                v *= k > 0.55f ? 7.5f : 0.35f; // crouch... then pounce
            }
            e.x += dx / d * v * dt;
            e.y += dy / d * v * dt;

            float eR = enemyRadius(e);

            // the cord cuts
            if (e.hitCooldown <= 0f
                    && segDist(e.x, e.y, px, py, qx, qy) < cordR + eR * 0.7f) {
                hurtEnemy(e, i, true);
                continue;
            }
            // the flying partner crushes
            if (e.hitCooldown <= 0f && lethalOrb) {
                float ddx = e.x - qx, ddy = e.y - qy;
                float rr = partnerR + eR;
                if (ddx * ddx + ddy * ddy < rr * rr) {
                    hurtEnemy(e, i, false);
                    continue;
                }
            }

            // claws on the white orb
            float pdx = e.x - px, pdy = e.y - py;
            float pr = playerR + eR * 0.8f;
            if (pdx * pdx + pdy * pdy < pr * pr) {
                enemies.remove(i);
                playerHit(e);
            }
        }
    }

    private void hurtEnemy(Enemy e, int index, boolean byCord) {
        e.hp--;
        if (e.hp <= 0) {
            enemies.remove(index);
            kill(e, byCord);
        } else {
            // a tank shrugs the first cut: knock it back, brief i-frames
            e.hitCooldown = 0.45f;
            float dx = e.x - px, dy = e.y - py;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
            e.x += dx / d * width * 0.06f;
            e.y += dy / d * width * 0.06f;
            burst(e.x, e.y, 6, 0xFFB0BEC5, 0.5f);
            sfx.play(SoundFx.CLANG);
            buzz(10);
        }
    }

    private void kill(Enemy e, boolean byCord) {
        combo = Math.min(MAX_COMBO, combo + 1);
        comboTimer = COMBO_WINDOW;
        int pts = combo;
        score += pts;
        kills++;

        int col = enemyColor(e.type);
        burst(e.x, e.y, 14, col, 1.0f);
        addText("+" + pts + (combo > 1 ? "  x" + combo : ""), e.x, e.y, 0xFF40E5FF);
        sfx.play(SoundFx.KILL_0 + Math.min(combo - 1, 7));
        buzz(14);

        if (e.type == E_SPLITTER) {
            spawnMini(e.x, e.y);
            spawnMini(e.x, e.y);
        }
        if (rng.nextInt(10) < 2) {
            Gem g = new Gem();
            g.x = e.x;
            g.y = e.y;
            gems.add(g);
        }
    }

    private void playerHit(Enemy e) {
        burst(px, py, 16, 0xFFFF3D58, 1.0f);
        if (hurtInvuln > 0f) {
            return;
        }
        hurtInvuln = 1.2f;
        hearts--;
        combo = 0;
        shake = Math.max(shake, 0.8f);
        flash = Math.max(flash, 0.6f);
        sfx.play(SoundFx.HIT);
        buzz(120);
        if (hearts <= 0) {
            gameOver();
        } else {
            addText(hearts == 1 ? "LAST HEART!" : "OUCH!",
                    width / 2f, height * 0.36f, 0xFFFF3D58);
        }
    }

    private void stepGems(float dt) {
        for (int i = gems.size() - 1; i >= 0; i--) {
            Gem g = gems.get(i);
            if (g.caught) {
                g.anim += dt * 4f;
                if (g.anim >= 1f) {
                    gems.remove(i);
                }
                continue;
            }
            // magnet toward the white orb when close
            float dx = px - g.x, dy = py - g.y;
            float d2 = dx * dx + dy * dy;
            float mag = width * 0.16f;
            if (d2 < mag * mag) {
                float d = (float) Math.sqrt(d2) + 1f;
                g.x += dx / d * width * 0.5f * dt;
                g.y += dy / d * width * 0.5f * dt;
            }
            float rr = playerR + width * 0.016f;
            if (d2 < rr * rr) {
                g.caught = true;
                score += 5;
                addText("+5", g.x, g.y - width * 0.03f, 0xFFFFD740);
                sfx.play(SoundFx.GEM);
                buzz(12);
            }
        }
    }

    private static float segDist(float x, float y,
                                 float ax, float ay, float bx, float by) {
        float abx = bx - ax, aby = by - ay;
        float len2 = abx * abx + aby * aby;
        float t = len2 > 0f ? ((x - ax) * abx + (y - ay) * aby) / len2 : 0f;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float cx2 = ax + abx * t, cy2 = ay + aby * t;
        float dx = x - cx2, dy = y - cy2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private int enemyColor(int type) {
        switch (type) {
            case E_DARTER: return 0xFFFF8A3D;
            case E_SPLITTER: return 0xFFC75CFF;
            case E_MINI: return 0xFFE090FF;
            case E_TANK: return 0xFFFF3D58;
            default: return 0xFF7986CB;
        }
    }

    // ------------------------------------------------------------ particles

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = rng.nextFloat() * TAU;
            float spd = (60f + rng.nextFloat() * 340f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.35f + rng.nextFloat() * 0.45f;
            p.size = width * (0.004f + rng.nextFloat() * 0.007f);
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
            t.y -= dt * height * 0.04f;
            if (t.life <= 0f) {
                texts.remove(i);
            }
        }
    }

    // ----------------------------------------------------------------- draw

    private void render(Canvas c) {
        synchronized (lock) {
            c.drawColor(0xFF070811);
            if (arenaShader != null) {
                paint.setShader(arenaShader);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            }
            drawStars(c);

            if (shake > 0f) {
                float m = shake * shake * width * 0.022f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            if (state != STATE_MENU) {
                drawGems(c);
                drawEnemies(c);
            }
            drawCord(c);
            drawParticles(c);
            drawOrbs(c);
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

            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (flash * 150f), 255, 80, 90));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawStars(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 28; i++) {
            float sx = (i * 379f + 53f) % width;
            float sy = (i * 233f + 89f) % height;
            int a = 22 + (int) (16 * Math.sin(menuT * 1.4f + i));
            paint.setColor(Color.argb(Math.max(8, a), 200, 215, 255));
            c.drawCircle(sx, sy, Math.max(1.5f, width * 0.0014f), paint);
        }
    }

    /** The blade: a glowing cord with elastic sag. */
    private void drawCord(Canvas c) {
        float dx = qx - px, dy = qy - py;
        float dist = (float) Math.sqrt(dx * dx + dy * dy) + 0.001f;
        // sag bulges perpendicular when the rope is slack
        float slack = Math.max(0f, restLen - dist) * 0.5f;
        float mx = (px + qx) / 2f - dy / dist * slack;
        float my = (py + qy) / 2f + dx / dist * slack;

        float taut = Math.min(1f, dist / restLen);
        int glow = (int) (50 + 90 * taut);

        paint.setStyle(Paint.Style.STROKE);
        cordPath.reset();
        cordPath.moveTo(px, py);
        cordPath.quadTo(mx, my, qx, qy);

        paint.setStrokeWidth(width * 0.020f);
        paint.setColor(Color.argb(glow / 2, 64, 229, 255));
        c.drawPath(cordPath, paint);
        paint.setStrokeWidth(width * 0.008f);
        paint.setColor(Color.argb(glow + 60, 120, 240, 255));
        c.drawPath(cordPath, paint);
        paint.setStrokeWidth(Math.max(2f, width * 0.003f));
        paint.setColor(0xFFE8FBFF);
        c.drawPath(cordPath, paint);
    }

    private void drawOrbs(Canvas c) {
        paint.setStyle(Paint.Style.FILL);

        // partner trail
        for (int s = 0; s < trailX.length; s++) {
            int idx = (trailHead - s + trailX.length * 2) % trailX.length;
            float k = 1f - s / (float) trailX.length;
            paint.setColor(Color.argb((int) (60f * k), 64, 229, 255));
            c.drawCircle(trailX[idx], trailY[idx], partnerR * (0.3f + 0.6f * k), paint);
        }

        // partner orb: white-hot when lethal
        boolean lethal = partnerSpeed() > killSpeed;
        if (lethal) {
            paint.setColor(0x5540E5FF);
            c.drawCircle(qx, qy, partnerR * 3.0f, paint);
        }
        paint.setColor(0x4640E5FF);
        c.drawCircle(qx, qy, partnerR * 1.9f, paint);
        paint.setColor(lethal ? 0xFFFFFFFF : 0xFF40E5FF);
        c.drawCircle(qx, qy, partnerR, paint);

        // player orb
        boolean blink = hurtInvuln > 0f && ((int) (hurtInvuln * 8f)) % 2 == 0;
        paint.setColor(blink ? 0x46FFFFFF : 0x66FFFFFF);
        c.drawCircle(px, py, playerR * 2.2f, paint);
        paint.setColor(blink ? 0x99FFFFFF : 0xFFFFFFFF);
        c.drawCircle(px, py, playerR, paint);
    }

    private void drawEnemies(Canvas c) {
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            float r = enemyRadius(e);
            float wob = 1f + 0.10f * (float) Math.sin(menuT * 6f + e.phase);
            int col = enemyColor(e.type);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor((col & 0x00FFFFFF) | 0x2E000000);
            c.drawCircle(e.x, e.y, r * 1.8f * wob, paint);
            paint.setColor(e.hitCooldown > 0f ? 0xFFFFFFFF : col);
            c.drawCircle(e.x, e.y, r * wob, paint);

            // tanks wear armor pips
            if (e.type == E_TANK) {
                paint.setColor(0xFF070811);
                for (int h = 0; h < e.hp; h++) {
                    c.drawCircle(e.x + (h - (e.hp - 1) / 2f) * r * 0.5f, e.y, r * 0.16f, paint);
                }
            } else {
                // eyes fixed on the white orb
                float look = (float) Math.atan2(py - e.y, px - e.x);
                float eyeOff = r * 0.4f;
                float perpX = (float) Math.cos(look + TAU / 4f) * eyeOff;
                float perpY = (float) Math.sin(look + TAU / 4f) * eyeOff;
                float fwdX = (float) Math.cos(look) * r * 0.3f;
                float fwdY = (float) Math.sin(look) * r * 0.3f;
                paint.setColor(0xFF070811);
                float er = Math.max(2f, r * 0.18f);
                c.drawCircle(e.x + fwdX + perpX, e.y + fwdY + perpY, er, paint);
                c.drawCircle(e.x + fwdX - perpX, e.y + fwdY - perpY, er, paint);
            }
        }
    }

    private void drawGems(Canvas c) {
        for (int i = 0; i < gems.size(); i++) {
            Gem g = gems.get(i);
            if (g.caught) {
                float k = 1f - g.anim;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.004f * k);
                paint.setColor(Color.argb((int) (k * 255f), 255, 215, 64));
                c.drawCircle(g.x, g.y, width * 0.02f * (1f + g.anim * 2f), paint);
                continue;
            }
            float pulse = 1f + 0.15f * (float) Math.sin(menuT * 6f + i);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33FFD740);
            c.drawCircle(g.x, g.y, width * 0.028f * pulse, paint);
            paint.setColor(0xFFFFD740);
            float s = width * 0.012f * pulse;
            cordPath.reset();
            cordPath.moveTo(g.x, g.y - s);
            cordPath.lineTo(g.x + s * 0.75f, g.y);
            cordPath.lineTo(g.x, g.y + s);
            cordPath.lineTo(g.x - s * 0.75f, g.y);
            cordPath.close();
            c.drawPath(cordPath, paint);
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
        textPaint.setTextSize(width * 0.11f);
        c.drawText(String.valueOf(score), width / 2f, height * 0.095f, textPaint);

        textPaint.setTextSize(width * 0.034f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, width / 2f, height * 0.038f, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(width * 0.048f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MAX_HEARTS; i++) {
            sb.append(i < hearts ? "♥ " : "♡ ");
        }
        textPaint.setColor(hearts == 1 ? 0xFFFF3D58 : 0xFFFF7B8C);
        c.drawText(sb.toString().trim(), width * 0.04f, height * 0.045f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(width * 0.034f);
        textPaint.setColor(0xFF40E5FF);
        c.drawText("WAVE " + wave, width * 0.96f, height * 0.045f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        if (combo > 1) {
            textPaint.setTextSize(width * 0.05f);
            textPaint.setColor(0xFF40E5FF);
            c.drawText("x" + combo, width / 2f, height * 0.135f, textPaint);
        }
    }

    private void drawMenu(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.17f);
        c.drawText("TETHER", width / 2f, height * 0.22f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0xFF40E5FF);
        c.drawText("the bond is the blade", width / 2f, height * 0.27f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        textPaint.setTextSize(width * 0.038f);
        c.drawText("drag the white orb — its partner whips behind",
                width / 2f, height * 0.315f, textPaint);
        c.drawText("the cord cuts everything it touches  •  spin to win",
                width / 2f, height * 0.35f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO PLAY", width / 2f, height * 0.82f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), width / 2f, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(games + " bouts  •  " + killsTotal + " shadows cut", width / 2f, height * 0.92f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.082f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "THE BOND BROKE", width / 2f, height * 0.30f, textPaint);

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
        c.drawText(kills + " cut  •  wave " + wave, width / 2f, height * 0.555f, textPaint);

        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", width / 2f, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 600) return "GOD MODE";
        if (s >= 380) return "LEGEND";
        if (s >= 220) return "MASTER";
        if (s >= 100) return "DUELIST";
        if (s >= 40) return "SLICER";
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
                    handleDrag(event, action);
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

    private void handleDrag(MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = true;
            lastTx = event.getX();
            lastTy = event.getY();
        } else if (action == MotionEvent.ACTION_MOVE && dragging) {
            float k = 1.3f;
            px += (event.getX() - lastTx) * k;
            py += (event.getY() - lastTy) * k;
            lastTx = event.getX();
            lastTy = event.getY();
            px = Math.max(playerR, Math.min(width - playerR, px));
            py = Math.max(playerR, Math.min(height - playerR, py));
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
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
            width = w;
            height = hpx;
            playerR = w * 0.020f;
            partnerR = w * 0.024f;
            cordR = w * 0.010f;
            restLen = w * 0.30f;
            killSpeed = w * 0.55f;
            if (px <= 0f) {
                px = w / 2f;
                py = hpx * 0.6f;
                qx = px;
                qy = py - restLen;
            }
            arenaShader = new RadialGradient(w / 2f, hpx * 0.45f, Math.max(w, hpx) * 0.85f,
                    new int[]{0x221A2C50, 0x00000000, 0x77000000},
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
