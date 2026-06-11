package gr.happyonline.rush;

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
 * RUSH - a neon endless runner.
 *
 * A cube sprints through a synthwave world. Tap to jump, tap again mid-air
 * for a double jump. Clear the spikes, hop the blocks, grab the coins.
 * Shaving a spike by a pixel pays a "CLOSE!" bonus, and the world speeds
 * up (and changes color) the longer you survive.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_OVER = 3;

    private static final int OB_SPIKE = 0;
    private static final int OB_BLOCK = 1;
    private static final int OB_COIN = 2;

    private static final float SPEEDUP_EVERY = 11f;  // seconds between gear shifts
    private static final int MAX_LEVEL = 8;
    private static final float DEATH_SLOWMO = 0.9f;

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;
    private float groundY, playerX, playerSize, gravity, jumpV;

    // run state
    private int state = STATE_MENU;
    private float playerBottom;     // y of the cube's feet
    private float vy;
    private boolean grounded;
    private int jumpsUsed;
    private float rot;              // cube spin while airborne, degrees
    private int score;
    private int bonus;
    private int coins;
    private int level;
    private float levelTimer;
    private float spawnTimer;
    private float runTime;
    private float distance;
    private float overTimer;
    private boolean newBestShown;

    // persistent stats
    private int best;
    private int runs;
    private int totalCoins;

    // presentation
    private float hue = 195f;
    private float targetHue = 195f;
    private float shake;
    private float flash;
    private float speedFlash;
    private float gridOffset;
    private float menuT;

    private final ArrayList<Obstacle> obstacles = new ArrayList<Obstacle>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private Shader skyShader;

    private static class Obstacle {
        int type;
        float x, w, h;      // x = left edge; spikes/blocks sit on the ground
        float yTop;         // coins: center y; blocks: computed ground - h
        boolean done;       // coin collected / near-miss already paid
    }

    private static class Particle {
        float x, y, vx, vy, life, maxLife, size;
        int color;
        boolean gravity;
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

        prefs = context.getSharedPreferences("rush", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        runs = prefs.getInt("runs", 0);
        totalCoins = prefs.getInt("coins", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ run

    private float speed() {
        return width * (0.46f + 0.075f * level);
    }

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            score = 0;
            bonus = 0;
            coins = 0;
            level = 0;
            levelTimer = 0f;
            spawnTimer = 0.9f;
            runTime = 0f;
            distance = 0f;
            overTimer = 0f;
            newBestShown = false;
            playerBottom = groundY;
            vy = 0f;
            grounded = true;
            jumpsUsed = 0;
            rot = 0f;
            obstacles.clear();
            particles.clear();
            texts.clear();
            targetHue = 195f;
            sfx.play(SoundFx.START);
        }
    }

    private void die() {
        state = STATE_DYING;
        overTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.DEATH);
        buzz(160);
        burst(playerX, playerBottom - playerSize / 2f, 50, 0xFFFF3D58, 1.7f, true);

        runs++;
        totalCoins += coins;
        if (score > best) {
            best = score;
        }
        prefs.edit()
                .putInt("best", best)
                .putInt("runs", runs)
                .putInt("coins", totalCoins)
                .apply();
    }

    // ------------------------------------------------------------- spawning

    private void spawnPattern() {
        float x = width + playerSize;
        int roll = rng.nextInt(10);
        float ps = playerSize;

        if (roll < 3) {                       // single spike
            addSpike(x);
            maybeCoinArc(x, ps * 2.2f);
        } else if (roll < 5) {                // double spike
            addSpike(x);
            addSpike(x + ps * 0.75f);
            maybeCoinArc(x + ps * 0.4f, ps * 2.5f);
        } else if (roll < 6 && level >= 2) {  // triple spike: double jump territory
            addSpike(x);
            addSpike(x + ps * 0.75f);
            addSpike(x + ps * 1.5f);
            maybeCoinArc(x + ps * 0.75f, ps * 3.2f);
        } else if (roll < 8) {                // block: hop over or land on it
            addBlock(x, ps * 1.05f, ps * (1.0f + rng.nextFloat() * 0.25f));
            maybeCoinArc(x + ps * 0.5f, ps * 2.6f);
        } else if (level >= 3 && roll < 9) {  // tall block: double jump or ride the top
            addBlock(x, ps * 1.1f, ps * 1.9f);
            maybeCoinArc(x + ps * 0.55f, ps * 3.4f);
        } else {                              // block with a spike on its heels
            addBlock(x, ps, ps * 1.1f);
            addSpike(x + ps * 2.4f);
        }
    }

    private void addSpike(float x) {
        Obstacle o = new Obstacle();
        o.type = OB_SPIKE;
        o.x = x;
        o.w = playerSize * 0.95f;
        o.h = playerSize * 1.0f;
        obstacles.add(o);
    }

    private void addBlock(float x, float w, float h) {
        Obstacle o = new Obstacle();
        o.type = OB_BLOCK;
        o.x = x;
        o.w = w;
        o.h = h;
        o.yTop = groundY - h;
        obstacles.add(o);
    }

    private void maybeCoinArc(float x, float clearance) {
        if (rng.nextInt(10) < 6) {
            for (int i = 0; i < 3; i++) {
                Obstacle o = new Obstacle();
                o.type = OB_COIN;
                o.w = playerSize * 0.5f;
                o.x = x + (i - 1) * playerSize * 0.9f;
                float lift = i == 1 ? playerSize * 0.5f : 0f;
                o.yTop = groundY - clearance - lift;
                obstacles.add(o);
            }
        }
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            float timeScale = state == STATE_DYING ? 0.16f : 1f;
            float dt = rawDt * timeScale;

            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            if (speedFlash > 0f) speedFlash = Math.max(0f, speedFlash - rawDt * 1.2f);

            float dh = targetHue - hue;
            if (dh > 180f) dh -= 360f;
            if (dh < -180f) dh += 360f;
            hue += dh * Math.min(1f, rawDt * 1.5f);
            if (hue < 0f) hue += 360f;
            if (hue >= 360f) hue -= 360f;

            updateParticles(rawDt);
            updateTexts(rawDt);

            float v = speed();
            if (state == STATE_MENU) {
                gridOffset = (gridOffset + width * 0.25f * rawDt) % (width * 0.12f);
            }

            if (state == STATE_PLAYING || state == STATE_DYING) {
                gridOffset = (gridOffset + v * dt) % (width * 0.12f);
            }

            if (state == STATE_PLAYING) {
                runTime += dt;
                distance += v * dt;
                score = (int) (distance / (width * 0.1f)) + coins * 5 + bonus;

                levelTimer += dt;
                if (levelTimer >= SPEEDUP_EVERY && level < MAX_LEVEL) {
                    levelTimer = 0f;
                    level++;
                    speedFlash = 1f;
                    targetHue = (195f + level * 42f) % 360f;
                    addText("SPEED UP!", width / 2f, height * 0.32f, 0xFFFFD740);
                    sfx.play(SoundFx.SPEED);
                    buzz(30);
                }

                spawnTimer -= dt;
                if (spawnTimer <= 0f) {
                    spawnPattern();
                    // time gap between patterns shrinks a touch as levels climb
                    float gap = 0.78f + rng.nextFloat() * 0.5f - level * 0.028f;
                    spawnTimer = Math.max(0.55f, gap);
                }

                stepPlayer(dt, v);
                stepObstacles(dt, v);

                if (!newBestShown && best > 0 && score > best) {
                    newBestShown = true;
                    addText("NEW BEST!", width / 2f, height * 0.26f, 0xFFFFD740);
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

    private void stepPlayer(float dt, float v) {
        float half = playerSize / 2f;
        float left = playerX - half;
        float right = playerX + half;
        float prevBottom = playerBottom;

        vy += gravity * dt;
        playerBottom += vy * dt;

        // find the surface under the cube: the ground, or a block top
        float support = groundY;
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            if (o.type != OB_BLOCK) continue;
            if (right > o.x + 2f && left < o.x + o.w - 2f && prevBottom <= o.yTop + 4f) {
                if (o.yTop < support) {
                    support = o.yTop;
                }
            }
        }

        if (playerBottom >= support) {
            if (!grounded && vy > height * 0.3f) {
                dust(playerX, support);
            }
            playerBottom = support;
            vy = 0f;
            grounded = true;
            jumpsUsed = 0;
            rot = 0f;
        } else {
            grounded = false;
        }

        if (!grounded) {
            rot += 460f * dt;
        }

        // motion trail
        Particle p = new Particle();
        p.x = playerX - half;
        p.y = playerBottom - half;
        p.vx = -v * 0.25f;
        p.vy = 0f;
        p.maxLife = p.life = 0.28f;
        p.size = half * 0.8f;
        p.color = playerColor();
        particles.add(p);
    }

    private void stepObstacles(float dt, float v) {
        float half = playerSize / 2f;
        float left = playerX - half;
        float right = playerX + half;
        float top = playerBottom - playerSize;

        for (int i = obstacles.size() - 1; i >= 0; i--) {
            Obstacle o = obstacles.get(i);
            o.x -= v * dt;

            if (o.x + o.w < -playerSize * 2f) {
                obstacles.remove(i);
                continue;
            }

            if (o.type == OB_COIN) {
                if (o.done) continue;
                float cx = o.x + o.w / 2f;
                float cy = o.yTop;
                if (cx > left - o.w && cx < right + o.w
                        && cy > top - o.w && cy < playerBottom + o.w) {
                    o.done = true;
                    coins++;
                    burst(cx, cy, 10, 0xFFFFD740, 0.8f, false);
                    addText("+5", cx, cy - playerSize * 0.4f, 0xFFFFD740);
                    sfx.play(SoundFx.COIN_0 + Math.min(coins % 8, 7));
                    buzz(14);
                }
                continue;
            }

            if (o.type == OB_SPIKE) {
                // forgiving hitbox: the deadly part is the spike's core
                float sx0 = o.x + o.w * 0.28f;
                float sx1 = o.x + o.w * 0.72f;
                float sy = groundY - o.h * 0.62f;
                if (right > sx0 && left < sx1 && playerBottom > sy) {
                    die();
                    return;
                }
                // near-miss bonus once the spike slides behind the cube
                if (!o.done && o.x + o.w < left) {
                    o.done = true;
                    if (!grounded) {
                        float clearance = (groundY - o.h) - playerBottom;
                        if (clearance >= 0f && clearance < playerSize * 0.45f) {
                            bonus += 5;
                            addText("CLOSE! +5", playerX, top - playerSize * 0.5f, 0xFF69F0AE);
                            sfx.play(SoundFx.CLOSE);
                        }
                    }
                }
            } else { // block: slamming into its side is fatal
                if (right > o.x + 3f && left < o.x + o.w - 3f
                        && playerBottom > o.yTop + height * 0.012f) {
                    die();
                    return;
                }
            }
        }
    }

    private void tapWhilePlaying() {
        if (grounded) {
            vy = -jumpV;
            grounded = false;
            jumpsUsed = 1;
            dust(playerX, playerBottom);
            sfx.play(SoundFx.JUMP);
            buzz(10);
        } else if (jumpsUsed < 2) {
            vy = -jumpV * 0.94f;
            jumpsUsed = 2;
            burst(playerX, playerBottom, 8, playerColor(), 0.6f, false);
            sfx.play(SoundFx.JUMP2);
            buzz(10);
        }
    }

    // ------------------------------------------------------------ particles

    private void dust(float x, float y) {
        for (int i = 0; i < 8; i++) {
            Particle p = new Particle();
            p.x = x + (rng.nextFloat() - 0.5f) * playerSize;
            p.y = y;
            p.vx = (rng.nextFloat() - 0.7f) * width * 0.25f;
            p.vy = -rng.nextFloat() * height * 0.06f;
            p.maxLife = p.life = 0.3f + rng.nextFloat() * 0.2f;
            p.size = playerSize * 0.16f;
            p.color = 0xFFB0BEC5;
            particles.add(p);
        }
    }

    private void burst(float x, float y, int count, int color, float power, boolean gravity) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
            float spd = (60f + rng.nextFloat() * 380f) * power * (width / 1080f);
            p.x = x;
            p.y = y;
            p.vx = (float) Math.cos(ang) * spd;
            p.vy = (float) Math.sin(ang) * spd;
            p.maxLife = p.life = 0.4f + rng.nextFloat() * 0.55f;
            p.size = playerSize * (0.12f + rng.nextFloat() * 0.22f);
            p.color = color;
            p.gravity = gravity;
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
            if (p.gravity) {
                p.vy += gravity * 0.5f * dt;
            } else {
                p.vx *= (1f - 1.8f * dt);
                p.vy *= (1f - 1.8f * dt);
            }
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

    private int playerColor() {
        float[] hsv = {(hue + 45f) % 360f, 0.3f, 1f};
        return Color.HSVToColor(hsv);
    }

    private void render(Canvas c) {
        synchronized (lock) {
            drawSky(c);

            if (shake > 0f) {
                float m = shake * shake * width * 0.03f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            drawGround(c);
            drawObstacles(c);
            drawParticles(c);
            if (state != STATE_OVER) {
                drawPlayer(c);
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
            if (speedFlash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (speedFlash * 60f), 255, 255, 255));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawSky(Canvas c) {
        c.drawColor(themeColor(0.6f, 0.10f));
        if (skyShader != null) {
            paint.setShader(skyShader);
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, width, groundY, paint);
            paint.setShader(null);
        }
        // horizon glow
        paint.setStyle(Paint.Style.FILL);
        int glow = themeColor(0.7f, 0.8f);
        for (int i = 3; i >= 1; i--) {
            paint.setColor((glow & 0x00FFFFFF) | (0x10 * i) << 24);
            c.drawRect(0, groundY - height * 0.006f * i * 2.2f, width, groundY, paint);
        }
        // far stars, drifting slowly
        paint.setColor(0x33FFFFFF);
        for (int i = 0; i < 24; i++) {
            float sx = ((i * 379f + 53f) - gridOffset * 0.18f * (1 + i % 3)) % width;
            if (sx < 0) sx += width;
            float sy = (i * 233f + 89f) % (groundY * 0.85f);
            c.drawCircle(sx, sy, Math.max(1.5f, width * 0.0016f), paint);
        }
    }

    private void drawGround(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0A0B12);
        c.drawRect(0, groundY, width, height, paint);

        int line = themeColor(0.65f, 0.95f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, height * 0.0035f));
        paint.setColor(line);
        c.drawLine(0, groundY, width, groundY, paint);

        // perspective grid sliding with the world
        paint.setStrokeWidth(Math.max(1.5f, height * 0.0018f));
        paint.setColor((line & 0x00FFFFFF) | 0x38000000);
        float spacing = width * 0.12f;
        float vanishX = width / 2f;
        for (float x = -spacing * 2f - gridOffset; x < width + spacing * 2f; x += spacing) {
            float bx = x;
            float tx = vanishX + (x - vanishX) * 0.55f;
            c.drawLine(tx, groundY, bx, height, paint);
        }
        for (int i = 1; i <= 4; i++) {
            float y = groundY + (height - groundY) * (i / 4.5f) * (i / 4.5f);
            c.drawLine(0, y, width, y, paint);
        }
    }

    private void drawPlayer(Canvas c) {
        float half = playerSize / 2f;
        float cx = playerX;
        float cy = playerBottom - half;
        int col = playerColor();

        c.save();
        c.rotate(rot, cx, cy);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((col & 0x00FFFFFF) | 0x38000000);
        c.drawRect(cx - half * 1.45f, cy - half * 1.45f, cx + half * 1.45f, cy + half * 1.45f, paint);
        paint.setColor(col);
        c.drawRect(cx - half, cy - half, cx + half, cy + half, paint);
        // face: two eyes looking ahead
        paint.setColor(0xFF10121C);
        float er = half * 0.18f;
        c.drawCircle(cx + half * 0.34f, cy - half * 0.22f, er, paint);
        c.drawCircle(cx - half * 0.1f, cy - half * 0.22f, er, paint);
        c.restore();
    }

    private void drawObstacles(Canvas c) {
        for (int i = 0; i < obstacles.size(); i++) {
            Obstacle o = obstacles.get(i);
            if (o.type == OB_SPIKE) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x40FF3D58);
                path.reset();
                path.moveTo(o.x - o.w * 0.12f, groundY);
                path.lineTo(o.x + o.w * 1.12f, groundY);
                path.lineTo(o.x + o.w / 2f, groundY - o.h * 1.18f);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(0xFFFF3D58);
                path.reset();
                path.moveTo(o.x, groundY);
                path.lineTo(o.x + o.w, groundY);
                path.lineTo(o.x + o.w / 2f, groundY - o.h);
                path.close();
                c.drawPath(path, paint);
            } else if (o.type == OB_BLOCK) {
                int col = themeColor(0.55f, 0.9f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((col & 0x00FFFFFF) | 0x30000000);
                c.drawRect(o.x - 4f, o.yTop - 4f, o.x + o.w + 4f, groundY, paint);
                paint.setColor(0xFF141828);
                c.drawRect(o.x, o.yTop, o.x + o.w, groundY, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, height * 0.003f));
                paint.setColor(col);
                c.drawRect(o.x, o.yTop, o.x + o.w, groundY, paint);
                c.drawLine(o.x, o.yTop, o.x + o.w, o.yTop, paint);
            } else if (!o.done) {
                float cx = o.x + o.w / 2f;
                float cy = o.yTop;
                float s = o.w / 2f * (0.8f + 0.2f * (float) Math.sin(menuT * 7f + i));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x33FFD740);
                c.drawCircle(cx, cy, s * 2f, paint);
                paint.setColor(0xFFFFD740);
                path.reset();
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s * 0.75f, cy);
                path.lineTo(cx, cy + s);
                path.lineTo(cx - s * 0.75f, cy);
                path.close();
                c.drawPath(path, paint);
            }
        }
    }

    private void drawParticles(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < particles.size(); i++) {
            Particle p = particles.get(i);
            float k = p.life / p.maxLife;
            paint.setColor((p.color & 0x00FFFFFF) | (((int) (k * 200f)) << 24));
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
        textPaint.setTextSize(width * 0.14f);
        c.drawText(String.valueOf(score), width / 2f, height * 0.14f, textPaint);

        textPaint.setTextSize(width * 0.038f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, width / 2f, height * 0.055f, textPaint);

        textPaint.setColor(0xFFFFD740);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("◆ " + coins, width * 0.95f, height * 0.055f, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(themeColor(0.4f, 1f));
        c.drawText("GEAR " + (level + 1), width * 0.05f, height * 0.055f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawMenu(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.20f);
        c.drawText("RUSH", width / 2f, height * 0.26f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(themeColor(0.4f, 1f));
        c.drawText("tap = jump  •  tap again = double jump", width / 2f, height * 0.315f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO RUN", width / 2f, height * 0.52f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), width / 2f, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(runs + " runs  •  " + totalCoins + " coins", width / 2f, height * 0.92f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB0000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.085f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "WRECKED!", width / 2f, height * 0.30f, textPaint);

        textPaint.setTextSize(width * 0.21f);
        textPaint.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), width / 2f, height * 0.46f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(0xAAFFFFFF);
        String sub;
        if (!isBest && best > 0 && score >= best * 0.9f) {
            sub = "SO CLOSE!  best is " + best;
        } else {
            sub = "best " + best + "  •  " + rankFor(best);
        }
        c.drawText(sub, width / 2f, height * 0.52f, textPaint);
        c.drawText("◆ " + coins + "  •  gear " + (level + 1), width / 2f, height * 0.565f, textPaint);

        if (overTimer > 0.35f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", width / 2f, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 500) return "GOD MODE";
        if (s >= 300) return "LEGEND";
        if (s >= 180) return "MASTER";
        if (s >= 90) return "PRO";
        if (s >= 40) return "RUNNER";
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
            groundY = hpx * 0.74f;
            playerX = w * 0.26f;
            playerSize = hpx * 0.052f;
            gravity = hpx * 3.1f;
            // enough to clear ~2.3 cube-heights with one jump
            jumpV = (float) Math.sqrt(2f * gravity * playerSize * 2.3f);
            if (playerBottom <= 0f || playerBottom > groundY) {
                playerBottom = groundY;
            }
            skyShader = new LinearGradient(0, 0, 0, groundY,
                    new int[]{0xCC000000, 0x22000000, 0x00000000},
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
