package com.morado.phppreview;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Manages the bundled runtime: MariaDB server, PHP built-in web server and the mariadb client.
 * Binaries are Alpine Linux (musl) builds, started through the musl loader that ships as
 * libldmusl.so in the app's native library directory.
 */
final class Rt {
    static final String ADMIN_USER = "pvadmin";

    private static Rt inst;

    static synchronized Rt get(Context c) {
        if (inst == null) inst = new Rt(c.getApplicationContext());
        return inst;
    }

    final Context ctx;
    final SharedPreferences prefs;
    final File base, rt, lib, data, myData, run, tmp, logs, projects;
    final String loader;

    private Process dbProc, phpProc;
    private boolean staleChecked;
    int dbPort = 3306;
    int webPort = 8080;
    int phpGen;
    Project current;

    private Rt(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("rt", Context.MODE_PRIVATE);
        base = c.getFilesDir();
        rt = new File(base, "rt");
        lib = new File(rt, "lib");
        data = new File(base, "data");
        myData = new File(data, "mysql");
        run = new File(base, "run");
        tmp = new File(base, "tmp");
        logs = new File(base, "logs");
        projects = new File(base, "projects");
        loader = c.getApplicationInfo().nativeLibraryDir + "/libldmusl.so";
    }

    // ------------------------------------------------------------------ install

    synchronized void install() throws Exception {
        if (!new File(loader).exists()) {
            throw new Exception("هذا الجهاز غير مدعوم من هذه النسخة.\nمعالج الجهاز: "
                    + Arrays.toString(Build.SUPPORTED_ABIS)
                    + "\nثبّت نسخة التطبيق المناسبة (arm64 أو armv7).");
        }
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        String ver = pi.versionName + "-" + pi.lastUpdateTime;
        File marker = new File(rt, ".installed");
        boolean ok = false;
        try {
            ok = marker.isFile() && Util.read(marker).equals(ver);
        } catch (Exception ignored) {
        }
        if (!ok) {
            killStale();
            Util.deleteRecursive(rt);
            rt.mkdirs();
            try (InputStream in = ctx.getAssets().open("runtime.zip")) {
                Util.unzipStream(in, rt);
            }
            for (String a : new String[]{"router.php", "adminer.php"}) {
                try (InputStream in = ctx.getAssets().open(a)) {
                    Util.save(in, new File(rt, a));
                } catch (Exception ignored) {
                }
            }
            Util.write(marker, ver);
        }
        // the native library dir changes on every app update -> always refresh the links
        for (String n : Util.read(new File(rt, "musl-names.txt")).split("\\s+")) {
            if (n.isEmpty()) continue;
            File l = new File(lib, n);
            //noinspection ResultOfMethodCallIgnored
            l.delete();
            Os.symlink(loader, l.getPath());
        }
        for (File d : new File[]{data, run, tmp, logs, projects}) d.mkdirs();
        if (!new File(myData, "mysql").isDirectory()) {
            Util.deleteRecursive(myData);
            Util.copyDir(new File(rt, "datadir-template"), myData);
            prefs.edit().remove("adminPass").putString("rootPass", "").commit();
        }
        if (!staleChecked) {
            staleChecked = true;
            killStale();
        }
        // clean leftovers of old imports
        File[] st = projects.listFiles();
        if (st != null) for (File f : st) if (f.getName().startsWith(".staging")) Util.deleteRecursive(f);
    }

