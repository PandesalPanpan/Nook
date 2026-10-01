package app.nook.integration

import app.nook.NookButton
import app.nook.NookActionBlue

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import app.nook.FirebaseRuntime
import app.nook.R
import app.nook.data.*
import app.nook.data.Record
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val Inter = FontFamily(Font(R.font.inter))
private val Yellow = Color(0xffffce00)
private val Teal = Color(0xff3fae9b)

/** Optional account entry and the real account-scoped outbox; capture never waits on these controls. */
@Composable
fun AccountControls(repository: NookRepository, firebase: FirebaseRuntime, continueOffline: () -> Unit) {
    val context = LocalContext.current
    val actions = firebase.actions
    val actionState = actions?.state?.collectAsStateWithLifecycle()?.value ?: AccountActionState()
    val outbox by remember(repository) { repository.db.records().observeOutbox(repository.accountId) }.collectAsStateWithLifecycle(emptyList())
    val syncState = firebase.auth?.accounts?.state?.collectAsStateWithLifecycle()?.value
    val cloud = !repository.accountId.startsWith("local:")
    var showEmail by remember { mutableStateOf(false) }
    // Credentials are deliberately excluded from saved instance state, Room and preferences.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        AccountText(if (cloud) "Sync" else "Keep Nook yours", 30, bold = true)
        if (!cloud) {
            AccountText("Start offline. Sync only if you want it.", 13, secondary = true)
            Spacer(Modifier.height(24.dp))
            AccountCard(bottomPadding = 4) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccountMarker("4-92-imgEllipse1.svg", 42)
                    AccountText("Use Nook without an account", 15, bold = true, modifier = Modifier.padding(top = 0.dp))
                }
                Spacer(Modifier.height(10.dp))
                AccountText("Everything stays on this device. You can turn on sync later.", 12, secondary = true, lineHeight = 14f)
                AccountBadge("DEFAULT", Yellow, MaterialTheme.colorScheme.background)
            }
            Spacer(Modifier.height(32.dp))
            AccountText("Or sync across Android + web", 13, bold = true)
            Spacer(Modifier.height(14.dp))
            AccountButton("Continue with Google", primary = true, enabled = actions != null && !firebase.googleWebClientId.isNullOrBlank() && !actionState.busy) {
                actions?.google { GoogleSignIn.token(context, requireNotNull(firebase.googleWebClientId)) }
            }
            Spacer(Modifier.height(14.dp))
            AccountButton("Use email", enabled = actions != null && !actionState.busy) { showEmail = !showEmail }
            if (actions == null) {
                Spacer(Modifier.height(12.dp))
                AccountText("Sync is unavailable in this build. You can keep using Nook offline.", 12, secondary = true)
            } else if (firebase.googleWebClientId.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                AccountText("Google sign-in isn’t enabled. Use email to sync.", 12, secondary = true)
            }
            if (showEmail && actions != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
                    enabled = !actionState.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                    enabled = !actionState.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Spacer(Modifier.height(12.dp))
                AccountButton(if (register) "Create account" else "Sign in", primary = true,
                    enabled = !actionState.busy && email.isNotBlank() && password.length >= 6) {
                    actions.signIn(email, password, register)
                    password = ""
                }
                TextButton(onClick = { register = !register }, enabled = !actionState.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (register) "Already have an account? Sign in" else "New here? Create an account", fontFamily = Inter)
                }
            }
            Spacer(Modifier.height(32.dp))
            AccountCard(elevated = true, radius = 18, minHeight = 104) {
                AccountText("Turning sync on later is safe.", 13, bold = true)
                Spacer(Modifier.height(10.dp))
                AccountText("Your local Nook becomes the starting copy; it isn’t replaced by an empty cloud.", 11, secondary = true)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = continueOffline, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                AccountText("Continue offline", 12, bold = true, secondary = true)
            }
        } else {
            Spacer(Modifier.height(10.dp))
            AccountText(firebase.auth?.identity()?.email ?: "Account connected", 13, secondary = true)
            Spacer(Modifier.height(24.dp))
            AccountCard(minHeight = 140) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AccountMarker("2-940-imgEllipse1.svg", 42)
                    AccountText("Everything is saved locally first.", 16, bold = true)
                }
                Spacer(Modifier.height(10.dp))
                AccountText("Nook keeps working normally without internet. Sync catches up when you’re back.", 12, secondary = true)
                AccountBadge("LOCAL FIRST", Teal, MaterialTheme.colorScheme.background)
            }
            Spacer(Modifier.height(32.dp))
            key(repository.accountId) { AccountWorkerSyncStatus(repository.accountId, syncState) }
            Spacer(Modifier.height(12.dp))
            AccountText("${outbox.size} changes waiting to sync", 14, bold = true)
            Spacer(Modifier.height(12.dp))
            if (outbox.isEmpty()) AccountText("No changes waiting to sync.", 12, secondary = true)
            outbox.take(8).forEach { operation ->
                val record = remember(operation.json) { wireJson.decodeFromString<Record>(operation.json) }
                val title = record.data["title"]?.jsonPrimitive?.contentOrNull
                    ?: record.data["filename"]?.jsonPrimitive?.contentOrNull
                    ?: record.data["body"]?.jsonPrimitive?.contentOrNull?.take(60) ?: record.kind
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountMarker("2-940-imgEllipse2.svg", 32)
                    Column {
                        AccountText("${if (record.deleted) "Delete" else record.kind.replaceFirstChar { it.uppercase() }} · $title", 13, bold = true)
                        AccountText(if (operation.attempts > 0) "Waiting to retry" else "Waiting", 10, secondary = true)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (outbox.size > 8) AccountText("${outbox.size - 8} more changes queued", 12, secondary = true)
            Spacer(Modifier.height(20.dp))
            AccountCard(elevated = true, radius = 18, minHeight = 84) {
                AccountText("No connection?", 13, bold = true)
                Spacer(Modifier.height(10.dp))
                AccountText("Keep working. There is nothing you need to fix.", 11, secondary = true)
            }
            Spacer(Modifier.height(16.dp))
            AccountButton("Sync now", primary = true, enabled = actions != null && !actionState.busy && syncState?.syncing != true) { actions?.sync() }
            Spacer(Modifier.height(14.dp))
            AccountButton("Disconnect", enabled = actions != null && !actionState.busy) { actions?.signOut() }
            AccountText("Queued changes stay in this account and resume when you sign back in.", 11, secondary = true, modifier = Modifier.padding(top = 12.dp))
        }
        if (actionState.busy) AccountText("Working…", 12, secondary = true, modifier = Modifier.padding(top = 12.dp))
        actionState.error?.let { AccountText(it, 12, modifier = Modifier.padding(top = 12.dp)) }
        actionState.message?.let { AccountText(it, 12, secondary = true, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@Composable private fun AccountText(text: String, size: Int, modifier: Modifier = Modifier, bold: Boolean = false, secondary: Boolean = false, lineHeight: Float = size * 1.4f) {
    Text(text, modifier, fontFamily = Inter, fontSize = size.sp, lineHeight = lineHeight.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}
@Composable private fun AccountCard(elevated: Boolean = false, radius: Int = 20, minHeight: Int = 128, bottomPadding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = minHeight.dp).background(if (elevated) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        RoundedCornerShape(radius.dp)).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomPadding.dp), content = content)
}
@Composable private fun AccountBadge(text: String, background: Color, color: Color) {
    Box(Modifier.background(background, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontFamily = Inter, fontSize = 12.sp, lineHeight = 20.sp, color = color)
    }
}
@Composable private fun AccountButton(text: String, primary: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
    NookButton(click, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) NookActionBlue else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if(primary) Color.White else MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
@Composable private fun AccountMarker(asset: String, size: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val bitmap = remember(context, asset, density) {
        val svg = com.caverock.androidsvg.SVG.getFromAsset(context.assets, "figma/$asset")
        createBitmap((svg.documentWidth * density).toInt(), (svg.documentHeight * density).toInt()).also {
            val canvas = android.graphics.Canvas(it); canvas.scale(density, density); canvas.drawPicture(svg.renderToPicture())
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(size.dp))
}

@Composable internal fun AccountSyncStatus(syncState: AccountState?, background: BackgroundSyncStatus = BackgroundSyncStatus()) {
    AccountText(syncStatusText(syncState, background), 12, secondary = true,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    syncFilesText(syncState, background)?.let { text ->
        AccountText(text, 12, secondary = true, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable private fun AccountWorkerSyncStatus(accountId: String, manual: AccountState?) {
    val context = LocalContext.current
    val status = remember(context, accountId) {
        observeBackgroundSync(context, accountId)
    }.collectAsStateWithLifecycle(BackgroundSyncStatus()).value
    AccountSyncStatus(manual, status)
}
