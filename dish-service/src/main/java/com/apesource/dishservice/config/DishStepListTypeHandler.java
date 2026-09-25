package com.apesource.dishservice.config;

import com.apesource.dishservice.dto.DishStep;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * {@code t_dish.step_json} 的 JSON 处理器。
 *
 * <p>=====================================================================
 * <b>为什么不能直接用 MP 自带的 {@link JacksonTypeHandler}（这是真的踩过）</b>
 * =====================================================================
 *
 * <p>用它读 {@code List<DishStep>} 字段，拿到的是 <b>{@code List<LinkedHashMap>}</b> ——
 * 元素类型丢了。然后：
 * <ul>
 *   <li>字段声明的类型是 {@code List<DishStep>}，编译期检查过得去，
 *       {@code list.get(0)} 也能拿到东西；</li>
 *   <li>只有当你**调用元素的方法**（{@code step.getText()}）时才会炸，
 *       而且是运行期 {@code ClassCastException}，堆栈指向你的业务代码，
 *       看起来像是你自己写错了类型转换。</li>
 * </ul>
 *
 * <p>更麻烦的是它<b>会骗过测试</b>：详情接口直接把 {@code steps} 序列化回 JSON，
 * 而 {@code LinkedHashMap} 序列化出来和 {@code DishStep} 一模一样，
 * 接口返回完全正常。所以这个坑只在"对步骤内容做业务判断"时才暴露 ——
 * 恰好就是发布校验要做的事（数一数有几条有效步骤）。
 *
 * <p>根因是泛型擦除：{@code JacksonTypeHandler} 只拿到原始类型 {@code List}，
 * {@code readValue(json, List.class)} 于是把每个元素当 Object 处理成 Map。
 * 解决办法就是把元素类型**写死**在这个类里，而不是指望运行期能推断出来。
 *
 * <p>注意只有 {@code List<自定义对象>} 需要它。同一张表上的
 * {@code seasonings / equipment / chefTips / substitutes} 都是
 * {@code List<String>}，JSON 里的字符串会正常映射成 String，不受影响。
 */
public class DishStepListTypeHandler extends JacksonTypeHandler {

    /**
     * 元素类型写死在这里 —— 这正是父类做不到的事。
     * 用匿名子类的写法是为了保留泛型信息（{@code new TypeReference<List<DishStep>>() {}}），
     * 直接写 {@code TypeReference} 的裸类型同样会退化成 Map。
     */
    private static final TypeReference<List<DishStep>> STEP_LIST = new TypeReference<>() {
    };

    public DishStepListTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    protected Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return getObjectMapper().readValue(json, STEP_LIST);
        } catch (Exception e) {
            // 解析失败要抛出而不是返回 null：步骤是这道菜的核心内容，
            // 静默变成 null 会让"这道菜为什么发不出去"变成一个查不出原因的问题。
            throw new IllegalStateException("解析菜品步骤 JSON 失败：" + json, e);
        }
    }

    // 写回数据库（toJson）沿用父类实现即可 —— 序列化 List<DishStep> 不存在类型丢失问题
}
