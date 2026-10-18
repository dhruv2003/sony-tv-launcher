package dev.dhruv.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. Favourites come first in the user's order (the first {@link MainActivity#COLUMNS} are the
 * dock), then everything else alphabetically.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for D-pad Up/Down on a tile. Return true when handled. */
        boolean onTileVerticalKey(int position, int keyCode);

        void onTileClicked(Entry entry);

        void onTileLongClicked(int position, View card);
    }

    static final class Entry {
        final String label;
        final ComponentName component;
        final String pkg;
        final String key;
        final Drawable banner;
        final Drawable icon;
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.key = component.flattenToShortString();
            this.banner = banner;
            this.icon = icon;
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    private static final String KEY_FAVOURITES = "favourites";
    private static final String KEY_LAST_OPENED = "last_opened";   // "component=millis" lines
    static final String KEY_RECENT_DOCK = "recent_dock";           // top row follows recent launches

    private final Context ctx;
    private final TileListener listener;
    private final int configuredDock;
    private boolean dockOn = true;
    private final SharedPreferences prefs;
    /** Favourites in dock order, as of the last reload or save (also kept while the dock is hidden). */
    private List<String> favouriteOrder = new ArrayList<>();
    /** Loaded entries by component, so returning home doesn't reload every banner. */
    private final Map<String, Entry> cache = new HashMap<>();
    private final List<Entry> items = new ArrayList<>();
    private int favouriteCount;
    private String signature = "";

    AppAdapter(Context ctx, TileListener listener, int dockSize, SharedPreferences prefs) {
        this.ctx = ctx;
        this.listener = listener;
        this.configuredDock = dockSize;
        this.prefs = prefs;
    }

    private int dockSize() {
        return dockOn ? configuredDock : 0;
    }

    /** Shows or hides the dock. When hidden, all apps are one grid ordered by last opened. */
    void setDockEnabled(boolean on) {
        if (dockOn == on) return;
        dockOn = on;
        signature = "";
    }

    /** Drop cached banners and labels, e.g. after an app was updated. */
    void invalidate() {
        cache.clear();
        signature = "";
    }

    /**
     * Rebuilds the list if the installed apps or the favourites changed since the last call.
     * Returns true when the list was rebuilt.
     */
    boolean reload() {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, ResolveInfo> byKey = new HashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || ri.activityInfo.packageName.equals(ctx.getPackageName())) continue;
            byKey.put(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToShortString(), ri);
        }
        List<String> keys = new ArrayList<>(byKey.keySet());
        Collections.sort(keys);
        // The favourites must stay last: commitFavourites() rewrites everything after the final '|'.
        String sig = TextUtils.join(",", keys) + "|" + dockOn + "|" + prefs.getString(KEY_LAST_OPENED, "")
                + "|" + prefs.getString(KEY_FAVOURITES, "");
        if (sig.equals(signature)) return false;
        signature = sig;

        List<Entry> all = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byKey.entrySet()) {
            Entry entry = cache.get(e.getKey());
            if (entry == null) {
                ResolveInfo ri = e.getValue();
                Drawable banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                entry = new Entry(String.valueOf(ri.loadLabel(pm)),
                        new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), banner, ri.loadIcon(pm));
                cache.put(e.getKey(), entry);
            }
            all.add(entry);
        }
        cache.keySet().retainAll(byKey.keySet());

        favouriteOrder = favouriteKeys(all);
        List<String> favs = dockOn ? favouriteOrder : new ArrayList<String>();
        // Most recently opened first; apps never opened from here follow alphabetically.
        final Map<String, Long> opened = lastOpened();
        Comparator<Entry> byRecent = (a, b) -> {
            int c = Long.compare(openedAt(opened, b.key), openedAt(opened, a.key));
            return c != 0 ? c : a.label.compareToIgnoreCase(b.label);
        };
        List<Entry> fresh = new ArrayList<>();
        for (String k : favs) fresh.add(cache.get(k));
        List<Entry> rest = new ArrayList<>();
        for (Entry e : all) if (!favs.contains(e.key)) rest.add(e);
        Collections.sort(rest, byRecent);
        fresh.addAll(rest);

        items.clear();
        items.addAll(fresh);
        favouriteCount = favs.size();
        notifyDataSetChanged();
        return true;
    }

    /** Saved favourites (or the defaults from config.xml), limited to installed apps. */
    private List<String> favouriteKeys(List<Entry> all) {
        Set<String> out = new LinkedHashSet<>();
        String saved = prefs.getString(KEY_FAVOURITES, null);
        if (saved != null) {
            Set<String> installed = new LinkedHashSet<>();
            for (Entry e : all) installed.add(e.key);
            for (String k : saved.split("\n")) if (installed.contains(k)) out.add(k);
        } else {
            for (String pkg : ctx.getResources().getStringArray(R.array.default_favourites)) {
                for (Entry e : all) {
                    if (e.pkg.equals(pkg)) {
                        out.add(e.key);
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    private Map<String, Long> lastOpened() {
        Map<String, Long> out = new HashMap<>();
        for (String line : prefs.getString(KEY_LAST_OPENED, "").split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static long openedAt(Map<String, Long> opened, String key) {
        Long t = opened.get(key);
        return t == null ? 0L : t;
    }

    int favouriteCount() {
        return favouriteCount;
    }

    boolean isFavourite(int position) {
        return position >= 0 && position < favouriteCount;
    }

    void addFavourite(int position) {
        List<String> favs = currentFavourites();
        favs.add(items.get(position).key);
        saveFavourites(favs);
        reload();
    }

    void removeFavourite(int position) {
        List<String> favs = currentFavourites();
        favs.remove(items.get(position).key);
        saveFavourites(favs);
        reload();
    }

    /**
     * Records that an app was opened: stamps its last-opened time (which orders the grid) and, when the
     * "recent apps" top row is switched on ({@link #KEY_RECENT_DOCK}, off by default so pinned favourites
     * stay put), moves it to the front of the favourites, keeping only the latest {@code configuredDock}.
     * Saved only; the list is rebuilt on the next reload (when Home resumes).
     */
    void noteLaunched(String key) {
        Map<String, Long> opened = lastOpened();
        opened.put(key, System.currentTimeMillis());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : opened.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        prefs.edit().putString(KEY_LAST_OPENED, sb.toString()).apply();
        if (!prefs.getBoolean(KEY_RECENT_DOCK, false)) return;

        List<String> favs = dockOn ? currentFavourites() : new ArrayList<>(favouriteOrder);
        favs.remove(key);
        favs.add(0, key);
        while (favs.size() > configuredDock) favs.remove(favs.size() - 1);
        saveFavourites(favs);
    }

    void resetFavourites() {
        prefs.edit().remove(KEY_FAVOURITES).apply();
        reload();
    }

    /** Live move mode: swap a favourite with its neighbour. Returns the new position. */
    int moveFavourite(int from, int to) {
        if (!isFavourite(from) || !isFavourite(to) || from == to) return from;
        Entry e = items.remove(from);
        items.add(to, e);
        notifyItemMoved(from, to);
        // Dock tiles have no label; rebind so labels match the new rows.
        int lo = Math.min(from, to), hi = Math.max(from, to);
        notifyItemRangeChanged(lo, hi - lo + 1);
        return to;
    }

    /** Persists the current favourites order (after a move). */
    void commitFavourites() {
        saveFavourites(currentFavourites());
        signature = signature.substring(0, signature.lastIndexOf('|') + 1) + prefs.getString(KEY_FAVOURITES, "");
    }

    private List<String> currentFavourites() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < favouriteCount; i++) out.add(items.get(i).key);
        return out;
    }

    private void saveFavourites(List<String> keys) {
        favouriteOrder = new ArrayList<>(keys);
        prefs.edit().putString(KEY_FAVOURITES, TextUtils.join("\n", keys)).apply();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        boolean focused = h.card.isFocused();   // a rebind during move mode keeps the lifted look
        h.name.setText(e.label);
        h.name.setVisibility(position < dockSize() ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        h.name.setAlpha(focused ? 1f : 0f);
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        h.card.setContentDescription(e.label);
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
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    Entry entryAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    static int vividColor(Drawable live) {
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

    /** The lift-and-label focus animation, shared with the Continue Watching cards. */
    static void animateFocus(View card, TextView name, boolean focused) {
        float density = card.getResources().getDisplayMetrics().density;
        float s = focused ? FOCUS_SCALE : 1.0f;
        card.animate().scaleX(s).scaleY(s)
                .setDuration(focused ? 260 : 180)
                .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                .start();
        name.animate().alpha(focused ? 1f : 0f)
                .translationY(focused ? density * 4f : -density * 6f)
                .setDuration(220).start();
    }

    static void roundCorners(View card) {
        final float radius = card.getResources().getDisplayMetrics().density * 16f;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
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
            roundCorners(card);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });
            card.setOnClickListener(view -> {
                int pos = getBindingAdapterPosition();
                AppAdapter a = (AppAdapter) getBindingAdapter();
                if (pos != RecyclerView.NO_POSITION && a != null) listener.onTileClicked(a.items.get(pos));
            });
            card.setOnLongClickListener(view -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onTileLongClicked(pos, view);
                return true;
            });
            card.setOnFocusChangeListener((view, focused) -> {
                animateFocus(view, name, focused);
                int pos = getBindingAdapterPosition();
                if (focused && pos != RecyclerView.NO_POSITION) listener.onTileFocused(pos, view);
            });
        }
    }
}
