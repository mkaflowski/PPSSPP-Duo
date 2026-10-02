package org.ppsspp.ppsspp.duo.mods;

import android.content.Context;
import android.graphics.Color;
import android.view.View;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;

// Black screen with the tab bar hidden. Saves power on OLED panels and removes distractions.
// Tapping the top edge brings the tab bar back.
public final class BlankMod extends DuoMod {
	public static final String ID = "blank";

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_blank);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_blank_desc);
	}

	@Override
	public long getStatusIntervalMs() {
		// Nothing to show; only polled so the host notices game changes.
		return 1000;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		View v = new View(host.getContext());
		v.setBackgroundColor(Color.BLACK);
		host.setTabBarVisible(false);
		return v;
	}
}
