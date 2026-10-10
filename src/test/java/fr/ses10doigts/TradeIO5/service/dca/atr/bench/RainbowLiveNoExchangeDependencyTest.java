package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.controller.RainbowLiveAdminController;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.scheduler.RainbowLivePass0005Job;
import fr.ses10doigts.tradeIO5.service.scheduler.RainbowLivePass2355Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Garde « aucun ordre » : aucun bean du lot (persistance, exécution, jobs, endpoint admin) ne dépend, directement ou
 * transitivement (constructeurs et champs des classes du projet), d'un chemin d'exécution d'ordre / d'exchange.
 * Deux exceptions : la LECTURE de marché ({@link MarketDatasetEngine}) et le port de portefeuille
 * ({@link RainbowPortfolioSource} et ses implémentations, seules à lire un wallet, en lecture seule) ; on ne descend pas
 * dedans. Le second test impose que tout bean du package du bench qui touche un exchange/wallet soit une implémentation
 * du port.
 */
@DisplayName("Bench grandeur nature : aucun accès exchange / ordre")
class RainbowLiveNoExchangeDependencyTest {

    private static final List<Class<?>> BEANS = List.of(RainbowLivePresetService.class, RainbowLiveRunService.class,
            RainbowLiveExecutionService.class, RainbowLivePass2355Job.class, RainbowLivePass0005Job.class,
            RainbowLiveAdminController.class);

    private static final String PROJECT_PACKAGE = "fr.ses10doigts.tradeIO5.";

    private static boolean isForbidden(Class<?> type) {
        String name = type.getName();
        String simple = type.getSimpleName();
        return name.endsWith(".ProviderApiService") || name.endsWith(".WalletService")
                || name.endsWith(".model.entity.currency.Wallet")
                || name.contains(".service.connector.")
                || name.contains(".service.market.provider.")
                || simple.contains("MarketDataApiClient")
                || simple.matches(".*(Order|Trade|Trading).*(Service|Client|Executor|Gateway)")
                || simple.matches(".*(Service|Client|Executor|Gateway).*Order.*");
    }

    private static boolean isAllowedMarketRead(Class<?> type) {
        return type == MarketDatasetEngine.class || RainbowPortfolioSource.class.isAssignableFrom(type);
    }

    @Test
    @DisplayName("Dépendances (transitives, hors lecture de marché) : ni ProviderApiService, ni WalletService/Wallet, ni client d'ordre")
    void noOrderExecutionPath() {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>(BEANS);
        while (!todo.isEmpty()) {
            Class<?> bean = todo.poll();
            if (!seen.add(bean) || isAllowedMarketRead(bean)) {
                continue;
            }
            for (Class<?> dep : dependenciesOf(bean)) {
                assertFalse(isForbidden(dep), bean.getName() + " dépend de " + dep.getName());
                if (dep.getName().startsWith(PROJECT_PACKAGE) && !dep.isEnum()) {
                    todo.add(dep);
                }
            }
        }
    }

    private static List<Class<?>> dependenciesOf(Class<?> bean) {
        java.util.ArrayList<Class<?>> out = new java.util.ArrayList<>();
        for (Constructor<?> c : bean.getDeclaredConstructors()) {
            out.addAll(List.of(c.getParameterTypes()));
        }
        for (Field f : bean.getDeclaredFields()) {
            out.add(f.getType());
        }
        return out;
    }

    @Test
    @DisplayName("Seules les implémentations de RainbowPortfolioSource touchent un exchange / un wallet dans le package du bench")
    void onlyPortfolioSourcesTouchExchange() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        for (BeanDefinition definition : scanner.findCandidateComponents(RainbowLiveExecutionService.class.getPackageName())) {
            Class<?> bean = classOf(definition);
            if (RainbowPortfolioSource.class.isAssignableFrom(bean)) {
                continue;
            }
            for (Class<?> dep : dependenciesOf(bean)) {
                assertFalse(isForbidden(dep), bean.getName() + " dépend de " + dep.getName()
                        + " : seul RainbowPortfolioSource peut atteindre un exchange");
            }
        }
    }

    @Test
    @DisplayName("Le port ne déclare aucune opération d'ordre")
    void portHasNoOrderMethod() {
        for (java.lang.reflect.Method m : RainbowPortfolioSource.class.getMethods()) {
            assertFalse(m.getName().matches("(?i).*(buy|sell|order|trade).*"), "méthode d'ordre : " + m.getName());
        }
    }

    private static Class<?> classOf(BeanDefinition definition) {
        try {
            return Class.forName(definition.getBeanClassName());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
