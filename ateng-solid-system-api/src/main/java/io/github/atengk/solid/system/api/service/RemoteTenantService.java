package io.github.atengk.solid.system.api.service;

import io.github.atengk.solid.system.api.dto.TenantAuthDTO;

/**
 * 租户认证信息与可用性跨模块远程服务契约。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface RemoteTenantService {

    /**
     * 按租户业务编码查询租户基础与授权状态信息。
     *
     * @param tenantCode 租户业务编码（如 {@code "000000"}）
     * @return 租户认证摘要；不存在时返回 {@code null}
     */
    TenantAuthDTO getTenantByCode(String tenantCode);

    /**
     * 按租户主键查询租户基础与授权状态信息。
     *
     * @param tenantId 租户主键
     * @return 租户认证摘要；不存在时返回 {@code null}
     */
    TenantAuthDTO getTenantById(Long tenantId);

    /**
     * 校验指定租户当前是否可用（存在、未软删、状态为正常且尚未过授权有效期）。
     *
     * @param tenantId 目标租户主键
     * @return 可用返回 {@code true}，否则返回 {@code false}
     */
    boolean isTenantAvailable(Long tenantId);
}
