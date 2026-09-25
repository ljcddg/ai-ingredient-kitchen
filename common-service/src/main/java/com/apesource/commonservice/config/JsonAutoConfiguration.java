package com.apesource.commonservice.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * JSON 序列化约定：把 {@code Long} 输出成<b>字符串</b>。
 *
 * <p><b>为什么必须这么做：</b>主键是雪花 ID，19 位十进制数（约 2×10¹⁸），
 * 而 JavaScript 的 Number 只能精确表示 2⁵³（约 9×10¹⁵）以内的整数。
 * 直接把 Long 当 JSON number 发出去，浏览器 {@code JSON.parse} 之后低位就丢了 ——
 * 前端再拿这个被悄悄改过的数字去请求，后端只会回一句「菜品不存在」。
 *
 * <p><b>这个 bug 特别难查，因为打印出来看着是对的：</b>
 * 丢掉精度的 Number 转成字符串往往还能显示出接近原样的值，
 * 只有拿去做等值比较、或者发给后端当查询条件时才暴露。
 * 实测过：数据库里的 {@code 2099705547174719489} 到了页面上变成
 * {@code 209970554717419489} —— 肉眼扫一眼几乎看不出少了数字。
 *
 * <p><b>为什么放在 common-service 而不是各服务各写一份：</b>
 * 这是「格式约定」，不是「各服务自己的业务配置」。两个服务必须口径一致 ——
 * 否则同一个 userId，account-service 返回数字、dish-service 返回字符串，
 * 前端就得为每个字段写两套判断，迟早出错。
 * 放在 common 并用自动配置注册，依赖它的服务零配置生效。
 *
 * <p><b>代价：</b>前端拿到的 ID 是字符串。但它们本来就只被拼进 URL、
 * 或者当 Map 的键，不需要做数学运算 —— 强制成字符串反而更安全，
 * 有人写 {@code id + 1} 之类的代码时会更早发现不对。
 *
 * <p>注意 {@code Long.class} 和 {@code Long.TYPE} 都要注册：
 * 前者管包装类型字段，后者管基本类型 {@code long}。只注册一个的话，
 * 漏掉的那种写法会继续以数字形式输出，问题还会再出现一次。
 */
@AutoConfiguration
public class JsonAutoConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
