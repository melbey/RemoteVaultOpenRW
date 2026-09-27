package com.remotevault.openrw;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal bridge between Termux/Obsidian and Android document editors.
 *
 * The app never copies a file. The user grants it persistent read/write access
 * to the Android "Obsidian Remote Cache" directory through the Storage Access
 * Framework. When Termux starts this Activity with --es path <cache-file>, the
 * Activity resolves that file through the granted document tree, then sends the
 * resulting content:// URI to the chosen Android app with read + write grants.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_TREE = 1001;
    private static final String PREFS = "open_rw_prefs";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String EXTRA_PATH = "path";
    private static final String CACHE_MARKER = "Obsidian Remote Cache";

    private String pendingPath;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingPath = extractIncomingPath(getIntent());
        buildUi();
        updateStatus();

        if (pendingPath != null && getSavedTreeUri() != null) {
            openPendingFile();
        } else if (pendingPath != null) {
            Toast.makeText(this,
                    "First use: select the Obsidian Remote Cache folder once.",
                    Toast.LENGTH_LONG).show();
            chooseCacheFolder();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingPath = extractIncomingPath(intent);
        updateStatus();
        if (pendingPath != null) {
            if (getSavedTreeUri() == null) {
                chooseCacheFolder();
            } else {
                openPendingFile();
            }
        }
    }

    private void buildUi() {
        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Remote Vault Open RW");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView description = new TextView(this);
        description.setText(
                "One-time setup: grant this helper access to Documents → Obsidian Remote Cache.\n\n" +
                "After that, Obsidian can open cached PDFs and Office files in Android apps with read/write access. " +
                "The helper does not copy or sync files; your Remote Working Cache plugin remains responsible for syncing them back to the remote vault.");
        description.setTextSize(16f);
        description.setPadding(0, dp(20), 0, dp(20));
        root.addView(description, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setPadding(0, 0, 0, dp(18));
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button chooseButton = new Button(this);
        chooseButton.setText("Choose / change cache folder");
        chooseButton.setOnClickListener(v -> chooseCacheFolder());
        root.addView(chooseButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button clearButton = new Button(this);
        clearButton.setText("Forget folder permission");
        clearButton.setOnClickListener(v -> clearFolderPermission());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.topMargin = dp(12);
        root.addView(clearButton, clearParams);

        setContentView(root);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private String extractIncomingPath(Intent intent) {
        if (intent == null) return null;

        String fromExtra = intent.getStringExtra(EXTRA_PATH);
        if (fromExtra != null && !fromExtra.trim().isEmpty()) {
            return fromExtra.trim();
        }

        Uri data = intent.getData();
        if (data == null) return null;

        if ("file".equalsIgnoreCase(data.getScheme())) {
            return data.getPath();
        }

        if ("content".equalsIgnoreCase(data.getScheme())) {
            try (Cursor cursor = getContentResolver().query(
                    data,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null)) {

                if (cursor != null && cursor.moveToFirst()) {
                    int column =
                            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);

                    if (column >= 0) {
                        String name = cursor.getString(column);

                        if (name != null && !name.trim().isEmpty()) {
                            return name.trim();
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            String last = data.getLastPathSegment();

            if (last != null && !last.trim().isEmpty()) {
                return last.trim();
            }
        }

        return null;
    }

    private void updateStatus() {
        if (statusView == null) return;
        Uri tree = getSavedTreeUri();
        StringBuilder text = new StringBuilder();
        text.append(tree == null ? "Cache folder: not configured" : "Cache folder permission: configured");
        if (pendingPath != null) {
            text.append("\nPending file: ").append(new File(pendingPath).getName());
        }
        statusView.setText(text.toString());
    }

    private Uri getSavedTreeUri() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_TREE_URI, null);
        if (value == null || value.isEmpty()) return null;
        return Uri.parse(value);
    }

    private void chooseCacheFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri treeUri = data.getData();
        int granted = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            getContentResolver().takePersistableUriPermission(treeUri, granted);
        } catch (SecurityException e) {
            Toast.makeText(this,
                    "Android did not grant persistent read/write access to that folder.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_TREE_URI, treeUri.toString())
                .apply();

        updateStatus();
        Toast.makeText(this, "Cache folder permission saved.", Toast.LENGTH_SHORT).show();

        if (pendingPath != null) openPendingFile();
    }

    private void clearFolderPermission() {
        Uri tree = getSavedTreeUri();
        if (tree != null) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_TREE_URI).apply();
        updateStatus();
        Toast.makeText(this, "Folder permission cleared.", Toast.LENGTH_SHORT).show();
    }

    private void openPendingFile() {
        Uri tree = getSavedTreeUri();

        if (tree == null || pendingPath == null) {
            return;
        }

        String fileName = new File(pendingPath).getName();

        if (fileName == null || fileName.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "Could not determine cached filename.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            String treeId =
                    DocumentsContract.getTreeDocumentId(tree);

            /*
             * Example tree:
             *
             * primary:Documents/Obsidian Remote Cache
             *
             * Instead of querying/enumerating the folder, construct
             * the child document ID directly:
             *
             * primary:Documents/Obsidian Remote Cache/<filename>
             *
             * Our persisted tree permission includes descendants.
             */
            String childId =
                    treeId + "/" + fileName;

            Uri childDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            childId
                    );

            String mime = mimeForName(fileName);

            /*
             * First try immediately. If Android already knows this
             * document in MediaStore, this is the fast path.
             */
            try {
                Uri mediaUri =
                        MediaStore.getMediaUri(
                                this,
                                childDocumentUri
                        );

                if (mediaUri != null) {
                    launchMediaUri(mediaUri, mime);
                    return;
                }
            } catch (Throwable ignored) {
            }

            if (!treeId.startsWith("primary:")) {
                Toast.makeText(
                        this,
                        "Cache folder is not on primary Android storage.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            String relative =
                    treeId.substring("primary:".length());

            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }

            File physicalFile =
                    new File(
                            Environment.getExternalStorageDirectory(),
                            relative + "/" + fileName
                    );

            Toast.makeText(
                    this,
                    "Preparing new working copy…",
                    Toast.LENGTH_SHORT
            ).show();

            /*
             * Register newly-created Termux files with MediaStore.
             *
             * IMPORTANT:
             * We do NOT use scannedUri directly. That was the cause
             * of the SecurityException in v1.0.6.
             *
             * After scanning, convert our SAF-authorized document URI
             * to its MediaStore equivalent instead.
             */
            MediaScannerConnection.scanFile(
                    this,
                    new String[]{physicalFile.getAbsolutePath()},
                    new String[]{mime},
                    (path, scannedUri) ->
                            runOnUiThread(() -> {
                                try {
                                    Uri mediaUri =
                                            MediaStore.getMediaUri(
                                                    MainActivity.this,
                                                    childDocumentUri
                                            );

                                    if (mediaUri == null) {
                                        Toast.makeText(
                                                MainActivity.this,
                                                "File was scanned, but Android could not map it to MediaStore.",
                                                Toast.LENGTH_LONG
                                        ).show();
                                        return;
                                    }

                                    launchMediaUri(
                                            mediaUri,
                                            mime
                                    );

                                } catch (Throwable t) {
                                    showFatal(
                                            "SAF → MEDIASTORE",
                                            t
                                    );
                                }
                            })
            );

        } catch (Throwable t) {
            showFatal(
                    "OPEN WORKING COPY",
                    t
            );
        }
    }

    private void launchMediaUri(Uri mediaUri, String mime) {
        /*
         * Match the successful Samsung My Files handoff
         * captured with Intent Probe:
         *
         * ACTION_VIEW
         * application/pdf
         * content://media/external/file/<id>
         * READ grant only
         * no ClipData
         */
        Intent openIntent =
                new Intent(Intent.ACTION_VIEW);

        openIntent.setDataAndType(
                mediaUri,
                mime
        );

        openIntent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        try {
            Intent chooser =
                    Intent.createChooser(
                            openIntent,
                            "Open working copy"
                    );

            startActivity(chooser);
            finish();

        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                    this,
                    "No Android app can open this file type.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    /**
     * Current Remote Working Cache stores files flat. This also supports a future
     * mirrored directory cache: if the incoming path contains the cache folder
     * name, everything after it is traversed through DocumentsContract.
     */
    private List<String> relativeSegmentsForPath(String fullPath) {
        String normalized = fullPath.replace('\\', '/');
        String[] parts = normalized.split("/");
        List<String> result = new ArrayList<>();

        boolean afterMarker = false;
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            if (afterMarker) result.add(part);
            if (CACHE_MARKER.equals(part)) afterMarker = true;
        }

        if (!result.isEmpty()) return result;

        String name = new File(fullPath).getName();
        if (name != null && !name.isEmpty()) result.add(name);
        return result;
    }

    private Uri resolveDocument(Uri treeUri, List<String> segments) {
        String currentDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri currentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocumentId);

        for (int i = 0; i < segments.size(); i++) {
            String wantedName = segments.get(i);
            boolean mustBeDirectory = i < segments.size() - 1;
            Child child = findChild(treeUri, currentDocumentId, wantedName, mustBeDirectory);
            if (child == null) return null;
            currentDocumentId = child.documentId;
            currentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocumentId);
        }

        return currentDocumentUri;
    }

    private Child findChild(Uri treeUri, String parentDocumentId, String wantedName, boolean mustBeDirectory) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) return null;

            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if (!wantedName.equals(name)) continue;

                String mime = cursor.getString(mimeColumn);
                boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (mustBeDirectory && !isDirectory) continue;

                return new Child(cursor.getString(idColumn), mime);
            }
        }
        return null;
    }

    private void openDocumentReadWrite(Uri documentUri, String fileName) {
        ContentResolver resolver = getContentResolver();
        String mime = resolver.getType(documentUri);
        if (mime == null || mime.trim().isEmpty()) mime = mimeForName(fileName);

        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(documentUri, mime);
        openIntent.setClipData(ClipData.newUri(resolver, fileName, documentUri));
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        Intent chooser = Intent.createChooser(openIntent, "Open working copy");
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            startActivity(chooser);
            finish();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No Android app can open this file type.", Toast.LENGTH_LONG).show();
        }
    }


    private void showFatal(String stage, Throwable t) {
        StringBuilder msg = new StringBuilder();

        msg.append(stage)
           .append("\n")
           .append(t.getClass().getName())
           .append("\n")
           .append(t.getMessage());

        Toast.makeText(
                this,
                msg.toString(),
                Toast.LENGTH_LONG
        ).show();

        android.util.Log.e(
                "RemoteVaultOpenRW",
                stage,
                t
        );
    }

    private String mimeForName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private static class Child {
        final String documentId;
        final String mimeType;

        Child(String documentId, String mimeType) {
            this.documentId = documentId;
            this.mimeType = mimeType;
        }
    }
}
