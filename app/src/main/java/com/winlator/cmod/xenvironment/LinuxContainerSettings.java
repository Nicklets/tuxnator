package com.winlator.cmod.xenvironment;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/**
 * Settings of the Linux container (Linux mode), edited in Settings > Linux container. They apply
 * to every Linux shortcut unless the shortcut sets its own value.
 */
public class LinuxContainerSettings {
    public static final String RENDERER_VULKAN = "vulkan";
    public static final String RENDERER_OPENGL = "opengl";
    /** Box64 preset id meaning "Box64's own defaults", no preset variables at all */
    public static final String BOX64_PRESET_DEFAULT = "";

    private static final String KEY_RENDERER = "linux_container_renderer";
    private static final String KEY_RENDERER_DRIVER = "linux_container_renderer_driver";
    private static final String KEY_BOX64_PRESET = "linux_container_box64_preset";

    private final SharedPreferences preferences;

    public LinuxContainerSettings(Context context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** X server renderer: Vulkan (presents dma-bufs zero-copy with a Turnip driver) or OpenGL */
    public String getRenderer() {
        return RENDERER_OPENGL.equals(preferences.getString(KEY_RENDERER, RENDERER_VULKAN)) ? RENDERER_OPENGL : RENDERER_VULKAN;
    }

    public void setRenderer(String renderer) {
        preferences.edit().putString(KEY_RENDERER, renderer).apply();
    }

    /** Vulkan driver of the X server renderer on Snapdragon: "system" or an installed adrenotools driver id */
    public String getRendererDriverId() {
        return preferences.getString(KEY_RENDERER_DRIVER, "system");
    }

    public void setRendererDriverId(String driverId) {
        preferences.edit().putString(KEY_RENDERER_DRIVER, driverId).apply();
    }

    /** Box64 preset id (see Box64PresetManager), or BOX64_PRESET_DEFAULT */
    public String getBox64Preset() {
        return preferences.getString(KEY_BOX64_PRESET, BOX64_PRESET_DEFAULT);
    }

    public void setBox64Preset(String presetId) {
        preferences.edit().putString(KEY_BOX64_PRESET, presetId).apply();
    }
}
