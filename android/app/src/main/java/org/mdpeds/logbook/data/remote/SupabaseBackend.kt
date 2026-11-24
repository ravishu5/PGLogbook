package org.mdpeds.logbook.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.mdpeds.logbook.BuildConfig
import org.mdpeds.logbook.data.LogbookBackend
import org.mdpeds.logbook.data.local.SecureSessionStore
import org.mdpeds.logbook.domain.logic.AppJson
import org.mdpeds.logbook.domain.model.AcademicDocument
import org.mdpeds.logbook.domain.model.AppNotification
import org.mdpeds.logbook.domain.model.Assignment
import org.mdpeds.logbook.domain.model.Attachment
import org.mdpeds.logbook.domain.model.AuditEntry
import org.mdpeds.logbook.domain.model.BackendException
import org.mdpeds.logbook.domain.model.Batch
import org.mdpeds.logbook.domain.model.Certificate
import org.mdpeds.logbook.domain.model.CertificationState
import org.mdpeds.logbook.domain.model.Competency
import org.mdpeds.logbook.domain.model.FeedbackEntry
import org.mdpeds.logbook.domain.model.InstitutionSettings
import org.mdpeds.logbook.domain.model.Invitation
import org.mdpeds.logbook.domain.model.InvitationRequest
import org.mdpeds.logbook.domain.model.Option
import org.mdpeds.logbook.domain.model.ReviewAction
import org.mdpeds.logbook.domain.model.ReviewQueueItem
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.SaveRequest
import org.mdpeds.logbook.domain.model.Signoff
import org.mdpeds.logbook.domain.model.SignoffAction
import org.mdpeds.logbook.domain.model.StaffMember
import org.mdpeds.logbook.domain.model.StudentOverview
import org.mdpeds.logbook.domain.model.Submission
import org.mdpeds.logbook.domain.model.Target
import org.mdpeds.logbook.domain.model.TargetProgress
import org.mdpeds.logbook.domain.model.Template
import org.mdpeds.logbook.domain.model.UserSession
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Supabase implementation: GoTrue for auth, PostgREST RPCs for every state change
 * (the database enforces RBAC, workflow transitions, validation and audit), Storage for evidence.
 */
