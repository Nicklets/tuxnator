package com.winlator.cmod.xenvironment.components;

import android.util.Log;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.LinuxRootfs;
import com.winlator.cmod.xenvironment.ProotLauncher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;

/**
 * Runs a program inside the Debian {@link LinuxRootfs} through PRoot, connected to the app's
 * X server, PulseAudio and SysV SHM servers (Linux mode, phase 2 — see PHASE2_PLAN.md).
 */
public class LinuxProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "LinuxGuest";
    public static final String GUEST_HOME = "/home/user";

    private final LinuxRootfs rootfs;
    private final String commandLine;
    private File homeDir;
    private String workingDir = GUEST_HOME;
    private final EnvVars envVars = new EnvVars();
    private Callback<Integer> terminationCallback;
    private Process process;
    private final Object lock = new Object();

    /** @param commandLine shell command line, run inside the guest with /bin/sh -c */
    public LinuxProgramLauncherComponent(LinuxRootfs rootfs, String commandLine) {
        this.rootfs = rootfs;
        this.commandLine = commandLine;
    }

    /** Host directory mounted as the guest's /home/user (the container's home). */
    public void setHomeDir(File homeDir) {
        this.homeDir = homeDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    /** Extra variables for the guest, e.g. from the shortcut. They override the defaults. */
    public void putEnvVars(EnvVars envVars) {
        this.envVars.putAll(envVars);
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    @Override
    public void start() {
        synchronized (lock) {
            ProotLauncher launcher = createLauncher();
            Log.d(TAG, "Starting: " + commandLine);
            try {
                process = launcher.createProcess("/bin/sh", "-c", commandLine).start();
            }
            catch (IOException e) {
                Log.e(TAG, "Failed to start PRoot", e);
                if (terminationCallback != null) terminationCallback.call(-1);
                return;
            }
            final Process started = process;
            Executors.newSingleThreadExecutor().execute(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) Log.d(TAG, line);
                }
                catch (IOException e) {}
                int status = -1;
                try {
                    status = started.waitFor();
                }
                catch (InterruptedException e) {}
                Log.d(TAG, "Exited with status " + status);
                synchronized (lock) {
                    if (process == started) process = null;
                }
                if (terminationCallback != null) terminationCallback.call(status);
            });
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            // PRoot runs with --kill-on-exit, so stopping it takes the whole guest down
            if (process != null) process.destroy();
            process = null;
        }
    }

    private ProotLauncher createLauncher() {
        ImageFs imageFs = environment.getImageFs();
        String tmpDir = imageFs.getTmpDir().getAbsolutePath();
        ProotLauncher launcher = new ProotLauncher(environment.getContext(), rootfs);

        // The app's servers listen on sockets under imagefs/usr/tmp, see UnixSocketConfig
        launcher.bind(tmpDir + "/.X11-unix", "/tmp/.X11-unix");
        launcher.bind(tmpDir + "/.sound", "/tmp/.sound");
        launcher.bind(tmpDir + "/.sysvshm", "/tmp/.sysvshm");
        if (homeDir != null) {
            homeDir.mkdirs();
            launcher.bind(homeDir.getAbsolutePath(), GUEST_HOME);
        }
        launcher.setWorkingDir(workingDir);

        launcher.putEnv("HOME", GUEST_HOME);
        launcher.putEnv("USER", "user");
        launcher.putEnv("DISPLAY", ":0");
        launcher.putEnv("XDG_RUNTIME_DIR", "/tmp/runtime");
        launcher.putEnv("PULSE_SERVER", "unix:/tmp/.sound/PS0");
        // Android has no SysV IPC; the shim forwards shmget & co. to the app (needed by MIT-SHM)
        launcher.putEnv("ANDROID_SYSVSHM_SERVER", "/tmp/.sysvshm/SM0");
        launcher.putEnv("LD_PRELOAD", "/usr/lib/winlator/libandroid-sysvshm.so");
        // The Java X server's DRI3 only takes AHardwareBuffers, so present through software
        // copies for now (risk R3 in PHASE2_PLAN.md)
        launcher.putEnv("MESA_VK_WSI_DEBUG", "sw");
        launcher.putEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        // Without usable DRI3 the GLX loader finds no fbconfigs; this makes Zink go through the
        // DRI2 path and present with the (working) Vulkan WSI instead
        launcher.putEnv("LIBGL_KOPPER_DRI2", "1");
        launcher.putEnv("TU_DEBUG", "noconform");

        for (String name : envVars) launcher.putEnv(name, envVars.get(name));
        return launcher;
    }
}
