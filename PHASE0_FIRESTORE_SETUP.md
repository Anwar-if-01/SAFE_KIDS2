# Phase 0 — Firestore indexes (required)

Parent screens query Firestore with `whereEqualTo` + `orderBy`. Without composite indexes, queries fail and the app may crash or show errors.

## Deploy indexes

### Option A — Firebase Console
1. Open [Firebase Console](https://console.firebase.google.com) → your project → **Firestore** → **Indexes**.
2. Click **Add index** or import from `firestore.indexes.json` if your project supports JSON import.
3. Wait until every index status is **Enabled** (often 5–15 minutes).

### Option B — Firebase CLI
```bash
cd c:\Users\MURAD\AndroidStudioProjects\SAFE_KIDS2
firebase deploy --only firestore:indexes
```

`firebase.json` points to `firestore.indexes.json` in this repo.

## Collections covered
- `child_notifications`
- `content_safety_logs`
- `web_history`
- `activities`
- `locations`
- `child_commands`

After indexes are enabled, retest **Device → Content Safety** and **Browsing History** from the parent app.
