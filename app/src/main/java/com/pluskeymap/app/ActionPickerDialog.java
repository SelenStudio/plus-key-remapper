package com.pluskeymap.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tabbed bottom-sheet action picker.
 * Tabs: Apps | Presets | Custom
 */
public class ActionPickerDialog extends BottomSheetDialog {

    public interface OnActionSelectedListener {
        /** Called when user confirms a selection. stored is the value to save to prefs. */
        void onActionSelected(int actionType, String stored, String displayLabel);
    }

    // actionType values reuse ActionConfig constants; ACTION_LAUNCH_APP = open app,
    // ACTION_CUSTOM_INTENT = custom, anything else = preset system action.
    private static final int TAB_APPS    = 0;
    private static final int TAB_PRESETS = 1;
    private static final int TAB_CUSTOM  = 2;

    private final OnActionSelectedListener listener;
    private final SharedPreferences prefs;

    // Views
    private TabLayout tabLayout;
    private View pageApps, pagePresets, pageCustom;

    // Apps tab
    private RecyclerView rvApps;
    private TextInputEditText etSearch;
    private TextView tvAppsLoading;
    private AppAdapter appAdapter;
    private List<AppInfo> allApps = new ArrayList<>();

    // Custom tab
    private TextInputEditText etAction, etPackage, etComponent, etData;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ActionPickerDialog(@NonNull Context context, OnActionSelectedListener listener) {
        super(context);
        this.listener = listener;
        this.prefs = ActionExecutor.prefs(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View root = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_action_picker, null);
        setContentView(root);

        // Expand fully by default
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) root.getParent());
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);

        bindViews(root);
        setupTabs();
        setupAppsTab();
        setupPresetsTab(root);
        setupCustomTab();

        loadAppsAsync();
    }

    private void bindViews(View root) {
        tabLayout   = root.findViewById(R.id.tabLayoutPicker);
        pageApps    = root.findViewById(R.id.pageApps);
        pagePresets = root.findViewById(R.id.pagePresets);
        pageCustom  = root.findViewById(R.id.pageCustom);

        // Apps
        rvApps       = root.findViewById(R.id.rvApps);
        etSearch     = root.findViewById(R.id.etAppSearch);
        tvAppsLoading = root.findViewById(R.id.tvAppsLoading);

        // Custom
        etAction    = root.findViewById(R.id.etCustomAction);
        etPackage   = root.findViewById(R.id.etCustomPackage);
        etComponent = root.findViewById(R.id.etCustomComponent);
        etData      = root.findViewById(R.id.etCustomData);

        root.findViewById(R.id.btnSaveCustom).setOnClickListener(v -> saveCustomIntent());
    }

    private void setupTabs() {
        showPage(TAB_APPS);
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { showPage(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showPage(int tab) {
        pageApps.setVisibility(tab == TAB_APPS    ? View.VISIBLE : View.GONE);
        pagePresets.setVisibility(tab == TAB_PRESETS ? View.VISIBLE : View.GONE);
        pageCustom.setVisibility(tab == TAB_CUSTOM  ? View.VISIBLE : View.GONE);
    }

    // ── Apps tab ─────────────────────────────────────────────────────────────

    private void setupAppsTab() {
        appAdapter = new AppAdapter(new ArrayList<>(), app -> {
            // Show this app's available intents
            showAppIntentPicker(app);
        });
        rvApps.setLayoutManager(new LinearLayoutManager(getContext()));
        rvApps.setAdapter(appAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterApps(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadAppsAsync() {
        tvAppsLoading.setVisibility(View.VISIBLE);
        rvApps.setVisibility(View.GONE);
        bgExecutor.execute(() -> {
            PackageManager pm = getContext().getPackageManager();
            // Use getInstalledPackages to bypass Android 11+ package visibility restrictions.
            // Then check each package for a launcher intent manually.
            List<android.content.pm.PackageInfo> packages;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packages = pm.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(0));
            } else {
                packages = pm.getInstalledPackages(0);
            }
            List<AppInfo> apps = new ArrayList<>();
            for (android.content.pm.PackageInfo pi : packages) {
                Intent launch = pm.getLaunchIntentForPackage(pi.packageName);
                if (launch == null) continue;
                android.content.pm.ResolveInfo ri;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    ri = pm.resolveActivity(launch, PackageManager.ResolveInfoFlags.of(0));
                } else {
                    ri = pm.resolveActivity(launch, 0);
                }
                AppInfo info = new AppInfo();
                info.pkg = pi.packageName;
                try {
                    info.label = pm.getApplicationLabel(
                            pm.getApplicationInfo(pi.packageName, 0)).toString();
                    info.icon  = pm.getApplicationIcon(pi.packageName);
                } catch (PackageManager.NameNotFoundException e) {
                    info.label = pi.packageName;
                    info.icon  = pm.getDefaultActivityIcon();
                }
                info.activity = (ri != null && ri.activityInfo != null)
                        ? ri.activityInfo.name : "";
                apps.add(info);
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
            allApps = apps;
            mainHandler.post(() -> {
                tvAppsLoading.setVisibility(View.GONE);
                rvApps.setVisibility(View.VISIBLE);
                appAdapter.setData(apps);
            });
        });
    }

    private void filterApps(String query) {
        if (query.isEmpty()) { appAdapter.setData(allApps); return; }
        String q = query.toLowerCase();
        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo a : allApps) {
            if (a.label.toLowerCase().contains(q) || a.pkg.toLowerCase().contains(q))
                filtered.add(a);
        }
        appAdapter.setData(filtered);
    }

    private void showAppIntentPicker(AppInfo app) {
        // Query all activities from this package dynamically
        bgExecutor.execute(() -> {
            PackageManager pm = getContext().getPackageManager();
            List<String> labels = new ArrayList<>();
            List<String> stored = new ArrayList<>();

            // Always first: launch main activity
            labels.add("▶ Open " + app.label + " (main)");
            stored.add("|" + app.pkg + "|" + app.activity + "|");

            // Get all activities from the package
            try {
                android.content.pm.PackageInfo pi;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pi = pm.getPackageInfo(app.pkg,
                            PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES));
                } else {
                    pi = pm.getPackageInfo(app.pkg, PackageManager.GET_ACTIVITIES);
                }
                if (pi.activities != null) {
                    for (android.content.pm.ActivityInfo ai : pi.activities) {
                        // Skip the main activity already added, skip non-exported
                        if (ai.name.equals(app.activity)) continue;
                        if (!ai.exported) continue;
                        // Get a friendly name: strip package prefix if possible
                        String shortName = ai.name.startsWith(app.pkg)
                                ? ai.name.substring(app.pkg.length()) // e.g. ".SomeActivity"
                                : ai.name;
                        // Try to get activity label
                        String actLabel = null;
                        try {
                            actLabel = ai.loadLabel(pm).toString();
                            // If label == app name, fall back to class name
                            if (actLabel.equals(app.label)) actLabel = null;
                        } catch (Exception ignored) {}
                        String display = (actLabel != null && !actLabel.isEmpty())
                                ? actLabel + "  (" + shortName + ")"
                                : shortName;
                        labels.add(display);
                        stored.add("|" + app.pkg + "|" + ai.name + "|");
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {}

            String[] labelArr = labels.toArray(new String[0]);
            String[] storedArr = stored.toArray(new String[0]);

            mainHandler.post(() -> new MaterialAlertDialogBuilder(getContext())
                    .setTitle(app.label)
                    .setItems(labelArr, (d, which) -> {
                        listener.onActionSelected(ActionConfig.ACTION_CUSTOM_INTENT,
                                storedArr[which], labelArr[which]);
                        dismiss();
                    })
                    .show());
        });
    }


    // ── Presets tab ───────────────────────────────────────────────────────────

    private static final String[][] PRESETS = {
        // {label, stored_value}  stored = "action|pkg|component|data"
        { "Open Dialer",             "android.intent.action.DIAL|||" },
        { "Open Camera",             "android.media.action.IMAGE_CAPTURE|||" },
        { "Open Settings",           "android.settings.SETTINGS|||" },
        { "WiFi Settings",           "android.settings.WIFI_SETTINGS|||" },
        { "Bluetooth Settings",      "android.settings.BLUETOOTH_SETTINGS|||" },
        { "Battery Settings",        "android.settings.BATTERY_SAVER_SETTINGS|||" },
        { "Sound Settings",          "android.settings.SOUND_SETTINGS|||" },
        { "Airplane Mode Settings",  "android.settings.AIRPLANE_MODE_SETTINGS|||" },
        { "Accessibility Settings",  "android.settings.ACCESSIBILITY_SETTINGS|||" },
        { "Take Screenshot",         "android.intent.action.SCREENSHOT|||" },
        { "Play / Pause Media",      "android.intent.action.MEDIA_BUTTON|||" },
        { "Next Track",              "android.intent.action.MEDIA_NEXT|||" },
        { "Previous Track",          "android.intent.action.MEDIA_PREVIOUS|||" },
        { "Expand Notification Panel", "com.android.systemui.statusbar.EXPAND_NOTIFICATIONS|com.android.systemui||" },
        { "Open Browser",            "android.intent.action.VIEW|||https://google.com" },
    };

    private void setupPresetsTab(View root) {
        RecyclerView rvPresets = root.findViewById(R.id.rvPresets);
        rvPresets.setLayoutManager(new LinearLayoutManager(getContext()));

        List<PresetItem> items = new ArrayList<>();
        for (String[] p : PRESETS) items.add(new PresetItem(p[0], p[1]));

        rvPresets.setAdapter(new PresetAdapter(items, item -> {
            listener.onActionSelected(ActionConfig.ACTION_CUSTOM_INTENT, item.stored, item.label);
            dismiss();
        }));
    }

    // ── Custom tab ────────────────────────────────────────────────────────────

    private void setupCustomTab() {
        // Populate fields from existing saved value if any
    }

    public void prefillCustom(String saved) {
        if (saved == null || saved.isEmpty()) return;
        String[] p = saved.split("\\|", -1);
        if (etAction    != null && p.length > 0) etAction.setText(p[0]);
        if (etPackage   != null && p.length > 1) etPackage.setText(p[1]);
        if (etComponent != null && p.length > 2) etComponent.setText(p[2]);
        if (etData      != null && p.length > 3) etData.setText(p[3]);
    }

    private void saveCustomIntent() {
        String action    = text(etAction);
        String pkg       = text(etPackage);
        String component = text(etComponent);
        String data      = text(etData);

        if (action.isEmpty() && pkg.isEmpty()) {
            etAction.setError("Enter an action or package");
            return;
        }

        String stored = action + "|" + pkg + "|" + component + "|" + data;
        String label  = !pkg.isEmpty() ? pkg : action;
        listener.onActionSelected(ActionConfig.ACTION_CUSTOM_INTENT, stored, "Custom: " + label);
        dismiss();
    }

    private String text(TextInputEditText et) {
        return et.getText() != null ? et.getText().toString().trim() : "";
    }

    // ── Data models ───────────────────────────────────────────────────────────

    static class AppInfo {
        String label, pkg, activity;
        Drawable icon;
    }

    static class PresetItem {
        String label, stored;
        PresetItem(String l, String s) { label = l; stored = s; }
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    interface OnItemClick<T> { void onClick(T item); }

    static class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        private List<AppInfo> data;
        private final OnItemClick<AppInfo> click;
        AppAdapter(List<AppInfo> data, OnItemClick<AppInfo> click) {
            this.data = data; this.click = click;
        }
        void setData(List<AppInfo> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_app_picker, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AppInfo app = data.get(pos);
            h.icon.setImageDrawable(app.icon);
            h.name.setText(app.label);
            h.pkg.setText(app.pkg);
            h.itemView.setOnClickListener(v -> click.onClick(app));
        }
        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView icon; TextView name, pkg;
            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.ivAppIcon);
                name = v.findViewById(R.id.tvAppName);
                pkg  = v.findViewById(R.id.tvAppPkg);
            }
        }
    }

    static class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.VH> {
        private final List<PresetItem> data;
        private final OnItemClick<PresetItem> click;
        PresetAdapter(List<PresetItem> d, OnItemClick<PresetItem> c) { data = d; click = c; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_preset_picker, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            h.label.setText(data.get(pos).label);
            h.itemView.setOnClickListener(v -> click.onClick(data.get(pos)));
        }
        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView label;
            VH(View v) { super(v); label = v.findViewById(R.id.tvPresetLabel); }
        }
    }
}
