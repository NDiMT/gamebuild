# PULSE

Ένα one-tap arcade παιχνίδι για Android, σχεδιασμένο γύρω από τους κλασικούς
μηχανισμούς εθισμού των hyper-casual παιχνιδιών: άμεση επανεκκίνηση, combo
multipliers, near-miss bonuses και διαρκώς αυξανόμενη ταχύτητα.

## Gameplay

- Μια μπάλα περιστρέφεται πάνω σε έναν δακτύλιο.
- **Tap** οπουδήποτε = αντιστροφή κατεύθυνσης.
- Μάζεψε τα **gems** (διαμάντια) — απόφυγε τα κόκκινα **spikes**.
- Συνεχόμενα gems μέσα σε 4" χτίζουν **combo x2…x8** (πολλαπλασιαστής πόντων).
- Αντιστροφή την τελευταία στιγμή πριν από spike = **"CLOSE!" bonus** (+5).
- Η ταχύτητα και ο αριθμός των spikes ανεβαίνουν όσο μαζεύεις gems· το χρώμα
  του κόσμου αλλάζει ανά 10 gems.
- High score, runs και συνολικά gems αποθηκεύονται τοπικά. Τίτλοι κατάταξης
  από ROOKIE μέχρι GOD MODE.

Όλα τα γραφικά είναι procedural (Canvas) και όλοι οι ήχοι συντίθενται σε
PCM κατά την εκκίνηση — το APK δεν περιέχει κανένα asset, γι' αυτό είναι ~70 KB.

## Έτοιμο APK

Το χτισμένο, υπογεγραμμένο (debug v1+v2+v3) APK βρίσκεται στο
[`dist/PULSE.apk`](dist/PULSE.apk). Υποστηρίζει Android 5.0+ (API 21).
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
./scripts/build-offline.sh  # παράγει dist/PULSE.apk
```

## Δομή

```
app/src/main/java/gr/happyonline/pulse/
  MainActivity.java   # fullscreen activity + lifecycle
  GameView.java       # game loop, physics, rendering, states
  SoundFx.java        # procedural PCM sound synthesis
tools/icon_gen.py     # δημιουργεί τα launcher icons (pure Python)
scripts/              # offline build pipeline
```
