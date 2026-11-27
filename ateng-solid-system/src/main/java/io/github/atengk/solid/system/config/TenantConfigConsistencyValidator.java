package io.github.atengk.solid.system.config;

import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 多租户双开关一致性启动校验器。
 * <p>
 * 强制校验底层 {@code ateng.boot.mybatis-plus.tenant.enabled} 与业务 {@code ateng.solid.tenant.enabled}
 * 始终保持同步开启或同步关闭，防止因单边开启导致的租户 SQL 自动隔离缺失或运行时租户校验失效。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Component
@RequiredArgsConstructor
public class TenantConfigConsistencyValidator implements InitializingBean {

    private final MybatisPlusProperties mybatisPlusProperties;
    private final SolidTenantProperties solidTenantProperties;

    @Override
    public void afterPropertiesSet() {
        validateConsistency();
    }

    /**
     * 校验多租户行级 SQL 插件开关与业务运行时租户开关是否严格一致。
     */
    public void validateConsistency() {
        boolean mpTenantEnabled = mybatisPlusProperties.getTenant() != null
                && mybatisPlusProperties.getTenant().isEnabled();
        boolean solidTenantEnabled = solidTenantProperties.isEnabled();
        if (mpTenantEnabled != solidTenantEnabled) {
            throw new IllegalStateException(String.format(
                    "多租户双开关配置不一致: ateng.boot.mybatis-plus.tenant.enabled=%s 与 ateng.solid.tenant.enabled=%s 必须保持一致",
                    mpTenantEnabled, solidTenantEnabled
            ));
        }
    }
}
