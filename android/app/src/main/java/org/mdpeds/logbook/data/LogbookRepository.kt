package org.mdpeds.logbook.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.mdpeds.logbook.data.local.BundledReference
import org.mdpeds.logbook.data.local.CacheEntity
import org.mdpeds.logbook.data.local.LocalDatabase
import org.mdpeds.logbook.data.local.OutboxAction
import org.mdpeds.logbook.data.local.OutboxEntity
import org.mdpeds.logbook.data.local.OutboxState
import org.mdpeds.logbook.data.local.UserPreferences
import org.mdpeds.logbook.domain.logic.AppJson
import org.mdpeds.logbook.domain.logic.ReferenceData
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.ReferenceSnapshot
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.SubmissionStatus
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.UserSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A value that may have come from the offline cache. */
data class Loaded<T>(val value: T, val offline: Boolean = false)

/** Local (not yet synchronised) state of a record. */
data class SyncInfo(val state: String, val error: String?, val pendingSubmit: Boolean)

/** A logbook record as shown in the app: server data, overlaid with any unsynchronised local draft. */
data class RecordItem(val submission: Submission, val sync: SyncInfo? = null) {
    val isLocalOnly get() = sync != null
}

data class SaveOutcome(val submission: Submission, val synced: Boolean, val message: String?)

/** Interface implemented by WorkManager scheduling (kept abstract for unit tests). */
interface SyncScheduler {
    fun scheduleSync()
}

/**
 * Single entry point for UI state: wraps the backend with an offline cache and a draft outbox.
 * Students can create and edit drafts offline; everything else requires a connection because the
 * backend is the authority for permissions and workflow transitions.
 */
