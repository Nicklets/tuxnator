package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the PRoot invocation that runs a program inside the {@link LinuxRootfs}.
 * Commands are passed as an argument array (not a single string) so paths with spaces
 * and quotes reach the guest unchanged.
 */
public class ProotLauncher {
    public static final String DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    public static final String TURNIP_ICD = "/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json";

    private final Context context;
    private final LinuxRootfs rootfs;
    private final List<String> bindings = new ArrayList<>();
    private final Map<String, String> guestEnv = new LinkedHashMap<>();
    private String workingDir = "/root";

    public ProotLauncher(Context context, LinuxRootfs rootfs) {
        this.context = context;
        this.rootfs = rootfs;

        bind("/dev");
        bind("/proc");
        bind("/sys");
        bind(Environment.getExternalStorageDirectory().getAbsolutePath(), "/mnt/sdcard");

        putEnv("HOME", "/root");
        putEnv("USER", "root");
        putEnv("PATH", DEFAULT_PATH);
        putEnv("LANG", "C.UTF-8");
        putEnv("TMPDIR", "/tmp");
        putEnv("VK_ICD_FILENAMES", TURNIP_ICD);
    }

    public ProotLauncher bind(String path) {
        bindings.add(path);
        return this;
    }

    public ProotLauncher bind(String hostPath, String guestPath) {
        bindings.add(hostPath + ":" + guestPath);
        return this;
    }

    public ProotLauncher putEnv(String name, String value) {
        guestEnv.put(name, value);
        return this;
    }

    public ProotLauncher setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
        return this;
    }

    public File getProotBinary() {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    public List<String> buildCommand(String... guestArgv) {
        List<String> command = new ArrayList<>();
        command.add(getProotBinary().getAbsolutePath());
        command.add("--kill-on-exit");
        command.add("-r");
        command.add(rootfs.getRootDir().getAbsolutePath());
        for (String binding : bindings) {
            command.add("-b");
            command.add(binding);
        }
        command.add("-w");
        command.add(workingDir);

        // Start from a clean environment so nothing from the Android process (LD_PRELOAD of
        // Bionic libraries, Android paths...) leaks into the glibc guest
        command.add("/usr/bin/env");
        command.add("-i");
        for (Map.Entry<String, String> entry : guestEnv.entrySet()) {
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        for (String arg : guestArgv) command.add(arg);
        return command;
    }

    /** Environment of the PRoot process itself (on the Android side). */
    public Map<String, String> buildHostEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PROOT_LOADER", new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so").getAbsolutePath());
        env.put("PROOT_TMP_DIR", rootfs.getTmpDir().getAbsolutePath());
        return env;
    }

    public ProcessBuilder createProcess(String... guestArgv) {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(guestArgv));
        pb.environment().remove("LD_PRELOAD");
        pb.environment().putAll(buildHostEnv());
        pb.redirectErrorStream(true);
        return pb;
    }
}
