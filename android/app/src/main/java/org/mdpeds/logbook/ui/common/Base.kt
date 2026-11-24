package org.mdpeds.logbook.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mdpeds.logbook.data.Loaded
import org.mdpeds.logbook.data.LogbookRepository
import org.mdpeds.logbook.domain.model.BackendException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String, val offline: Boolean) : UiState<Nothing>
    data class Ready<T>(val value: T, val offline: Boolean = false, val refreshing: Boolean = false) : UiState<T>
}

fun Throwable.userMessage(): String = when (this) {
    is BackendException -> if (isNetwork) "You appear to be offline. Check your connection and try again." else message
    else -> message ?: "Something went wrong"
}

/** Shared base: one-shot messages and a busy flag for actions. */
abstract class BaseViewModel(protected val repo: LogbookRepository) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    protected fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** Runs an online action; reports [success] or the backend error. Returns false if it failed. */
    fun action(success: String? = null, onSuccess: () -> Unit = {}, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        try {
            block()
            success?.let(::message)
            onSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(e.userMessage())
        } finally {
            _busy.value = false
        }
    }
}

/**
 * Loads data for a screen bound to arguments [A]. Reloads silently whenever the repository reports a change
 * (save, review, sync), so every screen stays consistent after actions taken elsewhere.
 */
abstract class LoadViewModel<A : Any, T>(repo: LogbookRepository) : BaseViewModel(repo) {
    private val _state = MutableStateFlow<UiState<T>>(UiState.Loading)
    val state: StateFlow<UiState<T>> = _state.asStateFlow()

    private var args: A? = null
    private var loadJob: Job? = null
    private var watching = false

    protected abstract suspend fun load(args: A): Loaded<T>

    /** Called on the main thread after every successful load (initial and silent reloads). */
    protected open fun onLoaded(value: T) {}

    fun bind(a: A) {
        if (args == a) return
        args = a
        _state.value = UiState.Loading
        refresh()
        if (!watching) {
            watching = true
            viewModelScope.launch { repo.changes.collect { refresh(silent = true) } }
        }
    }

    protected val boundArgs: A get() = args ?: error("ViewModel not bound")

    fun refresh(silent: Boolean = false) {
        val a = args ?: return
        loadJob?.cancel()
        val current = _state.value
        if (!silent && current is UiState.Ready) _state.value = current.copy(refreshing = true)
        loadJob = viewModelScope.launch {
            _state.value = try {
                val r = load(a)
                onLoaded(r.value)
                UiState.Ready(r.value, r.offline)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val prev = _state.value
                if (silent && prev is UiState.Ready) {
                    prev.copy(refreshing = false)
                } else {
                    UiState.Error(e.userMessage(), (e as? BackendException)?.isNetwork == true)
                }
            }
        }
    }

    protected fun <V> fresh(value: V) = Loaded(value, offline = false)
}

// ------------------------------------------------------------------ formatting

private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val dateTimeFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH)

fun LocalDate.display(): String = format(dateFmt)

fun parseDate(value: String?): LocalDate? = value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** Formats an ISO date or timestamp as "05 Mar 2026". */
fun formatDate(value: String?): String = parseDate(value)?.display() ?: "—"

/** Formats an ISO timestamp in the device time zone as "05 Mar 2026, 14:30". */
fun formatDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant() }.getOrNull()
        ?: return formatDate(value)
    return instant.atZone(ZoneId.systemDefault()).format(dateTimeFmt)
}

fun enumLabel(value: String?): String =
    value.orEmpty().lowercase(Locale.ENGLISH).split('_').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ENGLISH) } }
