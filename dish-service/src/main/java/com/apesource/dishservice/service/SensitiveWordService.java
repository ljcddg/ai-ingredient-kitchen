package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.dto.PageResult;
import com.apesource.dishservice.dto.SensitiveWordRequest;
import com.apesource.dishservice.entity.SensitiveWord;
import com.apesource.dishservice.mapper.SensitiveWordMapper;
import com.apesource.dishservice.moderation.ModerationResult;
import com.apesource.dishservice.moderation.SensitiveWordTrie;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 违规词服务：词库的读写 + 内存字典树 + 文本试跑。
 *
 * <p><b>为什么把词库整个装进内存：</b>
 * 评论发布是用户随时会触发的动作，而词库变更是一天几次的管理动作。
 * 每发一条评论去查一次 {@code t_sensitive_word}，等于用「每天几次的写」
 * 去拖慢「每分钟几次的读」。所以启动时一次性载入，改完立刻重建。
 *
 * <p><b>重建为什么是「换一个新对象」而不是「往旧树里加词」：</b>
 * 字典树一旦在使用中被修改，正在扫描的线程会看到半成品结构
 * （某个词的前缀已经挂上去、但它自己还没标记为完整词），
 * 结果是「刚才还能拦住的词突然拦不住了」。整体替换 + {@code volatile}
 * 让读线程要么看到旧树、要么看到新树，不存在中间态，也不需要加锁。
 * 重建本身用 {@code synchronized} 串行化，避免两个管理员同时改词时互相覆盖。
 *
 * <p><b>表不存在时降级为空词库</b>：没跑 migration 的环境里，
 * 评论功能本身应该照常可用（只是不拦截），而不是整个服务起不来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordService {

    /** 与 {@code t_sensitive_word.word} 的列宽一致 */
    private static final int MAX_WORD_LENGTH = 255;
    /** 与 {@code remark} 的列宽一致 */
    private static final int MAX_REMARK_LENGTH = 255;

    /** 合法类别。新增类别时这里和前端 constants 一起改 */
    private static final Set<String> CATEGORIES =
            Set.of("AD", "PORN", "VIOLENCE", "ILLEGAL", "ABUSE", "OTHER");

    private static final int LEVEL_REPLACE = 1;
    private static final int LEVEL_BLOCK = 2;

    private final SensitiveWordMapper wordMapper;

    /** 当前生效的字典树。整体替换，读路径无锁 */
    private volatile SensitiveWordTrie trie = SensitiveWordTrie.empty();

    /** 当前载入的词数，仅用于管理页展示与日志 */
    private volatile int loadedWordCount = 0;

    @PostConstruct
    public void init() {
        reload();
    }

    // ================================================================== 匹配

    /**
     * 扫描并替换。
     *
     * <p>这个方法会被每条评论调用，所以它<b>不查库、不加锁、不写日志</b>，
     * 只做纯内存计算。
     */
    public ModerationResult filter(String text) {
        return trie.scan(text);
    }

    /**
     * 命中计数落库。
     *
     * <p>失败只记 warn：计数是运营指标，不是业务事实 ——
     * 为了它让一条评论发布失败，属于拿主流程给辅助功能陪葬。
     */
    public void recordHits(List<String> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (String word : hits) {
            try {
                wordMapper.incrementHitCount(word);
            } catch (Exception e) {
                log.warn("违规词命中计数失败：word={}, {}", word, e.getMessage());
            }
        }
    }

    // ================================================================== 检索

    /**
     * 分页检索词库。
     *
     * @param keyword  按词模糊匹配，可空
     * @param category 类别筛选，可空或 ALL
     * @param level    级别筛选，可空
     * @param enabled  启用状态筛选，可空
     */
    public PageResult<SensitiveWord> page(String keyword, String category, Integer level, Integer enabled,
                                         int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));

        var wrapper = Wrappers.<SensitiveWord>lambdaQuery()
                .orderByDesc(SensitiveWord::getHitCount)
                .orderByDesc(SensitiveWord::getCreateTime);

        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.like(SensitiveWord::getWord, kw);
        }
        if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)) {
            wrapper.eq(SensitiveWord::getCategory, category.trim().toUpperCase());
        }
        if (level != null) {
            wrapper.eq(SensitiveWord::getLevel, level);
        }
        if (enabled != null) {
            wrapper.eq(SensitiveWord::getEnabled, enabled);
        }

        Page<SensitiveWord> result = wordMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), safePage, safeSize);
    }

    /**
     * 文本试跑：拿当前内存词库扫一段文字，返回替换结果与命中明细。
     *
     * <p>这是词库管理里最有用的一个功能。管理员新加一个词，光看词表
     * 判断不出「会不会误伤」——必须拿真实语句跑一遍才知道。
     * 它读的是<b>同一棵内存树</b>，所以试跑结果和用户实际看到的完全一致，
     * 不是另写一套正则来「模拟」。
     */
    public Map<String, Object> test(String text) {
        String source = text == null ? "" : text;
        ModerationResult result = filter(source);

        // 命中词 → 它在词库里的类别/级别（试跑要显示"为什么被拦"）
        Map<String, SensitiveWord> byWord = new LinkedHashMap<>();
        if (!result.hits().isEmpty()) {
            List<SensitiveWord> rows = wordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                    .in(SensitiveWord::getWord, result.hits()));
            for (SensitiveWord row : rows) {
                byWord.put(row.getWord(), row);
            }
        }

        List<Map<String, Object>> hits = new ArrayList<>();
        for (String word : result.hits()) {
            SensitiveWord row = byWord.get(word);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("word", word);
            item.put("category", row == null ? "OTHER" : row.getCategory());
            item.put("level", row == null ? LEVEL_REPLACE : row.getLevel());
            hits.add(item);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("original", source);
        view.put("masked", result.text());
        view.put("blocked", result.blocked());
        view.put("hitCount", hits.size());
        view.put("hits", hits);
        return view;
    }

    /** 词库概览：总量 / 启用数 / 各级别分布，管理页顶部展示 */
    public Map<String, Object> overview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("loaded", loadedWordCount);

        List<SensitiveWord> all = wordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                .select(SensitiveWord::getCategory, SensitiveWord::getLevel, SensitiveWord::getEnabled));
        long enabled = all.stream().filter(w -> w.getEnabled() != null && w.getEnabled() == 1).count();
        long blocking = all.stream().filter(w -> w.getLevel() != null && w.getLevel() == LEVEL_BLOCK).count();

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (SensitiveWord word : all) {
            byCategory.merge(word.getCategory() == null ? "OTHER" : word.getCategory(), 1, Integer::sum);
        }

        view.put("total", all.size());
        view.put("enabled", enabled);
        view.put("blocking", blocking);
        view.put("byCategory", byCategory);
        return view;
    }

    // ================================================================== 词库管理

    /** 新增一个词。同一个词只允许存在一条（逻辑删除的那条不算） */
    @Transactional
    public SensitiveWord create(SensitiveWordRequest request) {
        String word = normalizeWord(request == null ? null : request.getWord());

        Long exists = wordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                .eq(SensitiveWord::getWord, word));
        if (exists != null && exists > 0) {
            throw new BusinessException("「" + word + "」已经在词库里了");
        }

        SensitiveWord entity = new SensitiveWord();
        entity.setWord(word);
        entity.setCategory(normalizeCategory(request.getCategory()));
        entity.setLevel(normalizeLevel(request.getLevel()));
        entity.setRemark(trimToNull(request.getRemark(), MAX_REMARK_LENGTH));
        entity.setSource("manual");
        entity.setEnabled(request.getEnabled() == null || request.getEnabled() != 0 ? 1 : 0);
        entity.setHitCount(0);
        wordMapper.insert(entity);

        reload();
        return entity;
    }

    /** 修改。词本身改了也要重新查重 */
    @Transactional
    public SensitiveWord update(Long id, SensitiveWordRequest request) {
        SensitiveWord entity = requireWord(id);

        if (request != null && request.getWord() != null) {
            String word = normalizeWord(request.getWord());
            Long exists = wordMapper.selectCount(Wrappers.<SensitiveWord>lambdaQuery()
                    .eq(SensitiveWord::getWord, word)
                    .ne(SensitiveWord::getId, id));
            if (exists != null && exists > 0) {
                throw new BusinessException("「" + word + "」已经在词库里了");
            }
            entity.setWord(word);
        }
        if (request != null && request.getCategory() != null) {
            entity.setCategory(normalizeCategory(request.getCategory()));
        }
        if (request != null && request.getLevel() != null) {
            entity.setLevel(normalizeLevel(request.getLevel()));
        }
        if (request != null && request.getRemark() != null) {
            entity.setRemark(trimToNull(request.getRemark(), MAX_REMARK_LENGTH));
        }
        if (request != null && request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled() == 0 ? 0 : 1);
        }
        wordMapper.updateById(entity);

        reload();
        return entity;
    }

    /**
     * 停用 / 启用。
     *
     * <p>比起删除，更推荐停用：{@code t_dish_comment.hit_words} 里存着
     * 历史命中的词，删掉词条之后那些记录就成了「查不到原因的黑历史」。
     */
    @Transactional
    public SensitiveWord setEnabled(Long id, boolean enabled) {
        SensitiveWord entity = requireWord(id);
        entity.setEnabled(enabled ? 1 : 0);
        wordMapper.updateById(entity);
        reload();
        return entity;
    }

    /** 逻辑删除 */
    @Transactional
    public void delete(Long id) {
        requireWord(id);
        wordMapper.deleteById(id);
        reload();
    }

    /**
     * 按来源批量停用。
     *
     * <p>批量导入必然混进误伤词。发现某批词不该进库时，
     * 「按 source 一次性停用」比在列表里一条条点要可靠得多 ——
     * 后者一定会漏，而漏掉的词会继续把正常评论打成星号。
     */
    @Transactional
    public int disableBySource(String source) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty()) {
            throw new BusinessException("缺少来源标识");
        }
        SensitiveWord update = new SensitiveWord();
        update.setEnabled(0);
        int rows = wordMapper.update(update, Wrappers.<SensitiveWord>lambdaUpdate()
                .eq(SensitiveWord::getSource, value)
                .eq(SensitiveWord::getEnabled, 1));
        reload();
        return rows;
    }

    // ================================================================== 缓存

    /**
     * 重建字典树。
     *
     * <p>任何异常都不外抛：词库读失败时保留上一版（或空树），
     * 评论功能继续可用。这里是「降级优于失败」的少数正当场合 ——
     * 拦截是保护措施，保护措施自己挂了不该把被保护的东西一起拖下水。
     */
    public synchronized int reload() {
        try {
            List<SensitiveWord> words = wordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                    .eq(SensitiveWord::getEnabled, 1)
                    .select(SensitiveWord::getId, SensitiveWord::getWord, SensitiveWord::getLevel));

            SensitiveWordTrie rebuilt = SensitiveWordTrie.empty();
            for (SensitiveWord word : words) {
                rebuilt.add(word.getWord(), word.getLevel() == null ? LEVEL_REPLACE : word.getLevel());
            }
            trie = rebuilt;
            loadedWordCount = words.size();
            log.info("违规词库已载入 {} 条", loadedWordCount);
            return loadedWordCount;
        } catch (Exception e) {
            log.warn("违规词库载入失败，本次沿用上一版（{} 条）：{}", loadedWordCount, e.getMessage());
            return loadedWordCount;
        }
    }

    public int loadedWordCount() {
        return loadedWordCount;
    }

    // ================================================================== 工具

    private SensitiveWord requireWord(Long id) {
        if (id == null) {
            throw new BusinessException("缺少词条 ID");
        }
        SensitiveWord entity = wordMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("这个词条不存在或已被删除");
        }
        return entity;
    }

    /**
     * 规范化：去空白 + 英文小写 + 去掉干扰符。
     *
     * <p>词库里存「紧凑形式」：匹配时原文也会先紧凑再比对，
     * 两边口径必须一致 —— 否则录进来的「加 微 信」永远匹配不到「加微信」。
     */
    private String normalizeWord(String raw) {
        String word = raw == null ? "" : raw.trim();
        if (word.isEmpty()) {
            throw new BusinessException("违规词不能为空");
        }
        // 与 SensitiveWordTrie 的 NOISE 保持一致：库里存紧凑形式，运行时两边都紧凑
        String compact = word.replaceAll("[\\s*_\\-·.。~～+｜|/\\\\]+", "");
        if (compact.isEmpty()) {
            throw new BusinessException("违规词不能只由空格或符号组成");
        }
        if (compact.length() > MAX_WORD_LENGTH) {
            throw new BusinessException("违规词最多 " + MAX_WORD_LENGTH + " 个字符");
        }
        return compact.toLowerCase();
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "OTHER";
        }
        String value = category.trim().toUpperCase();
        if (!CATEGORIES.contains(value)) {
            throw new BusinessException("类别只能是 " + String.join(" / ", new LinkedHashSet<>(CATEGORIES)));
        }
        return value;
    }

    private int normalizeLevel(Integer level) {
        if (level == null) {
            return LEVEL_REPLACE;
        }
        if (level != LEVEL_REPLACE && level != LEVEL_BLOCK) {
            throw new BusinessException("处置级别只能是 1（替换）或 2（整条拦截）");
        }
        return level;
    }

    private String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
