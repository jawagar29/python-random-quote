package com.cleanhome.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A quiet home screen: a clock and a grid of apps. No rows of recommendations, no ads. */
public class MainActivity extends Activity {

    private static final int COLUMNS = 5;
    private static final String PREFS = "home";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_SHOWN = "shown";

    /** System apps worth showing by default. Every other system app starts out hidden. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "com.amazon.venezia",        // Appstore
            "com.amazon.firebat",        // Prime Video
            "com.amazon.avod",           // Prime Video (other firmware)
            "com.amazon.bueller.music"   // Amazon Music
    ));

    private static final String TILE_SETTINGS = "tile:settings";
    private static final String TILE_FIRE_HOME = "tile:firehome";
    private static final String TILE_HIDDEN = "tile:hidden";

    /** One tile on the grid: an installed app or one of the built-in tiles. */
    private static final class Entry {
        String key;      // package name, or a tile: id
        String label;
        Drawable banner; // wide artwork, when the app provides it
        Drawable icon;
        int color;
        boolean isApp;
    }

    private SharedPreferences prefs;
    private LinearLayout grid;
    private ScrollView scroll;
    private List<Entry> entries = new ArrayList<>();
    private volatile Set<String> autoHidden = new HashSet<>();
    private String focusKey;
    private boolean loading;
    private boolean reloadPending;
    private int cardW, cardH, gap;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = dp(58);
        gap = dp(22);
        cardW = (screenW - 2 * side - (COLUMNS - 1) * gap) / COLUMNS;
        cardH = cardW * 9 / 16;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF2A2D3A, 0xFF14151B, 0xFF08080A}));
        root.setClipChildren(false);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clockLp.setMargins(0, dp(26), side, 0);
        root.addView(clock, clockLp);

        scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setPadding(side, dp(30), side, dp(40));
        scroll.setFocusable(false);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        scroll.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrollLp.topMargin = dp(62);
        root.addView(scroll, scrollLp);

        setContentView(root);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        HomeService.bypass = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already here: go back to the first tile.
        if (hasWindowFocus() && grid.getChildCount() > 0) {
            scroll.smoothScrollTo(0, 0);
            View first = grid.findViewWithTag(entries.isEmpty() ? null : entries.get(0).key);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // This is the home screen; there is nowhere to go back to.
    }

    // ---------------------------------------------------------------- loading

    private void loadApps() {
        if (loading) {
            reloadPending = true;
            return;
        }
        loading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Entry> loaded = queryApps();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        entries = loaded;
                        rebuild();
                        if (reloadPending) {
                            reloadPending = false;
                            loadApps();
                        }
                    }
                });
            }
        }, "load-apps").start();
    }

    private List<Entry> queryApps() {
        PackageManager pm = getPackageManager();
        Set<String> hidden = hidden();
        Set<String> shown = new HashSet<>(prefs.getStringSet(KEY_SHOWN, new HashSet<String>()));
        Set<String> skipped = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<Entry> apps = new ArrayList<>();

        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || pkg.equals(HomeService.AMAZON_LAUNCHER)
                        || hidden.contains(pkg) || !seen.add(pkg)) {
                    continue;
                }
                boolean system = (ri.activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !KEEP.contains(pkg) && !shown.contains(pkg)) {
                    skipped.add(pkg);
                    continue;
                }
                Entry e = new Entry();
                e.key = pkg;
                e.isApp = true;
                e.label = String.valueOf(ri.loadLabel(pm));
                try {
                    e.banner = fit(ri.activityInfo.loadBanner(pm), cardW, cardH, true);
                } catch (RuntimeException ignored) {
                }
                if (e.banner == null) {
                    Drawable raw = null;
                    try {
                        raw = ri.loadIcon(pm);
                    } catch (RuntimeException ignored) {
                    }
                    e.color = tint(raw);
                    int size = cardH * 62 / 100;
                    e.icon = fit(raw, size, size, false);
                }
                apps.add(e);
            }
        }

        final List<String> order = order();
        Collections.sort(apps, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int ia = order.indexOf(a.key), ib = order.indexOf(b.key);
                if (ia >= 0 && ib >= 0) return ia - ib;
                if (ia >= 0) return -1;
                if (ib >= 0) return 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        apps.add(tile(TILE_SETTINGS, "Settings"));
        if (pm.getLaunchIntentForPackage(HomeService.AMAZON_LAUNCHER) != null || amazonHomeIntent()
                .resolveActivity(pm) != null) {
            apps.add(tile(TILE_FIRE_HOME, "Fire TV Home"));
        }
        if (!hidden.isEmpty() || !skipped.isEmpty()) {
            apps.add(tile(TILE_HIDDEN, "Hidden apps"));
        }
        autoHidden = skipped;
        return apps;
    }

    /**
     * Draws artwork once into a bitmap of exactly the size it is shown at, so that focus
     * animations never have to rescale a large image.
     */
    private Drawable fit(Drawable d, int w, int h, boolean crop) {
        if (d == null) {
            return null;
        }
        try {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) {
                iw = w;
                ih = h;
            }
            float sx = w / (float) iw, sy = h / (float) ih;
            float scale = crop ? Math.max(sx, sy) : Math.min(sx, sy);
            int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int left = (w - dw) / 2, top = (h - dh) / 2;
            d.setBounds(left, top, left + dw, top + dh);
            d.draw(new Canvas(bmp));
            return new BitmapDrawable(getResources(), bmp);
        } catch (RuntimeException | OutOfMemoryError e) {
            return d;
        }
    }

    private static int lighten(int color, float amount) {
        return Color.rgb(
                Math.round(Color.red(color) + (255 - Color.red(color)) * amount),
                Math.round(Color.green(color) + (255 - Color.green(color)) * amount),
                Math.round(Color.blue(color) + (255 - Color.blue(color)) * amount));
    }

    private static Entry tile(String key, String label) {
        Entry e = new Entry();
        e.key = key;
        e.label = label;
        e.color = 0xFF2C2C2E;
        return e;
    }

    /** A muted background colour taken from the app icon, for apps without wide artwork. */
    private static int tint(Drawable icon) {
        if (icon == null) {
            return 0xFF2C2C2E;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            icon.setBounds(0, 0, 24, 24);
            icon.draw(canvas);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int c = bmp.getPixel(x, y);
                    if (Color.alpha(c) > 128) {
                        r += Color.red(c);
                        g += Color.green(c);
                        b += Color.blue(c);
                        n++;
                    }
                }
            }
            bmp.recycle();
            if (n == 0) {
                return 0xFF2C2C2E;
            }
            // Darken so a bright icon still stands out against its card.
            return Color.rgb((int) (r / n * 0.5f) + 20, (int) (g / n * 0.5f) + 20,
                    (int) (b / n * 0.5f) + 20);
        } catch (RuntimeException e) {
            return 0xFF2C2C2E;
        }
    }

    // ---------------------------------------------------------------- grid

    private void rebuild() {
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            focusKey = (String) focused.getTag();
        }
        grid.removeAllViews();

        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(8);
                if (i == 0) {
                    // The first row sits on a soft shelf, like a dock for favourite apps.
                    int pad = dp(18);
                    GradientDrawable shelf = new GradientDrawable();
                    shelf.setColor(0x1FFFFFFF);
                    shelf.setCornerRadius(dp(26));
                    row.setBackground(shelf);
                    row.setPadding(pad, pad, pad, dp(6));
                    rowLp.setMargins(-pad, 0, -pad, dp(20));
                }
                grid.addView(row, rowLp);
            }
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLUMNS != 0) {
                cellLp.leftMargin = gap;
            }
            row.addView(buildCell(entries.get(i)), cellLp);
        }

        View target = focusKey == null ? null : grid.findViewWithTag(focusKey);
        if (target == null && !entries.isEmpty()) {
            target = grid.findViewWithTag(entries.get(0).key);
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    private View buildCell(final Entry e) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        final FrameLayout card = new FrameLayout(this);
        card.setTag(e.key);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                e.banner != null ? new int[] {0xFF1C1C1E, 0xFF1C1C1E}
                        : new int[] {lighten(e.color, 0.16f), e.color});
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(dp(2));

        if (e.banner != null) {
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setImageDrawable(e.banner);
            card.addView(art, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (e.icon != null) {
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageDrawable(e.icon);
            int size = cardH * 62 / 100;
            card.addView(art, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        } else {
            TextView name = new TextView(this);
            name.setText(e.label);
            name.setTextColor(0xF2FFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            name.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            name.setGravity(Gravity.CENTER);
            card.addView(name, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        cell.addView(card, new LinearLayout.LayoutParams(cardW, cardH));

        final TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(12);
        cell.addView(label, labelLp);

        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float scale = hasFocus ? 1.13f : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .translationZ(hasFocus ? dp(16) : 0).setDuration(150).withLayer().start();
                label.animate().alpha(hasFocus ? 1f : 0f).setDuration(140).start();
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(e);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return showMenu(e);
            }
        });
        card.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
                    return showMenu(e);
                }
                return false;
            }
        });
        return cell;
    }

    // ---------------------------------------------------------------- actions

    private Intent amazonHomeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeService.AMAZON_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void open(Entry e) {
        Intent intent;
        if (TILE_SETTINGS.equals(e.key)) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        } else if (TILE_FIRE_HOME.equals(e.key)) {
            HomeService.bypass = true;
            intent = amazonHomeIntent();
        } else if (TILE_HIDDEN.equals(e.key)) {
            showHidden();
            return;
        } else {
            PackageManager pm = getPackageManager();
            intent = pm.getLeanbackLaunchIntentForPackage(e.key);
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(e.key);
            }
        }
        if (intent == null) {
            Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException ex) {
            HomeService.bypass = false;
            Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean showMenu(final Entry e) {
        if (!e.isApp) {
            return false;
        }
        final String[] items = {"Move to front", "Move left", "Move right", "Hide",
                "App info", "Uninstall"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(e.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: move(e, Integer.MIN_VALUE); break;
                            case 1: move(e, -1); break;
                            case 2: move(e, 1); break;
                            case 3: hide(e); break;
                            case 4:
                                startSafely(new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + e.key)));
                                break;
                            default:
                                startSafely(new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + e.key)));
                        }
                    }
                })
                .show();
        return true;
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private void move(Entry e, int delta) {
        List<Entry> apps = new ArrayList<>();
        List<Entry> tiles = new ArrayList<>();
        for (Entry x : entries) {
            (x.isApp ? apps : tiles).add(x);
        }
        int from = apps.indexOf(e);
        if (from < 0) {
            return;
        }
        int to = delta == Integer.MIN_VALUE ? 0 : Math.max(0, Math.min(apps.size() - 1, from + delta));
        apps.remove(from);
        apps.add(to, e);

        List<String> keys = new ArrayList<>();
        for (Entry x : apps) {
            keys.add(x.key);
        }
        prefs.edit().putString(KEY_ORDER, TextUtils.join("\n", keys)).apply();

        apps.addAll(tiles);
        entries = apps;
        focusKey = e.key;
        rebuild();
    }

    private void hide(Entry e) {
        Set<String> hidden = hidden();
        hidden.add(e.key);
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
        focusKey = null;
        loadApps();
    }

    private void showHidden() {
        Set<String> all = hidden();
        all.addAll(autoHidden);
        final List<String> pkgs = new ArrayList<>(all);
        final String[] labels = new String[pkgs.size()];
        PackageManager pm = getPackageManager();
        for (int i = 0; i < labels.length; i++) {
            try {
                labels[i] = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkgs.get(i), 0)));
            } catch (PackageManager.NameNotFoundException ex) {
                labels[i] = pkgs.get(i);
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Select an app to show it again")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> hidden = hidden();
                        hidden.remove(pkgs.get(which));
                        Set<String> shown = new HashSet<>(
                                prefs.getStringSet(KEY_SHOWN, new HashSet<String>()));
                        shown.add(pkgs.get(which));
                        prefs.edit().putStringSet(KEY_HIDDEN, hidden)
                                .putStringSet(KEY_SHOWN, shown).apply();
                        loadApps();
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- storage

    private List<String> order() {
        String saved = prefs.getString(KEY_ORDER, "");
        return saved.isEmpty() ? new ArrayList<String>() : Arrays.asList(saved.split("\n"));
    }

    private Set<String> hidden() {
        // Copy: the set returned by SharedPreferences must not be modified.
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
