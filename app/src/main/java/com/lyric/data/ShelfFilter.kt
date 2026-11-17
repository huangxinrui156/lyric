package com.lyric.data

enum class ReadingStatusFilter(val label: String) {
    UNREAD("未读"),
    READING("在读"),
    FINISHED("读完"),
}

enum class ImportedWithin(val label: String, val days: Int?) {
    ANY("全部", null),
    DAYS_7("7 天内", 7),
    DAYS_30("30 天内", 30),
}

data class ShelfFilter(
    val statuses: Set<ReadingStatusFilter> = emptySet(),
    val imported: ImportedWithin = ImportedWithin.ANY,
) {
    val activeCount: Int get() = statuses.size + if (imported != ImportedWithin.ANY) 1 else 0
    val isActive: Boolean get() = activeCount > 0

    fun chips(): List<Pair<String, ShelfFilter>> = buildList {
        ReadingStatusFilter.entries.forEach { status ->
            if (status in statuses) add(status.label to copy(statuses = statuses - status))
        }
        if (imported != ImportedWithin.ANY) add(imported.label to copy(imported = ImportedWithin.ANY))
    }

    fun encode(): String = statuses.sortedBy { it.ordinal }.joinToString(",") { it.name } + "|" + imported.name

    companion object {
        fun decode(raw: String?): ShelfFilter {
            if (raw.isNullOrBlank()) return ShelfFilter()
            val parts = raw.split("|", limit = 2)
            val statuses = parts[0].split(',')
                .mapNotNull { token -> ReadingStatusFilter.entries.firstOrNull { it.name == token } }
                .toSet()
            val imported = parts.getOrNull(1)?.let { name -> ImportedWithin.entries.firstOrNull { it.name == name } }
                ?: ImportedWithin.ANY
            return ShelfFilter(statuses, imported)
        }
    }
}

internal const val DAY_MS = 86_400_000L

fun ShelfBook.readingStatus(): ReadingStatusFilter = when {
    percent >= .99f -> ReadingStatusFilter.FINISHED
    lastRead > 0L -> ReadingStatusFilter.READING
    else -> ReadingStatusFilter.UNREAD
}

fun applyShelfFilter(
    books: List<ShelfBook>,
    filter: ShelfFilter,
    now: Long = System.currentTimeMillis(),
): List<ShelfBook> {
    if (!filter.isActive) return books
    return books.filter { book ->
        val statusOk = filter.statuses.isEmpty() || book.readingStatus() in filter.statuses
        val days = filter.imported.days
        val importedOk = days == null || now - book.addedAt <= days * DAY_MS
        statusOk && importedOk
    }
}
