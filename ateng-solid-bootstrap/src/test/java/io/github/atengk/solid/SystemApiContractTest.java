package io.github.atengk.solid;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.common.event.LoginAuditEvent;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.api.command.UpdatePersonalProfileCommand;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.AdminProfileDTO;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.dto.PersonalProfileDTO;
import io.github.atengk.solid.system.api.dto.RouteMenuDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.enums.ConfigTypeEnum;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.enums.VisibleStatusEnum;
import io.github.atengk.solid.system.api.service.RemoteAuditLogService;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ateng-solid-system-api} 跨模块裸契约接口、DTO/Command 真实二进制序列化往返、
 * 静态 {@link IBaseEnum} 枚举总数锁定（7 项）与审计 {@code action} 解耦离线测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class SystemApiContractTest {

    @Test
    @DisplayName("Remote*Service 契约接口方法签名、显式租户定界与禁 Result/Optional 包装应当严格锁定")
    void shouldLockRemoteServiceInterfaceSignaturesAndForbidWrapperTypes() throws NoSuchMethodException {
        assertThat(declaredMethodNames(RemoteUserService.class)).containsExactlyInAnyOrder(
                "getAuthUserByUsername",
                "getAuthUserById",
                "getAdminProfile",
                "getPersonalProfile",
                "updatePersonalProfile",
                "uploadAvatar",
                "updatePasswordHash",
                "upgradePasswordHash"
        );
        assertThat(RemoteUserService.class.getDeclaredMethod(
                "upgradePasswordHash", Long.class, Long.class, String.class
        ).getReturnType()).isEqualTo(void.class);

        assertThat(declaredMethodNames(RemoteTenantService.class)).containsExactlyInAnyOrder(
                "getTenantByCode",
                "getTenantById",
                "isTenantAvailable"
        );

        assertThat(declaredMethodNames(RemotePermissionService.class)).containsExactlyInAnyOrder(
                "getRoleKeysByUserId",
                "getPermissionsByRoleKey",
                "getDirectPermissionsByUserId",
                "getRouteMenusByUserId"
        );

        assertThat(declaredMethodNames(RemoteAuditLogService.class)).containsExactlyInAnyOrder(
                "recordLoginAudit"
        );

        for (Method method : RemoteUserService.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                assertThat(method.getParameterCount()).isGreaterThanOrEqualTo(1);
                assertThat(method.getParameterTypes()[0]).isEqualTo(Long.class);
            }
        }
        for (Method method : RemotePermissionService.class.getDeclaredMethods()) {
            if (!method.isSynthetic()) {
                assertThat(method.getParameterCount()).isGreaterThanOrEqualTo(1);
                assertThat(method.getParameterTypes()[0]).isEqualTo(Long.class);
            }
        }

        for (Class<?> serviceInterface : List.of(
                RemoteUserService.class,
                RemoteTenantService.class,
                RemotePermissionService.class,
                RemoteAuditLogService.class
        )) {
            for (Method method : serviceInterface.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                assertTypeTreeExcludes(method.getGenericReturnType(), serviceInterface.getSimpleName() + "#" + method.getName() + " return");
                for (Type paramType : method.getGenericParameterTypes()) {
                    assertTypeTreeExcludes(paramType, serviceInterface.getSimpleName() + "#" + method.getName() + " param");
                }
            }
        }
    }

    @Test
    @DisplayName("system-api 全部 9 个 DTO 与 Command 应当具备 serialVersionUID 并通过真实二进制序列化往返")
    void shouldRoundTripSerializeEverySystemApiDtoAndCommand() throws Exception {
        JavaClasses apiClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "io.github.atengk.solid.system.api.dto",
                        "io.github.atengk.solid.system.api.command"
                );

        Set<String> discoveredTopLevelNames = apiClasses.stream()
                .filter(jc -> !jc.isInterface()
                        && !jc.isAnonymousClass()
                        && !jc.isMemberClass()
                        && !jc.getModifiers().contains(JavaModifier.SYNTHETIC))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        Set<String> expectedNames = Set.of(
                AdminAuthUserDTO.class.getName(),
                AdminProfileDTO.class.getName(),
                AvatarUploadDTO.class.getName(),
                PersonalProfileDTO.class.getName(),
                RouteMenuDTO.class.getName(),
                TenantAuthDTO.class.getName(),
                LoginAuditCommand.class.getName(),
                UpdatePersonalProfileCommand.class.getName(),
                UploadAvatarCommand.class.getName()
        );
        assertThat(discoveredTopLevelNames).containsExactlyInAnyOrderElementsOf(expectedNames);

        for (String className : expectedNames) {
            Class<?> clazz = Class.forName(className);
            assertThat(Serializable.class).isAssignableFrom(clazz);
            Field serialField = clazz.getDeclaredField("serialVersionUID");
            assertThat(Modifier.isStatic(serialField.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(serialField.getModifiers())).isTrue();
            assertThat(serialField.getType()).isEqualTo(long.class);
        }

        LocalDateTime baseTime = LocalDateTime.of(2026, 11, 27, 15, 30, 0);

        AdminAuthUserDTO authUser = AdminAuthUserDTO.builder()
                .userId(1001L)
                .tenantId(0L)
                .deptId(100L)
                .username("contract_user")
                .nickname("契约用户")
                .passwordHash("$pbkdf2-sha256$310000$salt$hash")
                .status(0)
                .pwdUpdateTime(baseTime)
                .loginIp("127.0.0.1")
                .loginDate(baseTime)
                .build();
        assertThat(roundTrip(authUser)).isEqualTo(authUser);

        AdminProfileDTO adminProfile = AdminProfileDTO.builder()
                .userId(1001L)
                .tenantId(0L)
                .tenantName("平台默认租户")
                .username("contract_user")
                .nickname("契约用户")
                .avatarUrl("https://cdn.example.com/avatar.png")
                .pwdUpdateTime(baseTime)
                .loginIp("10.0.0.1")
                .loginDate(baseTime)
                .build();
        assertThat(roundTrip(adminProfile)).isEqualTo(adminProfile);

        AvatarUploadDTO avatarUpload = AvatarUploadDTO.builder()
                .avatarUrl("https://cdn.example.com/inline.png")
                .objectPath("avatar/0/1001/abc.png")
                .build();
        assertThat(roundTrip(avatarUpload)).isEqualTo(avatarUpload);

        PersonalProfileDTO personalProfile = PersonalProfileDTO.builder()
                .userId(1001L)
                .tenantId(0L)
                .tenantName("平台默认租户")
                .username("contract_user")
                .nickname("契约用户")
                .email("contract@example.com")
                .phone("13800138000")
                .gender("0")
                .avatarUrl("https://cdn.example.com/avatar.png")
                .deptName("平台总部")
                .postNames(List.of("架构师"))
                .roles(List.of("super_admin"))
                .loginIp("10.0.0.1")
                .loginDate(baseTime)
                .createTime(baseTime.minusDays(1))
                .pwdUpdateTime(baseTime)
                .build();
        assertThat(roundTrip(personalProfile)).isEqualTo(personalProfile);

        RouteMenuDTO childMenu = RouteMenuDTO.builder()
                .id(1100L)
                .parentId(1000L)
                .menuType("C")
                .title("用户管理")
                .name("SysUser")
                .path("/system/user")
                .component("system/user/index")
                .icon("User")
                .sort(1)
                .visible(0)
                .keepAlive(true)
                .isExternal(false)
                .platformOnly(false)
                .children(List.of())
                .build();
        RouteMenuDTO rootMenu = RouteMenuDTO.builder()
                .id(1000L)
                .parentId(0L)
                .menuType("M")
                .title("组织权限")
                .path("/system")
                .redirect("/system/user")
                .icon("Setting")
                .sort(1)
                .visible(0)
                .keepAlive(false)
                .isExternal(false)
                .platformOnly(false)
                .children(List.of(childMenu))
                .build();
        RouteMenuDTO deserializedMenu = roundTrip(rootMenu);
        assertThat(deserializedMenu).isEqualTo(rootMenu);
        assertThat(deserializedMenu.getChildren()).containsExactly(childMenu);

        TenantAuthDTO futureTenant = TenantAuthDTO.builder()
                .tenantId(2001L)
                .tenantCode("T2001")
                .tenantName("未来到期租户")
                .packageId(10L)
                .expireTime(baseTime.plusHours(1))
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();
        TenantAuthDTO deserializedFutureTenant = roundTrip(futureTenant);
        assertThat(deserializedFutureTenant).isEqualTo(futureTenant);
        assertThat(deserializedFutureTenant.isAvailableAt(baseTime)).isTrue();
        assertThat(deserializedFutureTenant.isAvailableAt(baseTime.plusHours(2))).isFalse();

        TenantAuthDTO expiredTenant = TenantAuthDTO.builder()
                .tenantId(2002L)
                .tenantCode("T2002")
                .tenantName("已过期租户")
                .packageId(10L)
                .expireTime(baseTime.minusMinutes(1))
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();
        TenantAuthDTO deserializedExpiredTenant = roundTrip(expiredTenant);
        assertThat(deserializedExpiredTenant).isEqualTo(expiredTenant);
        assertThat(deserializedExpiredTenant.isAvailableAt(baseTime)).isFalse();

        LoginAuditCommand auditCmd = LoginAuditCommand.builder()
                .eventId("evt-1")
                .occurredAt(baseTime)
                .tenantId(0L)
                .traceId("trace-1")
                .userId(1001L)
                .username("contract_user")
                .loginType("admin")
                .clientIp("127.0.0.1")
                .userAgent("Mozilla/5.0")
                .status(LogStatusEnum.SUCCESS.getCode())
                .action(LoginAuditEvent.ACTION_LOGIN)
                .message("登录成功")
                .build();
        assertThat(roundTrip(auditCmd)).isEqualTo(auditCmd);

        UpdatePersonalProfileCommand profileCmd = UpdatePersonalProfileCommand.builder()
                .nickname("新昵称")
                .email("new@example.com")
                .phone("13900139000")
                .gender("1")
                .build();
        assertThat(roundTrip(profileCmd)).isEqualTo(profileCmd);

        UploadAvatarCommand avatarCmd = UploadAvatarCommand.builder()
                .originalFilename("avatar.png")
                .contentType("image/png")
                .content("png-binary-payload".getBytes(StandardCharsets.UTF_8))
                .build();
        UploadAvatarCommand deserializedAvatarCmd = roundTrip(avatarCmd);
        assertThat(deserializedAvatarCmd).isEqualTo(avatarCmd);
        assertThat(deserializedAvatarCmd.getContent()).isEqualTo(avatarCmd.getContent());
    }

    @Test
    @DisplayName("静态 IBaseEnum 枚举总量应当严格锁定为 7 项且审计模型不得私增 IBaseEnum")
    void shouldLockStaticIBaseEnumCountToExactlySeven() {
        JavaClasses solidClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.atengk.solid");

        Set<String> solidEnums = solidClasses.stream()
                .filter(jc -> !jc.isInterface() && jc.isAssignableTo(IBaseEnum.class))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());

        assertThat(solidEnums).containsExactlyInAnyOrder(
                CommonStatusEnum.class.getName(),
                LogStatusEnum.class.getName(),
                ConfigTypeEnum.class.getName(),
                DataScopeType.class.getName(),
                MenuTypeEnum.class.getName(),
                VisibleStatusEnum.class.getName()
        );
        assertThat(IBaseEnum.class).isAssignableFrom(OperateTypeEnum.class);
        assertThat(solidEnums.size() + 1).isEqualTo(7);

        for (Class<?> auditModel : List.of(LoginAuditEvent.class, LoginAuditCommand.class)) {
            assertThat(auditModel.getDeclaredClasses()).noneMatch(IBaseEnum.class::isAssignableFrom);
            for (Field field : auditModel.getDeclaredFields()) {
                assertThat(IBaseEnum.class.isAssignableFrom(field.getType()))
                        .as("审计模型 %s 字段 %s 不得为 IBaseEnum", auditModel.getSimpleName(), field.getName())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("LoginAuditEvent 与 LoginAuditCommand 的 action 应当保持纯字符串解耦并完整序列化")
    void shouldDecoupleLoginAuditActionAsPlainStringAndRemainSerializable() throws Exception {
        assertThat(LoginAuditEvent.ACTION_LOGIN).isEqualTo("LOGIN");
        assertThat(LoginAuditEvent.ACTION_LOGOUT).isEqualTo("LOGOUT");
        assertThat(LoginAuditEvent.class.getDeclaredField("action").getType()).isEqualTo(String.class);
        assertThat(LoginAuditCommand.class.getDeclaredField("action").getType()).isEqualTo(String.class);

        LoginAuditEvent logoutEvent = LoginAuditEvent.builder()
                .eventId("evt-logout-1")
                .occurredAt(LocalDateTime.of(2026, 11, 27, 15, 45, 0))
                .tenantId(0L)
                .traceId("trace-logout")
                .userId(1001L)
                .username("contract_user")
                .loginType("admin")
                .clientIp("198.51.100.10")
                .userAgent("Mozilla/5.0")
                .status(LogStatusEnum.SUCCESS.getCode())
                .action(LoginAuditEvent.ACTION_LOGOUT)
                .message("退出登录成功")
                .build();

        LoginAuditEvent deserialized = roundTrip(logoutEvent);
        assertThat(deserialized).isEqualTo(logoutEvent);
        assertThat(deserialized.getAction()).isEqualTo("LOGOUT");
    }

    private static List<String> declaredMethodNames(Class<?> serviceInterface) {
        return Arrays.stream(serviceInterface.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .toList();
    }

    private static void assertTypeTreeExcludes(Type type, String location) {
        if (type instanceof Class<?> clazz) {
            assertThat(clazz)
                    .as("%s 不得暴露 Result 或 Optional", location)
                    .isNotEqualTo(Result.class)
                    .isNotEqualTo(Optional.class);
            if (clazz.isArray()) {
                assertTypeTreeExcludes(clazz.getComponentType(), location);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            assertTypeTreeExcludes(parameterized.getRawType(), location);
            for (Type arg : parameterized.getActualTypeArguments()) {
                assertTypeTreeExcludes(arg, location);
            }
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type upper : wildcard.getUpperBounds()) {
                assertTypeTreeExcludes(upper, location);
            }
            for (Type lower : wildcard.getLowerBounds()) {
                assertTypeTreeExcludes(lower, location);
            }
            return;
        }
        if (type instanceof GenericArrayType genericArray) {
            assertTypeTreeExcludes(genericArray.getGenericComponentType(), location);
            return;
        }
        if (type instanceof TypeVariable<?> typeVariable) {
            for (Type bound : typeVariable.getBounds()) {
                assertTypeTreeExcludes(bound, location);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Serializable> T roundTrip(T source) throws IOException, ClassNotFoundException {
        byte[] serialized;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(source);
            oos.flush();
            serialized = baos.toByteArray();
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(serialized);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return (T) ois.readObject();
        }
    }
}
