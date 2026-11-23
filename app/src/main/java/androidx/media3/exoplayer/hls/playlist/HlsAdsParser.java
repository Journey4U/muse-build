/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.hls.playlist;

import android.text.TextUtils;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HlsAdsParser {

  private static final String TAG = HlsAdsParser.class.getSimpleName();

  private static final String TAG_DURATION = "#EXTINF";
  private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
  private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
  private static final String DEFAULT_GROUP_IDENTIFIER = "NO_PATH";

  private static final int REASONABLE_GROUP_LIMIT = 10;
  private static final int MIN_PREFIX_LENGTH_TO_TEST = 5;
  private static final int SEQUENCE_NUMBER_RESERVED_LENGTH = 4;

  private static final int AD_BREAK_THRESHOLD_SHORT = 3;
  private static final int AD_BREAK_THRESHOLD_MEDIUM = 4;
  private static final int AD_BREAK_THRESHOLD_LONG = 5;
  private static final int AD_BREAK_THRESHOLD_EXTRA = 6;

  private static final double MIN_MAJORITY_GROUP_RATIO = 0.85;
  private static final double AD_BLOCK_SIZE_RATIO = 0.75;
  private static final double DURATION_TIER_SHORT = 30.0;
  private static final double DURATION_TIER_MEDIUM = 60.0;
  private static final double DURATION_TIER_LONG = 90.0;

  private static final double DURATION_AD_MAX_SECONDS = 60.0;
  private static final double DURATION_CONTENT_MIN_SECONDS = 120.0;
  private static final double DURATION_AD_TOTAL_RATIO_LIMIT = 0.15;

  public static String process(String m3u8) {
    return process(m3u8, null);
  }

  /**
   * playlistUrl：本清单自己的地址（Exo 取 parse(uri) 的 uri，MPV 代理取请求 url）。
   * 用来认「正片锚点」：正片分片所在目录与清单自己的目录同源同路径；
   * 被缝进来的广告段来自另一个目录。只认结构，不看段数多少、不看时长。
   */
  public static String process(String m3u8, String playlistUrl) {
    if (TextUtils.isEmpty(m3u8) || !m3u8.contains(TAG_ENDLIST)) {
      return m3u8;
    }
    Log.d(TAG, "開始執行 HlsAdsParser。");
    String[] lines = Util.split(m3u8, "\\r?\\n");
    String[] strategy = new String[1];
    Set<Integer> adSegmentIndexes = findAds(lines, playlistUrl, strategy);
    if (adSegmentIndexes.isEmpty()) {
      Log.d(TAG, "沒有偵測到廣告片段，保留原始播放清單。");
      logNoStrip(lines, strategy[0]);
      return m3u8;
    }
    Log.d(TAG, "偵測到 " + adSegmentIndexes.size() + " 個廣告片段，開始重建播放清單。");
    logStripSummary(lines, adSegmentIndexes, playlistUrl, strategy[0]);
    return rebuildM3u8(lines, adSegmentIndexes, m3u8.length());
  }

  private static Set<Integer> findAds(String[] lines, String playlistUrl, String[] strategy) {
    Log.d(TAG, "執行主要策略：檔名與路徑分析。");
    List<String> allSegments = getSegments(lines);
    Log.d(TAG, "播放清單共有 " + allSegments.size() + " 個媒體片段。");
    // 先认正片锚点：认出来就以它为准（含「结构不干净、不动」），不再落到段数/时长猜测。
    Set<Integer> anchored = findAdsByAnchor(allSegments, lines, playlistUrl);
    if (anchored != null) {
      strategy[0] = anchored.isEmpty() ? "錨點（結構不乾淨，不動）" : "錨點";
      return anchored;
    }
    Set<Integer> adsFromFilename = findAdsByFilename(allSegments);
    if (!adsFromFilename.isEmpty()) {
      Log.d(TAG, "主要策略命中，使用檔名與路徑分析結果。");
      strategy[0] = "檔名路徑";
      return adsFromFilename;
    }
    Log.d(TAG, "主要策略未找到廣告，改用不連續標籤分析。");
    Set<Integer> adsFromDiscontinuity = findAdsByDiscontinuity(lines);
    if (!adsFromDiscontinuity.isEmpty()) {
      strategy[0] = "不連續標籤";
      return adsFromDiscontinuity;
    }
    Log.d(TAG, "不連續標籤分析未找到廣告，改用時長分析。");
    Set<Integer> adsFromDuration = findAdsByDuration(lines);
    strategy[0] = adsFromDuration.isEmpty() ? "各策略均未命中" : "時長";
    return adsFromDuration;
  }

  private static List<String> getSegments(String[] lines) {
    List<String> segments = new ArrayList<>(lines.length);
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (isSegmentLine(trimmedLine)) {
        segments.add(trimmedLine);
      }
    }
    return segments;
  }

  private static Set<Integer> findAdsByDiscontinuity(String[] lines) {
    List<List<Integer>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "不連續標籤分析只有 " + blocks.size() + " 個區塊，無法判斷。");
      return Collections.emptySet();
    }
    Log.d(TAG, "不連續標籤分析找到 " + blocks.size() + " 個區塊。");
    List<List<Integer>> analysisBlocks = blocks.subList(0, blocks.size() - 1);
    int modeSize = getModeSize(analysisBlocks);
    if (modeSize <= 0) {
      Log.d(TAG, "不連續標籤分析無法取得有效眾數區塊大小。");
      return Collections.emptySet();
    }
    int minorityBlockCount = 0;
    Set<Integer> adSegmentIndexes = new HashSet<>(analysisBlocks.size());
    double adSizeThreshold = modeSize * AD_BLOCK_SIZE_RATIO;
    int maxBlockSize = 0;
    for (List<Integer> block : analysisBlocks) {
      int size = block.size();
      maxBlockSize = Math.max(maxBlockSize, size);
      if (size < adSizeThreshold) {
        minorityBlockCount++;
        adSegmentIndexes.addAll(block);
      }
    }
    if (minorityBlockCount == 0 && modeSize * 2 < maxBlockSize) {
      Log.d(
          TAG, "不連續標籤分析：眾數區塊大小為 " + modeSize + "，明顯小於最大區塊 " + maxBlockSize + "，改將不大於眾數大小的區塊視為廣告。");
      for (List<Integer> block : analysisBlocks) {
        if (block.size() <= modeSize) {
          minorityBlockCount++;
          adSegmentIndexes.addAll(block);
        }
      }
    }
    double totalDurationMinutes = getTotalDurationInMinutes(lines);
    int minorityCountThreshold = getMinorityCountThreshold(totalDurationMinutes);
    Log.d(
        TAG,
        "播放清單總時長約 "
            + Util.formatInvariant("%.2f", totalDurationMinutes)
            + " 分鐘，廣告區塊上限為 "
            + minorityCountThreshold
            + "。");
    if (minorityBlockCount > 0 && minorityBlockCount <= minorityCountThreshold) {
      Log.d(TAG, "不連續標籤分析找到 " + minorityBlockCount + " 個廣告區塊，眾數區塊大小為 " + modeSize + "，已排除最後一個區塊。");
      return adSegmentIndexes;
    }
    Log.d(
        TAG,
        "不連續標籤分析找到 "
            + minorityBlockCount
            + " 個可疑廣告區塊，超過上限 "
            + minorityCountThreshold
            + " 或為 0，判定結果不明確，忽略。");
    return Collections.emptySet();
  }

  private static Set<Integer> findAdsByDuration(String[] lines) {
    List<List<Integer>> blocks = getDiscontinuityBlocks(lines);
    if (blocks.size() < 2) {
      Log.d(TAG, "時長分析只有 " + blocks.size() + " 個區塊，無法判斷。");
      return Collections.emptySet();
    }
    double[] durations = getBlockDurations(lines, blocks.size());
    if (durations == null) {
      Log.d(TAG, "時長分析的區塊時長與區塊數不一致，忽略。");
      return Collections.emptySet();
    }
    double totalDuration = 0.0;
    boolean hasLongBlock = false;
    for (double duration : durations) {
      totalDuration += duration;
      if (duration >= DURATION_CONTENT_MIN_SECONDS) {
        hasLongBlock = true;
      }
    }
    if (!hasLongBlock || totalDuration <= 0) {
      Log.d(TAG, "時長分析沒有足夠長的正片區塊，無法判斷。");
      return Collections.emptySet();
    }
    // 沿用不連續標籤分析的慣例：最後一個區塊不列入候選，避免誤傷片尾。
    Set<Integer> adSegmentIndexes = new HashSet<>();
    double adDuration = 0.0;
    for (int i = 0; i < blocks.size() - 1; i++) {
      if (durations[i] > 0 && durations[i] < DURATION_AD_MAX_SECONDS) {
        adSegmentIndexes.addAll(blocks.get(i));
        adDuration += durations[i];
      }
    }
    if (adSegmentIndexes.isEmpty()) {
      Log.d(TAG, "時長分析沒有發現短區塊。");
      return Collections.emptySet();
    }
    if (adDuration > totalDuration * DURATION_AD_TOTAL_RATIO_LIMIT) {
      Log.d(TAG, "時長分析的短區塊總時長佔比過高，判定結果不明確，忽略。");
      return Collections.emptySet();
    }
    Log.d(TAG, "時長分析找到 " + adSegmentIndexes.size() + " 個廣告片段。");
    return adSegmentIndexes;
  }

  private static double[] getBlockDurations(String[] lines, int expectedBlocks) {
    List<Double> durations = new ArrayList<>();
    double currentDuration = 0.0;
    boolean currentHasSegment = false;
    double pendingDuration = 0.0;
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (currentHasSegment) {
          durations.add(currentDuration);
          currentDuration = 0.0;
          currentHasSegment = false;
        }
      } else if (trimmedLine.startsWith(TAG_DURATION)) {
        try {
          pendingDuration = parseExtInfDurationSeconds(trimmedLine);
        } catch (NumberFormatException ignored) {
          pendingDuration = 0.0;
        }
      } else if (isSegmentLine(trimmedLine)) {
        currentDuration += pendingDuration;
        pendingDuration = 0.0;
        currentHasSegment = true;
      }
    }
    if (currentHasSegment) {
      durations.add(currentDuration);
    }
    if (durations.size() != expectedBlocks) {
      return null;
    }
    double[] result = new double[durations.size()];
    for (int i = 0; i < durations.size(); i++) {
      result[i] = durations.get(i);
    }
    return result;
  }

  private static int getModeSize(List<List<Integer>> blocks) {
    Map<Integer, Integer> sizeFrequencies = new HashMap<>(blocks.size());
    for (List<Integer> block : blocks) {
      int size = block.size();
      Integer count = sizeFrequencies.get(size);
      sizeFrequencies.put(size, count == null ? 1 : count + 1);
    }
    int modeSize = C.INDEX_UNSET;
    int maxFrequency = C.INDEX_UNSET;
    for (Map.Entry<Integer, Integer> entry : sizeFrequencies.entrySet()) {
      int frequency = entry.getValue();
      int size = entry.getKey();
      if (frequency > maxFrequency || (frequency == maxFrequency && size > modeSize)) {
        maxFrequency = frequency;
        modeSize = size;
      }
    }
    return modeSize;
  }

  private static double getTotalDurationInMinutes(String[] lines) {
    double totalSeconds = 0.0;
    for (String line : lines) {
      if (line.startsWith(TAG_DURATION)) {
        try {
          totalSeconds += parseExtInfDurationSeconds(line);
        } catch (NumberFormatException ignored) {
          // Ignore malformed EXTINF durations. This value is only used for ad heuristics.
        }
      }
    }
    return totalSeconds / 60.0;
  }

  private static double parseExtInfDurationSeconds(String line) {
    int durationStartIndex = TAG_DURATION.length() + 1;
    if (line.length() <= durationStartIndex || line.charAt(TAG_DURATION.length()) != ':') {
      throw new NumberFormatException();
    }
    int durationEndIndex = line.indexOf(',', durationStartIndex);
    return Double.parseDouble(
        durationEndIndex == C.INDEX_UNSET
            ? line.substring(durationStartIndex)
            : line.substring(durationStartIndex, durationEndIndex));
  }

  private static int getMinorityCountThreshold(double totalMinutes) {
    if (totalMinutes <= DURATION_TIER_SHORT) {
      return AD_BREAK_THRESHOLD_SHORT;
    }
    if (totalMinutes <= DURATION_TIER_MEDIUM) {
      return AD_BREAK_THRESHOLD_MEDIUM;
    }
    if (totalMinutes <= DURATION_TIER_LONG) {
      return AD_BREAK_THRESHOLD_LONG;
    }
    return AD_BREAK_THRESHOLD_EXTRA;
  }

  private static List<List<Integer>> getDiscontinuityBlocks(String[] lines) {
    List<List<Integer>> blocks = new ArrayList<>();
    List<Integer> currentBlock = new ArrayList<>();
    int segmentIndex = 0;
    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.equals(TAG_DISCONTINUITY)) {
        if (!currentBlock.isEmpty()) {
          blocks.add(currentBlock);
        }
        currentBlock = new ArrayList<>();
      } else if (isSegmentLine(trimmedLine)) {
        currentBlock.add(segmentIndex++);
      }
    }
    if (!currentBlock.isEmpty()) {
      blocks.add(currentBlock);
    }
    return blocks;
  }

  private static Set<Integer> findAdsByFilename(List<String> allSegments) {
    if (allSegments.size() < 2) {
      Log.d(TAG, "片段數少於 2，略過檔名與路徑分析。");
      return Collections.emptySet();
    }
    Map<String, List<Integer>> structuralGroups =
        groupIndexesBy(allSegments, HlsAdsParser::getStructuralIdentifier);
    Log.d(TAG, "路徑結構分成 " + structuralGroups.size() + " 組。");
    if (structuralGroups.size() > 1 && structuralGroups.size() <= REASONABLE_GROUP_LIMIT) {
      return findMinorityGroup(structuralGroups);
    }
    Log.d(TAG, "路徑結構分組不適合判斷，改用前綴分析。");
    return findAdsByPrefixAnalysis(allSegments);
  }


  /** 返回 null = 认不出锚点（交回原逻辑）；空集 = 认出锚点但结构不干净、不动。 */
  private static Set<Integer> findAdsByAnchor(
      List<String> segments, String[] lines, String playlistUrl) {
    if (TextUtils.isEmpty(playlistUrl)) {
      return null;
    }
    java.net.URI base;
    try {
      base = new java.net.URI(playlistUrl.trim());
    } catch (Exception e) {
      return null;
    }
    String[] baseDir = dirParts(base.getRawPath());
    if (baseDir.length == 0) {
      return null;
    }
    Map<String, List<Integer>> groups = new HashMap<>();
    Map<String, Integer> scores = new HashMap<>();
    for (int i = 0; i < segments.size(); i++) {
      java.net.URI u;
      try {
        u = base.resolve(segments.get(i));
      } catch (Exception e) {
        return null;
      }
      String key = (u.getHost() == null ? "" : u.getHost()) + "|" + dirOf(u.getRawPath());
      List<Integer> g = groups.get(key);
      if (g == null) {
        g = new ArrayList<>();
        groups.put(key, g);
        boolean sameHost = u.getHost() == null || u.getHost().equalsIgnoreCase(base.getHost());
        scores.put(key, sameHost ? sharedLeading(baseDir, dirParts(u.getRawPath())) : 0);
      }
      g.add(i);
    }
    if (groups.size() < 2) {
      return null;
    }
    // 正片家族：与清单自己的目录至少共享两级路径的分片组（同一部片的各码率/分段目录都算）。
    Set<Integer> anchor = new HashSet<>();
    int best = 0;
    for (Map.Entry<String, Integer> e : scores.entrySet()) {
      best = Math.max(best, e.getValue());
      if (e.getValue() >= 2) {
        anchor.addAll(groups.get(e.getKey()));
      }
    }
    if (anchor.isEmpty()) {
      Log.d(TAG, "錨點分析：沒有片段與清單同目錄（best=" + best + "），交回原策略。");
      return null;
    }
    if (anchor.size() == segments.size()) {
      Log.d(TAG, "錨點分析：所有片段都與清單同源，路徑上沒有外來片段。");
      return null;
    }
    // 结构闸：异目录分片必须整块（不连续标签之间）都是异目录，才剔。
    for (List<Integer> block : getDiscontinuityBlocks(lines)) {
      boolean hasAnchor = false;
      boolean hasForeign = false;
      for (int idx : block) {
        if (anchor.contains(idx)) hasAnchor = true; else hasForeign = true;
      }
      if (hasAnchor && hasForeign) {
        Log.d(TAG, "錨點分析：異目錄片段與正片混在同一區塊，結構不乾淨，不動。");
        return Collections.emptySet();
      }
    }
    Set<Integer> ads = new HashSet<>();
    for (int i = 0; i < segments.size(); i++) {
      if (!anchor.contains(i)) ads.add(i);
    }
    Log.d(TAG, "錨點分析：剔除異目錄片段 " + ads.size() + " 個。");
    return ads;
  }

  private static String dirOf(String path) {
    if (path == null) return "";
    int i = path.lastIndexOf('/');
    return i >= 0 ? path.substring(0, i) : "";
  }

  private static String[] dirParts(String path) {
    String d = dirOf(path);
    List<String> out = new ArrayList<>();
    for (String p : d.split("/")) {
      if (!p.isEmpty()) out.add(p);
    }
    return out.toArray(new String[0]);
  }

  private static int sharedLeading(String[] a, String[] b) {
    int n = Math.min(a.length, b.length);
    int i = 0;
    while (i < n && a[i].equals(b[i])) i++;
    return i;
  }

  private static String getStructuralIdentifier(String segmentUrl) {
    int schemeEnd = segmentUrl.indexOf("://");
    if (schemeEnd != C.INDEX_UNSET) {
      int hostEnd = segmentUrl.indexOf('/', schemeEnd + 3);
      return hostEnd != C.INDEX_UNSET ? segmentUrl.substring(0, hostEnd) : segmentUrl;
    }
    int lastSlashIndex = segmentUrl.lastIndexOf('/');
    return lastSlashIndex != C.INDEX_UNSET
        ? segmentUrl.substring(0, lastSlashIndex)
        : DEFAULT_GROUP_IDENTIFIER;
  }

  private static Set<Integer> findMinorityGroup(Map<String, List<Integer>> groups) {
    int totalSize = 0;
    for (List<Integer> segmentIndexes : groups.values()) {
      totalSize += segmentIndexes.size();
    }
    int maxSize = getMaxGroupSize(groups);
    if (maxSize * 2 <= totalSize) {
      Log.d(TAG, "少數群組分析沒有明確主群組（" + maxSize + "/" + totalSize + "），略過。");
      return Collections.emptySet();
    }
    Set<Integer> adSegmentIndexes = new HashSet<>(groups.size());
    for (List<Integer> segmentIndexes : groups.values()) {
      if (segmentIndexes.size() < maxSize) {
        adSegmentIndexes.addAll(segmentIndexes);
      }
    }
    return adSegmentIndexes;
  }

  private static Set<Integer> findAdsByPrefixAnalysis(List<String> segments) {
    int optimalPrefixLength = findOptimalPrefixLength(segments);
    if (optimalPrefixLength == C.INDEX_UNSET) {
      Log.d(TAG, "前綴分析找不到有效前綴長度。");
      return Collections.emptySet();
    }
    Log.d(TAG, "前綴分析選用長度 " + optimalPrefixLength + "。");
    Map<String, List<Integer>> groups =
        groupIndexesBy(segments, segment -> getPrefix(segment, optimalPrefixLength));
    if (groups.size() <= 1 || groups.size() > REASONABLE_GROUP_LIMIT) {
      Log.d(TAG, "前綴分析分成 " + groups.size() + " 組，分組數不適合判斷。");
      return Collections.emptySet();
    }
    return findMinorityGroup(groups);
  }

  private static int findOptimalPrefixLength(List<String> segments) {
    if (segments.size() < 2) {
      return C.INDEX_UNSET;
    }
    int shortestSegmentLength = Integer.MAX_VALUE;
    for (String segment : segments) {
      shortestSegmentLength = Math.min(shortestSegmentLength, segment.length());
    }
    int bestLength = C.INDEX_UNSET;
    double highestScore = 0.0;
    int maxLength = shortestSegmentLength - SEQUENCE_NUMBER_RESERVED_LENGTH;
    for (int length = MIN_PREFIX_LENGTH_TO_TEST; length < maxLength; length++) {
      final int prefixLength = length;
      Map<String, List<Integer>> groups =
          groupIndexesBy(segments, segment -> getPrefix(segment, prefixLength));
      int groupCount = groups.size();
      if (groupCount <= 1 || groupCount > REASONABLE_GROUP_LIMIT) {
        continue;
      }
      int maxGroupSize = getMaxGroupSize(groups);
      double score = (double) maxGroupSize / segments.size();
      if (score >= MIN_MAJORITY_GROUP_RATIO && score > highestScore) {
        highestScore = score;
        bestLength = length;
      }
    }
    return bestLength;
  }

  private static String getPrefix(String segment, int prefixLength) {
    return segment.length() > prefixLength ? segment.substring(0, prefixLength) : segment;
  }

  private static Map<String, List<Integer>> groupIndexesBy(
      List<String> segments, Classifier keyFn) {
    Map<String, List<Integer>> groups = new HashMap<>(segments.size());
    for (int i = 0; i < segments.size(); i++) {
      String key = keyFn.classify(segments.get(i));
      List<Integer> group = groups.get(key);
      if (group == null) {
        group = new ArrayList<>();
        groups.put(key, group);
      }
      group.add(i);
    }
    return groups;
  }

  private static int getMaxGroupSize(Map<String, List<Integer>> groups) {
    int maxSize = 0;
    for (List<Integer> group : groups.values()) {
      maxSize = Math.max(maxSize, group.size());
    }
    return maxSize;
  }

  private static boolean isSegmentLine(String trimmedLine) {
    return !trimmedLine.isEmpty() && !trimmedLine.startsWith("#");
  }

  private static String rebuildM3u8(
      String[] lines, Set<Integer> adSegmentIndexes, int originalLength) {
    List<String> stripped = removeAdSegments(lines, adSegmentIndexes);
    List<String> cleaned = removeOrphanedDiscontinuityTags(stripped);
    Log.d(TAG, "播放清單重建完成，行數由 " + lines.length + " 減少為 " + cleaned.size() + "。");
    StringBuilder builder = new StringBuilder(originalLength);
    for (String line : cleaned) {
      builder.append(line).append("\n");
    }
    return builder.toString();
  }

  private static List<String> removeAdSegments(String[] lines, Set<Integer> adSegmentIndexes) {
    List<String> result = new ArrayList<>(lines.length);
    boolean skippingAdSegment = false;
    int segmentIndex = 0;
    for (String sourceLine : lines) {
      String line = sourceLine.trim();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith(TAG_DURATION)) {
        if (adSegmentIndexes.contains(segmentIndex)) {
          skippingAdSegment = true;
          continue;
        }
      }
      if (isSegmentLine(line)) {
        segmentIndex++;
        if (skippingAdSegment || adSegmentIndexes.contains(segmentIndex - 1)) {
          skippingAdSegment = false;
          continue;
        }
      } else if (skippingAdSegment) {
        continue;
      }
      result.add(line);
    }
    return result;
  }

  private static List<String> removeOrphanedDiscontinuityTags(List<String> lines) {
    List<String> result = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.equals(TAG_DISCONTINUITY)) {
        boolean prevIsDiscontinuityOrBoundary =
            i == 0 || lines.get(i - 1).equals(TAG_DISCONTINUITY);
        String nextLine = i + 1 < lines.size() ? lines.get(i + 1) : null;
        boolean nextIsDiscontinuityOrBoundary =
            nextLine == null || nextLine.equals(TAG_DISCONTINUITY) || nextLine.equals(TAG_ENDLIST);
        if (prevIsDiscontinuityOrBoundary || nextIsDiscontinuityOrBoundary) {
          continue;
        }
      }
      result.add(line);
    }
    return result;
  }

  // ---- 去廣告結果匯總（只記日誌，不參與判定） ----

  private static void logNoStrip(String[] lines, String strategy) {
    if (!Log.on()) return;
    try {
      Log.d(TAG, "去廣告結果［" + (strategy == null ? "各策略均未命中" : strategy) + "］：未剔除，原 "
          + getSegments(lines).size() + " 片全部保留。");
    } catch (Throwable ignored) {
    }
  }

  private static void logStripSummary(
      String[] lines, Set<Integer> ads, String playlistUrl, String strategy) {
    if (!Log.on()) return;
    try {
      java.net.URI base = null;
      try {
        if (!TextUtils.isEmpty(playlistUrl)) base = new java.net.URI(playlistUrl.trim());
      } catch (Exception ignored) {
      }
      String name = strategy == null ? "未知" : strategy;
      int seg = 0;
      int blocks = 0;
      double pending = 0.0;
      double cut = 0.0;
      int runStart = -1;
      int runCount = 0;
      double runDur = 0.0;
      Set<String> runDirs = new LinkedHashSet<>();
      for (String raw : lines) {
        String line = raw.trim();
        if (line.startsWith(TAG_DURATION)) {
          try {
            pending = parseExtInfDurationSeconds(line);
          } catch (NumberFormatException e) {
            pending = 0.0;
          }
          continue;
        }
        if (!isSegmentLine(line)) continue;
        double d = pending;
        pending = 0.0;
        if (ads.contains(seg)) {
          if (runStart < 0) {
            runStart = seg;
            runCount = 0;
            runDur = 0.0;
            runDirs.clear();
          }
          runCount++;
          runDur += d;
          cut += d;
          runDirs.add(segmentDir(base, line));
        } else if (runStart >= 0) {
          blocks++;
          logRun(name, blocks, runStart, runCount, runDur, runDirs);
          runStart = -1;
        }
        seg++;
      }
      if (runStart >= 0) {
        blocks++;
        logRun(name, blocks, runStart, runCount, runDur, runDirs);
      }
      Log.d(TAG, "去廣告結果［" + name + "］：原 " + seg + " 片 → 留 " + (seg - ads.size()) + " 片，剔 "
          + ads.size() + " 片約 " + Util.formatInvariant("%.1f", cut) + " 秒（" + blocks + " 塊）。");
    } catch (Throwable ignored) {
    }
  }

  private static void logRun(
      String strategy, int no, int start, int count, double dur, Set<String> dirs) {
    StringBuilder from = new StringBuilder();
    int shown = 0;
    for (String d : dirs) {
      if (shown == 2) break;
      if (shown > 0) from.append("、");
      from.append(d);
      shown++;
    }
    if (dirs.size() > 2) from.append(" 等 ").append(dirs.size()).append(" 個目錄");
    Log.d(TAG, "去廣告剔除［" + strategy + "］第 " + no + " 塊：第 " + (start + 1) + "–" + (start + count)
        + " 片，共 " + count + " 片約 " + Util.formatInvariant("%.1f", dur) + " 秒，來源 " + from);
  }

  private static String segmentDir(java.net.URI base, String segment) {
    try {
      java.net.URI u = base == null ? new java.net.URI(segment) : base.resolve(segment);
      String host = u.getHost();
      String dir = dirOf(u.getRawPath());
      return (host == null ? "（相對路徑）" : host) + dir + "/";
    } catch (Exception e) {
      int q = segment.indexOf('?');
      return dirOf(q >= 0 ? segment.substring(0, q) : segment) + "/";
    }
  }

  /** 去廣告日誌出口：寫進調試日誌頁（DebugLogStore），調試日誌開關關時不記。 */
  private static final class Log {

    static boolean on() {
      try {
        return com.github.catvod.crawler.DebugLogStore.isEnabled();
      } catch (Throwable t) {
        return false;
      }
    }

    static void d(String tag, String msg) {
      if (!on()) return;
      try {
        com.github.catvod.crawler.DebugLogStore.add(com.github.catvod.crawler.diagnostics.LogCategory.AD, tag, msg, false);
      } catch (Throwable ignored) {
      }
    }
  }

  private interface Classifier {

    String classify(String segment);
  }
}
