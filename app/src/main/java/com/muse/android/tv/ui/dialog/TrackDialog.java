package com.muse.android.tv.ui.dialog;

import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.mpvplayer.MpvPlayer;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.TrackNameProvider;
import androidx.viewbinding.ViewBinding;

import com.muse.android.tv.App;
import com.muse.android.tv.R;
import com.muse.android.tv.bean.Sub;
import com.muse.android.tv.bean.Track;
import com.muse.android.tv.databinding.DialogTrackBinding;
import com.muse.android.tv.player.PlayerManager;
import com.muse.android.tv.player.track.TrackUtil;
import com.muse.android.tv.ui.adapter.TrackAdapter;
import com.muse.android.tv.ui.custom.SpaceItemDecoration;
import com.muse.android.tv.utils.FileChooser;
import com.muse.android.tv.utils.FileUtil;
import com.muse.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public final class TrackDialog extends BaseBottomSheetDialog implements TrackAdapter.OnClickListener {

    private final TrackNameProvider provider;
    private final TrackAdapter adapter;
    private DialogTrackBinding binding;
    private SubtitleView subtitleView;
    private PlayerManager player;
    private int type;

    public TrackDialog() {
        this.adapter = new TrackAdapter(this);
        this.provider = new DefaultTrackNameProvider(App.get().getResources());
    }

    public static TrackDialog create() {
        return new TrackDialog();
    }

    public TrackDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public TrackDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public TrackDialog type(int type) {
        this.type = type;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TrackDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean hasChoose() {
        return type == C.TRACK_TYPE_TEXT && player.isVod();
    }

    private boolean hasSearch() {
        return type == C.TRACK_TYPE_TEXT && player.isVod();
    }

    private boolean hasSetting() {
        return type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_TEXT;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTrackBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        binding.search.setVisibility(hasSearch() ? View.VISIBLE : View.GONE);
        binding.choose.setVisibility(hasChoose() ? View.VISIBLE : View.GONE);
        binding.setting.setVisibility(hasSetting() ? View.VISIBLE : View.GONE);
        binding.title.setText(ResUtil.getStringArray(R.array.select_track)[type - 1]);
    }

    @Override
    protected void initEvent() {
        binding.search.setOnClickListener(this::onSearch);
        binding.choose.setOnClickListener(this::onChoose);
        binding.setting.setOnClickListener(this::onSetting);
    }

    private void setRecyclerView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(adapter.addAll(getTrack()));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(adapter.getSelected()));
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void onSearch(View view) {
        FragmentActivity activity = requireActivity();
        dismissNow();
        SubtitleSearchDialog.create().player(player).show(activity);
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show(new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, "audio/*", "text/*", "application/octet-stream"});
        player.pause();
    }

    private void onSetting(View view) {
        FragmentActivity activity = requireActivity();
        dismissNow();
        showSetting(activity);
    }

    private void showSetting(FragmentActivity activity) {
        switch (type) {
            case C.TRACK_TYPE_AUDIO -> AudioSettingDialog.create().player(player).show(activity);
            case C.TRACK_TYPE_VIDEO -> VideoSettingDialog.create().player(player).show(activity);
            case C.TRACK_TYPE_TEXT -> SubtitleSettingDialog.create().view(subtitleView).player(player).show(activity);
        }
    }

    private List<Track> getTrack() {
        List<Track> items = new ArrayList<>();
        addTrack(items);
        return items;
    }

    private void addTrack(List<Track> items) {
        if (type == C.TRACK_TYPE_VIDEO && addHlsVariants(items)) return;
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group trackGroup = groups.get(i);
            if (trackGroup.getType() != type) continue;
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getTrackFormat(j);
                String name = provider.getTrackName(format);
                Track item = new Track(type, name, TrackUtil.describeFormat(format));
                // Keep the player's native track id with the visible item:
                // the MPV player selects tracks by this id directly, since
                // it never consumes TrackSelectionParameters overrides.
                item.playerId(format.id);
                item.setSelected(trackGroup.isTrackSelected(j));
                items.add(item);
            }
        }
    }

    // The MPV player's track list only ever materializes the HLS variant
    // currently playing, so for multi-variant HLS the video entries are
    // synthesized from the player's variant snapshot instead — the same
    // playlist data the EXO entries are built from.
    private boolean addHlsVariants(List<Track> items) {
        MpvPlayer.AutoHlsRuntimeSnapshot snapshot = player.getHlsSnapshot();
        if (snapshot == null || !snapshot.likelyHls() || snapshot.variants().size() <= 1) return false;
        boolean manual = !snapshot.acceptedOption().isEmpty() || !snapshot.stagedOption().isEmpty();
        Track auto = new Track(type, ResUtil.getString(R.string.exo_track_selection_auto), "");
        auto.setHlsVariant(true);
        auto.setSelected(!manual);
        items.add(auto);
        Format current = getCurrentVideoFormat();
        for (MpvPlayer.HlsVariant variant : snapshot.variants()) {
            long bits = variant.selectionBitsPerSecond();
            Format.Builder builder = new Format.Builder();
            if (current != null) builder.setSampleMimeType(current.sampleMimeType).setCodecs(current.codecs);
            if (variant.width() > 0) builder.setWidth(variant.width());
            if (variant.height() > 0) builder.setHeight(variant.height());
            if (bits > 0) builder.setAverageBitrate((int) Math.min(bits, Integer.MAX_VALUE));
            Track item = new Track(type, provider.getTrackName(builder.build()), "");
            item.setHlsVariant(true);
            item.setHlsVariantBits(bits);
            item.setSelected(snapshot.selectedVariant() != null && bits == snapshot.selectedVariant().selectionBitsPerSecond());
            items.add(item);
        }
        return true;
    }

    private Format getCurrentVideoFormat() {
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) return group.getTrackFormat(i);
            if (group.length > 0) return group.getTrackFormat(0);
        }
        return null;
    }

    @Override
    public void onItemClick(Track item) {
        if (item.isHlsVariant()) {
            // Variant entries are runtime state, not a persisted track
            // preference: they switch through the HLS bitrate ceiling.
            player.setHlsVariant(item.getHlsVariantBits());
            dismiss();
            return;
        }
        player.setTrack(item.key(player.getKey()).save());
        dismiss();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> FileChooser.getUri(result, this::setSubtitle));

    private void setSubtitle(Uri uri) {
        if (!isAdded()) return;
        player.setSub(Sub.from(FileUtil.getDisplayName(uri), uri.toString()));
        dismiss();
    }
}

