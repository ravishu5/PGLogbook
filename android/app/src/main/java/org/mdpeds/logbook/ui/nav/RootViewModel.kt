package org.mdpeds.logbook.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.data.local.ThemeMode
import org.mdpeds.logbook.data.local.UserPreferences
import org.mdpeds.logbook.domain.model.UserSession
import org.mdpeds.logbook.ui.common.userMessage
import javax.inject.Inject

sealed interface RootPhase {
    data object Starting : RootPhase
    data object SignedOut : RootPhase
    data class SignedIn(val session: UserSession) : RootPhase
    /** Arrived through an invitation / password-reset link: the user must choose a password first. */
    data class SetPassword(val session: UserSession) : RootPhase
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val repo: LogbookRepository,
    prefs: UserPreferences,
) : ViewModel() {

    private val restoring = MutableStateFlow(true)
    private val passwordRequired = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val theme: StateFlow<ThemeMode> = prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val phase: StateFlow<RootPhase> = combine(restoring, repo.session, passwordRequired) { r, s, p ->
        when {
            r -> RootPhase.Starting
            s == null -> RootPhase.SignedOut
            p -> RootPhase.SetPassword(s)
            else -> RootPhase.SignedIn(s)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootPhase.Starting)

    init {
        viewModelScope.launch {
            try {
                if (repo.session.value == null) repo.restore()
            } catch (_: Exception) {
                // No valid stored session: show the sign-in screen.
            } finally {
                restoring.value = false
            }
        }
    }

    fun completeAuthCallback(uri: String) {
        viewModelScope.launch {
            try {
                repo.completeAuthCallback(uri)
                passwordRequired.value = true
            } catch (e: Exception) {
                _messages.tryEmit(e.userMessage())
            }
        }
    }

    fun passwordSet() {
        passwordRequired.value = false
    }
}
