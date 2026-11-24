package org.mdpeds.logbook.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.Role
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.auth.LoginScreen
import org.mdpeds.logbook.ui.auth.SetPasswordScreen
import org.mdpeds.logbook.ui.components.LoadingBox
import org.mdpeds.logbook.ui.faculty.FacultyHomeScreen
import org.mdpeds.logbook.ui.faculty.ReviewQueueScreen
import org.mdpeds.logbook.ui.faculty.StudentDetailScreen
import org.mdpeds.logbook.ui.faculty.StudentsScreen
import org.mdpeds.logbook.ui.forms.CategoryScreen
import org.mdpeds.logbook.ui.forms.EditorScreen
import org.mdpeds.logbook.ui.forms.RecordsScreen
import org.mdpeds.logbook.ui.hod.AdminScreen
import org.mdpeds.logbook.ui.hod.FacultyManagementScreen
import org.mdpeds.logbook.ui.hod.HodHomeScreen
import org.mdpeds.logbook.ui.hod.InvitationsScreen
import org.mdpeds.logbook.ui.hod.MonitoringScreen
import org.mdpeds.logbook.ui.hod.ReportsScreen
import org.mdpeds.logbook.ui.screens.AuditScreen
import org.mdpeds.logbook.ui.screens.CertificateScreen
import org.mdpeds.logbook.ui.screens.DocumentScreen
import org.mdpeds.logbook.ui.screens.DocumentsScreen
import org.mdpeds.logbook.ui.screens.FeedbackScreen
import org.mdpeds.logbook.ui.screens.NotificationsScreen
import org.mdpeds.logbook.ui.screens.ProfileScreen
import org.mdpeds.logbook.ui.screens.SignoffsScreen
import org.mdpeds.logbook.ui.screens.SyncScreen
import org.mdpeds.logbook.ui.screens.TargetsScreen
import org.mdpeds.logbook.ui.student.StudentHomeScreen
import org.mdpeds.logbook.ui.theme.LogbookTheme
import javax.inject.Inject

