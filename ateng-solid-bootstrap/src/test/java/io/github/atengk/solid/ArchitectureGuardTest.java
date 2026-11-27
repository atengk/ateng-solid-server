package io.github.atengk.solid;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.solid.auth.security.TenantAvailabilityInterceptor;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后端同源双装配架构红线自动化守卫测试，验证模块物理隔离、零字段注入、零 Entity/Result 泄漏、
 * 强类型配置边界、Controller 鉴权全覆盖、DTO/Command 可序列化与非 Web 边界包 Servlet 零依赖。
 *
 * @author Ateng
 * @since 2026-11-19
 */
class ArchitectureGuardTest {

    private static final String RESULT_CLASS_NAME = "io.github.atengk.boot.core.model.Result";
    private static final String REFRESH_SCOPE_ANNOTATION =
            "org.springframework.cloud.context.config.annotation.RefreshScope";
    private static final String SA_CHECK_ANNOTATION_PACKAGE = "cn.dev33.satoken.annotation";
    private static final String SERVLET_PACKAGE_PREFIX = "jakarta.servlet..";

    private static final Set<String> HTTP_MAPPING_ANNOTATIONS = Set.of(
            GetMapping.class.getName(),
            PostMapping.class.getName(),
            PutMapping.class.getName(),
            DeleteMapping.class.getName(),
            PatchMapping.class.getName(),
            RequestMapping.class.getName()
    );

    private static final DescribedPredicate<JavaClass> CONTROLLER_TYPE = DescribedPredicate.describe(
            "annotated with @RestController or @Controller",
            javaClass -> javaClass.isAnnotatedWith(RestController.class) || javaClass.isAnnotatedWith(Controller.class)
    );

    private static final DescribedPredicate<JavaClass> MAPPER_TYPE =
            resideInAPackage("io.github.atengk.solid..mapper..")
                    .or(resideInAPackage("io.github.atengk.solid..").and(simpleNameEndingWith("Mapper")));

    private static final DescribedPredicate<JavaClass> BOOTSTRAP_SCOPE =
            resideInAPackage("io.github.atengk.solid")
                    .or(resideInAPackage("io.github.atengk.solid.bootstrap.."))
                    .or(DescribedPredicate.describe("residing in ateng-solid-bootstrap artifact",
                            javaClass -> javaClass.getSource()
                                    .map(source -> source.getUri().toString().replace('\\', '/')
                                            .contains("/ateng-solid-bootstrap/"))
                                    .orElse(false)));

    private static final DescribedPredicate<JavaClass> CONCRETE_DTO_OR_COMMAND = DescribedPredicate.describe(
            "concrete DTO or Command class excluding interfaces, anonymous, synthetic and Lombok Builder classes",
            javaClass -> !javaClass.isInterface()
                    && !javaClass.isAnonymousClass()
                    && !javaClass.getModifiers().contains(JavaModifier.SYNTHETIC)
                    && !javaClass.getSimpleName().endsWith("Builder")
    );

    private static final DescribedPredicate<JavaClass> SYSTEM_API_DTO_OR_COMMAND_SCOPE =
            resideInAnyPackage("io.github.atengk.solid.system.api.dto..", "io.github.atengk.solid.system.api.command..")
                    .or(resideInAPackage("io.github.atengk.solid.system.api..")
                            .and(simpleNameEndingWith("DTO").or(simpleNameEndingWith("Command"))));

    private static final DescribedPredicate<JavaClass> ALLOWED_SECURITY_INTERCEPTOR =
            resideInAPackage("io.github.atengk.solid..security..")
                    .and(assignableTo(HandlerInterceptor.class));

    private static final DescribedPredicate<JavaClass> NON_WEB_BOUNDARY_PACKAGES =
            resideInAnyPackage(
                    "io.github.atengk.solid..service..",
                    "io.github.atengk.solid..support..",
                    "io.github.atengk.solid..grant..",
                    "io.github.atengk.solid..security.."
            );

    private static final DescribedPredicate<JavaMethod> HTTP_MAPPING_METHOD = DescribedPredicate.describe(
            "annotated with a Spring MVC HTTP mapping annotation",
            method -> hasHttpMappingAnnotation(method.getAnnotations())
    );

    private static final ArchRule BOOTSTRAP_PURITY_RULE = CompositeArchRule
            .of(noClasses().that(BOOTSTRAP_SCOPE).should().beAnnotatedWith(RestController.class).allowEmptyShould(true))
            .and(noClasses().that(BOOTSTRAP_SCOPE).should().beAnnotatedWith(Controller.class).allowEmptyShould(true))
            .and(noClasses().that(BOOTSTRAP_SCOPE).should().haveSimpleNameEndingWith("Mapper").allowEmptyShould(true));

