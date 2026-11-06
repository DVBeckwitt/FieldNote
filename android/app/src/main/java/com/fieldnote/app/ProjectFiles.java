package com.fieldnote.app;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.List;

/** External viewing intents only; the caller owns availability and permission error UI. */
public final class ProjectFiles {
    private ProjectFiles() { }

    /**
     * Opens the user-granted storage tree, which may be an ancestor of an imported project.
     * A standalone document grant does not identify a parent; never guess from opaque IDs.
     */
    public static Intent folderIntent(Uri project) {
        requireContentUri(project);
        if (!DocumentsContract.isTreeUri(project)) return null;
        String root = DocumentsContract.getTreeDocumentId(project);
        if (root.isEmpty()) throw new IllegalArgumentException("The project storage folder is invalid.");
        Uri tree = DocumentsContract.buildTreeDocumentUri(project.getAuthority(), root);
        Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree, root);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(directory, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // Browsing needs child-document access. Scope this temporary, read-only prefix
        // to the selected tree, not to the whole provider or the device's storage root.
        intent.setClipData(ClipData.newRawUri("Project storage folder", tree));
        return intent;
    }

    /** Opens only the selected ZIP, without granting access to sibling files or writes. */
    public static Intent zipIntent(Uri project) {
        requireContentUri(project);
        if (DocumentsContract.isTreeUri(project)) {
            String document = DocumentsContract.getDocumentId(project);
            if (document.isEmpty()) throw new IllegalArgumentException("The project ZIP location is invalid.");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(project, "application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("Project ZIP", project));
        return intent;
    }

    private static void requireContentUri(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || !uri.isHierarchical()
                || uri.getAuthority() == null || uri.getAuthority().isEmpty()
                || uri.getPathSegments().isEmpty()) {
            throw new IllegalArgumentException("Choose a project using Android's file picker first.");
        }
        List<String> path = uri.getPathSegments();
        if (("tree".equals(path.get(0)) && !(path.size() == 2
                || (path.size() == 4 && "document".equals(path.get(2)))))
                || ("document".equals(path.get(0)) && path.size() != 2)) {
            throw new IllegalArgumentException("The project document location is invalid.");
        }
    }
}
