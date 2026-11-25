package io.github.atengk.solid;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.code.ResultCodeUtil;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 业务错误码分段边界与全局防碰撞校验测试（验证 100xxx/101xxx/102xxx 号段无越界，且与底座既有错误码零重复）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class ResultCodeSegmentTest {

    @Test
    @DisplayName("common/auth/system 业务错误码均应落在各自规范千位号段内")
    void shouldStayWithinDesignatedSegments() {
        assertSegment(CommonBizResultCode.values(), 100_000, 100_999);
        assertSegment(AuthResultCode.values(), 101_000, 101_999);
        assertSegment(SystemResultCode.values(), 102_000, 102_999);

        assertThat(CommonBizResultCode.values()).hasSize(5);
        assertThat(AuthResultCode.values()).hasSize(7);
        assertThat(SystemResultCode.values()).hasSize(7);
    }

    @Test
    @DisplayName("全应用业务错误码与底座全部 IResultCode 枚举应当绝对唯一零碰撞")
    void shouldHaveZeroCollisionAcrossSolidAndBoot() throws ClassNotFoundException {
        List<IResultCode> allCodes = new ArrayList<>();
        allCodes.addAll(Arrays.asList(CommonBizResultCode.values()));
        allCodes.addAll(Arrays.asList(AuthResultCode.values()));
        allCodes.addAll(Arrays.asList(SystemResultCode.values()));
        allCodes.addAll(Arrays.asList(CommonResultCode.values()));
        allCodes.addAll(Arrays.asList(StorageResultCode.values()));
        allCodes.addAll(Arrays.asList(FlywayResultCode.values()));

        // 动态扫描类路径中全部 io.github.atengk 下的 IResultCode 枚举以兜底覆盖未显式列举模块
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(IResultCode.class));
        for (BeanDefinition candidate : scanner.findCandidateComponents("io.github.atengk")) {
            Class<?> clazz = Class.forName(candidate.getBeanClassName());
            if (clazz.isEnum()) {
                for (Object constant : clazz.getEnumConstants()) {
                    IResultCode code = (IResultCode) constant;
                    if (!allCodes.contains(code)) {
                        allCodes.add(code);
                    }
                }
            }
        }

        assertThatCode(() -> ResultCodeUtil.assertUnique(allCodes)).doesNotThrowAnyException();
    }

    private static void assertSegment(IResultCode[] codes, int minInclusive, int maxInclusive) {
        for (IResultCode item : codes) {
            assertThat(item.getCode())
                    .as("错误码 [%s] 须位于 %d~%d 号段内", item, minInclusive, maxInclusive)
                    .isNotNull()
                    .isBetween(minInclusive, maxInclusive);
            assertThat(item.getMsg())
                    .as("错误码 [%s] 的提示文案不能为空", item)
                    .isNotBlank();
        }
    }
}
