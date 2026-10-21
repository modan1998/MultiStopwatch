package com.codex.multistopwatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String PREFS = "multi_stopwatch_settings";
    private static final String PREF_TIMERS = "timers_json";
    private static final String PREF_TRASH = "trash_json";
    private static final String PREF_LOGS = "runtime_logs_json";
    private static final String PREF_THEME_COLOR = "theme_color";
    private static final String PREF_DARK_MODE = "dark_mode";
    private static final String PREF_COMPACT_LIST = "compact_list";
    private static final String PREF_FONT_SCALE = "font_scale";
    private static final int MAX_STOPWATCHES = 7;
    private static final int REQUEST_EXPORT_LOGS = 1001;
    private static final long LOG_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long TRASH_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final long TIMER_REFRESH_INTERVAL_MILLIS = 16;
    private static final long TRASH_PRUNE_INTERVAL_MILLIS = 60 * 1000;
    private static final int[] STOPWATCH_COLORS = {
            Color.rgb(47, 125, 255),
            Color.rgb(31, 157, 99),
            Color.rgb(233, 120, 36),
            Color.rgb(123, 97, 255),
            Color.rgb(0, 150, 170),
            Color.rgb(220, 64, 112),
            Color.rgb(184, 137, 0)
    };
    private static final String[] STOPWATCH_COLOR_NAMES = {
            "蓝色", "绿色", "橙色", "紫色", "青色", "玫红色", "金色"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<StopwatchItem> stopwatches = new ArrayList<>();
    private final List<TrashEntry> trash = new ArrayList<>();
    private final List<LogEntry> runtimeLogs = new ArrayList<>();

    private SharedPreferences preferences;
    private StopwatchAdapter adapter;
    private LinearLayout windowContainer;
    private LinearLayout root;
    private TextView titleView;
    private TextView summaryView;
    private TextView emptyView;
    private ListView listView;
    private Button addButton;
    private Button themeButton;
    private Button exportButton;
    private Button trashButton;

    private int themeColor = Color.rgb(47, 125, 255);
    private boolean darkMode = false;
    private boolean compactList = false;
    private float fontScale = 1.0f;
    private int backgroundColor;
    private int surfaceColor;
    private int textColor;
    private int secondaryTextColor;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.refreshVisibleRows();
            }
            if (hasRunningStopwatches()) {
                handler.postDelayed(this, TIMER_REFRESH_INTERVAL_MILLIS);
            }
        }
    };

    private final Runnable trashPruner = new Runnable() {
        @Override
        public void run() {
            if (pruneTrash()) {
                saveTrash();
                updateSummary();
            }
            handler.postDelayed(this, TRASH_PRUNE_INTERVAL_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPreferences();
        loadRuntimeLogs();
        loadStopwatches();
        loadTrash();
        addLog("应用", "启动", "");
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pruneTrash()) {
            saveTrash();
            updateSummary();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        handler.removeCallbacks(trashPruner);
        handler.postDelayed(trashPruner, TRASH_PRUNE_INTERVAL_MILLIS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(trashPruner);
        super.onPause();
        saveStopwatches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveStopwatches();
        saveTrash();
    }

    private void buildUi() {
        windowContainer = new LinearLayout(this);
        windowContainer.setOrientation(LinearLayout.VERTICAL);
        windowContainer.setBackgroundColor(Color.BLACK);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        windowContainer.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1
        ));
        setContentView(windowContainer);
        applySystemBarInsets(windowContainer);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleRow.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        titleView = new TextView(this);
        titleView.setText("多秒表计时器");
        titleView.setTextSize(scaled(24));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleBlock.addView(titleView);

        summaryView = new TextView(this);
        summaryView.setTextSize(scaled(13));
        titleBlock.addView(summaryView);

        themeButton = createButton("界面");
        titleRow.addView(themeButton, new LinearLayout.LayoutParams(dp(78), dp(42)));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        actionRowParams.setMargins(0, dp(12), 0, dp(8));
        header.addView(actionRow, actionRowParams);

        addButton = createButton("新建秒表");
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        addParams.setMargins(0, 0, dp(4), 0);
        actionRow.addView(addButton, addParams);

        exportButton = createButton("导出日志");
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        exportParams.setMargins(dp(4), 0, dp(4), 0);
        actionRow.addView(exportButton, exportParams);

        trashButton = createButton("回收站");
        LinearLayout.LayoutParams trashParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        trashParams.setMargins(dp(4), 0, 0, 0);
        actionRow.addView(trashButton, trashParams);

        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));

        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        listView.setDividerHeight(dp(8));
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(8));
        frame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        emptyView = new TextView(this);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setText("还没有秒表\n点“新建秒表”开始计时");
        emptyView.setTextSize(scaled(16));
        emptyView.setLineSpacing(dp(4), 1.0f);
        frame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        listView.setEmptyView(emptyView);

        adapter = new StopwatchAdapter();
        listView.setAdapter(adapter);

        addButton.setOnClickListener(v -> addStopwatch());
        exportButton.setOnClickListener(v -> exportRecentLogs());
        trashButton.setOnClickListener(v -> showTrashDialog());
        themeButton.setOnClickListener(v -> showThemeDialog());

        applyTheme();
        updateSummary();
    }

    private void applySystemBarInsets(View container) {
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().setSystemUiVisibility(0);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        container.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) root.getLayoutParams();
            params.leftMargin = left;
            params.topMargin = top;
            params.rightMargin = right;
            params.bottomMargin = bottom;
            root.setLayoutParams(params);
            return insets;
        });
        container.requestApplyInsets();
    }

    private Button createButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(scaled(14));
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private void addStopwatch() {
        if (stopwatches.size() >= MAX_STOPWATCHES) {
            Toast.makeText(this, "最多只能创建 7 个秒表", Toast.LENGTH_SHORT).show();
            return;
        }
        String defaultName = "秒表 " + (stopwatches.size() + 1);
        showNameDialog("新建秒表", defaultName, name -> {
            if (stopwatches.size() >= MAX_STOPWATCHES) {
                Toast.makeText(this, "最多只能创建 7 个秒表", Toast.LENGTH_SHORT).show();
                return;
            }
            StopwatchItem item = new StopwatchItem(
                    UUID.randomUUID().toString(), name.trim(), firstAvailableColor(null)
            );
            stopwatches.add(0, item);
            addLog(item.name, "创建", "");
            saveStopwatches();
            adapter.notifyDataSetChanged();
            updateSummary();
        });
    }

    private void renameStopwatch(StopwatchItem item) {
        showNameDialog("修改名称", item.name, name -> {
            String oldName = item.name;
            item.name = name.trim();
            if (!oldName.equals(item.name)) {
                addLog(item.name, "改名", oldName + " → " + item.name);
            }
            saveStopwatches();
            adapter.notifyDataSetChanged();
        });
    }

    private void showNameDialog(String title, String initialValue, NameCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(initialValue);
        input.setSelectAllOnFocus(true);
        int pad = dp(18);
        input.setPadding(pad, dp(8), pad, dp(8));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onName(value);
                })
                .show();
    }

    private void deleteStopwatch(StopwatchItem item) {
        new AlertDialog.Builder(this)
                .setTitle("移入回收站")
                .setMessage("“" + item.name + "”会在回收站保留 7 天，期间可以恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移入回收站", (dialog, which) -> {
                    if (item.running) {
                        item.accumulatedMillis = item.elapsedMillis(now());
                        item.running = false;
                        item.startedAtMillis = 0;
                    }
                    stopwatches.remove(item);
                    trash.add(0, new TrashEntry(item, now()));
                    addLog(item.name, "移入回收站", "保留 7 天");
                    saveStopwatches();
                    saveTrash();
                    adapter.notifyDataSetChanged();
                    updateSummary();
                })
                .show();
    }

    private void resetStopwatch(StopwatchItem item) {
        new AlertDialog.Builder(this)
                .setTitle("确认重置")
                .setMessage("重置“" + item.name + "”的计时，并清除全部计次记录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> confirmResetStopwatch(item))
                .show();
    }

    private void confirmResetStopwatch(StopwatchItem item) {
        long previousElapsed = item.elapsedMillis(now());
        int previousLaps = item.laps.size();
        item.accumulatedMillis = 0;
        item.startedAtMillis = item.running ? now() : 0;
        item.laps.clear();
        addLog(item.name, "重置", "之前计时 " + formatDuration(previousElapsed)
                + "，清除 " + previousLaps + " 条计次记录");
        saveStopwatches();
        adapter.notifyDataSetChanged();
    }

    private void toggleStopwatch(StopwatchItem item) {
        long now = now();
        String action;
        if (item.running) {
            item.accumulatedMillis = item.elapsedMillis(now);
            item.startedAtMillis = 0;
            item.running = false;
            action = "暂停";
        } else {
            action = item.elapsedMillis(now) > 0 ? "继续" : "开始";
            item.startedAtMillis = now;
            item.running = true;
        }
        addLog(item.name, action, "计时 " + formatDuration(item.elapsedMillis(now)));
        saveStopwatches();
        adapter.notifyDataSetChanged();
        updateSummary();
        handler.removeCallbacks(ticker);
        if (hasRunningStopwatches()) {
            handler.post(ticker);
        }
    }

    private boolean hasRunningStopwatches() {
        for (StopwatchItem item : stopwatches) {
            if (item.running) {
                return true;
            }
        }
        return false;
    }

    private void addLap(StopwatchItem item) {
        long elapsed = item.elapsedMillis(now());
        if (elapsed <= 0) {
            return;
        }
        long previousTotal = item.laps.isEmpty() ? 0 : item.laps.get(item.laps.size() - 1).totalMillis;
        Lap lap = new Lap(item.laps.size() + 1, elapsed, elapsed - previousTotal);
        item.laps.add(lap);
        addLog(item.name, "计次", "第 " + lap.number + " 次；总计 "
                + formatDuration(lap.totalMillis) + "；本圈 " + formatDuration(lap.splitMillis));
        saveStopwatches();
        adapter.notifyDataSetChanged();
    }

    private void showLapDialog(StopwatchItem item) {
        StringBuilder builder = new StringBuilder();
        if (item.laps.isEmpty()) {
            builder.append("还没有计次记录。运行秒表时点“计次”即可记录圈速。");
        } else {
            for (int i = item.laps.size() - 1; i >= 0; i--) {
                Lap lap = item.laps.get(i);
                builder.append("第 ").append(lap.number).append(" 次  ")
                        .append(formatDuration(lap.totalMillis))
                        .append("  / 本圈 ")
                        .append(formatDuration(lap.splitMillis));
                if (i > 0) {
                    builder.append("\n");
                }
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(item.name + " · 圈速记录")
                .setMessage(builder.toString())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showThemeDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), 0);

        TextView colorLabel = dialogLabel("主题色");
        panel.addView(colorLabel);

        EditText colorInput = new EditText(this);
        colorInput.setSingleLine(true);
        colorInput.setInputType(InputType.TYPE_CLASS_TEXT);
        colorInput.setText(hex(themeColor));
        colorInput.setSelectAllOnFocus(true);
        panel.addView(colorInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout presetRow1 = new LinearLayout(this);
        presetRow1.setGravity(Gravity.CENTER);
        panel.addView(presetRow1, rowParams());
        addPresetButton(presetRow1, "蓝", "#2F7DFF", colorInput);
        addPresetButton(presetRow1, "绿", "#1F9D63", colorInput);
        addPresetButton(presetRow1, "橙", "#E97824", colorInput);
        addPresetButton(presetRow1, "紫", "#7B61FF", colorInput);

        CheckBox darkBox = new CheckBox(this);
        darkBox.setText("深色模式");
        darkBox.setChecked(darkMode);
        panel.addView(darkBox);

        CheckBox compactBox = new CheckBox(this);
        compactBox.setText("紧凑列表样式");
        compactBox.setChecked(compactList);
        panel.addView(compactBox);

        TextView fontLabel = dialogLabel("字体大小：" + Math.round(fontScale * 100) + "%");
        panel.addView(fontLabel);
        SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax(60);
        fontSeek.setProgress(Math.round((fontScale - 0.8f) * 100));
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.8f + progress / 100f;
                fontLabel.setText("字体大小：" + Math.round(value * 100) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        panel.addView(fontSeek);

        new AlertDialog.Builder(this)
                .setTitle("自定义界面")
                .setView(panel)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (dialog, which) -> {
                    try {
                        themeColor = parseColor(colorInput.getText().toString());
                        darkMode = darkBox.isChecked();
                        compactList = compactBox.isChecked();
                        fontScale = 0.8f + fontSeek.getProgress() / 100f;
                        preferences.edit()
                                .putInt(PREF_THEME_COLOR, themeColor)
                                .putBoolean(PREF_DARK_MODE, darkMode)
                                .putBoolean(PREF_COMPACT_LIST, compactList)
                                .putFloat(PREF_FONT_SCALE, fontScale)
                                .apply();
                        rebuildUi();
                    } catch (IllegalArgumentException ex) {
                        Toast.makeText(this, "颜色格式不正确，例如 #2F7DFF", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private TextView dialogLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(10), 0, 0);
        return label;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(8), 0, dp(4));
        return params;
    }

    private void addPresetButton(LinearLayout parent, String label, String color, EditText target) {
        Button button = createButton(label);
        int parsed = Color.parseColor(color);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(parsed);
        drawable.setCornerRadius(dp(6));
        button.setBackground(drawable);
        button.setTextColor(readableTextColor(parsed));
        button.setOnClickListener(v -> target.setText(color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(button, params);
    }

    private void rebuildUi() {
        buildUi();
        adapter.notifyDataSetChanged();
    }

    private void applyTheme() {
        backgroundColor = darkMode ? Color.rgb(16, 20, 27) : Color.rgb(246, 248, 252);
        surfaceColor = darkMode ? Color.rgb(28, 34, 44) : Color.WHITE;
        textColor = darkMode ? Color.rgb(238, 242, 248) : Color.rgb(20, 28, 39);
        secondaryTextColor = darkMode ? Color.rgb(168, 178, 192) : Color.rgb(95, 109, 128);

        root.setBackgroundColor(backgroundColor);
        titleView.setTextColor(textColor);
        summaryView.setTextColor(secondaryTextColor);
        emptyView.setTextColor(secondaryTextColor);
        listView.setDivider(new ColorDrawable(Color.TRANSPARENT));
        styleButton(addButton, themeColor, readableTextColor(themeColor));
        styleButton(exportButton, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
        styleButton(trashButton, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
        styleButton(themeButton, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
    }

    private void styleButton(Button button, int background, int foreground) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        drawable.setCornerRadius(dp(6));
        button.setBackground(drawable);
        button.setTextColor(foreground);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setBackgroundTintList(ColorStateList.valueOf(background));
        }
    }

    private void updateSummary() {
        int running = 0;
        for (StopwatchItem item : stopwatches) {
            if (item.running) {
                running++;
            }
        }
        if (summaryView != null) {
            String summary = stopwatches.size() + " / " + MAX_STOPWATCHES + " 个秒表 · "
                    + running + " 个正在运行";
            if (stopwatches.size() >= MAX_STOPWATCHES) {
                summary += " · 已达上限";
            }
            summaryView.setText(summary);
        }
        if (addButton != null) {
            boolean canAdd = stopwatches.size() < MAX_STOPWATCHES;
            addButton.setEnabled(canAdd);
            addButton.setText(canAdd ? "新建秒表" : "已达上限");
        }
        if (trashButton != null) {
            trashButton.setText("回收站 (" + trash.size() + ")");
        }
    }

    private void loadPreferences() {
        themeColor = preferences.getInt(PREF_THEME_COLOR, Color.rgb(47, 125, 255));
        darkMode = preferences.getBoolean(PREF_DARK_MODE, false);
        compactList = preferences.getBoolean(PREF_COMPACT_LIST, false);
        fontScale = preferences.getFloat(PREF_FONT_SCALE, 1.0f);
    }

    private void loadStopwatches() {
        stopwatches.clear();
        String raw = preferences.getString(PREF_TIMERS, null);
        if (raw == null || raw.trim().isEmpty()) {
            StopwatchItem initial = new StopwatchItem(UUID.randomUUID().toString(), "秒表 1", STOPWATCH_COLORS[0]);
            stopwatches.add(initial);
            addLog(initial.name, "创建", "首次启动自动创建");
            return;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                if (stopwatches.size() >= MAX_STOPWATCHES) {
                    break;
                }
                JSONObject object = array.getJSONObject(i);
                StopwatchItem item = parseStopwatch(object, "秒表 " + (i + 1));
                if (!isStopwatchColorAvailable(item.color, null)) {
                    item.color = firstAvailableColor(null);
                }
                stopwatches.add(item);
            }
        } catch (JSONException ex) {
            stopwatches.clear();
            stopwatches.add(new StopwatchItem(UUID.randomUUID().toString(), "秒表 1", STOPWATCH_COLORS[0]));
        }
    }

    private void saveStopwatches() {
        JSONArray array = new JSONArray();
        try {
            for (StopwatchItem item : stopwatches) {
                array.put(serializeStopwatch(item));
            }
        } catch (JSONException ignored) {
        }
        preferences.edit().putString(PREF_TIMERS, array.toString()).apply();
    }

    private JSONObject serializeStopwatch(StopwatchItem item) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", item.id);
        object.put("name", item.name);
        object.put("color", item.color);
        object.put("accumulatedMillis", item.accumulatedMillis);
        object.put("running", item.running);
        object.put("startedAtMillis", item.startedAtMillis);
        JSONArray laps = new JSONArray();
        for (Lap lap : item.laps) {
            JSONObject lapObject = new JSONObject();
            lapObject.put("number", lap.number);
            lapObject.put("totalMillis", lap.totalMillis);
            lapObject.put("splitMillis", lap.splitMillis);
            laps.put(lapObject);
        }
        object.put("laps", laps);
        return object;
    }

    private StopwatchItem parseStopwatch(JSONObject object, String defaultName) throws JSONException {
        StopwatchItem item = new StopwatchItem(
                object.optString("id", UUID.randomUUID().toString()),
                object.optString("name", defaultName),
                object.optInt("color", STOPWATCH_COLORS[0])
        );
        item.accumulatedMillis = Math.max(0, object.optLong("accumulatedMillis", 0));
        item.running = object.optBoolean("running", false);
        item.startedAtMillis = object.optLong("startedAtMillis", 0);
        JSONArray laps = object.optJSONArray("laps");
        if (laps != null) {
            for (int i = 0; i < laps.length(); i++) {
                JSONObject lapObject = laps.optJSONObject(i);
                if (lapObject != null) {
                    item.laps.add(new Lap(
                            lapObject.optInt("number", i + 1),
                            Math.max(0, lapObject.optLong("totalMillis", 0)),
                            Math.max(0, lapObject.optLong("splitMillis", 0))
                    ));
                }
            }
        }
        return item;
    }

    private void loadTrash() {
        trash.clear();
        String raw = preferences.getString(PREF_TRASH, null);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    JSONObject stopwatchObject = object == null ? null : object.optJSONObject("stopwatch");
                    if (object == null || stopwatchObject == null) {
                        continue;
                    }
                    StopwatchItem item = parseStopwatch(stopwatchObject, "已删除秒表");
                    trash.add(new TrashEntry(item, object.optLong("deletedAt", now())));
                }
            } catch (JSONException ignored) {
                trash.clear();
            }
        }
        if (pruneTrash()) {
            saveTrash();
        }
    }

    private void saveTrash() {
        JSONArray array = new JSONArray();
        try {
            for (TrashEntry entry : trash) {
                JSONObject object = new JSONObject();
                object.put("deletedAt", entry.deletedAtMillis);
                object.put("stopwatch", serializeStopwatch(entry.stopwatch));
                array.put(object);
            }
        } catch (JSONException ignored) {
        }
        preferences.edit().putString(PREF_TRASH, array.toString()).apply();
    }

    private boolean pruneTrash() {
        long cutoff = now() - TRASH_RETENTION_MILLIS;
        List<String> expiredNames = new ArrayList<>();
        for (int i = trash.size() - 1; i >= 0; i--) {
            TrashEntry entry = trash.get(i);
            if (entry.deletedAtMillis < cutoff) {
                expiredNames.add(entry.stopwatch.name);
                trash.remove(i);
            }
        }
        if (!expiredNames.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String name : expiredNames) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(name);
            }
            addLog("回收站", "自动清理", names + "（超过 7 天）");
            return true;
        }
        return false;
    }

    private void showTrashDialog() {
        if (pruneTrash()) {
            saveTrash();
            updateSummary();
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), 0);

        ScrollView scrollView = new ScrollView(this);
        panel.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
        ));
        LinearLayout entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(entries);

        Button clearButton = createButton("清空回收站");
        panel.addView(clearButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("回收站 · 保留 7 天")
                .setView(panel)
                .setPositiveButton("关闭", null)
                .create();
        clearButton.setOnClickListener(v -> confirmClearTrash(entries, clearButton));
        renderTrashEntries(entries, clearButton);
        dialog.show();
        styleButton(clearButton, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
    }

    private void renderTrashEntries(LinearLayout entries, Button clearButton) {
        entries.removeAllViews();
        if (trash.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("回收站是空的");
            empty.setGravity(Gravity.CENTER);
            empty.setTextColor(secondaryTextColor);
            empty.setPadding(0, dp(36), 0, dp(36));
            entries.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }

        SimpleDateFormat deletedDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (TrashEntry entry : new ArrayList<>(trash)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(8), dp(10), dp(8));
            GradientDrawable cardBackground = new GradientDrawable();
            cardBackground.setColor(surfaceColor);
            cardBackground.setCornerRadius(dp(8));
            cardBackground.setStroke(dp(2), entry.stopwatch.color);
            card.setBackground(cardBackground);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            cardParams.setMargins(0, 0, 0, dp(8));
            entries.addView(card, cardParams);

            TextView name = new TextView(this);
            name.setText(entry.stopwatch.name);
            name.setTextColor(entry.stopwatch.color);
            name.setTextSize(scaled(16));
            name.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(name);

            long hoursRemaining = Math.max(0, (entry.deletedAtMillis + TRASH_RETENTION_MILLIS - now() + 3599999) / 3600000);
            long daysRemaining = hoursRemaining / 24;
            long extraHours = hoursRemaining % 24;
            String expiry = daysRemaining > 0
                    ? daysRemaining + " 天 " + extraHours + " 小时后自动清理"
                    : hoursRemaining + " 小时内自动清理";
            TextView info = new TextView(this);
            info.setText("删除于 " + deletedDateFormat.format(new Date(entry.deletedAtMillis))
                    + " · " + expiry + "\n计时 " + formatDuration(entry.stopwatch.accumulatedMillis)
                    + " · " + entry.stopwatch.laps.size() + " 条计次记录");
            info.setTextColor(secondaryTextColor);
            info.setTextSize(scaled(12));
            card.addView(info);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            );
            actionsParams.setMargins(0, dp(4), 0, 0);
            card.addView(actions, actionsParams);

            Button restoreButton = createButton("恢复");
            Button deleteButton = createButton("永久删除");
            actions.addView(restoreButton, new LinearLayout.LayoutParams(0, dp(44), 1));
            LinearLayout.LayoutParams permanentDeleteParams = new LinearLayout.LayoutParams(0, dp(44), 1);
            permanentDeleteParams.setMargins(dp(6), 0, 0, 0);
            actions.addView(deleteButton, permanentDeleteParams);
            styleButton(restoreButton, entry.stopwatch.color, readableTextColor(entry.stopwatch.color));
            styleButton(deleteButton, Color.rgb(153, 58, 65), Color.WHITE);

            restoreButton.setOnClickListener(v -> restoreFromTrash(entry, entries, clearButton));
            deleteButton.setOnClickListener(v -> confirmPermanentDelete(entry, entries, clearButton));
        }
        clearButton.setEnabled(!trash.isEmpty());
    }

    private void restoreFromTrash(TrashEntry entry, LinearLayout entries, Button clearButton) {
        if (!trash.contains(entry)) {
            return;
        }
        if (stopwatches.size() >= MAX_STOPWATCHES) {
            Toast.makeText(this, "秒表已达 7 个上限，请先删除一个再恢复", Toast.LENGTH_LONG).show();
            return;
        }
        trash.remove(entry);
        StopwatchItem item = entry.stopwatch;
        if (!isStopwatchColorAvailable(item.color, null)) {
            item.color = firstAvailableColor(null);
        }
        stopwatches.add(0, item);
        addLog(item.name, "从回收站恢复", "");
        saveStopwatches();
        saveTrash();
        adapter.notifyDataSetChanged();
        updateSummary();
        renderTrashEntries(entries, clearButton);
    }

    private void confirmPermanentDelete(TrashEntry entry, LinearLayout entries, Button clearButton) {
        new AlertDialog.Builder(this)
                .setTitle("永久删除秒表")
                .setMessage("永久删除“" + entry.stopwatch.name + "”？此操作无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("永久删除", (dialog, which) -> {
                    if (trash.remove(entry)) {
                        addLog(entry.stopwatch.name, "永久删除", "从回收站删除");
                        saveTrash();
                        updateSummary();
                        renderTrashEntries(entries, clearButton);
                    }
                })
                .show();
    }

    private void confirmClearTrash(LinearLayout entries, Button clearButton) {
        if (trash.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空回收站")
                .setMessage("永久删除回收站中的全部 " + trash.size() + " 个秒表？此操作无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    int count = trash.size();
                    trash.clear();
                    addLog("回收站", "手动清空", "永久删除 " + count + " 个秒表");
                    saveTrash();
                    updateSummary();
                    renderTrashEntries(entries, clearButton);
                })
                .show();
    }

    private String lapPreview(StopwatchItem item) {
        if (item.laps.isEmpty()) {
            return "暂无圈速记录";
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, item.laps.size() - 3);
        for (int i = item.laps.size() - 1; i >= start; i--) {
            Lap lap = item.laps.get(i);
            builder.append("第 ").append(lap.number).append(" 次  ")
                    .append(formatDuration(lap.totalMillis))
                    .append("  本圈 ")
                    .append(formatDuration(lap.splitMillis));
            if (i > start) {
                builder.append("\n");
            }
        }
        if (item.laps.size() > 3) {
            builder.append("\n点这里查看全部 ").append(item.laps.size()).append(" 条");
        }
        return builder.toString();
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private String formatDuration(long millis) {
        long safe = Math.max(0, millis);
        long totalSeconds = safe / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        long hundredths = (safe % 1000) / 10;
        StringBuilder formatted = new StringBuilder(16);
        appendTwoDigits(formatted, hours);
        formatted.append(':');
        appendTwoDigits(formatted, minutes);
        formatted.append(':');
        appendTwoDigits(formatted, seconds);
        formatted.append('.');
        appendTwoDigits(formatted, hundredths);
        return formatted.toString();
    }

    private void appendTwoDigits(StringBuilder target, long value) {
        if (value >= 0 && value < 10) {
            target.append('0');
        }
        target.append(value);
    }

    private int parseColor(String raw) {
        String value = raw.trim();
        if (!value.startsWith("#")) {
            value = "#" + value;
        }
        return Color.parseColor(value);
    }

    private String hex(int color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }

    private int readableTextColor(int color) {
        int luminance = (int) (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color));
        return luminance > 150 ? Color.rgb(18, 24, 34) : Color.WHITE;
    }

    private float scaled(float sp) {
        return sp * fontScale;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int firstAvailableColor(StopwatchItem except) {
        for (int color : STOPWATCH_COLORS) {
            if (isStopwatchColorAvailable(color, except)) {
                return color;
            }
        }
        return STOPWATCH_COLORS[0];
    }

    private boolean isStopwatchColorAvailable(int color, StopwatchItem except) {
        boolean isPaletteColor = false;
        for (int paletteColor : STOPWATCH_COLORS) {
            if (paletteColor == color) {
                isPaletteColor = true;
                break;
            }
        }
        if (!isPaletteColor) {
            return false;
        }
        for (StopwatchItem item : stopwatches) {
            if (item != except && item.color == color) {
                return false;
            }
        }
        return true;
    }

    private void chooseStopwatchColor(StopwatchItem item) {
        List<Integer> availableColors = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < STOPWATCH_COLORS.length; i++) {
            int color = STOPWATCH_COLORS[i];
            if (isStopwatchColorAvailable(color, item)) {
                if (color == item.color) {
                    selected = availableColors.size();
                }
                availableColors.add(color);
                labels.add(STOPWATCH_COLOR_NAMES[i]);
            }
        }
        final int[] selectedIndex = {selected};
        new AlertDialog.Builder(this)
                .setTitle("选择“" + item.name + "”的颜色")
                .setSingleChoiceItems(labels.toArray(new String[0]), selected,
                        (dialog, which) -> selectedIndex[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    int color = availableColors.get(selectedIndex[0]);
                    if (item.color != color) {
                        item.color = color;
                        addLog(item.name, "更改颜色", hex(color));
                        saveStopwatches();
                        adapter.notifyDataSetChanged();
                    }
                })
                .show();
    }

    private void loadRuntimeLogs() {
        runtimeLogs.clear();
        String raw = preferences.getString(PREF_LOGS, null);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    runtimeLogs.add(new LogEntry(
                            object.optLong("time", 0),
                            object.optString("stopwatch", ""),
                            object.optString("action", ""),
                            object.optString("details", "")
                    ));
                }
            } catch (JSONException ignored) {
                runtimeLogs.clear();
            }
        }
        pruneLogs();
    }

    private void addLog(String stopwatchName, String action, String details) {
        runtimeLogs.add(new LogEntry(now(), stopwatchName, action, details));
        pruneLogs();
        JSONArray array = new JSONArray();
        try {
            for (LogEntry entry : runtimeLogs) {
                JSONObject object = new JSONObject();
                object.put("time", entry.timestampMillis);
                object.put("stopwatch", entry.stopwatchName);
                object.put("action", entry.action);
                object.put("details", entry.details);
                array.put(object);
            }
        } catch (JSONException ignored) {
        }
        preferences.edit().putString(PREF_LOGS, array.toString()).apply();
    }

    private void pruneLogs() {
        long cutoff = now() - LOG_RETENTION_MILLIS;
        for (int i = runtimeLogs.size() - 1; i >= 0; i--) {
            if (runtimeLogs.get(i).timestampMillis < cutoff) {
                runtimeLogs.remove(i);
            }
        }
    }

    private void exportRecentLogs() {
        addLog("应用", "导出日志", "请求导出最近 30 天运行日志");
        SimpleDateFormat fileDate = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "秒表运行日志_" + fileDate.format(new Date()) + ".csv");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_LOGS);
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "未找到可用的文件保存应用", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_LOGS || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            Toast.makeText(this, "无法创建日志文件", Toast.LENGTH_LONG).show();
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("Unable to open selected file");
            }
            output.write("\uFEFF".getBytes(StandardCharsets.UTF_8));
            output.write(buildRecentLogsCsv(now()).getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "最近一个月的运行日志已导出", Toast.LENGTH_LONG).show();
        } catch (IOException ex) {
            Toast.makeText(this, "导出失败，请重新选择保存位置", Toast.LENGTH_LONG).show();
        }
    }

    private String buildRecentLogsCsv(long endTime) {
        long cutoff = endTime - LOG_RETENTION_MILLIS;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder csv = new StringBuilder("时间,秒表,操作,详情\r\n");
        for (LogEntry entry : runtimeLogs) {
            if (entry.timestampMillis < cutoff || entry.timestampMillis > endTime) {
                continue;
            }
            csv.append(csvField(dateFormat.format(new Date(entry.timestampMillis)))).append(',')
                    .append(csvField(entry.stopwatchName)).append(',')
                    .append(csvField(entry.action)).append(',')
                    .append(csvField(entry.details)).append("\r\n");
        }
        return csv.toString();
    }

    private String csvField(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private interface NameCallback {
        void onName(String name);
    }

    private static class StopwatchItem {
        final String id;
        String name;
        int color;
        long accumulatedMillis = 0;
        boolean running = false;
        long startedAtMillis = 0;
        final List<Lap> laps = new ArrayList<>();

        StopwatchItem(String id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }

        long elapsedMillis(long now) {
            if (!running) {
                return accumulatedMillis;
            }
            return accumulatedMillis + Math.max(0, now - startedAtMillis);
        }
    }

    private static class LogEntry {
        final long timestampMillis;
        final String stopwatchName;
        final String action;
        final String details;

        LogEntry(long timestampMillis, String stopwatchName, String action, String details) {
            this.timestampMillis = timestampMillis;
            this.stopwatchName = stopwatchName;
            this.action = action;
            this.details = details;
        }
    }

    private static class TrashEntry {
        final StopwatchItem stopwatch;
        final long deletedAtMillis;

        TrashEntry(StopwatchItem stopwatch, long deletedAtMillis) {
            this.stopwatch = stopwatch;
            this.deletedAtMillis = deletedAtMillis;
        }
    }

    private static class Lap {
        final int number;
        final long totalMillis;
        final long splitMillis;

        Lap(int number, long totalMillis, long splitMillis) {
            this.number = number;
            this.totalMillis = totalMillis;
            this.splitMillis = splitMillis;
        }
    }

    private class StopwatchAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return stopwatches.size();
        }

        @Override
        public StopwatchItem getItem(int position) {
            return stopwatches.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);

                TextView name = new TextView(MainActivity.this);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(false);
                row.addView(name);

                TextView time = new TextView(MainActivity.this);
                time.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                time.setEms(11);
                row.addView(time);

                TextView status = new TextView(MainActivity.this);
                row.addView(status);

                LinearLayout controls1 = new LinearLayout(MainActivity.this);
                controls1.setGravity(Gravity.CENTER);
                row.addView(controls1);

                Button toggle = createButton("");
                Button lap = createButton("计次");
                Button reset = createButton("重置");
                controls1.addView(toggle, new LinearLayout.LayoutParams(0, dp(48), 1));
                LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, dp(48), 1);
                mid.setMargins(dp(6), 0, dp(6), 0);
                controls1.addView(lap, mid);
                controls1.addView(reset, new LinearLayout.LayoutParams(0, dp(48), 1));

                LinearLayout controls2 = new LinearLayout(MainActivity.this);
                controls2.setGravity(Gravity.CENTER);
                row.addView(controls2);

                Button rename = createButton("改名");
                Button delete = createButton("删除");
                Button color = createButton("颜色");
                controls2.addView(color, new LinearLayout.LayoutParams(0, dp(48), 1));
                LinearLayout.LayoutParams renameParams = new LinearLayout.LayoutParams(0, dp(48), 1);
                renameParams.setMargins(dp(6), 0, dp(6), 0);
                controls2.addView(rename, renameParams);
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(48), 1);
                controls2.addView(delete, deleteParams);

                TextView laps = new TextView(MainActivity.this);
                laps.setSingleLine(false);
                row.addView(laps);

                holder = new RowHolder(row, name, time, status, toggle, lap, reset, color, rename, delete, laps);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            StopwatchItem item = getItem(position);
            holder.item = item;
            long elapsed = item.elapsedMillis(now());

            holder.name.setText(item.name);
            holder.name.setTextColor(item.color);
            holder.name.setTextSize(scaled(17));

            holder.time.setText(formatDuration(elapsed));
            holder.time.setTextColor(item.color);
            holder.time.setTextSize(scaled(30));

            holder.status.setText(item.running ? "运行中" : "已暂停");
            holder.status.setTextColor(item.running ? item.color : secondaryTextColor);
            holder.status.setTextSize(scaled(13));

            holder.toggle.setText(item.running ? "暂停" : (elapsed > 0 ? "继续" : "开始"));
            holder.lap.setEnabled(elapsed > 0);
            holder.laps.setText(lapPreview(item));
            holder.laps.setTextSize(scaled(12));
            holder.laps.setTextColor(secondaryTextColor);
            holder.laps.setPadding(0, compactList ? dp(6) : dp(10), 0, 0);

            int vertical = compactList ? dp(10) : dp(14);
            holder.row.setPadding(dp(12), vertical, dp(12), vertical);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(surfaceColor);
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(2), item.color);
            holder.row.setBackground(bg);

            styleButton(holder.toggle, item.running ? Color.rgb(216, 70, 70) : item.color, Color.WHITE);
            styleButton(holder.lap, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
            styleButton(holder.reset, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
            styleButton(holder.color, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
            styleButton(holder.rename, darkMode ? Color.rgb(53, 61, 76) : Color.rgb(225, 232, 244), textColor);
            styleButton(holder.delete, Color.rgb(153, 58, 65), Color.WHITE);

            holder.toggle.setOnClickListener(v -> toggleStopwatch(item));
            holder.lap.setOnClickListener(v -> addLap(item));
            holder.reset.setOnClickListener(v -> resetStopwatch(item));
            holder.color.setOnClickListener(v -> chooseStopwatchColor(item));
            holder.rename.setOnClickListener(v -> renameStopwatch(item));
            holder.delete.setOnClickListener(v -> deleteStopwatch(item));
            holder.laps.setOnClickListener(v -> showLapDialog(item));

            return convertView;
        }

        void refreshVisibleRows() {
            if (listView == null) {
                return;
            }
            long currentTime = now();
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                Object tag = child.getTag();
                if (!(tag instanceof RowHolder)) {
                    continue;
                }
                RowHolder holder = (RowHolder) tag;
                StopwatchItem item = holder.item;
                if (item == null || !item.running) {
                    continue;
                }
                long elapsed = item.elapsedMillis(currentTime);
                String displayedTime = formatDuration(elapsed);
                if (!displayedTime.contentEquals(holder.time.getText())) {
                    holder.time.setText(displayedTime);
                }
            }
        }
    }

    private static class RowHolder {
        final LinearLayout row;
        final TextView name;
        final TextView time;
        final TextView status;
        final Button toggle;
        final Button lap;
        final Button reset;
        final Button color;
        final Button rename;
        final Button delete;
        final TextView laps;
        StopwatchItem item;

        RowHolder(
                LinearLayout row,
                TextView name,
                TextView time,
                TextView status,
                Button toggle,
                Button lap,
                Button reset,
                Button color,
                Button rename,
                Button delete,
                TextView laps
        ) {
            this.row = row;
            this.name = name;
            this.time = time;
            this.status = status;
            this.toggle = toggle;
            this.lap = lap;
            this.reset = reset;
            this.color = color;
            this.rename = rename;
            this.delete = delete;
            this.laps = laps;
        }
    }
}