class SupabaseBackend(
    private val store: SecureSessionStore,
    private val baseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) : LogbookBackend {

    override val isDemo = false

    @Volatile private var accessToken: String? = store.read(KEY_ACCESS)
    @Volatile private var refreshToken: String? = store.read(KEY_REFRESH)
    @Volatile private var expiresAt: Long = store.read(KEY_EXPIRES)?.toLongOrNull() ?: 0L
    private var session: UserSession? = store.read(KEY_SESSION)?.let { runCatching { AppJson.decodeFromString(UserSession.serializer(), it) }.getOrNull() }
    private val refreshLock = Mutex()

    private val api: SupabaseApi by lazy {
        if (baseUrl.isBlank() || anonKey.isBlank()) {
            throw BackendException(500, "Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
        }
        val auth = Interceptor { chain ->
            val req = chain.request().newBuilder().header("apikey", anonKey)
            if (chain.request().header("Authorization") == null) {
                req.header("Authorization", "Bearer ${accessToken ?: anonKey}")
            }
            chain.proceed(req.build())
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(auth)
            .apply {
                if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
            .build()
        Retrofit.Builder().baseUrl(baseUrl.trimEnd('/') + "/").client(client).build().create(SupabaseApi::class.java)
    }

    // =============================================================== plumbing

    private fun json(obj: JsonElement): RequestBody = obj.toString().toRequestBody(JSON)

    private suspend fun ensureFresh() {
        val rt = refreshToken ?: return
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) return
        refreshLock.withLock {
            if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) return
            val res = try {
                api.token("refresh_token", json(buildJsonObject { put("refresh_token", rt) }))
            } catch (e: IOException) {
                throw BackendException(0, "You are offline. Changes are saved on this device.")
            }
            if (!res.isSuccessful) {
                clearTokens()
                throw BackendException(401, "Your session has expired. Please sign in again.")
            }
            storeTokens(AppJson.parseToJsonElement(res.body()?.string().orEmpty()).jsonObject)
        }
    }

    private suspend fun call(authRequired: Boolean = true, block: suspend () -> Response<ResponseBody>): String {
        if (authRequired) ensureFresh()
        val res = try {
            block()
        } catch (e: IOException) {
            throw BackendException(0, "Unable to reach the server. Check your connection.")
        }
        if (res.isSuccessful) return res.body()?.string().orEmpty()
        throw errorOf(res.code(), res.errorBody()?.string().orEmpty())
    }

    private suspend fun callBytes(block: suspend () -> Response<ResponseBody>): ByteArray {
        ensureFresh()
        val res = try { block() } catch (e: IOException) { throw BackendException(0, "Unable to reach the server. Check your connection.") }
        if (res.isSuccessful) return res.body()?.bytes() ?: ByteArray(0)
        throw errorOf(res.code(), res.errorBody()?.string().orEmpty())
    }

    private fun errorOf(code: Int, body: String): BackendException {
        val obj = runCatching { AppJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = listOf("message", "msg", "error_description", "error")
            .firstNotNullOfOrNull { obj?.get(it)?.let { e -> (e as? JsonPrimitive)?.contentOrNull } }
            ?: body.ifBlank { "Request failed ($code)" }
        val friendly = when {
            code == 401 -> "Your session has expired. Please sign in again."
            message.contains("Invalid login credentials", ignoreCase = true) -> "Incorrect email or password."
            code == 403 && message.contains("row-level security", ignoreCase = true) -> "You are not allowed to perform this action."
            else -> message
        }
        return BackendException(code, friendly)
    }

    private suspend fun rpc(fn: String, args: JsonObject): String = call { api.rpc(fn, json(args)) }

    private suspend fun <T> rpcOne(fn: String, args: JsonObject, serializer: KSerializer<T>): T =
        decodeOne(rpc(fn, args), serializer)

    private fun <T> decodeOne(body: String, serializer: KSerializer<T>): T {
        val el = AppJson.parseToJsonElement(body)
        val obj = if (el is JsonArray) el.firstOrNull() ?: throw BackendException(404, "Not found") else el
        return AppJson.decodeFromJsonElement(serializer, obj)
    }

    private suspend fun <T> select(table: String, serializer: KSerializer<T>, vararg query: Pair<String, String>): List<T> {
        val q = linkedMapOf("select" to "*")
        q.putAll(query)
        return AppJson.decodeFromString(ListSerializer(serializer), call { api.select(table, q) })
    }

    private fun eq(v: String) = "eq.$v"

    private fun stringMap(map: Map<String, String>) = JsonObject(map.mapValues { JsonPrimitive(it.value) })

    private fun encodePath(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun storeTokens(obj: JsonObject) {
        accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
        refreshToken = obj["refresh_token"]?.jsonPrimitive?.contentOrNull ?: refreshToken
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        expiresAt = System.currentTimeMillis() + expiresIn * 1000
        store.write(KEY_ACCESS, accessToken)
        store.write(KEY_REFRESH, refreshToken)
        store.write(KEY_EXPIRES, expiresAt.toString())
    }

    private fun clearTokens() {
        accessToken = null
        refreshToken = null
        expiresAt = 0
        session = null
        store.clear()
    }

    private suspend fun loadProfile(userId: String): UserSession {
        val rows = AppJson.parseToJsonElement(call { api.select("profiles", mapOf("select" to "id,full_name,email,role,designation,active", "id" to eq(userId))) })
        val p = (rows as? JsonArray)?.firstOrNull()?.jsonObject
            ?: run { clearTokens(); throw BackendException(403, "Your account has not been set up yet. Contact the HOD.") }
        if (p["active"]?.jsonPrimitive?.contentOrNull == "false") {
            clearTokens()
            throw BackendException(403, "Your account has been deactivated. Contact the HOD.")
        }
        val s = UserSession(
            userId = userId,
            email = p["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            fullName = p["full_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            role = Role.from(p["role"]?.jsonPrimitive?.contentOrNull) ?: throw BackendException(403, "Unknown role"),
            designation = p["designation"]?.jsonPrimitive?.contentOrNull,
        )
        session = s
        store.write(KEY_SESSION, AppJson.encodeToString(UserSession.serializer(), s))
        return s
    }

    private fun userIdFrom(obj: JsonObject): String? =
        (obj["user"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: obj["id"]?.jsonPrimitive?.contentOrNull

    private fun me(): UserSession = session ?: throw BackendException(401, "Please sign in.")

    // =============================================================== authentication

    override suspend fun signIn(email: String, password: String): UserSession {
        val body = call(authRequired = false) {
            api.token("password", json(buildJsonObject { put("email", email.trim()); put("password", password) }))
        }
        val obj = AppJson.parseToJsonElement(body).jsonObject
        storeTokens(obj)
        return loadProfile(userIdFrom(obj) ?: throw BackendException(500, "Malformed sign-in response"))
    }

    override suspend fun restoreSession(): UserSession? {
        if (refreshToken == null) return null
        val cached = session
        return try {
            ensureFresh()
            val user = AppJson.parseToJsonElement(call { api.user() }).jsonObject
            loadProfile(userIdFrom(user) ?: return null)
        } catch (e: BackendException) {
            if (e.isNetwork) cached else { clearTokens(); null }
        }
    }

    override suspend fun signOut() {
        runCatching { if (accessToken != null) api.logout() }
        clearTokens()
    }

    override suspend fun sendPasswordReset(email: String) {
        call(authRequired = false) { api.recover(REDIRECT, json(buildJsonObject { put("email", email.trim()) })) }
    }

    override suspend fun completeAuthCallback(uri: String): UserSession {
        val fragment = uri.substringAfter('#', "").ifEmpty { uri.substringAfter('?', "") }
        val params = fragment.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        params["error_description"]?.let { throw BackendException(401, it) }
        val token = params["access_token"] ?: throw BackendException(400, "The link is invalid or has expired.")
        storeTokens(buildJsonObject {
            put("access_token", token)
            params["refresh_token"]?.let { put("refresh_token", it) }
            put("expires_in", params["expires_in"]?.toLongOrNull() ?: 3600L)
        })
        val user = AppJson.parseToJsonElement(call { api.user() }).jsonObject
        return loadProfile(userIdFrom(user) ?: throw BackendException(401, "The link is invalid or has expired."))
    }

    override suspend fun setPassword(newPassword: String) {
        call { api.updateUser(json(buildJsonObject { put("password", newPassword) })) }
    }

    // =============================================================== reference data

    private var templateCache: Pair<List<Template>, List<Option>>? = null

    private suspend fun loadTemplates(): Pair<List<Template>, List<Option>> {
        templateCache?.let { return it }
        val rows = AppJson.parseToJsonElement(
            call { api.select("form_templates", mapOf("select" to "schema,version", "active" to "eq.true", "order" to "sort_order")) },
        ) as JsonArray
        var roleOptions = emptyList<Option>()
        val templates = rows.map { row ->
            val schema = row.jsonObject["schema"]!!.jsonObject
            if (roleOptions.isEmpty()) {
                schema["roleOptions"]?.let { roleOptions = AppJson.decodeFromJsonElement(ListSerializer(Option.serializer()), it) }
            }
            AppJson.decodeFromJsonElement(Template.serializer(), schema).copy(version = row.jsonObject["version"]?.jsonPrimitive?.int ?: 1)
        }
        return (templates to roleOptions).also { templateCache = it }
    }

    override suspend fun templates() = loadTemplates().first
    override suspend fun roleOptions() = loadTemplates().second

    override suspend fun competencies(): List<Competency> =
        select("competencies", Competency.serializer(), "active" to "eq.true", "order" to "sort_order")

    override suspend fun targets(): List<Target> =
        select("targets", Target.serializer(), "is_current" to "eq.true", "order" to "category,sort_order")

    override suspend fun documents(): List<AcademicDocument> {
        val rows = AppJson.parseToJsonElement(
            call {
                api.select(
                    "academic_documents",
                    mapOf(
                        "select" to "code,title,description,sort_order,document_versions(version_no,content,storage_path,file_name,published_at,notes,published)",
                        "document_versions.published" to "eq.true",
                        "order" to "sort_order",
                    ),
                )
            },
        ) as JsonArray
        return rows.mapNotNull { r ->
            val o = r.jsonObject
            val v = (o["document_versions"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            AcademicDocument(
                code = o["code"]!!.jsonPrimitive.content,
                title = o["title"]!!.jsonPrimitive.content,
                description = o["description"]?.jsonPrimitive?.contentOrNull,
                sortOrder = o["sort_order"]?.jsonPrimitive?.intOrNull ?: 0,
                versionNo = v["version_no"]?.jsonPrimitive?.intOrNull ?: 1,
                content = v["content"] as? JsonObject,
                storagePath = v["storage_path"]?.jsonPrimitive?.contentOrNull,
                fileName = v["file_name"]?.jsonPrimitive?.contentOrNull,
                publishedAt = v["published_at"]?.jsonPrimitive?.contentOrNull,
                notes = v["notes"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun settings(): InstitutionSettings =
        select("institution_settings", InstitutionSettings.serializer(), "id" to "eq.1").firstOrNull() ?: InstitutionSettings()

    override suspend fun batches(): List<Batch> = select("academic_batches", Batch.serializer(), "order" to "name")

    // =============================================================== people

    override suspend fun studentOverviews(): List<StudentOverview> =
        select("v_student_overview", StudentOverview.serializer(), "order" to "full_name")

    override suspend fun studentOverview(studentId: String): StudentOverview =
        select("v_student_overview", StudentOverview.serializer(), "student_id" to eq(studentId)).firstOrNull()
            ?: throw BackendException(404, "Student not found or not assigned to you")

    override suspend fun staff(): List<StaffMember> = select("v_staff_directory", StaffMember.serializer(), "order" to "full_name")

    override suspend fun assignments(studentId: String): List<Assignment> =
        select("faculty_assignments", Assignment.serializer(), "student_id" to eq(studentId), "active" to "eq.true")

    // =============================================================== records

    override suspend fun submissions(studentId: String, templateCode: String?): List<Submission> {
        val q = mutableListOf("student_id" to eq(studentId), "deleted_at" to "is.null", "order" to "period_no.asc.nullslast,slot_no.asc.nullslast,entry_date.desc.nullslast,created_at.desc")
        if (templateCode != null) q += "template_code" to eq(templateCode)
        return select("submissions", Submission.serializer(), *q.toTypedArray())
    }

    override suspend fun submission(id: String): Submission =
        select("submissions", Submission.serializer(), "id" to eq(id)).firstOrNull() ?: throw BackendException(404, "Record not found")

    override suspend fun saveSubmission(request: SaveRequest): Submission = rpcOne(
        "save_submission",
        buildJsonObject {
            put("p_id", request.id)
            put("p_template", request.templateCode)
            put("p_student_data", stringMap(request.studentData))
            put("p_expected_version", request.expectedVersion)
            put("p_period", request.periodNo)
            put("p_slot", request.slotNo)
        },
        Submission.serializer(),
    )

    override suspend fun submitSubmission(id: String, expectedVersion: Int?): Submission =
        rpcOne("submit_submission", buildJsonObject { put("p_id", id); put("p_expected_version", expectedVersion) }, Submission.serializer())

    override suspend fun deleteDraft(id: String) {
        rpc("delete_draft", buildJsonObject { put("p_id", id) })
    }

    override suspend fun reviewSubmission(id: String, action: ReviewAction, facultyData: Map<String, String>, comment: String?, expectedVersion: Int?) =
        rpcOne(
            "review_submission",
            buildJsonObject {
                put("p_id", id)
                put("p_action", action.name)
                put("p_faculty_data", stringMap(facultyData))
                put("p_comment", comment)
                put("p_expected_version", expectedVersion)
            },
            Submission.serializer(),
        )

    override suspend fun hodReviewSubmission(id: String, hodData: Map<String, String>, expectedVersion: Int?) = rpcOne(
        "hod_review_submission",
        buildJsonObject { put("p_id", id); put("p_hod_data", stringMap(hodData)); put("p_expected_version", expectedVersion) },
        Submission.serializer(),
    )

    override suspend fun reopenSubmission(id: String, reason: String) =
        rpcOne("reopen_submission", buildJsonObject { put("p_id", id); put("p_reason", reason) }, Submission.serializer())

    override suspend fun correctReview(id: String, facultyData: Map<String, String>, reason: String) = rpcOne(
        "correct_review",
        buildJsonObject { put("p_id", id); put("p_faculty_data", stringMap(facultyData)); put("p_reason", reason) },
        Submission.serializer(),
    )

    override suspend fun saveAnnualReview(studentId: String, year: Int, data: Map<String, String>, submit: Boolean, reason: String?, expectedVersion: Int?) =
        rpcOne(
            "save_annual_review",
            buildJsonObject {
                put("p_student", studentId)
                put("p_year", year)
                put("p_data", stringMap(data))
                put("p_submit", submit)
                put("p_reason", reason)
                put("p_expected_version", expectedVersion)
            },
            Submission.serializer(),
        )

    override suspend fun targetProgress(studentId: String): List<TargetProgress> =
        select("v_target_progress", TargetProgress.serializer(), "student_id" to eq(studentId), "order" to "category,sort_order")

    // =============================================================== evidence

    override suspend fun attachments(submissionId: String): List<Attachment> =
        select("attachments", Attachment.serializer(), "submission_id" to eq(submissionId), "order" to "created_at")

    override suspend fun uploadAttachment(studentId: String, submissionId: String, fileName: String, mimeType: String, bytes: ByteArray): Attachment {
        if (bytes.size > MAX_EVIDENCE) throw BackendException(422, "Files must be 10 MB or smaller")
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        val path = "$studentId/$submissionId/${UUID.randomUUID()}-$safe"
        call { api.upload("evidence", encodePath(path), "false", bytes.toRequestBody(mimeType.toMediaType())) }
        return rpcOne(
            "register_attachment",
            buildJsonObject {
                put("p_submission", submissionId)
                put("p_path", path)
                put("p_file_name", fileName)
                put("p_mime", mimeType)
                put("p_size", bytes.size.toLong())
            },
            Attachment.serializer(),
        )
    }

    override suspend fun downloadAttachment(storagePath: String): ByteArray = callBytes { api.download("evidence", encodePath(storagePath)) }

    // =============================================================== sign-off & certification

    override suspend fun signoffs(studentId: String): List<Signoff> =
        select("competency_signoffs", Signoff.serializer(), "student_id" to eq(studentId))

    override suspend fun requestSignoff(code: String, note: String?): Signoff =
        rpcOne("request_signoff", buildJsonObject { put("p_code", code); put("p_note", note) }, Signoff.serializer())

    override suspend fun facultySignoff(studentId: String, code: String, action: SignoffAction, comment: String?, expectedVersion: Int?): Signoff =
        rpcOne(
            "faculty_signoff",
            buildJsonObject {
                put("p_student", studentId)
                put("p_code", code)
                put("p_action", action.name)
                put("p_comment", comment)
                put("p_expected_version", expectedVersion)
            },
            Signoff.serializer(),
        )

    override suspend fun certificationStatus(studentId: String): CertificationState =
        decodeOne(rpc("certification_status", buildJsonObject { put("p_student", studentId) }), CertificationState.serializer())

    override suspend fun certificate(studentId: String): Certificate? =
        select("certificates", Certificate.serializer(), "student_id" to eq(studentId), "status" to "eq.ISSUED").firstOrNull()

    override suspend fun issueCertificate(studentId: String): Certificate =
        rpcOne("issue_certificate", buildJsonObject { put("p_student", studentId) }, Certificate.serializer())

    override suspend fun revokeCertificate(studentId: String, reason: String) {
        rpc("revoke_certificate", buildJsonObject { put("p_student", studentId); put("p_reason", reason) })
    }

    // =============================================================== queue, notifications, history

    override suspend fun reviewQueue(): List<ReviewQueueItem> =
        select("v_review_queue", ReviewQueueItem.serializer(), "order" to "submitted_at.asc.nullslast")

    override suspend fun notifications(): List<AppNotification> =
        select("notifications", AppNotification.serializer(), "recipient_id" to eq(me().userId), "order" to "created_at.desc", "limit" to "200")

    override suspend fun markNotificationsRead(ids: List<String>?) {
        rpc("mark_notifications_read", buildJsonObject {
            if (ids == null) put("p_ids", JsonNull) else putJsonArray("p_ids") { ids.forEach { add(JsonPrimitive(it)) } }
        })
    }

    override suspend fun feedback(studentId: String): List<FeedbackEntry> =
        select("faculty_feedback", FeedbackEntry.serializer(), "student_id" to eq(studentId), "order" to "created_at.desc")

    override suspend fun auditLog(studentId: String?): List<AuditEntry> {
        val q = mutableListOf("order" to "at.desc", "limit" to "300")
        if (studentId != null) q += "student_id" to eq(studentId)
        return select("audit_logs", AuditEntry.serializer(), *q.toTypedArray())
    }

    // =============================================================== HOD administration

    override suspend fun invitations(): List<Invitation> = select("invitations", Invitation.serializer(), "order" to "created_at.desc")

    override suspend fun createInvitation(request: InvitationRequest): Invitation {
        val inv = rpcOne(
            "create_invitation",
            buildJsonObject {
                put("p_email", request.email.trim())
                put("p_full_name", request.fullName.trim())
                put("p_role", request.role.name)
                put("p_designation", request.designation)
                put("p_reg_no", request.universityRegNo)
                put("p_batch", request.batchId)
                put("p_start", request.programmeStartDate)
                put("p_guide", request.guideId)
            },
            Invitation.serializer(),
        )
        // The Edge Function (service role) sends the Supabase invite email for the pending invitation.
        call { api.function("invite-user", json(buildJsonObject { put("invitation_id", inv.id); put("redirect_to", REDIRECT) })) }
        return inv
    }

    override suspend fun revokeInvitation(id: String) {
        rpc("revoke_invitation", buildJsonObject { put("p_id", id) })
    }

    override suspend fun assignFaculty(studentId: String, facultyId: String, role: String) {
        rpc("assign_faculty", buildJsonObject { put("p_student", studentId); put("p_faculty", facultyId); put("p_role", role) })
    }

    override suspend fun unassignFaculty(studentId: String, facultyId: String) {
        rpc("unassign_faculty", buildJsonObject { put("p_student", studentId); put("p_faculty", facultyId) })
    }

    override suspend fun updateStudentDetails(studentId: String, data: Map<String, String?>) {
        rpc("update_student_details", buildJsonObject {
            put("p_student", studentId)
            put("p_data", JsonObject(data.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) }))
        })
    }

    override suspend fun setProgrammeStart(studentId: String, start: String, durationMonths: Int?, reason: String) {
        rpc("set_programme_start", buildJsonObject {
            put("p_student", studentId)
            put("p_start", start)
            put("p_duration", durationMonths)
            put("p_reason", reason)
        })
    }

    override suspend fun upsertBatch(id: String?, name: String, academicYear: String, intakeDate: String?): Batch = rpcOne(
        "upsert_batch",
        buildJsonObject { put("p_id", id); put("p_name", name); put("p_academic_year", academicYear); put("p_intake", intakeDate) },
        Batch.serializer(),
    )

    override suspend fun updateSettings(data: JsonObject): InstitutionSettings =
        rpcOne("update_institution_settings", buildJsonObject { put("p_data", data) }, InstitutionSettings.serializer())

    override suspend fun setTarget(key: String, value: Int, notes: String?): Target = rpcOne(
        "set_target",
        buildJsonObject { put("p_key", key); put("p_value", value); put("p_notes", notes) },
        Target.serializer(),
    )

    override suspend fun updateCompetency(code: String, title: String, domain: String, progression: String?): Competency = rpcOne(
        "update_competency",
        buildJsonObject { put("p_code", code); put("p_title", title); put("p_domain", domain); put("p_progression", progression) },
        Competency.serializer(),
    )

    override suspend fun publishDocument(code: String, content: JsonObject?, pdf: ByteArray?, fileName: String?, notes: String?): AcademicDocument {
        var path: String? = null
        if (pdf != null) {
            val safe = (fileName ?: "$code.pdf").replace(Regex("[^A-Za-z0-9._-]"), "_")
            path = "$code/${System.currentTimeMillis()}/$safe"
            call { api.upload("documents", encodePath(path), "false", pdf.toRequestBody(PDF)) }
        }
        rpc("publish_document_version", buildJsonObject {
            put("p_code", code)
            put("p_content", content ?: JsonNull)
            put("p_storage_path", path)
            put("p_file_name", fileName)
            put("p_notes", notes)
        })
        return documents().first { it.code == code }
    }

    override suspend fun downloadDocumentFile(storagePath: String): ByteArray = callBytes { api.download("documents", encodePath(storagePath)) }

    override suspend fun setUserActive(userId: String, active: Boolean) {
        rpc("set_user_active", buildJsonObject { put("p_user", userId); put("p_active", active) })
    }

    override suspend fun updateFacultyProfile(facultyId: String, designation: String?, unit: String?, isUnitHead: Boolean) {
        rpc("update_faculty_profile", buildJsonObject {
            put("p_faculty", facultyId)
            put("p_designation", designation)
            put("p_unit", unit)
            put("p_is_unit_head", isUnitHead)
        })
    }

    override suspend fun updateMyProfile(data: Map<String, String>) {
        rpc("update_my_profile", buildJsonObject { put("p_data", stringMap(data)) })
        session?.let { s -> data["designation"]?.let { loadProfile(s.userId) } }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val PDF = "application/pdf".toMediaType()
        const val REDIRECT = "mdpeds://auth-callback"
        const val MAX_EVIDENCE = 10 * 1024 * 1024
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_SESSION = "session"
    }
}
