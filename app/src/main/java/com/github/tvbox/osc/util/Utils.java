package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.bean.VodInfo;
import com.orhanobut.hawk.Hawk;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;


public class Utils {

    public static boolean isTablet(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    public static int getPosterSpanCount(Context context) {
        int widthDp = context.getResources().getConfiguration().screenWidthDp;
        // 目标列宽由用户在设置页选择的海报尺寸决定
        int posterSize = Hawk.get(HawkConfig.POSTER_SIZE, 1);
        float targetColWidth;
        if (Hawk.get(HawkConfig.LANDSCAPE_CARD, false)) {
            // 横版 16:9 卡片：列宽放大，手机竖屏一行一张(整行宽)，平板按宽度自动排 2-3 列
            // 0 小=300dp  1 中=400dp(默认)  2 大=720dp(手机上整行一张,平板 2 列)
            switch (posterSize) {
                case 0: targetColWidth = 300f; break;
                case 2: targetColWidth = 720f; break;
                default: targetColWidth = 400f; break;
            }
            int span = Math.round(widthDp / targetColWidth);
            return Math.max(1, span);
        }
        // 竖版 2:3 卡片
        // 0 小=150dp(列多/海报小)  1 中=180dp(默认)  2 大=220dp(列少/海报大)
        switch (posterSize) {
            case 0: targetColWidth = 150f; break;
            case 2: targetColWidth = 220f; break;
            default: targetColWidth = 180f; break;
        }
        int span = Math.round(widthDp / targetColWidth);
        return Math.max(2, span);
    }

    public static boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public static int getSeriesSpanCount(List<VodInfo.VodSeries> list) {
        int spanCount = 4;
        int total = 0;
        for (VodInfo.VodSeries item : list) total += item.name.length();
        int offset = (int) Math.ceil((double) total / list.size());
        if (offset >= 12) spanCount = 1;
        else if (offset >= 8) spanCount = 2;
        else if (offset >= 4) spanCount = 3;
        else if (offset >= 2) spanCount = 4;
        return spanCount;
    }

    public static String stringForTime(long timeMs) {
//        if (timeMs <= 0 || timeMs >= 24 * 60 * 60 * 1000) {
//            return "00:00";
//        }
        long totalSeconds = timeMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        StringBuilder stringBuilder = new StringBuilder();
        Formatter mFormatter = new Formatter(stringBuilder, Locale.getDefault());
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    public static List<VideoInfo> getVideoList() {
        List<VideoInfo> videoList = new ArrayList<>();
        try (Cursor cursor = App.getInstance().getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] {
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DATA,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.TITLE,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.RESOLUTION,
                        MediaStore.Video.Media.IS_PRIVATE,
                        MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.BOOKMARK
                },
                null,
                null,
                null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                return videoList;
            }
            do {
                VideoInfo videoInfo = new VideoInfo();
                videoInfo.setId(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                videoInfo.setPath(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)));
                videoInfo.setSize(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)));
                videoInfo.setDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                videoInfo.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)));
                videoInfo.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                videoInfo.setResolution(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION)));
                videoInfo.setIsPrivate(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PRIVATE)));
                videoInfo.setBucketId(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)));
                videoInfo.setBucketDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)));
                videoInfo.setBookmark(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BOOKMARK)));
                videoList.add(videoInfo);
            } while (cursor.moveToNext());
        } catch (SecurityException ignored) {
            // The activity requests the media permission before it opens this page.
        }
        return videoList;
    }

    public static boolean isDarkTheme(){
        int currentNightMode = App.getInstance().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES || AppCompatDelegate.getDefaultNightMode()==AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static void initTheme(){
        switch (Hawk.get(HawkConfig.THEME_TAG,0)) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }
}
