# Fold Desk

A personal dashboard PWA (timetable, tasks, events, people, memos) designed for foldable phones.

**No personal data lives in this repository.** The app is only a shell: all data is stored in the owner's own Google Drive and served by the owner's own Google Apps Script web app (`gas/`). Every request requires a secret token that exists only in the owner's Apps Script properties and on the owner's paired device.

- `index.html`, `sw.js`, `manifest.webmanifest`, `icons/` — the installable app
- `gas/` — Apps Script backend (copy into a new Apps Script project; see comments at the top of `Code.gs`)
