package com.muse.android.tv.ui.comic;

import android.app.Activity;
import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.utils.Task;

import java.io.File;

/**
 * 本地漫画图片加载（书架封面、漫画封面格子、两端 ImageActivity 共用）：
 * file:// 直接按文件交 Glide；压缩包条目先在后台解到缓存文件再交 Glide。
 * 视图打标记防复用错图；在线漫画（http 地址）不走这里。
 */
public final class ComicImageLoader {

    private ComicImageLoader() {
    }

    public static void load(ImageView view, String uri) {
        view.setTag(R.id.comic_image_uri, uri);
        if (uri == null || uri.isEmpty()) {
            Glide.with(view).clear(view);
            view.setImageDrawable(null);
            return;
        }
        if (uri.startsWith(LocalComics.FILE_PREFIX)) {
            Glide.with(view).load(new File(uri.substring(LocalComics.FILE_PREFIX.length()))).into(view);
            return;
        }
        Glide.with(view).clear(view);
        view.setImageDrawable(null);
        Task.execute(() -> {
            File file = LocalComics.resolve(uri);
            App.post(() -> {
                if (file == null || !uri.equals(view.getTag(R.id.comic_image_uri))) return;
                Context context = view.getContext();
                if (context instanceof Activity activity && (activity.isFinishing() || activity.isDestroyed())) return;
                Glide.with(view).load(file).into(view);
            });
        });
    }
}
