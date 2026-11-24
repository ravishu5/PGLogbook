package org.mdpeds.logbook.ui.nav

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import org.mdpeds.logbook.domain.model.UserSession

object Routes {
    const val HOME = "home"
    const val STUDENTS = "students"
    const val QUEUE = "queue"
    const val ASSESSMENTS = "assessments"
    const val MONITORING = "monitoring"
    const val PROFILE = "profile"
    const val DOCUMENTS = "documents"
    const val NOTIFICATIONS = "notifications"
    const val FACULTY = "faculty"
    const val INVITATIONS = "invitations"
    const val ADMIN = "admin"
    const val REPORTS = "reports"
    const val SYNC = "sync"

    private fun e(s: String) = Uri.encode(s)

    fun category(studentId: String, category: String) = "category/${e(studentId)}/${e(category)}"
    fun records(studentId: String, code: String) = "records/${e(studentId)}/${e(code)}"
    fun editor(studentId: String, code: String, id: String? = null, period: Int? = null, slot: Int? = null): String {
        val q = listOfNotNull(id?.let { "id=${e(it)}" }, period?.let { "period=$it" }, slot?.let { "slot=$it" })
        return "editor/${e(studentId)}/${e(code)}" + if (q.isEmpty()) "" else q.joinToString("&", prefix = "?")
    }
    fun student(id: String) = "student/${e(id)}"
    fun targets(id: String) = "targets/${e(id)}"
    fun signoffs(id: String) = "signoffs/${e(id)}"
    fun certificate(id: String) = "certificate/${e(id)}"
    fun feedback(id: String) = "feedback/${e(id)}"
    fun audit(studentId: String? = null) = if (studentId == null) "audit" else "audit?studentId=${e(studentId)}"
    fun document(code: String) = "document/${e(code)}"
}

/** Navigation and app-wide services available to every screen. */
class AppNav(
    val session: UserSession,
    val snackbar: SnackbarHostState,
    private val navigate: (String) -> Unit,
    private val goBack: () -> Unit,
    private val drawer: () -> Unit,
) {
    fun open(route: String) = navigate(route)
    fun back() = goBack()
    fun openDrawer() = drawer()
}

val LocalAppNav = staticCompositionLocalOf<AppNav> { error("AppNav not provided") }

val appNav: AppNav
    @Composable get() = LocalAppNav.current

sealed interface LaunchRequest {
    data class AuthCallback(val uri: String) : LaunchRequest
    data object OpenNotifications : LaunchRequest
}
