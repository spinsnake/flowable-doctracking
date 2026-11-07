package th.ac.mfu.doctracking;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import th.ac.mfu.doctracking.common.RuntimeConfig;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionSchemaGuardTest {
    @Test void productionRejectsAutomaticUpgradeBeforeEngineBeanCreation() {
        for(String update:new String[]{"true","create-drop"}) {
            var created=new AtomicBoolean();
            new ApplicationContextRunner().withUserConfiguration(RuntimeConfig.class)
                    .withPropertyValues("spring.profiles.active=prod","flowable.database-schema-update="+update)
                    .withBean("engine",Object.class,()->{created.set(true);return new Object();})
                    .run(context->{assertThat(context).hasFailed();assertThat(created).isFalse();});
        }
    }
    @Test void controlledProductionAndIsolatedDevelopmentCanInitialize() {
        for(String profile:new String[]{"prod","test"}) {
            new ApplicationContextRunner().withUserConfiguration(RuntimeConfig.class)
                    .withPropertyValues("spring.profiles.active="+profile,"flowable.database-schema-update="+(profile.equals("prod")?"false":"true"))
                    .run(context->assertThat(context).hasNotFailed());
        }
    }
}
