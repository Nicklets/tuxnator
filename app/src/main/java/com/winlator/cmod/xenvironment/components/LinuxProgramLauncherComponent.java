package com.winlator.cmod.xenvironment.components;

import android.os.Environment;
import android.system.Os;
import android.util.Log;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
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
    /** Where Android's internal storage shows up in the guest */
    public static final String ANDROID_STORAGE = "/media/android";

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
        // Evdev devices written by the app for the virtual/physical gamepads (FakeInputWriter)
        File fakeInputDir = new File(imageFs.getRootDir(), "dev/input");
        fakeInputDir.mkdirs();
        launcher.bind(fakeInputDir.getAbsolutePath(), "/tmp/fake-input");
        // Android's internal storage, to move files between Android and the guest
        launcher.bind(Environment.getExternalStorageDirectory().getAbsolutePath(), ANDROID_STORAGE);
        if (homeDir != null) {
            homeDir.mkdirs();
            launcher.bind(homeDir.getAbsolutePath(), GUEST_HOME);
            addAndroidStorageShortcuts();
        }
        launcher.setWorkingDir(workingDir);

        launcher.putEnv("HOME", GUEST_HOME);
        launcher.putEnv("USER", "user");
        launcher.putEnv("DISPLAY", ":0");
        launcher.putEnv("XDG_RUNTIME_DIR", "/tmp/runtime");
        launcher.putEnv("PULSE_SERVER", "unix:/tmp/.sound/PS0");
        // Android has no SysV IPC; the shim forwards shmget & co. to the app (needed by MIT-SHM)
        launcher.putEnv("ANDROID_SYSVSHM_SERVER", "/tmp/.sysvshm/SM0");
        // libfakeinput makes those devices show up as /dev/input/event* (SDL, evdev, joydev users)
        String preload = "/usr/lib/winlator/libandroid-sysvshm.so:/usr/lib/winlator/libfakeinput.so";
        // Udev monitors that initialize instead of failing on Android's netlink block; without it,
        // SDL2 retries its HIDAPI init every frame (rootfs v9+)
        if (new File(rootfs.getRootDir(), "usr/lib/winlator/libfakeudev.so").isFile()) {
            preload += ":/usr/lib/winlator/libfakeudev.so";
        }
        // Runs x86 programs through Box64 when a file manager or script executes them (rootfs v10+)
        if (new File(rootfs.getRootDir(), "usr/lib/winlator/libbox64exec.so").isFile()) {
            preload += ":/usr/lib/winlator/libbox64exec.so";
        }
        launcher.putEnv("LD_PRELOAD", preload);
        // The X server's XInput2 only reports raw events, and GTK3/Qt take their pointer and
        // keyboard input from XI2 device events: have them use core events instead
        launcher.putEnv("GDK_CORE_DEVICE_EVENTS", "1");
        launcher.putEnv("QT_XCB_NO_XI2", "1");
        launcher.putEnv("TZ", java.util.TimeZone.getDefault().getID());
        ensurePasswdEntry();
        launcher.putEnv("FAKE_EVDEV_DIR", "/tmp/fake-input");
        // libudev is in the rootfs but there is no udevd, so SDL2's udev enumeration fails and the
        // whole joystick subsystem with it; scanning /dev/input directly goes through libfakeinput
        launcher.putEnv("SDL_JOYSTICK_DISABLE_UDEV", "1");
        // Vulkan presents go through DRI3/Present: the renderer imports the dma-buf (zero-copy with
        // a Turnip renderer driver, a CPU copy otherwise) and FIFO is paced to the display's vsync.
        // Before rootfs v8, Mesa's WSI didn't wait for the frame's fence (KGSL has no implicit
        // sync), so the X server could show half-rendered frames: copy through the software path.
        if (rootfs.getVersion() < 8) launcher.putEnv("MESA_VK_WSI_DEBUG", "sw");
        launcher.putEnv("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        // Without usable DRI3 the GLX loader finds no fbconfigs; this makes Zink go through the
        // DRI2 path and present with the (working) Vulkan WSI instead
        launcher.putEnv("LIBGL_KOPPER_DRI2", "1");
        launcher.putEnv("TU_DEBUG", "noconform");

        for (String name : envVars) launcher.putEnv(name, envVars.get(name));
        return launcher;
    }

    /**
     * A desktop icon and a file manager bookmark (GTK) named "Android" for {@link #ANDROID_STORAGE}.
     * The icon is a symlink to that guest-only path, which doesn't exist on the Android side, so
     * nothing walking the container's home outside PRoot ends up in the user's storage.
     */
    private void addAndroidStorageShortcuts() {
        try {
            File link = new File(homeDir, "Desktop/Android");
            if (!link.exists() && !FileUtils.isSymlink(link)) {
                link.getParentFile().mkdirs();
                Os.symlink(ANDROID_STORAGE, link.getPath());
            }
            File bookmarks = new File(homeDir, ".config/gtk-3.0/bookmarks");
            String bookmark = "file://" + ANDROID_STORAGE + " Android";
            String content = bookmarks.isFile() ? FileUtils.readString(bookmarks) : "";
            if (!content.contains(bookmark)) {
                bookmarks.getParentFile().mkdirs();
                if (!content.isEmpty() && !content.endsWith("\n")) content += "\n";
                FileUtils.writeString(bookmarks, content + bookmark + "\n");
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not add the Android storage shortcuts", e);
        }
    }

    /**
     * Guest programs run with the app's Android uid, which the rootfs doesn't know (its "user" is
     * 1000). dbus-daemon, and anything else looking itself up with getpwuid(), refuses to start
     * without an entry, so add one for the current uid.
     */
    private void ensurePasswdEntry() {
        int uid = android.os.Process.myUid();
        String entry = ":" + uid + ":";
        try {
            File passwd = new File(rootfs.getRootDir(), "etc/passwd");
            if (passwd.isFile() && !FileUtils.readString(passwd).contains(entry)) {
                FileUtils.writeString(passwd, FileUtils.readString(passwd)
                    + "user:x" + entry + uid + ":user:" + GUEST_HOME + ":/bin/bash\n");
            }
            File group = new File(rootfs.getRootDir(), "etc/group");
            if (group.isFile() && !FileUtils.readString(group).contains(entry)) {
                FileUtils.writeString(group, FileUtils.readString(group) + "user:x" + entry + "\n");
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not add uid " + uid + " to the rootfs passwd/group", e);
        }
    }
}
