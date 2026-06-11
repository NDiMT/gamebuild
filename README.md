# BEACON

Ένα πρωτότυπο arcade παιχνίδι για Android: είσαι **ένας φάρος στο σκοτάδι**
και οι σκιές που σε ζυγώνουν **κινούνται μόνο όταν δεν τις κοιτάει το φως**
(weeping angels mechanic). Μία δέσμη, πολλοί εχθροί από όλες τις
κατευθύνσεις — διαλέγεις ποιος καίγεται και ποιος έρπει πιο κοντά.

## Gameplay

- **Άγγιξε / σύρε** για να στρέψεις τη δέσμη του φάρου προς το δάχτυλό σου.
  Όσο δεν αγγίζεις, ΟΛΕΣ οι σκιές προχωρούν.
- Σκιά μέσα στη δέσμη = **παγώνει και καίγεται** (κάθε τύπος θέλει
  διαφορετικό χρόνο). Σκιά στο σκοτάδι = πλησιάζει τον πυρήνα σου.
- Τύποι: **wisps** (γρήγορα, εύκαιγα), **brutes** (αργά τανκς),
  **shades** (σπιράλ πορεία, night 3+), **flickers** (κινούνται με
  ξεσπάσματα, night 5+). Clusters και pincer attacks από αντίθετες πλευρές.
- **Combo**: συνεχόμενα kills μέσα σε 1.6" ανεβάζουν πολλαπλασιαστή x2…x8.
- Έχεις **3 καρδιές** — σκιά που φτάνει στον πυρήνα κοστίζει μία.
- Κάθε 15" αλλάζει η **NIGHT**: περισσότερες, γρηγορότερες σκιές.
  Best score, νύχτες και συνολικά kills αποθηκεύονται τοπικά.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — κανένα asset, ~66 KB APK.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **BEACON** | [`dist/BEACON.apk`](dist/BEACON.apk) | Οι σκιές κινούνται μόνο στο σκοτάδι (τρέχων κώδικας στο `app/`) |
| **ECHO** | [`dist/ECHO.apk`](dist/ECHO.apk) | Αποφεύγεις looping replays του εαυτού σου |
| **SWARM** | [`dist/SWARM.apk`](dist/SWARM.apk) | Οδηγείς σμήνος πυγολαμπίδων με flocking AI |

Όλα υπογεγραμμένα (debug v1+v2+v3), Android 5.0+ (API 21). Εγκατάσταση:
μεταφορά στη συσκευή και άνοιγμα (απαιτεί "Install unknown apps").
Εγκαθίστανται παράλληλα (διαφορετικά package names).

## Build

### Με Android Studio / Gradle (κανονικός τρόπος)

Άνοιξε τον φάκελο στο Android Studio ή τρέξε:

```bash
gradle assembleDebug   # απαιτεί Android SDK
```

Υπάρχει και GitHub Actions workflow (`.github/workflows/android.yml`) που
χτίζει το debug APK σε κάθε push και το ανεβάζει ως artifact.

### Χωρίς Android SDK (offline pipeline)

Για περιβάλλοντα χωρίς πρόσβαση σε Google servers (το SDK κατεβαίνει μόνο από
Maven Central + GitHub mirrors):

```bash
./scripts/fetch-tools.sh    # κατεβάζει android.jar, aapt2, dx, signer
./scripts/build-offline.sh  # παράγει dist/BEACON.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/beacon/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # beam aiming, shadow AI, combos, nights, rendering
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner), **SWARM** (firefly
> flocking) και **ECHO** (self-replay ghosts) — ο κώδικάς τους υπάρχει
> στο git history.
