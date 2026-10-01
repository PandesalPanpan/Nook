package app.nook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContextTest {
    private val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),NookDatabase::class.java).build()
    private val repo=NookRepository(db,"local:context","client")
    @After fun close() { db.close() }
    @Test fun parentOnlySubtasksResolveNestedContextWithoutAccountsDeletionsOrCycles() = runTest {
        val area=repo.create("area",wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
        val project=repo.create("project",wireJson.encodeToJsonElement(Project("Garden",areaId=area.id)) as JsonObject)
        val parent=repo.create("task",wireJson.encodeToJsonElement(Task("Plant",projectId=project.id)) as JsonObject)
        val child=repo.create("task",wireJson.encodeToJsonElement(Task("Water",parentTaskId=parent.id)) as JsonObject)
        val leaf=repo.create("task",wireJson.encodeToJsonElement(Task("Check soil",parentTaskId=child.id)) as JsonObject)
        val records=listOf(project,parent,child,leaf)
        assertEquals(area.id,effectiveAreaId(leaf,records))
        assertEquals(project.id,projectResolver(records,repo.accountId)(leaf))
        assertEquals(listOf(leaf.id),repo.search("project:Garden soil").map { it.id })
        assertNull(projectResolver(listOf(project,parent.copy(deleted=true),child),repo.accountId)(leaf))
        assertNull(projectResolver(listOf(project,parent.copy(accountId="bob"),child),repo.accountId)(leaf))
        assertNull(projectResolver(listOf(parent.copy(data=wireJson.encodeToJsonElement(Task("Cycle",parentTaskId=child.id)) as JsonObject),child),repo.accountId)(child))
        assertEquals("explicit",projectResolver(records,repo.accountId)(leaf.copy(data=JsonObject(leaf.data+("projectId" to JsonPrimitive("explicit"))))))
        assertEquals(listOf(leaf.id),repo.search("area:Health soil").map { it.id })
        assertNull(effectiveAreaId(leaf,listOf(project,parent.copy(accountId="bob"),child)))
        assertNull(effectiveAreaId(leaf,listOf(project,parent.copy(deleted=true),child)))
        assertNull(effectiveAreaId(child.copy(data=JsonObject(child.data+("projectId" to JsonPrimitive("missing")))),records))
        assertNull(effectiveAreaId(child,listOf(parent.copy(data=wireJson.encodeToJsonElement(Task("Cycle",parentTaskId=child.id)) as JsonObject),child)))
        assertEquals("explicit",effectiveAreaId(leaf.copy(data=JsonObject(leaf.data+("areaId" to JsonPrimitive("explicit")))),records))
    }
    @Test fun resourceContextRestoresAndAreaSearchUsesProjectInheritanceWithExplicitOverrides() = runTest {
        val area=repo.create("area",wireJson.encodeToJsonElement(Area("Health")) as JsonObject)
        val other=repo.create("area",wireJson.encodeToJsonElement(Area("Work")) as JsonObject)
        val project=repo.create("project",wireJson.encodeToJsonElement(Project("Garden",areaId=area.id)) as JsonObject)
        val resource=repo.create("resource",wireJson.encodeToJsonElement(Resource("Seed guide",projectId=project.id)) as JsonObject)
        validate(resource.copy(data=wireJson.encodeToJsonElement(Resource("Legacy")) as JsonObject))
        assertNull(effectiveAreaId(resource,listOf(project.copy(accountId="bob"))))
        assertEquals(listOf(resource.id),repo.search("area:Health Seed").map { it.id })
        repo.update(resource.id) { it.copy(data=JsonObject(it.data+("areaId" to JsonPrimitive(other.id)))) }
        assertTrue(repo.search("area:Health Seed").isEmpty())
        assertEquals(listOf(resource.id),repo.search("area:Work Seed").map { it.id })
        val restored=NookRepository(db,"restored","client");restored.restoreBackup(repo.exportBackup())
        assertEquals(other.id,restored.get(resource.id)!!.data["areaId"]!!.jsonPrimitive.content)
        try { validate(resource.copy(data=JsonObject(resource.data+("areaId" to JsonPrimitive("unsafe/path")))));fail("Invalid reference") } catch(_:IllegalArgumentException) { }
    }
}
