package org.ppsspp.ppsspp.duo;

// JNI side lives in android/jni/DuoBridge.cpp.
//
// Everything here is safe to call from the UI thread. Input is queued and applied by the emu thread
// at the end of the next frame; status and memory snapshots are copied by the emu thread between
// frames, so reads never touch live emulator state.
public final class DuoNative {
	private DuoNative() {}

	// PSP button bits, matching Core/HLE/sceCtrl.h.
	public static final int CTRL_SELECT = 0x0001;
	public static final int CTRL_START = 0x0008;
	public static final int CTRL_UP = 0x0010;
	public static final int CTRL_RIGHT = 0x0020;
	public static final int CTRL_DOWN = 0x0040;
	public static final int CTRL_LEFT = 0x0080;
	public static final int CTRL_LTRIGGER = 0x0100;
	public static final int CTRL_RTRIGGER = 0x0200;
	public static final int CTRL_TRIANGLE = 0x1000;
	public static final int CTRL_CIRCLE = 0x2000;
	public static final int CTRL_CROSS = 0x4000;
	public static final int CTRL_SQUARE = 0x8000;

	// Virtual keys, matching the VirtKey enum in Core/KeyMap.h. Those values are stored in configs,
	// so they never change.
	public static final int VIRTKEY_FASTFORWARD = 0x40000006;
	public static final int VIRTKEY_PAUSE = 0x40000007;
	public static final int VIRTKEY_SPEED_TOGGLE = 0x40000008;
	public static final int VIRTKEY_REWIND = 0x4000000d;
	public static final int VIRTKEY_SAVE_STATE = 0x4000000e;
	public static final int VIRTKEY_LOAD_STATE = 0x4000000f;
	public static final int VIRTKEY_NEXT_SLOT = 0x40000010;
	public static final int VIRTKEY_SCREENSHOT = 0x4000001B;
	public static final int VIRTKEY_MUTE_TOGGLE = 0x4000001C;
	public static final int VIRTKEY_PREVIOUS_SLOT = 0x40000027;
	public static final int VIRTKEY_RESET_EMULATION = 0x40000032;
	public static final int VIRTKEY_PAUSE_NO_MENU = 0x40000034;

	public static final int STICK_LEFT = 0;
	public static final int STICK_RIGHT = 1;

	// While inactive, the native side ignores input and releases whatever the second screen held.
	public static native void nativeSetActive(boolean active);

	public static native void nativeButton(int pspButtonMask, boolean down);
	public static native void nativeVirtKey(int virtKey, boolean down);
	// x, y in [-1, 1], PSP convention: positive y is up (the opposite of screen coordinates).
	public static native void nativeAnalog(int stick, float x, float y);
	// Releases every button, virtual key and stick held from the second screen.
	public static native void nativeReleaseAll();

	// JSON snapshot, see DuoStatus.
	public static native String nativeGetStatus();
	// ICON0.PNG of the running game, or null.
	public static native byte[] nativeGetIcon();

	// PSP buttons that went down since the last call, from any input source, as pairs of
	// (newly pressed button bits, time in ms). Only recorded in game. Null if none.
	public static native int[] nativeGetButtonPresses();

	// Asynchronous read of up to 1 MB from the game's own files ("disc0:/PSP_GAME/..."), done on the
	// emu thread between frames. Returns a request id, or 0 if rejected.
	public static native int nativeRequestGameFile(String path, int offset, int size);
	// Null while pending, then the data (empty if the read failed). The request is gone afterwards.
	public static native byte[] nativePollGameFile(int id);
	public static native void nativeCancelGameFiles();

	// PSP memory ranges to copy at the end of every frame. Max 16 ranges of 16 KB each.
	// Pass nulls to clear. Returns false if the request was rejected.
	public static native boolean nativeSetWatches(int[] addresses, int[] sizes);
	// Last copy of a watched range, or null if the range isn't readable right now (no game, bad address).
	public static native byte[] nativeGetWatch(int index);
}
