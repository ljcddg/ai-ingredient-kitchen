package com.apesource.dishservice.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 只为「这条请求是不是管理员」服务的一个极简 Mapper。
 *
 * <p><b>为什么 dish-service 要直接读 t_user：</b>
 * 网关确实可以把角色塞进 JWT claim、再透传成 {@code X-User-Role} 请求头，
 * 但那有两个不合适的地方：
 * <ul>
 *   <li>角色是签发那一刻的快照，改完必须重新登录才生效 ——
 *       把某人提成管理员后还得让他退出重登，运维上很别扭；</li>
 *   <li>请求头和 {@code X-User-Id} 一样是<b>可伪造</b>的。身份头伪造顶多是"冒充某个普通用户"，
 *       而权限头伪造直接是"提权"。这种判断与其相信请求头，不如回源查一次库。</li>
 * </ul>
 *
 * <p>代价是每个管理接口多一次主键查询。管理接口本来就是低频运维操作，
 * 这点开销完全可以接受。
 *
 * <p>前提：全项目共用同一个库 {@code project0914}（common.yaml 里已明确的约定），
 * 所以这里能直接读到 account-service 维护的 t_user。
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 查用户角色。
     *
     * @param userId 用户ID（网关注入的 X-User-Id）
     * @return 角色值；用户不存在或已逻辑删除时返回 null
     */
    @Select("SELECT role FROM t_user WHERE id = #{userId} AND deleted = 0")
    Integer selectRoleById(@Param("userId") Long userId);
}
