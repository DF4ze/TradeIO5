package fr.ses10doigts.tradeIO5.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garde « lecture seule » : aucune classe du package {@code service.connector} (code de production) ne déclare de méthode
 * d'ordre (buy, sell, placeOrder, newOrder, createOrder, cancelOrder). Échoue dès qu'une telle méthode apparaît.
 */
@DisplayName("service.connector : aucune méthode d'ordre")
class ConnectorNoOrderMethodTest {

    private static final String CONNECTOR_PATTERN = "classpath*:fr/ses10doigts/tradeIO5/service/connector/**/*.class";

    private static final Pattern ORDER_METHOD =
            Pattern.compile("(?i).*(buy|sell|placeorder|neworder|createorder|cancelorder).*");

    static List<String> orderMethodsOf(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && ORDER_METHOD.matcher(method.getName()).matches()) {
                found.add(type.getName() + "#" + method.getName());
            }
        }
        return found;
    }

    @Test
    @DisplayName("Aucune classe de production de service.connector ne déclare de méthode d'ordre")
    void connectorDeclaresNoOrderMethod() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (Resource resource : resolver.getResources(CONNECTOR_PATTERN)) {
            if (resource.getURL().getPath().contains("test-classes")) {
                continue;
            }
            String className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            violations.addAll(orderMethodsOf(Class.forName(className, false, getClass().getClassLoader())));
            scanned++;
        }

        assertTrue(scanned > 10, "scan du package connector vide : " + scanned);
        assertTrue(violations.isEmpty(), "Méthodes d'ordre interdites : " + violations);
    }

    @Test
    @DisplayName("Le détecteur signale bien une méthode d'ordre (le test ci-dessus échouerait)")
    void detectorFlagsOrderMethods() {
        assertEquals(List.of(), orderMethodsOf(Clean.class));
        assertFalse(orderMethodsOf(WithOrders.class).isEmpty());
        assertEquals(4, orderMethodsOf(WithOrders.class).size());
    }

    @SuppressWarnings("unused")
    static class Clean {
        void getAvailableBalances() {
        }
    }

    @SuppressWarnings("unused")
    static class WithOrders {
        void placeOrder() {
        }

        void buy() {
        }

        void doSell() {
        }

        void cancelOrder() {
        }
    }
}
