# RUSH

Ένας neon endless runner για Android (στο πνεύμα του Geometry Dash), χτισμένος
γύρω από τους κλασικούς μηχανισμούς εθισμού των hyper-casual παιχνιδιών:
άμεση επανεκκίνηση, near-miss bonuses, ταχύτητα που κλιμακώνεται και "gears".

## Gameplay

- Ένας κύβος τρέχει μέσα σε synthwave κόσμο που επιταχύνει συνεχώς.
- **Tap** = άλμα. **Tap στον αέρα** = διπλό άλμα.
- Απόφυγε τα κόκκινα **spikes**, πήδα πάνω ή πέρα από τα **blocks**,
  μάζεψε τα **coins** (+5 πόντοι το καθένα).
- Πέρασμα ξυστά πάνω από spike = **"CLOSE!" bonus** (+5).
- Κάθε 11" ανεβαίνει **GEAR**: περισσότερη ταχύτητα, πιο πυκνά εμπόδια,
  νέο χρώμα κόσμου (μέχρι GEAR 9).
- Score = απόσταση + coins + bonuses. Best score, runs και συνολικά coins
  αποθηκεύονται τοπικά. Τίτλοι κατάταξης από ROOKIE μέχρι GOD MODE.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — το APK δεν περιέχει κανένα asset,
γι' αυτό είναι ~65 KB.

## Έτοιμο APK

Το χτισμένο, υπογεγραμμένο (debug v1+v2+v3) APK βρίσκεται στο
[`dist/RUSH.apk`](dist/RUSH.apk). Υποστηρίζει Android 5.0+ (API 21).
Εγκατάσταση: μεταφορά στη συσκευή και άνοιγμα (απαιτεί "Install unknown apps").

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
./scripts/build-offline.sh  # παράγει dist/RUSH.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/rush/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # game loop, physics, collisions, rendering, states
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: η πρώτη έκδοση του repo περιείχε το παιχνίδι **PULSE**
> (one-tap orbit dodger) — υπάρχει ακόμα στο git history.
