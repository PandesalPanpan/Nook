package app.nook

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.nook.data.*
import app.nook.integration.CaptureActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real OS chooser/camera activity results, without substituting ActivityResult callbacks. */
class SystemOriginalInputTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun find(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(predicate(node))return node
            for(index in 0 until node.childCount)node.getChild(index)?.let{child->visit(child)?.let{return it}}
            return null
        }
        return automation.rootInActiveWindow?.let(::visit)
    }
    private fun tap(node:AccessibilityNodeInfo) {
        var target:AccessibilityNodeInfo?=node
        while(target!=null) {
            if(target.isClickable){assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));return}
            target=target.parent
        }
        error("System control has no clickable ancestor: ${node.text} / ${node.contentDescription}")
    }
    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while(current != null) { if(current.isClickable) return true; current = current.parent }
        return false
    }
    private fun systemTap(predicate:(AccessibilityNodeInfo)->Boolean) {
        compose.waitUntil(10000){find { predicate(it) && hasClickableAncestor(it) } != null}
        tap(requireNotNull(find { predicate(it) && hasClickableAncestor(it) }))
    }
    private fun dump(name:String) {
        val rows=mutableListOf<String>()
        fun visit(node:AccessibilityNodeInfo){rows+="${node.viewIdResourceName} | ${node.text} | ${node.contentDescription} | clickable=${node.isClickable}";for(index in 0 until node.childCount)node.getChild(index)?.let(::visit)}
        automation.rootInActiveWindow?.let(::visit)
        File(context.getExternalFilesDir(null),name).writeText(rows.joinToString("\n"))
    }
    private fun offline() {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        assertTrue(manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true)
    }
    @Test fun systemDocumentSelectionAttachesExactOriginalAndReopensOffline():Unit=runBlocking {
        offline()
        val repository=AppGraph.activeRepository(context)
        val filename="nook-picker-${UUID.randomUUID()}.txt"
        val bytes="Original UTF-8 attachment\nLocal-only proof: café\n".toByteArray(Charsets.UTF_8)
        val metadata=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,filename);put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
        }
        val uri=requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,metadata))
        context.contentResolver.openOutputStream(uri)!!.use{it.write(bytes)}
        val note=repository.create("note",wireJson.encodeToJsonElement(Note("System picker proof","Saved note body")) as JsonObject)
        context.getSharedPreferences("nook-local",Context.MODE_PRIVATE).edit().putBoolean("onboarded",true).commit()
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("page","Resources").putExtra("recordId",note.id)).use{activity->
                compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Attach").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("Attach").performScrollTo().performClick()
                compose.waitUntil(10000){automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
                if(find{it.text?.toString()==filename}==null) {
                    find{it.contentDescription?.toString() in listOf("Show roots","Open navigation drawer")}?.let(::tap)
                    systemTap{it.text?.toString()=="Downloads"}
                }
                systemTap{it.text?.toString()==filename}
                // Some DocumentsUI versions leave a selection confirmation after a single file tap.
                find{it.text?.toString() in listOf("Select","SELECT","Open","OPEN") && it.isClickable}?.let(::tap)
                compose.waitUntil(10000){runBlocking{repository.db.records().byKinds(repository.accountId,listOf("attachment")).map{it.decode()}.any{!it.deleted&&it.data["ownerId"]?.jsonPrimitive?.content==note.id}}}
                val attachment=repository.db.records().byKinds(repository.accountId,listOf("attachment")).map{it.decode()}.single{!it.deleted&&it.data["ownerId"]?.jsonPrimitive?.content==note.id}
                assertEquals(filename,attachment.data["filename"]!!.jsonPrimitive.content)
                assertEquals("text/plain",attachment.data["mimeType"]!!.jsonPrimitive.content)
                assertArrayEquals(bytes,repository.db.records().original(repository.accountId,attachment.id)!!.bytes)
                assertNotNull(repository.db.records().operation(repository.accountId,attachment.id))
                compose.waitUntil(10000){compose.onAllNodesWithText("Save $filename").fetchSemanticsNodes().isNotEmpty()}
                activity.recreate()
                compose.waitUntil(10000){compose.onAllNodesWithText("Save $filename").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Save $filename").performScrollTo().assertIsDisplayed()
                assertArrayEquals(bytes,repository.db.records().original(repository.accountId,attachment.id)!!.bytes)
            }
        } catch(error:Throwable){dump("native-system-picker-failure.txt");throw error}
        finally {context.contentResolver.delete(uri,null,null);repository.delete(note.id)}
    }
    @Test fun registeredPhotoShortcutTakesCameraOriginalWithoutCaptionOffline():Unit=runBlocking {
        offline()
        val repository=AppGraph.activeRepository(context)
        val shortcut=context.getSystemService(ShortcutManager::class.java).manifestShortcuts.single{it.id=="photo"}
        val before=repository.db.records().byKinds(repository.accountId,listOf("attachment")).map{it.id}.toSet()
        var captureId:String?=null
        try {
            ActivityScenario.launch<CaptureActivity>(requireNotNull(shortcut.intent)).use {
                compose.waitUntil(15000){automation.rootInActiveWindow?.packageName?.toString()=="com.android.camera2"}
                dump("native-system-camera-before.txt")
                systemTap{it.viewIdResourceName?.endsWith("/shutter_button")==true || it.contentDescription?.toString() in listOf("Shutter","Capture photo","Take photo")}
                systemTap{it.viewIdResourceName?.endsWith("/done_button")==true || it.contentDescription?.toString() in listOf("Done","Review done")}
                compose.waitUntil(10000){compose.onAllNodesWithText("1 original file attached").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Save",substring=false).assertIsEnabled().performClick()
                compose.waitUntil(10000){runBlocking{repository.db.records().byKinds(repository.accountId,listOf("attachment")).any{it.id !in before}}}
                val attachment=repository.db.records().byKinds(repository.accountId,listOf("attachment")).map{it.decode()}.single{it.id !in before}
                captureId=attachment.data["ownerId"]!!.jsonPrimitive.content
                val capture=repository.get(captureId!!)!!
                assertEquals("",capture.data["body"]!!.jsonPrimitive.content)
                assertEquals("image",capture.data["captureType"]!!.jsonPrimitive.content)
                val original=repository.db.records().original(repository.accountId,attachment.id)!!.bytes
                val filename=attachment.data["filename"]!!.jsonPrimitive.content
                assertArrayEquals(File(context.cacheDir,"camera/$filename").readBytes(),original)
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(original,0,original.size,bounds)
                assertTrue(bounds.outWidth>0 && bounds.outHeight>0)
                assertNotNull(repository.db.records().operation(repository.accountId,captureId!!))
                File(context.getExternalFilesDir(null),"native-system-camera-result.json").writeText("{\"bytes\":${original.size},\"width\":${bounds.outWidth},\"height\":${bounds.outHeight},\"captionRequired\":false}")
            }
        } catch(error:Throwable){dump("native-system-camera-failure.txt");throw error}
        finally {captureId?.let{repository.delete(it)}}
    }
}
