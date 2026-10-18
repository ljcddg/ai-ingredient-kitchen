package com.apesource.dishservice.moderation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 违规词字典树（Trie），单次扫描完成整段文本的检测与替换。
 *
 * <p><b>为什么不用 {@code String.contains} 循环一遍词库：</b>
 * 词库有 N 个词、评论长度 L，逐个 {@code contains} 是 O(N·L)；
 * 词库涨到几千条时，一条 500 字评论要做几十万次字符比较，而且每来一条评论都得重来。
 * 字典树把「词库」压成一棵字符树，扫描时从每个起点沿着树往下走，
 * 一次遍历就能找出所有命中，复杂度降到 O(L·最大词长)，与词库大小基本无关。
 *
 * <p><b>三件必须做对的事：</b>
 * <ol>
 *   <li><b>最长匹配优先。</b>词库同时有「加微」和「加微信」时，命中的应该是长的那个 ——
 *       否则短词先命中，会把长词切成两半，替换后残留半截可读内容。</li>
 *   <li><b>命中区间不重叠。</b>替换过的区间直接跳过，避免同一个位置被反复覆盖。</li>
 *   <li><b>插字符绕过。</b>「加 微 信」「加*微*信」是最常见的规避手法。
 *       扫描前先把空白和常见分隔符抽掉、在原字符串上做一份「有效字符 → 原始下标」的映射，
 *       命中后按原始下标回填星号，空格也一起盖掉（留着空格等于告诉别人原词几个字）。</li>
 * </ol>
 *
 * <p><b>线程安全：</b>构建完成后只读。词库变更时由
 * {@link com.apesource.dishservice.service.SensitiveWordService} 整体换一个新实例
 * （{@code volatile} 引用），读路径完全无锁 —— 评论发布是高频操作，
 * 不该为了偶尔一次的词库改动去抢锁。
 *
 * <p>本类不依赖 Spring、不碰数据库，纯内存结构，所以可以直接写单元测试。
 */
public final class SensitiveWordTrie {

    /** 替换字符。用等长星号而不是固定两个：不泄露原词长度以外的信息，观感也自然 */
    public static final char MASK = '*';

    /** 级别 2：整条拦截待审（违法类内容，不该只替换了事） */
    public static final int LEVEL_BLOCK = 2;

    /** 匹配时忽略的干扰字符：空白 + 常见符号，用于识别「加 微 信」「加*微*信」 */
    private static final String NOISE = " \t\r\n\u3000*-_·.。~～+｜|/\\";

    private static final class Node {
        private Map<Character, Node> children;
        /** 非空表示这里是一个完整的词 */
        private String word;
        /** 该词的处置级别 */
        private int level;
    }

    private final Node root = new Node();

    /** 空树：词库为空时的状态，任何文本都算干净 */
    public static SensitiveWordTrie empty() {
        return new SensitiveWordTrie();
    }

    /**
     * 加入一个词。空词、纯干扰符的「词」直接忽略 ——
     * 它们会让扫描进入无意义的替换分支。
     *
     * @param word  违规词，英文按小写存
     * @param level 处置级别，1=替换，2=整条拦截
     */
    public void add(String word, int level) {
        if (word == null) {
            return;
        }
        String normalized = compact(word);
        if (normalized.isEmpty()) {
            return;
        }
        Node node = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (node.children == null) {
                node.children = new HashMap<>(4);
            }
            node = node.children.computeIfAbsent(c, k -> new Node());
        }
        node.word = normalized;
        node.level = level >= LEVEL_BLOCK ? LEVEL_BLOCK : 1;
    }

    /** 树里一个词都没有时，扫描可以整段跳过 */
    public boolean isEmpty() {
        return root.children == null;
    }

    /**
     * 扫描并替换。
     *
     * @param text 原文
     * @return 替换后的文本 + 命中的词 + 是否需要整条拦截
     */
    public ModerationResult scan(String text) {
        if (text == null || text.isEmpty() || isEmpty()) {
            return ModerationResult.clean(text);
        }

        int length = text.length();

        // ---- 1. 抽出「有效字符」，同时记住它在原文里的下标 ----
        char[] effective = new char[length];
        int[] originIndex = new int[length];
        int effectiveLength = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (NOISE.indexOf(c) >= 0) {
                continue;
            }
            effective[effectiveLength] = Character.toLowerCase(c);
            originIndex[effectiveLength] = i;
            effectiveLength++;
        }
        if (effectiveLength == 0) {
            return ModerationResult.clean(text);
        }

        // ---- 2. 在有效字符序列上做最长匹配 ----
        char[] output = text.toCharArray();
        List<String> hits = new ArrayList<>(4);
        boolean blocked = false;

        int i = 0;
        while (i < effectiveLength) {
            Node node = root;
            String matchedWord = null;
            int matchedLevel = 0;
            int matchedEnd = -1;

            for (int j = i; j < effectiveLength; j++) {
                if (node.children == null) {
                    break;
                }
                node = node.children.get(effective[j]);
                if (node == null) {
                    break;
                }
                if (node.word != null) {
                    matchedWord = node.word;
                    matchedLevel = node.level;
                    matchedEnd = j;
                }
            }

            if (matchedWord == null) {
                i++;
                continue;
            }

            // ---- 3. 按原文下标回填星号（被跳过的干扰符一起盖掉）----
            int from = originIndex[i];
            int to = originIndex[matchedEnd] + 1;
            for (int k = from; k < to; k++) {
                output[k] = MASK;
            }
            if (!hits.contains(matchedWord)) {
                hits.add(matchedWord);
            }
            if (matchedLevel >= LEVEL_BLOCK) {
                blocked = true;
            }
            i = matchedEnd + 1;
        }

        return new ModerationResult(new String(output), Collections.unmodifiableList(hits), blocked);
    }

    /** 统一小写并抽掉干扰符，用于入库前的词规范化 */
    private static String compact(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (NOISE.indexOf(c) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
