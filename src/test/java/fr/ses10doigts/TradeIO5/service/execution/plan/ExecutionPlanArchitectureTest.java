package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.controller.ExecutionPlanAdminController;
import fr.ses10doigts.tradeIO5.controller.RainbowLiveExecutionPlanController;
import fr.ses10doigts.tradeIO5.service.scheduler.ExecutionPlanJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde « rien n'est envoyé » du plan d'ordres : aucune classe de {@code service.execution.plan} (ni le job, ni les
 * endpoints) ne dépend, directement ou transitivement, d'un port / client d'ordre (inexistant à ce stade) ni de
 * {@code ProviderApiService} ; aucune méthode d'ordre n'y est déclarée. Le mode {@code LIVE} échoue au démarrage
 * (cf. {@code OrderPlanServiceTest#modes}).
 */
@DisplayName("Plan d'ordres : aucun chemin d'envoi d'ordre")
class ExecutionPlanArchitectureTest {

    private static final String PROJECT_PACKAGE = "fr.ses10doigts.tradeIO5.";
    private static final List<Class<?>> ROOTS = List.of(ExecutionPlanJob.class, ExecutionPlanAdminController.class,
            RainbowLiveExecutionPlanController.class);

    private static boolean isForbidden(Class<?> type) {
        String name = type.getName();
        String simple = type.getSimpleName();
        return name.endsWith(".ProviderApiService") || name.endsWith(".WalletService")
                || simple.matches(".*Order(Port|Gateway|Executor|Placer|Submitter).*")
                || simple.matches(".*(Spot|Trade|Trading)Order.*")
                || simple.endsWith("OrderClient");
    }

    private static List<Class<?>> dependenciesOf(Class<?> bean) {
        List<Class<?>> out = new ArrayList<>();
        for (Constructor<?> c : bean.getDeclaredConstructors()) {
            out.addAll(List.of(c.getParameterTypes()));
        }
        for (Field f : bean.getDeclaredFields()) {
            out.add(f.getType());
        }
        return out;
    }

    private static List<Class<?>> planPackageBeans() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> beans = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(OrderPlanner.class.getPackageName())) {
            beans.add(Class.forName(definition.getBeanClassName()));
        }
        return beans;
    }

    @Test
    @DisplayName("Dépendances transitives du plan, du job et des endpoints : ni port d'ordre, ni ProviderApiService")
    void noOrderDependency() throws Exception {
        List<Class<?>> roots = new ArrayList<>(ROOTS);
        roots.addAll(planPackageBeans());
        assertTrue(roots.size() > 5, "scan du package plan vide : " + roots);
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            Class<?> bean = todo.poll();
            if (!seen.add(bean)) {
                continue;
            }
            for (Class<?> dep : dependenciesOf(bean)) {
                assertFalse(isForbidden(dep), bean.getName() + " dépend de " + dep.getName());
                if (dep.getName().startsWith(PROJECT_PACKAGE) && !dep.isEnum() && !dep.isInterface()) {
                    todo.add(dep);
                }
            }
        }
    }

    @Test
    @DisplayName("Aucune méthode d'envoi d'ordre (placeOrder/newOrder/createOrder/cancelOrder/submit) dans le package du plan")
    void noOrderMethod() throws Exception {
        for (Class<?> bean : planPackageBeans()) {
            for (Method m : bean.getDeclaredMethods()) {
                assertFalse(m.getName().matches("(?i).*(placeorder|neworder|createorder|cancelorder|submit).*"),
                        bean.getName() + "#" + m.getName());
            }
        }
    }
}
