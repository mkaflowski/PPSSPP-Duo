// Copyright (c) 2012- PPSSPP Project.

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, version 2.0 or later versions.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License 2.0 for more details.

// A copy of the GPL 2.0 should have been included with the program.
// If not, see http://www.gnu.org/licenses/

// Official git repository and contact information can be found at
// https://github.com/hrydgard/ppsspp and http://www.ppsspp.org/.

#include "ppsspp_config.h"

#if PPSSPP_PLATFORM(ANDROID)

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Common/Log.h"
#include "Common/TimeUtil.h"
#include "Common/Input/InputState.h"
#include "Common/Data/Format/JSONWriter.h"
#include "Core/Config.h"
#include "Core/Core.h"
#include "Core/ControlMapper.h"
#include "Core/KeyMap.h"
#include "Core/MemMap.h"
#include "Core/SaveState.h"
#include "Core/System.h"
#include "Core/ELF/ParamSFO.h"
#include "Core/FileSystems/MetaFileSystem.h"
#include "Core/HLE/sceCtrl.h"
#include "Core/HW/Display.h"
#include "UI/GameInfoCache.h"

#include "android/jni/DuoBridge.h"

namespace {

// A press that waited this long (the render loop was stopped, for example) is dropped. Releases
// are never dropped, so nothing can get stuck.
constexpr double MAX_PRESS_AGE = 0.5;
constexpr int MAX_WATCHES = 16;
constexpr uint32_t MAX_WATCH_BYTES = 16 * 1024;
// Save slot info comes from SaveState's cached listing; refreshed this often and when the slot changes.
constexpr double SLOT_INFO_INTERVAL = 3.0;

enum class CommandType {
	PSP_BUTTON,
	VIRT_KEY,
};

struct Command {
	CommandType type;
	uint32_t code;
	bool down;
	double time;
};

struct Watch {
	uint32_t address = 0;
	uint32_t size = 0;
	bool valid = false;
	std::vector<uint8_t> data;
};

struct Status {
	std::string state = "menu";
	bool stepping = false;
	std::string gameId;
	std::string discVersion;
	std::string title;
	std::string path;
	float vps = 0.0f;
	float fps = 0.0f;
	float actualFps = 0.0f;
	bool fastForward = false;
	int fpsLimit = 0;
	int slot = 0;
	int slotCount = 0;
	bool slotUsed = false;
	std::string slotDate;
	int iconGeneration = 0;
	uint32_t frame = 0;
};

std::atomic<bool> g_active{};
std::atomic<bool> g_releaseAllRequested{};

std::mutex g_commandLock;
std::vector<Command> g_commands;

std::mutex g_analogLock;
bool g_analogDirty[2]{};
float g_analog[2][2]{};

std::mutex g_watchLock;
std::vector<Watch> g_watches;

std::mutex g_statusLock;
Status g_status;
std::string g_iconData;

// PSP buttons that went down, from any source (physical pad, touch controls, the second screen).
struct ButtonPress {
	uint32_t buttons;
	int32_t timeMs;
};
constexpr size_t MAX_BUTTON_PRESSES = 64;
std::mutex g_pressLock;
std::vector<ButtonPress> g_presses;
uint32_t g_prevButtons = 0;  // emu thread only

// Reads from the game's own files (disc0:/...), for mods that need game data such as map textures.
// Done on the emu thread between frames, one request per frame, so no file handle outlives a frame
// (savestates serialize open files) and the file system can't be torn down under us.
constexpr uint32_t MAX_FILE_READ = 1024 * 1024;
struct FileRequest {
	int id;
	std::string path;
	uint32_t offset;
	uint32_t size;
	bool done = false;
	bool ok = false;
	std::vector<uint8_t> data;
};
std::mutex g_fileLock;
std::vector<FileRequest> g_fileRequests;
int g_nextFileId = 1;

// Signature scans, so mods can find their data in game versions they weren't written for. A
// pattern is a set of (offset, u32 value) pairs that must all match. Also served one per frame.
struct FindRequest {
	int id;
	uint32_t start;
	uint32_t end;
	std::vector<uint32_t> offsets;
	std::vector<uint32_t> values;
	bool done = false;
	uint32_t result = 0;
};
std::mutex g_findLock;
std::vector<FindRequest> g_findRequests;
int g_nextFindId = 1;

// Emu thread only.
uint32_t g_heldButtons = 0;
std::vector<uint32_t> g_heldVirtKeys;
bool g_analogHeld[2]{};
std::string g_lastGamePath;
std::shared_ptr<GameInfo> g_iconInfo;
double g_lastSlotCheck = 0.0;
int g_lastSlotChecked = -1;
bool g_slotUsed = false;
std::string g_slotDate;
std::vector<double> g_rescanTimes;
uint32_t g_frameCounter = 0;

void ReleaseEverything() {
	if (g_heldButtons) {
		g_controlMapper.PSPKey(DEVICE_ID_TOUCH, g_heldButtons, KeyInputFlags::UP);
		g_heldButtons = 0;
	}
	for (uint32_t vkey : g_heldVirtKeys) {
		g_controlMapper.PSPKey(DEVICE_ID_TOUCH, vkey, KeyInputFlags::UP);
	}
	g_heldVirtKeys.clear();
	for (int stick = 0; stick < 2; stick++) {
		if (g_analogHeld[stick]) {
			__CtrlSetAnalogXY(stick, 0.0f, 0.0f);
			g_analogHeld[stick] = false;
		}
	}
}

void ProcessCommands(double now) {
	std::vector<Command> commands;
	{
		std::lock_guard<std::mutex> guard(g_commandLock);
		commands.swap(g_commands);
	}

	if (g_releaseAllRequested.exchange(false)) {
		ReleaseEverything();
	}

	for (const Command &cmd : commands) {
		if (cmd.down && now - cmd.time > MAX_PRESS_AGE) {
			continue;
		}
		const KeyInputFlags flags = cmd.down ? KeyInputFlags::DOWN : KeyInputFlags::UP;
		if (cmd.type == CommandType::PSP_BUTTON) {
			g_controlMapper.PSPKey(DEVICE_ID_TOUCH, cmd.code, flags);
			if (cmd.down) {
				g_heldButtons |= cmd.code;
			} else {
				g_heldButtons &= ~cmd.code;
			}
		} else {
			g_controlMapper.PSPKey(DEVICE_ID_TOUCH, cmd.code, flags);
			if (cmd.code == VIRTKEY_SAVE_STATE && cmd.down) {
				// Saving is asynchronous and doesn't update SaveState's cached directory listing,
				// so rescan a bit later (twice, in case the save is slow).
				g_rescanTimes.push_back(now + 1.0);
				g_rescanTimes.push_back(now + 4.0);
			}
			auto it = std::find(g_heldVirtKeys.begin(), g_heldVirtKeys.end(), cmd.code);
			if (cmd.down && it == g_heldVirtKeys.end()) {
				g_heldVirtKeys.push_back(cmd.code);
			} else if (!cmd.down && it != g_heldVirtKeys.end()) {
				g_heldVirtKeys.erase(it);
			}
		}
	}
}

void ApplyAnalog(bool inGame) {
	bool dirty[2];
	float values[2][2];
	{
		std::lock_guard<std::mutex> guard(g_analogLock);
		memcpy(dirty, g_analogDirty, sizeof(dirty));
		memcpy(values, g_analog, sizeof(values));
		g_analogDirty[0] = false;
		g_analogDirty[1] = false;
	}
	for (int stick = 0; stick < 2; stick++) {
		if (!dirty[stick]) {
			continue;
		}
		const bool centered = values[stick][0] == 0.0f && values[stick][1] == 0.0f;
		// Only write a centered stick if we moved it, so we don't fight a physical stick.
		if (inGame && (!centered || g_analogHeld[stick])) {
			__CtrlSetAnalogXY(stick, values[stick][0], values[stick][1]);
		}
		g_analogHeld[stick] = inGame && !centered;
	}
}

const char *UIStateName(GlobalUIState state) {
	switch (state) {
	case UISTATE_MENU: return "menu";
	case UISTATE_PAUSEMENU: return "paused";
	case UISTATE_INGAME: return "ingame";
	case UISTATE_EXIT: return "exit";
	case UISTATE_EXCEPTION: return "exception";
	default: return "unknown";
	}
}

void UpdateIcon(const std::string &gamePath) {
	if (gamePath != g_lastGamePath) {
		g_lastGamePath = gamePath;
		g_iconInfo.reset();
		{
			std::lock_guard<std::mutex> guard(g_statusLock);
			g_iconData.clear();
			g_status.iconGeneration++;
		}
		if (!gamePath.empty() && g_gameInfoCache) {
			g_iconInfo = g_gameInfoCache->GetInfo(nullptr, Path(gamePath), GameInfoFlags::ICON);
		}
	}

	if (g_iconInfo && g_iconInfo->icon.dataLoaded) {
		std::string data;
		{
			std::lock_guard<std::mutex> guard(g_iconInfo->lock);
			data = g_iconInfo->icon.data;
		}
		g_iconInfo.reset();
		std::lock_guard<std::mutex> guard(g_statusLock);
		g_iconData = std::move(data);
		g_status.iconGeneration++;
	}
}

void UpdateStatus(double now) {
	const GlobalUIState uiState = GetUIState();
	const bool running = PSP_IsInited() && (uiState == UISTATE_INGAME || uiState == UISTATE_PAUSEMENU);

	std::string gamePath = running ? PSP_CoreParameter().fileToStart.ToString() : std::string();
	UpdateIcon(gamePath);

	Status s;
	s.state = UIStateName(uiState);
	s.stepping = Core_IsStepping();
	s.path = gamePath;
	if (running) {
		s.gameId = g_paramSFO.GetDiscID();
		s.discVersion = g_paramSFO.GetValueString("DISC_VERSION");
		s.title = g_paramSFO.GetValueString("TITLE");
		__DisplayGetFPS(&s.vps, &s.fps, &s.actualFps);
		s.fastForward = PSP_CoreParameter().fastForward;
		s.fpsLimit = (int)PSP_CoreParameter().fpsLimit;
	}
	s.slot = g_Config.iCurrentStateSlot;
	s.slotCount = g_Config.iSaveStateSlotCount;
	s.frame = ++g_frameCounter;

	bool rescan = false;
	for (auto it = g_rescanTimes.begin(); it != g_rescanTimes.end();) {
		if (now >= *it) {
			rescan = true;
			it = g_rescanTimes.erase(it);
		} else {
			++it;
		}
	}

	// Done outside the lock, Rescan does file I/O (the UI thread reads the status).
	const bool refreshSlot = running && (rescan || s.slot != g_lastSlotChecked || now - g_lastSlotCheck > SLOT_INFO_INTERVAL);
	if (refreshSlot) {
		std::string prefix = SaveState::GetGamePrefix(g_paramSFO);
		if (rescan) {
			SaveState::Rescan(prefix);
		}
		g_slotUsed = SaveState::HasSaveInSlot(prefix, s.slot);
		g_slotDate = g_slotUsed ? SaveState::GetSlotDateAsString(prefix, s.slot) : std::string();
		g_lastSlotChecked = s.slot;
		g_lastSlotCheck = now;
	} else if (!running) {
		g_slotUsed = false;
		g_slotDate.clear();
		g_lastSlotChecked = -1;
	}
	s.slotUsed = g_slotUsed;
	s.slotDate = g_slotDate;

	std::lock_guard<std::mutex> guard(g_statusLock);
	s.iconGeneration = g_status.iconGeneration;
	g_status = std::move(s);
}

// Sampled once per host frame, so a press shorter than a frame can be missed. Games poll at most
// once per frame too, so that's rarely a press the game saw.
void UpdateButtonPresses(double now, bool running) {
	const uint32_t buttons = running ? __CtrlPeekButtons() : 0;
	const uint32_t pressed = buttons & ~g_prevButtons;
	g_prevButtons = buttons;
	if (!pressed) {
		return;
	}
	std::lock_guard<std::mutex> guard(g_pressLock);
	if (g_presses.size() >= MAX_BUTTON_PRESSES) {
		g_presses.erase(g_presses.begin());
	}
	g_presses.push_back(ButtonPress{ pressed, (int32_t)(now * 1000.0) });
}

void ProcessFileRequest(bool running) {
	FileRequest req;
	{
		std::lock_guard<std::mutex> guard(g_fileLock);
		auto it = std::find_if(g_fileRequests.begin(), g_fileRequests.end(), [](const FileRequest &r) { return !r.done; });
		if (it == g_fileRequests.end()) {
			return;
		}
		req.id = it->id;
		req.path = it->path;
		req.offset = it->offset;
		req.size = it->size;
	}

	std::vector<uint8_t> data;
	bool ok = false;
	if (running) {
		int handle = pspFileSystem.OpenFile(req.path, FILEACCESS_READ);
		if (handle >= 0) {
			data.resize(req.size);
			pspFileSystem.SeekFile(handle, (s32)req.offset, FILEMOVE_BEGIN);
			size_t got = pspFileSystem.ReadFile(handle, data.data(), req.size);
			pspFileSystem.CloseFile(handle);
			data.resize(got);
			ok = true;
		} else {
			WARN_LOG(Log::System, "Duo: can't open game file %s", req.path.c_str());
		}
	}

	std::lock_guard<std::mutex> guard(g_fileLock);
	for (FileRequest &r : g_fileRequests) {
		if (r.id == req.id) {
			r.done = true;
			r.ok = ok;
			r.data = std::move(data);
			break;
		}
	}
}

uint32_t FindPattern(const FindRequest &req) {
	uint32_t span = 0;
	for (uint32_t off : req.offsets) {
		span = std::max(span, off + 4);
	}
	if (req.end <= req.start || req.end - req.start < span || !Memory::IsValidRange(req.start, req.end - req.start)) {
		return 0;
	}
	const u8 *base = Memory::GetPointerUnchecked(req.start);
	const uint32_t last = req.end - req.start - span;
	const uint32_t firstOff = req.offsets[0];
	const uint32_t firstVal = req.values[0];
	for (uint32_t pos = 0; pos <= last; pos += 4) {
		uint32_t v;
		memcpy(&v, base + pos + firstOff, 4);
		if (v != firstVal) {
			continue;
		}
		bool match = true;
		for (size_t i = 1; i < req.offsets.size() && match; i++) {
			memcpy(&v, base + pos + req.offsets[i], 4);
			match = v == req.values[i];
		}
		if (match) {
			return req.start + pos;
		}
	}
	return 0;
}

void ProcessFindRequest(bool running) {
	if (!running) {
		return;
	}
	std::lock_guard<std::mutex> guard(g_findLock);
	for (FindRequest &r : g_findRequests) {
		if (!r.done) {
			r.result = FindPattern(r);
			r.done = true;
			return;
		}
	}
}

void UpdateWatches(bool running) {
	std::lock_guard<std::mutex> guard(g_watchLock);
	for (Watch &w : g_watches) {
		w.valid = running && Memory::IsValidRange(w.address, w.size);
		if (w.valid) {
			memcpy(w.data.data(), Memory::GetPointerUnchecked(w.address), w.size);
		}
	}
}

}  // namespace

