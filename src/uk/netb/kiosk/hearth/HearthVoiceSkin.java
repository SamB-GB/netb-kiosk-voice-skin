// SPDX-License-Identifier: Apache-2.0
// Based on the Hello World voice skin from the Kiosk Satellite Plugins SDK (Apache-2.0).
package uk.netb.kiosk.hearth;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Hearth, NETb Home's look, as a Voice Satellite skin. Pick it in Voice Satellite > Appearance > Assistant 1 skin
 * (or Assistant 2) and Kiosk Satellite draws none of its own overlay: this draws the turn instead.
 *
 * One warm card near the bottom of the screen: a violet orb whose bars move with the voice, a turning ring while
 * the assistant thinks, a small label (Listening, Thinking, Answering) and the words. Once there's an answer, what you
 * said sits above it in grey. Full screen, the dashboard behind it darkens towards the bottom; docked, the same card is
 * smaller and the dashboard stays in view and usable. It follows Voice Satellite's Appearance settings: Theme
 * (light, dark or auto), Background (how dark the backdrop is), Text size and Reactive activity bar. Every touch passes
 * through, so a double tap still dismisses the turn.
 */
public final class HearthVoiceSkin implements KioskPlugin {
    static final String KEY = "hearth_skin";
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.state", "voice.level", "voice.text", "voice.appearance"};
    /** The full screen backdrop's darkest point (at the bottom) when Background is left at Skin default */
    static final float DEFAULT_BACKGROUND = 0.5f;
    static final int DOCK_INSET = 24;

    // Hearth's colours (the NETb Home tokens)
    static final int SURFACE_LIGHT = 0xFFFBFAF8, SURFACE_DARK = 0xFF1D1B19;
    static final int TILE_LIGHT = 0xFFF2EFEA, TILE_DARK = 0xFF272522;
    static final int INK_LIGHT = 0xFF1D1B18, INK_DARK = 0xFFF2EEE8;
    static final int MUTED_LIGHT = 0xFF68625A, MUTED_DARK = 0xFFA9A297;
    static final int VIOLET_LIGHT = 0xFF6E5DE7, VIOLET_DARK = 0xFF9488FF;
    static final int VIOLET_INK_LIGHT = 0xFF4F3FC4, VIOLET_INK_DARK = 0xFFB4ABFF;
    static final int AMBER = 0xFFF3B23A;
    static final int SHADE = 0xFF1D1B18;

    private PluginHost host;
    private int generation;
    private boolean selected;
    private boolean skinHeard;
    private String overlay = "hidden";
    /** What it draws now: null, "full" or "docked" */
    private String drawn;
    /** Read by the views on the main thread */
    private volatile String state = "idle";
    private volatile float level;
    private volatile String command = "";
    private volatile String answer = "";
    private volatile String tool = "";
    private volatile boolean announcement;
    private volatile String theme = "auto";
    private volatile Float background;
    private volatile float textScale = 1;
    private volatile boolean reactive = true;
    private boolean appearanceHeard;
    private boolean stateHeard;
    /** The showing view. Touched on the main thread only */
    private SkinView view;

