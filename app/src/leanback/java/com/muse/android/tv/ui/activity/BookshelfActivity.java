package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.databinding.ActivityBookshelfBinding;
import com.muse.android.tv.databinding.AdapterComicBinding;
import com.muse.android.tv.databinding.AdapterBookshelfBinding;
import com.muse.android.tv.databinding.AdapterBookshelfHeaderBinding;
import com.muse.android.tv.databinding.AdapterBookshelfHintBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.comic.LocalComics;
import com.muse.android.tv.ui.comic.ComicImageLoader;
import com.muse.android.tv.ui.novel.LocalBooks;
import com.muse.android.tv.ui.novel.NovelProgress;
import com.muse.android.tv.ui.activity.ImageActivity;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 小说书架（电视版）：与手机版同一套数据（NovelProgress / LocalBooks / Keep 上架标记）。
 * 遥控模式照 Keep 页：点选打开，长按 OK 进删除模式（点选即删，只删记录不动源文件），
 * 返回键先退删除模式。导入口在页顶按钮，进文件夹模式的选文件页：点 TXT 导单本，
 * 点「选择当前文件夹」导本层全部 TXT。
 * 书架改版：栏目顺序 收藏 → 最近阅读 → 本地书 → 本地漫画；小说条目为 3 列胶囊（总 span 12：胶囊 4、漫画封面 3、
 * 标题/提示 12），书名只在获得焦点时跑马灯滚动（省 32 位电视性能），胶囊底部整本进度条；
 * 栏目标题带数量、可聚焦、OK 键折叠/展开（Prefers 记住，折叠后焦点回到该标题）。
 */
public class BookshelfActivity extends BaseActivity {

    private static final int KIND_RECENT = 0;
    private static final int KIND_LOCAL = 1;
    private static final int KIND_KEPT = 2;
    private static final int KIND_COMIC = 3;

    private static final String FOLD_KEY = "shelf_fold_";
    private static final String SEC_KEPT = "kept";
    private static final String SEC_RECENT = "recent";
    private static final String SEC_LOCAL = "local";
    private static final String SEC_COMIC = "comic";

