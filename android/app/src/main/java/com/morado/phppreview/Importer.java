package com.morado.phppreview;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unpacks a website ZIP and figures out document root, database settings and SQL dumps. */
final class Importer {
    static final class Staged {
        File dir, www;
        String suggestedName = "";
        String docRoot = "";
        ConfigDetector.Result cfg;
        final List<File> sqlFiles = new ArrayList<>();
        String sqlDbName;
    }

    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("vendor", "node_modules", ".git", "__macosx"));

    static String displayName(Context c, Uri uri) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        return name;
    }

    static File copyToTmp(Context c, Rt rt, Uri uri, String ext) throws Exception {
        File f = new File(rt.tmp, "upload-" + System.currentTimeMillis() + ext);
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("تعذر فتح الملف");
            Util.save(in, f);
        }
        return f;
    }

    static Staged stage(Context c, Rt rt, Uri uri) throws Exception {
        Staged s = new Staged();
        String disp = displayName(c, uri);
        s.suggestedName = disp.replaceAll("(?i)\\.zip$", "").trim();
        File zip = copyToTmp(c, rt, uri, ".zip");
        s.dir = new File(rt.projects, ".staging-" + System.currentTimeMillis());
        s.www = new File(s.dir, "www");
        try {
            Util.unzipProject(zip, s.www);
        } catch (Exception e) {
            Util.deleteRecursive(s.dir);
            throw e;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
        }
        s.docRoot = detectDocRoot(s.www);
        File dr = s.docRoot.isEmpty() ? s.www : new File(s.www, s.docRoot);
        s.cfg = ConfigDetector.detect(s.www, dr);
        findSql(s.www, 0, s.sqlFiles);
        if (!s.sqlFiles.isEmpty()) s.sqlDbName = sniffSqlDb(s.sqlFiles.get(0));
        return s;
    }

    /** Shallowest folder that contains index.php (preferred) or index.html. */
    static String detectDocRoot(File www) {
        ArrayDeque<File> q = new ArrayDeque<>();
        q.add(www);
        String html = null;
        while (!q.isEmpty()) {
            File d = q.poll();
            int depth = Util.relative(www, d).isEmpty() ? 0 : Util.relative(www, d).split("/").length;
            if (new File(d, "index.php").isFile()) return Util.relative(www, d);
            if (html == null && (new File(d, "index.html").isFile() || new File(d, "index.htm").isFile())) {
                html = Util.relative(www, d);
            }
            if (depth >= 3) continue;
            File[] fs = d.listFiles();
            if (fs == null) continue;
            Arrays.sort(fs, (a, b) -> {
                // Laravel style "public" first
                int pa = a.getName().equals("public") ? 0 : 1, pb = b.getName().equals("public") ? 0 : 1;
                return pa != pb ? pa - pb : a.getName().compareTo(b.getName());
            });
            for (File f : fs) {
                if (f.isDirectory() && !SKIP.contains(f.getName().toLowerCase(Locale.ROOT))) q.add(f);
            }
        }
        return html != null ? html : "";
    }

    static void findSql(File dir, int depth, List<File> out) {
        if (depth > 4) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".sql")) out.add(f);
        }
        for (File f : fs) {
            if (f.isDirectory() && !SKIP.contains(f.getName().toLowerCase(Locale.ROOT))) findSql(f, depth + 1, out);
        }
    }

    private static final Pattern SQL_DB = Pattern.compile(
            "^\\s*(?:CREATE\\s+DATABASE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?|USE\\s+)`?([A-Za-z0-9_$\\-]+)`?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_COMMENT_DB = Pattern.compile("^--.*:\\s*`([A-Za-z0-9_$\\-]+)`");

    /** Database name mentioned in a dump ("CREATE DATABASE x", "USE x" or phpMyAdmin header). */
    static String sniffSqlDb(File sql) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(sql), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 400) {
                Matcher m = SQL_DB.matcher(line);
                if (m.find()) return m.group(1);
                if (n < 40) {
                    m = SQL_COMMENT_DB.matcher(line);
                    if (m.find() && (line.contains("Database") || line.contains("قاعدة"))) return m.group(1);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
