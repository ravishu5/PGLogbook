package org.mdpeds.logbook

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mdpeds.logbook.data.demo.DemoBackend

/** End-to-end journeys against the in-memory demo backend (run with the demo flavor). */
@RunWith(AndroidJUnit4::class)
class AppJourneyTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val timeout = 15_000L

    private fun waitForTag(tag: String) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The demo backend is a process-wide singleton, so a previous test may have left a session open. */
    private fun ensureSignedOut() {
        rule.waitUntil(timeout) { exists("email") || exists("tab:Profile") }
        if (exists("email")) return
        rule.onNodeWithTag("tab:Profile", useUnmergedTree = true).performClick()
        rule.waitUntil(timeout) { rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("signOut"))
        rule.onNodeWithTag("signOut").performClick()
        waitForTag("confirmSignOut")
        rule.onNodeWithTag("confirmSignOut").performClick()
        waitForTag("email")
    }

    private fun signIn(email: String, password: String = DemoBackend.DEMO_PASSWORD) {
        ensureSignedOut()
        rule.onNodeWithTag("email").performTextInput(email)
        rule.onNodeWithTag("password").performTextInput(password)
        rule.onNodeWithTag("signIn").performClick()
    }

    @Test fun wrongPasswordShowsError() {
        signIn("student1@demo.org", "not-the-password")
        waitForTag("loginError")
    }

    @Test fun studentSeesDashboardAndCurrentMonth() {
        signIn("student1@demo.org")
        waitForTag("studentHome")
        waitForTag("currentMonth")
    }

    @Test fun facultySeesDashboardAndPendingQueue() {
        signIn("faculty1@demo.org")
        waitForTag("facultyHome")
        rule.onNodeWithTag("tab:Pending", useUnmergedTree = true).performClick()
        rule.waitUntil(timeout) { exists("list") || exists("empty") }
    }

    @Test fun hodSeesDashboard() {
        signIn("hod@demo.org")
        waitForTag("hodHome")
    }
}
