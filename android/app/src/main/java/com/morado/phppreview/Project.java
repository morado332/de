package com.morado.phppreview;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One client website: its files (www/) and its own database + user. */
final class Project {
    String id;
    String name;
    String dbName;
    String dbUser;
    String dbPass = "";
    String docRoot = "";
    long created;
    File dir;

    File www() {
        return new File(dir, "www");
    }

    File docRootDir() {
        return docRoot == null || docRoot.isEmpty() ? www() : new File(www(), docRoot);
    }

    static Project load(File dir) throws Exception {
        JSONObject o = new JSONObject(Util.read(new File(dir, "project.json")));
        Project p = new Project();
        p.dir = dir;
        p.id = dir.getName();
        p.name = o.optString("name", p.id);
        p.dbName = o.optString("dbName");
        p.dbUser = o.optString("dbUser");
        p.dbPass = o.optString("dbPass", "");
        p.docRoot = o.optString("docRoot", "");
        p.created = o.optLong("created", dir.lastModified());
        return p;
    }

    void save() throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("dbName", dbName);
        o.put("dbUser", dbUser);
        o.put("dbPass", dbPass);
        o.put("docRoot", docRoot == null ? "" : docRoot);
        o.put("created", created);
        Util.write(new File(dir, "project.json"), o.toString(2));
    }

    static List<Project> all(File projectsDir) {
        List<Project> out = new ArrayList<>();
        File[] ds = projectsDir.listFiles();
        if (ds != null) {
            for (File d : ds) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                try {
                    out.add(load(d));
                } catch (Exception ignored) {
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    @Override
    public String toString() {
        return name;
    }
}
