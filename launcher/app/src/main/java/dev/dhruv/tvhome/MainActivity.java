package dev.dhruv.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS style home: black, a top shelf for the focused app, Continue Watching, a favourites dock, an app
 * grid, and one glass highlight that springs between tiles. Based on tv-vibehack by Gathin (MIT).
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private static final long PANEL_MS = 220L;
    private static final String KEY_SOUNDS = "sounds";
    private static final String KEY_SHOW_SHELF = "show_shelf";     // the top row (dock) and the app grid
    private static final String KEY_SHOW_WATCH = "show_watch";     // Continue Watching
    private static final String SONY_AUDIO_OUTPUT = "com.sony.dtv.audiooutput";
    private static final String SONY_TIMERS = "com.sony.dtv.timers";
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private String liveTvPkg;
    private SharedPreferences prefs;
    private Sounds sounds;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private GlassView glass;
    private View ambient;
    private CrossfadeView heroBg;
    private View heroScrim;
    private View heroBlock;
    private CrossfadeView heroArt;
    private TextView heroName;
    private TextView heroSubtitle;
    private GradientDrawable ambientGlow;
    private int ambientColor;
    private boolean heroShown;
    private String heroKey;
    private TextView greeting;
    private TextView btnWifi;
    private View lastPill;
    private DockDecoration dockDecoration;
    private View watchBlock;
    private HorizontalScrollView watchScroll;
    private LinearLayout watchRow;
    private List<WatchNext.Item> watchItems = Collections.emptyList();
    private String watchSignature = "";
    private View qsOverlay;
    private View qsScrim;
    private View qsPanel;
    private LinearLayout qsItems;
    private View qsReturnFocus;
    private boolean qsOpen;
    private PopupWindow popup;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    private boolean glassShown;
    private boolean glassOnWatch;
    private int lastFocused = 0;
    private int lastDock = 0;
    private int lastWatch = 0;
    private boolean resumed;
    /** Focus moves before this time are programmatic: no tick sound. */
    private long quietUntil;
    private float density;

    // Live move mode: the favourite being moved and its jiggle.
    private boolean moving;
    private int movePos;
    private ObjectAnimator jiggle;
    private View jiggleCard;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (moving) exitMoveMode();
            adapter.invalidate();
            if (resumed) reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        sounds = new Sounds(this, prefs.getBoolean(KEY_SOUNDS, true));

        root = findViewById(R.id.root);
        glass = findViewById(R.id.glass);
        ambient = findViewById(R.id.ambient);
        heroBg = findViewById(R.id.heroBg);
        heroScrim = findViewById(R.id.heroScrim);
        heroBlock = findViewById(R.id.heroBlock);
        heroArt = findViewById(R.id.heroArt);
        heroName = findViewById(R.id.heroName);
        heroSubtitle = findViewById(R.id.heroSubtitle);
        watchBlock = findViewById(R.id.watchBlock);
        watchScroll = findViewById(R.id.watchScroll);
        watchRow = findViewById(R.id.watchRow);
        qsOverlay = findViewById(R.id.qsOverlay);
        qsScrim = findViewById(R.id.qsScrim);
        qsPanel = findViewById(R.id.qsPanel);
        qsItems = findViewById(R.id.qsItems);
        ambientGlow = new GradientDrawable();
        ambientGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambientGlow.setGradientCenter(0.32f, 0.42f);
        ambient.setBackground(ambientGlow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                ambientGlow.setGradientRadius((r - l) * 0.62f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            heroBg.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP));
        }
        greeting = findViewById(R.id.greeting);
        btnWifi = findViewById(R.id.btnWifi);

        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS, prefs);
        grid.setAdapter(adapter);
        applyDock(shelfShown());

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> parkDock(b - t));
        watchBlock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutOverlays());
        heroBlock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutOverlays());

        // Keep the glass glued to its tile while the grid or the Continue Watching row scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (glassShown && !glassOnWatch) shiftGlass(0, -dy);
            }
        });
        watchScroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (glassShown && glassOnWatch) shiftGlass(-(x - ox), 0);
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            s.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }

        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        lastPill = btnInputs;
        btnInputs.setOnClickListener(v -> {
            sounds.select();
            showInputs();
        });
        btnWifi.setOnClickListener(v -> {
            sounds.select();
            open(new Intent(Settings.ACTION_WIFI_SETTINGS));
        });
        btnSettings.setOnClickListener(v -> {
            sounds.select();
            openQuickSettings();
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction. Down goes to the row below.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (watchVisible()) focusWatch(lastWatch);
                else focusTile(lastDock);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v.getId() == R.id.btnInputs) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the glass when focus leaves the tiles; tick on every user focus move.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && (newF.getId() == R.id.card || newF.getId() == R.id.watch_card);
            if (!onTile) hideGlass();
            if (newF != null && newF.getParent() == findViewById(R.id.topButtons)) lastPill = newF;
            if (oldF != null && newF != null && !moving && SystemClock.uptimeMillis() > quietUntil) sounds.focus();
        });

        qsScrim.setOnClickListener(v -> closeQuickSettings(true));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        // The staggered entrance plays once, on the first load; coming back home is instant.
        adapter.reload();
        grid.scheduleLayoutAnimation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (!reloadApps() && getCurrentFocus() == null && !qsOpen) {
            quiet();
            grid.post(() -> focusTile(lastFocused));
        }
        updateGreeting();
        updateWifiLabel();
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, MINUTE_MS);
        refreshWatchNext();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(minuteTick);
        if (moving) exitMoveMode();
        dismissPopup();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        dismissPopup();
        sounds.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    /** Home pressed while already home: back to the first dock tile. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!resumed) return;
        if (moving) exitMoveMode();
        dismissPopup();
        closeQuickSettings(false);
        quiet();
        focusTile(0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null && !qsOpen) {
            quiet();
            focusTile(lastFocused);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moving) return handleMoveKey(event);
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                // Home screen: Back goes nowhere; it only closes the quick settings panel.
                if (up && qsOpen) closeQuickSettings(true);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up && event.getRepeatCount() == 0) {
                    if (qsOpen) closeQuickSettings(true);
                    else openQuickSettings();
                }
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** Reloads the app list if it changed and restores focus. Returns true when it was rebuilt. */
    private boolean reloadApps() {
        if (!adapter.reload()) return false;
        resetHighlight();
        quiet();
        grid.post(() -> {
            if (!qsOpen) focusTile(lastFocused);
        });
        return true;
    }

    private void quiet() {
        quietUntil = SystemClock.uptimeMillis() + 500;
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        glassOnWatch = false;
        moveGlass(card);
        View item = (View) card.getParent();
        if (position < COLUMNS && shelfShown()) {
            lastDock = position;
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
            AppAdapter.Entry e = adapter.entryAt(position);
            if (e != null) {
                String sub = moving ? getString(R.string.move_hint) : null;
                showHero(e.key, e.label, sub, e.banner != null ? e.banner : e.icon, e.banner == null,
                        adapter.ambientColorAt(position), false);
            }
            showWatchRow(true);
        } else {
            hideHero();
            showWatchRow(false);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            float pad = 6f * density;
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, false);
        }
    }

    private void moveGlass(View card) {
        Rect r = rectInRoot(card);
        float pad = 6f * density;        // matches GlassView's glow inset
        float w = card.getWidth() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float h = card.getHeight() * AppAdapter.FOCUS_SCALE + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f;

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + pad);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            glassShown = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    /**
     * The card's rect in root coordinates from layout positions plus translations (the Continue Watching
     * row is placed with setY), ignoring the focus scale.
     */
    private Rect rectInRoot(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != root) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            View parent = (View) cur.getParent();
            if (parent != null) {
                x -= parent.getScrollX();
                y -= parent.getScrollY();
            }
            cur = parent;
        }
        return new Rect((int) x, (int) y, (int) x + v.getWidth(), (int) y + v.getHeight());
    }

    private void shiftGlass(float dx, float dy) {
        glassTargetX += dx;
        glassTargetY += dy;
        if (springX.isRunning()) springX.animateToFinalPosition(glassTargetX);
        else glass.setTranslationX(glassTargetX);
        if (springY.isRunning()) springY.animateToFinalPosition(glassTargetY);
        else glass.setTranslationY(glassTargetY);
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

    private void resetHighlight() {
        glassShown = false;
        glass.animate().cancel();
        glass.setAlpha(0f);
        heroShown = false;
        heroKey = null;
        heroBlock.setAlpha(0f);
        ambient.setAlpha(0f);
        heroBg.setAlpha(0f);
        heroScrim.setAlpha(0f);
    }

    // ---- top shelf -------------------------------------------------------------------------------

    /** Places Continue Watching right above the dock, and the top shelf above that. */
    private void layoutOverlays() {
        if (grid.getHeight() == 0) return;
        Rect g = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, g);
        float anchor = g.top + grid.getPaddingTop() - 4 * density;   // top of the parked dock band
        if (watchBlock.getVisibility() == View.VISIBLE) {
            watchBlock.setY(anchor - watchBlock.getHeight());
            anchor = watchBlock.getY() - 2 * density;
        }
        // Drop the banner when the shelf is short (Continue Watching showing); the name always fits.
        View top = findViewById(R.id.leftBlock);
        Rect t = new Rect(0, 0, top.getWidth(), top.getHeight());
        root.offsetDescendantRectToMyCoords(top, t);
        float room = anchor - 18 * density - t.bottom;
        int artVis = room >= 124 * density ? View.VISIBLE : View.GONE;
        if (heroArt.getVisibility() != artVis) heroArt.setVisibility(artVis);
        heroBlock.setY(anchor - 18 * density - heroBlock.getHeight());
    }

    private void showHero(String key, String label, String subtitle, Drawable art, boolean iconArt, int color,
                          boolean force) {
        boolean changed = force || !key.equals(heroKey);
        heroKey = key;
        setSubtitle(subtitle);
        if (!heroShown) {
            heroName.setText(label);
            heroName.setTranslationX(0f);
            showArt(art, iconArt, color);
            heroBlock.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroBg.animate().alpha(0.55f).setDuration(600).start();
            heroScrim.animate().alpha(1f).setDuration(400).start();
            heroShown = true;
        } else if (changed) {
            showArt(art, iconArt, color);
            if (!label.contentEquals(heroName.getText())) {
                heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                    heroName.setText(label);
                    heroName.setTranslationX(12 * density);
                    heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
                }).start();
            }
            if (color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        }
    }

    private void setSubtitle(String subtitle) {
        heroSubtitle.setText(subtitle);
        heroSubtitle.setVisibility(subtitle == null || subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Crossfades the shelf banner and the blurred full-screen background to this art. */
    private void showArt(Drawable art, boolean iconArt, int color) {
        Drawable copy = art == null ? null : copyOf(art);
        heroArt.show(layer -> {
            layer.setBackgroundResource(R.drawable.hero_art_bg);
            AppAdapter.roundCorners(layer);
            if (iconArt) {
                int p = (int) (30 * density);
                layer.setPadding(p, p, p, p);
                layer.setScaleType(ImageView.ScaleType.FIT_CENTER);
            } else {
                layer.setPadding(0, 0, 0, 0);
                layer.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            layer.setImageDrawable(copy);
        });
        Bitmap tiny = tinyBitmap(art, iconArt, color);
        heroBg.show(layer -> {
            layer.setScaleType(ImageView.ScaleType.CENTER_CROP);
            layer.setImageBitmap(tiny);
        });
    }

    private Drawable copyOf(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    /**
     * A small render of the art, blurred in software so it also looks smooth below Android 12
     * (where RenderEffect is unavailable). Scaled up full screen it reads as a soft blur.
     */
    private Bitmap tinyBitmap(Drawable art, boolean iconArt, int color) {
        int w = 128, h = 72;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(iconArt || art == null ? darken(color) : Color.BLACK);
        if (art != null) {
            Drawable d = copyOf(art);
            int half = w * 7 / 48;
            if (iconArt) d.setBounds(w / 2 - half, h / 2 - half, w / 2 + half, h / 2 + half);
            else d.setBounds(0, 0, w, h);
            d.draw(c);
        }
        boxBlur(bmp, 5, 3);
        return bmp;
    }

    /** In-place box blur (edge-clamped), repeated {@code passes} times to approximate a Gaussian. */
    private static void boxBlur(Bitmap bmp, int radius, int passes) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < passes; i++) {
            blurPass(px, tmp, w, h, radius, true);
            blurPass(tmp, px, w, h, radius, false);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * lineStep;
            int a = 0, rr = 0, g = 0, b = 0;
            for (int k = -r; k <= r; k++) {
                int p = src[base + Math.min(len - 1, Math.max(0, k)) * step];
                a += p >>> 24; rr += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                int add = src[base + Math.min(len - 1, i + r + 1) * step];
                int sub = src[base + Math.max(0, i - r) * step];
                a += (add >>> 24) - (sub >>> 24);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                b += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int darken(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.45f;
        return Color.HSVToColor(hsv);
    }

    private void setAmbient(int color) {
        ambientColor = color;
        int c = color & 0x00FFFFFF;
        ambientGlow.setColors(new int[]{0x5C000000 | c, 0x26000000 | c, 0x00000000 | c});
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroKey = null;
        heroBlock.animate().alpha(0f).setDuration(160).start();
        ambient.animate().alpha(0f).setDuration(300).start();
        heroBg.animate().alpha(0f).setDuration(300).start();
        heroScrim.animate().alpha(0f).setDuration(300).start();
    }

    // ---- continue watching -----------------------------------------------------------------------

    private void refreshWatchNext() {
        worker.execute(() -> {
            List<WatchNext.Item> items = WatchNext.query(getApplicationContext());
            runOnUiThread(() -> {
                if (!isDestroyed()) renderWatchNext(items);
            });
        });
    }

    private boolean shelfShown() {
        return prefs.getBoolean(KEY_SHOW_SHELF, true);
    }

    /** Continue Watching sits above the top row, so hiding the top row hides it too. */
    private boolean watchAllowed() {
        return shelfShown() && prefs.getBoolean(KEY_SHOW_WATCH, true);
    }

    /** Pads the grid so the dock row is parked at the bottom of the screen; the rest hides below it. */
    private void parkDock(int gridHeight) {
        int dockRow = (int) ((145 + 6 + 12) * density);   // item height plus DockDecoration offsets
        // Dock on: park the dock row at the bottom. Dock off: the grid starts right under the top bar.
        int want = shelfShown() ? Math.max(0, gridHeight - dockRow - (int) (30 * density)) : (int) (10 * density);
        if (grid.getPaddingTop() != want) {
            grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
        }
        layoutOverlays();
    }

    private void applyDock(boolean on) {
        dockDecoration.setDockSize(on ? COLUMNS : 0);
        adapter.setDockEnabled(on);
    }

    /**
     * Applies the quick-settings switches. Top row off removes the dock band and the shelf above it
     * (including Continue Watching) but keeps every app: they become one grid, most recently opened first.
     */
    private void applyShelfPrefs() {
        boolean dock = shelfShown();
        applyDock(dock);
        if (moving) exitMoveMode();
        dismissPopup();
        resetHighlight();
        lastFocused = 0;
        lastDock = 0;
        parkDock(grid.getHeight());
        adapter.reload();
        grid.invalidateItemDecorations();
        grid.post(() -> grid.scrollToPosition(0));
        if (!watchAllowed()) watchBlock.setVisibility(View.GONE);
        layoutOverlays();
        watchSignature = null;      // force a re-render so the row comes back when switched on
        refreshWatchNext();
    }

    private void renderWatchNext(List<WatchNext.Item> items) {
        if (!watchAllowed()) items = Collections.emptyList();
        StringBuilder sig = new StringBuilder();
        for (WatchNext.Item it : items) {
            sig.append(it.id).append(':').append(it.title).append(':').append(it.subtitle).append(':')
                    .append(it.art).append(':').append(it.progress).append('|');
        }
        if (sig.toString().equals(watchSignature)) return;
        watchSignature = sig.toString();

        View focus = getCurrentFocus();
        boolean focusInRow = focus != null && focus.getId() == R.id.watch_card;
        quiet();
        watchRow.removeAllViews();
        watchItems = items;
        for (int i = 0; i < items.size(); i++) watchRow.addView(watchCard(items.get(i), i));
        watchBlock.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        lastWatch = Math.min(lastWatch, Math.max(0, items.size() - 1));
        if (focusInRow) {
            if (items.isEmpty()) focusTile(lastDock);
            else focusWatch(lastWatch);
        }
    }

    private View watchCard(WatchNext.Item it, int index) {
        View v = getLayoutInflater().inflate(R.layout.item_watch, watchRow, false);
        View card = v.findViewById(R.id.watch_card);
        ImageView poster = v.findViewById(R.id.poster);
        TextView fallback = v.findViewById(R.id.fallback_title);
        TextView name = v.findViewById(R.id.name);
        AppAdapter.roundCorners(card);
        card.setContentDescription(it.title);
        fallback.setText(it.title);
        name.setText(it.title);
        View fill = v.findViewById(R.id.progress_fill);
        if (it.progress > 0f) {
            fill.setScaleX(it.progress);
        } else {
            fill.setVisibility(View.GONE);
            v.findViewById(R.id.progress_track).setVisibility(View.GONE);
        }
        if (it.art != null) {
            ImageLoader.load(this, it.art, poster, (int) (320 * density), () -> {
                fallback.setVisibility(View.GONE);
                if (card.isFocused()) showWatchHero(index, poster, true);
            });
        }
        card.setOnFocusChangeListener((view, focused) -> {
            AppAdapter.animateFocus(view, name, focused);
            if (!focused) return;
            lastWatch = index;
            glassOnWatch = true;
            moveGlass(view);
            showWatchRow(true);
            showWatchHero(index, poster, false);
        });
        card.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    lastPill.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusTile(lastDock);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == watchRow.getChildCount() - 1;
                default:
                    return false;
            }
        });
        card.setOnClickListener(view -> {
            sounds.select();
            launchWatchItem(it);
        });
        return v;
    }

    private void showWatchHero(int index, ImageView poster, boolean force) {
        if (index >= watchItems.size()) return;
        WatchNext.Item it = watchItems.get(index);
        Drawable art = poster.getDrawable();
        String sub = getString(R.string.resume) + (it.subtitle != null ? " · " + it.subtitle : "");
        int color = art != null ? AppAdapter.vividColor(art) : 0xFF444444;
        showHero("watch:" + it.id, it.title, sub, art, false, color, force);
    }

    private boolean watchVisible() {
        return watchBlock.getVisibility() == View.VISIBLE && watchRow.getChildCount() > 0;
    }

    private void focusWatch(int index) {
        if (!watchVisible()) return;
        View item = watchRow.getChildAt(Math.max(0, Math.min(index, watchRow.getChildCount() - 1)));
        item.findViewById(R.id.watch_card).requestFocus();
    }

    /** The grid scrolls up into the shelf area; fade Continue Watching out while browsing it. */
    private void showWatchRow(boolean show) {
        if (!watchVisible()) return;
        watchBlock.animate().alpha(show ? 1f : 0f).setDuration(show ? 260 : 160).start();
    }

    private void launchWatchItem(WatchNext.Item it) {
        Intent i = null;
        if (it.intentUri != null) {
            try {
                i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                i.setSelector(null);
                i.setFlags((i.getFlags() & ~GRANT_FLAGS) | Intent.FLAG_ACTIVITY_NEW_TASK);
            } catch (Exception e) {
                i = null;
            }
        }
        if (i == null && it.pkg != null) i = launchIntentFor(it.pkg);
        if (i == null) {
            Toast.makeText(this, getString(R.string.cannot_open, it.title), Toast.LENGTH_SHORT).show();
            return;
        }
        open(i);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {                       // dock row: Continue Watching, else the top bar
                if (watchVisible()) focusWatch(lastWatch);
                else lastPill.requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- tiles: launch, long-press menu, move mode -----------------------------------------------

    @Override
    public void onTileClicked(AppAdapter.Entry e) {
        sounds.select();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            adapter.noteLaunched(e.key);
        } catch (Exception ex) {
            Toast.makeText(this, getString(R.string.cannot_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** Long-press OK: a small glass menu under the tile. */
    @Override
    public void onTileLongClicked(int position, View card) {
        AppAdapter.Entry e = adapter.entryAt(position);
        if (e == null) return;
        sounds.select();
        PopupWindow menu = newPopup((int) (300 * density));
        LinearLayout rows = menu.getContentView().findViewById(R.id.rows);
        if (adapter.isFavourite(position)) {
            addRow(rows, menu, R.drawable.ic_move, getString(R.string.menu_move), null, false,
                    () -> enterMoveMode(position));
            addRow(rows, menu, R.drawable.ic_star_off, getString(R.string.menu_remove_fav), null, false, () -> {
                adapter.removeFavourite(position);
                refocus(e.key);
            });
        } else {
            addRow(rows, menu, R.drawable.ic_star, getString(R.string.menu_add_fav), null, false, () -> {
                adapter.addFavourite(position);
                refocus(e.key);
            });
        }
        addRow(rows, menu, R.drawable.ic_info, getString(R.string.menu_app_info), null, false, () ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg))));
        showPopup(menu, card);
    }

    /** After the list was rebuilt, put focus back on the same app wherever it moved to. */
    private void refocus(String key) {
        resetHighlight();
        quiet();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            AppAdapter.Entry e = adapter.entryAt(i);
            if (e != null && e.key.equals(key)) {
                lastFocused = i;
                final int pos = i;
                grid.post(() -> focusTile(pos));
                return;
            }
        }
    }

    private void enterMoveMode(int position) {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh == null) return;
        moving = true;
        movePos = position;
        jiggleCard = vh.itemView.findViewById(R.id.card);
        jiggleCard.requestFocus();
        jiggle = ObjectAnimator.ofFloat(jiggleCard, View.ROTATION, -1.2f, 1.2f);
        jiggle.setDuration(130);
        jiggle.setRepeatMode(ValueAnimator.REVERSE);
        jiggle.setRepeatCount(ValueAnimator.INFINITE);
        jiggle.start();
        onTileFocused(position, jiggleCard);   // refresh the shelf with the move hint
        Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show();
    }

    private boolean handleMoveKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int target = movePos;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) target = movePos - 1;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) target = movePos + 1;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) target = movePos - COLUMNS;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) target = movePos + COLUMNS;
            if (target != movePos && adapter.isFavourite(target)) moveTo(target);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) {
            exitMoveMode();
        }
        return true;
    }

    private void moveTo(int target) {
        movePos = adapter.moveFavourite(movePos, target);
        lastFocused = movePos;
        sounds.focus();
        final int pos = movePos;
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
            if (vh == null) return;
            View card = vh.itemView.findViewById(R.id.card);
            if (card != jiggleCard) {   // the view was rebound: carry the jiggle over
                stopJiggle();
                jiggleCard = card;
                card.requestFocus();
                jiggle.setTarget(card);
                jiggle.start();
            }
            onTileFocused(pos, card);
        });
    }

    private void exitMoveMode() {
        moving = false;
        stopJiggle();
        if (jiggle != null) jiggle.cancel();
        adapter.commitFavourites();
        sounds.select();
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(movePos);
        if (vh != null) onTileFocused(movePos, vh.itemView.findViewById(R.id.card));
    }

    private void stopJiggle() {
        if (jiggle != null) jiggle.end();
        if (jiggleCard != null) jiggleCard.setRotation(0f);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(res);
    }

    /** Connection type on the Wi-Fi pill (the network name would need a location permission). */
    private void updateWifiLabel() {
        int text = R.string.wifi;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
            if (caps == null) text = R.string.offline;
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) text = R.string.ethernet;
        } catch (Exception ignored) {
        }
        btnWifi.setText(text);
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private Intent launchIntentFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(pkg);
    }

    private Intent resolvable(Intent i) {
        return i.resolveActivity(getPackageManager()) != null ? i : null;
    }

    // ---- popups: inputs and the tile menu ----------------------------------------------------------

    private PopupWindow newPopup(int width) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        PopupWindow p = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        p.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        p.setOutsideTouchable(true);
        p.setAnimationStyle(0);
        p.setElevation(0f);
        return p;
    }

    private void showPopup(PopupWindow p, View anchor) {
        dismissPopup();
        popup = p;
        View panel = p.getContentView();
        LinearLayout rows = panel.findViewById(R.id.rows);
        // Below the anchor when it fits, otherwise above it (the dock sits near the bottom edge).
        panel.measure(View.MeasureSpec.makeMeasureSpec(p.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int panelH = panel.getMeasuredHeight();
        int gap = (int) (10 * density);
        int margin = (int) (24 * density);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenH = root.getRootView().getHeight();
        boolean below = loc[1] + anchor.getHeight() + gap + panelH <= screenH - margin;
        int y = below ? loc[1] + anchor.getHeight() + gap
                : Math.max(margin, loc[1] - gap - panelH);
        // Drop in: fade and unfold from the edge nearest the tile.
        panel.setAlpha(0f);
        panel.setPivotY(below ? 0f : panelH);
        panel.setScaleY(0.92f);
        panel.setTranslationY((below ? -8 : 8) * density);
        p.showAtLocation(root, Gravity.TOP | Gravity.START, loc[0], y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void dismissPopup() {
        if (popup != null && popup.isShowing()) popup.dismiss();
        popup = null;
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        PopupWindow menu = newPopup(Math.max(anchor.getWidth(), (int) (280 * density)));
        LinearLayout rows = menu.getContentView().findViewById(R.id.rows);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                // This TV may report "standby" for every port, so only definite states are shown.
                String status = state == TvInputManager.INPUT_STATE_CONNECTED ? getString(R.string.connected)
                        : state == TvInputManager.INPUT_STATE_DISCONNECTED ? getString(R.string.not_connected) : null;
                addRow(rows, menu, hdmi ? R.drawable.ic_hdmi : R.drawable.ic_av, label, status,
                        state == TvInputManager.INPUT_STATE_CONNECTED,
                        () -> open(new Intent(Intent.ACTION_VIEW).setDataAndType(
                                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel")));
            }
        }
        Intent liveTv = launchIntentFor(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addRow(rows, menu, R.drawable.ic_livetv, getString(R.string.live_tv), null, false, () -> open(liveTv));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        showPopup(menu, anchor);
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A glass menu row (Inputs, tile menu, quick settings). {@code popup} is dismissed before the action runs. */
    private TextView addRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, String status,
                            boolean dot, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView statusView = row.findViewById(R.id.status);
        statusView.setText(status);
        statusView.setVisibility(status == null ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.dot).setVisibility(dot ? View.VISIBLE : View.GONE);
        // Popups are separate windows, so the activity's focus listener doesn't hear these moves.
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused && popup != null && SystemClock.uptimeMillis() > quietUntil) sounds.focus();
        });
        row.setOnClickListener(v -> {
            sounds.select();
            if (popup != null) popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return statusView;
    }

    // ---- quick settings panel --------------------------------------------------------------------

    private void openQuickSettings() {
        if (qsOpen) return;
        qsOpen = true;
        dismissPopup();
        qsReturnFocus = getCurrentFocus();
        buildQuickSettings();
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        qsOverlay.setVisibility(View.VISIBLE);
        qsScrim.setAlpha(0f);
        qsScrim.animate().alpha(1f).setDuration(PANEL_MS).start();
        qsPanel.setTranslationX(380 * density);
        qsPanel.animate().translationX(0f).setDuration(PANEL_MS).setInterpolator(new DecelerateInterpolator()).start();
        quiet();
        for (int i = 0; i < qsItems.getChildCount(); i++) {
            View v = qsItems.getChildAt(i);
            if (v.isFocusable() && v.requestFocus()) break;
        }
    }

    private void closeQuickSettings(boolean animate) {
        if (!qsOpen) return;
        qsOpen = false;
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        quiet();
        View back = qsReturnFocus;
        qsReturnFocus = null;
        if (back == null || !back.isAttachedToWindow() || !back.requestFocus()) focusTile(lastFocused);
        if (animate) {
            qsScrim.animate().alpha(0f).setDuration(PANEL_MS).start();
            qsPanel.animate().translationX(380 * density).setDuration(PANEL_MS)
                    .withEndAction(() -> {
                        if (!qsOpen) qsOverlay.setVisibility(View.GONE);
                    }).start();
        } else {
            qsScrim.animate().cancel();
            qsPanel.animate().cancel();
            qsOverlay.setVisibility(View.GONE);
        }
    }

    private void buildQuickSettings() {
        qsItems.removeAllViews();
        qsItems.addView(caption(R.string.qs_title, 6));
        addPanelIntent(R.drawable.ic_wifi, R.string.qs_wifi, resolvable(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addPanelIntent(R.drawable.ic_bluetooth, R.string.qs_bluetooth,
                resolvable(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        Intent sound = launchIntentFor(SONY_AUDIO_OUTPUT);
        addPanelIntent(R.drawable.ic_sound, R.string.qs_sound,
                sound != null ? sound : resolvable(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        addPanelIntent(R.drawable.ic_picture, R.string.qs_picture,
                resolvable(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        addPanelIntent(R.drawable.ic_timer, R.string.qs_sleep, launchIntentFor(SONY_TIMERS));
        addRow(qsItems, null, R.drawable.ic_hdmi, getString(R.string.qs_inputs), null, false, () -> {
            closeQuickSettings(false);
            showInputs();
        });
        addPanelIntent(R.drawable.ic_settings, R.string.qs_all_settings,
                resolvable(new Intent(Settings.ACTION_SETTINGS)));

        qsItems.addView(caption(R.string.app_name, 18));
        final TextView[] soundStatus = new TextView[1];
        soundStatus[0] = addRow(qsItems, null, R.drawable.ic_sound, getString(R.string.qs_sounds), soundsLabel(), false,
                () -> {
                    boolean on = !prefs.getBoolean(KEY_SOUNDS, true);
                    prefs.edit().putBoolean(KEY_SOUNDS, on).apply();
                    sounds.enabled = on;
                    soundStatus[0].setText(soundsLabel());
                    sounds.select();
                });
        final TextView[] recentStatus = new TextView[1];
        recentStatus[0] = addRow(qsItems, null, R.drawable.ic_refresh, getString(R.string.qs_recent_dock),
                recentDockLabel(), false, () -> {
                    boolean on = !prefs.getBoolean(AppAdapter.KEY_RECENT_DOCK, false);
                    prefs.edit().putBoolean(AppAdapter.KEY_RECENT_DOCK, on).apply();
                    recentStatus[0].setText(recentDockLabel());
                    sounds.select();
                });
        final TextView[] status = new TextView[2];
        final TextView[] bothTitle = new TextView[1];
        final Runnable refresh = () -> {
            boolean shelf = shelfShown();
            status[0].setText(getString(watchAllowed() ? R.string.qs_on : R.string.qs_off));
            status[1].setText(getString(shelf ? R.string.qs_on : R.string.qs_off));
            bothTitle[0].setText(shelf ? R.string.qs_hide_both : R.string.qs_show_both);
        };
        status[0] = addRow(qsItems, null, R.drawable.ic_av, getString(R.string.continue_watching), "", false, () -> {
            prefs.edit().putBoolean(KEY_SHOW_WATCH, !prefs.getBoolean(KEY_SHOW_WATCH, true)).apply();
            applyShelfPrefs();
            sounds.select();
            refresh.run();
        });
        status[1] = addRow(qsItems, null, R.drawable.ic_star, getString(R.string.qs_shelf), "", false, () -> {
            prefs.edit().putBoolean(KEY_SHOW_SHELF, !shelfShown()).apply();
            applyShelfPrefs();
            sounds.select();
            refresh.run();
        });
        addRow(qsItems, null, R.drawable.ic_home, getString(R.string.qs_hide_both), null, false, () -> {
            boolean show = !shelfShown();
            prefs.edit().putBoolean(KEY_SHOW_SHELF, show).putBoolean(KEY_SHOW_WATCH, show).apply();
            applyShelfPrefs();
            sounds.select();
            refresh.run();
        });
        bothTitle[0] = qsItems.getChildAt(qsItems.getChildCount() - 1).findViewById(R.id.title);
        refresh.run();
        addRow(qsItems, null, R.drawable.ic_refresh, getString(R.string.qs_reset), null, false, () -> {
            if (moving) exitMoveMode();
            adapter.resetFavourites();
            resetHighlight();
            Toast.makeText(this, R.string.qs_reset_done, Toast.LENGTH_SHORT).show();
        });
    }

    private void addPanelIntent(int icon, int label, Intent intent) {
        if (intent == null) return;
        addRow(qsItems, null, icon, getString(label), null, false, () -> {
            closeQuickSettings(false);
            open(intent);
        });
    }

    private String recentDockLabel() {
        return getString(prefs.getBoolean(AppAdapter.KEY_RECENT_DOCK, false) ? R.string.qs_on : R.string.qs_off);
    }

    private String soundsLabel() {
        return getString(prefs.getBoolean(KEY_SOUNDS, true) ? R.string.qs_on : R.string.qs_off);
    }

    private TextView caption(int text, int topDp) {
        TextView t = new TextView(this, null, 0, R.style.Caption);
        t.setText(text);
        t.setPadding((int) (12 * density), (int) (topDp * density), (int) (12 * density), (int) (8 * density));
        return t;
    }
}
