/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jvnet.hudson.update_center;

import hudson.util.VersionNumber;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.ClassParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    @Option(name = "-o", usage = "json file")
    public File output = new File("output.json");

    @Option(name = "-r", usage = "release history JSON file")
    public File releaseHistory = new File("release-history.json");

    /**
     * This file defines all the convenient symlinks in the form of
     * ./latest/PLUGINNAME.hpi.
     */
    @Option(name = "-latest", usage = "Build latest symlink directory")
    public File latest = new File("latest");

    /**
     * This option builds the directory image for the download server, which contains all the plugins
     * ever released to date in a directory structure.
     *
     * This is what we push into http://mirrors.jenkins-ci.org/ and from there it gets rsynced to
     * our mirror servers (some indirectly through OSUOSL.)
     *
     * TODO: it also currently produces war/ directory that we aren't actually using. Maybe remove?
     */
    @Option(name = "-download", usage = "Build mirrors.jenkins-ci.org layout")
    public File download = null;

    /**
     * This options builds update site. update-center.json(.html) that contains metadata,
     * latest symlinks, and download/ directories that are referenced from metadata and
     * redirects to the actual download server.
     */
    @Option(name = "-www", usage = "Build updates.jenkins-ci.org layout")
    public File www = null;

    /**
     * This options builds the http://updates.jenkins-ci.org/download files,
     * which consists of a series of index.html that lists available versions of plugins and cores.
     *
     * <p>
     * This is the URL space that gets referenced by update center metadata, and this is the entry point of all the
     * inbound download traffic. Actual *.hpi downloads are redirected to mirrors.jenkins-ci.org via Apache .htaccess.
     */
    @Option(name = "-www-download", usage = "Build updates.jenkins-ci.org/download directory")
    public File wwwDownload = null;

    @Option(name = "-nosymlinks", usage = "Don't add 'latest' symlinks, and copy files to download server layout instead of symlinks. (used together with '-download').")
    public boolean nosymlinks;

    @Option(name = "-index.html", usage = "Update the version number of the latest jenkins.war in jenkins-ci.org/index.html")
    public File indexHtml = null;

    @Option(name = "-latestCore.txt", usage = "Update the version number of the latest jenkins.war in latestCore.txt")
    public File latestCoreTxt = null;

    @Option(name = "-id", required = true, usage = "Uniquely identifies this update center. We recommend you use a dot-separated name like \"com.sun.wts.jenkins\". This value is not exposed to users, but instead internally used by Jenkins.")
    public String id;

    private String repository;

    @Option(name = "-repository", usage = "Alternate repository for plugins.")
    public void setRepository(String repo) {
        repository = repo;
        if (!repo.endsWith("/")) {
            repository += "/";
        }
    }

    @Option(name = "-remoteIndex", usage = "Nexus index file in repository.")
    public String remoteIndex;

    @Option(name = "-repositoryName", usage = "Name of repository. This is a value for n opition of nexus-indexer-cli.")
    public String repositoryName;

    @Option(name = "-directLink", usage = "links to the hpi in repository directly.")
    public boolean directLink;

    @Option(name = "-hpiDirectory", usage = "Directory containing HPI files.")
    public String hpiDirectoryPath;

    @Option(name = "-includeSnapshots", usage = "Include SNAPSHOT builds.")
    public boolean includeSnapshots;

    @Option(name = "-nowiki", usage = "Do not refer wiki.jenkins-ci.org to retrieve plugin information.")
    public boolean nowiki;

    @Option(name = "-maxPlugins", usage = "For testing purposes. Limit the number of plugins managed to the specified number.")
    public Integer maxPlugins;

    @Option(name = "-connectionCheckUrl",
            usage = "Specify an URL of the 'always up' server for performing connection check.")
    public String connectionCheckUrl;

    @Option(name = "-pretty", usage = "Pretty-print the result")
    public boolean prettyPrint;

    @Option(name = "-cap", usage = "Cap the version number and only report plugins that are compatible with ")
    public String capPlugin = null;

    @Option(name = "-capCore", usage = "Cap the version number and only core that's compatible with. Defaults to -cap")
    public String capCore = null;

    @Option(name = "-pluginCount.txt", usage = "Report a number of plugins in a simple text file")
    public File pluginCountTxt = null;

    @Option(name = "-experimental-only", usage = "Include alpha/beta releases only")
    public boolean experimentalOnly;

    @Option(name = "-no-experimental", usage = "Exclude alpha/beta releases")
    public boolean noExperimental;

    public Signer signer = new Signer();

    public static final String EOL = System.getProperty("line.separator");

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            System.out.println(arg);
        }
        System.exit(new Main().run(args));
    }

    public int run(String[] args) throws Exception {
        CmdLineParser p = new CmdLineParser(this);
        new ClassParser().parse(signer, p);
        try {
            p.parseArgument(args);

            if (www != null) {
                prepareStandardDirectoryLayout();
            }

            run();
            return 0;
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            p.printUsage(System.err);
            return 1;
        }
    }

    private String getCapCore() {
        if (capCore != null) {
            return capCore;
        }
        return capPlugin;
    }

    private void prepareStandardDirectoryLayout() {
        output = new File(www, "update-center.json");
        latest = new File(www, "latest");
        indexHtml = new File(www, "index.html");
        releaseHistory = new File(www, "release-history.json");
        latestCoreTxt = new File(www, "latestCore.txt");
    }

    public void run() throws Exception {

        MavenRepository repo = createRepository();

        LatestLinkBuilder latest = createHtaccessWriter();

        JSONObject ucRoot = buildUpdateCenterJson(repo, latest);
        writeToFile(updateCenterPostCallJson(ucRoot), output);
        writeToFile(updateCenterPostMessageHtml(ucRoot), new File(output.getPath() + ".html"));

        JSONObject rhRoot = buildFullReleaseHistory(repo);
        String rh = prettyPrintJson(rhRoot);
        writeToFile(rh, releaseHistory);

        latest.close();
    }

    String updateCenterPostCallJson(JSONObject ucRoot) {
        return "updateCenter.post(" + EOL + prettyPrintJson(ucRoot) + EOL + ");";
    }

    String updateCenterPostMessageHtml(JSONObject ucRoot) {
        // needs the DOCTYPE to make JSON.stringify work on IE8
        return "\uFEFF<!DOCTYPE html><html><head><meta http-equiv='Content-Type' content='text/html;charset=UTF-8' /></head><body><script>window.onload = function () { window.parent.postMessage(JSON.stringify("
                + EOL + prettyPrintJson(ucRoot) + EOL + "),'*'); };</script></body></html>";
    }

    private LatestLinkBuilder createHtaccessWriter() throws IOException {

        latest.mkdirs();
        return new LatestLinkBuilder(latest);
    }

    private JSONObject buildUpdateCenterJson(MavenRepository repo, LatestLinkBuilder latest) throws Exception {
        JSONObject root = new JSONObject();
        root.put("updateCenterVersion", "1"); // we'll bump the version when we make incompatible changes
        JSONObject core = buildCore(repo, latest);
        if (core != null) {
            root.put("core", core);
        }
        root.put("plugins", buildPlugins(repo, latest));
        root.put("id", id);
        if (connectionCheckUrl != null) {
            root.put("connectionCheckUrl", connectionCheckUrl);
        }

        if (signer.isConfigured()) {
            signer.sign(root);
        }

        return root;
    }

    private static void writeToFile(String string, final File file) throws IOException {
        PrintWriter rhpw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
        rhpw.print(string);
        rhpw.close();
    }

    private String prettyPrintJson(JSONObject json) {
        return prettyPrint ? json.toString(2) : json.toString();
    }

    protected MavenRepository createRepository() throws Exception {
        MavenRepository repo;
        if (hpiDirectoryPath != null)
        {
            repo = createLocalDirectoryRepository();
        }
        else
        {
            repo = DefaultMavenRepositoryBuilder.createStandardInstance(repositoryName, repository, remoteIndex, directLink, includeSnapshots);
        }

        if (maxPlugins != null) {
            repo = new TruncatedMavenRepository(repo, maxPlugins);
        }
        if (capPlugin != null || getCapCore() != null) {
            VersionNumber vp = capPlugin == null ? ANY_VERSION : new VersionNumber(capPlugin);
            VersionNumber vc = getCapCore() == null ? ANY_VERSION : new VersionNumber(getCapCore());
            repo = new VersionCappedMavenRepository(repo, vp, vc);
        }
        if (experimentalOnly) {
            repo = new AlphaBetaOnlyRepository(repo, false);
        }
        if (noExperimental) {
            repo = new AlphaBetaOnlyRepository(repo, true);
        }

        return repo;
    }

    protected MavenRepository createLocalDirectoryRepository() throws Exception
    {
        // Parameter validation
        if (hpiDirectoryPath == null)
        {
            throw new Exception("-hpiDirectoryPath must be specified.");
        }
        File hpiDirectory = new File(hpiDirectoryPath);
        if (!hpiDirectory.isDirectory())
        {
            throw new Exception(String.format("Not a directory: %s", hpiDirectoryPath));
        }

        if (repository == null)
        {
            throw new Exception("-repository must be specified when using hpiDirectoryPath.");
        }

        return new LocalDirectoryRepository(hpiDirectory, new URL(repository), includeSnapshots, download);
    }

    /**
     * Build JSON for the plugin list.
     *
     * @param repository
     * @param latest
     */
    protected JSONObject buildPlugins(MavenRepository repository, LatestLinkBuilder latest) throws Exception {
        ConfluencePluginList cpl;

        if (nowiki) {
            cpl = new NoConfluencePluginList();
        } else {
            cpl = new ConfluencePluginList();
        }
        cpl.initialize();

        int total = 0;

        JSONObject plugins = new JSONObject();
        for (PluginHistory hpi : repository.listHudsonPlugins()) {
            try {
                System.out.println(hpi.artifactId);

                Plugin plugin = new Plugin(hpi, cpl);
                if (plugin.isDeprecated()) {
                    System.out.println("=> Plugin is deprecated.. skipping.");
                    continue;
                }

                System.out.println(
                        plugin.page != null ? "=> " + plugin.page.getTitle() : "** No wiki page found");
                JSONObject json = plugin.toJSON();
                System.out.println("=> " + json);
                plugins.put(plugin.artifactId, json);
                latest.add(plugin.artifactId + ".hpi", plugin.latest.getURL().getPath());

                if (download != null) {
                    for (HPI v : hpi.artifacts.values()) {
                        stage(v, new File(download, v.getRelativePath()));
                    }
                    if (!hpi.artifacts.isEmpty() && !nosymlinks) {
                        createLatestSymlink(hpi, plugin.latest);
                    }
                }

                if (wwwDownload != null) {
                    String permalink = String.format("/latest/%s.hpi", plugin.artifactId);
                    buildIndex(new File(wwwDownload, "plugins/" + hpi.artifactId), hpi.artifactId, hpi.artifacts.values(), permalink);
                }

                total++;
            } catch (IOException e) {
                e.printStackTrace();
                // move on to the next plugin
            }
        }

        if (pluginCountTxt != null) {
            FileUtils.writeStringToFile(pluginCountTxt, String.valueOf(total));
        }
        System.out.println("Total " + total + " plugins listed.");
        return plugins;
    }

    /**
     * Generates symlink to the latest version.
     */
    protected void createLatestSymlink(PluginHistory hpi, HPI latest) throws InterruptedException, IOException {
        File dir = new File(download, "plugins/" + hpi.artifactId);
        new File(dir, "latest").delete();

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("ln", "-s", latest.getShortVersion(), "latest");
        pb.directory(dir);
        int r = pb.start().waitFor();
        if (r != 0) {
            throw new IOException("ln failed: " + r);
        }
    }

    /**
     * Stages an artifact into the specified location.
     */
    protected void stage(MavenArtifact a, File dst) throws IOException, InterruptedException {
        File src = a.resolve();
        if (dst.exists() && dst.lastModified() == src.lastModified() && dst.length() == src.length()) {
            return; // already up to date
        }

        if (nosymlinks) {
            dst.getParentFile().mkdirs();
            FileUtils.copyFile(src, dst);
        } else {
            // TODO: directory and the war file should have the release timestamp
            dst.getParentFile().mkdirs();

            ProcessBuilder pb = new ProcessBuilder();
            pb.command("ln", "-f", src.getAbsolutePath(), dst.getAbsolutePath());
            if (pb.start().waitFor() != 0) {
                throw new IOException("ln failed");
            }
        }
    }

    /**
     * Build JSON for the release history list.
     *
     * @param repo
     */
    protected JSONObject buildFullReleaseHistory(MavenRepository repo) throws Exception {
        JSONObject rhRoot = new JSONObject();
        rhRoot.put("releaseHistory", buildReleaseHistory(repo));
        return rhRoot;
    }

    protected JSONArray buildReleaseHistory(MavenRepository repository) throws Exception {
        ConfluencePluginList cpl;
        if (nowiki) {
            cpl = new NoConfluencePluginList();
        } else {
            cpl = new ConfluencePluginList();
        }
        cpl.initialize();

        JSONArray releaseHistory = new JSONArray();
        for (Map.Entry<Date, Map<String, HPI>> relsOnDate : repository.listHudsonPluginsByReleaseDate().entrySet()) {
            String relDate = MavenArtifact.getDateFormat().format(relsOnDate.getKey());
            System.out.println("Releases on " + relDate);

            JSONArray releases = new JSONArray();

            for (Map.Entry<String, HPI> rel : relsOnDate.getValue().entrySet()) {
                HPI h = rel.getValue();
                JSONObject o = new JSONObject();
                try {
                    Plugin plugin = new Plugin(h, cpl);

                    String title = plugin.getTitle();
                    if ((title == null) || (title.equals(""))) {
                        title = h.artifact.artifactId;
                    }

                    o.put("title", title);
                    o.put("gav", h.artifact.groupId + ':' + h.artifact.artifactId + ':' + h.artifact.version);
                    o.put("timestamp", h.getTimestamp());
                    o.put("wiki", plugin.getWiki());

                    System.out.println("\t" + title + ":" + h.version);
                } catch (IOException e) {
                    System.out.println("Failed to resolve plugin " + h.artifact.artifactId + " so using defaults");
                    o.put("title", h.artifact.artifactId);
                    o.put("wiki", "");
                }

                PluginHistory history = h.history;
                if (history.latest() == h) {
                    o.put("latestRelease", true);
                }
                if (history.first() == h) {
                    o.put("firstRelease", true);
                }
                o.put("version", h.version);

                releases.add(o);
            }
            JSONObject d = new JSONObject();
            d.put("date", relDate);
            d.put("releases", releases);
            releaseHistory.add(d);
        }

        return releaseHistory;
    }

    private void buildIndex(File dir, String title, Collection<? extends MavenArtifact> versions, String permalink) throws IOException {
        List<MavenArtifact> list = new ArrayList<MavenArtifact>(versions);
        Collections.sort(list, new Comparator<MavenArtifact>() {
            public int compare(MavenArtifact o1, MavenArtifact o2) {
                return -o1.getVersion().compareTo(o2.getVersion());
            }
        });

        IndexHtmlBuilder index = new IndexHtmlBuilder(dir, title);
        index.add(permalink, "permalink to the latest");
        for (MavenArtifact a : list) {
            index.add(a);
        }
        index.close();
    }

    /**
     * Creates a symlink.
     */
    private void ln(String from, File to) throws InterruptedException, IOException {
        to.getParentFile().mkdirs();

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("ln", "-sf", from, to.getAbsolutePath());
        if (pb.start().waitFor() != 0) {
            throw new IOException("ln failed");
        }
    }

    /**
     * Identify the latest core, populates the htaccess redirect file, optionally download the core wars and build the
     * index.html
     *
     * @return the JSON for the core Jenkins
     */
    protected JSONObject buildCore(MavenRepository repository, LatestLinkBuilder redirect) throws Exception {
        TreeMap<VersionNumber, HudsonWar> wars = repository.getHudsonWar();
        if (wars.isEmpty()) {
            return null;
        }

        HudsonWar latest = wars.get(wars.firstKey());
        JSONObject core = latest.toJSON("core");
        System.out.println("core\n=> " + core);

        redirect.add("jenkins.war", latest.getURL().getPath());

        if (latestCoreTxt != null) {
            writeToFile(latest.getVersion().toString(), latestCoreTxt);
        }

        if (download != null) {
            // build the download server layout
            for (HudsonWar w : wars.values()) {
                stage(w, new File(download, "war/" + w.version + "/" + w.getFileName()));
            }
        }

        if (wwwDownload != null) {
            buildIndex(new File(wwwDownload, "war/"), "jenkins.war", wars.values(), "/latest/jenkins.war");
        }

        return core;
    }

    private static final VersionNumber ANY_VERSION = new VersionNumber("999.999");
}
