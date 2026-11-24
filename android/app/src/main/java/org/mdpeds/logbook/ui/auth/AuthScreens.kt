package org.mdpeds.logbook.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.BaseViewModel
import org.mdpeds.logbook.ui.components.InfoCard
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(repo: LogbookRepository) : BaseViewModel(repo) {
    val isDemo = repo.isDemo
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun signIn(email: String, password: String) {
        _error.value = null
        if (email.isBlank() || password.isBlank()) {
            _error.value = "Enter your email and password."
            return
        }
        action {
            try {
                repo.signIn(email.trim(), password)
            } catch (e: Exception) {
                _error.value = (e as? org.mdpeds.logbook.domain.model.BackendException)?.let {
                    if (it.isNetwork) "Can't reach the server. Check your connection." else it.message
                } ?: (e.message ?: "Sign-in failed")
            }
        }
    }

    fun sendReset(email: String) = action(success = "If an account exists for $email, a reset link has been sent.") {
        repo.backend.sendPasswordReset(email.trim())
    }

    fun setPassword(password: String, onDone: () -> Unit) = action(success = "Password set. Welcome!", onSuccess = onDone) {
        repo.backend.setPassword(password)
    }
}

/** Password policy for invitation / reset links. */
fun passwordProblem(p: String, confirm: String): String? = when {
    p.length < 8 -> "Use at least 8 characters."
    !p.any { it.isLetter() } || !p.any { it.isDigit() } -> "Use both letters and numbers."
    p != confirm -> "Passwords do not match."
    else -> null
}

private val demoAccounts = listOf(
    "student1@demo.org" to "PG student (month 14)",
    "student2@demo.org" to "PG student (month 2)",
    "student3@demo.org" to "PG student (certified)",
    "faculty1@demo.org" to "Faculty / guide",
    "faculty2@demo.org" to "Faculty",
    "hod@demo.org" to "Head of Department",
)

@Composable
fun LoginScreen() {
    val vm: AuthViewModel = hiltViewModel()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { info = it } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Filled.LocalHospital, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("MD Pediatrics PG Logbook", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Competency-based logbook and academic management",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("email"),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("password"),
            )
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("loginError"))
            Button(onClick = { vm.signIn(email, password) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("signIn")) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
            TextButton(onClick = { forgot = true }) { Text("Forgot password?") }
            Text(
                "Accounts are created by the department. New users receive an invitation email to set their password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (vm.isDemo) {
                InfoCard {
                    Text("Demo mode", style = MaterialTheme.typography.titleSmall)
                    Text("All demo accounts use the password Demo@1234. Data is kept in memory and resets when the app restarts.", style = MaterialTheme.typography.bodySmall)
                    demoAccounts.forEach { (mail, label) ->
                        SuggestionChip(
                            onClick = { email = mail; password = "Demo@1234" },
                            label = { Text("$label — $mail") },
                            modifier = Modifier.testTag("demo:$mail"),
                        )
                    }
                }
            }
        }
    }

    if (forgot) {
        var resetEmail by rememberSaveable { mutableStateOf(email) }
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Reset password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your registered email. You'll receive a secure link to choose a new password.")
                    OutlinedTextField(value = resetEmail, onValueChange = { resetEmail = it }, label = { Text("Email") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = { forgot = false; vm.sendReset(resetEmail) }, enabled = resetEmail.contains('@')) { Text("Send link") } },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Cancel") } },
        )
    }
    info?.let { text ->
        AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } }, text = { Text(text) })
    }
}

@Composable
fun SetPasswordScreen(session: UserSession, onDone: () -> Unit) {
    val vm: AuthViewModel = hiltViewModel()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { message = it } }
    val problem = passwordProblem(password, confirm)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Choose your password", style = MaterialTheme.typography.headlineSmall)
            Text("Signed in as ${session.fullName} (${session.email}) — ${session.role.label}.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("New password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                value = confirm, onValueChange = { confirm = it }, label = { Text("Confirm password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = password.isNotEmpty() && confirm.isNotEmpty() && problem != null,
                supportingText = { Text(if (password.isEmpty()) "At least 8 characters with letters and numbers." else problem ?: "Looks good.") },
            )
            Button(onClick = { vm.setPassword(password, onDone) }, enabled = problem == null && !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Save password")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
