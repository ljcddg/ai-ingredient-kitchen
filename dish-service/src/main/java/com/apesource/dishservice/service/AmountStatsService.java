package com.apesource.dishservice.service;

import com.apesource.dishservice.dto.AmountStatRow;
import com.apesource.dishservice.dto.QuantityStatRow;
import com.apesource.dishservice.mapper.AmountStatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用量分位统计的出口 —— 把两张离线统计表带给 Python 校验器。
 *
 * <p><b>为什么放在"每请求快照"里、而不是让 Python 去读文件或连库：</b>
 * 校验器（{@code ai_service/validator.py}）是唯一的使用方，它在 Python 侧跑。
 * ai-service 有一条铁律：不碰业务库。于是数据只有两条路过去 ——
 * 随请求注入，或导出成静态 JSON。选前者，因为统计表是<b>菜的派生数据</b>：
 * 新菜一发布，分位数就变了；静态 JSON 得靠人记得重跑脚本，一旦忘了，
 * 校验器就会拿上个月的口径判这个月的菜，而且错得没有任何声响。
 *
 * <p><b>为什么每次查库而不是缓存：</b>全表 268 + 19 行，一次查询 2ms 量级，
 * 而这次请求背后是一轮 12~26 秒的模型推理 —— 这点开销可以忽略；
 * 换掉的是"缓存什么时候失效"这一整类问题（脚本刚重建完，缓存还是旧的）。
 *
 * <p><b>失败必须降级而不是抛：</b>统计表是<b>增强</b>，不是生成的前提。
 * 没建表、脚本没跑过，校验器会自动回退到类别级、再回退到固定上限，
 * 生成照常能用 —— 这条链路不能因为一张可选的派生表而整体不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmountStatsService {

    private final AmountStatsMapper mapper;

    /** 只告警一次：表不存在会很稳定地每次都失败，刷屏反而掩盖别的日志 */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /** 单品级用量分位（食材 ID × 人数档） */
    public List<AmountStatRow> amountStats() {
        try {
            return mapper.selectAmountStats();
        } catch (Exception e) {
            warnOnce("单品用量分位读取失败（校验器将回退到类别级）", e);
            return List.of();
        }
    }

    /** 类别级用量分位（类目 × 人数档） */
    public List<QuantityStatRow> quantityStats() {
        try {
            return mapper.selectQuantityStats();
        } catch (Exception e) {
            warnOnce("类别用量分位读取失败（校验器将回退到固定上限）", e);
            return List.of();
        }
    }

    private void warnOnce(String what, Exception e) {
        if (warned.compareAndSet(false, true)) {
            log.warn("{}：{}（后续同类失败不再重复告警）", what, e.getMessage());
        }
    }
}
