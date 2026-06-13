# SAVE THE DOGE

Το αληθινό παιχνίδι πίσω από τα παραπλανητικά ads: ένας **doge** κάθεται
στο έδαφος και ένα σμήνος **μέλισσες** ξεχύνεται από την κυψέλη για να τον
τσιμπήσει. Έχεις περιορισμένο **μελάνι** — σύρε το δάχτυλο για να
ζωγραφίσεις συμπαγείς γραμμές/θόλο που οι μέλισσες δεν μπορούν να
περάσουν, και κράτησέ τον ασφαλή μέχρι να τελειώσει ο χρόνος.

## Gameplay

- **Σύρε** οπουδήποτε για να ζωγραφίσεις γραμμές μελανιού (η μπάρα δείχνει
  πόσο μελάνι έμεινε). Σχημάτισε θόλο/τοίχο γύρω από τον doge.
- Οι **μέλισσες** βγαίνουν από την κυψέλη και ορμάνε στη φάτσα του doge,
  στριμώχνονται και γλιστράνε πάνω στις γραμμές σου.
- **Επιβίωσε** μέχρι το ρολόι να φτάσει στο 0 → νίκη, επόμενο level.
  Μία μέλισσα αγγίζει τον doge → "OUCH!", retry.
- Το έδαφος είναι συμπαγές — αρκεί να καλύψεις από πάνω.
- Κουμπί **CLEAR** (πάνω δεξιά) σβήνει το μελάνι σου για να ξανασχεδιάσεις.
- Κάθε level: περισσότερες/γρηγορότερες μέλισσες, λιγότερο μελάνι.
  Η πρόοδος και το best level αποθηκεύονται.

Όλα procedural (Canvas/SurfaceView) — γραφικά και ήχοι (loop βουητού
μελισσών, μολύβι, τσίμπημα, φανφάρα νίκης) συντίθενται κατά την εκκίνηση.
Κανένα asset, ~42 KB APK.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **SAVE THE DOGE** | [`dist/DOGE.apk`](dist/DOGE.apk) | Ζωγραφίζεις γραμμές για να σώσεις τον doge από τις μέλισσες (τρέχων κώδικας στο `app/`) |
| **KEPLER** | [`dist/KEPLER.apk`](dist/KEPLER.apk) | Orbital capture με μουσική Satie |
| **ORBIT** | [`dist/ORBIT.apk`](dist/ORBIT.apk) | Gravity golf με άπειρα levels |
| **TETHER** | [`dist/TETHER.apk`](dist/TETHER.apk) | Σκοινί-λεπίδα με physics flail |
| **BEACON** | [`dist/BEACON.apk`](dist/BEACON.apk) | Οι σκιές κινούνται μόνο στο σκοτάδι |
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
./scripts/build-offline.sh  # παράγει dist/DOGE.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/doge/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # bees, ink drawing, collisions, levels, rendering
  SoundFx.java        # procedural SFX + looping bee buzz
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner), **SWARM** (firefly
> flocking) και **ECHO** (self-replay ghosts) — ο κώδικάς τους υπάρχει
> στο git history.
