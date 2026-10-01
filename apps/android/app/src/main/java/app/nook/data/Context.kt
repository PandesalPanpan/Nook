package app.nook.data

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun projectResolver(records: List<Record>, accountId: String): (Record) -> String? {
    val index = records.filter { !it.deleted && it.accountId == accountId }.associateBy { it.id }
    return fun(record: Record): String? {
        if(record.accountId != accountId) return null
        val visited = mutableSetOf<String>()
        var current: Record? = record
        while(current != null && !current.deleted && visited.add(current.id)) {
            current.data["projectId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
            current = if(current.kind == "task") current.data["parentTaskId"]?.jsonPrimitive?.contentOrNull?.let(index::get)?.takeIf { it.kind == "task" } else null
        }
        return null
    }
}

fun effectiveAreaId(record: Record, records: List<Record>): String? {
    return areaResolver(records,record.accountId)(record)
}
fun areaResolver(records: List<Record>, accountId: String): (Record) -> String? {
    val index=records.filter { !it.deleted && it.accountId == accountId }.associateBy { it.id }
    return fun(record: Record): String? {
    if(record.accountId != accountId)return null
    val visited=mutableSetOf<String>()
    var current: Record?=record
    while(current != null && !current.deleted && visited.add(current.id)) {
        current.data["areaId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { return it }
        current.data["projectId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { projectId ->
            return index[projectId]?.takeIf { it.kind == "project" }?.data?.get("areaId")?.jsonPrimitive?.contentOrNull
        }
        current=if(current.kind == "task") current.data["parentTaskId"]?.jsonPrimitive?.contentOrNull?.let(index::get)?.takeIf { it.kind == "task" } else null
    }
    return null
    }
}
