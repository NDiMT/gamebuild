# ORBIT

Gravity golf για Android: εκτοξεύεις έναν κομήτη με σφεντόνα μέσα σε ένα
μικρό σύμπαν, οι πλανήτες λυγίζουν την τροχιά του με πραγματική βαρύτητα,
και πρέπει να τον προσγειώσεις στον στόχο. **Άπειρα procedurally
generated levels** — η πρόοδός σου σώζεται.

## Gameplay

- **Σύρε οπουδήποτε και άφησε** = σφεντόνα (με preview τροχιάς που
  δείχνει πώς θα καμπυλώσει η βολή μέσα στα βαρυτικά πεδία).
- Οι πλανήτες **έλκουν** τον κομήτη — χρησιμοποίησε slingshots γύρω τους.
  Πρόσκρουση = χάνεις τη βολή, ξαναπροσπαθείς.
- Μάζεψε τα **3 αστέρια ★** κάθε επιπέδου στη διαδρομή — μένουν δικά σου
  και μεταξύ προσπαθειών.
- Κλιμάκωση: περισσότεροι/βαρύτεροι πλανήτες, **repulsors** (κόκκινοι,
  απωθούν, level 6+), **κινούμενα φεγγάρια** (level 12+), μικρότερος
  στόχος όσο ανεβαίνεις.
- Μετράς **shots** ανά level (golf score). Κόλλησες; Μετά από 12 βολές
  εμφανίζεται SKIP.
- Αποθηκεύονται: τρέχον level, σύνολο αστεριών, μέγιστο level.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — κανένα asset, ~75 KB APK.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **ORBIT** | [`dist/ORBIT.apk`](dist/ORBIT.apk) | Gravity golf με άπειρα levels (τρέχων κώδικας στο `app/`) |
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
./scripts/build-offline.sh  # παράγει dist/ORBIT.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/orbit/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # level generation, gravity physics, aiming, rendering
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner), **SWARM** (firefly
> flocking) και **ECHO** (self-replay ghosts) — ο κώδικάς τους υπάρχει
> στο git history.