    /** Kills runtime processes left over from a previous (crashed/killed) app process. */
    private void killStale() {
        int me = android.os.Process.myPid();
        File[] ps = new File("/proc").listFiles();
        if (ps == null) return;
        List<Integer> killed = new ArrayList<>();
        String marker = rt.getPath() + "/bin/";
        for (File d : ps) {
            String n = d.getName();
            if (!n.matches("\\d+")) continue;
            int pid = Integer.parseInt(n);
            if (pid == me) continue;
            try {
                String cmd = Util.read(new File(d, "cmdline")).replace('\0', ' ');
                if (cmd.contains(marker)) {
                    Os.kill(pid, OsConstants.SIGTERM);
                    killed.add(pid);
                }
            } catch (Throwable ignored) {
            }
        }
        long end = System.currentTimeMillis() + 20000;
        for (int pid : killed) {
            while (new File("/proc/" + pid).exists() && System.currentTimeMillis() < end) sleep(200);
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<String> cmd(String bin, String... args) {
        List<String> c = new ArrayList<>();
        c.add(loader);
        c.add("--library-path");
        c.add(lib.getPath());
        c.add(new File(rt, "bin/" + bin).getPath());
        c.addAll(Arrays.asList(args));
        return c;
    }

    private void baseEnv(ProcessBuilder pb) {
        Map<String, String> e = pb.environment();
        e.put("TMPDIR", tmp.getPath());
        e.put("HOME", base.getPath());
        e.put("PHP_INI_SCAN_DIR", "");
        e.remove("LD_PRELOAD");
    }

    File sockFile() {
        return new File(run, "mysqld.sock");
    }

    static boolean alive(Process p) {
        return p != null && p.isAlive();
    }

    static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int pickPort(int preferred) throws Exception {
        for (int i = 0; i < 40; i++) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("127.0.0.1", preferred + i));
                return preferred + i;
            } catch (Exception ignored) {
            }
        }
        throw new Exception("لا يوجد منفذ متاح بدءاً من " + preferred);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String tzOffset() {
        int off = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        char sign = off < 0 ? '-' : '+';
        off = Math.abs(off);
        return String.format(java.util.Locale.ROOT, "%c%02d:%02d", sign, off / 60, off % 60);
    }