    /* ---------------------------------------------------------------- KioskPlugin */

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        int current = ++generation;
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            host.status("This skin needs a Kiosk Satellite with plugin voice skins (2026.10.45 or newer).", true);
            return;
        }
        try {
            read(current);
        } catch (UnsupportedOperationException olderHost) {
            // Events alone still bring the skin up
        }
        host.status("Pick Hearth in Voice Satellite > Appearance > Assistant 1 skin.", false);
    }

    @Override public void configure(Map<String, Object> settings) {}

    @Override public void execute(String command, Map<String, Object> arguments) {}

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null || payload == null) return;
        switch (event) {
            case "ks.voice.skin":
                selected = Boolean.TRUE.equals(payload.get("selected"));
                skinHeard = true;
                break;
            case "ks.voice.overlay":
                overlay = String.valueOf(payload.get("overlay"));
                break;
            case "ks.voice.state":
                stateHeard = true;
                state = payload.get("state") instanceof String ? (String) payload.get("state") : "idle";
                refreshView();
                return;
            case "ks.voice.level":
                Object value = payload.get("level");
                level = value instanceof Number ? ((Number) value).floatValue() : 0;
                return;
            case "ks.voice.text":
                words(payload);
                return;
            case "ks.voice.appearance":
                appearanceHeard = true;
                appearance(payload);
                return;
            default:
                return;
        }
        update();
    }

    @Override public synchronized void stop() {
        if (host != null && drawn != null) {
            try { host.hideOverlay(KEY); } catch (RuntimeException ignored) { /* already gone */ }
        }
        host = null;
        generation++;
        selected = false;
        skinHeard = false;
        stateHeard = false;
        appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        state = "idle";
        level = 0;
        command = answer = tool = "";
        announcement = false;
        theme = "auto";
        background = null;
        textScale = 1;
        reactive = true;
    }

    /* ---------------------------------------------------------------- What it knows */

    private void read(int current) {
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (HearthVoiceSkin.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> s = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(s.get("skinSelected"));
                if ("hidden".equals(overlay) && s.get("overlay") instanceof String) overlay = (String) s.get("overlay");
                if (!stateHeard && s.get("state") instanceof String) state = (String) s.get("state");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (HearthVoiceSkin.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (HearthVoiceSkin.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    private void appearance(Map<?, ?> s) {
        theme = s.get("theme") instanceof String ? (String) s.get("theme") : "auto";
        background = s.get("background") instanceof Number ? ((Number) s.get("background")).floatValue() : null;
        textScale = s.get("textScale") instanceof Number ? Math.max(0.5f, Math.min(2f, ((Number) s.get("textScale")).floatValue())) : 1;
        reactive = !Boolean.FALSE.equals(s.get("reactive"));
        SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    private void words(Map<?, ?> t) {
        command = t.get("command") instanceof String ? (String) t.get("command") : "";
        answer = t.get("answer") instanceof String ? (String) t.get("answer") : "";
        announcement = Boolean.TRUE.equals(t.get("announcement"));
        Object tools = t.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        refreshView();
    }

    private void refreshView() {
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    /** Draw while this skin is picked and the overlay is up, in the look Overlay mode asks for */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, 280), new Skin(false));
        } else {
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, 240), new Skin(true));
        }
    }

    private static OverlaySpec animated(OverlaySpec spec, String transition, int ms) {
        try {
            return spec.enter(transition).exit(transition).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    /* For the tests */
    synchronized String drawn() { return drawn; }
    synchronized String state() { return state; }
    synchronized Object[] appearanceNow() { return new Object[] {theme, background, textScale, reactive}; }
    String label() { return label(state, command, answer, tool, announcement); }

    /** The small label over the words */
    static String label(String state, String command, String answer, String tool, boolean announcement) {
        if (announcement) return "Announcement";
        if ("listening".equals(state)) return "Listening";
        if ("processing".equals(state) || (!command.isEmpty() && answer.isEmpty() && tool.isEmpty() && !"responding".equals(state))) return "Thinking";
        if (!answer.isEmpty() || "responding".equals(state)) return "";
        return "Listening";
    }

    /* ---------------------------------------------------------------- The views */

    private final class Skin implements OverlayFactory {
        private final boolean docked;

        Skin(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme ks) {
            SkinView skin = new SkinView(context, ks, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View v, KsTheme ks) { ((SkinView) v).restyle(ks); }

        @Override public void onDestroy(View destroyed) { if (view == destroyed) view = null; }
    }

    /** Full screen: the backdrop and the card at the bottom. Docked: the card alone, a little smaller */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final LinearLayout card;
        private final Orb orb;
        private final TextView tag;
        private final TextView said;
        private final TextView main;
        private final TextView did;
        private KsTheme ks;

        SkinView(Context context, KsTheme ks, boolean docked) {
            super(context);
            this.ks = ks;
            this.docked = docked;
            int screenW = context.getResources().getDisplayMetrics().widthPixels;
            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            orb = new Orb(context);
            int orbSize = ks.px(docked ? 56 : 72);
            card.addView(orb, new LinearLayout.LayoutParams(orbSize, orbSize));
            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            tag = new TextView(context);
            said = new TextView(context);
            main = new TextView(context);
            did = new TextView(context);
            said.setMaxLines(2);
            said.setEllipsize(TextUtils.TruncateAt.END);
            main.setMaxLines(docked ? 3 : 6);
            main.setEllipsize(TextUtils.TruncateAt.END);
            did.setMaxLines(1);
            did.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(tag);
            words.addView(said);
            words.addView(main);
            words.addView(did);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            wp.leftMargin = ks.px(docked ? 16 : 20);
            card.addView(words, wp);
            int width = Math.min(ks.px(docked ? 560 : 720), screenW - ks.px(48));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            if (!docked) cp.bottomMargin = ks.px(44);
            addView(card, cp);
            restyle(ks);
            refresh();
        }

        void restyle(KsTheme next) { ks = next; restyle(); }

        boolean dark() { return "dark".equals(theme) || (!"light".equals(theme) && ks.isDark()); }

        void restyle() {
            boolean dark = dark();
            float scale = textScale;
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(dark ? SURFACE_DARK : SURFACE_LIGHT);
            shape.setCornerRadius(ks.px(docked ? 28 : 34));
            if (dark) shape.setStroke(Math.max(1, ks.px(1) / 2), 0x14FFF5E6);
            card.setBackground(shape);
            card.setElevation(ks.px(docked ? 10 : 18));
            int pad = ks.px(docked ? 14 : 18);
            card.setPadding(pad, pad, ks.px(docked ? 20 : 26), pad);
            if (docked) {
                setBackground(null);
            } else {
                // The dashboard stays visible at the top and darkens towards the card, as on the canvas
                float opacity = background != null ? background : DEFAULT_BACKGROUND;
                int bottom = Color.argb(Math.round(255 * opacity), Color.red(SHADE), Color.green(SHADE), Color.blue(SHADE));
                GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x001D1B18, 0x001D1B18, bottom});
                setBackground(shade);
            }
            ks.styleText(tag, 12 * scale, 800, "primary");
            ks.styleText(said, (docked ? 13 : 14) * scale, 600, "onSurfaceVariant");
            ks.styleText(main, (docked ? 20 : 26) * scale, 700, "onSurface");
            ks.styleText(did, 13 * scale, 700, "onSurfaceVariant");
            tag.setLetterSpacing(0.12f);
            tag.setAllCaps(true);
            main.setLetterSpacing(-0.015f);
            tag.setTextColor(dark ? VIOLET_INK_DARK : VIOLET_INK_LIGHT);
            said.setTextColor(dark ? MUTED_DARK : MUTED_LIGHT);
            main.setTextColor(dark ? INK_DARK : INK_LIGHT);
            did.setTextColor(dark ? 0xFFF5BE4F : 0xFF7A4E00);
            orb.invalidate();
        }

        void refresh() {
            String c = command; String a = answer; String t = tool;
            String l = label(state, c, a, t, announcement);
            tag.setText(l);
            tag.setVisibility(l.isEmpty() ? GONE : VISIBLE);
            boolean answered = !a.isEmpty();
            // Listening and thinking: what you're saying is the big line. Answered: it moves up, in quotes and grey
            said.setText(answered && !c.isEmpty() && !announcement ? "“" + c + "”" : "");
            said.setVisibility(said.getText().length() == 0 ? GONE : VISIBLE);
            main.setText(answered ? a : c.isEmpty() ? (announcement ? "" : "…") : c);
            did.setText(t);
            did.setVisibility(t.isEmpty() ? GONE : VISIBLE);
            orb.invalidate();
        }

        /**
         * The violet orb: bars that follow the voice (eased, so 30 readings a second still look smooth), soft rings
         * that breathe out while listening, and an amber arc that turns while the assistant thinks.
         */
        private final class Orb extends View {
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF box = new RectF();
            private float shown;

            Orb(Context context) {
                super(context);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeCap(Paint.Cap.ROUND);
            }

            @Override protected void onDraw(Canvas canvas) {
                shown += ((reactive ? level : 0) - shown) * 0.22f;
                boolean dark = dark();
                int violet = dark ? VIOLET_DARK : VIOLET_LIGHT;
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(cx, cy) * 0.86f;
                long now = SystemClock.uptimeMillis();
                String l = label(state, command, answer, tool, announcement);
                // Listening: two rings breathe out from the orb, half a beat apart
                if ("Listening".equals(l)) {
                    for (int i = 0; i < 2; i++) {
                        float p = ((now + i * 1200) % 2400) / 2400f;
                        fill.setColor(Color.argb(Math.round(90 * (1 - p)), Color.red(violet), Color.green(violet), Color.blue(violet)));
                        canvas.drawCircle(cx, cy, r * (1 + 0.16f * p), fill);
                    }
                }
                fill.setColor(violet);
                canvas.drawCircle(cx, cy, r, fill);
                // The bars: five while answering, four while listening; still while thinking
                int bars = "".equals(l) ? 5 : 4;
                float bw = r * 0.14f, gap = r * 0.12f;
                float total = bars * bw + (bars - 1) * gap;
                fill.setColor(dark ? SURFACE_DARK : SURFACE_LIGHT);
                boolean thinking = "Thinking".equals(l);
                for (int i = 0; i < bars; i++) {
                    double wave = 0.5 + 0.5 * Math.sin(now / 160.0 + i * 1.3);
                    float h = thinking ? r * 0.22f : r * (0.28f + 0.22f * (float) wave * (0.35f + shown) + 0.55f * shown * (i % 2 == 0 ? 1f : 0.7f));
                    h = Math.min(h, r * 1.1f);
                    float x = cx - total / 2 + i * (bw + gap);
                    box.set(x, cy - h / 2, x + bw, cy + h / 2);
                    canvas.drawRoundRect(box, bw / 2, bw / 2, fill);
                }
                if (thinking) {
                    stroke.setStrokeWidth(r * 0.12f);
                    stroke.setColor(AMBER);
                    float rr = r * 1.08f;
                    box.set(cx - rr, cy - rr, cx + rr, cy + rr);
                    canvas.drawArc(box, (now % 1100) / 1100f * 360f, 80, false, stroke);
                }
                postInvalidateOnAnimation();
            }
        }
    }
}
