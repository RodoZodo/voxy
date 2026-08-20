package me.cortex.voxy.client.core.rendering.section.geometry;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.HierarchicalBitSet;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;


//Is basicly the manager for an "undefined" data store, the underlying store is irrelevant
// this manager serves as an overlay, that is, it allows an implementation to do "async management" of the data store
public class BasicAsyncGeometryManager implements IGeometryManager {
    public static final int SECTION_METADATA_SIZE = 32;

    private static final long GEOMETRY_ELEMENT_SIZE = 8;
    private final HierarchicalBitSet allocationSet;
    private final AllocationArena allocationHeap = new AllocationArena();
    private final ObjectArrayList<SectionMeta> sectionMetadata = new ObjectArrayList<>(1<<15);

    //Changes that need to be applied to the underlying data store to match this state
    private final IntOpenHashSet invalidatedIds = new IntOpenHashSet(1024);//Ids that need to be invalidated
    //TODO: maybe change from it pointing to MemoryBuffer, to BuiltSection
    //Note!: the int part is an unsigned int ptr, must be scaled by GEOMETRY_ELEMENT_SIZE
    private final Int2ObjectOpenHashMap<MemoryBuffer> heapUploads = new Int2ObjectOpenHashMap<>(1024);//Uploads into the buffer at the given location
    private final IntOpenHashSet heapRemoveUploads = new IntOpenHashSet(1024);//Any removals are added here, so that it can be properly synced
    private long usedCapacity = 0;

    public BasicAsyncGeometryManager(int maxSectionCount, long geometryCapacity) {
        this.allocationSet = new HierarchicalBitSet(maxSectionCount);
        if (geometryCapacity%GEOMETRY_ELEMENT_SIZE != 0)  throw new IllegalStateException();
        this.allocationHeap.setLimit(geometryCapacity/GEOMETRY_ELEMENT_SIZE);
    }

    @Override
    public int uploadSection(BuiltSection section) {
        return this.uploadReplaceSection(-1, section);
    }

    @Override
    public int uploadReplaceSection(int oldId, BuiltSection section) {
        if (section.isEmpty()) {
            throw new IllegalArgumentException("sectionData is empty, cannot upload nothing");
        }

        //Free the old id and replace it with a new one
        // if oldId is -1, then treat it as not previously existing

        //Free the old data if oldId is supplied
        if (oldId != -1) {
            //Its here just for future optimization potential
            this.removeSection(oldId);
        }

        int newId =  this.allocationSet.allocateNext();
        if (newId == HierarchicalBitSet.SET_FULL) {
            throw new IllegalStateException("Tried adding section when section count is already at capacity");
        }
        if (newId > this.sectionMetadata.size()) {
            throw new IllegalStateException("Size exceeds limits: " + newId + ", " + this.sectionMetadata.size() + ", " + this.allocationSet.getCount());
        }

        if (newId < this.sectionMetadata.size()) {
            if (this.sectionMetadata.get(newId) != null) {
                throw new IllegalStateException();
            }
        }

        var newMeta = this.createMeta(section);

        if (newId == this.sectionMetadata.size()) {
            this.sectionMetadata.add(newMeta);
        } else {
            if (this.sectionMetadata.set(newId, newMeta) != null) {
                throw new IllegalStateException();
            }
        }

        //Invalidate the section id
        this.invalidatedIds.add(newId);

        //HierarchicalOcclusionTraverser.HACKY_SECTION_COUNT = this.allocationSet.getCount();
        return newId;
    }

    @Override
    public void removeSection(int id) {
        if (!this.allocationSet.free(id)) {
            throw new IllegalStateException("Id was not already allocated. id: " + id);
        }
        var oldMetadata = this.sectionMetadata.set(id, null);
        int ptr = oldMetadata.geometryPtr;
        //Free from the heap
        this.usedCapacity -= this.allocationHeap.free(Integer.toUnsignedLong(ptr));
        //Free the upload if it was uploading
        var buf = this.heapUploads.remove(ptr);
        if (buf != null) {
            buf.free();
        }
        this.heapRemoveUploads.add(ptr);
        this.invalidatedIds.add(id);
    }

