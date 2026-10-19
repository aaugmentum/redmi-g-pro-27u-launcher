package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the dock: pinned apps in their order, then the
 * first of the rest if slots are left. After the pinned apps come the module cards the owner chose to show (by
 * label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /** Long press on OK, or the Menu key, on a tile. */
        void onTileMenu(int position, View card);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Package lastUpdateTime when loaded: an update reloads the entry, anything else reuses it. */
        final long stamp;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon, long stamp) {
            this(APP, label, component, banner, icon, null, 0, stamp);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent,
              long stamp) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.stamp = stamp;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** The edited dock, and the configured dock it started from (a new config or theme drops the edit). */
    private static final String PREF_DOCK = "dock", PREF_DOCK_BASE = "dock_base";
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    private final SharedPreferences prefs;
    /** Configured dock order (config.xml dock_apps, or a theme's config.json), joined; the base of an edit. */
    private final String dockBase;
    /** Pinned apps in dock order, packages that are not installed included: the configured dock until edited. */
    private final List<String> favourites;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final boolean showHomeSettings;
    private final List<Entry> items = new ArrayList<>();
    /** Entries from the last reload by component, so returning home decodes no banners. */
    private final Map<String, Entry> cache = new HashMap<>();
    /** Banners are drawn up to FOCUS_SCALE times their size, so load assets for a denser screen than this one. */
    private final int assetDpi;
    /** The cards of the last reload, for the reload after a dock edit. */
    private List<Cards.Card> cards = Collections.emptyList();

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        this.dockBase = TextUtils.join("\n", cfg.dockApps);
        // An edit saved before the base was recorded counts as made on this configuration.
        String saved = dockBase.equals(prefs.getString(PREF_DOCK_BASE, dockBase)) ? prefs.getString(PREF_DOCK, null) : null;
        this.favourites = new ArrayList<>(saved != null ? Arrays.asList(TextUtils.split(saved, "\n")) : cfg.dockApps);
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        this.assetDpi = ctx.getResources().getDisplayMetrics().densityDpi * 3 / 2;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        this.cards = cards;
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        Map<String, Entry> seen = new HashMap<>();
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            String pkg = ai.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ai.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            String key = component.flattenToShortString();
            long stamp = lastUpdate(pm, pkg);
            Entry e = cache.get(key);
            if (e == null || e.stamp != stamp) e = load(pm, ri, component, stamp);
            seen.put(key, e);
            fresh.add(card == null ? e
                    : new Entry(Entry.CARD, card.label, component, e.banner, e.icon, card.caption, card.accent, stamp));
        }
        cache.clear();
        cache.putAll(seen);
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0, 0));
        }
        items.clear();
        items.addAll(fresh);
        notifyDataSetChanged();
    }

    private Entry load(PackageManager pm, ResolveInfo ri, ComponentName cn, long stamp) {
        ActivityInfo ai = ri.activityInfo;
        Drawable banner = forDensity(pm, ai.applicationInfo, ai.banner != 0 ? ai.banner : ai.applicationInfo.banner);
        if (banner == null) banner = ai.loadBanner(pm);
        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        Drawable icon = null;
        if (banner == null) {
            icon = forDensity(pm, ai.applicationInfo, ri.getIconResource());
            if (icon == null) icon = ri.loadIcon(pm);
        }
        return new Entry(String.valueOf(ri.loadLabel(pm)), cn, banner, icon, stamp);
    }

    /** The app's drawable at {@link #assetDpi}, or null. An xhdpi banner (320x180) is upscaled on a 1080p TV. */
    private Drawable forDensity(PackageManager pm, ApplicationInfo app, int res) {
        if (res == 0) return null;
        try {
            return pm.getResourcesForApplication(app).getDrawableForDensity(res, assetDpi, null);
        } catch (Exception e) {   // package gone, or a resource the app doesn't really have
            return null;
        }
    }

    private static long lastUpdate(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    // ---- dock editing (tile menu) ----------------------------------------------------------------

    /** False for the "Default home" shortcut, which has no menu. */
    boolean hasMenu(int position) {
        return position >= 0 && position < items.size() && items.get(position).kind != Entry.HOME_SETTINGS;
    }

    boolean isPinned(int position) {
        return position >= 0 && position < items.size() && favourites.contains(items.get(position).pkg);
    }

    /** Pins the app at the end of the dock. False when the dock is already full of pinned apps. */
    boolean pin(int position) {
        if (!hasMenu(position) || isPinned(position) || isPinned(dockSize - 1)) return false;
        favourites.add(items.get(position).pkg);
        saveDock();
        return true;
    }

    void unpin(int position) {
        if (!isPinned(position)) return;
        favourites.remove(items.get(position).pkg);
        saveDock();
    }

    /** True when the pinned app at position can swap with the pinned dock app next to it (dir -1 or +1). */
    boolean canMove(int position, int dir) {
        int other = position + dir;
        return position < dockSize && other >= 0 && other < dockSize && isPinned(position) && isPinned(other);
    }

    void move(int position, int dir) {
        if (!canMove(position, dir)) return;
        Collections.swap(favourites, favourites.indexOf(items.get(position).pkg),
                favourites.indexOf(items.get(position + dir).pkg));
        saveDock();
    }

    private void saveDock() {
        prefs.edit().putString(PREF_DOCK, TextUtils.join("\n", favourites)).putString(PREF_DOCK_BASE, dockBase).apply();
        reload(cards);
    }

    // ---- RecyclerView ----------------------------------------------------------------------------

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        h.name.setText(e.name);
        h.name.setVisibility(position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    String pkgAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 16f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (listener == null || pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.getRepeatCount() == 0) listener.onTileMenu(pos, view);
                    return true;
                }
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                return listener.onTileVerticalKey(pos, keyCode);
            });
            card.setOnLongClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileMenu(pos, view);
                return true;
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
