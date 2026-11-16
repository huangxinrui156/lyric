package com.lyric

import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** A real SAF DocumentsProvider, only in the instrumentation APK; sources are original fixtures. */
class EpubDocumentsProvider : DocumentsProvider() {
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
    )).apply {
        newRow().add(Root.COLUMN_ROOT_ID, "epubs").add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "Lyric EPUB fixtures").add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_MIME_TYPES, "application/epub+zip\napplication/octet-stream")
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)).apply {
            val parts = documentId.split('|')
            newRow().add(Document.COLUMN_DOCUMENT_ID, documentId)
                .add(Document.COLUMN_DISPLAY_NAME, if (parts.lastOrNull() == "bare") "阅读文档" else parts[0])
                .add(Document.COLUMN_MIME_TYPE, if (parts.getOrNull(1) == "octet") "application/octet-stream" else "application/epub+zip")
                .add(Document.COLUMN_FLAGS, 0)
        }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
        queryDocument("epub3-basic.epub|epub|named", projection)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r")
        val name = documentId.substringBefore('|')
        require(name in listOf("epub2-root.epub", "epub2-nested.epub", "epub3-nested.epub", "epub3-literal-percent.epub", "epub3-basic.epub"))
        val ctx = context!!
        val file = File(ctx.cacheDir, name)
        if (!file.exists()) ctx.assets.open("epub/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