    private ActivityBookshelfBinding mBinding;
    private ShelfAdapter mAdapter;

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null && result.getData().getData().getPath() != null) {
            File picked = new File(result.getData().getData().getPath());
            if (picked.isDirectory()) {
                onFolderPicked(picked);
                return;
            }
        }
        FileChooser.getUri(result, this::onFilePicked);
    });

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, BookshelfActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityBookshelfBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 总 span 12：小说胶囊占 4（3 列）、漫画封面占 3（保持原 4 列）、栏目标题/提示行占整行
        GridLayoutManager manager = new GridLayoutManager(this, 12);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (mAdapter == null) return manager.getSpanCount();
                if (mAdapter.isComicRow(position)) return 3;
                return mAdapter.isCellRow(position) ? 4 : manager.getSpanCount();
            }
        });
        mBinding.recycler.setLayoutManager(manager);
        mBinding.recycler.setAdapter(mAdapter = new ShelfAdapter());
        mBinding.importBook.setOnClickListener(v -> FileChooser.from(launcher).showInternalFolder());
        mBinding.importComic.setOnClickListener(v -> FileChooser.from(comicLauncher).showInternalFolder());
        loadData();
        mBinding.recycler.requestFocus();
    }

    private void loadData() {
        List<Row> rows = new ArrayList<>();
        List<JSONObject> recent = NovelProgress.all();
        Map<String, JSONObject> records = new HashMap<>();
        for (JSONObject record : recent) records.put(record.optString("bookId"), record);
        // 1) 收藏的小说（置顶）
        Set<String> shelved = NovelProgress.shelfKeys();
        List<Keep> kept = new ArrayList<>();
        for (Keep item : Keep.getVod()) if (shelved.contains(item.getKey())) kept.add(item);
        addHeader(rows, SEC_KEPT, getString(R.string.shelf_kept), kept.size());
        if (!isFolded(SEC_KEPT)) {
            if (kept.isEmpty()) rows.add(Row.hint(getString(R.string.shelf_empty_kept)));
            for (Keep item : kept) rows.add(Row.kept(item));
        }
        // 2) 最近阅读（有记录才显示）
        if (!recent.isEmpty()) {
            addHeader(rows, SEC_RECENT, getString(R.string.shelf_recent), recent.size());
            if (!isFolded(SEC_RECENT)) for (JSONObject record : recent) rows.add(Row.recent(record));
        }
        // 3) 本地书（读过的按最近阅读时间在前）
        List<JSONObject> locals = sortByRecent(LocalBooks.books(), records);
        addHeader(rows, SEC_LOCAL, getString(R.string.shelf_local), locals.size());
        if (!isFolded(SEC_LOCAL)) {
            if (locals.isEmpty()) rows.add(Row.hint(getString(R.string.shelf_empty_local)));
            for (JSONObject book : locals) rows.add(Row.local(book));
        }
        // 4) 本地漫画：有漫画才显示（与底栏/电视功能区「漫画」同一份数据 LocalComics.comics()）
        List<JSONObject> comics = LocalComics.comics();
        if (!comics.isEmpty()) {
            addHeader(rows, SEC_COMIC, getString(R.string.shelf_comic), comics.size());
            if (!isFolded(SEC_COMIC)) for (JSONObject comic : comics) rows.add(Row.comic(comic));
        }
        mAdapter.setRows(rows);
    }

    private void addHeader(List<Row> rows, String section, String title, int count) {
        rows.add(Row.header(getString(R.string.shelf_header_count, title, count), section));
    }

    private boolean isFolded(String section) {
        return Prefers.getBoolean(FOLD_KEY + section, false);
    }

    private void toggleSection(String section) {
        Prefers.put(FOLD_KEY + section, !isFolded(section));
        loadData();
        // 刷新后焦点回到刚按的栏目标题，遥控器不丢焦点
        int position = mAdapter.headerPosition(section);
        if (position < 0) return;
        mBinding.recycler.scrollToPosition(position);
        mBinding.recycler.post(() -> {
            RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(position);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    /** 本地书排序：有阅读记录的按记录时间倒序在前；没读过（或旧记录无时间）的按导入顺序在后。 */
    private static List<JSONObject> sortByRecent(List<JSONObject> books, Map<String, JSONObject> records) {
        List<JSONObject> read = new ArrayList<>();
        List<JSONObject> rest = new ArrayList<>();
        Map<JSONObject, Long> times = new HashMap<>();
        for (JSONObject book : books) {
            JSONObject record = records.get("local:" + book.optString("path"));
            long time = record == null ? 0 : record.optLong("time", 0);
            if (time > 0) {
                times.put(book, time);
                read.add(book);
            } else {
                rest.add(book);
            }
        }
        Collections.sort(read, (a, b) -> Long.compare(times.get(b), times.get(a)));
        read.addAll(rest);
        return read;
    }

    /** 整本阅读进度 0–100；没读过或总章数未知返回 -1（进度条不显示、格子高度不变）。 */
    private static int bookPercent(JSONObject record, int total) {
        if (record == null || total <= 0) return -1;
        int chapter = Math.max(0, Math.min(record.optInt("chapter", 0), total - 1));
        int percent = Math.max(0, Math.min(100, record.optInt("percent", 0)));
        return Math.max(0, Math.min(100, Math.round((chapter + percent / 100f) * 100f / total)));
    }

    private static int localChapters(JSONObject book) {
        return book == null || book.optJSONArray("chapters") == null ? 0 : book.optJSONArray("chapters").length();
    }

    private int percentOf(Row row) {
        if (row.kind == KIND_LOCAL) return bookPercent(NovelProgress.find("local:" + row.book.optString("path")), localChapters(row.book));
        if (row.kind == KIND_KEPT) {
            JSONObject record = NovelProgress.find(row.keep.getKey());
            return bookPercent(record, record == null ? 0 : record.optInt("total", 0));
        }
        if (row.kind == KIND_RECENT) {
            String bookId = row.record.optString("bookId");
            if (bookId.startsWith(LocalComics.BOOK_ID_PREFIX)) {
                JSONObject comic = LocalComics.find(bookId.substring(LocalComics.BOOK_ID_PREFIX.length()));
                return bookPercent(row.record, comic == null ? 0 : comic.optInt("chapters", 0));
            }
            if (bookId.startsWith("local:")) return bookPercent(row.record, localChapters(LocalBooks.find(bookId.substring("local:".length()))));
            return bookPercent(row.record, row.record.optInt("total", 0));
        }
        return -1;
    }

    private final ActivityResultLauncher<Intent> comicLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null && result.getData().getData().getPath() != null) {
            File picked = new File(result.getData().getData().getPath());
            if (picked.isDirectory()) {
                importComic(picked);
                return;
            }
        }
        FileChooser.getUri(result, this::onComicFilePicked);
    });

    private void onComicFilePicked(Uri uri) {
        if (uri == null || uri.getPath() == null) return;
        File file = new File(uri.getPath());
        if (!LocalComics.isArchive(file.getName())) {
            Notify.show(getString(R.string.comic_import_empty));
            return;
        }
        importComic(file);
    }

    private void importComic(File file) {
        Notify.show(getString(R.string.shelf_importing));
        Task.execute(() -> {
            int chapters = LocalComics.importComic(file);
            App.post(() -> {
                if (chapters < 0) Notify.show(getString(R.string.comic_import_fail));
                else if (chapters == 0) Notify.show(getString(R.string.comic_import_empty));
                else {
                    JSONObject comic = LocalComics.find(file.getAbsolutePath());
                    Notify.show(getString(R.string.comic_import_ok, comic == null ? file.getName() : comic.optString("title"), chapters));
                    loadData();
                }
            });
        });
    }

    private void onFilePicked(Uri uri) {
        if (uri == null || uri.getPath() == null) return;
        File file = new File(uri.getPath());
        if (!file.getName().toLowerCase().endsWith(".txt")) {
            Notify.show(getString(R.string.shelf_txt_only));
            return;
        }
        Notify.show(getString(R.string.shelf_importing));
        Task.execute(() -> {
            int count = LocalBooks.importBook(file);
            App.post(() -> {
                if (count < 0) {
                    Notify.show(getString(R.string.shelf_import_fail));
                } else {
                    JSONObject book = LocalBooks.find(file.getAbsolutePath());
                    Notify.show(getString(R.string.shelf_import_ok, book == null ? file.getName() : book.optString("title"), count));
                    loadData();
                }
            });
        });
    }

    private void onFolderPicked(File dir) {
        Notify.show(getString(R.string.shelf_importing));
        Task.execute(() -> {
            File[] files = dir.listFiles((parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
            int total = 0, added = 0, skipped = 0, failed = 0;
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File file : files) {
                    if (!file.isFile()) continue;
                    total++;
                    if (LocalBooks.find(file.getAbsolutePath()) != null) skipped++;
                    else if (LocalBooks.importBook(file) < 0) failed++;
                    else added++;
                }
            }
            int totalCount = total, addedCount = added, skippedCount = skipped, failedCount = failed;
            App.post(() -> {
                if (totalCount == 0) {
                    Notify.show(getString(R.string.shelf_folder_empty));
                } else {
                    Notify.show(getString(R.string.shelf_folder_result, addedCount, skippedCount, failedCount));
                    if (addedCount > 0) loadData();
                }
            });
        });
    }

    private void openLocal(JSONObject book) {
        String path = book.optString("path");
        ArrayList<String> items = LocalBooks.chapterItems(path);
        if (items.isEmpty()) {
            Notify.show(getString(R.string.shelf_import_fail));
            return;
        }
        JSONObject record = NovelProgress.find("local:" + path);
        int position = record == null ? 0 : Math.max(0, Math.min(record.optInt("chapter", 0), items.size() - 1));
        NovelActivity.startLocal(this, path, book.optString("title"), items, position);
    }

    private void openSpider(String bookId, String title, String pic) {
        String[] parts = bookId.split("@@@");
        if (parts.length < 2) return;
        VideoActivity.start(this, parts[0], parts[1], title, pic);
    }

    private void onRowClick(Row row) {
        if (row.kind == KIND_COMIC) ImageActivity.openLocalComic(this, row.book.optString("path"));
        else if (row.kind == KIND_LOCAL) openLocal(row.book);
        else if (row.kind == KIND_KEPT) VideoActivity.start(this, row.keep.getSiteKey(), row.keep.getVodId(), row.keep.getVodName(), row.keep.getVodPic());
        else if (row.kind == KIND_RECENT) {
            String bookId = row.record.optString("bookId");
            if (bookId.startsWith(LocalComics.BOOK_ID_PREFIX)) {
                ImageActivity.openLocalComic(this, bookId.substring(LocalComics.BOOK_ID_PREFIX.length()));
            } else if (bookId.startsWith("local:")) {
                JSONObject book = LocalBooks.find(bookId.substring("local:".length()));
                if (book != null) openLocal(book);
                else Notify.show(getString(R.string.shelf_book_gone));
            } else {
                openSpider(bookId, row.record.optString("title"), row.record.optString("pic"));
            }
        }
    }

    // 删除模式下点选即删（语义与手机版长按确认框一致，只删记录/标记，不动源文件）。
    private void deleteRow(Row row) {
        if (row.kind == KIND_COMIC) {
            LocalComics.remove(row.book.optString("path"));
        } else if (row.kind == KIND_LOCAL) {
            String path = row.book.optString("path");
            LocalBooks.remove(path);
            NovelProgress.remove("local:" + path);
        } else if (row.kind == KIND_KEPT) {
            NovelProgress.markShelf(row.keep.getKey(), false);
            row.keep.delete();
        } else if (row.kind == KIND_RECENT) {
            NovelProgress.remove(row.record.optString("bookId"));
        }
        loadData();
        if (!mAdapter.hasBookRows()) mAdapter.setDelete(false);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter != null && mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAdapter != null) loadData();
    }

    private static class Row {
        static final int TYPE_HEADER = 0;
        static final int TYPE_ROW = 1;
        static final int TYPE_HINT = 2;
        static final int TYPE_COMIC = 3;

        int type;
        int kind;
        String text;
        String section;
        JSONObject book;
        JSONObject record;
        Keep keep;

        static Row header(String text, String section) {
            Row row = new Row();
            row.type = TYPE_HEADER;
            row.kind = -1;
            row.text = text;
            row.section = section;
            return row;
        }

        static Row hint(String text) {
            Row row = new Row();
            row.type = TYPE_HINT;
            row.kind = -1;
            row.text = text;
            return row;
        }

        static Row local(JSONObject book) {
            Row row = new Row();
            row.type = TYPE_ROW;
            row.kind = KIND_LOCAL;
            row.book = book;
            return row;
        }

        static Row recent(JSONObject record) {
            Row row = new Row();
            row.type = TYPE_ROW;
            row.kind = KIND_RECENT;
            row.record = record;
            return row;
        }

        static Row comic(JSONObject comic) {
            Row row = new Row();
            row.type = TYPE_ROW;
            row.kind = KIND_COMIC;
            row.book = comic;
            return row;
        }

        static Row kept(Keep keep) {
            Row row = new Row();
            row.type = TYPE_ROW;
            row.kind = KIND_KEPT;
            row.keep = keep;
            return row;
        }
    }

    private class ShelfAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<Row> rows = new ArrayList<>();
        private boolean delete;

        void setRows(List<Row> items) {
            rows.clear();
            rows.addAll(items);
            notifyDataSetChanged();
        }

        boolean isComicRow(int position) {
            return position >= 0 && position < rows.size() && rows.get(position).kind == KIND_COMIC;
        }

        /** 格子行（小说胶囊、漫画封面）；栏目标题与提示行占整行。 */
        boolean isCellRow(int position) {
            return position >= 0 && position < rows.size() && rows.get(position).type == Row.TYPE_ROW;
        }

        int headerPosition(String section) {
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).type == Row.TYPE_HEADER && section.equals(rows.get(i).section)) return i;
            return -1;
        }

        boolean isDelete() {
            return delete;
        }

        void setDelete(boolean delete) {
            this.delete = delete;
            notifyDataSetChanged();
        }

        boolean hasBookRows() {
            for (Row row : rows) if (row.type == Row.TYPE_ROW) return true;
            return false;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind == KIND_COMIC ? Row.TYPE_COMIC : rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Row.TYPE_HEADER) return new HeaderHolder(AdapterBookshelfHeaderBinding.inflate(inflater, parent, false));
            if (viewType == Row.TYPE_COMIC) return new ComicHolder(AdapterComicBinding.inflate(inflater, parent, false));
            if (viewType == Row.TYPE_HINT) return new HintHolder(AdapterBookshelfHintBinding.inflate(inflater, parent, false));
            return new RowHolder(AdapterBookshelfBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof HeaderHolder headerHolder) {
                boolean folded = isFolded(row.section);
                headerHolder.binding.text.setText(row.text);
                headerHolder.binding.arrow.setRotation(folded ? -90f : 0f);
                headerHolder.binding.arrow.setContentDescription(getString(folded ? R.string.shelf_expand : R.string.shelf_collapse));
                holder.itemView.setOnClickListener(v -> toggleSection(row.section));
                return;
            }
            if (holder instanceof HintHolder hintHolder) {
                hintHolder.binding.text.setText(row.text);
                return;
            }
            if (holder instanceof ComicHolder comicHolder) {
                comicHolder.binding.title.setText(row.book.optString("title"));
                comicHolder.binding.sub.setText(LocalComics.progressText(BookshelfActivity.this, row.book));
                ComicImageLoader.load(comicHolder.binding.cover, row.book.optString("cover"));
                holder.itemView.setOnClickListener(v -> {
                    if (delete) deleteRow(row);
                    else onRowClick(row);
                });
                holder.itemView.setOnLongClickListener(v -> {
                    if (!delete) {
                        setDelete(true);
                        Notify.show(getString(R.string.comic_delete_hint));
                    }
                    return true;
                });
                return;
            }
            RowHolder rowHolder = (RowHolder) holder;
            rowHolder.binding.del.setVisibility(delete ? View.VISIBLE : View.GONE);
            rowHolder.binding.title.setText(titleOf(row));
            // 跑马灯只在获得焦点时滚动，失焦截断（几十本同时滚太费 32 位电视）
            setMarquee(rowHolder, rowHolder.itemView.hasFocus());
            rowHolder.itemView.setOnFocusChangeListener((v, hasFocus) -> setMarquee(rowHolder, hasFocus));
            rowHolder.binding.sub.setText(subOf(row));
            int percent = percentOf(row);
            rowHolder.binding.progress.setVisibility(percent < 0 ? View.INVISIBLE : View.VISIBLE);
            rowHolder.binding.progress.setProgress(Math.max(0, percent));
            rowHolder.itemView.setOnClickListener(v -> {
                if (delete) deleteRow(row);
                else onRowClick(row);
            });
            rowHolder.itemView.setOnLongClickListener(v -> {
                if (!delete) setDelete(true);
                return true;
            });
        }

        private void setMarquee(RowHolder holder, boolean on) {
            holder.binding.title.setEllipsize(on ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
            holder.binding.title.setSelected(on);
        }

        private String titleOf(Row row) {
            if (row.kind == KIND_COMIC) return row.book.optString("title");
            if (row.kind == KIND_LOCAL) return row.book.optString("title");
            if (row.kind == KIND_KEPT) return row.keep.getVodName();
            return row.record.optString("title");
        }

        private String subOf(Row row) {
            if (row.kind == KIND_COMIC) return LocalComics.progressText(BookshelfActivity.this, row.book);
            if (row.kind == KIND_LOCAL) {
                JSONObject record = NovelProgress.find("local:" + row.book.optString("path"));
                int chapters = row.book.optJSONArray("chapters") == null ? 0 : row.book.optJSONArray("chapters").length();
                String base = getString(R.string.shelf_chapters, chapters);
                return record == null ? base : base + " · " + getString(R.string.shelf_read_to, record.optString("chapterName"), record.optInt("percent"));
            }
            if (row.kind == KIND_KEPT) return row.keep.getSiteName();
            String site = row.record.optString("site");
            String base = row.record.optString("bookId").startsWith("local:") ? getString(R.string.shelf_local_tag) : site;
            return base + " · " + getString(R.string.shelf_read_to, row.record.optString("chapterName"), row.record.optInt("percent"));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static class ComicHolder extends RecyclerView.ViewHolder {
        final AdapterComicBinding binding;

        ComicHolder(AdapterComicBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class HintHolder extends RecyclerView.ViewHolder {
        final AdapterBookshelfHintBinding binding;

        HintHolder(AdapterBookshelfHintBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class HeaderHolder extends RecyclerView.ViewHolder {
        final AdapterBookshelfHeaderBinding binding;

        HeaderHolder(AdapterBookshelfHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class RowHolder extends RecyclerView.ViewHolder {
        final AdapterBookshelfBinding binding;

        RowHolder(AdapterBookshelfBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
