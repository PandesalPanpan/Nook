package app.nook.data

fun currentSettings(records: List<Record>): Record? = records.filter { it.kind == "settings" && !it.deleted && !it.archived }
    .sortedWith(compareByDescending<Record> { it.updatedAt }.thenByDescending { it.clientId }.thenBy { it.id }).firstOrNull()
