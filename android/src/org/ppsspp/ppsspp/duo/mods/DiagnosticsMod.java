package org.ppsspp.ppsspp.duo.mods;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Typeface;
import android.view.Display;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.ppsspp.ppsspp.R;
import org.ppsspp.ppsspp.duo.DuoMod;
import org.ppsspp.ppsspp.duo.DuoModContext;
import org.ppsspp.ppsspp.duo.DuoStatus;
import org.ppsspp.ppsspp.duo.DuoUi;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

// Developer view: raw status, display info and a memory watch. Also the reference for mods that
// read game memory (companion maps, stats): set watches in onCreateView, read them in onStatus.
public final class DiagnosticsMod extends DuoMod {
	public static final String ID = "diagnostics";

	// Start of PSP user memory, where most games load their main module.
	private static final int WATCH_ADDRESS = 0x08804000;
	private static final int WATCH_SIZE = 128;

	private DuoModContext host;
	private TextView text;
	private final StringBuilder sb = new StringBuilder();

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public String getTitle(Context context) {
		return context.getString(R.string.duo_mod_diagnostics);
	}

	@Override
	public String getDescription(Context context) {
		return context.getString(R.string.duo_mod_diagnostics_desc);
	}

	@Override
	public long getStatusIntervalMs() {
		return 500;
	}

	@Override
	public View onCreateView(DuoModContext host) {
		this.host = host;
		Context ctx = host.getContext();
		host.setMemoryWatches(new int[] {WATCH_ADDRESS}, new int[] {WATCH_SIZE});

		LinearLayout root = new LinearLayout(ctx);
		root.setOrientation(LinearLayout.VERTICAL);
		int pad = DuoUi.dp(ctx, 12);

		TextView copy = DuoUi.button(ctx, ctx.getString(R.string.duo_diag_copy_logs));
		copy.setOnClickListener(v -> copyLogs(copy));
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, DuoUi.dp(ctx, 48));
		lp.setMargins(pad, pad, pad, 0);
		root.addView(copy, lp);

		ScrollView scroll = new ScrollView(ctx);
		text = DuoUi.text(ctx, "", 13, DuoUi.COLOR_TEXT);
		text.setTypeface(Typeface.MONOSPACE);
		text.setPadding(pad, pad, pad, pad);
		scroll.addView(text);
		root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
		return root;
	}

	// Copies the status shown here and the app's own log lines (tag PPSSPPDuo, its own process only,
	// which Android lets an app read) to the clipboard, to paste into a message.
	private void copyLogs(View button) {
		Context ctx = button.getContext();
		String header = text != null ? text.getText().toString() : "";
		new Thread(() -> {
			StringBuilder out = new StringBuilder(header).append("\n--- log ---\n");
			int lines = 0;
			try {
				Process p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000", "-s", "PPSSPPDuo:*")
					.redirectErrorStream(true).start();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
					java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
					String line;
					while ((line = r.readLine()) != null) {
						tail.add(line);
						if (tail.size() > 300) {
							tail.poll();
						}
					}
					for (String l : tail) {
						out.append(l).append('\n');
					}
					lines = tail.size();
				}
			} catch (Exception e) {
				out.append("can't read the log: ").append(e).append('\n');
			}
			final int n = lines;
			button.post(() -> {
				ClipboardManager cm = (ClipboardManager)ctx.getSystemService(Context.CLIPBOARD_SERVICE);
				if (cm != null) {
					cm.setPrimaryClip(ClipData.newPlainText("PPSSPP Duo logs", out.toString()));
					Toast.makeText(ctx, ctx.getString(R.string.duo_diag_logs_copied, n), Toast.LENGTH_SHORT).show();
				}
			});
		}).start();
	}

	@Override
	public void onStatus(DuoStatus s) {
		sb.setLength(0);
		sb.append("state      ").append(s.state).append(s.stepping ? " (stepping)" : "").append('\n');
		sb.append("game       ").append(s.gameId).append("  ").append(s.title).append('\n');
		sb.append("path       ").append(s.path).append('\n');
		sb.append(String.format(Locale.US, "fps        %.1f  vps %.1f  actual %.1f  speed %d%%%n", s.fps, s.vps, s.actualFps, s.speedPercent()));
		sb.append("fastFwd    ").append(s.fastForward).append("  fpsLimit ").append(s.fpsLimit).append('\n');
		sb.append("slot       ").append(s.slot + 1).append('/').append(s.slotCount)
			.append(s.slotUsed ? "  " + s.slotDate : "  (empty)").append('\n');
		sb.append("frame      ").append(s.frame).append("  icon gen ").append(s.iconGeneration).append('\n');

		Display d = host.getDisplay();
		Point size = new Point();
		d.getRealSize(size);
		sb.append('\n');
		sb.append("display    #").append(d.getDisplayId()).append(" \"").append(d.getName()).append("\"\n");
		sb.append(String.format(Locale.US, "           %dx%d @ %.1f Hz, %d dpi, rotation %d, flags 0x%x%n",
			size.x, size.y, d.getRefreshRate(), host.getContext().getResources().getDisplayMetrics().densityDpi,
			d.getRotation(), d.getFlags()));

		sb.append('\n');
		sb.append(String.format(Locale.US, "memory     %08x (%d bytes)%n", WATCH_ADDRESS, WATCH_SIZE));
		byte[] mem = host.readMemoryWatch(0);
		if (mem == null) {
			sb.append("           not readable (no game?)\n");
		} else {
			for (int row = 0; row < mem.length; row += 16) {
				sb.append(String.format(Locale.US, "  %08x ", WATCH_ADDRESS + row));
				for (int i = row; i < row + 16 && i < mem.length; i++) {
					sb.append(String.format(Locale.US, " %02x", mem[i] & 0xFF));
				}
				sb.append("  ");
				for (int i = row; i < row + 16 && i < mem.length; i++) {
					int c = mem[i] & 0xFF;
					sb.append(c >= 32 && c < 127 ? (char)c : '.');
				}
				sb.append('\n');
			}
		}
		text.setText(sb);
	}

	@Override
	public void onDestroyView() {
		host = null;
		text = null;
	}
}
