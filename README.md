# SWARM

Ένα πρωτότυπο arcade παιχνίδι για Android: οδηγείς ένα **ζωντανό σμήνος από
πυγολαμπίδες** με το δάχτυλό σου. Το σμήνος κινείται με πραγματικό flocking
AI (έλξη + separation + wander) και συμπεριφέρεται σαν ένας οργανισμός που
ρέει, απλώνει και στριμώχνεται στα κενά.

## Gameplay

- **Κράτα πατημένο** οπουδήποτε — το σμήνος ρέει προς το δάχτυλό σου.
  Άφησέ το και οι πυγολαμπίδες περιπλανώνται μόνες τους.
- Εμπόδια σαρώνουν την οθόνη: τοίχοι με κενά, πλάκες, **περιστρεφόμενα
  laser**, έμβολα που ανεβοκατεβαίνουν. Όποια πυγολαμπίδα τα ακουμπήσει σβήνει.
- **Το σμήνος είναι η ζωή σου**: ξεκινάς με 12, χάνεις όταν σβήσουν όλες.
  Τα κυανά **orbs** προσθέτουν +2 πυγολαμπίδες (μέχρι 60).
- Το πρωτότυπο dilemma: μεγάλο σμήνος = περισσότερες "ζωές", αλλά πιο
  δύσκολο να χωρέσει στα κενά.
- Πέρασμα εμποδίου με **μηδέν απώλειες** = "PERFECT" αλυσίδα με κλιμακούμενο
  bonus (x1, x2, x3…). Μία απώλεια μηδενίζει την αλυσίδα.
- Η ταχύτητα και η πυκνότητα ανεβαίνουν με τα levels. Best score, νύχτες
  και συνολικά orbs αποθηκεύονται τοπικά.

Όλα τα γραφικά είναι procedural (Canvas/SurfaceView) και όλοι οι ήχοι
συντίθενται σε PCM κατά την εκκίνηση — το APK δεν περιέχει κανένα asset,
γι' αυτό είναι ~75 KB.

## Έτοιμο APK

Το χτισμένο, υπογεγραμμένο (debug v1+v2+v3) APK βρίσκεται στο
[`dist/SWARM.apk`](dist/SWARM.apk). Υποστηρίζει Android 5.0+ (API 21).
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
./scripts/build-offline.sh  # παράγει dist/SWARM.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/swarm/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # flocking, obstacles, collisions, rendering, states
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```

> Σημείωση: προηγούμενες εκδόσεις του repo περιείχαν τα παιχνίδια **PULSE**
> (one-tap orbit dodger) και **RUSH** (neon endless runner) — υπάρχουν στο
> git history.
