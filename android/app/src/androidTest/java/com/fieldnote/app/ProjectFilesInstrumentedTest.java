package com.fieldnote.app;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Intent boundaries only: no external app is launched and no URI permission is acquired. */
@RunWith(AndroidJUnit4.class)
public final class ProjectFilesInstrumentedTest {
    @Test public void folderUsesGrantedTreeWithoutGuessingOpaqueDocumentParent() {
        String rootId = "provider folder:/A B?#%";
        Uri tree = DocumentsContract.buildTreeDocumentUri("example.documents", rootId);
        Uri project = DocumentsContract.buildDocumentUriUsingTree(tree, "unrelated-opaque-id:735/zip?");

        Intent intent = ProjectFiles.folderIntent(project);

        assertNotNull(intent);
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, intent.getType());
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), intent.getData());
        assertEquals(rootId, DocumentsContract.getDocumentId(intent.getData()));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                intent.getFlags());
        assertEquals(1, intent.getClipData().getItemCount());
        assertEquals("Only the selected storage subtree is granted for browsing", tree,
                intent.getClipData().getItemAt(0).getUri());
        assertNoOtherTargets(intent);
    }

    @Test public void treeZipIntentKeepsExactOriginalAndCannotGrantSiblingAccess() {
        Uri tree = DocumentsContract.buildTreeDocumentUri("example.documents", "root:notes");
        Uri project = DocumentsContract.buildDocumentUriUsingTree(tree, "root:notes/Project name.zip");

        assertExactZipIntent(project, ProjectFiles.zipIntent(project));
    }

    @Test public void importedDocumentCanOpenZipWithoutInventingAStorageFolder() {
        Uri imported = DocumentsContract.buildDocumentUri("cloud.example.documents", "opaque:uuid/1234");

        assertNull("A document ID is not a filesystem path or proof of a parent grant",
                ProjectFiles.folderIntent(imported));
        assertExactZipIntent(imported, ProjectFiles.zipIntent(imported));
    }

    @Test public void treeOnlyGrantCanOpenFolderButCannotMasqueradeAsAZip() {
        Uri tree = DocumentsContract.buildTreeDocumentUri("example.documents", "root:notes");

        assertNotNull(ProjectFiles.folderIntent(tree));
        assertThrows(IllegalArgumentException.class, () -> ProjectFiles.zipIntent(tree));
    }

    @Test public void invalidLocationsCannotEscapeIntoWebOrFilesystemIntents() {
        Uri[] invalid = {null, Uri.parse("file:///sdcard/Notes.zip"), Uri.parse("https://example.com/Notes.zip"),
                Uri.parse("content:opaque"), Uri.parse("content:///document/123"), Uri.parse("content://example.documents"),
                Uri.parse("content://example.documents/tree"), Uri.parse("content://example.documents/document"),
                Uri.parse("content://example.documents/tree/root/document"),
                Uri.parse("content://example.documents/document/root/children")};
        for (Uri uri : invalid) {
            assertThrows(IllegalArgumentException.class, () -> ProjectFiles.folderIntent(uri));
            assertThrows(IllegalArgumentException.class, () -> ProjectFiles.zipIntent(uri));
        }
    }

    private static void assertExactZipIntent(Uri expected, Intent intent) {
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertEquals("application/zip", intent.getType());
        assertEquals(expected, intent.getData());
        assertEquals("The ZIP viewer receives no write, persistable, or prefix grant",
                Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.getFlags());
        assertEquals(1, intent.getClipData().getItemCount());
        assertEquals(expected, intent.getClipData().getItemAt(0).getUri());
        assertNoOtherTargets(intent);
    }

    private static void assertNoOtherTargets(Intent intent) {
        assertNull(intent.getComponent());
        assertNull(intent.getPackage());
        assertNull(intent.getSelector());
        assertNull("No picker initial location or unrelated grants are added", intent.getExtras());
        assertNull(intent.getCategories());
    }
}
