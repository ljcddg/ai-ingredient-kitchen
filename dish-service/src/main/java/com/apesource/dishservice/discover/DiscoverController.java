package com.apesource.dishservice.discover;

import com.apesource.commonservice.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发现流接口。
 *
 * <p>挂在 {@code /api/dish/ai/discover} 下，网关已有 {@code /api/dish/**} 路由，
 * 不需要新增路由配置。
 *
 * <p>用户身份从网关注入的 {@code X-User-Id} 读，和项目里其他接口一致。
 *
 * <p><b>两个端点共用一套排序</b>，只在"一次给几条"上分叉：
 * 列表页调 {@code browse}，决策页调 {@code decide}。
 * 它们看到的第一条必定是同一道菜——两种呈现给出矛盾的答案最伤信任。
 */
@Slf4j
@RestController
@RequestMapping("/ai/discover")
@RequiredArgsConstructor
public class DiscoverController {

    private final DiscoverService discoverService;

    /**
     * 浏览模式：一屏列表。
     *
     * @param ingredientIds   手边食材的 ID，可空
     * @param ingredientNames 自由输入的食材名（食材表之外的），可空
     * @param maxCookTimeMin  时长上限（分钟），可空
     * @param maxDifficulty   难度上限 1-5，可空
     * @param serving         几人份，可空（差 1 人份也算合适）
     */
    @GetMapping("/browse")
    public Result<DiscoverService.DiscoverPage> browse(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(name = "ingredientIds", required = false) List<Long> ingredientIds,
            @RequestParam(name = "ingredientNames", required = false) List<String> ingredientNames,
            @RequestParam(name = "maxCookTimeMin", required = false) Integer maxCookTimeMin,
            @RequestParam(name = "maxDifficulty", required = false) Integer maxDifficulty,
            @RequestParam(name = "serving", required = false) Integer serving,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        DiscoverFilter filter = new DiscoverFilter(maxCookTimeMin, maxDifficulty, serving);
        return Result.success(
                discoverService.browse(userId, ingredientIds, ingredientNames, filter, page, size));
    }

    /**
     * 决策模式：一次备一批卡片，"换一个"从这批里依次取。
     *
     * <p>为什么不是每次返回一道：每次重算会因为排序的微小抖动重复出同一道菜。
     * 一次取一批、前端本地推进，顺序就锁死了。
     *
     * @param offset 从第几道开始取；这批用完后再取下一批时传 20、40……
     */
    @GetMapping("/decide")
    public Result<List<DiscoverItemView>> decide(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(name = "ingredientIds", required = false) List<Long> ingredientIds,
            @RequestParam(name = "ingredientNames", required = false) List<String> ingredientNames,
            @RequestParam(name = "maxCookTimeMin", required = false) Integer maxCookTimeMin,
            @RequestParam(name = "maxDifficulty", required = false) Integer maxDifficulty,
            @RequestParam(name = "serving", required = false) Integer serving,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        DiscoverFilter filter = new DiscoverFilter(maxCookTimeMin, maxDifficulty, serving);
        return Result.success(
                discoverService.decide(userId, ingredientIds, ingredientNames, filter, offset));
    }
}
