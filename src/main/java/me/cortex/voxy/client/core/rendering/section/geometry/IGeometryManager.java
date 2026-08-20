package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;

import java.util.function.Consumer;

public interface IGeometryManager {
    int uploadSection(BuiltSection section);
    int uploadReplaceSection(int oldId, BuiltSection section);
    void removeSection(int id);

    void downloadAndRemove(int id, Consumer<BuiltSection> callback);

    /** Bytes of device geometry memory currently in use (0 when the meshing system is idle). */
    default long getGeometryUsedBytes() {
        return 0;
    }

    /** Number of sections with uploaded geometry (0 when the meshing system is idle). */
    default int getSectionCount() {
        return 0;
    }

    /** Total device geometry capacity in bytes. */
    default long getGeometryCapacityBytes() {
        return 1L << 30;
    }
}
