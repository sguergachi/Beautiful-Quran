package com.beautifulquran.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beautifulquran.QuranApp
import com.beautifulquran.data.account.QfAccount
import com.beautifulquran.data.account.QfSignInActivity
import com.beautifulquran.ui.theme.QuranTheme
import com.beautifulquran.ui.theme.quietClickable
import com.beautifulquran.ui.theme.verticalFadingEdges
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Consent, import and erasure each occupy lines on the account sheet; nothing floats over the book. */
@Composable
internal fun QfAccountPage(account: QfAccount, onBack: () -> Unit) {
    val status by account.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as QuranApp
    val saved by app.bookmarks.bookmarks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adult by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var exportNote by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri ->
        if (uri != null) scope.launch {
            exportNote = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(account.exportBookmarks().toByteArray(Charsets.UTF_8))
                    } ?: error("Export unavailable")
                    "Bookmarks exported."
                }.getOrElse { "Export could not be saved. Try another location." }
            }
        }
    }
    fun action(block: () -> Unit) { scope.launch(Dispatchers.IO) { block() } }
    fun open(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth()
        .windowInsetsPadding(WindowInsets.systemBars)
        .verticalFadingEdges(MaterialTheme.colorScheme.background, top = 20.dp, bottom = 40.dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
        Spacer(Modifier.height(20.dp))
        BackChevron { if (confirmation != null) confirmation = null else onBack() }
        Spacer(Modifier.height(6.dp))
        Text("Quran Foundation", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        if (confirmation != null) {
            val erasing = confirmation == "erase"
            Caption(if (erasing)
                "Remove every Hafs verse bookmark in the Beautiful Quran collection from QF. " +
                    "Other collections, reading history and device bookmarks are kept. " +
                    "If offline, this request is saved until sync completes."
                else "Disconnect restores this device's separate bookmarks and clears its QF cache " +
                    "and " + status.pending + " queued edits. Export first to keep a copy. " +
                    "The collection and your account remain in QF.")
            Spacer(Modifier.height(16.dp))
            AccountAction(if (erasing) "Remove synced bookmarks" else "Disconnect from this device") {
                if (erasing) action(account::eraseSyncedBookmarks) else action(account::disconnect)
                confirmation = null
            }
            AccountAction("Keep connected") { confirmation = null }
        } else {
            if (status.environment == "prelive" && status.available) Caption("QF test environment")
            if (status.connected) {
                Caption(saved.size.toString() + " saved verses · " + status.pending + " edits waiting")
                if (status.lastSyncedAt > 0) Caption("Last synced " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(status.lastSyncedAt)))
                if (status.deleting) Caption("Removal is pending until QF confirms an empty collection.")
                Spacer(Modifier.height(16.dp))
                AccountAction("Sync now", enabled = !status.busy) { account.retry() }
                val deviceCount = app.bookmarks.deviceBookmarks().size
                if (deviceCount > 0) {
                    Caption("Your " + deviceCount + " original device bookmarks are stored separately.")
                    AccountAction("Add " + deviceCount + " device bookmarks to QF",
                        enabled = !status.deleting) { action(account::importDeviceBookmarks) }
                }
                AccountAction("Remove synced bookmarks", enabled = !status.deleting) {
                    confirmation = "erase"
                }
                AccountAction("Disconnect from this device") { confirmation = "disconnect" }
            } else {
                Caption("Optional bookmark sync between Beautiful Quran on Android and the web.")
                Spacer(Modifier.height(12.dp))
                Caption("Saved verse references can reveal religious activity. Connecting sends " +
                    "your account identity and future bookmark changes to QF. QF stores the " +
                    "Beautiful Quran collection, and other connected apps can access it. " +
                    "Existing device bookmarks are added only when you choose to add them.")
                Spacer(Modifier.height(12.dp))
                Caption("Your notes, settings and listening position stay on this device. " +
                    "You can read without an account and export or remove synced bookmarks here. " +
                    "Manage app access or delete the full QF account through QF.")
                Spacer(Modifier.height(16.dp))
                ToggleRow("I am 18 or older", adult, { adult = it })
                ToggleRow("I agree to send my bookmark activity to QF", consent, { consent = it })
                AccountAction("Continue with Quran Foundation",
                    enabled = status.available && adult && consent && !status.busy) {
                    context.startActivity(Intent(context, QfSignInActivity::class.java))
                }
                if (!status.available) Caption("Account sync is awaiting approved public-client configuration.")
            }
            Spacer(Modifier.height(12.dp))
            Caption(status.message)
            AccountAction("Export bookmarks") { export.launch("beautiful-quran-bookmarks.json") }
            if (exportNote.isNotEmpty()) Caption(exportNote)
            Spacer(Modifier.height(20.dp))
            AccountAction("Beautiful Quran privacy") {
                open("https://sguergachi.github.io/Beautiful-Quran/privacy.html")
            }
            AccountAction("QF account terms") { open("https://quran.com/terms-and-conditions") }
            AccountAction("QF account privacy") { open("https://quran.com/privacy") }
            AccountAction("Manage your QF account") { open("https://quran.com") }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AccountAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.labelSmall,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else QuranTheme.ink.quiet,
        modifier = Modifier.fillMaxWidth().quietClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp))
}
