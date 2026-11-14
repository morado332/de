package com.morado.phppreview;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tries to find the database name / user / password a website already uses in its config files. */
final class ConfigDetector {
    static final class Result {
        String name, user, pass, source;

        int score() {
            return (name != null ? 2 : 0) + (user != null ? 2 : 0) + (pass != null ? 1 : 0);
        }
    }

    private static final Set<String> SKIP = new HashSet<>(Arrays.asList(
            "vendor", "node_modules", ".git", "storage", "cache", "uploads", "assets", "images", "img",
            "css", "js", "fonts", "wp-includes", "wp-admin", "plugins", "themes", "languages", "lang", "tests"));

    private static final int F = Pattern.CASE_INSENSITIVE;
    private static final String S = "['\"]([^'\"]*)['\"]";
    private static final Pattern DEFINE = Pattern.compile(
            "define\\s*\\(\\s*['\"](DB_NAME|DB_DATABASE|DB_USER|DB_USERNAME|DB_PASSWORD|DB_PASS|DB_PWD)['\"]\\s*,\\s*" + S, F);
    private static final Pattern CONNECT = Pattern.compile(
            "(?:mysqli_connect|new\\s+mysqli|mysql_connect|mysqli_real_connect\\s*\\([^,]+,)\\s*\\(?\\s*" + S
                    + "\\s*,\\s*" + S + "\\s*,\\s*" + S + "(?:\\s*,\\s*" + S + ")?", F);
    private static final Pattern ARRAY = Pattern.compile(
            "['\"](database|dbname|db_name|db|username|user|password|pass|passwd)['\"]\\s*=>\\s*" + S, F);
    private static final Pattern VAR = Pattern.compile("\\$(?:this->)?(\\w+)\\s*=\\s*" + S + "\\s*;", F);
    private static final Pattern DSN = Pattern.compile("dbname\\s*=\\s*([A-Za-z0-9_$\\-]+)", F);

    static Result detect(File www, File docRoot) {
        for (File dir : new File[]{www, docRoot, docRoot.getParentFile()}) {
            if (dir == null) continue;
            Result r = fromEnv(new File(dir, ".env"));
            if (r != null && r.name != null && r.user != null) return r;
        }
        List<File> files = new ArrayList<>();
        collect(www, www, 0, files);
        files.sort((a, b) -> rank(b, www) - rank(a, www));
        Result best = new Result();
        for (File f : files) {
            String t = Util.readLimited(f, 300 * 1024);
            if (t == null) continue;
            Result r = scan(t);
            r.source = Util.relative(www, f);
            if (r.name != null && r.user != null) return r;
            if (r.score() > best.score()) best = r;
        }
        return best;
    }

    private static Result fromEnv(File env) {
        String t = Util.readLimited(env, 64 * 1024);
        if (t == null) return null;
        Result r = new Result();
        r.source = ".env";
        for (String line : t.split("\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            switch (k) {
                case "DB_DATABASE": r.name = v; break;
                case "DB_USERNAME": r.user = v; break;
                case "DB_PASSWORD": r.pass = v; break;
                default: break;
            }
        }
        return r;
    }

    private static void collect(File root, File dir, int depth, List<File> out) {
        if (depth > 3 || out.size() > 600) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                if (!SKIP.contains(n)) collect(root, f, depth + 1, out);
            } else if (n.endsWith(".php") || n.endsWith(".inc") || n.endsWith(".ini")) {
                out.add(f);
            }
        }
    }

    private static int rank(File f, File root) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        int r = 0;
        if (n.equals("wp-config.php")) r += 20;
        if (n.contains("config")) r += 8;
        if (n.contains("db") || n.contains("database") || n.contains("conn")) r += 7;
        if (n.contains("setting") || n.contains("init") || n.contains("const")) r += 3;
        String rel = Util.relative(root, f);
        int depth = rel.length() - rel.replace("/", "").length();
        return r * 10 - depth;
    }

    private static void put(Result r, String key, String val) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains("pass") || k.contains("pwd")) {
            if (r.pass == null) r.pass = val;
        } else if (k.contains("user")) {
            if (r.user == null && !val.isEmpty()) r.user = val;
        } else if (k.equals("db") || k.contains("dbname") || k.contains("db_name") || k.contains("database")
                || k.equals("dbase") || k.equals("db_database")) {
            if (r.name == null && val.matches("[A-Za-z0-9_$\\-]{1,64}")) r.name = val;
        }
    }

    static Result scan(String t) {
        Result r = new Result();
        Matcher m = DEFINE.matcher(t);
        while (m.find()) put(r, m.group(1), m.group(2));
        if (r.name != null && r.user != null) return r;

        m = CONNECT.matcher(t);
        if (m.find()) {
            if (r.user == null) r.user = m.group(2);
            if (r.pass == null) r.pass = m.group(3);
            if (r.name == null && m.group(4) != null && !m.group(4).isEmpty()) r.name = m.group(4);
        }
        m = ARRAY.matcher(t);
        while (m.find()) put(r, m.group(1), m.group(2));
        m = VAR.matcher(t);
        while (m.find()) {
            String k = m.group(1).toLowerCase(Locale.ROOT);
            if (k.contains("db") || k.contains("database") || k.contains("user") || k.contains("pass")
                    || k.contains("pwd")) {
                put(r, k, m.group(2));
            }
        }
        if (r.name == null) {
            m = DSN.matcher(t);
            if (m.find()) r.name = m.group(1);
        }
        return r;
    }
}
