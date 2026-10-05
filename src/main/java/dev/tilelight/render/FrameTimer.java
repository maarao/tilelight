package dev.tilelight.render;

import dev.tilelight.Tilelight;

/**
 * Splits the render thread's frame into recording, queue submission and waiting for the GPU, from
 * Minecraft's per-frame submit. Logs averages every 600 submits, with or without Tilelight active.
 */
public final class FrameTimer {
	private static final int EVERY = 600;
	private static long submitStart, waitStart, lastWaitEnd;
	private static long recordNs, submitNs, waitNs, count;

	private FrameTimer() {
	}

	public static void submitStart() {
		submitStart = System.nanoTime();
		if (lastWaitEnd != 0) recordNs += submitStart - lastWaitEnd;
	}

	public static void waitStart() {
		waitStart = System.nanoTime();
		submitNs += waitStart - submitStart;
	}

	public static void waitEnd() {
		lastWaitEnd = System.nanoTime();
		waitNs += lastWaitEnd - waitStart;
		if (++count >= EVERY) {
			Tilelight.LOG.info("render thread us/frame: recording {}, queue submit {}, waiting for GPU {}",
					recordNs / count / 1000, submitNs / count / 1000, waitNs / count / 1000);
			recordNs = submitNs = waitNs = count = 0;
		}
	}
}