@Composable
fun AppRoot(launchRequests: StateFlow<LaunchRequest?>, onConsumed: () -> Unit) {
    val root: RootViewModel = hiltViewModel()
    val theme by root.theme.collectAsStateWithLifecycle()
    val phase by root.phase.collectAsStateWithLifecycle()
    val request by launchRequests.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(request) {
        val r = request
        if (r is LaunchRequest.AuthCallback) {
            root.completeAuthCallback(r.uri)
            onConsumed()
        }
    }
    LaunchedEffect(Unit) { root.messages.collect { snackbar.showSnackbar(it) } }

    LogbookTheme(theme) {
        Surface(Modifier.fillMaxSize()) {
            when (val p = phase) {
                RootPhase.Starting -> LoadingBox()
                RootPhase.SignedOut -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()) { LoginScreen() }
                }
                is RootPhase.SetPassword -> Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                    Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()) { SetPasswordScreen(p.session, onDone = root::passwordSet) }
                }
                is RootPhase.SignedIn -> MainShell(
                    session = p.session,
                    openNotifications = request == LaunchRequest.OpenNotifications,
                    onConsumed = onConsumed,
                    snackbar = snackbar,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ shell

@HiltViewModel
class ShellViewModel @Inject constructor(private val repo: LogbookRepository) : ViewModel() {
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()
    val pendingSync: StateFlow<Int> = repo.observeOutbox().map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        refresh()
        viewModelScope.launch { repo.changes.collect { refresh() } }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.backend.notifications() }.onSuccess { list -> _unread.value = list.count { it.readAt == null } }
        }
    }

    fun signOut() {
        viewModelScope.launch { runCatching { repo.signOut() } }
    }
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private fun bottomTabs(s: UserSession): List<Dest> = when (s.role) {
    Role.STUDENT -> listOf(
        Dest(Routes.HOME, "Home", Icons.Filled.Home),
        Dest(Routes.category(s.userId, "LOG"), "Logs", Icons.Filled.EditNote),
        Dest(Routes.category(s.userId, "FORM"), "Forms", Icons.AutoMirrored.Filled.Assignment),
        Dest(Routes.records(s.userId, "MONTHLY"), "Monthly", Icons.Filled.CalendarMonth),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
    Role.FACULTY -> listOf(
        Dest(Routes.HOME, "Dashboard", Icons.Filled.Dashboard),
        Dest(Routes.STUDENTS, "Students", Icons.Filled.Groups),
        Dest(Routes.ASSESSMENTS, "Assessments", Icons.Filled.Assessment),
        Dest(Routes.QUEUE, "Pending", Icons.Filled.PendingActions),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
    Role.HOD -> listOf(
        Dest(Routes.HOME, "Dashboard", Icons.Filled.Dashboard),
        Dest(Routes.STUDENTS, "Students", Icons.Filled.Groups),
        Dest(Routes.MONITORING, "Monitoring", Icons.Filled.Monitor),
        Dest(Routes.QUEUE, "Reviews", Icons.Filled.RateReview),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
}

private fun drawerItems(s: UserSession): List<Dest> = when (s.role) {
    Role.STUDENT -> listOf(
        Dest(Routes.HOME, "Home", Icons.Filled.Home),
        Dest(Routes.DOCUMENTS, "Academic documents", Icons.AutoMirrored.Filled.MenuBook),
        Dest(Routes.category(s.userId, "LOG"), "Clinical logs", Icons.Filled.EditNote),
        Dest(Routes.category(s.userId, "FORM"), "Assessments & forms", Icons.AutoMirrored.Filled.Assignment),
        Dest(Routes.records(s.userId, "REFLECTION"), "Reflective writing", Icons.Filled.Psychology),
        Dest(Routes.records(s.userId, "MONTHLY"), "Monthly assessment", Icons.Filled.CalendarMonth),
        Dest(Routes.records(s.userId, "ANNUAL_REVIEW"), "Annual review", Icons.AutoMirrored.Filled.FactCheck),
        Dest(Routes.targets(s.userId), "Progress & targets", Icons.Filled.TrackChanges),
        Dest(Routes.signoffs(s.userId), "Competency sign-off", Icons.Filled.TaskAlt),
        Dest(Routes.certificate(s.userId), "Certificate", Icons.Filled.WorkspacePremium),
        Dest(Routes.feedback(s.userId), "Faculty feedback", Icons.Filled.Feedback),
        Dest(Routes.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications),
        Dest(Routes.SYNC, "Offline drafts", Icons.Filled.CloudSync),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
    Role.FACULTY -> listOf(
        Dest(Routes.HOME, "Dashboard", Icons.Filled.Dashboard),
        Dest(Routes.STUDENTS, "My students", Icons.Filled.Groups),
        Dest(Routes.ASSESSMENTS, "Assessments", Icons.Filled.Assessment),
        Dest(Routes.QUEUE, "Pending approvals", Icons.Filled.PendingActions),
        Dest(Routes.DOCUMENTS, "Academic documents", Icons.AutoMirrored.Filled.MenuBook),
        Dest(Routes.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications),
        Dest(Routes.audit(), "Activity history", Icons.Filled.History),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
    Role.HOD -> listOf(
        Dest(Routes.HOME, "Dashboard", Icons.Filled.Dashboard),
        Dest(Routes.STUDENTS, "Students", Icons.Filled.Groups),
        Dest(Routes.MONITORING, "Monitoring", Icons.Filled.Monitor),
        Dest(Routes.QUEUE, "Reviews & approvals", Icons.Filled.RateReview),
        Dest(Routes.FACULTY, "Faculty", Icons.Filled.SupervisorAccount),
        Dest(Routes.INVITATIONS, "Invitations", Icons.Filled.MailOutline),
        Dest(Routes.REPORTS, "Reports & exports", Icons.Filled.Insights),
        Dest(Routes.ADMIN, "Programme settings", Icons.Filled.AdminPanelSettings),
        Dest(Routes.DOCUMENTS, "Academic documents", Icons.Filled.Description),
        Dest(Routes.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications),
        Dest(Routes.audit(), "Audit log", Icons.Filled.Verified),
        Dest(Routes.PROFILE, "Profile", Icons.Filled.Person),
    )
}

/** The concrete route of a back-stack entry (placeholders replaced by argument values, query dropped). */
private fun NavBackStackEntry.concreteRoute(): String? {
    var r = destination.route?.substringBefore('?') ?: return null
    Regex("\\{([^}]+)\\}").findAll(r).toList().forEach { m ->
        val v = arguments?.getString(m.groupValues[1]) ?: return@forEach
        r = r.replace(m.value, android.net.Uri.encode(v))
    }
    return r
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun MainShell(session: UserSession, openNotifications: Boolean, onConsumed: () -> Unit, snackbar: SnackbarHostState) {
    val shell: ShellViewModel = hiltViewModel()
    val unread by shell.unread.collectAsStateWithLifecycle()
    val pending by shell.pendingSync.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.concreteRoute()
    val tabs = remember(session) { bottomTabs(session) }
    val drawer = remember(session) { drawerItems(session) }

    RequestNotificationPermission()

    LaunchedEffect(openNotifications) {
        if (openNotifications) {
            navController.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
            onConsumed()
        }
    }

    val nav = remember(session, navController) {
        AppNav(
            session = session,
            snackbar = snackbar,
            navigate = { route -> navController.navigate(route) { launchSingleTop = true } },
            goBack = { navController.popBackStack() },
            drawer = { scope.launch { drawerState.open() } },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    Spacer(Modifier.height(16.dp))
                    Text(session.fullName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(session.role.label, session.designation).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    drawer.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.label) },
                            icon = { Icon(d.icon, null) },
                            selected = current == d.route.substringBefore('?'),
                            badge = {
                                when {
                                    d.route == Routes.NOTIFICATIONS && unread > 0 -> Badge { Text("$unread") }
                                    d.route == Routes.SYNC && pending > 0 -> Badge { Text("$pending") }
                                }
                            },
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (tabs.any { it.route == d.route }) navController.openTab(d.route) else nav.open(d.route)
                            },
                            modifier = Modifier.testTag("drawer:${d.label}"),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("Account & sign out") },
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; nav.open(Routes.PROFILE) },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { navController.openTab(t.route) },
                            icon = {
                                if (t.route == Routes.HOME && unread > 0) {
                                    BadgedBox(badge = { Badge { Text("$unread") } }) { Icon(t.icon, null) }
                                } else {
                                    Icon(t.icon, null)
                                }
                            },
                            label = { Text(t.label, maxLines = 1) },
                            modifier = Modifier.testTag("tab:${t.label}"),
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppNav provides nav) {
                    AppNavHost(navController, session)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, session: UserSession) {
    fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)
    val str = { name: String -> navArgument(name) { type = NavType.StringType } }
    val opt = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }

    NavHost(navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            when (session.role) {
                Role.STUDENT -> StudentHomeScreen()
                Role.FACULTY -> FacultyHomeScreen()
                Role.HOD -> HodHomeScreen()
            }
        }
        composable(Routes.STUDENTS) { StudentsScreen() }
        composable(Routes.QUEUE) { ReviewQueueScreen(assessmentsOnly = false) }
        composable(Routes.ASSESSMENTS) { ReviewQueueScreen(assessmentsOnly = true) }
        composable(Routes.MONITORING) { MonitoringScreen() }
        composable(Routes.PROFILE) { ProfileScreen() }
        composable(Routes.DOCUMENTS) { DocumentsScreen() }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen() }
        composable(Routes.FACULTY) { FacultyManagementScreen() }
        composable(Routes.INVITATIONS) { InvitationsScreen() }
        composable(Routes.ADMIN) { AdminScreen() }
        composable(Routes.REPORTS) { ReportsScreen() }
        composable(Routes.SYNC) { SyncScreen() }
        composable("category/{studentId}/{category}", listOf(str("studentId"), str("category"))) {
            CategoryScreen(it.arg("studentId")!!, it.arg("category")!!)
        }
        composable("records/{studentId}/{code}", listOf(str("studentId"), str("code"))) {
            RecordsScreen(it.arg("studentId")!!, it.arg("code")!!)
        }
        composable(
            "editor/{studentId}/{code}?id={id}&period={period}&slot={slot}",
            listOf(str("studentId"), str("code"), opt("id"), opt("period"), opt("slot")),
        ) {
            EditorScreen(it.arg("studentId")!!, it.arg("code")!!, it.arg("id"), it.arg("period")?.toIntOrNull(), it.arg("slot")?.toIntOrNull())
        }
        composable("student/{id}", listOf(str("id"))) { StudentDetailScreen(it.arg("id")!!) }
        composable("targets/{id}", listOf(str("id"))) { TargetsScreen(it.arg("id")!!) }
        composable("signoffs/{id}", listOf(str("id"))) { SignoffsScreen(it.arg("id")!!) }
        composable("certificate/{id}", listOf(str("id"))) { CertificateScreen(it.arg("id")!!) }
        composable("feedback/{id}", listOf(str("id"))) { FeedbackScreen(it.arg("id")!!) }
        composable("audit?studentId={studentId}", listOf(opt("studentId"))) { AuditScreen(it.arg("studentId")) }
        composable("document/{code}", listOf(str("code"))) { DocumentScreen(it.arg("code")!!) }
    }
}

/** Top-level destinations reachable from the bottom bar or drawer show the menu button instead of Back. */
@Composable
fun isTopLevel(route: String): Boolean {
    val s = appNav.session
    return remember(s, route) { bottomTabs(s).any { it.route == route } || drawerItems(s).any { it.route == route } }
}
