# KEPLER

Το πιο μαγικό παιχνίδι του repo: **slingshot σώματα και προσπάθησε να τα
βάλεις σε τροχιά** γύρω από πλανήτες — με υπόκρουση το **Πρελούδιο σε Ντο
μείζονα του Bach (BWV 846)**, συντεθειμένο νότα-νότα σε PCM με ήχο άρπας
κατά την εκκίνηση (κανένα αρχείο ήχου στο APK, seamless loop).

## Gameplay

- **Σύρε & άφησε** = σφεντόνα (με dotted preview τροχιάς).
- Κάθε πλανήτης έχει μια **λαμπερή ζώνη σύλληψης** (annulus). Κράτησε τον
  κομήτη μέσα της για **μία πλήρη περιφορά** (βλέπεις χρυσό τόξο προόδου
  και ποσοστό) — και «αποκοιμιέται»: γίνεται **φεγγάρι** που μένει σε
  τροχιά για πάντα.
- Κάθε level ζητά συγκεκριμένα φεγγάρια ανά πλανήτη (pips στο κέντρο του).
- Κλιμάκωση: 2ος πλανήτης (lvl 3), στενότερες ζώνες, **repulsors** (lvl 7+),
  3ος πλανήτης (lvl 8), περισσότερα φεγγάρια ανά πλανήτη, **κινούμενοι
  πλανήτες** (lvl 12+).
- Πρόσκρουση/χάσιμο στο κενό = απλώς νέος κομήτης, χωρίς τιμωρία.
  Άπειρα procedural levels, η πρόοδος σώζεται (level, σύνολο φεγγαριών).
- Αισθητική: νεφελώματα που παρασύρονται, αστέρια που τρεμοπαίζουν,
  fairy dust στις ζώνες, harp glissando σε κάθε σύλληψη.

Όλα procedural (Canvas/SurfaceView) — γραφικά, ήχοι ΚΑΙ μουσική. ~90 KB APK.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **KEPLER** | [`dist/KEPLER.apk`](dist/KEPLER.apk) | Orbital capture με μουσική Bach (τρέχων κώδικας στο `app/`) |
| **ORBIT** | [`dist/ORBIT.apk`](dist/ORBIT.apk) | Gravity golf με άπειρα levels |
| **TETHER** | [`dist/TETHER.apk`](dist/TETHER.apk) | Σκοινί-λεπίδα με physics flail |
| **BEACON** | [`dist/BEACON.apk`](dist/BEACON.apk) | Οι σκιές κινούνται μόνο στο σκοτάδι + survivor upgrades |
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
./scripts/build-offline.sh  # παράγει dist/KEPLER.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/kepler/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # orbital capture, gravity, level generation, rendering
  SoundFx.java        # SFX + Bach BWV 846 synthesized into a looping track
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner), **SWARM** (firefly
> flocking) και **ECHO** (self-replay ghosts) — ο κώδικάς τους υπάρχει
> στο git history.
