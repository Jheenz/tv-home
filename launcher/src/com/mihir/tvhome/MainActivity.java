package com.mihir.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, Control Center top-right, the focused dock app's name over an
 * a clean wallpaper, a dock of favorites, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock apps, hidden apps and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final List<String> favorites = new ArrayList<>();
    private String selectedPackage, movingPackage;
    private List<String> orderBeforeMove;
    private PopupWindow appMenu;
    private View activationTile;
    private Runnable pendingLongPress;
    private boolean swallowActivationRelease;
    private TextView emptyDockButton;
    private static final int DOCK_COLUMNS = 5;
    private static final int APP_GAP_DP = 16;
    private static final float APP_CORNER_DP = 14f;
    private final Set<String> hidden = new HashSet<>();
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private FrameLayout rootFrame, controlOverlay;
    private LinearLayout controlButton;
    private TextView wifiStatus;
    private TextView panelClock;
    private View appearanceControl;
    private boolean lightMode;
    private int panelReturnFocusId = View.NO_ID;
    private int lastDockFocusId = View.NO_ID;

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private ImageView wallBack, wallFront;
    private View gridShade, wallpaperScrim;
    private Bitmap frostedWallpaper, controlBackdrop;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, appsLabel;
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Updates send REMOVED before ADDED; do not erase a favorite mid-update.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())
                    && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            if (movingPackage != null) cancelMove();
            closeAppMenu();
            buildApps();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lightMode = getPreferences(MODE_PRIVATE).getBoolean("light_mode", false);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        cols = getResources().getInteger(R.integer.columns);
        loadFavorites();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        cancelActivation();
        closeAppMenu();
        if (movingPackage != null) cancelMove();
        unregisterReceiver(tick);
        closeControlCenter();
    }

    @Override
    protected void onDestroy() {
        cancelActivation();
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        cancelActivation();
        closeAppMenu();
        if (movingPackage != null) cancelMove();
        // Home pressed while already home: jump back to the first dock app.
        closeControlCenter();
        focusHome();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        if (appMenu != null) { closeAppMenu(); return; }
        if (movingPackage != null) { cancelMove(); return; }
        if (controlOverlay != null) { closeControlCenter(); return; }
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0) focusHome();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        root.setBackgroundColor(Color.BLACK);

        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, Control Center) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        wallpaperScrim = scrim;
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(18, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(14), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(6), 0, 0, dp(10));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(15), dp(16), dp(15), dp(16));
        dockRow.setBackground(new FrostedGlass(dockRow, 20, false));
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ALL APPS  ·  HOLD OK FOR OPTIONS");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        controlButton = new LinearLayout(this);
        controlButton.setGravity(Gravity.CENTER_VERTICAL);
        controlButton.setPadding(dp(14), 0, dp(14), 0);
        controlButton.setId(View.generateViewId());
        controlButton.setFocusable(true);
        controlButton.setClickable(true);
        controlButton.setContentDescription("Control Center");
        GradientDrawable capsule = roundedBackground(surfaceColor(), 18, surfaceBorder());
        controlButton.setBackground(capsule); // same translucency as the dock
        ImageView controlIcon = vectorIcon(R.drawable.ic_control_center, 22);
        controlIcon.setColorFilter(controlTextColor());
        controlButton.addView(controlIcon);
        controlButton.setOnClickListener(v -> showControlCenter());
        controlButton.setOnFocusChangeListener((v, has) -> {
            capsule.setColor(has ? 0x55FFFFFF : surfaceColor());
            capsule.setStroke(dp(1), has ? 0x99FFFFFF : surfaceBorder());
            if (has) {
                lastFocusedId = v.getId();
                scroll.smoothScrollTo(0, 0);
                updateGridShade(false);
            }
        });
        header.addView(controlButton, new FrameLayout.LayoutParams(-2, dp(36), Gravity.END | Gravity.TOP));
        return header;
    }

    private GradientDrawable roundedBackground(int color, float radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), stroke);
        return bg;
    }

    private ImageView vectorIcon(int resource, int size) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        boolean keepControlFocus = getCurrentFocus() == controlButton;
        String restorePackage = movingPackage != null ? movingPackage : selectedPackage;
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        // Remove only packages that are actually uninstalled, not disabled/temporarily hidden apps.
        boolean pruned = favorites.removeIf(pkg -> !packageInstalled(pkg));
        if (pruned && movingPackage == null) saveFavorites();
        List<App> dock = new ArrayList<>();
        for (String pkg : favorites) {
            if (byPkg.containsKey(pkg)) dock.add(byPkg.remove(pkg));
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        emptyDockButton = null;

        // Five weighted slots fill the dock. Empty trailing slots keep fewer favorites left aligned.
        int gap = dp(APP_GAP_DP);
        if (!dock.isEmpty()) for (int i = 0; i < DOCK_COLUMNS; i++) {
            View slot = i < dock.size() ? tile(dock.get(i), true) : new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, i < dock.size() ? -2 : 1, 1f);
            p.setMargins(0, 0, i == DOCK_COLUMNS - 1 ? 0 : gap, 0);
            dockRow.addView(slot, p);
        }

        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(20)));
            }
            View tile = tile(rest.get(i), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(0, 0, i % cols == cols - 1 ? 0 : gap, 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(0, 0, i == cols - 1 ? 0 : gap, 0);
            row.addView(new View(this), p);
        }
        if (dock.isEmpty()) {
            emptyDockButton = text(14, "sans-serif-medium", Color.WHITE);
            emptyDockButton.setText("All Apps  ↓");
            emptyDockButton.setContentDescription("All Apps");
            emptyDockButton.setGravity(Gravity.CENTER);
            emptyDockButton.setId(View.generateViewId());
            emptyDockButton.setFocusable(true);
            emptyDockButton.setClickable(true);
            GradientDrawable emptyBg = roundedBackground(0x20FFFFFF, 12, 0x33FFFFFF);
            emptyDockButton.setBackground(emptyBg);
            emptyDockButton.setOnFocusChangeListener((v, has) -> {
                emptyBg.setColor(has ? 0x38FFFFFF : 0x20FFFFFF);
                if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); }
            });
            emptyDockButton.setOnClickListener(v -> {
                if (!gridCards.isEmpty()) gridCards.get(0).requestFocus();
            });
            dockRow.addView(emptyDockButton, new LinearLayout.LayoutParams(dp(220), dp(64)));
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();
        if (movingPackage != null) {
            title.setText("MOVE  ·  ← / →   OK saves   Back cancels");
        } else if (!dock.isEmpty()) {
            title.setText(dock.get(0).label);
        } else {
            title.setText(rest.isEmpty() ? "No TV apps installed" : "Hold OK on an app to add a favorite");
        }
        View target = findAppCard(restorePackage);
        if (keepControlFocus) controlButton.requestFocus();
        else if (target != null) target.requestFocus();
        else focusHome();
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setContentDescription(app.label);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(APP_CORNER_DP));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(inDock ? 16.5f : 22), dp(inDock ? 10.5f : 14),
                    dp(inDock ? 16.5f : 22), dp(inDock ? 10.5f : 14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(APP_CORNER_DP));
        ring.setStroke(dp(inDock ? 1.5f : 2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(8), 0, 0));

        card.setOnLongClickListener(v -> { showAppMenu(app); return true; });
        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.04f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(inDock ? 10 : 14) : 0)
                    .setInterpolator(new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            selectedPackage = app.pkg;
            if (inDock) {
                lastDockFocusId = v.getId();
                controlButton.setNextFocusDownId(v.getId());
                title.setText(movingPackage == null ? app.label : "MOVE  ·  ← / →   OK saves   Back cancels");
                title.animate().alpha(1f).setDuration(200).start();
                updateGridShade(false);
                scroll.smoothScrollTo(0, 0);
            } else {
                updateGridShade(true);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    // ---------------------------------------------------------------- favorites

    private android.content.SharedPreferences favoritePrefs() {
        return getSharedPreferences("favorites", MODE_PRIVATE);
    }

    private void loadFavorites() {
        favorites.clear();
        if (!favoritePrefs().contains("order")) {
            for (String pkg : dockApps) {
                if (favorites.size() < DOCK_COLUMNS && !favorites.contains(pkg) && packageInstalled(pkg))
                    favorites.add(pkg);
            }
        } else {
            try {
                JSONArray saved = new JSONArray(favoritePrefs().getString("order", "[]"));
                for (int i = 0; i < saved.length() && favorites.size() < DOCK_COLUMNS; i++) {
                    String pkg = saved.optString(i, "");
                    if (!pkg.isEmpty() && !favorites.contains(pkg) && packageInstalled(pkg)) favorites.add(pkg);
                }
            } catch (JSONException ignored) {
                // An invalid saved list becomes an explicit empty dock, with All Apps available.
            }
        }
        saveFavorites();
    }

    private void saveFavorites() {
        // Tiny list (at most five names); synchronous commit makes an immediate reboot safe.
        favoritePrefs().edit().putString("order", new JSONArray(favorites).toString()).commit();
    }

    private boolean packageInstalled(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private View findAppCard(String pkg) {
        if (pkg == null) return null;
        for (View v : dockCards) if (((App) v.getTag()).pkg.equals(pkg)) return v;
        for (View v : gridCards) if (((App) v.getTag()).pkg.equals(pkg)) return v;
        return null;
    }

    private void focusHome() {
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        else if (emptyDockButton != null) emptyDockButton.requestFocus();
        else controlButton.requestFocus();
    }

    private void showAppMenu(App app) {
        if (appMenu != null || movingPackage != null) return;
        View anchor = findAppCard(app.pkg);
        if (anchor == null) return;
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(10), dp(12), dp(10), dp(10));
        menu.setBackground(roundedBackground(0xEE17191F, 16, 0x26FFFFFF));
        TextView heading = text(13, "sans-serif-medium", Color.WHITE);
        heading.setText(app.label);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        menu.addView(heading, llp(-1, -2, dp(10), 0, dp(10), dp(8)));
        List<View> rows = new ArrayList<>();
        boolean favorite = favorites.contains(app.pkg);
        if (favorite) {
            rows.add(appMenuRow("Remove from Favorites", () -> {
                closeAppMenu(); favorites.remove(app.pkg); saveFavorites();
                selectedPackage = app.pkg; buildApps();
            }));
            if (favorites.size() > 1) rows.add(appMenuRow("Move", () -> {
                closeAppMenu(); orderBeforeMove = new ArrayList<>(favorites);
                movingPackage = app.pkg; selectedPackage = app.pkg; buildApps();
            }));
        } else {
            rows.add(appMenuRow("Add to Favorites", () -> {
                if (favorites.size() >= DOCK_COLUMNS) {
                    Toast.makeText(this, "Favorites are full (5). Remove a favorite first.", Toast.LENGTH_LONG).show();
                    return;
                }
                closeAppMenu();
                if (!favorites.contains(app.pkg)) favorites.add(app.pkg);
                saveFavorites(); selectedPackage = app.pkg; buildApps();
            }));
        }
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            row.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            row.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            row.setNextFocusLeftId(row.getId()); row.setNextFocusRightId(row.getId());
            menu.addView(row, llp(-1, dp(38), 0, dp(3), 0, 0));
        }
        int width = dp(230);
        menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = menu.getMeasuredHeight();
        int[] location = new int[2]; anchor.getLocationOnScreen(location);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int x = location[0] + anchor.getWidth() + dp(12);
        if (x + width > screenW - dp(24)) x = location[0] - width - dp(12);
        x = Math.max(dp(24), Math.min(x, screenW - width - dp(24)));
        int y = Math.max(dp(24), Math.min(location[1], screenH - height - dp(24)));
        PopupWindow popup = new PopupWindow(menu, width, height, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> {
            appMenu = null;
            View tile = findAppCard(app.pkg);
            if (tile != null) tile.requestFocus(); else focusHome();
        });
        appMenu = popup;
        popup.showAtLocation(rootFrame, Gravity.TOP | Gravity.LEFT, x, y);
        rows.get(0).requestFocus();
    }

    private View appMenuRow(String label, Runnable action) {
        TextView row = text(12, "sans-serif-medium", Color.WHITE);
        row.setText(label); row.setContentDescription(label);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), 0, dp(10), 0);
        row.setId(View.generateViewId()); row.setFocusable(true); row.setClickable(true);
        GradientDrawable bg = roundedBackground(Color.TRANSPARENT, 10, Color.TRANSPARENT);
        row.setBackground(bg);
        row.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x24FFFFFF : Color.TRANSPARENT);
            bg.setStroke(dp(1), has ? 0x38FFFFFF : Color.TRANSPARENT);
        });
        // The opening key's release/repeats may be delivered to the popup's new window.
        row.setOnKeyListener((v, code, event) -> {
            if (isActivationKey(code) && swallowActivationRelease) {
                if (event.getAction() == KeyEvent.ACTION_UP) swallowActivationRelease = false;
                return true;
            }
            return false;
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void closeAppMenu() {
        if (appMenu != null) appMenu.dismiss();
    }

    private void cancelMove() {
        if (movingPackage == null) return;
        favorites.clear(); favorites.addAll(orderBeforeMove);
        selectedPackage = movingPackage; movingPackage = null; orderBeforeMove = null;
        buildApps(); // unsaved preview is discarded; stored order was never changed
    }

    private boolean isActivationKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void cancelActivation() {
        if (pendingLongPress != null) handler.removeCallbacks(pendingLongPress);
        pendingLongPress = null; activationTile = null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (movingPackage != null) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    int from = favorites.indexOf(movingPackage);
                    int to = Math.max(0, Math.min(favorites.size() - 1,
                            from + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    if (from != to) { Collections.swap(favorites, from, to); buildApps(); }
                }
                return true;
            }
            if (isActivationKey(key)) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    selectedPackage = movingPackage; movingPackage = null; orderBeforeMove = null;
                    saveFavorites(); buildApps();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        }
        if (isActivationKey(key)) {
            if (swallowActivationRelease) {
                if (event.getAction() == KeyEvent.ACTION_UP) swallowActivationRelease = false;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                View focused = getCurrentFocus();
                if (activationTile != null) {
                    if (event.isLongPress() && pendingLongPress != null) {
                        handler.removeCallbacks(pendingLongPress);
                        pendingLongPress.run();
                    }
                    return true;
                }
                if (event.getRepeatCount() == 0 && focused != null && focused.getTag() instanceof App) {
                    activationTile = focused;
                    App app = (App) focused.getTag();
                    pendingLongPress = () -> {
                        if (activationTile != null && activationTile.hasFocus()) {
                            activationTile = null; pendingLongPress = null;
                            swallowActivationRelease = true; showAppMenu(app);
                        }
                    };
                    handler.postDelayed(pendingLongPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP && activationTile != null) {
                View tile = activationTile;
                boolean launch = tile.hasFocus() && !event.isCanceled();
                cancelActivation();
                if (launch) tile.performClick();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        int topId = controlButton.getId();
        controlButton.setNextFocusLeftId(topId);
        controlButton.setNextFocusRightId(topId);
        controlButton.setNextFocusUpId(topId);
        View returnTile = lastDockFocusId == View.NO_ID ? null : findViewById(lastDockFocusId);
        controlButton.setNextFocusDownId(returnTile != null ? returnTile.getId()
                : d > 0 ? dockCards.get(0).getId() : emptyDockButton != null ? emptyDockButton.getId()
                : g > 0 ? gridCards.get(0).getId() : topId);
        if (emptyDockButton != null) {
            int id = emptyDockButton.getId();
            emptyDockButton.setNextFocusUpId(topId);
            emptyDockButton.setNextFocusLeftId(id);
            emptyDockButton.setNextFocusRightId(id);
            emptyDockButton.setNextFocusDownId(g > 0 ? gridCards.get(0).getId() : id);
        }
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : emptyDockButton != null ? emptyDockButton.getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- Control Center

    /** Blur small snapshots rather than continuously filtering the full TV framebuffer. */
    private Bitmap blurredSnapshot(boolean wallpaperOnly) {
        int width = rootFrame.getWidth(), height = rootFrame.getHeight();
        if (width == 0 || height == 0) return null;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width / 4), Math.max(1, height / 4), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / (float) width, bitmap.getHeight() / (float) height);
        if (wallpaperOnly) {
            canvas.drawColor(Color.BLACK);
            wallBack.draw(canvas);
            wallFront.draw(canvas);
            wallpaperScrim.draw(canvas);
        } else rootFrame.draw(canvas);
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h], temp = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        // One gentle pass softens detail without obscuring the wallpaper.
        blurAxis(pixels, temp, w, h, true);
        blurAxis(temp, pixels, w, h, false);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static void blurAxis(int[] source, int[] target, int w, int h, boolean horizontal) {
        int radius = 2, diameter = radius * 2 + 1;
        int length = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int position = Math.max(0, Math.min(length - 1, k));
                int color = source[horizontal ? line * w + position : position * w + line];
                r += Color.red(color); g += Color.green(color); b += Color.blue(color);
            }
            for (int i = 0; i < length; i++) {
                target[horizontal ? line * w + i : i * w + line] = Color.rgb(r / diameter, g / diameter, b / diameter);
                int remove = Math.max(0, i - radius), add = Math.min(length - 1, i + radius + 1);
                int old = source[horizontal ? line * w + remove : remove * w + line];
                int next = source[horizontal ? line * w + add : add * w + line];
                r += Color.red(next) - Color.red(old);
                g += Color.green(next) - Color.green(old);
                b += Color.blue(next) - Color.blue(old);
            }
        }
    }

    private void refreshFrostedWallpaper() {
        frostedWallpaper = blurredSnapshot(true);
        if (dockRow != null) dockRow.invalidate();
    }

    /** Draw the correctly aligned blurred backdrop, tint, highlight and fine glass edge. */
    private final class FrostedGlass extends Drawable {
        private final View owner;
        private final float radius;
        private final boolean panel;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final int[] location = new int[2], rootLocation = new int[2];
        private boolean focused;

        FrostedGlass(View owner, float radius, boolean panel) {
            this.owner = owner; this.radius = dp(radius); this.panel = panel;
        }

        void setFocused(boolean value) { focused = value; invalidateSelf(); }

        @Override public void draw(Canvas canvas) {
            paint.setAlpha(110);
            RectF area = new RectF(getBounds());
            clip.reset(); clip.addRoundRect(area, radius, radius, Path.Direction.CW);
            int save = canvas.save(); canvas.clipPath(clip);
            Bitmap source = panel ? controlBackdrop : frostedWallpaper;
            if (source != null) {
                owner.getLocationInWindow(location); rootFrame.getLocationInWindow(rootLocation);
                float x = location[0] - rootLocation[0], y = location[1] - rootLocation[1];
                canvas.drawBitmap(source, null, new RectF(-x, -y, rootFrame.getWidth() - x,
                        rootFrame.getHeight() - y), paint);
            }
            int[] colors = focused ? new int[]{0xFFF6FCFF, 0xEEF1FAFF}
                    : lightMode ? new int[]{0x80FFFFFF, 0x50E9F0F8, 0x66DDE7F1}
                    : new int[]{0x26435369, 0x18212B3B, 0x30101925};
            GradientDrawable tint = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
            tint.setBounds(getBounds()); tint.draw(canvas);
            canvas.restoreToCount(save);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(focused ? 0xEEFFFFFF : lightMode ? 0xCCFFFFFF : 0x669DB4CF);
            area.inset(dp(0.5f), dp(0.5f));
            canvas.drawRoundRect(area, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private int cc(float value) { return dp(value * 0.8f); }

    private int surfaceColor() { return lightMode ? 0x99FFFFFF : 0x1FFFFFFF; }
    private int surfaceBorder() { return lightMode ? 0xBBFFFFFF : 0x14FFFFFF; }
    private int controlTextColor() { return lightMode ? 0xFF17191F : Color.WHITE; }

    private void showControlCenter() {
        if (controlOverlay != null) return;
        View previous = getCurrentFocus();
        panelReturnFocusId = previous == null ? controlButton.getId() : previous.getId();
        controlButton.setVisibility(View.INVISIBLE);
        controlBackdrop = blurredSnapshot(false);
        controlOverlay = new FrameLayout(this);
        if (controlBackdrop != null) {
            ImageView backdrop = new ImageView(this);
            backdrop.setImageBitmap(controlBackdrop);
            backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            controlOverlay.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        }
        View dim = new View(this);
        dim.setBackgroundColor(lightMode ? 0x18000000 : 0x30000000);
        controlOverlay.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        controlOverlay.setClickable(true);
        controlOverlay.setOnClickListener(v -> closeControlCenter());
        rootFrame.addView(controlOverlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setClipChildren(false);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panelClock = text(14, "sans-serif-medium", Color.WHITE);
        header.addView(panelClock, new LinearLayout.LayoutParams(0, -2, 1));
        View close = controlTile(R.drawable.ic_control_center, "Close Control Center", null, false,
                this::closeControlCenter);
        header.addView(close, new LinearLayout.LayoutParams(cc(36), cc(36)));
        panel.addView(header, llp(-1, cc(36), cc(6), 0, 0, cc(16)));

        FrameLayout tiles = new FrameLayout(this);
        View standby = controlTile(R.drawable.ic_standby, "Standby", null, true, this::standby);
        View wifi = controlTile(R.drawable.ic_wifi, "Wi-Fi", "Connected", false,
                () -> openControlSettings(Settings.ACTION_WIFI_SETTINGS));
        View bluetooth = controlTile(R.drawable.ic_bluetooth, "Bluetooth", bluetoothState(), false,
                () -> openControlSettings(Settings.ACTION_BLUETOOTH_SETTINGS));
        View inputs = controlTile(R.drawable.ic_inputs, "Inputs", null, false,
                () -> openControlSettings("android.settings.TV_INPUT_SETTINGS"));
        View settings = controlTile(R.drawable.ic_settings, "Settings", null, false,
                () -> openControlSettings(Settings.ACTION_SETTINGS));
        placeControl(tiles, standby, 0, 0, 152, 152);
        placeControl(tiles, wifi, 164, 0, 152, 70);
        placeControl(tiles, bluetooth, 164, 82, 152, 70);
        placeControl(tiles, inputs, 0, 164, 152, 70);
        placeControl(tiles, settings, 164, 164, 152, 70);
        panel.addView(tiles, new LinearLayout.LayoutParams(-1, cc(234)));

        LinearLayout shortcuts = new LinearLayout(this);
        View mic = controlTile(R.drawable.ic_mic, "Assistant", null, false, this::openAssistant);
        View airplay = controlTile(R.drawable.ic_airplay, "AirPlay", null, false, this::openAirPlay);
        View appearance = controlTile(lightMode ? R.drawable.ic_moon : R.drawable.ic_sun,
                lightMode ? "Switch to dark mode" : "Switch to light mode", null, false, () -> {
                    lightMode = !lightMode;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("light_mode", lightMode).apply();
                    dockRow.setBackground(new FrostedGlass(dockRow, 20, false));
                    GradientDrawable capsule = (GradientDrawable) controlButton.getBackground();
                    capsule.setColor(surfaceColor());
                    capsule.setStroke(cc(1), surfaceBorder());
                    ((ImageView) controlButton.getChildAt(0)).setColorFilter(controlTextColor());
                    showControlCenter();
                    appearanceControl.requestFocusFromTouch();
                });
        appearanceControl = appearance;
        View display = controlTile(R.drawable.ic_display, "Display settings", null, false,
                () -> openControlSettings(Settings.ACTION_DISPLAY_SETTINGS));
        View[] bottom = {mic, airplay, appearance, display};
        for (int i = 0; i < bottom.length; i++)
            shortcuts.addView(bottom[i], llp(cc(64), cc(64), i == 0 ? 0 : cc(20), 0, 0, 0));
        panel.addView(shortcuts, llp(-1, cc(64), 0, cc(14), 0, 0));

        // Explicit D-pad routes follow the visual columns and keep focus inside the panel.
        route(close, close, wifi, standby, close);
        route(standby, close, inputs, standby, wifi);
        route(wifi, close, bluetooth, standby, wifi);
        route(bluetooth, wifi, settings, standby, bluetooth);
        route(inputs, standby, mic, inputs, settings);
        route(settings, bluetooth, display, inputs, settings);
        for (int i = 0; i < bottom.length; i++)
            route(bottom[i], i < 2 ? inputs : settings, bottom[i],
                    bottom[Math.max(0, i - 1)], bottom[Math.min(3, i + 1)]);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(cc(316), -2, Gravity.TOP | Gravity.END);
        position.setMargins(dp(24), dp(36), dp(48), dp(24));
        controlOverlay.addView(panel, position);
        panel.setAlpha(0f);
        panel.setTranslationY(-cc(6));
        panel.animate().alpha(1f).translationY(0).setDuration(140)
                .setInterpolator(new DecelerateInterpolator()).start();
        updateClock();
        updateWifi();
        standby.requestFocusFromTouch();
    }

    private void route(View view, View up, View down, View left, View right) {
        view.setNextFocusUpId(up.getId());
        view.setNextFocusDownId(down.getId());
        view.setNextFocusLeftId(left.getId());
        view.setNextFocusRightId(right.getId());
    }

    private void placeControl(FrameLayout parent, View tile, int x, int y, int width, int height) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(cc(width), cc(height));
        p.leftMargin = cc(x); p.topMargin = cc(y);
        parent.addView(tile, p);
    }

    private View controlTile(int icon, String label, String subtitle, boolean tall, Runnable action) {
        boolean iconOnly = label.equals("Assistant") || label.equals("AirPlay")
                || label.contains("mode") || label.equals("Display settings") || label.startsWith("Close");
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(tall ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(cc(iconOnly ? 0 : 14), 0, cc(iconOnly ? 0 : 14), 0);
        tile.setId(View.generateViewId());
        tile.setFocusable(true); tile.setClickable(true);
        tile.setContentDescription(label + (subtitle == null ? "" : ", " + subtitle));
        FrostedGlass bg = new FrostedGlass(tile, (tall ? 26 : 36) * 0.8f, true);
        tile.setBackground(bg);
        ImageView image = vectorIcon(icon, tall ? 37 : iconOnly ? 20 : 19);
        image.setColorFilter(controlTextColor());
        tile.addView(image);
        List<TextView> labels = new ArrayList<>();
        if (!iconOnly) {
            LinearLayout stack = new LinearLayout(this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(tall ? Gravity.CENTER : Gravity.START);
            TextView name = text(11.5f, "sans-serif-medium", controlTextColor());
            name.setText(label); name.setSingleLine(true);
            stack.addView(name); labels.add(name);
            if (subtitle != null) {
                TextView status = text(9, "sans-serif", controlTextColor());
                status.setAlpha(0.75f); status.setText(subtitle); status.setSingleLine(true);
                status.setEllipsize(android.text.TextUtils.TruncateAt.END);
                stack.addView(status, llp(-1, -2, 0, cc(4), 0, 0)); labels.add(status);
                if (label.equals("Wi-Fi")) wifiStatus = status;
            }
            tile.addView(stack, llp(tall ? -2 : 0, -2, tall ? 0 : cc(12), tall ? cc(18) : 0, 0, 0));
            if (!tall) ((LinearLayout.LayoutParams) stack.getLayoutParams()).weight = 1;
        }
        tile.setOnFocusChangeListener((v, focused) -> {
            bg.setFocused(focused);
            int tint = focused ? 0xFF17191F : controlTextColor();
            image.setColorFilter(tint);
            for (TextView t : labels) t.setTextColor(tint);
        });
        tile.setOnClickListener(v -> { closeControlCenter(); action.run(); });
        return tile;
    }

    private String bluetoothState() {
        android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        try { return adapter != null && adapter.isEnabled() ? "On" : "Off"; }
        catch (SecurityException e) { return "Settings"; }
    }

    private void standby() {
        if (!StandbyService.sleep()) {
            Toast.makeText(this, "Enable Home standby control in Accessibility to use Standby", Toast.LENGTH_LONG).show();
            openControlSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        }
    }

    private void openAssistant() {
        for (String action : new String[]{"android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST, Intent.ACTION_SEARCH_LONG_PRESS}) {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) != null && launch(intent)) return;
        }
        Toast.makeText(this, "No assistant is available", Toast.LENGTH_SHORT).show();
    }

    private void openAirPlay() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> receivers = new ArrayList<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER})
            receivers.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0));
        // Prefer an installed system AirPlay receiver over third-party receiver apps.
        receivers.sort((a, b) -> Boolean.compare(
                (b.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                (a.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0));
        for (ResolveInfo info : receivers) {
            String label = info.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            String pkg = info.activityInfo.packageName;
            if (label.contains("airplay") || label.contains("airscreen") || label.contains("airreceiver")
                    || pkg.equals("io.github.jqssun.airplay") || pkg.equals("com.softmedia.receiver")
                    || pkg.equals("com.waxrain.airplaydmr")) {
                Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name)).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                if (launch(intent)) return;
            }
        }
        Toast.makeText(this, "No AirPlay receiver app is installed", Toast.LENGTH_LONG).show();
    }

    private void openControlSettings(String action) {
        Intent intent = new Intent(action);
        // Some TV firmware has no Bluetooth settings action. Open system Settings
        // so the user can choose Remotes & Accessories without starting pairing.
        if (Settings.ACTION_BLUETOOTH_SETTINGS.equals(action)
                && intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        // Specific settings must open their activity, even if another settings
        // page is currently above it in the existing system Settings task.
        if (!Settings.ACTION_SETTINGS.equals(intent.getAction())) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (intent.resolveActivity(getPackageManager()) == null || !launch(intent)) {
            launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void closeControlCenter() {
        if (controlOverlay == null) return;
        rootFrame.removeView(controlOverlay);
        controlOverlay = null;
        controlBackdrop = null;
        controlButton.setVisibility(View.VISIBLE);
        wifiStatus = null;
        panelClock = null;
        appearanceControl = null;
        View previous = findViewById(panelReturnFocusId);
        if (previous != null) previous.requestFocus();
        else controlButton.requestFocus();
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        if (panelClock != null) panelClock.setText(new SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a",
                Locale.getDefault()).format(now.getTime()));
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        if (wifiStatus == null) return;
        String name = "Connected";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
            }
            // Android 10 can redact SSID/network ID without location permission.
            // Use connectivity state rather than a redacted ID to detect offline mode.
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo network = cm == null ? null : cm.getActiveNetworkInfo();
            if (network == null || !network.isConnected()) name = "Offline";
        } catch (SecurityException ignored) { }
        wifiStatus.setText(name);
    }

    // ---------------------------------------------------------------- grid readability

    private void updateGridShade(boolean browsingGrid) {
        // Only a neutral scrim; app selection never colors the wallpaper.
        gridShade.animate().alpha(browsingGrid ? 1f : 0f).setDuration(200).start();
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
            refreshFrostedWallpaper();
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Sample artwork for the fallback tile background only; never tint the wallpaper. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
