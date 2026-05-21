package org.kissweb.rag;

import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reader for the multi-project RAG config file (rag-projects.json), which lives
 * next to application.ini in the Kiss backend dir.
 *
 *   { "projects": [
 *       { "name": "<schema-safe-id>", "roots": [...], "excludeGlobs": [...] },
 *       ...
 *   ] }
 *
 * The project name doubles as a PostgreSQL schema name, so it must match
 * the identifier rule [a-z][a-z0-9_]* — lowercase, no hyphens — so that
 * string-interpolating it into SQL is safe.
 *
 * Lives in precompiled/ so it is on the standard webapp classpath: both
 * backend Groovy files and the precompiled RAGMCPServer can import it directly.
 */
public final class ProjectRegistry {

    private static final Pattern NAME_RE = Pattern.compile("[a-z][a-z0-9_]*");

    /** Fallback excludes when a project entry omits excludeGlobs. */
    private static final List<String> DEFAULT_EXCLUDES = Collections.unmodifiableList(Arrays.asList(
            "**/node_modules", "**/node_modules/**",
            "**/work", "**/work/**",
            "**/target", "**/target/**",
            "**/.git", "**/.git/**",
            "**/*.jar",
            "**/tomcat", "**/tomcat/**",
            "**/build", "**/build/**"
    ));

    private ProjectRegistry() {
        // utility class
    }

    public static final class Project {
        public final String name;
        public final List<String> roots;
        public final List<String> excludeGlobs;

        Project(String name, List<String> roots, List<String> excludeGlobs) {
            this.name = name;
            this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
            this.excludeGlobs = Collections.unmodifiableList(new ArrayList<>(excludeGlobs));
        }

        public JSONObject toJSON() {
            JSONObject o = new JSONObject();
            o.put("name", name);
            JSONArray r = new JSONArray();
            for (String s : roots)
                r.put(s);
            o.put("roots", r);
            JSONArray e = new JSONArray();
            for (String s : excludeGlobs)
                e.put(s);
            o.put("excludeGlobs", e);
            return o;
        }
    }

    /** Absolute path of rag-projects.json (next to application.ini). */
    public static String configPath() {
        String appPath = MainServlet.getApplicationPath();
        if (appPath == null || appPath.isEmpty())
            throw new RuntimeException("MainServlet.getApplicationPath returned empty; Kiss not initialized?");
        return appPath.endsWith("/") ? appPath + "rag-projects.json" : appPath + "/rag-projects.json";
    }

    /** Read and validate the project list. Throws on malformed input. */
    public static List<Project> load() {
        Path p = Paths.get(configPath());
        if (!Files.exists(p))
            throw new RuntimeException("rag-projects.json not found at " + p);
        String content;
        try {
            content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + p + ": " + e.getMessage(), e);
        }
        JSONObject root = new JSONObject(content);
        JSONArray arr = root.getJSONArray("projects");
        if (arr.length() == 0)
            throw new RuntimeException("rag-projects.json has zero projects; need at least one");

        List<Project> out = new ArrayList<>(arr.length());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String name = obj.getString("name");
            if (!NAME_RE.matcher(name).matches())
                throw new RuntimeException("Invalid project name '" + name + "': must match [a-z][a-z0-9_]*");
            if (!seen.add(name))
                throw new RuntimeException("Duplicate project name '" + name + "' in rag-projects.json");

            JSONArray rootsArr = obj.getJSONArray("roots");
            if (rootsArr.length() == 0)
                throw new RuntimeException("Project '" + name + "' has zero roots");
            List<String> roots = new ArrayList<>(rootsArr.length());
            for (int j = 0; j < rootsArr.length(); j++)
                roots.add(rootsArr.getString(j));

            List<String> excludes;
            if (obj.has("excludeGlobs")) {
                JSONArray exArr = obj.getJSONArray("excludeGlobs");
                excludes = new ArrayList<>(exArr.length());
                for (int j = 0; j < exArr.length(); j++)
                    excludes.add(exArr.getString(j));
            } else {
                excludes = new ArrayList<>(DEFAULT_EXCLUDES);
            }
            out.add(new Project(name, roots, excludes));
        }
        return out;
    }

    /** Find one project by name; null if not configured. */
    public static Project get(String name) {
        if (name == null || name.isEmpty())
            return null;
        for (Project p : load()) {
            if (p.name.equals(name))
                return p;
        }
        return null;
    }

    /** Just the names. */
    public static List<String> listNames() {
        List<Project> all = load();
        List<String> names = new ArrayList<>(all.size());
        for (Project p : all)
            names.add(p.name);
        return names;
    }

    /** Defensive: confirm a name is well-formed before splicing it into SQL. */
    public static boolean isValidName(String name) {
        return name != null && NAME_RE.matcher(name).matches();
    }
}
