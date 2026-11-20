package io.github.atengk.solid;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 后端同源双装配架构红线自动化守卫测试，验证模块物理隔离、零字段注入、零 Entity 泄漏及启动包纯净性。
 *
 * @author Ateng
 * @since 2026-11-19
 */
class ArchitectureGuardTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.atengk.solid");
    }

    @Test
    @DisplayName("auth 模块禁止直接依赖 system 除 system.api 外的全部实现包")
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
    @DisplayName("system-api 契约层禁止引入 Web、MyBatis-Plus、Feign 或 Dubbo 框架依赖")
    void systemApiMustRemainFrameworkPure() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid.system.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "com.baomidou..",
                        "org.springframework.cloud.openfeign..",
                        "org.apache.dubbo..",
                        "jakarta.servlet.."
                )
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("全局业务源码禁止使用 @Autowired / @Resource 字段注入（强制构造器注入）")
    void noFieldInjectionAllowed() {
        fields()
                .should().notBeAnnotatedWith(Autowired.class)
                .andShould().notBeAnnotatedWith(Resource.class)
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Controller 公开方法禁止返回或接收持久层 Entity（含泛型嵌套参数与返回值）")
    void controllersMustNotLeakEntities() {
        ArchCondition<JavaMethod> notExposeEntityInSignature = new ArchCondition<>("不在参数或返回值（含泛型）中暴露 entity 包类") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                checkTypeNoEntity(method, "返回值", method.getReturnType(), events);
                for (JavaType paramType : method.getParameterTypes()) {
                    checkTypeNoEntity(method, "参数", paramType, events);
                }
            }

            private void checkTypeNoEntity(JavaMethod method, String position, JavaType type, ConditionEvents events) {
                for (JavaClass involved : type.getAllInvolvedRawTypes()) {
                    if (involved.getPackageName().contains(".entity")) {
                        String message = String.format(
                                "Controller 方法 %s 的 %s 泄漏了持久层实体 %s",
                                method.getFullName(), position, involved.getName()
                        );
                        events.add(SimpleConditionEvent.violated(method, message));
                    }
                }
            }
        };

        methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
                .or().areDeclaredInClassesThat().areAnnotatedWith(Controller.class)
                .and().arePublic()
                .should(notExposeEntityInSignature)
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("bootstrap 启动装配包禁止承载业务 Controller 或持久层 Mapper")
    void bootstrapMustNotContainBusinessControllersOrMappers() {
        noClasses()
                .that().resideInAPackage("io.github.atengk.solid")
                .or().resideInAPackage("io.github.atengk.solid.bootstrap..")
                .should().beAnnotatedWith(RestController.class)
                .orShould().beAnnotatedWith(Controller.class)
                .orShould().haveSimpleNameEndingWith("Mapper")
                .allowEmptyShould(true)
                .check(importedClasses);
    }
}
