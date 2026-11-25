package io.github.atengk.solid;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Predicate;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 后端同源双装配架构红线自动化守卫测试，验证模块物理隔离、零字段注入、零 Entity/Result 泄漏与强类型配置边界。
 *
 * @author Ateng
 * @since 2026-11-19
 */
class ArchitectureGuardTest {

    private static final String RESULT_CLASS_NAME = "io.github.atengk.boot.core.model.Result";
    private static final String REFRESH_SCOPE_ANNOTATION =
            "org.springframework.cloud.context.config.annotation.RefreshScope";

    private static final DescribedPredicate<JavaClass> BOOTSTRAP_SCOPE =
            resideInAPackage("io.github.atengk.solid")
                    .or(resideInAPackage("io.github.atengk.solid.bootstrap.."))
                    .or(DescribedPredicate.describe("residing in ateng-solid-bootstrap artifact",
                            javaClass -> javaClass.getSource()
                                    .map(source -> source.getUri().toString().replace('\\', '/')
                                            .contains("/ateng-solid-bootstrap/"))
                                    .orElse(false)));

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
                        "jakarta.servlet.."
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
        DescribedPredicate<JavaClass> controllerType = DescribedPredicate.describe(
                "annotated with @RestController or @Controller",
                javaClass -> javaClass.isAnnotatedWith(RestController.class) || javaClass.isAnnotatedWith(Controller.class)
        );

        methods()
                .that().areDeclaredInClassesThat(controllerType)
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
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("7. Mapper 持久层禁止反向依赖 Controller 或 Service 层")
    void mapperMustNotDependOnControllerOrService() {
        DescribedPredicate<JavaClass> mapperClass = resideInAPackage("io.github.atengk.solid..mapper..")
                .or(simpleNameEndingWith("Mapper"));

        noClasses()
                .that(mapperClass)
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
    @DisplayName("守卫自检：规则 5 与规则 9 对单点违规样本必须实质触发失败（防组合逻辑漏判）")
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
}