    private SectionMeta createMeta(BuiltSection section) {
        if ((section.geometryBuffer.size%GEOMETRY_ELEMENT_SIZE)!=0) throw new IllegalStateException();
        int size = (int) (section.geometryBuffer.size/GEOMETRY_ELEMENT_SIZE);
        //clamp size upwards to ranges of 127
        int upsized = (size+127)&~127;
        //Address
        int addr = (int)this.allocationHeap.alloc(upsized);
        if (addr == -1) {
            throw new IllegalStateException("Geometry OOM. requested allocation size (in elements): " + size + ", Heap size at top remaining: " + (this.allocationHeap.getLimit()-this.allocationHeap.getSize()) + ", used elements: " + this.usedCapacity);
        }
        this.usedCapacity += upsized;
        //Create upload
        if (this.heapUploads.put(addr, section.geometryBuffer) != null) {
            throw new IllegalStateException("Addr: " + addr);
        }
        this.heapRemoveUploads.remove(addr);
        //Create Meta
        return new SectionMeta(section.position, section.aabb, addr, size, section.offsets, section.childExistence);
    }

    @Override
    public void downloadAndRemove(int id, Consumer<BuiltSection> callback) {
        throw new IllegalStateException("Not yet implemented");
    }

    public Int2ObjectOpenHashMap<MemoryBuffer> getUploads() {
        return this.heapUploads;
    }

    public IntOpenHashSet getHeapRemovals() {
        return this.heapRemoveUploads;
    }

    public int getSectionCount() {
        return this.allocationSet.getCount();
    }

    public long getGeometryUsedBytes() {
        return this.usedCapacity * GEOMETRY_ELEMENT_SIZE;
    }

    public IntOpenHashSet getUpdateIds() {
        return this.invalidatedIds;
    }

    /** Write the 32-byte metadata for a section into the (little-endian) buffer. */
    public void writeMetadata(int sectionId, ByteBuffer buffer) {
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing (zeros)
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < SECTION_METADATA_SIZE / 4; i++) {
                buffer.putInt(0);
            }
        } else {
            sec.writeMetadata(buffer);
        }
    }

    /** Write the 32-byte metadata split into two 16-byte (little-endian) buffers. */
    public void writeMetadataSplit(int sectionId, ByteBuffer bufferA, ByteBuffer bufferB) {
        if (SECTION_METADATA_SIZE != 32) {//This system only works with 32 byte metadata
            throw new IllegalStateException();
        }
        var sec = this.sectionMetadata.get(sectionId);
        if (sec == null) {
            //Write nothing (zeros)
            bufferA.order(ByteOrder.LITTLE_ENDIAN);
            bufferB.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 4; i++) {
                bufferA.putInt(0);
                bufferB.putInt(0);
            }
        } else {
            sec.writeMetadataSplitParts(bufferA, bufferB);
        }
    }

    private record SectionMeta(long position, int aabb, int geometryPtr, int itemCount, int[] offsets, byte childExistence) {
        public void writeMetadata(ByteBuffer buffer) {
            this.writeMetadataSplitParts(buffer, buffer.duplicate().position(buffer.position() + 16));
        }

        public void writeMetadataSplitParts(ByteBuffer bufferA, ByteBuffer bufferB) {//First 16 bytes are put into ptrA the remaining 16 bytes are put into ptrB
            bufferA.order(ByteOrder.LITTLE_ENDIAN);
            bufferB.order(ByteOrder.LITTLE_ENDIAN);
            //Split the long into 2 ints to solve endian issues
            bufferA.putInt((int) (this.position >> 32));
            bufferA.putInt((int) this.position);
            bufferA.putInt((int) this.aabb);
            bufferA.putInt(this.geometryPtr + this.offsets[0]);

            bufferB.putInt((this.offsets[1] - this.offsets[0]) | ((this.offsets[2] - this.offsets[1]) << 16));
            bufferB.putInt((this.offsets[3] - this.offsets[2]) | ((this.offsets[4] - this.offsets[3]) << 16));
            bufferB.putInt((this.offsets[5] - this.offsets[4]) | ((this.offsets[6] - this.offsets[5]) << 16));
            bufferB.putInt((this.offsets[7] - this.offsets[6]) | ((this.itemCount - this.offsets[7]) << 16));
        }
    }
}