    private static final ArchRule SYSTEM_API_RESULT_RULE = CompositeArchRule
            .of(methods()
                    .that().areDeclaredInClassesThat().resideInAPackage("io.github.atengk.solid.system.api..")
                    .and().arePublic()
                    .should(forbidTypeInSignature(
                            "不在方法签名或泛型中暴露 Result<T>",
                            involved -> RESULT_CLASS_NAME.equals(involved.getName()),
                            "违反裸契约规范，使用了包装类型"
                    ))
                    .allowEmptyShould(true))
            .and(noClasses()
                    .that().resideInAPackage("io.github.atengk.solid.system.api..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(RESULT_CLASS_NAME)
                    .allowEmptyShould(true));

    private static final ArchRule NO_VALUE_OR_REFRESH_SCOPE_RULE = CompositeArchRule
            .of(noClasses().should().dependOnClassesThat().haveFullyQualifiedName(Value.class.getName()).allowEmptyShould(true))
            .and(fields().should().notBeAnnotatedWith(Value.class).allowEmptyShould(true))
            .and(methods().should().notBeAnnotatedWith(Value.class).allowEmptyShould(true))
            .and(noClasses().should().dependOnClassesThat().haveFullyQualifiedName(REFRESH_SCOPE_ANNOTATION).allowEmptyShould(true))
            .and(noClasses().should().beAnnotatedWith(REFRESH_SCOPE_ANNOTATION).allowEmptyShould(true));

    private static final ArchRule CONTROLLERS_NO_MAPPER_RULE = noClasses()
            .that(CONTROLLER_TYPE)
            .should().dependOnClassesThat(MAPPER_TYPE)
            .allowEmptyShould(true);

    private static final ArchRule CONTROLLER_HTTP_MAPPINGS_AUTH_RULE = methods()
            .that().areDeclaredInClassesThat(CONTROLLER_TYPE)
            .and(HTTP_MAPPING_METHOD)
            .should(requireAuthOrAnonymousAnnotation())
            .allowEmptyShould(true);

    private static final ArchRule SYSTEM_API_SERIALIZABLE_RULE = classes()
            .that(SYSTEM_API_DTO_OR_COMMAND_SCOPE.and(CONCRETE_DTO_OR_COMMAND))
            .should().implement(Serializable.class)
            .allowEmptyShould(true);

    private static final ArchRule NON_WEB_BOUNDARIES_NO_SERVLET_RULE = noClasses()
            .that(NON_WEB_BOUNDARY_PACKAGES.and(DescribedPredicate.not(ALLOWED_SECURITY_INTERCEPTOR)))
            .should().dependOnClassesThat().resideInAPackage(SERVLET_PACKAGE_PREFIX)
            .allowEmptyShould(true);

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.atengk.solid");
    }

