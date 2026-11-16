package com.muse.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
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
import com.muse.android.tv.ui.novel.NovelReaderActivity;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.Notify;
import com.muse.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 小说书架（手机版）：最近阅读（阅读器进度记录）+ 本地书（TXT / EPUB 导入）+ 收藏的小说（Keep 视图）。
 */
public class BookshelfActivity extends BaseActivity {

    private static final int KIND_RECENT = 0;
    private static final int KIND_LOCAL = 1;
    private static final int KIND_KEPT = 2;

    private ActivityBookshelfBinding mBinding;
    private ShelfAdapter mAdapter;

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        // Folder mode returns the directory itself; FileChooser.getUri only
        // accepts files, so directories are intercepted before it.
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
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new ShelfAdapter());
        loadData();
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
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".txt") && !lowerName.endsWith(".epub")) {
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
                    Notify.show(getString(R.string.shelf_import_ok, LocalBooks.find(file.getAbsolutePath()) == null ? file.getName() : LocalBooks.find(file.getAbsolutePath()).optString("title"), count));
                    loadData();
                }
            });
        });
    }

    // Folder import: only this folder's own TXT / EPUB files (no recursion,
    // per Liu's choice); books already on the shelf are skipped by path.
    private void onFolderPicked(File dir) {
        Notify.show(getString(R.string.shelf_importing));
        Task.execute(() -> {
            File[] files = dir.listFiles((parent, name) -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.endsWith(".txt") || lower.endsWith(".epub");
            });
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
        NovelReaderActivity.startLocal(this, path, book.optString("title"), items, position);
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

    private void onRowLongClick(Row row) {
        if (row.kind == KIND_LOCAL) {
            String path = row.book.optString("path");
            confirm(getString(R.string.shelf_remove_book), getString(R.string.shelf_remove_local_msg), () -> {
                LocalBooks.remove(path);
                NovelProgress.remove("local:" + path);
                loadData();
            });
        } else if (row.kind == KIND_KEPT) {
            confirm(getString(R.string.shelf_remove_book), "", () -> {
                NovelProgress.markShelf(row.keep.getKey(), false);
                row.keep.delete();
                loadData();
            });
        } else if (row.kind == KIND_RECENT) {
            confirm(getString(R.string.shelf_delete_record), "", () -> {
                NovelProgress.remove(row.record.optString("bookId"));
                loadData();
            });
        }
    }

    private void confirm(String title, String message, Runnable action) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this).setTitle(title)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> action.run());
        if (!message.isEmpty()) builder.setMessage(message);
        builder.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_bookshelf, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.import_book) FileChooser.from(launcher).showInternalFolder();
        return super.onOptionsItemSelected(item);
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

        void setRows(List<Row> items) {
            rows.clear();
            rows.addAll(items);
            notifyDataSetChanged();
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
            if (row.type == Row.TYPE_HINT) {
                rowHolder.binding.title.setText(row.text);
                rowHolder.binding.sub.setText("");
                rowHolder.itemView.setOnClickListener(null);
                rowHolder.itemView.setOnLongClickListener(null);
                return;
            }
            rowHolder.binding.title.setText(titleOf(row));
            rowHolder.binding.sub.setText(subOf(row));
            rowHolder.itemView.setOnClickListener(v -> onRowClick(row));
            rowHolder.itemView.setOnLongClickListener(v -> {
                onRowLongClick(row);
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
