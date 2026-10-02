package org.ppsspp.ppsspp.duo;

import org.ppsspp.ppsspp.duo.games.GranTurismoMod;
import org.ppsspp.ppsspp.duo.games.GtaLcsMapMod;
import org.ppsspp.ppsspp.duo.games.LuminesMod;
import org.ppsspp.ppsspp.duo.games.PataponMod;
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
		// Game-specific mods. They hide themselves for other games.
		mods.add(new PataponMod());
		mods.add(new GtaLcsMapMod());
		mods.add(new LuminesMod());
		mods.add(new GranTurismoMod());
		mods.add(new BlankMod());
		mods.add(new DiagnosticsMod());
		return Collections.unmodifiableList(mods);
	}
}
