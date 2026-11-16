package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.bean.Keep;
import com.muse.android.tv.databinding.ActivityBookshelfBinding;
import com.muse.android.tv.databinding.AdapterBookshelfBinding;
import com.muse.android.tv.databinding.AdapterBookshelfHeaderBinding;
import com.muse.android.tv.ui.base.BaseActivity;
import com.muse.android.tv.ui.novel.LocalBooks;
import com.muse.android.tv.ui.novel.NovelProgress;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.Task;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 小说书架（电视版）：与手机版同一套数据（NovelProgress / LocalBooks / Keep 上架标记）。
 * 遥控模式照 Keep 页：点选打开，长按 OK 进删除模式（点选即删，只删记录不动源文件），
 * 返回键先退删除模式。导入口在页顶按钮，进文件夹模式的选文件页：点 TXT 导单本，
 * 点「选择当前文件夹」导本层全部 TXT。
 */
public class BookshelfActivity extends BaseActivity {

    private static final int KIND_RECENT = 0;
    private static final int KIND_LOCAL = 1;
    private static final int KIND_KEPT = 2;

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
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new ShelfAdapter());
        mBinding.importBook.setOnClickListener(v -> FileChooser.from(launcher).showInternalFolder());
        loadData();
        mBinding.recycler.requestFocus();
    }

    private void loadData() {
        List<Row> rows = new ArrayList<>();
        List<JSONObject> recent = NovelProgress.all();
        if (!recent.isEmpty()) {
            rows.add(Row.header(getString(R.string.shelf_recent)));
            for (JSONObject record : recent) rows.add(Row.recent(record));
        }
        List<JSONObject> locals = LocalBooks.books();
        rows.add(Row.header(getString(R.string.shelf_local)));
        if (locals.isEmpty()) rows.add(Row.hint(getString(R.string.shelf_empty_local)));
        for (JSONObject book : locals) rows.add(Row.local(book));
        Set<String> shelved = NovelProgress.shelfKeys();
        List<Keep> kept = new ArrayList<>();
        for (Keep item : Keep.getVod()) if (shelved.contains(item.getKey())) kept.add(item);
        rows.add(Row.header(getString(R.string.shelf_kept)));
        if (kept.isEmpty()) rows.add(Row.hint(getString(R.string.shelf_empty_kept)));
        for (Keep item : kept) rows.add(Row.kept(item));
        mAdapter.setRows(rows);
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
        if (row.kind == KIND_LOCAL) openLocal(row.book);
        else if (row.kind == KIND_KEPT) VideoActivity.start(this, row.keep.getSiteKey(), row.keep.getVodId(), row.keep.getVodName(), row.keep.getVodPic());
        else if (row.kind == KIND_RECENT) {
            String bookId = row.record.optString("bookId");
            if (bookId.startsWith("local:")) {
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
        if (row.kind == KIND_LOCAL) {
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

        int type;
        int kind;
        String text;
        JSONObject book;
        JSONObject record;
        Keep keep;

        static Row header(String text) {
            Row row = new Row();
            row.type = TYPE_HEADER;
            row.text = text;
            return row;
        }

        static Row hint(String text) {
            Row row = new Row();
            row.type = TYPE_HINT;
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
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Row.TYPE_HEADER) return new HeaderHolder(AdapterBookshelfHeaderBinding.inflate(inflater, parent, false));
            return new RowHolder(AdapterBookshelfBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).binding.text.setText(row.text);
                return;
            }
            RowHolder rowHolder = (RowHolder) holder;
            rowHolder.binding.del.setVisibility(delete && row.type == Row.TYPE_ROW ? View.VISIBLE : View.GONE);
            if (row.type == Row.TYPE_HINT) {
                rowHolder.binding.title.setText(row.text);
                rowHolder.binding.sub.setText("");
                rowHolder.itemView.setOnClickListener(null);
                rowHolder.itemView.setOnLongClickListener(null);
                return;
            }
            rowHolder.binding.title.setText(titleOf(row));
            rowHolder.binding.sub.setText(subOf(row));
            rowHolder.itemView.setOnClickListener(v -> {
                if (delete) deleteRow(row);
                else onRowClick(row);
            });
            rowHolder.itemView.setOnLongClickListener(v -> {
                if (!delete) setDelete(true);
                return true;
            });
        }

        private String titleOf(Row row) {
            if (row.kind == KIND_LOCAL) return row.book.optString("title");
            if (row.kind == KIND_KEPT) return row.keep.getVodName();
            return row.record.optString("title");
        }

        private String subOf(Row row) {
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
