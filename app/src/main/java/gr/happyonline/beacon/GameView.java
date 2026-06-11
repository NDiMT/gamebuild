package gr.happyonline.beacon;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
 * BEACON - they only move in the dark.
 *
 * You are a lighthouse in an endless night. Shadows creep toward your
 * core from every direction, but they freeze - and slowly burn away -
 * inside your beam. Burned shadows drop motes of light: collect them to
 * level up and pick survivor-style upgrades (wider beams, twin beams,
 * a burning halo, novas, orbiting lanterns...) until your little light
 * becomes a sun. One beam, many shadows - choose who burns.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    private static final int STATE_MENU = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_CHOOSE = 2;  // level-up card pick
    private static final int STATE_DYING = 3;
    private static final int STATE_OVER = 4;

    private static final int E_WISP = 0;
    private static final int E_BRUTE = 1;
    private static final int E_SHADE = 2;
    private static final int E_FLICKER = 3;

    // upgrade ids
    private static final int UP_WIDE = 0;
    private static final int UP_FOCUS = 1;
    private static final int UP_TWIN = 2;
    private static final int UP_HALO = 3;
    private static final int UP_NOVA = 4;
    private static final int UP_AFTER = 5;
    private static final int UP_LANTERN = 6;
    private static final int UP_MEND = 7;
    private static final int UP_BOUNTY = 8;

    private static final String[] UP_NAME = {
            "WIDE BEAM", "FOCUS", "TWIN BEAM", "HALO",
            "NOVA", "AFTERBURN", "LANTERN", "MEND", "BOUNTY"};
    private static final String[] UP_DESC = {
            "your light reaches wider",
            "shadows burn faster",
            "one more beam of light",
            "a burning ring guards the core",
            "periodic blast hurls shadows back",
            "shadows keep burning in the dark",
            "an orbiting guardian light",
            "+1 heart, all hearts refilled",
            "+60 score, right now"};
    private static final int[] UP_MAX = {4, 4, 2, 4, 3, 3, 3, 2, 99};

    private static final float TAU = (float) (Math.PI * 2.0);
    private static final float COMBO_WINDOW = 1.6f;
    private static final int MAX_COMBO = 8;
    private static final float NIGHT_LEN = 15f;
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
    private float cx, cy, coreR, spawnR;

    // run state
    private int state = STATE_MENU;
    private float runTime;
    private int night;
    private int score;
    private int kills;
    private int hearts;
    private int maxHearts;
    private int combo;
    private float comboTimer;
    private float hurtInvuln;
    private float spawnTimer;
    private float overTimer;
    private boolean newBestShown;

    // survivor systems
    private final int[] upLvl = new int[9];
    private int xp;
    private int xpNeed;
    private int playerLevel;
    private final int[] choice = new int[3];
    private float novaTimer;
    private float novaAnim;
    private float lanternSpin;

    // beam
    private boolean lit;
    private float beamAngle = -TAU / 4f;

    // persistent stats
    private int best;
    private int games;
    private int killsTotal;

    // presentation
    private float shake;
    private float flash;
    private float corePulse;
    private float menuT;

    private final ArrayList<Enemy> enemies = new ArrayList<Enemy>();
    private final ArrayList<Mote> motes = new ArrayList<Mote>();
    private final ArrayList<Particle> particles = new ArrayList<Particle>();
    private final ArrayList<FloatText> texts = new ArrayList<FloatText>();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path beamPath = new Path();
    private final RectF rect = new RectF();
    private Shader nightShader;
    private Shader beamShader;
    private Shader haloShader;

    private static class Enemy {
        int type;
        float angle, dist;
        float hp, maxHp;
        float speed;
        float phase, drift;
        float dot;        // afterburn seconds left
        float stun;       // nova daze
        boolean litNow;
    }

    private static class Mote {
        float x, y, vx, vy;
        int xp;
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

        prefs = context.getSharedPreferences("beacon", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        games = prefs.getInt("games", 0);
        killsTotal = prefs.getInt("kills", 0);

        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        sfx = new SoundFx();

        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------ derived powers

    private float coneHalf() {
        return 0.26f + 0.05f * upLvl[UP_WIDE];
    }

    private int beamCount() {
        return 1 + upLvl[UP_TWIN];
    }

    private float burnRate() {
        return 1f + 0.35f * upLvl[UP_FOCUS];
    }

    private float haloRadius() {
        return upLvl[UP_HALO] == 0 ? 0f : width * (0.115f + 0.028f * upLvl[UP_HALO]);
    }

    private float haloDps() {
        return 0.30f + 0.18f * upLvl[UP_HALO];
    }

    private float novaInterval() {
        return 9.5f - 1.6f * upLvl[UP_NOVA];
    }

    private float afterburnDur() {
        return 0.4f + 0.6f * upLvl[UP_AFTER];
    }

    // ------------------------------------------------------------------ run

    private void startRun() {
        synchronized (lock) {
            state = STATE_PLAYING;
            runTime = 0f;
            night = 1;
            score = 0;
            kills = 0;
            maxHearts = 3;
            hearts = maxHearts;
            combo = 0;
            comboTimer = 0f;
            hurtInvuln = 0f;
            spawnTimer = 1.2f;
            overTimer = 0f;
            newBestShown = false;
            lit = false;
            for (int i = 0; i < upLvl.length; i++) {
                upLvl[i] = 0;
            }
            xp = 0;
            xpNeed = 6;
            playerLevel = 1;
            novaTimer = 0f;
            novaAnim = 0f;
            enemies.clear();
            motes.clear();
            particles.clear();
            texts.clear();
            addText("NIGHT 1", cx, height * 0.3f, 0xFFFFE082);
            sfx.play(SoundFx.START);
        }
    }

    private void gameOver() {
        state = STATE_DYING;
        overTimer = DEATH_SLOWMO;
        shake = 1f;
        flash = 1f;
        sfx.play(SoundFx.OVER);
        buzz(200);
        burst(cx, cy, 50, 0xFFFFC940, 1.8f);

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

    private void spawnEnemy(float angle) {
        Enemy e = new Enemy();
        int roll = rng.nextInt(10);
        if (night >= 5 && roll < 2) {
            e.type = E_FLICKER;
            e.hp = 0.8f;
            e.speed = width * 0.16f;
        } else if (night >= 3 && roll < 4) {
            e.type = E_SHADE;
            e.hp = 1.1f;
            e.speed = width * 0.105f;
            e.drift = (rng.nextBoolean() ? 1f : -1f) * (0.25f + rng.nextFloat() * 0.2f);
        } else if (roll < 6) {
            e.type = E_BRUTE;
            e.hp = 2.3f;
            e.speed = width * 0.055f;
        } else {
            e.type = E_WISP;
            e.hp = 0.55f;
            e.speed = width * 0.13f;
        }
        // shadows toughen up as the player's arsenal grows
        e.hp *= 1f + 0.09f * (night - 1);
        e.speed *= 1f + 0.07f * (night - 1);
        e.maxHp = e.hp;
        e.angle = angle;
        e.dist = spawnR * (0.95f + rng.nextFloat() * 0.15f);
        e.phase = rng.nextFloat() * TAU;
        enemies.add(e);
    }

    private void spawnWave() {
        float a = rng.nextFloat() * TAU;
        int cluster = 1;
        int r = rng.nextInt(10);
        if (night >= 2 && r < 3) cluster = 2;
        if (night >= 4 && r >= 8) cluster = 3;
        for (int i = 0; i < cluster; i++) {
            spawnEnemy(a + (i - (cluster - 1) / 2f) * 0.35f);
        }
        if (night >= 3 && rng.nextInt(10) < 2) {
            spawnEnemy(a + TAU / 2f + (rng.nextFloat() - 0.5f) * 0.5f);
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
            if (corePulse > 0f) corePulse = Math.max(0f, corePulse - rawDt * 3f);
            if (hurtInvuln > 0f) hurtInvuln -= rawDt;
            if (novaAnim > 0f) novaAnim = Math.max(0f, novaAnim - rawDt * 2.2f);
            lanternSpin += rawDt * 1.7f;

            updateParticles(rawDt);
            updateTexts(rawDt);
            updateMotes(rawDt);

            if (state == STATE_PLAYING) {
                runTime += dt;

                int newNight = 1 + (int) (runTime / NIGHT_LEN);
                if (newNight > night) {
                    night = newNight;
                    addText("NIGHT " + night, cx, height * 0.3f, 0xFFFFE082);
                    sfx.play(SoundFx.NIGHT);
                }

                if (combo > 0) {
                    comboTimer -= dt;
                    if (comboTimer <= 0f) {
                        combo = 0;
                    }
                }

                spawnTimer -= dt;
                if (spawnTimer <= 0f) {
                    spawnWave();
                    float gap = Math.max(0.6f, 2.1f - 0.13f * night);
                    spawnTimer = gap * (0.8f + rng.nextFloat() * 0.4f);
                }

                if (upLvl[UP_NOVA] > 0) {
                    novaTimer -= dt;
                    if (novaTimer <= 0f) {
                        novaTimer = novaInterval();
                        fireNova();
                    }
                }

                stepEnemies(dt);

                if (xp >= xpNeed) {
                    levelUp();
                }

                if (!newBestShown && best > 0 && score > best) {
                    newBestShown = true;
                    addText("NEW BEST!", cx, height * 0.24f, 0xFFFFD740);
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

    private boolean inAnyBeam(Enemy e, float eR) {
        if (!lit) {
            return false;
        }
        float slack = (float) Math.atan2(eR, Math.max(e.dist, coreR));
        int n = beamCount();
        for (int b = 0; b < n; b++) {
            float a = beamAngle + b * (TAU / n);
            if (angularDist(e.angle, a) < coneHalf() + slack) {
                return true;
            }
        }
        return false;
    }

    private void stepEnemies(float dt) {
        float haloR = haloRadius();
        int lanterns = upLvl[UP_LANTERN];
        float lanternOrbit = width * 0.18f;
        float lanternR = width * 0.030f;

        for (int i = enemies.size() - 1; i >= 0; i--) {
            Enemy e = enemies.get(i);
            float eR = enemyRadius(e);
            boolean inBeam = inAnyBeam(e, eR);
            e.litNow = inBeam;

            float burn = 0f;
            if (inBeam) {
                burn += burnRate();
                e.dot = afterburnDur() * (upLvl[UP_AFTER] > 0 ? 1f : 0f);
            } else {
                if (e.dot > 0f) {
                    e.dot -= dt;
                    burn += 0.55f * burnRate();
                }
                float v = e.speed;
                if (e.stun > 0f) {
                    e.stun -= dt;
                    v = 0f;
                } else if (e.type == E_FLICKER) {
                    e.phase += dt * 5f;
                    float burstK = (float) Math.sin(e.phase);
                    v *= burstK > 0.2f ? 2.4f : 0.15f;
                } else if (e.type == E_SHADE) {
                    e.angle += e.drift * dt;
                }
                e.dist -= v * dt;
            }

            // halo: walking through fire
            if (haloR > 0f && e.dist < haloR + eR) {
                burn += haloDps();
            }
            // lanterns: orbiting guardian lights
            if (lanterns > 0) {
                float ex = cx + (float) Math.cos(e.angle) * e.dist;
                float ey = cy + (float) Math.sin(e.angle) * e.dist;
                for (int l = 0; l < lanterns; l++) {
                    float la = lanternSpin + l * (TAU / lanterns);
                    float lx = cx + (float) Math.cos(la) * lanternOrbit;
                    float ly = cy + (float) Math.sin(la) * lanternOrbit;
                    float dx = ex - lx, dy = ey - ly;
                    float rr = lanternR + eR;
                    if (dx * dx + dy * dy < rr * rr) {
                        burn += 1.1f;
                        break;
                    }
                }
            }

            if (burn > 0f) {
                e.hp -= burn * dt;
                if (rng.nextInt(3) == 0) {
                    ember(e);
                }
                if (e.hp <= 0f) {
                    enemies.remove(i);
                    kill(e);
                    continue;
                }
            }

            if (e.dist <= coreR + eR * 0.6f) {
                enemies.remove(i);
                coreHit(e);
            }
        }
    }

    private void fireNova() {
        novaAnim = 1f;
        float push = width * (0.08f + 0.04f * upLvl[UP_NOVA]);
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            e.dist = Math.min(spawnR, e.dist + push);
            e.stun = 0.5f;
        }
        burst(cx, cy, 26, 0xFFFFE082, 1.3f);
        sfx.play(SoundFx.NOVA);
        buzz(40);
    }

    private void levelUp() {
        xp -= xpNeed;
        playerLevel++;
        xpNeed = 6 + (int) (playerLevel * 4.5f);

        // offer 3 distinct upgrades that still have room to grow
        ArrayList<Integer> pool = new ArrayList<Integer>();
        for (int t = 0; t <= UP_LANTERN; t++) {
            if (upLvl[t] < UP_MAX[t]) {
                pool.add(Integer.valueOf(t));
            }
        }
        if (maxHearts < 5 && upLvl[UP_MEND] < UP_MAX[UP_MEND]) {
            pool.add(Integer.valueOf(UP_MEND));
        }
        for (int i = 0; i < 3; i++) {
            if (pool.isEmpty()) {
                choice[i] = UP_BOUNTY;
            } else {
                choice[i] = pool.remove(rng.nextInt(pool.size())).intValue();
            }
        }
        state = STATE_CHOOSE;
        sfx.play(SoundFx.LEVELUP);
        buzz(30);
    }

    private void applyChoice(int type) {
        if (type == UP_BOUNTY) {
            score += 60;
            addText("+60", cx, cy - coreR * 3f, 0xFFFFE082);
        } else if (type == UP_MEND) {
            upLvl[UP_MEND]++;
            maxHearts = Math.min(5, maxHearts + 1);
            hearts = maxHearts;
            addText("MENDED", cx, cy - coreR * 3f, 0xFFFF7B8C);
        } else {
            upLvl[type]++;
            addText(UP_NAME[type] + " " + roman(upLvl[type]), cx, cy - coreR * 3f, 0xFFFFE082);
            if (type == UP_NOVA && upLvl[UP_NOVA] == 1) {
                novaTimer = novaInterval();
            }
        }
        state = STATE_PLAYING;
        hurtInvuln = Math.max(hurtInvuln, 0.6f);
        sfx.play(SoundFx.PICK);
    }

    private static String roman(int n) {
        switch (n) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            default: return String.valueOf(n);
        }
    }

    private float enemyRadius(Enemy e) {
        float base;
        switch (e.type) {
            case E_BRUTE:
                base = 0.040f;
                break;
            case E_SHADE:
                base = 0.026f;
                break;
            case E_FLICKER:
                base = 0.020f;
                break;
            default:
                base = 0.022f;
        }
        return width * base;
    }

    private void kill(Enemy e) {
        combo = Math.min(MAX_COMBO, combo + 1);
        comboTimer = COMBO_WINDOW;
        int pts = combo;
        score += pts;
        kills++;
        corePulse = 1f;

        float ex = cx + (float) Math.cos(e.angle) * e.dist;
        float ey = cy + (float) Math.sin(e.angle) * e.dist;
        burst(ex, ey, 12, 0xFFFFC940, 0.9f);
        addText("+" + pts + (combo > 1 ? "  x" + combo : ""), ex, ey, 0xFFFFE082);
        sfx.play(SoundFx.KILL_0 + Math.min(combo - 1, 7));
        buzz(14);

        // drop motes of light worth more for tougher shadows
        int worth = e.type == E_BRUTE ? 3 : (e.type == E_WISP ? 1 : 2);
        for (int i = 0; i < worth; i++) {
            Mote m = new Mote();
            m.x = ex + (rng.nextFloat() - 0.5f) * width * 0.03f;
            m.y = ey + (rng.nextFloat() - 0.5f) * width * 0.03f;
            float a = rng.nextFloat() * TAU;
            float s = width * (0.1f + rng.nextFloat() * 0.15f);
            m.vx = (float) Math.cos(a) * s;
            m.vy = (float) Math.sin(a) * s;
            m.xp = 1;
            motes.add(m);
        }
    }

    private void updateMotes(float dt) {
        if (state != STATE_PLAYING && state != STATE_CHOOSE) {
            return;
        }
        for (int i = motes.size() - 1; i >= 0; i--) {
            Mote m = motes.get(i);
            float dx = cx - m.x, dy = cy - m.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 1f;
            float acc = width * 4.5f;
            m.vx = (m.vx + dx / d * acc * dt) * (1f - 2.2f * dt);
            m.vy = (m.vy + dy / d * acc * dt) * (1f - 2.2f * dt);
            m.x += m.vx * dt;
            m.y += m.vy * dt;
            if (d < coreR * 1.1f) {
                motes.remove(i);
                xp += m.xp;
                corePulse = Math.max(corePulse, 0.4f);
            }
        }
    }

    private void coreHit(Enemy e) {
        float ex = cx + (float) Math.cos(e.angle) * (coreR + enemyRadius(e));
        float ey = cy + (float) Math.sin(e.angle) * (coreR + enemyRadius(e));
        burst(ex, ey, 18, 0xFF8090C0, 1.0f);

        if (hurtInvuln > 0f) {
            return;
        }
        hurtInvuln = 1.0f;
        hearts--;
        combo = 0;
        shake = Math.max(shake, 0.8f);
        flash = Math.max(flash, 0.6f);
        sfx.play(SoundFx.HIT);
        buzz(120);
        if (hearts <= 0) {
            gameOver();
        } else {
            addText(hearts == 1 ? "LAST LIGHT!" : "THE LIGHT DIMS",
                    cx, height * 0.36f, 0xFFFF3D58);
        }
    }

    private static float angularDist(float a, float b) {
        float d = (a - b) % TAU;
        if (d < 0) d += TAU;
        return d > TAU / 2f ? TAU - d : d;
    }

    // ------------------------------------------------------------ particles

    private void ember(Enemy e) {
        float ex = cx + (float) Math.cos(e.angle) * e.dist;
        float ey = cy + (float) Math.sin(e.angle) * e.dist;
        Particle p = new Particle();
        float r = enemyRadius(e);
        p.x = ex + (rng.nextFloat() - 0.5f) * r * 2f;
        p.y = ey + (rng.nextFloat() - 0.5f) * r * 2f;
        p.vx = (rng.nextFloat() - 0.5f) * width * 0.05f;
        p.vy = -width * (0.04f + rng.nextFloat() * 0.06f);
        p.maxLife = p.life = 0.4f + rng.nextFloat() * 0.3f;
        p.size = width * 0.004f;
        p.color = 0xFFFFB860;
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
            c.drawColor(0xFF05060C);
            if (nightShader != null) {
                paint.setShader(nightShader);
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

            if (state == STATE_MENU) {
                beamAngle = menuT * 0.7f;
                drawBeams(c);
            } else if (lit && (state == STATE_PLAYING || state == STATE_DYING)) {
                drawBeams(c);
            }
            if (state != STATE_MENU) {
                drawHalo(c);
                drawNova(c);
                drawEnemies(c);
                drawLanterns(c);
                drawMotes(c);
            }
            drawParticles(c);
            drawCore(c);
            drawTexts(c);

            if (shake > 0f) {
                c.restore();
            }

            if (state == STATE_MENU) {
                drawMenu(c);
            } else {
                drawHud(c);
            }
            if (state == STATE_CHOOSE) {
                drawChoice(c);
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
        for (int i = 0; i < 30; i++) {
            float sx = (i * 379f + 53f) % width;
            float sy = (i * 233f + 89f) % height;
            int a = 24 + (int) (18 * Math.sin(menuT * 1.3f + i));
            paint.setColor(Color.argb(Math.max(8, a), 200, 215, 255));
            c.drawCircle(sx, sy, Math.max(1.5f, width * 0.0014f), paint);
        }
    }

    /** Soft gradient cones with a living flicker - the heart of the look. */
    private void drawBeams(Canvas c) {
        float reach = width + height;
        float flick = 0.88f + 0.08f * (float) Math.sin(menuT * 31f)
                + 0.04f * (float) Math.sin(menuT * 47f);
        int n = state == STATE_MENU ? 1 : beamCount();
        float half = coneHalf();

        for (int b = 0; b < n; b++) {
            float ang = beamAngle + b * (TAU / n);
            // outer soft cone with radial falloff
            if (beamShader != null) {
                paint.setShader(beamShader);
                paint.setAlpha((int) (255 * flick));
                wedge(c, ang, half * 1.35f, reach);
                // inner hot cone
                paint.setAlpha((int) (190 * flick));
                wedge(c, ang, half * 0.55f, reach);
                paint.setShader(null);
                paint.setAlpha(255);
            }
            // crisp edge rays
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, width * 0.0022f));
            paint.setColor(Color.argb((int) (70 * flick), 255, 235, 170));
            for (int s = -1; s <= 1; s += 2) {
                float ea = ang + s * half;
                c.drawLine(cx + (float) Math.cos(ea) * coreR,
                        cy + (float) Math.sin(ea) * coreR,
                        cx + (float) Math.cos(ea) * reach,
                        cy + (float) Math.sin(ea) * reach, paint);
            }
        }
    }

    private void wedge(Canvas c, float ang, float half, float reach) {
        paint.setStyle(Paint.Style.FILL);
        beamPath.reset();
        beamPath.moveTo(cx, cy);
        int segs = 8;
        for (int s = 0; s <= segs; s++) {
            float a = ang - half + (2f * half) * s / segs;
            beamPath.lineTo(cx + (float) Math.cos(a) * reach,
                    cy + (float) Math.sin(a) * reach);
        }
        beamPath.close();
        c.drawPath(beamPath, paint);
    }

    private void drawHalo(Canvas c) {
        float r = haloRadius();
        if (r <= 0f) {
            return;
        }
        float pulse = 1f + 0.03f * (float) Math.sin(menuT * 6f);
        if (haloShader != null) {
            paint.setShader(haloShader);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, r * 1.25f * pulse, paint);
            paint.setShader(null);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.006f);
        paint.setColor(0x88FFB860);
        c.drawCircle(cx, cy, r * pulse, paint);
        paint.setStrokeWidth(width * 0.014f);
        paint.setColor(0x2EFFB860);
        c.drawCircle(cx, cy, r * pulse, paint);
    }

    private void drawNova(Canvas c) {
        if (novaAnim <= 0f) {
            return;
        }
        float k = 1f - novaAnim;
        float r = coreR + (width * 0.55f) * k;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width * 0.02f * novaAnim);
        paint.setColor(Color.argb((int) (novaAnim * 180f), 255, 230, 150));
        c.drawCircle(cx, cy, r, paint);
        paint.setStrokeWidth(width * 0.05f * novaAnim);
        paint.setColor(Color.argb((int) (novaAnim * 60f), 255, 210, 120));
        c.drawCircle(cx, cy, r, paint);
    }

    private void drawLanterns(Canvas c) {
        int lanterns = upLvl[UP_LANTERN];
        if (lanterns == 0) {
            return;
        }
        float orbit = width * 0.18f;
        paint.setStyle(Paint.Style.FILL);
        for (int l = 0; l < lanterns; l++) {
            float la = lanternSpin + l * (TAU / lanterns);
            float lx = cx + (float) Math.cos(la) * orbit;
            float ly = cy + (float) Math.sin(la) * orbit;
            paint.setColor(0x33FFE082);
            c.drawCircle(lx, ly, width * 0.055f, paint);
            paint.setColor(0x88FFE8A0);
            c.drawCircle(lx, ly, width * 0.022f, paint);
            paint.setColor(0xFFFFF4CC);
            c.drawCircle(lx, ly, width * 0.011f, paint);
        }
        // faint orbit path
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, width * 0.0015f));
        paint.setColor(0x22FFE082);
        c.drawCircle(cx, cy, orbit, paint);
    }

    private void drawMotes(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < motes.size(); i++) {
            Mote m = motes.get(i);
            float tw = 0.7f + 0.3f * (float) Math.sin(menuT * 9f + i);
            paint.setColor(Color.argb((int) (90 * tw), 255, 240, 170));
            c.drawCircle(m.x, m.y, width * 0.012f, paint);
            paint.setColor(0xFFFFF2B0);
            c.drawCircle(m.x, m.y, width * 0.005f, paint);
        }
    }

    private void drawEnemies(Canvas c) {
        for (int i = 0; i < enemies.size(); i++) {
            Enemy e = enemies.get(i);
            float r = enemyRadius(e);
            float hurt = 1f - e.hp / e.maxHp;
            float size = r * (1f - 0.35f * hurt);
            float ex = cx + (float) Math.cos(e.angle) * e.dist;
            float ey = cy + (float) Math.sin(e.angle) * e.dist;
            float wob = 1f + 0.08f * (float) Math.sin(menuT * 5f + e.phase);
            boolean burning = e.litNow || e.dot > 0f;

            paint.setStyle(Paint.Style.FILL);
            if (burning) {
                paint.setColor(Color.argb(70, 255, 200, 110));
                c.drawCircle(ex, ey, size * 2.0f * wob, paint);
                paint.setColor(0xFF3A3550);
                c.drawCircle(ex, ey, size * wob, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width * 0.004f);
                paint.setColor(Color.argb((int) (140 + 100 * hurt), 255, 210, 120));
                c.drawCircle(ex, ey, size * wob, paint);
            } else {
                paint.setColor(Color.argb(60, 10, 12, 24));
                c.drawCircle(ex, ey, size * 1.9f * wob, paint);
                paint.setColor(e.stun > 0f ? 0xFF222A45 : 0xFF14182B);
                c.drawCircle(ex, ey, size * wob, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            float look = (float) Math.atan2(cy - ey, cx - ex);
            float eyeOff = size * 0.38f;
            float perpX = (float) Math.cos(look + TAU / 4f) * eyeOff;
            float perpY = (float) Math.sin(look + TAU / 4f) * eyeOff;
            float fwdX = (float) Math.cos(look) * size * 0.25f;
            float fwdY = (float) Math.sin(look) * size * 0.25f;
            paint.setColor(burning ? 0xFFFFE8B0 : 0xFF8FA8FF);
            float er = Math.max(2f, size * 0.16f);
            c.drawCircle(ex + fwdX + perpX, ey + fwdY + perpY, er, paint);
            c.drawCircle(ex + fwdX - perpX, ey + fwdY - perpY, er, paint);
        }
    }

    private void drawCore(Canvas c) {
        float pulse = 1f + corePulse * 0.12f
                + 0.04f * (float) Math.sin(menuT * 3f);
        boolean blink = hurtInvuln > 0f && ((int) (hurtInvuln * 8f)) % 2 == 0;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(blink ? 30 : 60, 255, 222, 130));
        c.drawCircle(cx, cy, coreR * 2.6f * pulse, paint);
        paint.setColor(Color.argb(blink ? 60 : 110, 255, 226, 140));
        c.drawCircle(cx, cy, coreR * 1.6f * pulse, paint);
        paint.setColor(blink ? 0x99FFF2CC : 0xFFFFF2CC);
        c.drawCircle(cx, cy, coreR * pulse, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, coreR * 0.45f * pulse, paint);
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
        c.drawText(String.valueOf(score), cx, height * 0.095f, textPaint);

        textPaint.setTextSize(width * 0.034f);
        textPaint.setColor(0x88FFFFFF);
        c.drawText("BEST " + best, cx, height * 0.038f, textPaint);

        // XP bar + level
        float bw = width * 0.5f;
        float by = height * 0.115f;
        float k = Math.min(1f, xp / (float) xpNeed);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        c.drawRect(cx - bw / 2f, by, cx + bw / 2f, by + height * 0.006f, paint);
        paint.setColor(0xFFFFE082);
        c.drawRect(cx - bw / 2f, by, cx - bw / 2f + bw * k, by + height * 0.006f, paint);
        textPaint.setTextSize(width * 0.030f);
        textPaint.setColor(0xAAFFE082);
        c.drawText("LVL " + playerLevel, cx, by + height * 0.026f, textPaint);

        // hearts
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(width * 0.048f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxHearts; i++) {
            sb.append(i < hearts ? "♥ " : "♡ ");
        }
        textPaint.setColor(hearts == 1 ? 0xFFFF3D58 : 0xFFFF7B8C);
        c.drawText(sb.toString().trim(), width * 0.04f, height * 0.045f, textPaint);

        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setTextSize(width * 0.034f);
        textPaint.setColor(0xFFFFE082);
        int secs = (int) runTime;
        c.drawText("NIGHT " + night + "  " + (secs / 60) + ":"
                + (secs % 60 < 10 ? "0" : "") + (secs % 60),
                width * 0.96f, height * 0.045f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);

        if (combo > 1) {
            textPaint.setTextSize(width * 0.05f);
            textPaint.setColor(0xFFFFC940);
            c.drawText("x" + combo, cx, height * 0.175f, textPaint);
        }
        if (state == STATE_PLAYING && !lit) {
            float blink = 0.4f + 0.4f * (float) Math.sin(menuT * 6f);
            textPaint.setTextSize(width * 0.038f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 120, 130));
            c.drawText("THE DARK IS MOVING — touch to shine", cx, height * 0.95f, textPaint);
        }
    }

    private void drawChoice(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC000000);
        c.drawRect(0, 0, width, height, paint);

        textPaint.setColor(0xFFFFE082);
        textPaint.setTextSize(width * 0.075f);
        c.drawText("LEVEL " + playerLevel, cx, height * 0.20f, textPaint);
        textPaint.setTextSize(width * 0.038f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("the light grows — choose a gift", cx, height * 0.245f, textPaint);

        for (int i = 0; i < 3; i++) {
            cardRect(i);
            int type = choice[i];

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF141A2E);
            c.drawRoundRect(rect, width * 0.03f, width * 0.03f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width * 0.004f);
            paint.setColor(0xFFFFC940);
            c.drawRoundRect(rect, width * 0.03f, width * 0.03f, paint);

            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(width * 0.052f);
            textPaint.setColor(Color.WHITE);
            String name = UP_NAME[type];
            if (type != UP_BOUNTY && type != UP_MEND) {
                name += "  " + roman(upLvl[type] + 1);
            }
            c.drawText(name, rect.left + width * 0.05f, rect.top + rect.height() * 0.42f, textPaint);
            textPaint.setTextSize(width * 0.036f);
            textPaint.setColor(0x99FFFFFF);
            c.drawText(UP_DESC[type], rect.left + width * 0.05f, rect.top + rect.height() * 0.74f, textPaint);
            textPaint.setTextAlign(Paint.Align.CENTER);

            // level pips
            if (type != UP_BOUNTY) {
                paint.setStyle(Paint.Style.FILL);
                int max = UP_MAX[type];
                float px0 = rect.right - width * 0.05f - (max - 1) * width * 0.028f;
                for (int p2 = 0; p2 < max; p2++) {
                    paint.setColor(p2 < upLvl[type] + 1 ? 0xFFFFE082 : 0x33FFFFFF);
                    c.drawCircle(px0 + p2 * width * 0.028f,
                            rect.top + rect.height() * 0.34f, width * 0.008f, paint);
                }
            }
        }
    }

    private void cardRect(int i) {
        float cw = width * 0.84f;
        float ch = height * 0.115f;
        float cyc = height * (0.36f + 0.155f * i);
        rect.set(cx - cw / 2f, cyc - ch / 2f, cx + cw / 2f, cyc + ch / 2f);
    }

    private void drawMenu(Canvas c) {
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(width * 0.17f);
        c.drawText("BEACON", cx, height * 0.22f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0xFFFFE082);
        c.drawText("they only move in the dark", cx, height * 0.27f, textPaint);
        textPaint.setColor(0x99FFFFFF);
        textPaint.setTextSize(width * 0.038f);
        c.drawText("touch to aim your light  •  burned shadows drop light motes",
                cx, height * 0.315f, textPaint);
        c.drawText("level up  •  twin beams, halos, novas, lanterns...",
                cx, height * 0.35f, textPaint);

        float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 4f);
        textPaint.setTextSize(width * 0.07f);
        textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
        c.drawText("TAP TO SHINE", cx, height * 0.80f, textPaint);

        textPaint.setTextSize(width * 0.042f);
        textPaint.setColor(0x99FFFFFF);
        c.drawText("BEST  " + best + "      " + rankFor(best), cx, height * 0.88f, textPaint);
        textPaint.setColor(0x55FFFFFF);
        c.drawText(games + " nights  •  " + killsTotal + " shadows burned", cx, height * 0.92f, textPaint);
    }

    private void drawGameOver(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB8000000);
        c.drawRect(0, 0, width, height, paint);

        boolean isBest = score >= best && score > 0;
        textPaint.setTextSize(width * 0.082f);
        textPaint.setColor(isBest ? 0xFFFFD740 : Color.WHITE);
        c.drawText(isBest ? "NEW BEST!" : "THE LIGHT WENT OUT", cx, height * 0.30f, textPaint);

        textPaint.setTextSize(width * 0.20f);
        textPaint.setColor(Color.WHITE);
        c.drawText(String.valueOf(score), cx, height * 0.45f, textPaint);

        textPaint.setTextSize(width * 0.043f);
        textPaint.setColor(0xAAFFFFFF);
        String sub;
        if (!isBest && best > 0 && score >= best * 0.9f) {
            sub = "SO CLOSE!  best is " + best;
        } else {
            sub = "best " + best + "  •  " + rankFor(best);
        }
        c.drawText(sub, cx, height * 0.51f, textPaint);
        int secs = (int) runTime;
        c.drawText(kills + " burned  •  LVL " + playerLevel + "  •  night " + night
                + "  •  " + (secs / 60) + ":" + (secs % 60 < 10 ? "0" : "") + (secs % 60),
                cx, height * 0.555f, textPaint);

        if (overTimer > 0.4f) {
            float blink = 0.55f + 0.45f * (float) Math.sin(menuT * 5f);
            textPaint.setTextSize(width * 0.065f);
            textPaint.setColor(Color.argb((int) (blink * 255f), 255, 255, 255));
            c.drawText("TAP TO RELIGHT", cx, height * 0.72f, textPaint);
        }
    }

    private static String rankFor(int s) {
        if (s >= 800) return "GOD MODE";
        if (s >= 500) return "LEGEND";
        if (s >= 280) return "MASTER";
        if (s >= 130) return "LIGHTKEEPER";
        if (s >= 45) return "WATCHER";
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
                        aimBeam(event);
                    }
                    break;
                case STATE_PLAYING:
                    if (action == MotionEvent.ACTION_DOWN
                            || action == MotionEvent.ACTION_MOVE) {
                        lit = true;
                        aimBeam(event);
                    } else if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        lit = false;
                    }
                    break;
                case STATE_CHOOSE:
                    if (action == MotionEvent.ACTION_DOWN) {
                        lit = false;
                        for (int i = 0; i < 3; i++) {
                            cardRect(i);
                            if (rect.contains(event.getX(), event.getY())) {
                                applyChoice(choice[i]);
                                break;
                            }
                        }
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

    private void aimBeam(MotionEvent event) {
        float dx = event.getX() - cx;
        float dy = event.getY() - cy;
        if (dx * dx + dy * dy > coreR * coreR * 0.2f) {
            beamAngle = (float) Math.atan2(dy, dx);
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
            cx = w / 2f;
            cy = hpx * 0.5f;
            coreR = w * 0.055f;
            spawnR = (float) Math.hypot(w / 2f, hpx / 2f) + w * 0.08f;
            nightShader = new RadialGradient(cx, cy, Math.max(w, hpx) * 0.85f,
                    new int[]{0x33251A38, 0x00000000, 0x88000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            beamShader = new RadialGradient(cx, cy, (float) Math.hypot(w, hpx) * 0.72f,
                    new int[]{Color.argb(120, 255, 232, 150),
                            Color.argb(48, 255, 210, 120), 0x00000000},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
            haloShader = new RadialGradient(cx, cy, w * 0.26f,
                    new int[]{0x00000000, 0x14FFB860, 0x3DFFB860, 0x00000000},
                    new float[]{0f, 0.55f, 0.82f, 1f}, Shader.TileMode.CLAMP);
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
