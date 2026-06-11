# TETHER

Ένα πρωτότυπο physics arcade παιχνίδι για Android: δύο σφαίρες δεμένες με
σκοινί από φως. Σέρνεις τη λευκή — η κυανή σύντροφός της εκσφενδονίζεται
από πίσω με πραγματική ορμή, σαν flail. **Το σκοινί ανάμεσά τους είναι η
λεπίδα**: ό,τι το διασχίσει κόβεται στα δύο.

## Gameplay

- **Σύρε** τη λευκή σφαίρα (relative drag). Η σύντροφος ακολουθεί με
  φυσική ελατηρίου — στροβίλισέ την για να χτίσεις ορμή.
- **Το σκοινί κόβει** ό,τι ακουμπήσει. Η σύντροφος σφαίρα συνθλίβει κι
  αυτή, αλλά μόνο όταν πετάει γρήγορα (γίνεται λευκή-πυρακτωμένη).
- Πρόσεχε τη λευκή σφαίρα: ό,τι την αγγίξει σου τρώει καρδιά (3 ♥).
- Εχθροί: **chasers**, **splitters** (σπάνε σε 2 minis), **darters**
  (λουφάζουν και ορμάνε, wave 3+), **tanks** (θέλουν 2 κοψίματα, wave 4+).
- **Combo x2…x8** για συνεχόμενα kills μέσα σε 1.2" — το spin-to-win
  μέσα σε κοπάδι είναι η κορυφαία στιγμή του παιχνιδιού.
- Gems από kills (+5, με μαγνήτη). Νέο **WAVE** κάθε 15" με περισσότερους
  και γρηγορότερους εχθρούς. Best score, kills αποθηκεύονται τοπικά.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — κανένα asset, ~70 KB APK.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **TETHER** | [`dist/TETHER.apk`](dist/TETHER.apk) | Σκοινί-λεπίδα με physics flail (τρέχων κώδικας στο `app/`) |
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
./scripts/build-offline.sh  # παράγει dist/TETHER.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/tether/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # rope physics, slicing, enemy AI, waves, rendering
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner), **SWARM** (firefly
> flocking) και **ECHO** (self-replay ghosts) — ο κώδικάς τους υπάρχει
> στο git history.
