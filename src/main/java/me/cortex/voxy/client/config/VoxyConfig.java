package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public static final int MIN_RENDER_DISTANCE_CHUNKS = 128;
    public static final int MAX_RENDER_DISTANCE_CHUNKS = 2048;
    public static final int RENDER_DISTANCE_STEP_CHUNKS = 32;

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    /** LoD coverage in original Voxy units. GPU uniforms use {@code sectionRenderDistance * 32} chunks. Default 16 = 512 chunks. */
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public boolean dontUseSodiumBuilderThreads = false;


    private static VoxyConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                var conf = GSON.fromJson(reader, VoxyConfig.class);
                if (conf != null) {
                    conf.clampRenderDistance();
                    conf.save();
                    return conf;
                } else {
                    Logger.error("Failed to load voxy config, resetting");
                }
            } catch (IOException e) {
                Logger.error("Could not load config", e);
            } catch (JsonParseException e) {
                Logger.error("Could not parse config", e);
            }
            Logger.info("Error during config loading, creating new");
        } else {
            Logger.info("Config file doesnt exist, creating new");
        }
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return this.enabled && this.enableRendering;
    }

    /** Voxy LoD render distance in chunks (what the settings slider shows). */
    public int getRenderDistanceChunks() {
        return Math.round(this.sectionRenderDistance * 32f);
    }

    public void setRenderDistanceChunks(int chunks) {
        int clamped = Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_RENDER_DISTANCE_CHUNKS, chunks));
        int snapped = Math.round(clamped / (float) RENDER_DISTANCE_STEP_CHUNKS) * RENDER_DISTANCE_STEP_CHUNKS;
        snapped = Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_RENDER_DISTANCE_CHUNKS, snapped));
        this.sectionRenderDistance = snapped / 32f;
    }

    private void clampRenderDistance() {
        int chunks = this.getRenderDistanceChunks();
        if (chunks < MIN_RENDER_DISTANCE_CHUNKS || chunks > MAX_RENDER_DISTANCE_CHUNKS) {
            this.setRenderDistanceChunks(chunks);
        }
    }
}
