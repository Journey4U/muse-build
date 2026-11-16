package com.muse.android.tv.bean;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;
import com.muse.android.tv.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页功能组（原版 OK 5.1.6 大屏版 Button.java 的 leanback 移植）。
 * 与原版唯一的区别：原版 getDefaultButtons 按 home_ui（glide f.z()）分 Simple/Tvbox 两组；
 * leanback 即原版大屏分支，固定用 Tvbox 那组 "1,2,3,4,6,7"（含历史、不含点播），不引入 home_ui 开关。
 * 注意：all() 第 5/6 项引用 R.string.home_cast / R.string.home_history_short，
 * 我方 leanback strings 目前没有这两个串（见 DIFF-REPORT），补串后方可编译。
 */
public class Button {

    private static String all = "0,1,2,3,4,5,6,7,8";
    private static List<Button> buttons = null;
    private static String homeUiTvbox = "1,2,3,8,4,6,7";

    public int id;
    public int resId;

    public Button(int id, int resId) {
        this.id = id;
        this.resId = resId;
    }

    public static List<Button> all() {
        List<Button> list = buttons;
        if (list != null) {
            return list;
        }
        ArrayList<Button> arrayList = new ArrayList<>();
        buttons = arrayList;
        arrayList.add(new Button(0, R.string.home_vod));
        buttons.add(new Button(1, R.string.home_live));
        buttons.add(new Button(2, R.string.home_search));
        buttons.add(new Button(3, R.string.home_keep));
        buttons.add(new Button(4, R.string.home_push));
        buttons.add(new Button(5, R.string.home_cast));
        buttons.add(new Button(6, R.string.home_history_short));
        buttons.add(new Button(7, R.string.home_setting));
        buttons.add(new Button(8, R.string.shelf_title));
        return buttons;
    }

    public static List<Button> getButtons() {
        String text = Prefers.getString("home_button", getDefaultButtons());
        if (TextUtils.isEmpty(text)) {
            return new ArrayList<>();
        }
        String[] split = text.split(",");
        ArrayList<Button> arrayList = new ArrayList<>();
        Map<Integer, Button> map = getMap(all());
        for (String str : split) {
            int parseInt = Integer.parseInt(str);
            if (map.containsKey(parseInt)) {
                arrayList.add(map.get(parseInt));
            }
        }
        return arrayList;
    }

    public static Map<Integer, Button> getButtonsMap() {
        return getMap(getButtons());
    }

    public static String getDefaultButtons() {
        return homeUiTvbox;
    }

    public static Map<Integer, Button> getMap(List<Button> list) {
        LinkedHashMap<Integer, Button> linkedHashMap = new LinkedHashMap<>();
        if (list.size() == 0) {
            return linkedHashMap;
        }
        for (int i = 0; i < list.size(); i++) {
            Button button = list.get(i);
            linkedHashMap.put(button.getId(), button);
        }
        return linkedHashMap;
    }

    public static boolean has(String str) {
        return Prefers.getString("home_button", getDefaultButtons()).contains(str);
    }

    public static void save(Map<Integer, Button> map) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        if (map.size() > 0) {
            Iterator<Integer> it = map.keySet().iterator();
            while (it.hasNext()) {
                arrayList.add(it.next());
            }
        }
        Prefers.put("home_button", TextUtils.join(",", arrayList));
    }

    public static void saveSorted(Map<Integer, Button> map) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        if (map.size() > 0) {
            Iterator<Integer> it = map.keySet().iterator();
            while (it.hasNext()) {
                arrayList.add(it.next());
            }
        }
        Prefers.put("home_button_sorted", TextUtils.join(",", arrayList));
    }

    public static List<Button> sortedAll() {
        String text = Prefers.getString("home_button_sorted", all);
        if (TextUtils.isEmpty(text)) {
            return all();
        }
        String[] split = text.split(",");
        ArrayList<Button> arrayList = new ArrayList<>();
        Map<Integer, Button> map = getMap(all());
        for (String str : split) {
            int parseInt = Integer.parseInt(str);
            if (map.containsKey(parseInt)) {
                arrayList.add(map.get(parseInt));
            }
        }
        return arrayList;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return (obj instanceof Button) && getId() == ((Button) obj).getId();
    }

    public int getId() {
        return this.id;
    }

    public String getName() {
        return com.muse.android.tv.utils.ResUtil.getString(this.resId);
    }

    public int getResId() {
        return this.resId;
    }
}
