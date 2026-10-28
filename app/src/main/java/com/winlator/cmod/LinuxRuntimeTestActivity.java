package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import com.winlator.cmod.xenvironment.LinuxRootfs;
import com.winlator.cmod.xenvironment.ProotLauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;

/**
 * Debug-only screen (registered in src/debug/AndroidManifest.xml) to install the Linux rootfs and
 * run commands inside it through PRoot, driven from adb:
 *
 *   adb shell am start -n com.winlator.cmod/.LinuxRuntimeTestActivity \
 *       --es install /sdcard/Download/linux-rootfs-1.tar.zst --es sha256 <hash> --es cmd "uname -a"
 *
 * Output goes to the screen, logcat (tag LinuxRuntimeTest) and files/linux/test.log.
 */
public class LinuxRuntimeTestActivity extends Activity {
    private static final String TAG = "LinuxRuntimeTest";
    private static final String DEFAULT_CMD =
            "uname -a; head -3 /etc/os-release; cat /etc/winlator-rootfs-version; " +
            "box64 --version; ls -l /dev/kgsl-3d0; vulkaninfo --summary 2>&1 | head -40";

    private TextView textView;
    private ScrollView scrollView;
    private File logFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        textView = new TextView(this);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextSize(11);
        textView.setPadding(16, 16, 16, 16);
        textView.setTextIsSelectable(true);
        scrollView = new ScrollView(this);
        scrollView.addView(textView);
        setContentView(scrollView);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        final String installPath = intent.getStringExtra("install");
        final String sha256 = intent.getStringExtra("sha256");
        final String cmd = intent.hasExtra("cmd") ? intent.getStringExtra("cmd") : DEFAULT_CMD;
        final LinuxRootfs rootfs = LinuxRootfs.find(this);
        logFile = new File(rootfs.getRootDir().getParentFile(), "test.log");
        logFile.getParentFile().mkdirs();
        logFile.delete();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (installPath != null) {
                if (!rootfs.install(new File(installPath), sha256, this::log)) {
                    log("== INSTALL FAILED");
                    return;
                }
            }
            if (!rootfs.isInstalled()) {
                log("Rootfs not installed. Pass --es install <path to linux-rootfs-N.tar.zst>");
                return;
            }
            run(new ProotLauncher(this, rootfs), cmd);
        });
    }

    private void run(ProotLauncher launcher, String cmd) {
        log("$ " + cmd);
        log("proot: " + launcher.getProotBinary() + " (exists=" + launcher.getProotBinary().isFile() + ")");
        try {
            Process process = launcher.createProcess("/bin/sh", "-c", cmd).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) log(line);
            }
            log("== exit code " + process.waitFor());
        }
        catch (IOException | InterruptedException e) {
            log("== failed to start: " + e);
        }
    }

    private synchronized void log(String line) {
        Log.d(TAG, line);
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(line + "\n");
        }
        catch (IOException e) {}
        runOnUiThread(() -> {
            textView.append(line + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
