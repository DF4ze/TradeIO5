package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.controller.RainbowLiveAdminController;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.scheduler.RainbowLivePass0005Job;
import fr.ses10doigts.tradeIO5.service.scheduler.RainbowLivePass2355Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * La LECTURE de marché ({@link MarketDatasetEngine}) est autorisée : on ne descend pas dedans.
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
        return type == MarketDatasetEngine.class;
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
}
