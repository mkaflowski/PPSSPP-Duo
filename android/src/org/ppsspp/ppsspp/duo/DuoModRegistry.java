package org.ppsspp.ppsspp.duo;

import org.ppsspp.ppsspp.duo.mods.BlankMod;
import org.ppsspp.ppsspp.duo.mods.DashboardMod;
import org.ppsspp.ppsspp.duo.mods.DiagnosticsMod;
import org.ppsspp.ppsspp.duo.mods.GamepadMod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The built-in mods, in tab order. New mods are added here.
public final class DuoModRegistry {
	private DuoModRegistry() {}

	public static final String DEFAULT_MOD_ID = DashboardMod.ID;

	public static List<DuoMod> createAll() {
		List<DuoMod> mods = new ArrayList<>();
		mods.add(new DashboardMod());
		mods.add(new GamepadMod());
		mods.add(new BlankMod());
		mods.add(new DiagnosticsMod());
		return Collections.unmodifiableList(mods);
	}
}
