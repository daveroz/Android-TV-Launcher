package com.camdeco.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The home screen: a clock, two buttons and a grid of every app. */
public class HomeActivity extends Activity implements AppAdapter.Listener {
    private static final int COLUMNS = 5;
    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private AppRepository repo;
    private AppAdapter adapter;
    private RecyclerView grid;
    private View empty;
    private List<AppEntry> allApps = new ArrayList<>();
    /** Package of the tile being moved, or null when not moving. */
    private String movingPackage;

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        repo = new AppRepository(this);
        empty = findViewById(R.id.empty);
        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        adapter = new AppAdapter(this);
        grid.setAdapter(adapter);

        findViewById(R.id.settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.options).setOnClickListener(v -> showLauncherOptions());

        // Refresh the grid when apps are installed, removed, updated, enabled or disabled.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            // These are protected broadcasts: only the system can send them.
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (movingPackage != null) endMove();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home screen: jump back to the first app.
        boolean alreadyHome = hasWindowFocus()
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0;
        if (alreadyHome) {
            if (movingPackage != null) endMove();
            focusPosition(0);
        }
    }

    // ---- Loading apps ----

    private void reload() {
        if (loader.isShutdown()) return;
        loader.execute(() -> {
            List<AppEntry> apps = repo.loadAll();
            main.post(() -> {
                if (isDestroyed()) return;
                if (movingPackage != null) endMove();
                repo.sort(apps);
                allApps = apps;
                showApps();
            });
        });
    }

    private void showApps() {
        Set<String> hidden = repo.getHidden();
        List<AppEntry> visible = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (!hidden.contains(app.packageName)) visible.add(app);
        }
        boolean firstLoad = adapter.getItemCount() == 0;
        adapter.submit(visible);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        if (firstLoad) focusPosition(0);
    }

    private void focusPosition(int position) {
        int count = adapter.getItemCount();
        if (count == 0) return;
        int target = Math.max(0, Math.min(position, count - 1));
        grid.scrollToPosition(target);
        focusWhenLaidOut(target, 10);
    }

    private void focusWhenLaidOut(int position, int attemptsLeft) {
        grid.post(() -> {
            RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.itemView.requestFocus();
            } else if (attemptsLeft > 0) {
                main.postDelayed(() -> focusWhenLaidOut(position, attemptsLeft - 1), 30);
            }
        });
    }

    // ---- Tiles ----

    @Override
    public void onOpen(AppEntry app) {
        open(new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }

    @Override
    public void onOptions(AppEntry app) {
        String[] items = {
                getString(R.string.option_move),
                getString(R.string.option_hide),
                getString(R.string.option_app_info),
                getString(R.string.option_uninstall),
        };
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(app.label)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startMove(app);
                            break;
                        case 1:
                            hide(app);
                            break;
                        case 2:
                            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(app)));
                            break;
                        case 3:
                            open(new Intent(Intent.ACTION_DELETE, packageUri(app)));
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void startMove(AppEntry app) {
        movingPackage = app.packageName;
        adapter.setMoving(movingPackage);
        focusPosition(adapter.indexOf(movingPackage));
        toast(R.string.move_hint);
    }

    private void moveBy(int delta) {
        int from = adapter.indexOf(movingPackage);
        if (from < 0) {
            endMove();
            return;
        }
        int to = Math.max(0, Math.min(adapter.getItemCount() - 1, from + delta));
        if (to == from) return;
        adapter.move(from, to);
        focusPosition(to);
    }

    private void endMove() {
        movingPackage = null;
        adapter.setMoving(null);
        repo.saveOrder(adapter.items());
        repo.sort(allApps);
    }

    private void hide(AppEntry app) {
        int position = adapter.indexOf(app.packageName);
        Set<String> hidden = repo.getHidden();
        hidden.add(app.packageName);
        repo.setHidden(hidden);
        showApps();
        focusPosition(position);
        toast(R.string.hidden_hint);
    }

    // ---- Launcher menu ----

    private void showLauncherOptions() {
        List<AppEntry> hiddenApps = hiddenApps();
        String[] items = {
                getString(R.string.option_hidden_apps, hiddenApps.size()),
                getString(R.string.option_home_setup),
                getString(R.string.option_tv_settings),
        };
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showHiddenApps(hiddenApps);
                    else if (which == 1) showHomeSetup();
                    else open(new Intent(Settings.ACTION_SETTINGS));
                })
                .show();
    }

    private List<AppEntry> hiddenApps() {
        Set<String> hidden = repo.getHidden();
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : allApps) {
            if (hidden.contains(app.packageName)) result.add(app);
        }
        return result;
    }

    private void showHiddenApps(List<AppEntry> hiddenApps) {
        if (hiddenApps.isEmpty()) {
            toast(R.string.no_hidden_apps);
            return;
        }
        String[] labels = new String[hiddenApps.size()];
        boolean[] checked = new boolean[hiddenApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenApps.get(i).label;
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.unhide_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.unhide, (dialog, which) -> {
                    Set<String> hidden = repo.getHidden();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) hidden.remove(hiddenApps.get(i).packageName);
                    }
                    repo.setHidden(hidden);
                    showApps();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showHomeSetup() {
        String message = getString(R.string.home_setup_message,
                getString(isDefaultHome() ? R.string.yes : R.string.no),
                getString(isOverrideOn() ? R.string.on : R.string.off));
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.option_home_setup)
                .setMessage(message)
                .setPositiveButton(R.string.open_accessibility,
                        (dialog, which) -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && getPackageName().equals(info.activityInfo.packageName);
    }

    private boolean isOverrideOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName service = new ComponentName(this, HomeButtonService.class);
        return enabled.contains(service.flattenToString()) || enabled.contains(service.flattenToShortString());
    }

    // ---- Remote buttons ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (movingPackage != null && isMoveKey(code)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int sideways = grid.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        moveBy(-sideways);
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        moveBy(sideways);
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveBy(-COLUMNS);
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveBy(COLUMNS);
                        break;
                    default:
                        endMove(); // OK or Back drops the tile where it is
                        break;
                }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            return true; // the home screen has nowhere to go back to
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            View focused = grid.getFocusedChild(); // the focused tile, or null if focus is elsewhere
            if (focused != null) {
                if (event.getAction() == KeyEvent.ACTION_UP) focused.performLongClick();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isMoveKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    // ---- Helpers ----

    private void open(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(R.string.cannot_open);
        }
    }

    private static Uri packageUri(AppEntry app) {
        return Uri.fromParts("package", app.packageName, null);
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
