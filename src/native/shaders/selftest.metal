#include <metal_stdlib>
using namespace metal;

// Writes a multiplicative hash of each index; tl_selftest checks it on the CPU.
kernel void tl_selftest_kernel(device uint *out [[buffer(0)]],
                               constant uint &count [[buffer(1)]],
                               uint id [[thread_position_in_grid]]) {
	if (id < count) out[id] = id * 2654435761u;
}
