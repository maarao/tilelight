package dev.tilelight.nativeapi;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Binding to libtilelight (src/native/tilelight.h) through the Foreign Function &amp; Memory API.
 *
 * <p>The dylib and metallib ship inside the jar under {@code natives/}. They are extracted to a
 * temporary directory on first use, unless the {@code tilelight.nativeDir} system property points
 * at an already-built directory (used by tests and dev runs).
 */
public final class NativeLib {
	public static final int OK = 0;

	public static final long CAP_RAYTRACING = 1L;
	public static final long CAP_APPLE9 = 1L << 1;
	public static final long CAP_UNIFIED_MEMORY = 1L << 2;
	public static final long CAP_METAL3 = 1L << 3;

	private static final String DYLIB = "libtilelight.dylib";
	private static final String METALLIB = "tilelight.metallib";

	private final MethodHandle init;
	private final MethodHandle deviceName;
	private final MethodHandle capabilities;
	private final MethodHandle selftest;
	private final MethodHandle shutdown;
	private final Path metallib;

	private NativeLib(Path dir) {
		Linker linker = Linker.nativeLinker();
		SymbolLookup lookup = SymbolLookup.libraryLookup(dir.resolve(DYLIB), Arena.global());
		this.init = bind(linker, lookup, "tl_init", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		this.deviceName = bind(linker, lookup, "tl_device_name",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		this.capabilities = bind(linker, lookup, "tl_capabilities", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
		this.selftest = bind(linker, lookup, "tl_selftest",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
		this.shutdown = bind(linker, lookup, "tl_shutdown", FunctionDescriptor.ofVoid());
		this.metallib = dir.resolve(METALLIB);
	}

	private static MethodHandle bind(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor fd) {
		MemorySegment symbol = lookup.find(name)
				.orElseThrow(() -> new UnsatisfiedLinkError("missing native symbol " + name));
		return linker.downcallHandle(symbol, fd);
	}

	/** Loads the native library, extracting it from the jar if needed. */
	public static NativeLib load() throws IOException {
		String override = System.getProperty("tilelight.nativeDir");
		if (override != null) {
			return new NativeLib(Path.of(override));
		}
		Path dir = Files.createTempDirectory("tilelight-natives");
		dir.toFile().deleteOnExit();
		for (String name : new String[] {DYLIB, METALLIB}) {
			try (InputStream in = NativeLib.class.getResourceAsStream("/natives/" + name)) {
				if (in == null) {
					throw new IOException("natives/" + name + " is missing from the jar");
				}
				Path target = dir.resolve(name);
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				target.toFile().deleteOnExit();
			}
		}
		return new NativeLib(dir);
	}

	/** Creates the Metal device and loads the shader library. Returns a tl error code. */
	public int init() {
		try (Arena arena = Arena.ofConfined()) {
			return (int) init.invokeExact(arena.allocateFrom(metallib.toString()));
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public String deviceName() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buf = arena.allocate(256);
			int rc = (int) deviceName.invokeExact(buf, 256);
			check(rc, "tl_device_name");
			return buf.getString(0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public long capabilities() {
		try {
			return (long) capabilities.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	/** Runs the GPU self-test over n elements and returns the GPU time in nanoseconds. */
	public long selftest(int n) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment gpuNs = arena.allocate(ValueLayout.JAVA_LONG);
			int rc = (int) selftest.invokeExact(n, gpuNs);
			check(rc, "tl_selftest");
			return gpuNs.get(ValueLayout.JAVA_LONG, 0);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public void shutdown() {
		try {
			shutdown.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static void check(int rc, String what) {
		if (rc != OK) {
			throw new IllegalStateException(what + " failed with code " + rc);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException re) return re;
		if (t instanceof Error e) throw e;
		return new IllegalStateException(t);
	}
}
