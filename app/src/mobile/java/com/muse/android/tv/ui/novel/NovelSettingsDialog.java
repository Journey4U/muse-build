package com.muse.android.tv.ui.novel;

import android.content.SharedPreferences;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.R;
import com.muse.android.tv.databinding.DialogNovelSettingsBinding;
import com.muse.android.tv.ui.dialog.BaseBottomSheetDialog;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 小说「主题」/「设置」面板。
 *
 * 同一份布局（dialog_novel_settings.xml）按模式展示两组互不相同的服务内容：
 * <ul>
 *     <li>{@link #MODE_THEME}：外观 —— 预设主题（背景色/文字色）、字体（加粗/字体族）</li>
 *     <li>{@link #MODE_SETTINGS}：行为 —— 字号、排版（行距/字间距/段间距/边距）、显示（亮度）、行为（屏幕常亮/自动滚动/翻页模式）</li>
 * </ul>
 * 所有设置项仍落在同一个 novel_settings SharedPreferences 中，键名与拆分前完全一致，
 * 阅读页通过 {@link OnSettingsChangedListener} 回调实时生效。
 */
public class NovelSettingsDialog extends BaseBottomSheetDialog {

    public static final String PREFS = "novel_settings";

    /** 主题入口：只呈现外观项。 */
    public static final int MODE_THEME = 0;
    /** 设置入口：只呈现行为项。 */
    public static final int MODE_SETTINGS = 1;

    /** 预设主题：[背景色, 文字色] */
    public static final int[][] THEME_COLORS = {
            {0xFFF5F0E1, 0xFF333333}, // 羊皮纸
            {0xFFFFFFFF, 0xFF333333}, // 纯白
            {0xFFE8F5E9, 0xFF2E7D32}, // 护眼绿
            {0xFFFCE4EC, 0xFF880E4F}, // 淡粉
            {0xFF1B2A1B, 0xFFA5D6A7}, // 墨绿
            {0xFF1A237E, 0xFF9FA8DA}, // 深蓝
            {0xFF2A1B3D, 0xFFCE93D8}, // 深紫
            {0xFF000000, 0xFFCCCCCC}, // 纯黑
    };

    public static final String[] THEME_NAMES = {"羊皮纸", "纯白", "护眼绿", "淡粉", "墨绿", "深蓝", "深紫", "纯黑"};

    private static final String[] FONT_NAMES = {"系统默认", "衬线体", "等宽字体", "黑体风格"};

    public interface OnSettingsChangedListener {

        void onFontSizeChanged(int size);

        void onBoldChanged(boolean bold);

        void onFontChanged(int index);

        void onLineSpacingChanged(float spacing);

        void onLayoutChanged();

        void onThemeChanged(int bgColor, int textColor);

        void onAutoScrollChanged(boolean enable);
    }

    private DialogNovelSettingsBinding binding;
    private OnSettingsChangedListener listener;
    private SharedPreferences prefs;
    private int mode = MODE_SETTINGS;
    private final TextView[] tabs = new TextView[4];
    private final View[] groups = new View[4];
    private String[] labels = new String[0];

    /** 兼容旧调用：默认展示「设置」入口。 */
    public static NovelSettingsDialog create(OnSettingsChangedListener listener) {
        return create(listener, MODE_SETTINGS);
    }

    public static NovelSettingsDialog create(OnSettingsChangedListener listener, int mode) {
        NovelSettingsDialog dialog = new NovelSettingsDialog();
        dialog.listener = listener;
        dialog.mode = mode;
        return dialog;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogNovelSettingsBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        prefs = requireContext().getSharedPreferences(PREFS, 0);
        binding.dialogTitle.setText(mode == MODE_THEME ? "主题" : "阅读设置");
        initTabs();
        showGroup(0);
    }

    /**
     * #60：高度贴合内容。基类 setHeight 在 maxHeight<=0 时不写死 sheet 高度，
     * sheet 随内容自适应（此前写死 72% 屏高，内容不足时下半截大片留白）；
     * 内容超高时由布局内 ScrollView 内部滚动、窗口封顶，不会被截断。
     */
    @Override
    protected int getMaxHeight() {
        return 0;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** 按模式决定 Tab 文案与可见性：主题入口 2 个（主题/字体），设置入口 4 个（字号/排版/显示/行为）。 */
    private void initTabs() {
        tabs[0] = binding.tabOne;
        tabs[1] = binding.tabTwo;
        tabs[2] = binding.tabThree;
        tabs[3] = binding.tabFour;
        if (mode == MODE_THEME) {
            groups[0] = binding.groupTheme;
            groups[1] = binding.groupFont;
            labels = new String[]{"主题", "字体"};
        } else {
            groups[0] = binding.groupSize;
            groups[1] = binding.groupLayout;
            groups[2] = binding.groupDisplay;
            groups[3] = binding.groupBehavior;
            labels = new String[]{"字号", "排版", "显示", "行为"};
        }
        for (int i = 0; i < tabs.length; i++) {
            final int position = i;
            boolean used = i < labels.length;
            tabs[i].setText(used ? labels[i] : "");
            tabs[i].setVisibility(used ? View.VISIBLE : View.GONE);
            if (used) tabs[i].setOnClickListener(v -> showGroup(position));
        }
    }

    /** 切换分组：只显示当前分组，其余隐藏，两组之间互不影响。 */
    private void showGroup(int position) {
        if (position < 0 || position >= labels.length) position = 0;
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == position;
            tabs[i].setSelected(selected);
            if (groups[i] != null) groups[i].setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        if (mode == MODE_THEME) {
            if (position == 0) bindThemeGroup();
            else bindFontGroup();
        } else if (position == 0) {
            bindSizeGroup();
        } else if (position == 1) {
            bindLayoutGroup();
        } else if (position == 2) {
            bindDisplayGroup();
        } else {
            bindBehaviorGroup();
        }
    }

    // ===== 主题入口：外观 =====

    /** 主题：8 套预设配色网格，只改背景色/文字色。 */
    private void bindThemeGroup() {
        int current = prefs.getInt("theme_index", 0);
        // #60 自查顺手修：item 定宽 80dp，固定 4 列在 320dp 窄屏会溢出裁切；
        // 按可用宽度（屏宽 − 内容区左右 padding 各 16dp）反推列数，clamp 到 2..4。
        float density = getResources().getDisplayMetrics().density;
        int availDp = (int) (getResources().getDisplayMetrics().widthPixels / density) - 32;
        binding.themeGrid.setColumnCount(Math.max(2, Math.min(4, availDp / 82)));
        binding.themeGrid.removeAllViews();
        for (int i = 0; i < THEME_COLORS.length; i++) {
            final int index = i;
            View item = LayoutInflater.from(requireContext()).inflate(R.layout.item_novel_theme, binding.themeGrid, false);
            item.findViewById(R.id.themePreview).setBackgroundColor(THEME_COLORS[i][0]);
            ((TextView) item.findViewById(R.id.themeName)).setText(THEME_NAMES[i]);
            item.setSelected(i == current);
            item.setOnClickListener(v -> {
                prefs.edit().putInt("theme_index", index).apply();
                if (listener != null) listener.onThemeChanged(THEME_COLORS[index][0], THEME_COLORS[index][1]);
                showGroup(0);
            });
            binding.themeGrid.addView(item);
        }
    }

    /** 字体：加粗开关 + 字体族列表（字号已归入「设置」入口）。 */
    private void bindFontGroup() {
        binding.switchBold.setChecked(prefs.getBoolean("bold", false));
        binding.switchBold.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("bold", checked).apply();
            if (listener != null) listener.onBoldChanged(checked);
        });
        int current = prefs.getInt("font_index", 0);
        binding.fontList.removeAllViews();
        for (int i = 0; i < FONT_NAMES.length; i++) {
            final int index = i;
            TextView view = new TextView(requireContext());
            view.setText(FONT_NAMES[i]);
            view.setTextSize(16.0f);
            view.setPadding(32, 24, 32, 24);
            view.setSelected(i == current);
            view.setBackgroundResource(i == current ? R.drawable.bg_novel_theme_item : 0);
            view.setOnClickListener(v -> {
                prefs.edit().putInt("font_index", index).apply();
                if (listener != null) listener.onFontChanged(index);
                showGroup(1);
            });
            binding.fontList.addView(view);
        }
    }

    // ===== 设置入口：行为 =====

    /** 字号：增减按钮。 */
    private void bindSizeGroup() {
        binding.fontSizeValue.setText(String.valueOf(prefs.getInt("font_size", 18)));
        binding.btnFontMinus.setOnClickListener(v -> {
            int size = Math.max(12, prefs.getInt("font_size", 18) - 1);
            prefs.edit().putInt("font_size", size).apply();
            binding.fontSizeValue.setText(String.valueOf(size));
            if (listener != null) listener.onFontSizeChanged(size);
        });
        binding.btnFontPlus.setOnClickListener(v -> {
            int size = Math.min(30, prefs.getInt("font_size", 18) + 1);
            prefs.edit().putInt("font_size", size).apply();
            binding.fontSizeValue.setText(String.valueOf(size));
            if (listener != null) listener.onFontSizeChanged(size);
        });
    }

    /** 排版：行距 / 字间距 / 段间距 / 左右上下边距，参数以 0.1 为单位存储。 */
    private void bindLayoutGroup() {
        binding.btnResetLayout.setOnClickListener(v -> {
            prefs.edit()
                    .putInt("line_spacing", 18)
                    .putInt("letter_spacing", 5)
                    .putInt("para_spacing", 10)
                    .putInt("padding_h", 20)
                    // r19-#5 上/下边距默认 0：正文与标题栏、「上一章/下一章」行之间只保留屏幕宽度 3% 的固定留白
                    .putInt("padding_top", 0)
                    .putInt("padding_bottom", 0)
                    .apply();
            if (listener != null) listener.onLayoutChanged();
            showGroup(1);
        });
        setupSlider(binding.seekLineSpacing, binding.valueLineSpacing, "line_spacing", 10, 30, 18,
                value -> String.format("%.1f", value / 10.0f),
                value -> {
                    if (listener != null) listener.onLineSpacingChanged(value / 10.0f);
                });
        setupSlider(binding.seekLetterSpacing, binding.valueLetterSpacing, "letter_spacing", 0, 20, 5,
                value -> String.format("%.1f", value / 10.0f),
                value -> {
                    if (listener != null) listener.onLayoutChanged();
                });
        setupSlider(binding.seekParaSpacing, binding.valueParaSpacing, "para_spacing", 0, 30, 10,
                value -> String.format("%.1f", value / 10.0f),
                value -> {
                    if (listener != null) listener.onLayoutChanged();
                });
        setupSlider(binding.seekPaddingH, binding.valuePaddingH, "padding_h", 0, 60, 20,
                String::valueOf,
                value -> {
                    if (listener != null) listener.onLayoutChanged();
                });
        // r19-#5 默认 0：上下留白由布局层固定给到「屏宽 3%」，此处只在用户主动加大时追加
        setupSlider(binding.seekPaddingTop, binding.valuePaddingTop, "padding_top", 0, 60, 0,
                String::valueOf,
                value -> {
                    if (listener != null) listener.onLayoutChanged();
                });
        setupSlider(binding.seekPaddingBottom, binding.valuePaddingBottom, "padding_bottom", 0, 60, 0,
                String::valueOf,
                value -> {
                    if (listener != null) listener.onLayoutChanged();
                });
    }

    private void setupSlider(SeekBar seekBar, TextView valueView, String key, int min, int max, int def,
                             Function<Integer, String> formatter, Consumer<Integer> onChange) {
        int value = prefs.getInt(key, def);
        seekBar.setMax(max - min);
        seekBar.setProgress(value - min);
        valueView.setText(formatter.apply(value));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int current = progress + min;
                valueView.setText(formatter.apply(current));
                if (fromUser) {
                    prefs.edit().putInt(key, current).apply();
                    onChange.accept(current);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    /** 显示：屏幕亮度（设置项，与主题配色解耦）。 */
    private void bindDisplayGroup() {
        int current = getCurrentBrightness();
        binding.valueBrightness.setText(String.valueOf(current));
        binding.seekBrightness.setMax(100);
        binding.seekBrightness.setProgress(current);
        binding.seekBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                binding.valueBrightness.setText(String.valueOf(progress));
                if (fromUser) {
                    prefs.edit().putInt("brightness", progress).apply();
                    setBrightness(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    private int getCurrentBrightness() {
        if (prefs.contains("brightness")) return prefs.getInt("brightness", 50);
        if (getActivity() != null) {
            float value = getActivity().getWindow().getAttributes().screenBrightness;
            if (value >= 0.0f) return (int) (value * 100.0f);
        }
        try {
            return (Settings.System.getInt(requireContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) * 100) / 255;
        } catch (Exception e) {
            return 50;
        }
    }

    private void setBrightness(int brightness) {
        if (getActivity() == null) return;
        WindowManager.LayoutParams params = getActivity().getWindow().getAttributes();
        params.screenBrightness = brightness / 100.0f;
        getActivity().getWindow().setAttributes(params);
    }

    /** 行为：屏幕常亮、自动滚动开关与速度、翻页模式。 */
    private void bindBehaviorGroup() {
        binding.switchKeepScreen.setChecked(prefs.getBoolean("keep_screen", true));
        binding.switchKeepScreen.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("keep_screen", checked).apply();
            if (getActivity() == null) return;
            if (checked) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        binding.switchAutoScroll.setOnCheckedChangeListener(null);
        binding.switchAutoScroll.setChecked(prefs.getBoolean("auto_scroll_enable", false));
        binding.switchAutoScroll.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("auto_scroll_enable", checked).apply();
            if (listener != null) listener.onAutoScrollChanged(checked);
        });
        setupSlider(binding.seekAutoScroll, binding.valueAutoScroll, "auto_scroll", 0, 100, 50,
                String::valueOf,
                value -> {
                });
        // #65：原「整屏翻页」开关（点按正文左右侧翻页）随点按翻页一并移除——
        // Liu 定案屏幕单击不翻页、翻页只在功能键上，开关行已从布局删除，page_mode 不再被读取。
    }
}