    @Test
    @DisplayName("1. auth 模块禁止直接依赖 system 除 system.api 外的全部实现包")
    void authMustNotDependOnSystemImplementation() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.auth..")
                .should().dependOnClassesThat(
                        resideInAPackage("io.github.atengk.solid.system..")
                                .and(resideOutsideOfPackage("io.github.atengk.solid.system.api.."))
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("2. system-api 契约层禁止引入 Web、ORM、Redis、Feign 或 Dubbo 框架依赖")
    void systemApiMustRemainFrameworkPure() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.system.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "com.baomidou..",
                        "org.springframework.data.redis..",
                        "org.springframework.cloud.openfeign..",
                        "org.apache.dubbo..",
                        SERVLET_PACKAGE_PREFIX
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("3. 全局业务源码禁止使用 @Autowired / @Resource 字段注入（强制构造器注入）")
    void noFieldInjectionAllowed() {
        CompositeArchRule
                .of(fields().should().notBeAnnotatedWith(Autowired.class).allowEmptyShould(true))
                .and(fields().should().notBeAnnotatedWith(Resource.class).allowEmptyShould(true))
                .check(importedClasses);
    }

    @Test
    @DisplayName("4. Controller 公开方法禁止返回或接收持久层 Entity（含泛型嵌套参数与返回值）")
    void controllersMustNotLeakEntities() {
        methods()
                .that().areDeclaredInClassesThat(CONTROLLER_TYPE)
                .and().arePublic()
                .should(forbidTypeInSignature(
                        "不在参数或返回值（含泛型）中暴露 entity 包类",
                        involved -> involved.getPackageName().contains(".entity"),
                        "泄漏了持久层实体"
                ))
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("5. bootstrap 启动装配包禁止承载业务 Controller 或持久层 Mapper")
    void bootstrapMustNotContainBusinessControllersOrMappers() {
        BOOTSTRAP_PURITY_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("6. Service 层禁止依赖 jakarta.servlet HTTP 容器类型")
    void serviceMustNotDependOnServlet() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid..service..")
                .should().dependOnClassesThat().resideInAPackage(SERVLET_PACKAGE_PREFIX)
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("7. Mapper 持久层禁止反向依赖 Controller 或 Service 层")
    void mapperMustNotDependOnControllerOrService() {
        noClasses()
                .that(MAPPER_TYPE)
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.github.atengk.solid..controller..",
                        "io.github.atengk.solid..service.."
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("8. system-api 契约接口与 DTO 严禁使用或返回 Result<T> 包装类型")
    void systemApiMustNotExposeResultWrapper() {
        SYSTEM_API_RESULT_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("9. 全局业务源码禁止使用 @Value 与 @RefreshScope（统一收口为 @ConfigurationProperties）")
    void noValueOrRefreshScopeAllowed() {
        NO_VALUE_OR_REFRESH_SCOPE_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("10. system 模块严禁反向依赖 auth 模块")
    void systemMustNotDependOnAuth() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.system..")
                .should().dependOnClassesThat().resideInAPackage("io.github.atengk.solid.auth..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("11. common 模块严禁反向依赖 system、auth 或 bootstrap 上层模块")
    void commonMustNotDependOnUpstreamModules() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.common..")
                .should().dependOnClassesThat(
                        resideInAPackage("io.github.atengk.solid.system..")
                                .or(resideInAPackage("io.github.atengk.solid.auth.."))
                                .or(BOOTSTRAP_SCOPE)
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("12. Controller 严禁越级注入或直接调用持久层 *Mapper")
    void controllersMustNotDependOnMappers() {
        CONTROLLERS_NO_MAPPER_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("13. Controller 对外 HTTP 映射方法必须在类或方法上显式标注 @SaCheck* 或 @Anonymous")
    void controllerHttpMappingsMustDeclareExplicitAuthOrAnonymous() {
        CONTROLLER_HTTP_MAPPINGS_AUTH_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("14. system-api 全部 DTO 与 Command 类必须实现 Serializable（排除 Lombok Builder）")
    void systemApiDtosAndCommandsMustImplementSerializable() {
        assertThat(importedClasses.stream().filter(SYSTEM_API_DTO_OR_COMMAND_SCOPE.and(CONCRETE_DTO_OR_COMMAND)))
                .hasSizeGreaterThanOrEqualTo(9);
        SYSTEM_API_SERIALIZABLE_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("15. service / support / grant 与非 HandlerInterceptor 的 security 类严禁依赖 jakarta.servlet")
    void nonWebBoundaryPackagesMustNotDependOnServlet() {
        NON_WEB_BOUNDARIES_NO_SERVLET_RULE.check(importedClasses);
    }

    @Test
    @DisplayName("守卫自检：规则 5、9、12~15 对正负单点样本必须精确触发或放行（防逻辑空洞与假阳性）")
    void shouldCatchSingleViolationSamplesOnSelfCheck() {
        JavaClasses bootstrapControllerSample = new ClassFileImporter().importClasses(ForbiddenBootstrapController.class);
        assertThatThrownBy(() -> BOOTSTRAP_PURITY_RULE.check(bootstrapControllerSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses bootstrapMapperSample = new ClassFileImporter().importClasses(ForbiddenBootstrapMapper.class);
        assertThatThrownBy(() -> BOOTSTRAP_PURITY_RULE.check(bootstrapMapperSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses valueFieldSample = new ClassFileImporter().importClasses(ForbiddenValueFieldBean.class);
        assertThatThrownBy(() -> NO_VALUE_OR_REFRESH_SCOPE_RULE.check(valueFieldSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses controllerWithMapperSample = new ClassFileImporter()
                .importClasses(ForbiddenControllerWithMapper.class, ForbiddenBootstrapMapper.class);
        assertThatThrownBy(() -> CONTROLLERS_NO_MAPPER_RULE.check(controllerWithMapperSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses unannotatedMappingSample = new ClassFileImporter()
                .importClasses(ForbiddenUnannotatedMappingController.class);
        assertThatThrownBy(() -> CONTROLLER_HTTP_MAPPINGS_AUTH_RULE.check(unannotatedMappingSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses validAuthMappingSamples = new ClassFileImporter()
                .importClasses(ValidClassAnnotatedController.class, ValidMethodAnnotatedController.class);
        CONTROLLER_HTTP_MAPPINGS_AUTH_RULE.check(validAuthMappingSamples);

        ArchRule dtoSerializableSelfCheckRule = classes()
                .that(CONCRETE_DTO_OR_COMMAND)
                .should().implement(Serializable.class)
                .allowEmptyShould(true);
        JavaClasses unserializableDtoSample = new ClassFileImporter().importClasses(ForbiddenUnserializableDTO.class);
        assertThatThrownBy(() -> dtoSerializableSelfCheckRule.check(unserializableDtoSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses validSerializableAndBuilderSample = new ClassFileImporter()
                .importClasses(ValidSerializableRecordDTO.class, IgnoredNonSerializableDTOBuilder.class);
        dtoSerializableSelfCheckRule.check(validSerializableAndBuilderSample);

        assertThat(ALLOWED_SECURITY_INTERCEPTOR.test(
                importedClasses.get(TenantAvailabilityInterceptor.class)
        )).isTrue();

        ArchRule servletInterceptorSelfCheckRule = noClasses()
                .that(DescribedPredicate.not(assignableTo(HandlerInterceptor.class)))
                .should().dependOnClassesThat().resideInAPackage(SERVLET_PACKAGE_PREFIX)
                .allowEmptyShould(true);
        JavaClasses forbiddenNonInterceptorServletSample = new ClassFileImporter()
                .importClasses(ForbiddenNonInterceptorServletUser.class);
        assertThatThrownBy(() -> servletInterceptorSelfCheckRule.check(forbiddenNonInterceptorServletSample))
                .isInstanceOf(AssertionError.class);

        JavaClasses validInterceptorServletSample = new ClassFileImporter()
                .importClasses(ValidHandlerInterceptorServletUser.class);
        servletInterceptorSelfCheckRule.check(validInterceptorServletSample);
    }

    private static boolean hasHttpMappingAnnotation(Collection<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (HTTP_MAPPING_ANNOTATIONS.contains(annotation.getRawType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAuthOrAnonymousAnnotation(Collection<? extends JavaAnnotation<?>> annotations) {
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass rawType = annotation.getRawType();
            if (Anonymous.class.getName().equals(rawType.getName())) {
                return true;
            }
            if (SA_CHECK_ANNOTATION_PACKAGE.equals(rawType.getPackageName())
                    && rawType.getSimpleName().startsWith("SaCheck")) {
                return true;
            }
        }
        return false;
    }

    private static ArchCondition<JavaMethod> requireAuthOrAnonymousAnnotation() {
        return new ArchCondition<>("在类或方法上显式标注 @SaCheck* 或 @Anonymous 鉴权注解") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean hasExplicitAuth = hasAuthOrAnonymousAnnotation(method.getAnnotations())
                        || hasAuthOrAnonymousAnnotation(method.getOwner().getAnnotations());
                if (!hasExplicitAuth) {
                    events.add(SimpleConditionEvent.violated(
                            method,
                            String.format("Controller 映射方法 %s 未在类或方法上显式标注 @SaCheck* 或 @Anonymous", method.getFullName())
                    ));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> forbidTypeInSignature(
            String description,
            Predicate<JavaClass> forbiddenTypePredicate,
            String violationSummary
    ) {
        return new ArchCondition<>(description) {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                checkPosition(method, "返回值", method.getReturnType(), events);
                for (JavaType paramType : method.getParameterTypes()) {
                    checkPosition(method, "参数", paramType, events);
                }
            }

            private void checkPosition(JavaMethod method, String position, JavaType type, ConditionEvents events) {
                for (JavaClass involved : type.getAllInvolvedRawTypes()) {
                    if (forbiddenTypePredicate.test(involved)) {
                        String message = String.format(
                                "方法 %s 的 %s %s %s",
                                method.getFullName(), position, violationSummary, involved.getName()
                        );
                        events.add(SimpleConditionEvent.violated(method, message));
                    }
                }
            }
        };
    }

    @RestController
    private static final class ForbiddenBootstrapController {
    }

    private interface ForbiddenBootstrapMapper {
    }

    private static final class ForbiddenValueFieldBean {
        @Value("${server.port:8080}")
        private String port;
    }

    @RestController
    private static final class ForbiddenControllerWithMapper {
        private final ForbiddenBootstrapMapper mapper;

        private ForbiddenControllerWithMapper(ForbiddenBootstrapMapper mapper) {
            this.mapper = mapper;
        }
    }

    @RestController
    private static final class ForbiddenUnannotatedMappingController {
        @GetMapping("/open-without-guard")
        public String open() {
            return "bad";
        }
    }

    @SaCheckLogin
    @RestController
    private static final class ValidClassAnnotatedController {
        @GetMapping("/class-guarded")
        public String guarded() {
            return "ok";
        }

        public static String helperWithoutMapping() {
            return "helper";
        }
    }

    @RestController
    private static final class ValidMethodAnnotatedController {
        @Anonymous
        @PostMapping("/method-anonymous")
        public String open() {
            return "ok";
        }
    }

    private static final class ForbiddenUnserializableDTO {
    }

    private record ValidSerializableRecordDTO(String code) implements Serializable {
    }

    private static final class IgnoredNonSerializableDTOBuilder {
    }

    private static final class ForbiddenNonInterceptorServletUser {
        @SuppressWarnings("unused")
        private HttpServletRequest request;
    }

    private static final class ValidHandlerInterceptorServletUser implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            return true;
        }
    }
}
