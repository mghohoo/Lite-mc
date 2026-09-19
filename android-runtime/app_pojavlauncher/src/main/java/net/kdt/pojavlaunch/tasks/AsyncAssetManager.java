package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.Architecture.archAsString;
import static net.kdt.pojavlaunch.Architecture.archAsStringAndroid;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AsyncAssetManager {
    // MultiRTUtils clears UNPACK_RUNTIME before postPrepare. A separate outer task
    // keeps waitUntilDone blocked until installation, post-processing and checks finish.
    private static final Phase RUNTIME = new Phase("litemc_initial_runtime");
    private static final Phase COMPONENTS = new Phase(ProgressLayout.EXTRACT_COMPONENTS);
    private static final Phase SINGLE_FILES = new Phase(ProgressLayout.EXTRACT_SINGLE_FILES);
    private static final Object STATE_LOCK = new Object();

    private AsyncAssetManager() {}

    private static final class Phase {
        final String key;
        boolean started, running, completed;
        IOException failure;
        final List<File> requiredFiles = new ArrayList<>();
        Phase(String key) { this.key = key; }
    }

    private interface Preparation { void run() throws IOException; }

    private static void schedule(Phase phase, Preparation preparation) {
        synchronized(STATE_LOCK) {
            if(phase.running) return;
            if(phase.completed && phase.failure == null && filesPresent(phase.requiredFiles)) return;
            phase.started = true;
            phase.running = true;
            phase.completed = false;
            phase.failure = null;
            phase.requiredFiles.clear();
        }
        // Register on the caller thread, before execute: callers can immediately wait.
        ProgressLayout.setProgress(phase.key, 0);
        try {
            sExecutorService.execute(() -> {
                IOException failure = null;
                boolean finished = false;
                try {
                    preparation.run();
                    finished = true;
                } catch(Exception e) {
                    failure = new IOException("Launcher preparation failed: " + phase.key, e);
                    Log.e("AsyncAssetManager", failure.getMessage(), e);
                } finally {
                    synchronized(STATE_LOCK) {
                        phase.failure = !finished && failure == null
                                ? new IOException("Preparation aborted: " + phase.key) : failure;
                        phase.running = false;
                        phase.completed = true;
                    }
                    if(phase == RUNTIME) ProgressLayout.clearProgress(ProgressLayout.UNPACK_RUNTIME);
                    ProgressLayout.clearProgress(phase.key);
                }
            });
        } catch(RuntimeException e) {
            synchronized(STATE_LOCK) {
                phase.failure = new IOException("Unable to schedule preparation: " + phase.key, e);
                phase.running = false;
                phase.completed = true;
            }
            ProgressLayout.clearProgress(phase.key);
        }
    }

    /** Call after ProgressKeeper.waitUntilDone, before making the launcher ready. */
    public static void assertReady() throws IOException {
        IOException combined = new IOException("Launcher runtime components are not ready");
        boolean failed = false;
        synchronized(STATE_LOCK) {
            for(Phase phase : new Phase[]{RUNTIME, COMPONENTS, SINGLE_FILES}) {
                if(!phase.started || phase.running || !phase.completed) {
                    combined.addSuppressed(new IOException("Preparation is incomplete: " + phase.key));
                    failed = true;
                }
                if(phase.failure != null) {
                    combined.addSuppressed(phase.failure);
                    failed = true;
                }
                for(File file : phase.requiredFiles) {
                    if(!usableFile(file)) {
                        combined.addSuppressed(new IOException("Missing or empty component: " + file.getName()));
                        failed = true;
                    }
                }
            }
        }
        if(failed) throw combined;
    }

    /** Install bundled Java 8 if present; an APK without Java bundles is valid. */
    public static void unpackRuntime(AssetManager am) {
        schedule(RUNTIME, () -> {
            String[] bundledFiles = am.list("components/jre");
            if(bundledFiles == null || bundledFiles.length == 0) return;
            if(!Arrays.asList(bundledFiles).contains("version"))
                throw new IOException("Bundled Java runtime has no version metadata");

            String bundledVersion;
            try(InputStream input = am.open("components/jre/version")) {
                bundledVersion = Tools.read(input);
            }
            if(bundledVersion.trim().isEmpty()) throw new IOException("Bundled Java runtime version is empty");
            String currentVersion = MultiRTUtils.readInternalRuntimeVersion("Internal");
            String externalJava8 = MultiRTUtils.getExactJreName(8);
            if(currentVersion == null && externalJava8 != null && !"Internal".equals(externalJava8)) return;

            // The game process also calls this method. A healthy installed runtime is
            // checked and reused, without copying or unpacking its archives again.
            if(bundledVersion.equals(currentVersion)) {
                try {
                    verifyInternalRuntime();
                    return;
                } catch(IOException ignored) {
                    synchronized(STATE_LOCK) { RUNTIME.requiredFiles.clear(); }
                }
            }
            try(InputStream universal = am.open("components/jre/universal.tar.xz");
                InputStream platform = am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz")) {
                MultiRTUtils.installRuntimeNamedBinpack(universal, platform, "Internal", bundledVersion);
                MultiRTUtils.postPrepare("Internal");
                verifyInternalRuntime();
            }
        });
    }

    private static void verifyInternalRuntime() throws IOException {
        Runtime runtime = MultiRTUtils.forceReread("Internal");
        if(runtime == null || runtime.javaVersion != 8) throw new IOException("Bundled Java 8 metadata is invalid");
        File home = new File(Tools.MULTIRT_HOME, "Internal");
        rememberRequired(RUNTIME, new File(home, "release"));
        rememberRequired(RUNTIME, new File(home, "lib/rt.jar"));
        rememberRequired(RUNTIME, findRuntimeFile(home, "libjvm.so", 4));
        rememberRequired(RUNTIME, findRuntimeFile(home, "libjli.so", 4));
    }

    private static File findRuntimeFile(File directory, String name, int depth) throws IOException {
        File exact = new File(directory, name);
        if(usableFile(exact)) return exact;
        if(depth > 0) {
            File[] children = directory.listFiles();
            if(children != null) for(File child : children) {
                if(!child.isDirectory()) continue;
                try { return findRuntimeFile(child, name, depth - 1); }
                catch(IOException ignored) { }
            }
        }
        throw new IOException("Bundled Java runtime is missing " + name);
    }

    public static void unpackSingleFiles(Context ctx) {
        schedule(SINGLE_FILES, () -> {
            copyDefault(ctx, "options.txt", Tools.DIR_GAME_NEW);
            File defaultControls = new File(Tools.CTRLDEF_FILE);
            if(!usableFile(defaultControls)) {
                Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, true);
            } else {
                try(InputStream input = ctx.getAssets().open("default.json")) {
                    String sha1 = new String(org.apache.commons.codec.binary.Hex.encodeHex(
                            org.apache.commons.codec.digest.DigestUtils.sha1(input)));
                    // Preserve user-edited controls; keep the bundled update separately.
                    if(!Tools.compareSHA1(defaultControls, sha1))
                        Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, "new_default.json", true);
                }
            }
            rememberRequired(SINGLE_FILES, defaultControls);
            copyDefault(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW);
            copyDefault(ctx, "resolv.conf", Tools.DIR_DATA);
        });
    }

    private static void copyDefault(Context ctx, String name, String directory) throws IOException {
        File target = new File(directory, name);
        Tools.copyAssetFile(ctx, name, directory, !usableFile(target));
        rememberRequired(SINGLE_FILES, target);
    }

    public static void unpackComponents(Context ctx) {
        schedule(COMPONENTS, () -> {
            unpackComponent(ctx, "caciocavallo", false);
            unpackComponent(ctx, "caciocavallo17", false);
            unpackLwjglNatives(ctx);
            unpackComponent(ctx, "lwjgl3/3.3.3", false);
            unpackComponent(ctx, "lwjgl3/3.4.1", false);
            unpackComponent(ctx, "security", true);
            unpackComponent(ctx, "arc_dns_injector", true);
            unpackComponent(ctx, "MioLibPatcher", true);
            unpackComponent(ctx, "forge_installer", true);
        });
    }

    private static void unpackLwjglNatives(Context ctx) throws IOException {
        AssetManager am = ctx.getAssets();
        String arch = archAsStringAndroid(getDeviceArchitecture());
        for(String version : new String[]{"3.3.3", "3.4.1"}) {
            File versionFile = new File(Tools.DIR_GAME_HOME, "lwjgl3/" + version + "/version");
            String assetDir = "components/lwjgl-" + version + "-natives/" + arch;
            File destination = new File(Tools.DIR_DATA, "lwjgl-" + version + "-natives/" + arch);
            String[] files = requiredAssetList(am, assetDir);
            boolean matchingVersion = versionMatches(am, "components/lwjgl3/" + version + "/version", versionFile);
            for(String name : files) {
                File target = new File(destination, name);
                if(!matchingVersion || !usableFile(target))
                    Tools.copyAssetFile(ctx, assetDir + "/" + name, destination.getAbsolutePath(), true);
                rememberRequired(COMPONENTS, target);
            }
        }
    }

    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        File root = new File(privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME);
        File destination = new File(root, component);
        if(!destination.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Unsafe component directory");
        String assetDir = "components/" + component;
        String[] files = requiredAssetList(am, assetDir);
        File versionFile = new File(destination, "version");
        boolean matches = versionMatches(am, assetDir + "/version", versionFile);
        if(!matches && destination.exists()) FileUtils.deleteDirectory(destination);
        if(!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Cannot create " + component);
        // Commit the version marker last. A failed update must not look fully installed.
        for(String name : files) {
            if("version".equals(name)) continue;
            File target = new File(destination, name);
            if(!matches || !usableFile(target)) Tools.copyAssetFile(ctx, assetDir + "/" + name, destination.getAbsolutePath(), true);
            rememberRequired(COMPONENTS, target);
        }
        if(!matches) Tools.copyAssetFile(ctx, assetDir + "/version", destination.getAbsolutePath(), true);
        rememberRequired(COMPONENTS, versionFile);
    }

    private static String[] requiredAssetList(AssetManager am, String path) throws IOException {
        String[] files = am.list(path);
        if(files == null || files.length == 0) throw new IOException("APK component is missing: " + path);
        return files;
    }

    private static boolean versionMatches(AssetManager am, String assetPath, File versionFile) throws IOException {
        if(!usableFile(versionFile)) return false;
        try(InputStream bundled = am.open(assetPath); InputStream installed = new FileInputStream(versionFile)) {
            return Tools.read(bundled).equals(Tools.read(installed));
        }
    }

    private static boolean usableFile(File file) {
        return file != null && file.isFile() && file.canRead() && file.length() > 0;
    }

    private static boolean filesPresent(List<File> files) {
        for(File file : files) if(!usableFile(file)) return false;
        return true;
    }

    private static void rememberRequired(Phase phase, File file) throws IOException {
        if(!usableFile(file)) throw new IOException("Missing or empty component: " + file.getName());
        synchronized(STATE_LOCK) { phase.requiredFiles.add(file); }
    }
}
