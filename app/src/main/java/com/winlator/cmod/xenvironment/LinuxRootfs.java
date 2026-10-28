package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.StreamUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Debian arm64 rootfs used by the Linux mode, run through PRoot (see PHASE2_PLAN.md).
 * Lives outside the imagefs because ImageFsInstaller wipes everything there except home/.
 */
public class LinuxRootfs {
    private static final String TAG = "LinuxRootfs";
    private static final String VERSION_FILE = "etc/winlator-rootfs-version";

    public interface ProgressListener {
        void onProgress(String message);
    }

    private final File rootDir;
    private final File tmpDir;

    private LinuxRootfs(File baseDir) {
        rootDir = new File(baseDir, "rootfs");
        tmpDir = new File(baseDir, "tmp");
    }

    public static LinuxRootfs find(Context context) {
        return new LinuxRootfs(new File(context.getFilesDir(), "linux"));
    }

    public File getRootDir() {
        return rootDir;
    }

    /** Scratch directory for PRoot itself (PROOT_TMP_DIR), outside the guest rootfs. */
    public File getTmpDir() {
        if (!tmpDir.isDirectory()) tmpDir.mkdirs();
        return tmpDir;
    }

    public boolean isInstalled() {
        return new File(rootDir, VERSION_FILE).isFile();
    }

    public int getVersion() {
        File versionFile = new File(rootDir, VERSION_FILE);
        if (!versionFile.isFile()) return 0;
        try {
            return Integer.parseInt(FileUtils.readLines(versionFile).get(0).trim());
        }
        catch (Exception e) {
            return 0;
        }
    }

    /**
     * Installs the rootfs from a linux-rootfs-N.tar.zst archive. When expectedSha256 is given the
     * archive is verified first. Extracts into a staging directory and swaps it in at the end, so
     * a failed install never leaves a half-written rootfs behind.
     */
    public boolean install(File archive, String expectedSha256, ProgressListener listener) {
        if (!archive.isFile()) {
            report(listener, "Archive not found: " + archive);
            return false;
        }

        if (expectedSha256 != null && !expectedSha256.isEmpty()) {
            report(listener, "Verifying SHA-256...");
            String actual = sha256(archive);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                report(listener, "SHA-256 mismatch: expected " + expectedSha256 + ", got " + actual);
                return false;
            }
        }

        File parent = rootDir.getParentFile();
        File stagingDir = new File(parent, "rootfs.installing");
        FileUtils.delete(stagingDir);
        stagingDir.mkdirs();

        report(listener, "Extracting " + archive.getName() + "...");
        if (!extract(archive, stagingDir, listener)) {
            FileUtils.delete(stagingDir);
            return false;
        }

        FileUtils.delete(rootDir);
        if (!stagingDir.renameTo(rootDir)) {
            report(listener, "Could not move the rootfs into place");
            return false;
        }
        report(listener, "Installed rootfs version " + getVersion());
        return true;
    }

    /**
     * Unlike TarCompressorUtils, keeps each entry's permission bits (they matter inside a Linux
     * rootfs), never chmods through symlinks and handles hard links by copying.
     */
    private static boolean extract(File archive, File destination, ProgressListener listener) {
        String destPath;
        try {
            destPath = destination.getCanonicalPath() + File.separator;
        }
        catch (IOException e) {
            return false;
        }

        List<TarArchiveEntry> hardLinks = new ArrayList<>();
        int count = 0;
        try (InputStream in = new ZstdCompressorInputStream(new BufferedInputStream(new FileInputStream(archive), StreamUtils.BUFFER_SIZE));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                File file = new File(destination, entry.getName());
                // Refuse entries that would land outside the destination
                if (!(file.getCanonicalPath() + File.separator).startsWith(destPath) && !file.getCanonicalPath().equals(destination.getCanonicalPath())) {
                    Log.w(TAG, "Skipping unsafe entry " + entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    file.mkdirs();
                    FileUtils.chmod(file, (entry.getMode() & 0777) | 0700);
                }
                else if (entry.isSymbolicLink()) {
                    file.getParentFile().mkdirs();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                }
                else if (entry.isLink()) {
                    hardLinks.add(entry);
                }
                else if (entry.isFile()) {
                    file.getParentFile().mkdirs();
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                        if (!StreamUtils.copy(tar, out)) return false;
                    }
                    FileUtils.chmod(file, (entry.getMode() & 0777) | 0600);
                }
                // Device nodes, FIFOs etc. are skipped: PRoot binds the host /dev

                if (++count % 5000 == 0) report(listener, "Extracted " + count + " entries");
            }
        }
        catch (IOException e) {
            Log.e(TAG, "Extraction failed", e);
            report(listener, "Extraction failed: " + e.getMessage());
            return false;
        }

        // Apps can't create hard links on Android, so materialize them as copies
        for (TarArchiveEntry link : hardLinks) {
            File target = new File(destination, link.getLinkName());
            File file = new File(destination, link.getName());
            file.getParentFile().mkdirs();
            if (!FileUtils.copy(target, file)) Log.w(TAG, "Could not copy hard link " + link.getName());
            FileUtils.chmod(file, (link.getMode() & 0777) | 0600);
        }

        report(listener, "Extracted " + count + " entries");
        return true;
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format(Locale.ENGLISH, "%02x", b));
            return sb.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    private static void report(ProgressListener listener, String message) {
        Log.d(TAG, message);
        if (listener != null) listener.onProgress(message);
    }
}
