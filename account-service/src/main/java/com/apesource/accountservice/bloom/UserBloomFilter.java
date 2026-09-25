package com.apesource.accountservice.bloom;

import com.apesource.accountservice.entity.User;
import com.apesource.accountservice.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户名布隆过滤器。
 * <p>
 * <b>作用</b>：挡掉「查询一个肯定不存在的用户名」的请求，减少数据库压力（防缓存穿透）。
 * <p>
 * <b>关键语义（必须理解，否则会用错）</b>：
 * <ul>
 *   <li>{@code contains} 返回 <b>false</b> → 该用户名 <b>100% 不存在</b>，可以直接放行／拒绝</li>
 *   <li>{@code contains} 返回 <b>true</b>  → 只能说明「<b>可能存在</b>」，有误判率，
 *       <b>必须再查一次数据库确认</b></li>
 * </ul>
 * 所以本类只做「快速否定」，真相永远以数据库为准。误判的代价仅仅是多查一次库，
 * 而命中「不存在」时省掉的正是那次库查询 —— 这才是布隆过滤器的价值。
 * <p>
 * <b>为什么选 Redisson 的 RBloomFilter</b>：位数组存在 Redis 上，多实例共享、重启不丢；
 * 底层用 Redis 的 SETBIT/GETBIT 实现，无需自己实现哈希函数与位运算。
 *
 * <h2>可用性设计：Redis 不可用时降级，而不是让服务挂掉</h2>
 * 布隆过滤器本质是<b>性能优化</b>（防缓存穿透），不是<b>正确性依赖</b> ——
 * 没有它，直接查库一样能得到正确答案，只是慢一点。所以它的故障不该拖垮整个账号服务。
 * 为此这里做了四件事：
 * <ol>
 *   <li><b>读失败 → 返回 true</b>（{@link #mightContain}）：当作「可能存在」，交给数据库判定。
 *       因为布隆只有「说 false 才可信」，它一旦不可用，就<b>没有资格给出否定结论</b>。
 *       要是这里图省事返回 false，真实用户会被判定成「不存在」而登不进来。</li>
 *   <li><b>写失败 → 只记账不回滚</b>（{@link #add}）：注册成功后写布隆失败只记日志 + 暂存待补，
 *       <b>绝不让注册流程失败</b>（否则调用方的 {@code @Transactional} 会连用户数据一起回滚）。</li>
 *   <li><b>有欠账就不信任否定</b>：{@link #pendingWrites} 非空说明布隆可能不完整，
 *       此时 {@link #mightContain} 一律返回 true —— 宁可都去查库，也不能误拒真实用户。</li>
 *   <li><b>启动预热失败不影响启动</b>（{@link #run}），配合 Redisson 懒初始化，
 *       Redis 挂掉时账号服务照样能起来、能注册登录。</li>
 * </ol>
 *
 * <h2>重启场景的第二道兜底</h2>
 * {@link #pendingWrites} 放在内存里，服务一重启就丢。所以光有它还不够 ——
 * 若「Redis 故障 → 期间有注册 → 服务重启」三件事撞在一起，那批用户名就补不回来了，
 * 而它们会被误判为「不存在」导致用户登不进来。
 * <p>
 * 因此 {@link #warmUp} 不满足于「Redis 里有数据就跳过」，而是把布隆的元素估算数和
 * 数据库记录数做对比：<b>估算数明显少于记录数就判定为不完整，重新灌一遍</b>
 * （{@code add} 幂等，重复灌没有副作用）。这样上面那种组合场景也能自动收敛。
 * <p>
 * 生产环境数据量大时，全量对比 / 补齐会拖慢启动，可改为把待补记录落库，
 * 或交由离线任务定期重建。
 */
@Component
public class UserBloomFilter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBloomFilter.class);

    /** Redis 中该布隆过滤器的 key */
    private static final String FILTER_NAME = "bloom:user:username";

    /**
     * 预估元素数量。
     * <p>
     * 这个值决定位数组大小。宁可估大不可估小 —— 估小了误判率会急剧上升。
     * 按 100 万用户预估，误判率 1% 时位数组约 1.14MB，成本极低。
     */
    private static final long EXPECTED_INSERTIONS = 1_000_000L;

    /**
     * 可接受的误判率。
     * <p>
     * 0.01 表示「说存在但其实不存在」的概率不超过 1%。
     * 因为存在时会再查库确认，这个误判只会导致多一次查询，没有正确性风险。
     */
    private static final double FALSE_PROBABILITY = 0.01D;

    /**
     * Redis 出问题后的「冷静期」时长。
     * <p>
     * 没有它的话，Redis 挂掉期间每个登录请求都会去尝试连接、白等一次超时，
     * 把接口响应时间拖长。这里失败后 30 秒内直接走降级路径，不再碰 Redis。
     */
    private static final long DEGRADE_COOLDOWN_MS = 30_000L;

    private final RedissonClient redissonClient;

    /**
     * 这里注入 Mapper 而不是 Service，是为了避免循环依赖：
     * UserServiceImpl 需要注入本类做布隆判断，若本类反过来注入 UserService 就成环了。
     * Mapper 层没有这层依赖，天然安全。
     */
    private final UserMapper userMapper;

    /** 是否在启动时全量预热，可在配置里关闭（如大数据量时改用离线任务） */
    @Value("${bloom.user.warm-up:true}")
    private boolean warmUpEnabled;

    private RBloomFilter<String> bloomFilter;

    /**
     * 降级冷静期截止时间戳（毫秒）。当前时间小于它 = 处于冷静期，不去碰 Redis。
     */
    private volatile long degradeUntil = 0L;

    /**
     * 待补写的用户名：Redis 不可用期间注册成功、但没能写进布隆的账号。
     * <p>
     * <b>非空就代表布隆可能不完整</b>，此时不能相信它的否定结论（见 {@link #mightContain}）。
     * 用 Set 而不是 List：同一个用户名重复补写没有意义，布隆本身也允许重复 add。
     */
    private final Set<String> pendingWrites = ConcurrentHashMap.newKeySet();

    public UserBloomFilter(RedissonClient redissonClient, UserMapper userMapper) {
        this.redissonClient = redissonClient;
        this.userMapper = userMapper;
    }

    /**
     * 获取布隆过滤器实例，懒初始化 + 双检锁。
     * <p>
     * 用 {@code tryInit} 而不是 {@code init}：{@code tryInit} 在 key 已存在时不报错，
     * 多个实例并发初始化时只有一个真正生效，避免分布式环境下的竞态。
     * <p>
     * Redis 不可用时本方法会抛异常，且 {@code bloomFilter} 保持为 null —— 这是刻意的，
     * 下次调用会重新尝试初始化，Redis 恢复后能自动接上。
     * 异常由调用方（{@link #mightContain}／{@link #add}／{@link #warmUp}）负责降级。
     */
    public RBloomFilter<String> getFilter() {
        if (bloomFilter == null) {
            synchronized (this) {
                if (bloomFilter == null) {
                    RBloomFilter<String> filter = redissonClient.getBloomFilter(FILTER_NAME);
                    // 返回 true 表示本次完成了初始化；false 表示 Redis 里已存在，复用它
                    boolean created = filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
                    log.info("布隆过滤器[{}] {}", FILTER_NAME, created ? "初始化完成" : "已存在，复用");
                    bloomFilter = filter;
                }
            }
        }
        return bloomFilter;
    }

    /**
     * 添加一个用户名到布隆过滤器。
     * <p>
     * 注册成功后必须调用，否则该用户会被误判为「不存在」。
     * <p>
     * <b>写失败不影响注册结果</b>：Redis 不可用时只把用户名暂存到 {@link #pendingWrites}，
     * 等它恢复后由 {@link #flushPendingWrites} 补上。
     * 这里绝不能抛异常 —— 调用方 {@code UserServiceImpl.register} 带 {@code @Transactional}，
     * 抛出去会把已经写好的用户数据一起回滚掉。
     *
     * @param username 用户名
     */
    public void add(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        if (isDegraded()) {
            // 冷静期内不再尝试连 Redis，先记账，等恢复后补
            pendingWrites.add(username);
            return;
        }

        try {
            RBloomFilter<String> filter = getFilter();
            filter.add(username);
            // 顺手把故障期间欠下的也补上
            flushPendingWrites(filter);
        } catch (Exception e) {
            pendingWrites.add(username);
            enterDegrade(e, "写入");
        }
    }

    /**
     * 判断用户名是否「可能存在」。
     * <p>
     * <b>返回 false 可以完全信任；返回 true 必须再查库确认。</b>
     * <p>
     * <b>降级行为</b>：读不到（Redis 不可用）或布隆可能不完整时，一律返回 <b>true</b>。
     * 这样调用方会去查数据库，结论依然正确，代价只是失去「快速否定」的加速。
     *
     * @param username 用户名
     * @return false 表示一定不存在；true 表示可能存在（含降级场景）
     */
    public boolean mightContain(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        // 布隆可能不完整（故障期间有注册没写进来）→ 不能相信它的否定结论
        if (!pendingWrites.isEmpty()) {
            return true;
        }

        if (isDegraded()) {
            return true;
        }

        try {
            RBloomFilter<String> filter = getFilter();
            flushPendingWrites(filter);
            return filter.contains(username);
        } catch (Exception e) {
            // 关键：读不到就说「可能存在」，让调用方去查库。
            // 返回 false 会让真实用户被判定成「不存在」，那是正确性事故
            enterDegrade(e, "查询");
            return true;
        }
    }

    /**
     * 删除布隆过滤器（仅用于重建场景）。
     * <p>
     * 布隆过滤器<b>不支持删除单个元素</b> —— 把某一位清 0 会影响其他元素的判断，
     * 所以「删除用户」时不能从布隆里移除，只能容忍它继续存在（代价是多查一次库）。
     * 真要清理只能整体重建。
     */
    public void clear() {
        try {
            getFilter().delete();
        } catch (Exception e) {
            log.warn("删除布隆过滤器[{}] 失败（Redis 可能不可用）：{}", FILTER_NAME, e.toString());
        } finally {
            // 无论删没删成功，本地的缓存引用和欠账都清掉，下次重新初始化
            bloomFilter = null;
            pendingWrites.clear();
            log.warn("布隆过滤器[{}] 已删除，下次访问会重新初始化", FILTER_NAME);
        }
    }

    /**
     * 是否处于降级冷静期。
     */
    private boolean isDegraded() {
        return System.currentTimeMillis() < degradeUntil;
    }

    /**
     * 进入降级冷静期并告警。
     */
    private void enterDegrade(Exception e, String action) {
        degradeUntil = System.currentTimeMillis() + DEGRADE_COOLDOWN_MS;
        log.warn("布隆过滤器[{}] {}失败，未来 {} 秒降级为直查数据库（原因：{}）",
                FILTER_NAME, action, DEGRADE_COOLDOWN_MS / 1000, e.toString());
    }

    /**
     * 把故障期间欠下的用户名补写进布隆。
     * <p>
     * 在每次成功拿到 filter 之后顺手调用，不需要额外的定时任务 ——
     * 只要 Redis 恢复后有任何一个请求进来，欠账就会被补上。
     * <p>
     * 补完（{@link #pendingWrites} 清空）之后，{@link #mightContain} 就又能信任否定结论了。
     */
    private void flushPendingWrites(RBloomFilter<String> filter) {
        if (pendingWrites.isEmpty()) {
            return;
        }
        int done = 0;
        for (String name : pendingWrites) {
            // 先 add 成功再移除，避免 add 抛异常时把没写进去的名字丢了
            filter.add(name);
            pendingWrites.remove(name);
            done++;
        }
        if (done > 0) {
            log.info("布隆过滤器[{}] 补写 {} 个故障期间漏掉的用户名，已恢复完整", FILTER_NAME, done);
        }
    }

    /**
     * 应用启动后全量预热：把数据库里已有的用户名写进布隆过滤器。
     * <p>
     * 不预热的话，存量用户会被误判为「不存在」，历史上已有的账号就查不到了。
     * <p>
     * <b>生产环境注意</b>：数据量大时（百万级以上）这种同步全量加载会拖慢启动，
     * 应改为「离线任务构建 + 启动只加载一次」或分批异步加载。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!warmUpEnabled) {
            log.info("布隆过滤器预热已关闭（bloom.user.warm-up=false）");
            return;
        }

        // 预热失败不能让应用起不来 —— 布隆只是加速手段，没有它注册登录照样可用
        try {
            warmUp();
        } catch (Exception e) {
            enterDegrade(e, "预热");
            log.warn("布隆过滤器[{}] 预热失败，服务仍会正常启动；"
                    + "期间注册/登录走直查数据库，Redis 恢复后自动重试", FILTER_NAME, e);
        }
    }

    /**
     * 全量预热的具体实现，异常交给 {@link #run} 统一处理。
     */
    private void warmUp() {
        long start = System.currentTimeMillis();

        RBloomFilter<String> filter = getFilter();

        /*
         * 判断布隆是否「完整」，决定要不要重新灌一遍。
         *
         * 不能只看 filter.count() > 0 就跳过 —— 那会漏掉一种情况：
         * Redis 只是网络不通（数据还在），此期间有新用户注册、写布隆失败，
         * 随后服务重启，内存里的 pendingWrites 丢了。
         * 这时 Redis 里 count() > 0，一跳过，那几个用户就永远不在布隆里，
         * 登录时被误判成「不存在」而登不进来 —— 这是正确性事故。
         *
         * 所以把布隆的元素估算数和数据库记录数比一下：
         * 正常情况两者接近（估算值通常略高于实际，因为位冲突会拉高估值）；
         * 估算数明显少于库里记录数，就说明有漏，必须补齐。
         * 上面的 add 是幂等的，重复灌一遍没有副作用。
         */
        Long total = userMapper.selectCount(null);
        long dbCount = total == null ? 0L : total;
        long count = filter.count();

        if (count > 0 && count >= dbCount) {
            log.info("布隆过滤器[{}] 已有 {} 个元素（库中 {} 条），跳过预热", FILTER_NAME, count, dbCount);
            return;
        }
        if (count > 0) {
            log.warn("布隆过滤器[{}] 元素数 {} 少于库中记录数 {}，判定为不完整，开始全量补齐",
                    FILTER_NAME, count, dbCount);
        }

        // 只查 username 一列，避免把整表字段都捞回来
        List<User> users = userMapper.selectList(
                Wrappers.<User>lambdaQuery().select(User::getUsername));
        for (User user : users) {
            filter.add(user.getUsername());
        }

        log.info("布隆过滤器[{}] 预热完成：加载 {} 个用户名，耗时 {} ms",
                FILTER_NAME, users.size(), System.currentTimeMillis() - start);
    }
}
