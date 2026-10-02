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

#pragma once

// PPSSPP Duo: native side of the second-screen host (org.ppsspp.ppsspp.duo.DuoNative).
//
// The second screen is drawn entirely by Android views in a Presentation, so it never waits on
// (or slows down) the emulator. This bridge only moves small amounts of data between the two:
// - input from second-screen mods is queued from the UI thread and applied on the emu thread,
// - a status snapshot and mod-requested memory ranges are copied on the emu thread, between frames,
//   so the UI thread never touches emulator state directly.

// Emu thread, after every NativeFrame().
void DuoBridge_OnFrame();
