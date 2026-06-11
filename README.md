# ECHO

Ένα πρωτότυπο arcade παιχνίδι για Android με έναν μοναδικό μηχανισμό:
**ο μόνος εχθρός είσαι εσύ ο ίδιος**. Κάθε γύρο που καθαρίζεις, η ακριβής
διαδρομή που μόλις πέταξες ηχογραφείται και ξαναπαίζει για πάντα ως
«echo» — ένα φάντασμα του εαυτού σου που κινείται ακριβώς όπως κινήθηκες.

## Gameplay

- **Σύρε** το δάχτυλο για να γλιστράς στην αρένα (relative drag — η
  κουκκίδα δεν κρύβεται κάτω από το δάχτυλο).
- Μάζεψε **5 orbs** για να καθαρίσεις τον γύρο (+5×γύρος bonus).
- Με κάθε γύρο, **η διαδρομή σου γίνεται φάντασμα** που επαναλαμβάνεται σε
  loop. Στον γύρο 6 αποφεύγεις 5 παλιούς εαυτούς σου ταυτόχρονα.
- Αν σε αγγίξει echo — τέλος. *Παίξε καθαρά τώρα, για να ζήσεις αργότερα*:
  άτσαλες κινήσεις σήμερα = κόλαση αύριο. Το camping τιμωρείται (το echo
  σου θα «κάθεται» εκεί για πάντα).
- **Graze bonus**: πέρνα ξυστά από ένα echo χωρίς να το αγγίξεις και
  μαζεύεις +1 πόντους συνεχόμενα — ρίσκο εναντίον ασφάλειας.
- Σύντομο invulnerability στην αρχή κάθε γύρου. Best score, ζωές και
  συνολικά echoes αποθηκεύονται τοπικά.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — το APK δεν περιέχει κανένα asset,
γι' αυτό είναι ~66 KB.

## Έτοιμα APKs

| Παιχνίδι | Αρχείο | Περιγραφή |
|---|---|---|
| **ECHO** | [`dist/ECHO.apk`](dist/ECHO.apk) | Αποφεύγεις replays του εαυτού σου (τρέχων κώδικας στο `app/`) |
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
./scripts/build-offline.sh  # παράγει dist/ECHO.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/echo/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # path recording/replay, ghosts, graze, rendering
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν και τα παιχνίδια
> **PULSE** (orbit dodger), **RUSH** (neon runner) και **SWARM**
> (firefly flocking) — ο κώδικάς τους υπάρχει στο git history.