    static String q(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    static String qi(String s) {
        return "`" + s.replace("`", "``") + "`";
    }

    String rootPass() {
        return prefs.getString("rootPass", "");
    }

    boolean showErrors() {
        return prefs.getBoolean("showErrors", false);
    }

    void setShowErrors(boolean b) {
        prefs.edit().putBoolean("showErrors", b).apply();
    }

    // ------------------------------------------------------------------ MariaDB

    synchronized boolean dbRunning() {
        return alive(dbProc);
    }

    synchronized void startDb() throws Exception {
        if (alive(dbProc) && portOpen(dbPort)) return;
        stopDb();
        dbPort = pickPort(3306);
        File sock = sockFile();
        //noinspection ResultOfMethodCallIgnored
        sock.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(run, "mysqld.pid").delete();
        String r = rt.getPath();
        List<String> c = cmd("mariadbd",
                "--no-defaults",
                "--basedir=" + r + "/mariadb",
                "--datadir=" + myData,
                "--plugin-dir=" + r + "/mariadb/plugin",
                "--lc-messages-dir=" + r + "/mariadb/share",
                "--lc-messages=en_US",
                "--character-sets-dir=" + r + "/mariadb/share/charsets",
                "--socket=" + sock,
                "--port=" + dbPort,
                "--bind-address=127.0.0.1",
                "--skip-name-resolve",
                "--pid-file=" + run + "/mysqld.pid",
                "--tmpdir=" + tmp,
                "--log-error=" + logs + "/mysql.err",
                "--innodb-use-native-aio=0",
                "--innodb-flush-method=fsync",
                "--innodb-buffer-pool-size=64M",
                "--innodb-log-file-size=8M",
                "--performance-schema=OFF",
                "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_unicode_ci",
                "--max-connections=60",
                "--default-time-zone=" + tzOffset(),
                "--sql-mode=NO_ENGINE_SUBSTITUTION",
                "--max-allowed-packet=64M");
        ProcessBuilder pb = new ProcessBuilder(c);
        baseEnv(pb);
        pb.directory(data);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(logs, "mysql-out.log")));
        dbProc = pb.start();
        long end = System.currentTimeMillis() + 90000;
        while (true) {
            if (!alive(dbProc)) {
                dbProc = null;
                throw new Exception("تعذر تشغيل قاعدة البيانات MariaDB.\n\n"
                        + Util.tail(new File(logs, "mysql.err"), 1500) + "\n"
                        + Util.tail(new File(logs, "mysql-out.log"), 800));
            }
            if (sock.exists() && portOpen(dbPort)) break;
            if (System.currentTimeMillis() > end) {
                throw new Exception("انتهت مهلة تشغيل MariaDB.\n\n" + Util.tail(new File(logs, "mysql.err"), 1500));
            }
            sleep(300);
        }
        ensureAdmin();
    }

    synchronized void stopDb() {
        if (alive(dbProc)) {
            dbProc.destroy(); // SIGTERM -> clean shutdown
            long end = System.currentTimeMillis() + 20000;
            while (alive(dbProc) && System.currentTimeMillis() < end) sleep(200);
            if (alive(dbProc)) dbProc.destroyForcibly();
        }
        dbProc = null;
    }

    /** Creates the internal admin account the app itself uses (root stays free for your sites). */
    private void ensureAdmin() throws Exception {
        String ap = prefs.getString("adminPass", null);
        if (ap != null) {
            try {
                sql(ADMIN_USER, ap, null, "SELECT 1;", null);
                return;
            } catch (Exception ignored) {
            }
        }
        String np = Util.randomPass(20);
        String rp = rootPass();
        sql("root", rp, null,
                "CREATE USER IF NOT EXISTS '" + ADMIN_USER + "'@'localhost' IDENTIFIED BY '" + np + "';\n"
                        + "ALTER USER '" + ADMIN_USER + "'@'localhost' IDENTIFIED BY '" + np + "';\n"
                        + "GRANT ALL PRIVILEGES ON *.* TO '" + ADMIN_USER + "'@'localhost' WITH GRANT OPTION;\n"
                        + "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '" + q(rp) + "';\n"
                        + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;\n"
                        + "FLUSH PRIVILEGES;\n", null);
        prefs.edit().putString("adminPass", np).commit();
    }

    /** Runs SQL (or an SQL file) with the mariadb command line client over the unix socket. */
    String sql(String user, String pass, String db, String sql, File input) throws Exception {
        List<String> c = cmd("mariadb", "--no-defaults",
                "--socket=" + sockFile(),
                "--user=" + user,
                "--default-character-set=utf8mb4",
                "--character-sets-dir=" + rt + "/mariadb/share/charsets",
                "--batch");
        if (db != null) c.add(db);
        ProcessBuilder pb = new ProcessBuilder(c);
        baseEnv(pb);
        pb.environment().put("MYSQL_PWD", pass == null ? "" : pass);
        pb.redirectErrorStream(true);
        if (input != null) pb.redirectInput(input);
        Process pr = pb.start();
        if (input == null) {
            try (OutputStream os = pr.getOutputStream()) {
                os.write(sql.getBytes(StandardCharsets.UTF_8));
            }
        }
        String out = Util.readAll(pr.getInputStream());
        int code = pr.waitFor();
        if (code != 0) throw new Exception("خطأ من قاعدة البيانات:\n" + out.trim());
        return out;
    }

    String adminSql(String db, String sql) throws Exception {
        return sql(ADMIN_USER, prefs.getString("adminPass", ""), db, sql, null);
    }

    /** Creates (or updates) the project database and its user for localhost + 127.0.0.1. */
    synchronized void createDb(Project p) throws Exception {
        StringBuilder s = new StringBuilder();
        s.append("CREATE DATABASE IF NOT EXISTS ").append(qi(p.dbName))
                .append(" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n");
        for (String h : new String[]{"localhost", "127.0.0.1"}) {
            String acc = "'" + q(p.dbUser) + "'@'" + h + "'";
            s.append("CREATE USER IF NOT EXISTS ").append(acc).append(" IDENTIFIED BY '").append(q(p.dbPass)).append("';\n");
            s.append("ALTER USER ").append(acc).append(" IDENTIFIED BY '").append(q(p.dbPass)).append("';\n");
            s.append("GRANT ALL PRIVILEGES ON ").append(qi(p.dbName)).append(".* TO ").append(acc).append(";\n");
        }
        s.append("FLUSH PRIVILEGES;\n");
        adminSql(null, s.toString());
        if ("root".equals(p.dbUser)) prefs.edit().putString("rootPass", p.dbPass).commit();
    }

    synchronized void dropDb(Project p, boolean dropUser) throws Exception {
        StringBuilder s = new StringBuilder("DROP DATABASE IF EXISTS " + qi(p.dbName) + ";\n");
        if (dropUser && !"root".equals(p.dbUser)) {
            s.append("DROP USER IF EXISTS '").append(q(p.dbUser)).append("'@'localhost';\n");
            s.append("DROP USER IF EXISTS '").append(q(p.dbUser)).append("'@'127.0.0.1';\n");
        }
        adminSql(null, s.toString());
    }

    synchronized void setRootPassword(String np) throws Exception {
        adminSql(null, "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + q(np) + "';\n"
                + "CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '" + q(np) + "';\n"
                + "ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '" + q(np) + "';\n"
                + "GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;\n"
                + "FLUSH PRIVILEGES;\n");
        prefs.edit().putString("rootPass", np).commit();
    }

    private static final Pattern MYSQL8_COLLATION = Pattern.compile("utf8mb4_0900_[a-z_]+");

    /** Imports an .sql dump into the project database (MySQL 8 collations are converted). */
    synchronized void importSql(Project p, File sqlFile) throws Exception {
        File fixed = new File(tmp, "import-" + System.currentTimeMillis() + ".sql");
        // ISO-8859-1 keeps every byte unchanged while letting us replace ASCII text
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(sqlFile), StandardCharsets.ISO_8859_1));
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fixed), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("_0900_")) line = MYSQL8_COLLATION.matcher(line).replaceAll("utf8mb4_unicode_ci");
                w.write(line);
                w.write('\n');
            }
        }
        try {
            sql(ADMIN_USER, prefs.getString("adminPass", ""), p.dbName, null, fixed);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            fixed.delete();
        }
    }

    // ------------------------------------------------------------------ PHP

    private void writeIni(File ini) throws Exception {
        String sock = sockFile().getPath();
        boolean show = showErrors();
        String s = "extension_dir=\"" + rt + "/php/modules\"\n"
                + Util.read(new File(rt, "php/extensions.txt")) + "\n"
                + "mysqli.default_socket=\"" + sock + "\"\n"
                + "pdo_mysql.default_socket=\"" + sock + "\"\n"
                + "mysqli.default_port=" + dbPort + "\n"
                + "date.timezone=\"" + TimeZone.getDefault().getID() + "\"\n"
                + "memory_limit=512M\n"
                + "upload_max_filesize=128M\n"
                + "post_max_size=128M\n"
                + "max_execution_time=300\n"
                + "max_input_time=300\n"
                + "max_input_vars=5000\n"
                + "display_errors=" + (show ? "On" : "Off") + "\n"
                + "display_startup_errors=" + (show ? "On" : "Off") + "\n"
                + "error_reporting=E_ALL & ~E_DEPRECATED & ~E_STRICT\n"
                + "log_errors=On\n"
                + "error_log=\"" + logs + "/php-errors.log\"\n"
                + "session.save_path=\"" + tmp + "\"\n"
                + "sys_temp_dir=\"" + tmp + "\"\n"
                + "upload_tmp_dir=\"" + tmp + "\"\n"
                + "default_charset=\"UTF-8\"\n"
                + "short_open_tag=On\n"
                + "output_buffering=4096\n"
                + "variables_order=\"EGPCS\"\n"
                + "allow_url_fopen=On\n"
                + "file_uploads=On\n";
        Util.write(ini, s);
    }

    /** Starts the PHP web server for a project and returns its port. */
    synchronized int startPhp(Project p) throws Exception {
        stopPhp();
        webPort = pickPort(8080);
        File ini = new File(run, "php.ini");
        writeIni(ini);
        File docroot = p.docRootDir();
        if (!docroot.isDirectory()) throw new Exception("مجلد الموقع غير موجود: " + docroot);
        List<String> c = cmd("php", "-c", ini.getPath(), "-S", "127.0.0.1:" + webPort,
                "-t", docroot.getPath(), new File(rt, "router.php").getPath());
        ProcessBuilder pb = new ProcessBuilder(c);
        baseEnv(pb);
        Map<String, String> e = pb.environment();
        e.put("DB_HOST", "127.0.0.1");
        e.put("DB_PORT", String.valueOf(dbPort));
        e.put("DB_DATABASE", p.dbName);
        e.put("DB_NAME", p.dbName);
        e.put("DB_USERNAME", p.dbUser);
        e.put("DB_USER", p.dbUser);
        e.put("DB_PASSWORD", p.dbPass);
        e.put("DB_PASS", p.dbPass);
        e.put("DB_SOCKET", sockFile().getPath());
        e.put("ADMINER_FILE", new File(rt, "adminer.php").getPath());
        pb.directory(docroot);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(new File(logs, "php.log")));
        phpProc = pb.start();
        phpGen++;
        current = p;
        long end = System.currentTimeMillis() + 20000;
        while (!portOpen(webPort)) {
            if (!alive(phpProc)) {
                phpProc = null;
                throw new Exception("تعذر تشغيل خادم PHP.\n\n" + Util.tail(new File(logs, "php.log"), 1500));
            }
            if (System.currentTimeMillis() > end) throw new Exception("انتهت مهلة تشغيل خادم PHP.");
            sleep(150);
        }
        return webPort;
    }

    synchronized void stopPhp() {
        if (alive(phpProc)) {
            phpProc.destroy();
            long end = System.currentTimeMillis() + 3000;
            while (alive(phpProc) && System.currentTimeMillis() < end) sleep(100);
            if (alive(phpProc)) phpProc.destroyForcibly();
        }
        phpProc = null;
        current = null;
    }

    /** Stops the PHP server only if it is still the one started on this port. */
    synchronized void stopPhpIfGen(int gen) {
        if (gen > 0 && gen == phpGen && phpProc != null) stopPhp();
    }

    synchronized void stopAll() {
        stopPhp();
        stopDb();
    }

    // ------------------------------------------------------------------ texts

    String adminerPath(Project p) {
        try {
            return "/__adminer?server=" + URLEncoder.encode("127.0.0.1:" + dbPort, "UTF-8")
                    + "&username=" + URLEncoder.encode(p.dbUser, "UTF-8")
                    + "&db=" + URLEncoder.encode(p.dbName, "UTF-8");
        } catch (Exception e) {
            return "/__adminer";
        }
    }

    String connectionInfo(Project p) {
        String pw = p.dbPass.isEmpty() ? "(فارغة)" : p.dbPass;
        String phpPw = p.dbPass.replace("\\", "\\\\").replace("'", "\\'");
        return "بيانات الاتصال بقاعدة البيانات\n\n"
                + "• المضيف Host:  localhost  أو  127.0.0.1\n"
                + "• المنفذ Port:  " + dbPort + "\n"
                + "• اسم القاعدة:  " + p.dbName + "\n"
                + "• المستخدم:  " + p.dbUser + "\n"
                + "• كلمة المرور:  " + pw + "\n\n"
                + "مثال mysqli:\n"
                + "$conn = new mysqli('localhost', '" + p.dbUser + "', '" + phpPw + "', '" + p.dbName + "');\n\n"
                + "مثال PDO:\n"
                + "$pdo = new PDO('mysql:host=127.0.0.1;port=" + dbPort + ";dbname=" + p.dbName
                + ";charset=utf8mb4', '" + p.dbUser + "', '" + phpPw + "');\n\n"
                + "إذا كانت هذه البيانات مطابقة لملف الإعدادات (config) في موقعك فلن تحتاج لأي تعديل.\n"
                + "متاحة أيضاً كمتغيرات بيئة: DB_HOST, DB_PORT, DB_DATABASE, DB_USERNAME, DB_PASSWORD";
    }

    String serverInfo() {
        String versions = "";
        try {
            versions = Util.read(new File(rt, "versions.txt")).trim();
        } catch (Exception ignored) {
        }
        String rp = rootPass();
        return "حالة MariaDB: " + (dbRunning() ? "تعمل ✅" : "متوقفة") + "\n"
                + "المنفذ: " + dbPort + "\n"
                + "مستخدم root — كلمة المرور: " + (rp.isEmpty() ? "(فارغة)" : rp) + "\n"
                + "المقبس (socket):\n" + sockFile() + "\n\n"
                + "خادم PHP: " + (current != null ? "يعمل لمشروع «" + current.name + "» على المنفذ " + webPort : "متوقف") + "\n\n"
                + versions;
    }
}
