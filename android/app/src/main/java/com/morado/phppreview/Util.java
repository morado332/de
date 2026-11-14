package com.morado.phppreview;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class Util {
    private Util() {}

    static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Reads at most maxBytes of a file (null if it is bigger). */
    static String readLimited(File f, int maxBytes) {
        if (!f.isFile() || f.length() > maxBytes) return null;
        try {
            return read(f);
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File f, String s) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Copies the stream into a file; does NOT close the input stream. */
    static void save(InputStream in, File f) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStream o = new FileOutputStream(f)) {
            pipe(in, o);
        }
    }

    static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }

    static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static void copyDir(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] c = src.listFiles();
            if (c != null) for (File x : c) copyDir(x, new File(dst, x.getName()));
        } else {
            try (InputStream in = new FileInputStream(src)) {
                save(in, dst);
            }
        }
    }

    static File safeChild(File dest, String name) throws IOException {
        File f = new File(dest, name);
        String base = dest.getCanonicalPath() + File.separator;
        String target = f.getCanonicalPath();
        if (!(target + File.separator).startsWith(base) && !target.startsWith(base)) {
            throw new IOException("مسار غير آمن داخل الملف المضغوط: " + name);
        }
        return f;
    }

    /** Extracts a zip stream as-is (used for the bundled runtime). */
    static void unzipStream(InputStream in, File dest) throws IOException {
        dest.mkdirs();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(in, 65536))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File f = safeChild(dest, e.getName());
                if (e.isDirectory()) f.mkdirs();
                else save(z, f);
            }
        }
    }

    private static ZipFile openZip(File zip, List<ZipEntry> out) throws IOException {
        String[] charsets = {"UTF-8", "windows-1256", "IBM437"};
        IOException last = null;
        for (String cs : charsets) {
            ZipFile zf = null;
            try {
                zf = new ZipFile(zip, Charset.forName(cs));
                List<ZipEntry> list = new ArrayList<>();
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) list.add(en.nextElement());
                out.clear();
                out.addAll(list);
                return zf;
            } catch (IllegalArgumentException e) {
                if (zf != null) try { zf.close(); } catch (IOException ignored) { }
                last = new IOException(e);
            } catch (IOException e) {
                if (zf != null) try { zf.close(); } catch (IOException ignored) { }
                throw new IOException("الملف ليس ملف ZIP صالحاً", e);
            }
        }
        throw last;
    }

    private static boolean junk(String name) {
        return name.startsWith("__MACOSX/") || name.endsWith(".DS_Store") || name.endsWith("Thumbs.db");
    }

    /** Extracts a project zip; if everything is inside one top folder, that folder is stripped. */
    static void unzipProject(File zip, File dest) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        try (ZipFile zf = openZip(zip, entries)) {
            Set<String> tops = new HashSet<>();
            boolean rootFile = false;
            for (ZipEntry e : entries) {
                String n = e.getName().replace('\\', '/');
                if (junk(n) || n.isEmpty()) continue;
                int i = n.indexOf('/');
                if (i < 0) {
                    rootFile = true;
                    tops.add(n);
                } else tops.add(n.substring(0, i));
            }
            String prefix = (!rootFile && tops.size() == 1) ? tops.iterator().next() + "/" : "";
            dest.mkdirs();
            for (ZipEntry e : entries) {
                String n = e.getName().replace('\\', '/');
                if (junk(n)) continue;
                if (!prefix.isEmpty()) {
                    if (!n.startsWith(prefix)) continue;
                    n = n.substring(prefix.length());
                }
                if (n.isEmpty()) continue;
                File f = safeChild(dest, n);
                if (e.isDirectory() || n.endsWith("/")) {
                    f.mkdirs();
                } else {
                    try (InputStream in = zf.getInputStream(e)) {
                        save(in, f);
                    }
                }
            }
        }
    }

    static String tail(File f, int maxChars) {
        if (!f.isFile()) return "(لا يوجد)";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long start = Math.max(0, len - maxChars);
            r.seek(start);
            byte[] b = new byte[(int) (len - start)];
            r.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "(فارغ)" : s;
        } catch (IOException e) {
            return "(تعذرت القراءة: " + e.getMessage() + ")";
        }
    }

    private static final String CHARS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    static String randomPass(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(CHARS.charAt(r.nextInt(CHARS.length())));
        return s.toString();
    }

    /** Turns any text into a safe MySQL identifier (letters, digits, underscore). */
    static String ident(String s, int max) {
        String r = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (r.isEmpty()) r = "site" + (System.currentTimeMillis() % 10000);
        if (Character.isDigit(r.charAt(0))) r = "db_" + r;
        if (r.length() > max) r = r.substring(0, max);
        return r;
    }

    static String relative(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(b)) return "";
        if (p.startsWith(b + "/")) return p.substring(b.length() + 1);
        return p;
    }
}
