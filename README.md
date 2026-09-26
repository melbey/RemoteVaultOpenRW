# Remote Vault Open RW

A tiny Android helper for the Termux + Linux Obsidian **Remote Working Cache** workflow.

## Why this exists

`termux-open` can expose a cached file to Android apps, but its current file-sharing path is read-oriented. Some editors therefore open a PDF correctly but force **Save As** instead of saving back to the same cached file.

This helper uses Android's **Storage Access Framework (SAF)** instead:

1. You select `Documents/Obsidian Remote Cache` once.
2. Android grants this app persistent read/write permission to that folder.
3. Termux passes the cache file path to this app.
4. The app resolves the same file as a document `content://` URI.
5. It opens the Android chooser with **read + write URI grants**.
6. Samsung Notes / another editor can edit the same cached file in place.
7. The existing Obsidian Remote Working Cache plugin sees the file modification and syncs it back to the remote vault.

The helper does **not** copy, upload, download, or sync files itself.

## Build

Open this folder as an Android Studio project. The project uses:

- Java
- no third-party libraries
- compile SDK 35
- min SDK 26
- application ID `com.remotevault.openrw`

Build the debug APK with **Build → Build APK(s)**.

## One-time Android setup

1. Install the APK.
2. Open **Remote Vault Open RW** once.
3. Tap **Choose / change cache folder**.
4. In Android's folder picker select:
   `Internal storage → Documents → Obsidian Remote Cache`
5. Tap **Use this folder** / **Allow**.

Do not select the whole Documents directory; select the cache directory itself.

## Termux integration

Copy `termux/android-open-rw` into `~/bin`:

```bash
cp /path/to/android-open-rw ~/bin/android-open-rw
chmod +x ~/bin/android-open-rw
```

Test it against a cached PDF:

```bash
PDF="$(find "$HOME/storage/shared/Documents/Obsidian Remote Cache" -maxdepth 1 -type f -iname '*.pdf' | head -n 1)"
android-open-rw "$PDF"
```

Then in Linux Obsidian set:

**Settings → Remote Working Cache → External open command**

```text
/data/data/com.termux/files/home/bin/android-open-rw
```

After that, **Open local working copy** should call this helper instead of `termux-open`.

## Expected workflow

```text
Remote vault
    ↓
Remote Working Cache downloads working copy
    ↓
Documents/Obsidian Remote Cache/<cached-file>.pdf
    ↓
Open local working copy
    ↓
Remote Vault Open RW
    ↓
Android chooser
    ↓
Samsung Notes / another editor
    ↓
Save modifies the same cached file
    ↓
Remote Working Cache detects the modification
    ↓
Sync back to remote vault
```

## Safety behavior

The helper only has access to the folder you select through Android's system folder picker. It does not request broad storage permission or `MANAGE_EXTERNAL_STORAGE`.

The existing Remote Working Cache plugin remains responsible for conflict detection. If the remote file changed after the working copy was downloaded, the plugin should create its conflict copy rather than blindly overwriting the remote file.

## GitHub Actions build

This repository includes `.github/workflows/build-apk.yml`.

After the files are pushed to `main`, open **Actions → Build Android APK**. The workflow installs Android SDK 35 and Gradle 8.9, builds `app-debug.apk`, and uploads it as the artifact **RemoteVaultOpenRW-debug**.
