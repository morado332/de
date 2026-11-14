package com.morado.phppreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 1, REQ_SQL = 2, REQ_UPDATE = 3;
    private static final int M_SERVER = 1, M_LOGS = 2, M_RESTART = 3, M_ROOT = 4, M_ERRORS = 5, M_STOP = 6;

    interface Work { String run() throws Exception; }
    interface Done { void ok(String result); }

    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Rt rt;
    private TextView status;
    private ArrayAdapter<Project> adapter;
    private final List<Project> items = new ArrayList<>();
    private AlertDialog busy;
    private TextView busyText;
    private Project target;
    private boolean ready;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        rt = Rt.get(this);
        setTitle("معاينة مواقع PHP");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        Button imp = new Button(this);
        imp.setText("➕  إضافة موقع زبون جديد (ملف ZIP)");
        imp.setTextSize(17);
        imp.setOnClickListener(v -> pickFile(REQ_IMPORT, null));
        root.addView(imp, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        FrameLayout box = new FrameLayout(this);
        ListView list = new ListView(this);
        TextView empty = new TextView(this);
        empty.setText("لا توجد مواقع بعد.\n\nاضغط الزر بالأعلى واختر ملف ZIP يحتوي ملفات الموقع"
                + " (ويمكن أن يحتوي ملف قاعدة البيانات ‎.sql‎ أيضاً).\nسيقوم التطبيق بإنشاء قاعدة البيانات والمستخدم وكلمة المرور تلقائياً.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(16);
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        box.addView(list);
        box.addView(empty);
        list.setEmptyView(empty);
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        adapter = new ArrayAdapter<Project>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                Project pr = getItem(pos);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText("🌐  " + pr.name);
                t1.setTextSize(19);
                t1.setTypeface(Typeface.DEFAULT_BOLD);
                t2.setText("قاعدة البيانات: " + pr.dbName + "   •   المستخدم: " + pr.dbUser);
                v.setPadding(dp(8), dp(12), dp(8), dp(12));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> projectMenu(items.get(pos)));

        status.setText("جاري التجهيز...");
        bg("جاري تجهيز الخادم وقاعدة البيانات...\n(المرة الأولى قد تستغرق دقيقة)", () -> {
            rt.install();
            rt.startDb();
            return null;
        }, r -> {
            ready = true;
            refresh();
        });
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) new Thread(rt::stopAll).start();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refresh() {
        items.clear();
        items.addAll(Project.all(rt.projects));
        adapter.notifyDataSetChanged();
        String db = rt.dbRunning() ? "قاعدة البيانات تعمل ✅ (المنفذ " + rt.dbPort + ")" : "قاعدة البيانات متوقفة";
        status.setText(db + "   •   عدد المواقع: " + items.size());
    }

    private void showBusy(String msg) {
        if (busy != null && busy.isShowing()) {
            busyText.setText(msg);
            return;
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(dp(24), dp(24), dp(24), dp(24));
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(new ProgressBar(this));
        busyText = new TextView(this);
        busyText.setText(msg);
        busyText.setTextSize(16);
        busyText.setPadding(dp(16), 0, dp(16), 0);
        l.addView(busyText);
        busy = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
        busy.show();
    }

    private void hideBusy() {
        if (busy != null) {
            try {
                busy.dismiss();
            } catch (Exception ignored) {
            }
            busy = null;
        }
    }

    private void bg(String msg, Work w, Done d) {
        showBusy(msg);
        ex.execute(() -> {
            try {
                String r = w.run();
                ui.post(() -> {
                    if (isDestroyed()) return;
                    hideBusy();
                    refresh();
                    if (d != null) d.ok(r);
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    hideBusy();
                    refresh();
                    error(e);
                });
            }
        });
    }

    private void error(Throwable e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        new AlertDialog.Builder(this)
                .setTitle("حدث خطأ")
                .setMessage(m)
                .setPositiveButton("حسناً", null)
                .setNeutralButton("السجلات", (d, w) -> showLogs())
                .show();
    }

    private void message(String title, String msg) {
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextIsSelectable(true);
        t.setTextSize(15);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("حسناً", null).show();
    }

    private EditText field(LinearLayout form, String label, String value, boolean ltr) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(10), 0, 0);
        form.addView(l);
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        if (ltr) {
            e.setTextDirection(View.TEXT_DIRECTION_LTR);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        }
        form.addView(e);
        return e;
    }

    private LinearLayout form() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), dp(8));
        return f;
    }

    private AlertDialog formDialog(String title, LinearLayout form, String ok, Runnable onCancel, FormOk onOk) {
        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(ok, null)
                .setNegativeButton("إلغاء", (x, y) -> { if (onCancel != null) onCancel.run(); })
                .setCancelable(false)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String err = onOk.ok();
            if (err == null) d.dismiss();
            else Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }));
        d.show();
        return d;
    }

    interface FormOk { String ok(); }

    private static final String ID_RE = "[A-Za-z0-9_$\\-]{1,64}";

    private static String validateDb(String name, String user) {
        if (!name.matches(ID_RE)) return "اسم القاعدة: أحرف إنجليزية وأرقام و _ فقط";
        if (user.isEmpty() || user.length() > 80 || user.contains("'") || user.contains("\\"))
            return "اسم المستخدم غير صالح";
        if (user.equals(Rt.ADMIN_USER)) return "هذا الاسم محجوز للتطبيق";
        if (name.equalsIgnoreCase("mysql") || name.equalsIgnoreCase("information_schema")
                || name.equalsIgnoreCase("performance_schema") || name.equalsIgnoreCase("sys"))
            return "اسم القاعدة محجوز";
        return null;
    }

    // ------------------------------------------------------------------ file picking

    private void pickFile(int req, Project p) {
        if (!ready) {
            Toast.makeText(this, "انتظر حتى ينتهي التجهيز", Toast.LENGTH_SHORT).show();
            return;
        }
        target = p;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (req != REQ_SQL) {
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                    "application/x-zip", "application/octet-stream"});
        }
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            error(e);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            final Importer.Staged[] st = new Importer.Staged[1];
            bg("جاري فك الضغط وتحليل الموقع...", () -> {
                st[0] = Importer.stage(this, rt, uri);
                return null;
            }, r -> newProjectDialog(st[0]));
        } else if (req == REQ_SQL && target != null) {
            Project p = target;
            String name = Importer.displayName(this, uri);
            bg("جاري استيراد " + name + " ...", () -> {
                File f = Importer.copyToTmp(this, rt, uri, ".sql");
                try {
                    rt.startDb();
                    rt.importSql(p, f);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                return null;
            }, r -> message("تم ✅", "تم استيراد " + name + " إلى قاعدة " + p.dbName));
        } else if (req == REQ_UPDATE && target != null) {
            Project p = target;
            bg("جاري تحديث ملفات الموقع...", () -> {
                Importer.Staged s = Importer.stage(this, rt, uri);
                if (rt.current != null && rt.current.id.equals(p.id)) rt.stopPhp();
                File old = new File(p.dir, "www-old");
                Util.deleteRecursive(old);
                if (!p.www().renameTo(old)) throw new Exception("تعذر نقل الملفات القديمة");
                if (!s.www.renameTo(p.www())) {
                    //noinspection ResultOfMethodCallIgnored
                    old.renameTo(p.www());
                    throw new Exception("تعذر نسخ الملفات الجديدة");
                }
                Util.deleteRecursive(old);
                Util.deleteRecursive(s.dir);
                p.docRoot = s.docRoot;
                p.save();
                return null;
            }, r -> message("تم ✅", "تم تحديث ملفات «" + p.name + "».\nقاعدة البيانات بقيت كما هي."));
        }
    }

    // ------------------------------------------------------------------ new project

    private void newProjectDialog(Importer.Staged s) {
        ConfigDetector.Result c = s.cfg;
        String dbName = c.name != null ? c.name : (s.sqlDbName != null ? s.sqlDbName : Util.ident(s.suggestedName, 40));
        String dbUser = c.user != null ? c.user : Util.ident(dbName, 32);
        String dbPass = c.user != null ? (c.pass == null ? "" : c.pass) : Util.randomPass(12);

        LinearLayout f = form();
        TextView info = new TextView(this);
        StringBuilder sb = new StringBuilder();
        if (c.user != null || c.name != null) {
            sb.append("✅ تم العثور على بيانات قاعدة البيانات في: ").append(c.source)
                    .append("\nسيتم إنشاء القاعدة بنفس البيانات حتى يعمل الموقع بدون أي تعديل.");
        } else {
            sb.append("لم يتم العثور على إعدادات قاعدة بيانات. تم توليد بيانات جديدة — عدّلها لتطابق ملف الإعدادات في موقعك.");
        }
        sb.append("\nمجلد الصفحة الرئيسية: ").append(s.docRoot.isEmpty() ? "(الجذر)" : s.docRoot);
        info.setText(sb);
        info.setTextColor(Color.rgb(0x22, 0x66, 0x22));
        f.addView(info);

        EditText eName = field(f, "اسم الزبون / الموقع", s.suggestedName, false);
        EditText eDb = field(f, "اسم قاعدة البيانات", dbName, true);
        EditText eUser = field(f, "اسم المستخدم", dbUser, true);
        EditText ePass = field(f, "كلمة المرور (يمكن تركها فارغة)", dbPass, true);
        EditText eRoot = field(f, "مجلد الصفحة الرئيسية (اتركه فارغاً للجذر)", s.docRoot, true);

        Spinner sp = null;
        if (!s.sqlFiles.isEmpty()) {
            TextView l = new TextView(this);
            l.setText("ملف قاعدة البيانات للاستيراد:");
            l.setPadding(0, dp(10), 0, 0);
            f.addView(l);
            List<String> names = new ArrayList<>();
            for (File x : s.sqlFiles) names.add(Util.relative(s.www, x));
            names.add("بدون استيراد");
            sp = new Spinner(this);
            sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            f.addView(sp);
        } else {
            TextView l = new TextView(this);
            l.setText("لا يوجد ملف ‎.sql‎ داخل الـ ZIP — ستُنشأ قاعدة فارغة ويمكنك استيراد ملف SQL لاحقاً.");
            l.setPadding(0, dp(10), 0, 0);
            f.addView(l);
        }
        final Spinner spf = sp;
        formDialog("موقع جديد", f, "إنشاء", () -> new Thread(() -> Util.deleteRecursive(s.dir)).start(), () -> {
            String name = eName.getText().toString().trim();
            String db = eDb.getText().toString().trim();
            String user = eUser.getText().toString().trim();
            String pass = ePass.getText().toString();
            String docRoot = eRoot.getText().toString().trim().replaceAll("^/+|/+$", "");
            if (name.isEmpty()) return "اكتب اسم الزبون";
            String err = validateDb(db, user);
            if (err != null) return err;
            if (!docRoot.isEmpty() && !new File(s.www, docRoot).isDirectory()) return "المجلد غير موجود: " + docRoot;
            File sql = null;
            if (spf != null && spf.getSelectedItemPosition() < s.sqlFiles.size()) {
                sql = s.sqlFiles.get(spf.getSelectedItemPosition());
            }
            createProject(s, name, db, user, pass, docRoot, sql);
            return null;
        });
    }

    private void createProject(Importer.Staged s, String name, String db, String user, String pass, String docRoot, File sql) {
        Project p = new Project();
        p.id = "p" + System.currentTimeMillis();
        p.dir = new File(rt.projects, p.id);
        p.name = name;
        p.dbName = db;
        p.dbUser = user;
        p.dbPass = pass;
        p.docRoot = docRoot;
        p.created = System.currentTimeMillis();
        String sqlRel = sql == null ? null : Util.relative(s.www, sql);
        bg("جاري إنشاء الموقع وقاعدة البيانات...", () -> {
            rt.startDb();
            if (!s.dir.renameTo(p.dir)) throw new Exception("تعذر نقل ملفات الموقع");
            p.save();
            rt.createDb(p);
            String msg = "تم إنشاء الموقع وقاعدة البيانات بنجاح ✅\n\n";
            if (sqlRel != null) {
                try {
                    rt.importSql(p, new File(p.www(), sqlRel));
                    msg += "تم استيراد ملف قاعدة البيانات: " + sqlRel + " ✅\n\n";
                } catch (Exception e) {
                    msg += "⚠️ فشل استيراد " + sqlRel + ":\n" + e.getMessage() + "\n\n";
                }
            }
            return msg;
        }, r -> showInfo(p, r));
    }

    // ------------------------------------------------------------------ project actions

    private void showInfo(Project p, String header) {
        String text = (header == null ? "" : header) + rt.connectionInfo(p);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextIsSelectable(true);
        t.setTextSize(15);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(sv)
                .setPositiveButton("▶ عرض الموقع", (d, w) -> preview(p, "/"))
                .setNeutralButton("نسخ", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("db", rt.connectionInfo(p)));
                    Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void preview(Project p, String path) {
        if (!ready) {
            Toast.makeText(this, "انتظر حتى ينتهي التجهيز", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, PreviewActivity.class);
        i.putExtra("id", p.id);
        i.putExtra("path", path);
        startActivity(i);
    }

    private void projectMenu(Project p) {
        String[] opts = {
                "▶  عرض الموقع (شاشة كاملة)",
                "🔑  بيانات الاتصال بقاعدة البيانات",
                "🗄  إدارة قاعدة البيانات (Adminer)",
                "📥  استيراد ملف SQL",
                "✏️  تعديل الاسم / بيانات القاعدة",
                "🔄  تحديث ملفات الموقع (ZIP جديد)",
                "♻️  تفريغ قاعدة البيانات (إعادة إنشائها فارغة)",
                "🗑  حذف الموقع"
        };
        new AlertDialog.Builder(this).setTitle(p.name).setItems(opts, (d, which) -> {
            switch (which) {
                case 0: preview(p, "/"); break;
                case 1: showInfo(p, null); break;
                case 2: preview(p, rt.adminerPath(p)); break;
                case 3: pickFile(REQ_SQL, p); break;
                case 4: editProject(p); break;
                case 5: pickFile(REQ_UPDATE, p); break;
                case 6: confirm("تفريغ قاعدة «" + p.dbName + "»؟ سيتم حذف كل الجداول والبيانات.", () ->
                        bg("جاري إعادة إنشاء القاعدة...", () -> {
                            rt.startDb();
                            rt.dropDb(p, false);
                            rt.createDb(p);
                            return null;
                        }, r -> Toast.makeText(this, "تم ✅", Toast.LENGTH_SHORT).show()));
                    break;
                case 7: deleteProject(p); break;
                default: break;
            }
        }).show();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("نعم", (d, w) -> yes.run())
                .setNegativeButton("لا", null).show();
    }

    private void editProject(Project p) {
        LinearLayout f = form();
        EditText eName = field(f, "اسم الزبون / الموقع", p.name, false);
        EditText eDb = field(f, "اسم قاعدة البيانات", p.dbName, true);
        EditText eUser = field(f, "اسم المستخدم", p.dbUser, true);
        EditText ePass = field(f, "كلمة المرور", p.dbPass, true);
        EditText eRoot = field(f, "مجلد الصفحة الرئيسية", p.docRoot, true);
        TextView note = new TextView(this);
        note.setText("ملاحظة: تغيير اسم القاعدة يُنشئ قاعدة جديدة فارغة (القديمة تبقى).");
        note.setPadding(0, dp(10), 0, 0);
        f.addView(note);
        formDialog("تعديل", f, "حفظ", null, () -> {
            String name = eName.getText().toString().trim();
            String db = eDb.getText().toString().trim();
            String user = eUser.getText().toString().trim();
            String pass = ePass.getText().toString();
            String docRoot = eRoot.getText().toString().trim().replaceAll("^/+|/+$", "");
            if (name.isEmpty()) return "اكتب الاسم";
            String err = validateDb(db, user);
            if (err != null) return err;
            if (!docRoot.isEmpty() && !new File(p.www(), docRoot).isDirectory()) return "المجلد غير موجود: " + docRoot;
            p.name = name;
            p.dbName = db;
            p.dbUser = user;
            p.dbPass = pass;
            p.docRoot = docRoot;
            bg("جاري الحفظ...", () -> {
                rt.startDb();
                rt.createDb(p);
                p.save();
                return null;
            }, r -> showInfo(p, "تم الحفظ ✅\n\n"));
            return null;
        });
    }

    private void deleteProject(Project p) {
        LinearLayout f = form();
        TextView t = new TextView(this);
        t.setText("سيتم حذف ملفات «" + p.name + "» من الجهاز.");
        f.addView(t);
        CheckBox cb = new CheckBox(this);
        cb.setText("حذف قاعدة البيانات «" + p.dbName + "» أيضاً");
        cb.setChecked(true);
        f.addView(cb);
        new AlertDialog.Builder(this).setTitle("حذف الموقع").setView(f)
                .setPositiveButton("حذف", (d, w) -> bg("جاري الحذف...", () -> {
                    if (rt.current != null && rt.current.id.equals(p.id)) rt.stopPhp();
                    if (cb.isChecked()) {
                        boolean shared = false;
                        for (Project o : Project.all(rt.projects)) {
                            if (!o.id.equals(p.id) && o.dbUser.equals(p.dbUser)) shared = true;
                        }
                        rt.startDb();
                        rt.dropDb(p, !shared);
                    }
                    Util.deleteRecursive(p.dir);
                    return null;
                }, r -> Toast.makeText(this, "تم الحذف", Toast.LENGTH_SHORT).show()))
                .setNegativeButton("إلغاء", null).show();
    }

    // ------------------------------------------------------------------ options menu

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, M_SERVER, 0, "معلومات الخادم و root");
        m.add(0, M_LOGS, 0, "سجلات الأخطاء");
        m.add(0, M_ERRORS, 0, "إظهار أخطاء PHP في الصفحات").setCheckable(true).setChecked(rt.showErrors());
        m.add(0, M_ROOT, 0, "تغيير كلمة مرور root");
        m.add(0, M_RESTART, 0, "إعادة تشغيل قاعدة البيانات");
        m.add(0, M_STOP, 0, "إيقاف الخوادم والخروج");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) {
        switch (it.getItemId()) {
            case M_SERVER:
                message("معلومات الخادم", rt.serverInfo());
                return true;
            case M_LOGS:
                showLogs();
                return true;
            case M_ERRORS:
                boolean nv = !rt.showErrors();
                rt.setShowErrors(nv);
                it.setChecked(nv);
                Toast.makeText(this, nv ? "سيتم إظهار أخطاء PHP (للتطوير)" : "تم إخفاء أخطاء PHP (وضع العرض للزبون)",
                        Toast.LENGTH_LONG).show();
                return true;
            case M_ROOT: {
                LinearLayout f = form();
                EditText e = field(f, "كلمة مرور root الجديدة (يمكن تركها فارغة)", rt.rootPass(), true);
                formDialog("كلمة مرور root", f, "حفظ", null, () -> {
                    String np = e.getText().toString();
                    if (np.contains("'") || np.contains("\\")) return "لا تستخدم ' أو \\";
                    bg("جاري الحفظ...", () -> {
                        rt.startDb();
                        rt.setRootPassword(np);
                        return null;
                    }, r -> Toast.makeText(this, "تم ✅", Toast.LENGTH_SHORT).show());
                    return null;
                });
                return true;
            }
            case M_RESTART:
                bg("جاري إعادة التشغيل...", () -> {
                    rt.stopDb();
                    rt.startDb();
                    return null;
                }, r -> Toast.makeText(this, "تم ✅", Toast.LENGTH_SHORT).show());
                return true;
            case M_STOP:
                bg("جاري الإيقاف...", () -> {
                    rt.stopAll();
                    return null;
                }, r -> finish());
                return true;
            default:
                return super.onOptionsItemSelected(it);
        }
    }

    private void showLogs() {
        message("السجلات",
                "— PHP —\n" + Util.tail(new File(rt.logs, "php.log"), 3000)
                        + "\n\n— أخطاء PHP —\n" + Util.tail(new File(rt.logs, "php-errors.log"), 3000)
                        + "\n\n— MariaDB —\n" + Util.tail(new File(rt.logs, "mysql.err"), 3000));
    }
}