@Singleton
class LogbookRepository @Inject constructor(
    val backend: LogbookBackend,
    private val db: LocalDatabase,
    private val bundled: BundledReference,
    private val prefs: UserPreferences,
    private val scheduler: SyncScheduler,
) {
    private val _session = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = _session.asStateFlow()

    private val _reference = MutableStateFlow<ReferenceData?>(null)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** Emits whenever data changed locally or on the server through this app. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val syncMutex = Mutex()

    val isDemo get() = backend.isDemo

    fun requireSession(): UserSession = _session.value ?: throw BackendException(401, "Please sign in.")

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    // ------------------------------------------------------------ session

    suspend fun restore(): UserSession? {
        val s = backend.restoreSession()
        _session.value = s
        if (s != null) {
            runCatching { refreshReference() }
            scheduler.scheduleSync()
        }
        return s
    }

    suspend fun signIn(email: String, password: String): UserSession {
        val s = backend.signIn(email, password)
        _session.value = s
        runCatching { refreshReference() }
        scheduler.scheduleSync()
        return s
    }

    suspend fun completeAuthCallback(uri: String): UserSession {
        val s = backend.completeAuthCallback(uri)
        _session.value = s
        runCatching { refreshReference() }
        return s
    }

    suspend fun pendingDraftCount(): Int = _session.value?.let { db.outbox().count(it.userId) } ?: 0

    /** Signs out and removes all locally cached data from the device. */
    suspend fun signOut() {
        backend.signOut()
        db.outbox().clear()
        db.cache().clear()
        prefs.clearUserData()
        _reference.value = null
        _session.value = null
    }

    // ------------------------------------------------------------ cache helpers

    suspend fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Loaded<T> {
        val owner = _session.value?.userId ?: "anon"
        val fullKey = "$owner:$key"
        return try {
            val value = fetch()
            db.cache().put(CacheEntity(fullKey, AppJson.encodeToString(serializer, value), System.currentTimeMillis()))
            Loaded(value, offline = false)
        } catch (e: BackendException) {
            if (!e.isNetwork) throw e
            val hit = db.cache().get(fullKey) ?: throw e
            Loaded(AppJson.decodeFromString(serializer, hit.json), offline = true)
        }
    }

    /** Runs an online-only operation and notifies observers on success. */
    suspend fun <T> online(block: suspend LogbookBackend.() -> T): T {
        val result = backend.block()
        notifyChanged()
        return result
    }

    // ------------------------------------------------------------ reference data

    suspend fun reference(): ReferenceData = _reference.value ?: refreshReference()

    suspend fun refreshReference(): ReferenceData {
        val snapshot = try {
            cached("reference", ReferenceSnapshot.serializer()) {
                ReferenceSnapshot(backend.templates(), backend.roleOptions(), backend.competencies(), backend.targets(), backend.documents())
            }.value
        } catch (e: BackendException) {
            null
        }
        val b = bundled.data
        val data = if (snapshot == null || snapshot.templates.isEmpty()) b else ReferenceData(
            templates = snapshot.templates.sortedBy { it.sortOrder },
            roleOptions = snapshot.roleOptions.ifEmpty { b.roleOptions },
            competencies = snapshot.competencies.ifEmpty { b.competencies },
            targets = snapshot.targets.ifEmpty { b.targets },
            targetsDisclaimer = b.targetsDisclaimer,
            documents = snapshot.documents.ifEmpty { b.documents },
        )
        _reference.value = data
        return data
    }

    suspend fun settings(): InstitutionSettings =
        runCatching { cached("settings", InstitutionSettings.serializer()) { backend.settings() }.value }.getOrDefault(InstitutionSettings())

    // ------------------------------------------------------------ records + offline drafts

    fun observeOutbox(): Flow<List<OutboxEntity>> = _session.flatMapLatestCompat { s ->
        if (s == null) flowOf(emptyList()) else db.outbox().observe(s.userId)
    }

    suspend fun records(studentId: String, templateCode: String? = null): Loaded<List<RecordItem>> {
        val me = requireSession()
        val server = cached("subs:$studentId:${templateCode ?: "*"}", ListSerializer(Submission.serializer())) {
            backend.submissions(studentId, templateCode)
        }
        if (studentId != me.userId) return Loaded(server.value.map { RecordItem(it) }, server.offline)
        val reference = reference()
        val local = db.outbox().all(me.userId).filter { templateCode == null || it.templateCode == templateCode }
        val byId = local.associateBy { it.id }
        val merged = server.value.map { s -> byId[s.id]?.let { overlay(s, it, reference.template(s.templateCode)) } ?: RecordItem(s) }
        val serverIds = server.value.map { it.id }.toSet()
        val extra = local.filter { it.id !in serverIds }.map { overlay(null, it, reference.template(it.templateCode)) }
        return Loaded(extra + merged, server.offline)
    }

    suspend fun record(id: String): RecordItem {
        val me = requireSession()
        val local = db.outbox().get(id)?.takeIf { it.ownerId == me.userId }
        val server = try {
            cached("sub:$id", Submission.serializer()) { backend.submission(id) }.value
        } catch (e: BackendException) {
            if (local == null || (e.code != 404 && !e.isNetwork)) throw e
            null
        }
        val tpl = reference().template(local?.templateCode ?: server!!.templateCode)
        return if (local != null) overlay(server, local, tpl) else RecordItem(server!!)
    }

    private fun overlay(server: Submission?, e: OutboxEntity, template: Template?): RecordItem {
        val data = decodeData(e.dataJson)
        val base = server ?: Submission(
            id = e.id,
            studentId = e.studentId,
            templateCode = e.templateCode,
            periodNo = e.periodNo,
            slotNo = e.slotNo,
            status = SubmissionStatus.DRAFT,
            version = e.baseVersion ?: 0,
        )
        val entryDate = template?.dateField?.let { data[it] } ?: base.entryDate
        val status = if (base.status == SubmissionStatus.NOT_STARTED) SubmissionStatus.DRAFT else base.status
        return RecordItem(
            base.copy(studentData = data, entryDate = entryDate, status = status),
            SyncInfo(e.state, e.error, e.action == OutboxAction.SUBMIT),
        )
    }

    private fun decodeData(json: String): Map<String, String> =
        AppJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json)

    /**
     * Saves a student draft locally first, then tries to send it immediately. If offline the draft stays in
     * the outbox and is synchronised by the background worker. [submit] also submits it for review.
     */
    suspend fun saveDraft(
        template: Template,
        item: RecordItem?,
        periodNo: Int?,
        slotNo: Int?,
        data: Map<String, String>,
        submit: Boolean,
    ): SaveOutcome {
        val me = requireSession()
        val id = item?.submission?.id ?: UUID.randomUUID().toString()
        val existing = db.outbox().get(id)
        val baseVersion = when {
            existing != null -> existing.baseVersion
            item != null && !item.isLocalOnly -> item.submission.version
            else -> null
        }
        val entity = OutboxEntity(
            id = id,
            ownerId = me.userId,
            studentId = me.userId,
            templateCode = template.code,
            periodNo = periodNo ?: item?.submission?.periodNo,
            slotNo = slotNo ?: item?.submission?.slotNo,
            baseVersion = baseVersion,
            dataJson = AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), data.filterValues { it.isNotEmpty() }),
            action = if (submit) OutboxAction.SUBMIT else OutboxAction.SAVE,
            state = OutboxState.PENDING,
            error = null,
            updatedAt = System.currentTimeMillis(),
        )
        db.outbox().upsert(entity)
        notifyChanged()
        val result = syncMutex.withLock { syncOne(entity) }
        notifyChanged()
        return result ?: run {
            val local = record(id)
            val msg = local.sync?.error ?: "Saved on this device. It will sync when you are back online."
            if (local.sync?.state == OutboxState.PENDING) scheduler.scheduleSync()
            SaveOutcome(local.submission, synced = false, message = msg)
        }
    }

    /** Sends one outbox entry; returns the server record on success, or null when it stays local. */
    private suspend fun syncOne(e: OutboxEntity): SaveOutcome? {
        db.outbox().mark(e.id, OutboxState.SYNCING, null)
        var saved: Submission? = null
        try {
            saved = backend.saveSubmission(SaveRequest(e.id, e.templateCode, decodeData(e.dataJson), e.baseVersion, e.periodNo, e.slotNo))
            var result: Submission = saved
            if (e.action == OutboxAction.SUBMIT) result = backend.submitSubmission(saved.id, saved.version)
            finishEntry(e, result.version)
            return SaveOutcome(result, synced = true, message = if (e.action == OutboxAction.SUBMIT) "Submitted for review" else "Draft saved")
        } catch (ex: BackendException) {
            when {
                ex.isNetwork -> {
                    if (saved != null) db.outbox().upsert(e.copy(baseVersion = saved.version, state = OutboxState.PENDING, error = null))
                    else db.outbox().mark(e.id, OutboxState.PENDING, null)
                }
                ex.isConflict -> db.outbox().mark(e.id, OutboxState.CONFLICT, ex.message)
                saved != null -> {
                    // The draft itself was stored on the server; only submission failed (e.g. validation).
                    finishEntry(e, saved.version)
                    return SaveOutcome(saved, synced = true, message = ex.message)
                }
                else -> db.outbox().mark(e.id, OutboxState.FAILED, ex.message)
            }
            return null
        } catch (ex: Exception) {
            if (ex is kotlinx.coroutines.CancellationException) throw ex
            db.outbox().mark(e.id, OutboxState.FAILED, ex.message ?: "Unexpected error")
            return null
        }
    }

    private suspend fun finishEntry(e: OutboxEntity, newVersion: Int) {
        val current = db.outbox().get(e.id)
        if (current == null || current.updatedAt == e.updatedAt) {
            db.outbox().delete(e.id)
        } else {
            // Edited again while syncing: keep the newer local change, now based on the new server version.
            db.outbox().upsert(current.copy(baseVersion = newVersion, state = OutboxState.PENDING, error = null))
        }
    }

    /** Synchronises all pending drafts. Returns the number still waiting (offline). */
    suspend fun syncAll(): Int {
        val me = _session.value ?: return 0
        var waiting = 0
        syncMutex.withLock {
            for (e in db.outbox().all(me.userId)) {
                if (e.state == OutboxState.CONFLICT || e.state == OutboxState.FAILED) continue
                if (syncOne(e) == null && db.outbox().get(e.id)?.state == OutboxState.PENDING) waiting++
            }
        }
        notifyChanged()
        return waiting
    }

    /** Conflict resolution: keep the local version and overwrite the newer server version. */
    suspend fun resolveKeepMine(id: String): SaveOutcome? {
        val e = db.outbox().get(id) ?: return null
        val server = backend.submission(id)
        val updated = e.copy(baseVersion = server.version, state = OutboxState.PENDING, error = null, updatedAt = System.currentTimeMillis())
        db.outbox().upsert(updated)
        val r = syncMutex.withLock { syncOne(updated) }
        notifyChanged()
        return r
    }

    /** Conflict resolution / discard: drop the local draft and use the server version. */
    suspend fun discardLocal(id: String) {
        db.outbox().delete(id)
        notifyChanged()
    }

    /** Retry a failed entry after the user corrected it elsewhere. */
    suspend fun retry(id: String) {
        db.outbox().mark(id, OutboxState.PENDING, null)
        scheduler.scheduleSync()
        notifyChanged()
    }

    /** Deletes an unsubmitted additional draft (server and local). */
    suspend fun deleteDraft(item: RecordItem) {
        if (item.isLocalOnly && item.submission.version == 0) {
            db.outbox().delete(item.submission.id)
        } else {
            backend.deleteDraft(item.submission.id)
            db.outbox().delete(item.submission.id)
        }
        notifyChanged()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T, R> Flow<T>.flatMapLatestCompat(transform: suspend (T) -> Flow<R>): Flow<R> = flatMapLatest(transform)
