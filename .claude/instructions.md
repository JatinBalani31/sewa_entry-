You are working on two projects:
(A) "Sewa Entry": an Android attendance app (client + kiosk modes) with a Node/Python backend and PostgreSQL, described in the attached Readme.md.
(B) "Biometric Kiosk": our existing app that already does face detection and identification.

GOAL
Add face-recognition attendance to Sewa Entry, reusing the approach already working in the Biometric Kiosk app. Store arrival and departure times and produce precise analytics.

STEP 0 - AUDIT FIRST (do not write feature code yet)
1. Read the Biometric Kiosk code. Report: how faces are detected, how identity is matched (on-device model or a cloud API such as Gemini), how faces are enrolled and stored, the match thresholds, and how fast it runs.
2. Read the Sewa Entry code. Report: current screens/tabs, the lock screen and where the 4-digit PIN is stored and checked, how attendance logs are saved and synced, and any offline cache.
3. List what can be reused directly, what must be rewritten, and risks. Wait for my approval before continuing.

REQUIREMENTS
1. New "Face Scan" tab (default). Camera detects one face, runs a liveness check (blink or head turn), matches against enrolled templates, shows the name for 2 seconds, then saves an IN or OUT entry. Add a 60-second cooldown per person. Toggle IN/OUT from the person's last log of the day, with an admin override.
2. Prefer ON-DEVICE recognition (ML Kit face detection + a quantised TFLite embedding model). If the Biometric Kiosk app uses a cloud API for each scan, keep its logic and thresholds as reference but implement on-device matching. Target under 1.5 seconds from face to result.
3. Enrolment tab (admin only): capture 3 to 5 samples per person, store ENCRYPTED embeddings plus model_version, not photos. Show a consent screen first. Allow deleting a person's face data.
4. Manual Entry tab: LOCKED by default. Opens only with the admin's 6-digit PIN. Saves method ADMIN_OVERRIDE with the admin ID and a mandatory note. Auto-locks after 60 seconds of inactivity.
5. PIN change: 4 digits -> 6 digits everywhere in the lock flow. Hash the PIN (bcrypt/Argon2), keep it in Android Keystore/EncryptedSharedPreferences. On first launch after update, force the admin to set a new 6-digit PIN. Keep lockout: 3 wrong attempts = 5-minute cooldown, longer on repeats.
6. Backend/database: add FACE to verification_method, add face_templates and recognition_events tables, add match_score, liveness_passed, device_id, device_time, synced_at to attendance_logs (use migrations, do not drop existing data). Server time is the official timestamp; keep the device time as a separate flagged field for offline entries.
7. Analytics tab + endpoints + Excel sheets: present/absent, first IN/last OUT, hours worked, late arrival, early departure, overtime, missing OUT, average arrival time, punctuality rate, method mix, face scan quality (match rate, no-match rate, spoof flags), peak-hour heat map. Add shift start/end and grace minutes to Settings.
8. Offline: face scans must work without internet, saved in the local database and synced with WorkManager. No duplicate logs after sync.

RULES
- Work in a separate git branch. Do not break existing GPS, PIN, signature or kiosk-mode features.
- Never log or transmit raw face images unless I explicitly approve it.
- Write unit tests for PIN lockout, IN/OUT toggling, cooldown and analytics calculations.
- Finish one phase at a time and show how to test it before moving on. Phases: 1 PIN + locked Manual tab, 2 Enrolment, 3 Face Scan, 4 Backend/DB, 5 Analytics + Excel, 6 tuning and field test.