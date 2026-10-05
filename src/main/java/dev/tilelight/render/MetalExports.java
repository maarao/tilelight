package dev.tilelight.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import dev.tilelight.mixin.VulkanGpuBufferDirectAccessor;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.EXTMetalObjects;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExportMetalBufferInfoEXT;
import org.lwjgl.vulkan.VkExportMetalCommandQueueInfoEXT;
import org.lwjgl.vulkan.VkExportMetalDeviceInfoEXT;
import org.lwjgl.vulkan.VkExportMetalObjectsInfoEXT;
import org.lwjgl.vulkan.VkExportMetalSharedEventInfoEXT;
import org.lwjgl.vulkan.VkExportMetalTextureInfoEXT;

/**
 * Looks up the Metal objects under Minecraft's Vulkan (MoltenVK) objects via VK_EXT_metal_objects.
 * Returned pointers are not retained: they stay valid as long as the Vulkan object does.
 */
final class MetalExports {
	private static final int PLANE_0 = 0x10; // VK_IMAGE_ASPECT_PLANE_0_BIT

	/** An MTLBuffer and the byte offset of a Vulkan buffer's contents inside it. */
	record BufferRef(long mtlBuffer, long offset) {
	}

	private final VulkanDevice device;
	private final Map<GpuTexture, Long> textures = new IdentityHashMap<>();
	private final Map<GpuBuffer, BufferRef> buffers = new IdentityHashMap<>();

	MetalExports(VulkanDevice device) {
		this.device = device;
	}

	long device() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalDeviceInfoEXT info = VkExportMetalDeviceInfoEXT.calloc(stack).sType$Default();
			export(stack, info.address());
			return info.mtlDevice();
		}
	}

	/** The MTLCommandQueue MoltenVK submits Minecraft's graphics queue work to. */
	long commandQueue() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalCommandQueueInfoEXT info = VkExportMetalCommandQueueInfoEXT.calloc(stack).sType$Default()
					.queue(this.device.graphicsQueue().vkQueue());
			export(stack, info.address());
			return info.mtlCommandQueue();
		}
	}

	long sharedEvent(long vkSemaphore) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalSharedEventInfoEXT info = VkExportMetalSharedEventInfoEXT.calloc(stack).sType$Default().semaphore(vkSemaphore);
			export(stack, info.address());
			return info.mtlSharedEvent();
		}
	}

	long texture(GpuTexture texture) {
		Long cached = this.textures.get(texture);
		if (cached != null) return cached;
		// Targets are recreated on resize; drop pointers to closed textures so the map stays small.
		this.textures.keySet().removeIf(GpuTexture::isClosed);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkExportMetalTextureInfoEXT info = VkExportMetalTextureInfoEXT.calloc(stack).sType$Default()
					.image(((VulkanGpuTexture) texture).vkImage()).plane(PLANE_0);
			export(stack, info.address());
			if (info.mtlTexture() == 0L) {
				throw new IllegalStateException("MoltenVK did not export an MTLTexture for " + texture.getLabel());
			}
			this.textures.put(texture, info.mtlTexture());
			return info.mtlTexture();
		}
	}

	/** Returns null if MoltenVK has no single MTLBuffer behind this buffer's memory. */
	BufferRef buffer(GpuBuffer buffer) {
		BufferRef cached = this.buffers.get(buffer);
		if (cached != null) return cached;
		this.buffers.keySet().removeIf(GpuBuffer::isClosed);
		if (!(buffer instanceof VulkanGpuBuffer.Direct direct)) return null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VmaAllocationInfo alloc = VmaAllocationInfo.calloc(stack);
			Vma.vmaGetAllocationInfo(this.device.vma(), ((VulkanGpuBufferDirectAccessor) direct).tilelight$vmaAllocation(), alloc);
			VkExportMetalBufferInfoEXT info = VkExportMetalBufferInfoEXT.calloc(stack).sType$Default().memory(alloc.deviceMemory());
			export(stack, info.address());
			BufferRef ref = info.mtlBuffer() == 0L ? null : new BufferRef(info.mtlBuffer(), alloc.offset());
			this.buffers.put(buffer, ref);
			return ref;
		}
	}

	void clear() {
		this.textures.clear();
		this.buffers.clear();
	}

	private void export(MemoryStack stack, long chain) {
		VkExportMetalObjectsInfoEXT objects = VkExportMetalObjectsInfoEXT.calloc(stack).sType$Default();
		objects.pNext(chain);
		EXTMetalObjects.vkExportMetalObjectsEXT(this.device.vkDevice(), objects);
	}

	VkDevice vkDevice() {
		return this.device.vkDevice();
	}
}
