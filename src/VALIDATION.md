# Validation report

Validated on 2026-07-30 with an Android 15 / API 35 ARM64 Pixel 6 emulator.

## Automated checks

- `testDebugUnitTest`: passed
  - book metadata and reading-position JSON round trip
  - tokenization preserves abbreviations, possessives and hyphenated words
  - phrase-window generation and context POS ordering
  - reader JavaScript contains word/sentence extraction and sentence offsets
  - reader preferences are safely encoded
  - saved-word JSON round trip, CSV escaping and review scheduling
- `connectedDebugAndroidTest`: 8/8 passed
  - EPUB metadata/spine parsing and script sanitization
  - bundled ECDICT lookup with irregular inflection (`carried` → `carry`)
  - lemmatized phrases (`looked forward to` and `took off`)
  - abbreviation lookup retaining internal periods (`U.S.`)
  - saved-word persistence and review-state update
  - bookshelf Compose UI smoke test
  - vocabulary screen navigation and controls
- `lintDebug`: passed with 0 errors
  - remaining notices are dependency-update and optional KTX-style suggestions
- `assembleDebug`: passed

## Manual emulator checks

Using `artifacts/TheLanternLibrary.epub`:

1. Imported through Android's system file picker.
2. Verified title, author, three chapters and generated bookshelf card.
3. Opened chapter one and confirmed four-page pagination.
4. Used the right edge to move from page 1 to page 2.
5. Opened the table of contents and reading appearance controls.
6. Restarted the app and confirmed the stored book and reading position.
7. Tapped `carried`; the app selected `carry`, inferred a verb context and moved
   `vt.` / `vi.` Chinese senses ahead of the noun sense.
8. Tapped `to` in `They looked forward to working together.`; the app matched
   the complete phrase `look forward to` and returned its Chinese phrase meaning.
9. Added `carry` to the vocabulary list and confirmed its pronunciation button,
   three context-ranked senses, source sentence, book and chapter.
10. Opened review mode, revealed the answer and verified both “again” and
    “remembered” grading actions.
11. Confirmed the app manifest does not request network access.

## Artifact

- APK: `artifacts/LinguaReader-debug.apk`
- Version: 1.0.0 (version code 3)
- Size: approximately 38 MB
- SHA-256: `0408d313b9f53317df2565a1c8427e6d7e3fac23b8b4d8e3a181f999dd64b39b`

The APK is debug-signed for direct installation and evaluation. A Play Store
release still requires a production signing key and release configuration.
