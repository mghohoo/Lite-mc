package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class MinecraftDownloader {
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private ArrayList<File> mDeclaredNatives;
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress
    private Set<String> mProcessedVersions;
    private Set<String> mScheduledPaths;

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    private boolean isOnline;

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion,
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(activity != null){
            isOnline = Tools.isOnline(activity);
            Tools.switchDemo(Tools.isDemoProfile(activity));

        } else {
            isOnline = false;
            Tools.switchDemo(true);
        }

        sExecutorService.execute(() -> {
            try {
                // Local accounts may download public game files while connected. This class
                // never refreshes an account; network state only controls file downloads.
                downloadGame(activity, version, realVersion);
                listener.onDownloadDone();
            }catch (Exception e) {
                listener.onDownloadFailed(e);
            }finally {
                ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
            }
        });
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mDeclaredNatives = new ArrayList<>();
        mProcessedVersions = new HashSet<>();
        mScheduledPaths = new HashSet<>();
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;
        mTotalFileCount = 0;
        mTotalSize = 0;
        mSourceJarFile = null;

        downloadAndProcessMetadata(activity, verInfo, versionName);

        // A fully cached version has no downloads. ArrayBlockingQueue rejects capacity 0.
        if(mScheduledDownloadTasks.isEmpty()) {
            ensureJarFileCopy();
            extractNatives(versionName);
            return;
        }

        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(mScheduledDownloadTasks.size(), false);
        // Minecraft versions contain hundreds of small libraries/assets. Four workers
        // leave fast mobile/Wi-Fi connections idle while each request waits on TLS and
        // disk I/O. Keep a bounded pool so low-end devices are not overwhelmed, but
        // scale up on devices that can actually use the available bandwidth.
        int downloaderThreads = Math.max(4,
                Math.min(8, Runtime.getRuntime().availableProcessors()));
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(downloaderThreads, downloaderThreads, 500,
                        TimeUnit.MILLISECONDS, taskQueue);

        // I have tried pre-filling the queue directly instead of doing this, but it didn't work.
        // What a shame.
        for(DownloaderTask scheduledTask : mScheduledDownloadTasks) downloaderPool.execute(scheduledTask);
        downloaderPool.shutdown();

        try {
            while (mDownloaderThreadException.get() == null &&
                    !downloaderPool.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                double speed = speedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                if(mUseFileCounter) reportProgressFileCounter(speed);
                else reportProgressSizeCounter(speed);
            }
            Exception thrownException = mDownloaderThreadException.get();
            if(thrownException != null) {
                throw thrownException;
            } else {
                ensureJarFileCopy();
                extractNatives(versionName);
            }
        }catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e; // Cancellation must not reach onDownloadDone().
        }finally {
            downloaderPool.shutdownNow();
        }
    }

    private void reportProgressFileCounter(double speed) {
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = mTotalFileCount == 0 ? 100 : (int)((dlFileCounter * 100L) / mTotalFileCount);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
    }

    private void reportProgressSizeCounter(double speed) {
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = mTotalSize == 0 ? 100 : (int)((dlFileSize * 100L) / mTotalSize);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.isFile() && mTargetJarFile.length() == mSourceJarFile.length()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount = mDeclaredNatives.size();

        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                R.string.newdl_extracting_native_libraries, 0, totalCount);

        File targetDirectory = new File(Tools.DIR_CACHE, "natives/"+versionName);
        FileUtils.ensureDirectory(targetDirectory);
        NativesExtractor nativesExtractor = new NativesExtractor(targetDirectory);
        int extractedCount = 0;
        for(File source : mDeclaredNatives) {
            // Optional Android JNA AARs may be absent; the bundled native is the fallback.
            if(!source.isFile()) continue;
            nativesExtractor.extractFromAar(source);
            extractedCount++;
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, extractedCount * 100 / totalCount,
                    R.string.newdl_extracting_native_libraries, extractedCount, totalCount);
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        if(!isOnline) {
            requireCachedFile(targetFile, 0, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null);
            return targetFile;
        }
        if(verInfo.sha1 == null && isUsableFile(targetFile, 0, null)) return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        if(!isOnline) {
            requireCachedFile(targetFile, assetIndex.size, assetIndex.sha1);
            return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
        }
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            DownloadMirror.downloadFileMirrored(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        if(!mProcessedVersions.add(versionName))
            throw new IOException("Cyclic version inheritance: " + versionName);
        File versionJsonFile;
        if(verInfo != null) versionJsonFile = downloadGameJson(verInfo);
        else versionJsonFile = createGameJsonPath(versionName);
        if(isUsableFile(versionJsonFile, 0, null))  {
            verInfo = Tools.GLOBAL_GSON.fromJson(Tools.read(versionJsonFile), JMinecraftVersionList.Version.class);
        } else {
            throw new IOException("Unable to read Version JSON for version " + versionName);
        }
        if(verInfo == null) throw new IOException("Invalid Version JSON for " + versionName);

        if(activity != null && !NewJREUtil.installNewJreIfNeeded(activity, verInfo)){
            throw new RuntimeException(activity.getString(R.string.exception_failed_to_unpack_jre17));
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        if(!mScheduledPaths.add(targetFile.getCanonicalPath())) return;
        FileUtils.ensureParentDirectory(targetFile);
        mTotalFileCount++;
        if(isUsableFile(targetFile, size, sha1)) {
            long cachedSize = size > 0 ? size : targetFile.length();
            mTotalSize += cachedSize;
            mProcessedSizeCounter.addAndGet(cachedSize);
            mProcessedFileCounter.incrementAndGet();
            return;
        }
        if(!isOnline) {
            if(skipIfFailed && mDeclaredNatives.contains(targetFile)) {
                mProcessedFileCounter.incrementAndGet();
                return;
            }
            throw missingCachedFile(targetFile);
        }
        // Unknown length is only a progress-display issue. Avoid a blocking HEAD request
        // for every Maven artifact, especially on cached or slow-network launches.
        if(size <= 0) {
            size = 0;
            mUseFileCounter = true;
        }else {
            mTotalSize += size;
        }
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, url, sha1, size, skipIfFailed)
        );
    }

    private static boolean isUsableFile(File file, long size, String sha1) {
        return file.isFile() && file.canRead() && file.length() > 0
                && (size <= 0 || file.length() == size)
                && (!Tools.isValidString(sha1) || Tools.compareSHA1(file, sha1));
    }

    private static IOException missingCachedFile(File file) {
        return new IOException("Missing or invalid cached file: " + file.getName()
                + ". Connect to the internet and download this version first.");
    }

    private static void requireCachedFile(File file, long size, String sha1) throws IOException {
        if(!isUsableFile(file, size, sha1)) throw missingCachedFile(file);
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        String[] originalNames = new String[dependentLibraries.length];
        for(int i = 0; i < dependentLibraries.length; i++) originalNames[i] = dependentLibraries[i].name;
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(int i = 0; i < dependentLibraries.length; i++) {
            DependentLibrary dependentLibrary = dependentLibraries[i];
            if(!isLibraryUsedByRuntime(dependentLibrary)) continue;
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    // Runtime compatibility substitutions update name/URL/hash, but upstream
                    // metadata may still carry the size of the original version.
                    size = dependentLibrary.name.equals(originalNames[i]) ? artifact.size : 0;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, false
            );
        }
    }

    private static boolean isLibraryUsedByRuntime(DependentLibrary library) {
        // Match the runtime classpath's current Tools.checkRules behavior. In particular,
        // macOS-only artifacts must not make a complete Android cache appear incomplete.
        if(library.rules == null) return true;
        for(JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : library.rules) {
            if("allow".equals(rule.action) && rule.os != null && "osx".equals(rule.os.name)) return false;
        }
        return true;
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[32768];
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(!downloadedHash.matches("[0-9a-fA-F]{40}")) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() throws IOException {
            File sha1CacheDir = new File(Tools.DIR_CACHE + "/sha1hashes");
            // Key by the complete artifact URL, not just its filename. Reuse immutable
            // Maven hashes on later launches instead of requesting them every time.
            File cacheFile = new File(sha1CacheDir,
                    UUID.nameUUIDFromBytes(mTargetUrl.getBytes(StandardCharsets.UTF_8)) + ".sha");
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) return;
            try (BufferedReader reader = new BufferedReader(new FileReader(cacheFile))) {
                String cached = reader.readLine();
                if(cached != null && cached.matches("[0-9a-fA-F]{40}")) {
                    mTargetSha1 = cached;
                    return;
                }
            }catch(FileNotFoundException ignored) {
                // No cached hash yet.
            }
            if(!isOnline) return;

            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            } catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
            }
            if (resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
                FileUtils.ensureDirectory(sha1CacheDir);
                try (FileWriter writeHash = new FileWriter(cacheFile)) {
                    Log.i("MinecraftDownloader", "Saving hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl) + " to " + cacheFile);
                    writeHash.write(resultHash);
                }
            }
        }

        @Override
        public void run() {
            try {
                runCatching();
            }catch (Exception e) {
                mDownloaderThreadException.set(e);
            }
        }

        private void runCatching() throws Exception {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Download cancelled");
            if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                // If we're downloading a library, try to get sha1 since it might be available as a file
                tryGetLibrarySha1();
            }
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                    // not for string validity
                if(isUsableFile(mTargetPath, mDownloadSize, null)) finishWithoutDownloading();
                else downloadFile();
            }
        }
        
        private void verifyFileSha1() throws Exception {
            if(isUsableFile(mTargetPath, mDownloadSize, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }
        
        private void downloadFile() throws Exception {
            if(!isOnline) throw missingCachedFile(mTargetPath);
            File partialFile = File.createTempFile("litemc-download-", ".part", mTargetPath.getParentFile());
            try {
                boolean verified = false;
                for(int attempt = 0; attempt < 5 && !verified; attempt++) {
                    if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Download cancelled");
                    mProcessedSizeCounter.addAndGet(-mLastCurr);
                    mLastCurr = 0;
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, partialFile,
                            getLocalBuffer(), this);
                    verified = isUsableFile(partialFile, mDownloadSize, mTargetSha1);
                }
                if(!verified) throw new IOException("Downloaded file failed verification: " + mTargetPath.getName());
                // Never expose a partially downloaded artifact as a valid cache entry.
                if(!partialFile.renameTo(mTargetPath)) {
                    // Some filesystems do not support rename-over-existing. Only replace
                    // an invalid cache file after its replacement has been verified.
                    if(!mTargetPath.isFile() || !mTargetPath.delete() || !partialFile.renameTo(mTargetPath))
                        throw new IOException("Unable to install downloaded file: " + mTargetPath.getName());
                }
            }catch (Exception e) {
                if(e instanceof InterruptedException) throw e;
                if(!mSkipIfFailed) throw e;
            }finally {
                if(partialFile.exists() && !partialFile.delete())
                    Log.w("MinecraftDownloader", "Unable to remove partial download: " + partialFile);
            }
            mProcessedFileCounter.incrementAndGet();
        }

        private void finishWithoutDownloading() {
            mProcessedFileCounter.incrementAndGet();
            mProcessedSizeCounter.addAndGet(mDownloadSize);
        }

        @Override
        public void updateProgress(int curr, int max) {
            int delta = curr - mLastCurr;
            mProcessedSizeCounter.addAndGet(delta);
            mInternetUsageCounter.addAndGet(delta);
            mLastCurr = curr;
        }
    }
}
