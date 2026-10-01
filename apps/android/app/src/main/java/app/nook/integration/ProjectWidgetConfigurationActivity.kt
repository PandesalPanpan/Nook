package app.nook.integration

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.nook.AppGraph
import app.nook.NookTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

class ProjectWidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setContent { NookTheme {
            val repository by produceState<app.nook.data.NookRepository?>(null) { value = AppGraph.activeRepository(applicationContext) }
            val selectedRepository = repository
            if (selectedRepository == null) { Text("Opening your Nook…", modifier = Modifier.padding(24.dp)); return@NookTheme }
            val records by selectedRepository.observe("project").collectAsState(initial = emptyList())
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Choose a project", style = MaterialTheme.typography.headlineSmall)
                    Text("Show its progress and next action on your home screen.")
                    records.filter { !it.archived }.forEach { project -> OutlinedButton(onClick = {
                        if (AppGraph.repository(applicationContext).accountId != selectedRepository.accountId) { finish(); return@OutlinedButton }
                        getSharedPreferences("nook-widgets", MODE_PRIVATE).edit().putString("${selectedRepository.accountId}:$id", project.id).commit()
                        lifecycleScope.launch {
                            ProjectWidget().update(applicationContext, GlanceAppWidgetManager(applicationContext).getGlanceIdBy(id))
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(project.data["title"]!!.jsonPrimitive.content) } }
                    if(records.none { !it.archived }) Text("Create a project in Nook, then choose it here.")
                    TextButton(onClick = ::finish) { Text("Cancel") }
                }
            }
        } }
    }
}
