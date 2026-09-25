package com.apesource.dishservice.es;

import com.apesource.dishservice.config.EsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL → ES 的写入端。
 *
 * <p>和 {@link DishSearchRepository} 共用同一个 {@code RestClient}，
 * 一个负责写、一个负责读，分开是为了让"读路径 ES 挂了要降级"这条规则
 * 只写在读端，不会因为写端也要兜异常而互相干扰。
 *
 * <p><b>三个刻意的设计：</b>
 * <ol>
 *   <li>{@code _id} 直接用菜品雪花 ID —— 重复写会覆盖同一条，
 *       <b>天然幂等</b>。定时全量重建、事件重投都不会写出重复数据，
 *       不需要额外做去重。</li>
 *   <li>写失败只记 warn 返回 false，<b>不抛异常</b>。调用它的是"发布成功之后"，
 *       这时候业务数据已经落库并且提交了，为了 ES 写失败去抛异常只会误导调用方
 *       （以为发布失败，其实已经成功了）。索引落后由定时全量兜底。</li>
 *   <li>mapping 只有一份，放在 {@code src/main/resources/es/dish-index.json}，
 *       建索引脚本读的也是它。两处各存一份 mapping 是"改了没生效"的经典来源。</li>
 * </ol>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DishIndexWriter {

    /** 索引结构定义的唯一来源（同时被 es/init-dish-index.py 读取） */
    public static final String MAPPING_LOCATION = "es/dish-index.json";

    private final RestClient esRestClient;

    private final EsProperties properties;

    private final ObjectMapper objectMapper;

    /**
     * 确保索引存在。
     *
     * <p>为什么要在应用里做这件事，而不是只靠初始化脚本：
     * 索引被误删、或者换了一台 ES 忘了跑脚本时，写入会一直 404 而搜索结果一直是空。
     * 这两种情况下日志上只有一句 "写入失败"，很难联想到"索引没了"。
     * 让应用启动时自愈一次，省掉一类排查。
     */
    public boolean ensureIndex() {
        String index = properties.getDishIndex();

        // HEAD 一个不存在的索引，ES 返回 404（而不是抛连接异常）
        try {
            esRestClient.head().uri("/{index}", index).retrieve().toBodilessEntity();
            log.debug("ES 索引 {} 已存在", index);
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404) {
                log.warn("探测 ES 索引失败（HTTP {}）：{}", e.getStatusCode().value(),
                        brief(e.getResponseBodyAsString()));
                return false;
            }
            // 404 是预期内的情况：索引还不存在，继续往下建
        } catch (Exception e) {
            // 连不上 ES。这时候不该让服务启动失败 —— 召回本来就有降级路径，
            // 启动不起来反而把小问题放大成大故障。
            log.warn("无法连接 Elasticsearch [{}]，跳过索引检查：{}",
                    properties.getUris(), e.getMessage());
            return false;
        }

        try {
            String mapping = StreamUtils.copyToString(
                    new ClassPathResource(MAPPING_LOCATION).getInputStream(), StandardCharsets.UTF_8);

            esRestClient.put()
                    .uri("/{index}", index)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapping)
                    .retrieve()
                    .toBodilessEntity();

            log.info("ES 索引 {} 不存在，已按 {} 自动创建", index, MAPPING_LOCATION);
            return true;
        } catch (Exception e) {
            log.warn("自动创建 ES 索引 {} 失败：{}", index, e.getMessage());
            return false;
        }
    }

    /**
     * 写入（或覆盖）一条菜品文档。
     *
     * @return 是否成功。失败不要当成业务失败处理，见类注释第 2 条
     */
    public boolean index(DishDoc doc) {
        if (doc == null || doc.getDishId() == null) {
            log.warn("文档缺少 dishId，已跳过写入");
            return false;
        }

        try {
            /*
             * refresh=wait_for 是这里的关键，不能省。
             *
             * ES 的写入不是"立刻可搜"的：文档先落到内存缓冲，要等下一次 refresh
             * （索引上配的是 1s）才对外可见。不加这个参数时会出现一种很迷惑的现象：
             *   · 直接用 _doc/{id} 查 —— **能查到**（这个接口是实时的）
             *   · 用 _search 搜 —— **搜不到**（受 refresh 影响）
             * 两种查询给出不同答案，很容易被当成"同步没生效"查半天。
             *
             * 加上它之后，ES 会等到这次写入对外可见才返回，
             * 于是"调用方拿到成功响应"就等价于"别人立刻能搜到"，
             * 不必再靠 sleep 去赌 refresh 有没有跑完。
             *
             * 代价是单次写入最多多等一个 refresh 周期（1s）。
             * 发布是低频操作，这个代价换掉一整类时序问题很划算；
             * 但如果将来要批量灌数据，应该改成不带这个参数 + 最后统一 _refresh
             * （reindexAll 就是那么做的）。
             */
            esRestClient.put()
                    .uri("/{index}/_doc/{id}?refresh=wait_for",
                            properties.getDishIndex(), doc.getDishId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(doc)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("已写入 ES：dishId={}，name={}", doc.getDishId(), doc.getName());
            return true;
        } catch (Exception e) {
            log.warn("写入 ES 失败 dishId={}：{}", doc.getDishId(), brief(e.getMessage()));
            return false;
        }
    }

    /**
     * 从索引里删除一条文档（下架、逻辑删除时用）。
     *
     * <p>注意这里用**真删除**而不是写一个 {@code deleted=1} 的文档：
     * 召回 DSL 里已经有 {@code deleted} 过滤，但"库里查不到这条菜"的情况下
     * 索引里留一条残留文档没有意义，不如直接删掉。
     */
    public boolean delete(Long dishId) {
        if (dishId == null) {
            return false;
        }
        try {
            // 同样要 refresh=wait_for：删除也是"延迟生效"的，
            // 否则会出现"已经撤回了、但搜索还能搜到"——
            // 用户看到的是一道点进去提示"菜品不存在"的幽灵菜。
            esRestClient.delete()
                    .uri("/{index}/_doc/{id}?refresh=wait_for",
                            properties.getDishIndex(), dishId)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("已从 ES 删除：dishId={}", dishId);
            return true;
        } catch (RestClientResponseException e) {
            // 本来就不在索引里（404）算成功，调用方不该为一个"No such document"报警
            if (e.getStatusCode().value() == 404) {
                return true;
            }
            log.warn("从 ES 删除失败 dishId={}：{}", dishId, brief(e.getMessage()));
            return false;
        } catch (Exception e) {
            log.warn("从 ES 删除失败 dishId={}：{}", dishId, brief(e.getMessage()));
            return false;
        }
    }

    /**
     * 列出索引里现有的全部菜品 ID，用来找出「ES 里有、但库里已经没有了」的幽灵文档。
     *
     * <p><b>为什么需要它：</b>幽灵菜搜索时能召回，点进去却提示"菜品不存在" ——
     * 搜索走 ES、详情走 MySQL，两边对不上号。来源不止一种：有人手工往索引灌了
     * 测试数据、索引残留、或者库里的行被物理删掉而删无声。
     * 只要发生过一次，正向 upsert 就永远清不掉它（重复写只会覆盖，不会删除）。
     *
     * <p><b>失败时返回空列表，而不是抛异常：</b>这个方法的返回值会被拿去做差集删除，
     * 返回空 = "没发现幽灵"，最坏情况是少删；反过来如果把失败当成"全部都要删"，
     * 一次网络抖动就会把整个索引清空。宁可漏删，不可误删。
     *
     * @param limit 最多取多少条。ES 单次 {@code size} 上限是 10000，
     *              超了必须改成 {@code search_after} / PIT 翻页
     */
    public List<Long> listAllDishIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String dsl = "{\"_source\":[\"dishId\"],\"size\":" + limit
                + ",\"query\":{\"match_all\":{}}}";
        try {
            String json = esRestClient.post()
                    .uri("/{index}/_search", properties.getDishIndex())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(dsl)
                    .retrieve()
                    .body(String.class);

            List<Long> ids = new ArrayList<>();
            JsonNode root = objectMapper.readTree(json);
            long total = root.path("hits").path("total").path("value").asLong();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode v = hit.path("_source").path("dishId");
                if (v.isNumber()) {
                    ids.add(v.asLong());
                }
            }
            if (total > limit) {
                // 说明这次没扫全，差集里会漏掉一部分 —— 明确说出来，别假装清理干净了
                log.warn("ES 里有 {} 条文档，但本次只取了 {} 条，幽灵清理不完整", total, limit);
            }
            return ids;
        } catch (Exception e) {
            log.warn("读取 ES 现有菜品 ID 失败，跳过幽灵清理：{}", brief(e.getMessage()));
            return List.of();
        }
    }

    /** 主动刷新索引，让刚写的数据立刻可搜。只在验证/调试时用，正常写入靠 refresh_interval */
    public boolean refresh() {
        try {
            esRestClient.post()
                    .uri("/{index}/_refresh", properties.getDishIndex())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("刷新索引失败：{}", e.getMessage());
            return false;
        }
    }

    private String brief(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
