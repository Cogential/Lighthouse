package com.cogent.lighthouse;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs before the game: unpacks the bundled port data (lighthouse.o2r, assets/, config.yml)
 * into the directory libultraship reads from, and on first launch offers to import a ROM so
 * the game's extractor finds it without the user touching Android/data by hand.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "LighthouseSetup";
    private static final int REQUEST_ROM = 1;
    private static final String ROM_NAME = "baserom.us.z64";
    private static final int VANILLA_ROM_SIZE = 0x1000000;

    // SHA-1 of the big-endian ROMs Lighthouse can extract (mirrors config.yml / GameExtractor).
    private static final Map<String, String> SUPPORTED_ROMS = new HashMap<>();
    static {
        SUPPORTED_ROMS.put("1fe1632098865f639e22c11b9a81ee8f29c75d7a", "Banjo-Kazooie (USA v1.0)");
        SUPPORTED_ROMS.put("ded6ee166e740ad1bc810fd678a84b48e245ab80", "Banjo-Kazooie (USA v1.1)");
        SUPPORTED_ROMS.put("bb359a75941df74bf7290212c89fbc6e2c5601fe", "Banjo-Kazooie (PAL)");
        SUPPORTED_ROMS.put("90726d7e7cd5bf6cdfd38f45c9acbf4d45bd9fd8", "Banjo-Kazooie (Japan)");
    }

    private File mGameDir;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Same directory SDL_AndroidGetExternalStoragePath() returns to the native side.
        mGameDir = getExternalFilesDir(null);
        if (mGameDir == null) {
            mGameDir = getFilesDir();
        }

        new Thread(() -> {
            try {
                unpackGameDataIfNeeded();
            } catch (IOException e) {
                Log.e(TAG, "Failed to unpack game data", e);
                mMain.post(() -> Toast.makeText(this, "Failed to unpack game data: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
            boolean haveRom = haveRomOrArchive();
            if (!haveRom && importBundledRom()) {
                haveRom = haveRomOrArchive();
            }
            final boolean romReady = haveRom;
            mMain.post(() -> {
                if (romReady) {
                    launchGame();
                } else {
                    promptForRom();
                }
            });
        }).start();
    }

    private void unpackGameDataIfNeeded() throws IOException {
        SharedPreferences prefs = getSharedPreferences("setup", MODE_PRIVATE);
        long installed = prefs.getLong("unpackedVersion", -1);
        long current;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            current = 0;
        }
        long lastUpdate;
        try {
            lastUpdate = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            lastUpdate = 0;
        }
        // Re-unpack on reinstall too, so a rebuilt APK with the same versionCode still refreshes data.
        if (installed == current && prefs.getLong("unpackedAt", -1) == lastUpdate
                && new File(mGameDir, "lighthouse.o2r").exists()) {
            return;
        }
        unpackGameData();
        prefs.edit().putLong("unpackedVersion", current).putLong("unpackedAt", lastUpdate).apply();
    }

    // Only our own entries: the root asset listing also includes framework assets on some devices.
    private static final String[] GAME_DATA = { "lighthouse.o2r", "config.yml", "gamecontrollerdb.txt", "assets" };

    private void unpackGameData() throws IOException {
        AssetManager am = getAssets();
        for (String entry : GAME_DATA) {
            copyAssetTree(am, entry, new File(mGameDir, entry));
        }
    }

    private void copyAssetTree(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children != null && children.length > 0) {
            for (String child : children) {
                copyAssetTree(am, path + "/" + child, new File(dest, child));
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /**
     * Private builds can bundle the ROM (build.gradle's lighthouseRomDir) for devices without a
     * document picker. Copies it into the game folder; returns false when the APK has none.
     */
    private boolean importBundledRom() {
        File out = new File(mGameDir, ROM_NAME);
        try (InputStream in = getAssets().open(ROM_NAME); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
            Log.i(TAG, "Imported bundled ROM");
            return true;
        } catch (IOException e) {
            out.delete();
            return false;
        }
    }

    /** Runs off the main thread: may hash a ROM already sitting in the game folder. */
    private boolean haveRomOrArchive() {
        if (new File(mGameDir, "bk.o2r").exists()) {
            return true;
        }
        File[] files = mGameDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || !f.getName().toLowerCase().endsWith(".z64")) {
                    continue;
                }
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] rom = readAll(in);
                    if (checkRom(rom) == null) {
                        return true;
                    }
                } catch (IOException e) {
                    // unreadable: ignore
                }
                Log.w(TAG, "Ignoring unsupported ROM " + f.getName());
                if (f.getName().equals(ROM_NAME)) {
                    // Our own earlier import of an unsupported ROM: drop it so the game doesn't try it.
                    f.delete();
                }
            }
        }
        return false;
    }

    private void promptForRom() {
        new AlertDialog.Builder(this)
                .setTitle("Banjo-Kazooie ROM needed")
                .setMessage("Lighthouse needs your own Banjo-Kazooie (USA) ROM to build its game data. "
                        + "Pick the ROM file (.z64, .n64 or .v64). It is copied into the app's folder:\n\n"
                        + mGameDir.getAbsolutePath())
                .setCancelable(false)
                .setPositiveButton("Choose ROM", (d, w) -> pickRom())
                .setNegativeButton("Skip", (d, w) -> launchGame())
                .show();
    }

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_ROM);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
            launchGame();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ROM) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            promptForRom();
            return;
        }
        importRom(data.getData());
    }

    @SuppressWarnings("deprecation")
    private void importRom(Uri uri) {
        ProgressDialog progress = ProgressDialog.show(this, null, "Copying ROM…", true, false);
        new Thread(() -> {
            String error = null;
            String name = null;
            File out = new File(mGameDir, ROM_NAME);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("could not open file");
                }
                byte[] rom = readAll(in);
                String problem = checkRom(rom);
                if (problem != null) {
                    throw new IOException(problem);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    os.write(rom);
                }
                name = SUPPORTED_ROMS.get(sha1(rom));
            } catch (IOException e) {
                error = e.getMessage();
                out.delete();
            }
            final String err = error;
            final String importedName = name;
            mMain.post(() -> {
                progress.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this)
                            .setTitle("Wrong ROM")
                            .setMessage(err)
                            .setCancelable(false)
                            .setPositiveButton("Try again", (d, w) -> pickRom())
                            .setNegativeButton("Skip", (d, w) -> launchGame())
                            .show();
                } else {
                    Toast.makeText(this, "ROM OK: " + importedName, Toast.LENGTH_LONG).show();
                    launchGame();
                }
            });
        }).start();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(16 * 1024 * 1024);
        copy(in, bos);
        return bos.toByteArray();
    }

    /**
     * Converts the ROM to big-endian in place and checks it is one Lighthouse can extract.
     * Returns null when supported, otherwise a message for the user.
     */
    private static String checkRom(byte[] rom) {
        if (!toBigEndian(rom)) {
            return "That file isn't an N64 ROM.";
        }
        if (SUPPORTED_ROMS.containsKey(sha1(rom))) {
            return null;
        }
        String internalName = new String(rom, 0x20, 20, StandardCharsets.US_ASCII).replace('\0', ' ').trim();
        boolean isBanjo = rom[0x3B] == 'N' && rom[0x3C] == 'B' && rom[0x3D] == 'K';
        String supported = "\n\nLighthouse needs an unmodified Banjo-Kazooie ROM: USA (v1.0 or v1.1), PAL or Japan.";
        if (isBanjo && rom.length > VANILLA_ROM_SIZE) {
            return "This looks like a Banjo-Kazooie romhack. Import the original game here first; "
                    + "romhacks are added afterwards from the in-game Mod Menu." + supported;
        }
        if (isBanjo) {
            return "This Banjo-Kazooie ROM doesn't match any supported version. "
                    + "It may be modified or a bad dump." + supported;
        }
        return "\"" + (internalName.isEmpty() ? "This ROM" : internalName) + "\" isn't Banjo-Kazooie." + supported;
    }

    private static String sha1(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Converts .v64 (byte-swapped) and .n64 (little-endian) dumps to .z64 in place. */
    private static boolean toBigEndian(byte[] rom) {
        if (rom.length < 0x1000 || rom.length % 4 != 0) {
            return false;
        }
        int b0 = rom[0] & 0xFF, b1 = rom[1] & 0xFF, b2 = rom[2] & 0xFF, b3 = rom[3] & 0xFF;
        if (b0 == 0x80 && b1 == 0x37 && b2 == 0x12 && b3 == 0x40) {
            return true;
        }
        if (b0 == 0x37 && b1 == 0x80 && b2 == 0x40 && b3 == 0x12) {
            for (int i = 0; i < rom.length; i += 2) {
                byte t = rom[i]; rom[i] = rom[i + 1]; rom[i + 1] = t;
            }
            return true;
        }
        if (b0 == 0x40 && b1 == 0x12 && b2 == 0x37 && b3 == 0x80) {
            for (int i = 0; i < rom.length; i += 4) {
                byte t0 = rom[i], t1 = rom[i + 1];
                rom[i] = rom[i + 3]; rom[i + 1] = rom[i + 2];
                rom[i + 2] = t1; rom[i + 3] = t0;
            }
            return true;
        }
        return false;
    }

    private void launchGame() {
        startActivity(new Intent(this, LighthouseActivity.class));
        finish();
    }
}
