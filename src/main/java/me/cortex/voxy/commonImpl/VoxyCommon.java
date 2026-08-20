package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public class VoxyCommon implements ModInitializer {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        String version = "<UNKNOWN>";
        boolean inMinecraft = false;
        boolean dedicated = false;
        try {
            ModContainer mod = FabricLoader.getInstance().getModContainer("voxy").orElse(null);
            if (mod == null) {
                System.out.println("[Voxy] Running voxy without minecraft");
            } else {
                inMinecraft = true;
                version = mod.getMetadata().getVersion().getFriendlyString();
                try {
                    var commit = mod.getMetadata().getCustomValue("commit");
                    if (commit != null) {
                        String hash = commit.getAsString();
                        if (hash != null && hash.length() >= 7) {
                            version = version + "-" + hash.substring(0, 7);
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    dedicated = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
                } catch (Throwable ignored) {
                }
                try {
                    Serialization.init();
                } catch (Throwable t) {
                    System.out.println("[Voxy] Serialization.init failed: " + t);
                    t.printStackTrace(System.out);
                }
            }
        } catch (Throwable t) {
            System.out.println("[Voxy] VoxyCommon static init failed: " + t);
            t.printStackTrace(System.out);
        }
        IS_IN_MINECRAFT = inMinecraft;
        MOD_VERSION = version;
        IS_DEDICATED_SERVER = dedicated;
    }

    //This is hardcoded like this because people do not understand what they are doing
    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    @Override
    public void onInitialize() {

    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            return;
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;//Make it null before shutdown
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            return;
        }
        if (INSTANCE != null) {
            return;
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    //Is voxy available in any capacity
    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}