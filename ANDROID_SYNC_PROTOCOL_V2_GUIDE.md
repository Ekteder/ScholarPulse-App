# ScholarPulse Android Synchronization Protocol v2 Upgrade Guide

**Document Version:** 2.0.0  
**Target:** Android Native Engineering Team (`/app`)  
**Backend:** Next.js 16 Web Backend (`/v2/src/app/api/sync/batch/route.ts`)  
**Status:** Live & Active on Backend  

---

## Executive Summary for App Developer

The web backend now supports **Sync Protocol v2** via `POST /api/sync/batch`. 

### Key Improvements Available to the Android App:
1. **No More Poisoned Queues:** If a student studies a subject offline, but deletes that subject on the web, previously PostgreSQL threw a foreign key violation that rolled back the **entire batch** with an HTTP 500/400. With Protocol 2, the server isolates items with transaction savepoints: it permanently rejects the deleted subject while **accepting and saving all other sessions, tasks, and revision records**.
2. **Item-Level Granular Acknowledgements:** The server returns `accepted: [String]` and `rejected: [{ key, reason }]`, telling the app exactly which mutations were committed and which had permanent constraint failures.
3. **Timer Conflict Status:** The server returns `timer: { status: "applied" | "server_ahead" | "rejected", serverTimer: ... }` so the phone knows if its timer was applied or if the server was ahead.

---

## Required Changes in Android Codebase

### Step 1: Send `X-Sync-Protocol: 2` Header

In [OfflineSyncWorker.kt](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/workers/OfflineSyncWorker.kt#L224-L228), inside `uploadBatch()`, add the `X-Sync-Protocol: 2` request header:

```kotlin
val connection = (url.openConnection() as HttpURLConnection).apply {
    requestMethod = "POST"
    connectTimeout = 15000
    readTimeout = 20000
    doInput = true
    doOutput = true
    setRequestProperty("Content-Type", "application/json; charset=utf-8")
    setRequestProperty("Accept", "application/json")
    setRequestProperty("Cookie", "sp_session=$sessionToken")
    setRequestProperty("User-Agent", "ScholarPulseAndroid/${BuildConfig.VERSION_NAME}")
    // ADD THIS HEADER TO OPT INTO PROTOCOL 2:
    setRequestProperty("X-Sync-Protocol", "2")
}
```

---

### Step 2: Handle `accepted` and `rejected` in Response Body

Previously, `OfflineSyncWorker.kt` deleted all mutations upon receiving HTTP 200, or marked all as `FAILED` upon receiving HTTP 400/500.

Under Protocol 2, the server returns HTTP 200 whenever the envelope is valid. The response body contains:
```json
{
  "success": true,
  "protocol": 2,
  "synced": {
    "studySessions": { "inserted": 3, "duplicates": 0 },
    "tasks": { "inserted": 1, "updated": 0 },
    "revisionRecords": { "upserted": 2 },
    "activeTimer": { "updated": true, "cleared": false }
  },
  "accepted": [
    "session_uuid_1",
    "task_uuid_2",
    "topicId:category"
  ],
  "rejected": [
    {
      "key": "session_uuid_corrupt",
      "reason": "23503: insert or update on table \"study_sessions\" violates foreign key constraint"
    }
  ],
  "timer": {
    "status": "applied",
    "serverTimer": null
  }
}
```

#### Update `performSync()` in `OfflineSyncWorker.kt`:
```kotlin
// Define response data classes:
@Serializable
data class BatchSyncResponse(
    val success: Boolean,
    val protocol: Int? = null,
    val accepted: List<String> = emptyList(),
    val rejected: List<RejectedItem> = emptyList(),
    val timer: TimerReconciliation? = null
)

@Serializable
data class RejectedItem(
    val key: String,
    val reason: String
)

@Serializable
data class TimerReconciliation(
    val status: String, // "none", "applied", "server_ahead", "rejected"
    val reason: String? = null,
    val serverTimer: JsonElement? = null
)
```

#### Mutation Clearing Logic:
```kotlin
if (response.protocol == 2) {
    val acceptedKeys = response.accepted.toSet()
    val rejectedMap = response.rejected.associate { it.key to it.reason }

    for (mutation in pendingMutations) {
        val key = extractKey(mutation)
        if (acceptedKeys.contains(key)) {
            // Successfully saved on server -> delete from Room SQLite
            mutationDao.deleteMutationById(mutation.id)
        } else if (rejectedMap.containsKey(key)) {
            // Permanent constraint failure -> mark DEAD so it stops retrying
            val reason = rejectedMap[key] ?: "Permanent server rejection"
            mutationDao.updateStatus(mutation.id, "DEAD", reason)
            Log.e(TAG, "Mutation ${mutation.id} permanently rejected by server: $reason")
        } else {
            // Unprocessed -> reset to PENDING for next sync attempt
            mutationDao.updateStatus(mutation.id, "PENDING", "Not processed in batch")
        }
    }

    // Handle timer reconciliation if server is ahead:
    if (response.timer?.status == "server_ahead" && response.timer.serverTimer != null) {
        Log.i(TAG, "Server timer is ahead. Reconciling local state...")
        NativeBridgeDispatcher.activeDispatcher?.sendEvent("timer.reconcile", response.timer.serverTimer)
    }
}
```

---

### Step 3: Fix SQLite Stuck `SYNCING` Mutations (Crash Recovery)

In [Daos.kt](file:///media/sanglap/Movies/scholarpulsev2/app/app/src/main/java/com/scholarpulse/app/db/Daos.kt#L104-L105):

#### The Bug:
When `performSync()` marks mutations as `"SYNCING"`, if the Android Low Memory Killer (LMK) kills the app or the battery dies mid-upload, those rows stay stuck in `"SYNCING"` forever because `getPendingMutations()` only selects `'PENDING'` or `'FAILED'`.

#### The Fix:
Update `getPendingMutations` to reclaim mutations whose `"SYNCING"` status is older than 5 minutes (stale lease):

```kotlin
@Query("""
    SELECT * FROM offline_mutations 
    WHERE status IN ('PENDING', 'FAILED') 
       OR (status = 'SYNCING' AND updatedAtMs < (:nowMs - 300000))
    ORDER BY createdAtMs ASC
""")
suspend fun getPendingMutations(nowMs: Long = System.currentTimeMillis()): List<OfflineMutationEntity>
```

And in `OfflineMutationDao`:
```kotlin
@Query("DELETE FROM offline_mutations WHERE id = :id")
suspend fun deleteMutationById(id: Long)
```

---

### Step 4: Timer Idempotency Key Alignment

When a timer session finishes and creates a study session:
1. Make sure the completed study session's `idempotencyKey` matches the active timer's `idempotencyKey` (or explicitly delete the active timer row).
2. The server will automatically clean up the server's active timer row upon seeing the completed session with that matching key.

---

### Summary of Benefits for Android

* **Resilient to Foreign Key Errors:** Deleted subjects on web will never brick the Android offline queue.
* **Crash-Proof Offline Queue:** No more lost study sessions if the phone dies mid-sync.
* **Accurate Server Reconciliation:** Clear acknowledgements per record with full `accepted` / `rejected` auditing.
