package com.apesource.dishservice.service;

import com.apesource.dishservice.entity.Ingredient;
import com.apesource.dishservice.mapper.IngredientMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 食材目录（P1-1 归一化的唯一入口）的加载规则测试。
 *
 * <p>这里守的是两条容易被数据悄悄破坏的规则：
 * <ol>
 *   <li><b>正式名优先于别名</b>：数据里「五花肉」既是自己的正式名、又是「猪肉」的别名，
 *       混在一遍登记就会变成"看数据库返回顺序" —— 名字归一到哪里会抖，
 *       缺料判断和营养计算跟着抖。这里用一个故意把别名行排在前的顺序，锁住结果。</li>
 *   <li><b>加载失败不能把已有缓存清空</b>：目录在请求路径上会被触发重载，
 *       查库失败只该退化成"这次不更新"，不该让归一化整体失效。</li>
 * </ol>
 */
class IngredientDirectoryTest {

    private IngredientMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mock(IngredientMapper.class);
    }

    private static Ingredient ingredient(long id, String name, String alias, String category) {
        Ingredient ing = new Ingredient();
        ing.setId(id);
        ing.setName(name);
        ing.setAlias(alias);
        ing.setCategory(category);
        return ing;
    }

    @Test
    @DisplayName("正式名优先于别名：别名行排在前面也抢不走正式名")
    void nameWinsOverAliasRegardlessOfRowOrder() {
        // 「猪肉」的别名里写着「五花肉」，而「五花肉」自己也是一条主数据。
        // 故意把「猪肉」排在前面（数据库返回顺序不可依赖）
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(
                ingredient(109L, "猪肉", "五花肉,猪瘦肉", "肉类"),
                ingredient(200L, "五花肉", null, "肉类"),
                ingredient(101L, "番茄", "西红柿,tomato", "蔬菜"),
                ingredient(300L, "盐", "食盐", "调料")));

        IngredientDirectory dir = new IngredientDirectory(mapper);
        dir.refresh();

        // 五花肉 归到自己那条主数据，而不是猪肉
        assertThat(dir.resolveId("五花肉")).isEqualTo(200L);
        assertThat(dir.nameOf(dir.resolveId("五花肉"))).isEqualTo("五花肉");
        // 没有正式名冲突的别名照常生效
        assertThat(dir.resolveId("西红柿")).isEqualTo(101L);
        assertThat(dir.resolveId("tomato")).isEqualTo(101L);
        assertThat(dir.resolveId("番茄")).isEqualTo(101L);
        // 去空白 + 大小写不敏感
        assertThat(dir.resolveId(" Tomato ")).isEqualTo(101L);
        // 认不出来就是表外食材
        assertThat(dir.resolveId("高脚鸟蛋")).isNull();
        assertThat(dir.resolveId(null)).isNull();
    }

    @Test
    @DisplayName("调料按类目判定，且别名也认")
    void recognizesSeasoningByCategory() {
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(
                ingredient(101L, "番茄", "西红柿", "蔬菜"),
                ingredient(300L, "盐", "食盐", "调料")));

        IngredientDirectory dir = new IngredientDirectory(mapper);
        dir.refresh();

        assertThat(dir.isSeasoning(300L)).isTrue();
        assertThat(dir.isSeasoning(101L)).isFalse();
        assertThat(dir.isSeasoningName("食盐")).isTrue();
        assertThat(dir.isSeasoningName("西红柿")).isFalse();
        assertThat(dir.categoryOf(101L)).isEqualTo("蔬菜");
        assertThat(dir.categoryOf(null)).isNull();
        assertThat(dir.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("重载失败保留上一份缓存，不把归一化清空")
    void keepsPreviousCacheWhenReloadFails() {
        when(mapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(
                ingredient(101L, "番茄", "西红柿", "蔬菜")));
        IngredientDirectory dir = new IngredientDirectory(mapper);
        dir.refresh();
        assertThat(dir.resolveId("西红柿")).isEqualTo(101L);

        // 第二次查库炸了
        when(mapper.selectList(ArgumentMatchers.any())).thenThrow(new RuntimeException("数据库连接断了"));
        dir.refresh();

        assertThat(dir.resolveId("西红柿")).isEqualTo(101L);
        assertThat(dir.size()).isEqualTo(1);
    }
}
