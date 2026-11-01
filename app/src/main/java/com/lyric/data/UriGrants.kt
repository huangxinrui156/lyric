package com.lyric.data

import android.net.Uri
import android.provider.DocumentsContract

/** Tree document IDs are provider-defined: never infer ancestry from a path prefix. */
internal fun grantIsReferenced(grant: Uri, references: List<Uri>): Boolean = references.any { ref ->
    if (ref == grant) true
    else if (ref.authority != grant.authority) false
    else if (DocumentsContract.isTreeUri(grant) && grant.pathSegments.size == 2) {
        val tree = DocumentsContract.getTreeDocumentId(grant)
        if (DocumentsContract.isTreeUri(ref)) DocumentsContract.getTreeDocumentId(ref) == tree
        // A plain document URI has no tree provenance. Conservatively keep this provider's
        // tree grant; an opaque document ID cannot prove that it is outside the tree.
        else ref.scheme == "content"
    } else {
        val id = runCatching { DocumentsContract.getDocumentId(grant) }.getOrNull()
        id != null && id == runCatching { DocumentsContract.getDocumentId(ref) }.getOrNull()
    }
}
