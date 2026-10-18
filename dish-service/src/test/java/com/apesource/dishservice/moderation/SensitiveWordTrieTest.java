package com.apesource.dishservice.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 违规词字典树的单元测试。
 *
 * <p>纯内存测试，不起 Spring、不连库 —— 匹配算法是这一整套功能里
 * 唯一「错了也不会报错、只会静默放过」的部分：漏一个词没人发现，
 * 误伤一个正常词却会被用户投诉。所以它的边界必须逐个钉死。
 *
 * <p>覆盖的场景：
 * 最长匹配（短词不能把长词切两半）、不重叠替换、插空格/符号绕过、
 * 英文大小写、无命中、整条拦截级别、空输入与空词库。
 */
class SensitiveWordTrieTest {

    private static SensitiveWordTrie trie(String... words) {
        SensitiveWordTrie trie = SensitiveWordTrie.empty();
        for (String word : words) {
            trie.add(word, 1);
        }
        return trie;
    }

    @Test
    @DisplayName("命中即替换成等长星号，其余字符原样保留")
    void replacesWithEqualLengthMask() {
        SensitiveWordTrie trie = trie("加微信");
        ModerationResult result = trie.scan("想学的加微信联系我");

        assertThat(result.text()).isEqualTo("想学的***联系我");
        assertThat(result.hits()).containsExactly("加微信");
        assertThat(result.blocked()).isFalse();
        assertThat(result.masked()).isTrue();
    }

    @Test
    @DisplayName("最长匹配优先：有「加微」和「加微信」时命中长的")
    void prefersLongestMatch() {
        SensitiveWordTrie trie = trie("加微", "加微信");
        ModerationResult result = trie.scan("加微信聊");

        assertThat(result.text()).isEqualTo("***聊");
        assertThat(result.hits()).containsExactly("加微信");
    }

    @Test
    @DisplayName("多个命中互不重叠，各替换各的")
    void handlesMultipleNonOverlappingHits() {
        SensitiveWordTrie trie = trie("骗子", "加微信");
        ModerationResult result = trie.scan("骗子，加微信");

        // 原文 6 字：骗子(2) + 逗号(1) + 加微信(3)，逗号不是干扰符所以保留
        assertThat(result.text()).isEqualTo("**，***");
        assertThat(result.hits()).containsExactly("骗子", "加微信");
    }

    @Test
    @DisplayName("插空格绕过：加 微 信 也要命中，词内空格一起盖掉")
    void catchesSpaceObfuscation() {
        SensitiveWordTrie trie = trie("加微信");
        ModerationResult result = trie.scan("请加 微 信 详聊");

        // 「加 微 信」在原文里占 5 个字符，所以是 5 个星号；
        // 词尾那个空格是正常分隔符，保留 —— 吃掉它会把句子粘起来（"***详聊"）
        assertThat(result.text()).isEqualTo("请***** 详聊");
        assertThat(result.hits()).containsExactly("加微信");
    }

    @Test
    @DisplayName("插符号绕过：加*微*信 命中")
    void catchesSymbolObfuscation() {
        SensitiveWordTrie trie = trie("加微信");
        ModerationResult result = trie.scan("加*微*信");

        assertThat(result.text()).isEqualTo("*****");
        assertThat(result.hits()).containsExactly("加微信");
    }

    @Test
    @DisplayName("英文不区分大小写，替换长度按原文算")
    void caseInsensitiveForAscii() {
        SensitiveWordTrie trie = trie("wechat");
        ModerationResult result = trie.scan("Add my WECHAT please");

        assertThat(result.text()).isEqualTo("Add my ****** please");
        assertThat(result.hits()).containsExactly("wechat");
    }

    @Test
    @DisplayName("没有命中时原文一字不改")
    void keepsTextWhenNoHit() {
        SensitiveWordTrie trie = trie("加微信");
        ModerationResult result = trie.scan("这道菜的火候真不错");

        assertThat(result.text()).isEqualTo("这道菜的火候真不错");
        assertThat(result.hits()).isEmpty();
        assertThat(result.blocked()).isFalse();
        assertThat(result.masked()).isFalse();
    }

    @Test
    @DisplayName("命中拦截级（level=2）时标记 blocked，但仍然替换")
    void marksBlockedForLevelTwo() {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        trie.add("博彩", 2);

        ModerationResult result = trie.scan("推荐一个博彩网站");

        assertThat(result.text()).isEqualTo("推荐一个**网站");
        assertThat(result.blocked()).isTrue();
        // 拦截级也要替换：万一这条被放行，展示出去的也不该是原词
        assertThat(result.hits()).containsExactly("博彩");
    }

    @Test
    @DisplayName("命中去重：同一个词出现三次，hits 里只有一条")
    void deduplicatesHits() {
        SensitiveWordTrie trie = trie("骗子");
        ModerationResult result = trie.scan("骗子骗子骗子");

        assertThat(result.text()).isEqualTo("******");
        assertThat(result.hits()).hasSize(1);
    }

    @Test
    @DisplayName("空文本、null、纯干扰符都不会炸")
    void handlesEmptyInputs() {
        SensitiveWordTrie trie = trie("加微信");

        assertThat(trie.scan("").text()).isEmpty();
        assertThat(trie.scan(null).text()).isNull();
        assertThat(trie.scan("   ").text()).isEqualTo("   ");
        assertThat(trie.scan("***").text()).isEqualTo("***");
    }

    @Test
    @DisplayName("空词库直接返回原文")
    void emptyTrieReturnsTextAsIs() {
        SensitiveWordTrie trie = SensitiveWordTrie.empty();

        assertThat(trie.isEmpty()).isTrue();
        ModerationResult result = trie.scan("加微信");
        assertThat(result.text()).isEqualTo("加微信");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    @DisplayName("入库时词本身带空格，也要能匹配紧凑写法")
    void normalizesSpaceInWordItself() {
        SensitiveWordTrie trie = trie("加 微信");
        ModerationResult result = trie.scan("加微信");

        assertThat(result.text()).isEqualTo("***");
    }

    @Test
    @DisplayName("只由干扰符组成的词不算词，不会把整段文本刷成星号")
    void ignoresNoiseOnlyWord() {
        SensitiveWordTrie trie = new SensitiveWordTrie();
        trie.add("***", 1);
        trie.add("   ", 1);

        assertThat(trie.isEmpty()).isTrue();
        assertThat(trie.scan("正常评论").text()).isEqualTo("正常评论");
    }
}
