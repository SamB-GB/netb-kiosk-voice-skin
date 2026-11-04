// SPDX-License-Identifier: Apache-2.0
package uk.netb.kiosk.hearth;

import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** The skin's decisions, without a device: when it draws, which look, what the label says, and its settings. */
public final class HearthVoiceSkinTest {
    static final class Host implements PluginHost {
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, CommandCallback> reads = new HashMap<>();
        final Map<String, OverlaySpec> overlays = new HashMap<>();
        boolean older;
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) { reads.put(command, callback); }
        public void subscribe(String event) { if (older) throw new IllegalArgumentException("Unknown KS event"); subscriptions.add(event); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { assert key.matches("[a-z][a-z0-9_]{0,39}") : key; overlays.put(key, spec); }
        public void hideOverlay(String key) { overlays.remove(key); }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) {}
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    public static void main(String[] args) {
        Host host = new Host();
        HearthVoiceSkin skin = new HearthVoiceSkin();
        skin.start(host, Collections.<String, Object>emptyMap());
        assert host.subscriptions.containsAll(Arrays.asList(HearthVoiceSkin.EVENTS));
        assert host.reads.containsKey("getVoiceState") && host.reads.containsKey("getVoiceText") && host.reads.containsKey("getVoiceAppearance");

        // Another skin picked: Kiosk Satellite draws the turn
        host.reads.get("getVoiceState").onResult(true, map("state", "idle", "skinSelected", false, "overlay", "hidden"), null);
        skin.onEvent("ks.voice.overlay", map("overlay", "full"));
        assert host.overlays.isEmpty() && skin.drawn() == null;

        // Picked: full screen, on top, every touch passing through
        skin.onEvent("ks.voice.skin", map("selected", true));
        OverlaySpec full = host.overlays.get(HearthVoiceSkin.KEY);
        assert full != null && full.onTop() && !full.touchable() && "full".equals(skin.drawn());

        // The label follows the turn
        skin.onEvent("ks.voice.state", map("state", "listening"));
        skin.onEvent("ks.voice.text", map("command", "turn the living room lights to", "answer", "", "tools", Collections.emptyList()));
        assert "Listening".equals(skin.label()) : skin.label();
        skin.onEvent("ks.voice.state", map("state", "processing"));
        assert "Thinking".equals(skin.label()) : skin.label();
        skin.onEvent("ks.voice.state", map("state", "responding"));
        skin.onEvent("ks.voice.text", map("command", "turn the living room lights to 40%", "answer", "Done. The living room lights are at 40%.", "tools", Arrays.asList("Floor lamp · 40%")));
        assert "".equals(skin.label()) : skin.label();
        skin.onEvent("ks.voice.text", map("command", "", "answer", "Dinner is ready", "announcement", true));
        assert "Announcement".equals(skin.label());
        assert "Thinking".equals(HearthVoiceSkin.label("idle", "what's the time", "", "", false));

        // Docked replaces the full screen look, and back
        skin.onEvent("ks.voice.overlay", map("overlay", "docked"));
        OverlaySpec docked = host.overlays.get(HearthVoiceSkin.KEY);
        assert docked != null && docked != full && "docked".equals(skin.drawn()) && !docked.touchable();
        skin.onEvent("ks.voice.overlay", map("overlay", "hidden"));
        assert host.overlays.isEmpty() && skin.drawn() == null;

        // Appearance settings
        skin.onEvent("ks.voice.appearance", map("theme", "dark", "background", 0.3, "textScale", 3.0, "reactive", false));
        Object[] a = skin.appearanceNow();
        assert "dark".equals(a[0]) && Float.valueOf(0.3f).equals(a[1]) && Float.valueOf(2f).equals(a[2]) && Boolean.FALSE.equals(a[3]);

        // Unpicked mid turn: it steps aside
        skin.onEvent("ks.voice.overlay", map("overlay", "full"));
        assert host.overlays.containsKey(HearthVoiceSkin.KEY);
        skin.onEvent("ks.voice.skin", map("selected", false));
        assert host.overlays.isEmpty();

        // An older Kiosk Satellite: no events, no reads, a status line saying why
        Host older = new Host();
        older.older = true;
        HearthVoiceSkin old = new HearthVoiceSkin();
        old.start(older, Collections.<String, Object>emptyMap());
        assert older.reads.isEmpty();

        skin.stop();
        skin.onEvent("ks.voice.skin", map("selected", true));
        assert skin.drawn() == null;
        System.out.println("HearthVoiceSkinTest passed");
    }
}