void DuoBridge_OnFrame() {
	if (!g_active) {
		if (g_heldButtons || !g_heldVirtKeys.empty() || g_analogHeld[0] || g_analogHeld[1]) {
			ReleaseEverything();
		}
		g_releaseAllRequested = false;
		std::lock_guard<std::mutex> guard(g_commandLock);
		g_commands.clear();
		return;
	}

	const double now = time_now_d();
	const GlobalUIState uiState = GetUIState();
	const bool inGame = PSP_IsInited() && uiState == UISTATE_INGAME;
	const bool running = PSP_IsInited() && (uiState == UISTATE_INGAME || uiState == UISTATE_PAUSEMENU);

	ProcessCommands(now);
	ApplyAnalog(inGame);
	UpdateStatus(now);
	UpdateButtonPresses(now, inGame);
	UpdateWatches(running);
	ProcessFileRequest(running);
	ProcessFindRequest(running);
}

extern "C" {

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeSetActive(JNIEnv *, jclass, jboolean active) {
	INFO_LOG(Log::System, "Duo: second screen %s", active ? "active" : "inactive");
	g_active = active;
}

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeButton(JNIEnv *, jclass, jint mask, jboolean down) {
	std::lock_guard<std::mutex> guard(g_commandLock);
	g_commands.push_back(Command{ CommandType::PSP_BUTTON, (uint32_t)mask, down != 0, time_now_d() });
}

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeVirtKey(JNIEnv *, jclass, jint vkey, jboolean down) {
	if ((uint32_t)vkey < VIRTKEY_FIRST || (uint32_t)vkey >= VIRTKEY_LAST) {
		WARN_LOG(Log::System, "Duo: bad virtual key %08x", (uint32_t)vkey);
		return;
	}
	std::lock_guard<std::mutex> guard(g_commandLock);
	g_commands.push_back(Command{ CommandType::VIRT_KEY, (uint32_t)vkey, down != 0, time_now_d() });
}

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeAnalog(JNIEnv *, jclass, jint stick, jfloat x, jfloat y) {
	if (stick < 0 || stick > 1) {
		return;
	}
	std::lock_guard<std::mutex> guard(g_analogLock);
	g_analog[stick][0] = std::max(-1.0f, std::min(1.0f, (float)x));
	g_analog[stick][1] = std::max(-1.0f, std::min(1.0f, (float)y));
	g_analogDirty[stick] = true;
}

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeReleaseAll(JNIEnv *, jclass) {
	{
		std::lock_guard<std::mutex> guard(g_commandLock);
		g_commands.clear();
	}
	{
		std::lock_guard<std::mutex> guard(g_analogLock);
		g_analogDirty[0] = false;
		g_analogDirty[1] = false;
	}
	g_releaseAllRequested = true;
}

JNIEXPORT jstring JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeGetStatus(JNIEnv *env, jclass) {
	json::JsonWriter w;
	{
		std::lock_guard<std::mutex> guard(g_statusLock);
		const Status &s = g_status;
		w.begin();
		w.writeString("state", s.state);
		w.writeBool("stepping", s.stepping);
		w.writeString("gameId", s.gameId);
		w.writeString("discVersion", s.discVersion);
		w.writeString("title", s.title);
		w.writeString("path", s.path);
		w.writeFloat("vps", s.vps);
		w.writeFloat("fps", s.fps);
		w.writeFloat("actualFps", s.actualFps);
		w.writeBool("fastForward", s.fastForward);
		w.writeInt("fpsLimit", s.fpsLimit);
		w.writeInt("slot", s.slot);
		w.writeInt("slotCount", s.slotCount);
		w.writeBool("slotUsed", s.slotUsed);
		w.writeString("slotDate", s.slotDate);
		w.writeInt("iconGeneration", s.iconGeneration);
		w.writeUint("frame", s.frame);
		w.end();
	}
	return env->NewStringUTF(w.str().c_str());
}

JNIEXPORT jbyteArray JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeGetIcon(JNIEnv *env, jclass) {
	std::lock_guard<std::mutex> guard(g_statusLock);
	if (g_iconData.empty()) {
		return nullptr;
	}
	jbyteArray result = env->NewByteArray((jsize)g_iconData.size());
	env->SetByteArrayRegion(result, 0, (jsize)g_iconData.size(), (const jbyte *)g_iconData.data());
	return result;
}

JNIEXPORT jint JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeRequestGameFile(JNIEnv *env, jclass, jstring jpath, jint offset, jint size) {
	if (!jpath || offset < 0 || size <= 0 || (uint32_t)size > MAX_FILE_READ) {
		return 0;
	}
	const char *chars = env->GetStringUTFChars(jpath, nullptr);
	std::string path = chars;
	env->ReleaseStringUTFChars(jpath, chars);
	// Only the game's own read-only files.
	if (path.rfind("disc0:/", 0) != 0 && path.rfind("umd0:/", 0) != 0) {
		return 0;
	}
	std::lock_guard<std::mutex> guard(g_fileLock);
	if (g_fileRequests.size() >= 16) {
		return 0;
	}
	FileRequest req;
	req.id = g_nextFileId++;
	req.path = path;
	req.offset = (uint32_t)offset;
	req.size = (uint32_t)size;
	g_fileRequests.push_back(std::move(req));
	return g_fileRequests.back().id;
}

// Null while pending. When done, returns the data (empty if the read failed) and forgets the request.
JNIEXPORT jbyteArray JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativePollGameFile(JNIEnv *env, jclass, jint id) {
	std::vector<uint8_t> data;
	{
		std::lock_guard<std::mutex> guard(g_fileLock);
		auto it = std::find_if(g_fileRequests.begin(), g_fileRequests.end(), [id](const FileRequest &r) { return r.id == id; });
		if (it == g_fileRequests.end()) {
			return env->NewByteArray(0);
		}
		if (!it->done) {
			return nullptr;
		}
		data = std::move(it->data);
		g_fileRequests.erase(it);
	}
	jbyteArray result = env->NewByteArray((jsize)data.size());
	if (!data.empty()) {
		env->SetByteArrayRegion(result, 0, (jsize)data.size(), (const jbyte *)data.data());
	}
	return result;
}

JNIEXPORT void JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeCancelGameFiles(JNIEnv *, jclass) {
	{
		std::lock_guard<std::mutex> guard(g_fileLock);
		g_fileRequests.clear();
	}
	std::lock_guard<std::mutex> guard(g_findLock);
	g_findRequests.clear();
}

JNIEXPORT jint JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeRequestFind(JNIEnv *env, jclass, jint start, jint end, jintArray joffsets, jintArray jvalues) {
	if (!joffsets || !jvalues) {
		return 0;
	}
	jsize n = env->GetArrayLength(joffsets);
	if (n == 0 || n != env->GetArrayLength(jvalues) || n > 1024) {
		return 0;
	}
	FindRequest req;
	req.start = (uint32_t)start;
	req.end = (uint32_t)end;
	req.offsets.resize(n);
	req.values.resize(n);
	env->GetIntArrayRegion(joffsets, 0, n, (jint *)req.offsets.data());
	env->GetIntArrayRegion(jvalues, 0, n, (jint *)req.values.data());
	for (uint32_t off : req.offsets) {
		if (off & 3 || off > 0x10000) {
			return 0;
		}
	}
	std::lock_guard<std::mutex> guard(g_findLock);
	if (g_findRequests.size() >= 16) {
		return 0;
	}
	req.id = g_nextFindId++;
	g_findRequests.push_back(std::move(req));
	return g_findRequests.back().id;
}

// -1 while pending, then the address of the first match (0 if none). The request is gone afterwards.
JNIEXPORT jlong JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativePollFind(JNIEnv *, jclass, jint id) {
	std::lock_guard<std::mutex> guard(g_findLock);
	auto it = std::find_if(g_findRequests.begin(), g_findRequests.end(), [id](const FindRequest &r) { return r.id == id; });
	if (it == g_findRequests.end()) {
		return 0;
	}
	if (!it->done) {
		return -1;
	}
	jlong result = it->result;
	g_findRequests.erase(it);
	return result;
}

JNIEXPORT jintArray JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeGetButtonPresses(JNIEnv *env, jclass) {
	std::vector<ButtonPress> presses;
	{
		std::lock_guard<std::mutex> guard(g_pressLock);
		presses.swap(g_presses);
	}
	if (presses.empty()) {
		return nullptr;
	}
	std::vector<jint> flat;
	flat.reserve(presses.size() * 2);
	for (const ButtonPress &p : presses) {
		flat.push_back((jint)p.buttons);
		flat.push_back((jint)p.timeMs);
	}
	jintArray result = env->NewIntArray((jsize)flat.size());
	env->SetIntArrayRegion(result, 0, (jsize)flat.size(), flat.data());
	return result;
}

JNIEXPORT jboolean JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeSetWatches(JNIEnv *env, jclass, jintArray jaddresses, jintArray jsizes) {
	std::vector<Watch> watches;
	if (jaddresses && jsizes) {
		jsize count = env->GetArrayLength(jaddresses);
		if (count != env->GetArrayLength(jsizes) || count > MAX_WATCHES) {
			return false;
		}
		std::vector<jint> addresses(count), sizes(count);
		env->GetIntArrayRegion(jaddresses, 0, count, addresses.data());
		env->GetIntArrayRegion(jsizes, 0, count, sizes.data());
		for (jsize i = 0; i < count; i++) {
			if (sizes[i] <= 0 || (uint32_t)sizes[i] > MAX_WATCH_BYTES) {
				return false;
			}
			Watch w;
			w.address = (uint32_t)addresses[i];
			w.size = (uint32_t)sizes[i];
			w.data.resize(w.size);
			watches.push_back(std::move(w));
		}
	}
	std::lock_guard<std::mutex> guard(g_watchLock);
	g_watches = std::move(watches);
	return true;
}

JNIEXPORT jbyteArray JNICALL Java_org_ppsspp_ppsspp_duo_DuoNative_nativeGetWatch(JNIEnv *env, jclass, jint index) {
	std::lock_guard<std::mutex> guard(g_watchLock);
	if (index < 0 || index >= (jint)g_watches.size() || !g_watches[index].valid) {
		return nullptr;
	}
	const Watch &w = g_watches[index];
	jbyteArray result = env->NewByteArray((jsize)w.size);
	env->SetByteArrayRegion(result, 0, (jsize)w.size, (const jbyte *)w.data.data());
	return result;
}

}  // extern "C"

#endif  // PPSSPP_PLATFORM(ANDROID)
