package gr.happyonline.echo;

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
 * ECHO - outrun your past.
 *
 * Drag to glide around the arena and collect the light orbs. Every time
 * you clear a round, the exact path you just flew is recorded and replayed
 * forever as a ghost - so after five rounds you are weaving between five
 * past versions of yourself, each one moving precisely the way you did.
 * Fly sloppy now and you will pay for it later: the only enemy in this
 * game is you.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_DYING = 2;
    private static final int STATE_OVER = 3;

    private static final int ORBS_PER_ROUND = 5;
    private static final float INVULN_TIME = 1.4f;
    private static final float GRAZE_TICK = 0.45f;
    private static final float DEATH_SLOWMO = 1.0f;
    private static final int MAX_RECORD = 60 * 150; // safety cap per round

    private final SurfaceHolder holder;
    private final Object lock = new Object();
    private final Random rng = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final SoundFx sfx;

    private GameThread thread;
    private boolean surfaceReady;

    private int width = 1, height = 1;
    private float playerR, killR, grazeR, orbR;

    // run state
    private int state = STATE_MENU;
    private int round;
    private int orbsGot;
    private int score;
    private int best;
    private float roundTime;
    private float invuln;
    private float grazeTimer;
    private boolean grazing;
    private float overTimer;
    private boolean newBestShown;

    // player
    private float px, py;
    private float lastTx, lastTy;
    private boolean dragging;
    private final float[] trailX = new float[16];
    private final float[] trailY = new float[16];
    private int trailHead;

    // current-round recording
    private float[] recT = new float[2048];
    private float[] recX = new float[2048];
    private float[] recY = new float[2048];
    private int recN;

    // orb
    private float orbX, orbY;
    private float orbPulse;

    // persistent stats
    private int games;
    private int roundsTotal;

    // presentation
    private float shake;
    private float flash;
    private float menuT;

    private final ArrayList<Ghost> ghosts = new ArrayList<Ghost>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader arenaShader;

    private static class Ghost {
        float[] ts, xs, ys;
        int n;
        float duration;
        float t;        // current replay clock
        int idx;        // replay cursor
        int color;
        float x, y;     // current interpolated position
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

        prefs = context.getSharedPreferences("echo", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        games = prefs.getInt("games", 0);
        roundsTotal = prefs.getInt("rounds", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ run

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            round = 1;
            orbsGot = 0;
            score = 0;
            roundTime = 0f;
            invuln = INVULN_TIME;
            grazeTimer = 0f;
            overTimer = 0f;
            newBestShown = false;
            dragging = false;
            px = width / 2f;
            py = height * 0.7f;
            ghosts.clear();
            particles.clear();
            texts.clear();
            recN = 0;
            resetTrail();
            spawnOrb();
            addText("ROUND 1", width / 2f, height * 0.3f, 0xFFFFFFFF);
            sfx.play(SoundFx.START);
        }
    }

    private void resetTrail() {
        for (int i = 0; i < trailX.length; i++) {
            trailX[i] = px;
            trailY[i] = py;
        }
        trailHead = 0;
    }

    private int ghostColor(int r) {
        float[] hsv = {(160f + r * 47f) % 360f, 0.75f, 1f};
        return Color.HSVToColor(hsv);
    }

    private void roundClear() {
        // freeze this round's flight path into a ghost
        if (recN >= 2) {
            Ghost g = new Ghost();
            g.n = recN;
            g.ts = new float[recN];
            g.xs = new float[recN];
            g.ys = new float[recN];
            System.arraycopy(recT, 0, g.ts, 0, recN);
            System.arraycopy(recX, 0, g.xs, 0, recN);
            System.arraycopy(recY, 0, g.ys, 0, recN);
            g.duration = g.ts[recN - 1];
            g.color = ghostColor(round);
            g.x = g.xs[0];
            g.y = g.ys[0];
            ghosts.add(g);
        }

        int pts = 5 * round;
        score += pts;
        roundsTotal++;
        round++;
        orbsGot = 0;
        recN = 0;
        roundTime = 0f;
        invuln = INVULN_TIME;
        addText("ROUND " + round + "  +" + pts, width / 2f, height * 0.3f, ghostColor(round - 1));
        addText("your echo joins the hunt", width / 2f, height * 0.35f, 0x88FFFFFF);
        sfx.play(SoundFx.CLEAR);
        buzz(30);
        checkBest();
    }

    private void checkBest() {
        if (!newBestShown && best > 0 && score > best) {
            newBestShown = true;
            addText("NEW BEST!", width / 2f, height * 0.24f, 0xFFFFD740);
            sfx.play(SoundFx.NEW_BEST);
        }
    }

    private void die(Ghost killer) {
        state = STATE_DYING;
        overTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.OVER);
        buzz(180);
        burst(px, py, 40, killer != null ? killer.color : 0xFFFF3D58, 1.6f);

        games++;
        if (score > best) {
            best = score;
        }
        prefs.edit()
                .putInt("best", best)
                .putInt("games", games)
                .putInt("rounds", roundsTotal)
                .apply();
    }

    private void spawnOrb() {
        for (int attempt = 0; attempt < 60; attempt++) {
            float x = width * 0.08f + rng.nextFloat() * width * 0.84f;
            float y = height * 0.10f + rng.nextFloat() * height * 0.80f;
            float dx = x - px, dy = y - py;
            if (dx * dx + dy * dy < width * 0.3f * width * 0.3f) {
                continue; // too easy: must travel for it
            }
            boolean clear = true;
            for (int i = 0; i < ghosts.size(); i++) {
                Ghost g = ghosts.get(i);
                float gx = x - g.x, gy = y - g.y;
                if (gx * gx + gy * gy < width * 0.12f * width * 0.12f) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                orbX = x;
                orbY = y;
                orbPulse = 0f;
                return;
            }
        }
        // crowded arena: drop it anywhere reachable
        orbX = width * 0.1f + rng.nextFloat() * width * 0.8f;
        orbY = height * 0.12f + rng.nextFloat() * height * 0.76f;
        orbPulse = 0f;
    }

    // --------------------------------------------------------------- update

    private void update(float rawDt) {
        synchronized (lock) {
            float timeScale = state == STATE_DYING ? 0.18f : 1f;
            float dt = rawDt * timeScale;

            menuT += rawDt;
            if (shake > 0f) shake = Math.max(0f, shake - rawDt * 2.2f);
            if (flash > 0f) flash = Math.max(0f, flash - rawDt * 2.5f);
            orbPulse += rawDt;

            updateParticles(rawDt);
            updateTexts(rawDt);

            if (state == STATE_PLAYING || state == STATE_DYING) {
                roundTime += dt;
                stepGhosts(dt);
            }

            if (state == STATE_PLAYING) {
                if (invuln > 0f) invuln -= dt;

                recordFrame();
                pushTrail();

                // orb pickup
                float dx = px - orbX, dy = py - orbY;
                float rr = playerR + orbR;
                if (dx * dx + dy * dy < rr * rr) {
                    orbsGot++;
                    score++;
                    burst(orbX, orbY, 12, 0xFFFFE082, 0.8f);
                    addText("+1", orbX, orbY - orbR * 2f, 0xFFFFE082);
                    sfx.play(SoundFx.ORB_0 + Math.min(orbsGot - 1, 7));
                    buzz(15);
                    checkBest();
                    if (orbsGot >= ORBS_PER_ROUND) {
                        roundClear();
                    }
                    spawnOrb();
                }

                // ghosts: lethal touch, profitable proximity
                grazing = false;
                if (invuln <= 0f) {
                    for (int i = 0; i < ghosts.size(); i++) {
                        Ghost g = ghosts.get(i);
                        float gx = px - g.x, gy = py - g.y;
                        float d2 = gx * gx + gy * gy;
                        if (d2 < killR * killR * 4f) {
                            die(g);
                            return;
                        }
                        if (d2 < grazeR * grazeR) {
                            grazing = true;
                        }
                    }
                }
                if (grazing) {
                    grazeTimer += dt;
                    if (grazeTimer >= GRAZE_TICK) {
                        grazeTimer = 0f;
                        score++;
                        addText("graze +1", px, py - playerR * 4f, 0xFF69F0AE);
                        sfx.play(SoundFx.GRAZE);
                        checkBest();
                    }
                } else {
                    grazeTimer = Math.max(0f, grazeTimer - dt * 0.5f);
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

    private void recordFrame() {
        if (recN >= MAX_RECORD) {
            return;
        }
        if (recN == recT.length) {
            recT = grow(recT);
            recX = grow(recX);
            recY = grow(recY);
        }
        recT[recN] = roundTime;
        recX[recN] = px;
        recY[recN] = py;
        recN++;
    }

    private static float[] grow(float[] a) {
        float[] b = new float[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private void pushTrail() {
        trailHead = (trailHead + 1) % trailX.length;
        trailX[trailHead] = px;
        trailY[trailHead] = py;
    }

    private void stepGhosts(float dt) {
        for (int i = 0; i < ghosts.size(); i++) {
            Ghost g = ghosts.get(i);
            g.t += dt;
            if (g.t >= g.duration) {
                g.t %= g.duration;
                g.idx = 0;
            }
            while (g.idx < g.n - 2 && g.ts[g.idx + 1] < g.t) {
                g.idx++;
            }
            float t0 = g.ts[g.idx];
            float t1 = g.ts[g.idx + 1];
            float k = t1 > t0 ? (g.t - t0) / (t1 - t0) : 0f;
            if (k < 0f) k = 0f;
            if (k > 1f) k = 1f;
            g.x = g.xs[g.idx] + (g.xs[g.idx + 1] - g.xs[g.idx]) * k;
            g.y = g.ys[g.idx] + (g.ys[g.idx + 1] - g.ys[g.idx]) * k;
        }
    }

    // ------------------------------------------------------------ particles

    private void burst(float x, float y, int count, int color, float power) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            float ang = rng.nextFloat() * (float) (Math.PI * 2);
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
            c.drawColor(0xFF07070E);
            if (arenaShader != null) {
                paint.setShader(arenaShader);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, width, height, paint);
                paint.setShader(null);
            }

            if (shake > 0f) {
                float m = shake * shake * width * 0.02f;
                c.save();
                c.translate((rng.nextFloat() - 0.5f) * m, (rng.nextFloat() - 0.5f) * m);
            }

            if (state != STATE_MENU) {
                drawGhosts(c);
                drawOrb(c);
            }
            drawParticles(c);
            if (state == STATE_PLAYING || state == STATE_DYING) {
                drawPlayer(c);
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

            if (grazing && state == STATE_PLAYING) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.012f);
                paint.setColor(0x3369F0AE);
                c.drawRect(0, 0, width, height, paint);
            }
            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb((int) (flash * 150f), 255, 60, 80));
                c.drawRect(0, 0, width, height, paint);
            }
        }
    }

    private void drawGhosts(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        float dim = invuln > 0f ? 0.35f : 1f;
        for (int i = 0; i < ghosts.size(); i++) {
            Ghost g = ghosts.get(i);
            int col = g.color;

            // breadcrumb trail along its recent path
            int steps = 9;
            int back = g.idx;
            for (int s = 0; s < steps; s++) {
                back -= 4;
                if (back < 0) back += g.n;
                float k = 1f - s / (float) steps;
                paint.setColor((col & 0x00FFFFFF)
                        | (((int) (60f * k * dim)) << 24));
                c.drawCircle(g.xs[back], g.ys[back], playerR * (0.25f + 0.5f * k), paint);
            }

            paint.setColor((col & 0x00FFFFFF) | (((int) (70f * dim)) << 24));
            c.drawCircle(g.x, g.y, playerR * 2.6f, paint);
            paint.setColor((col & 0x00FFFFFF) | (((int) (255f * dim)) << 24));
            c.drawCircle(g.x, g.y, playerR, paint);
        }
    }

    private void drawOrb(Canvas c) {
        float pulse = 1f + 0.15f * (float) Math.sin(orbPulse * 6f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2EFFE082);
        c.drawCircle(orbX, orbY, orbR * 2.6f * pulse, paint);
        paint.setColor(0xFFFFE082);
        c.drawCircle(orbX, orbY, orbR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.004f);
        paint.setColor(0x99FFE082);
        c.drawCircle(orbX, orbY, orbR * 1.8f * pulse, paint);
    }

    private void drawPlayer(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        // trail
        for (int s = 0; s < trailX.length; s++) {
            int idx = (trailHead - s + trailX.length * 2) % trailX.length;
            float k = 1f - s / (float) trailX.length;
            paint.setColor(Color.argb((int) (70f * k), 255, 255, 255));
            c.drawCircle(trailX[idx], trailY[idx], playerR * (0.3f + 0.6f * k), paint);
        }
        boolean blink = invuln > 0f && ((int) (invuln * 8f)) % 2 == 0;
        paint.setColor(blink ? 0x66FFFFFF : 0x46FFFFFF);
        c.drawCircle(px, py, playerR * 2.8f, paint);
        paint.setColor(blink ? 0x88FFFFFF : 0xFFFFFFFF);
        c.drawCircle(px, py, playerR, paint);
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
        textPaint.setTextSize(width * 0.12f);
        c.drawText(String.valueOf(score), width / 2f, height * 0.10f, textPaint);

        textPaint.setTextSize(width * 0.036f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, width / 2f, height * 0.04f, textPaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(0xCCFFFFFF);
        c.drawText("ROUND " + round, width * 0.04f, height * 0.04f, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(ghosts.isEmpty() ? 0x66FFFFFF : ghostColor(ghosts.size()));
        c.drawText(ghosts.size() + " echoes", width * 0.96f, height * 0.04f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // orb progress dots for this round
        float ox = width / 2f - (ORBS_PER_ROUND - 1) * width * 0.022f / 2f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < ORBS_PER_ROUND; i++) {
            paint.setColor(i < orbsGot ? 0xFFFFE082 : 0x33FFFFFF);
            c.drawCircle(ox + i * width * 0.022f, height * 0.125f, width * 0.006f, paint);
        }
    }

    private void drawMenu(Canvas c) {
        // demo: three fake echoes chasing each other in circles
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float t = menuT * 1.1f - i * 0.8f;
            float ex = width * (0.5f + 0.28f * (float) Math.cos(t));
            float ey = height * (0.52f + 0.16f * (float) Math.sin(t * 1.5f));
            int col = ghostColor(i + 1);
            paint.setColor((col & 0x00FFFFFF) | 0x46000000);
            c.drawCircle(ex, ey, playerR * 2.6f, paint);
            paint.setColor(col);
            c.drawCircle(ex, ey, playerR, paint);
        }
        float ptx = width * (0.5f + 0.28f * (float) Math.cos(menuT * 1.1f + 0.8f));
        float pty = height * (0.52f + 0.16f * (float) Math.sin((menuT * 1.1f + 0.8f) * 1.5f));
        paint.setColor(0x46FFFFFF);
        c.drawCircle(ptx, pty, playerR * 2.8f, paint);
        paint.setColor(Color.WHITE);
        c.drawCircle(ptx, pty, playerR, paint);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.19f);
        c.drawText("ECHO", width / 2f, height * 0.25f, textPaint);

        textPaint.setTextSize(width * 0.040f);
        textPaint.setColor(0xFF9CE8FF);
        c.drawText("every round you clear, your own path", width / 2f, height * 0.305f, textPaint);
        c.drawText("comes back to hunt you", width / 2f, height * 0.345f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("drag to glide  •  collect 5 orbs per round  •  graze for bonus",
                width / 2f, height * 0.395f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO PLAY", width / 2f, height * 0.78f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), width / 2f, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(games + " lives  •  " + roundsTotal + " echoes made", width / 2f, height * 0.92f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.082f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "CAUGHT BY YOURSELF", width / 2f, height * 0.30f, textPaint);

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
        c.drawText("outlived " + ghosts.size() + " echoes of yourself", width / 2f, height * 0.555f, textPaint);

        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RETRY", width / 2f, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 300) return "GOD MODE";
        if (s >= 200) return "LEGEND";
        if (s >= 120) return "MASTER";
        if (s >= 70) return "PHANTOM";
        if (s >= 30) return "DRIFTER";
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
        // relative drag: the dot mirrors finger movement without hiding
        // under the fingertip
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = true;
            lastTx = event.getX();
            lastTy = event.getY();
        } else if (action == MotionEvent.ACTION_MOVE && dragging) {
            float k = 1.25f;
            px += (event.getX() - lastTx) * k;
            py += (event.getY() - lastTy) * k;
            lastTx = event.getX();
            lastTy = event.getY();
            float m = playerR;
            px = Math.max(m, Math.min(width - m, px));
            py = Math.max(m, Math.min(height - m, py));
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
            playerR = w * 0.014f;
            killR = playerR;          // ghost contact distance = 2 * killR
            grazeR = w * 0.075f;
            orbR = w * 0.018f;
            if (px <= 0f) {
                px = w / 2f;
                py = hpx * 0.7f;
            }
            arenaShader = new RadialGradient(w / 2f, hpx * 0.45f, Math.max(w, hpx) * 0.85f,
                    new int[]{0x22152545, 0x00000000, 0x77000000},
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
