package com.pocketbuilder.app_wslenytvplayer1792356127271;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Busca entre las carpetas del teléfono las listas M3U/M3U8 que el usuario ya
 * tiene descargadas (Descargas, Downloader, Documents, Movies...), como hace el
 * panel PLAYLIST de la app de referencia.
 */
final class LocalLists {

    /** Código de petición del permiso de lectura de archivos. */
    static final int REQ_STORAGE = 4712;

    private static final int MAX_ENTRIES = 40;
    private static final int MAX_FILES_PER_FOLDER = 12;

    /** Una lista encontrada o una carpeta que contiene listas. */
    static final class Entry {
        final String label;
        final String detail;
        final String path;
        final boolean folder;

        Entry(String label, String detail, String path, boolean folder) {
            this.label = label == null ? "" : label;
            this.detail = detail == null ? "" : detail;
            this.path = path == null ? "" : path;
            this.folder = folder;
        }
    }

    private LocalLists() {
    }

    /** true si la app puede leer las carpetas del teléfono ahora mismo. */
    static boolean hasAccess(Context c) {
        if (c == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
            return c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Pide permiso para leer archivos (o abre los ajustes en Android 11 o superior). */
    static void requestAccess(Activity a) {
        if (a == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + a.getPackageName()));
                a.startActivityForResult(intent, REQ_STORAGE);
                return;
            } catch (Throwable ignored) {
            }
            try {
                a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_STORAGE);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            a.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } catch (Throwable ignored) {
        }
    }

    /** Recorre las carpetas habituales y devuelve listas y carpetas con listas. */
    static ArrayList<Entry> scan(Context c) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        ArrayList<String> seen = new ArrayList<String>();
        ArrayList<File> roots = new ArrayList<File>();
        addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        addRoot(roots, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));

        for (int r = 0; r < roots.size(); r++) {
            File root = roots.get(r);
            if (root == null || !root.isDirectory()) continue;
            File[] children = root.listFiles();
            if (children == null) continue;

            for (int i = 0; i < children.length && out.size() < MAX_ENTRIES; i++) {
                File f = children[i];
                if (f.isFile() && isPlaylist(f.getName())) addFile(out, seen, f);
            }
            for (int i = 0; i < children.length && out.size() < MAX_ENTRIES; i++) {
                File dir = children[i];
                if (!dir.isDirectory()) continue;
                ArrayList<File> inside = playlistsIn(dir);
                if (inside.size() == 1) {
                    addFile(out, seen, inside.get(0));
                } else if (inside.size() > 1) {
                    addFolder(out, seen, dir, inside.size());
                }
            }
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    /** Listas M3U/M3U8 guardadas directamente en una carpeta. */
    static ArrayList<File> playlistsIn(File dir) {
        ArrayList<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isFile() && isPlaylist(f.getName()) && out.size() < MAX_FILES_PER_FOLDER) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    static boolean isPlaylist(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(Locale.US);
        return low.endsWith(".m3u") || low.endsWith(".m3u8");
    }

    private static void addRoot(ArrayList<File> roots, File dir) {
        if (dir == null) return;
        String path = dir.getAbsolutePath();
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).getAbsolutePath().equals(path)) return;
        }
        roots.add(dir);
    }

    private static void addFile(ArrayList<Entry> out, ArrayList<String> seen, File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        if (seen.contains(path)) return;
        seen.add(path);
        File parent = file.getParentFile();
        out.add(new Entry(file.getName(), parent == null ? path : parent.getAbsolutePath(), path, false));
    }

    private static void addFolder(ArrayList<Entry> out, ArrayList<String> seen, File dir, int count) {
        String path = dir.getAbsolutePath();
        if (seen.contains(path)) return;
        seen.add(path);
        out.add(new Entry(dir.getName() + " (" + count + ")", path, path, true));
    }
}
