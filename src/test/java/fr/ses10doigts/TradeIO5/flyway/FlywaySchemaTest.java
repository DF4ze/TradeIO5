package fr.ses10doigts.tradeIO5.flyway;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.ImplicitNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Flyway préparé mais non activé (docs/operations/flyway.md) : génère le schéma MySQL depuis les ENTITÉS (jamais depuis
 * la base) et contrôle le script {@code V1__init.sql}.
 * <ul>
 *   <li>{@code mvn test -Dtest=FlywaySchemaTest -Dflyway.generate=true} : (ré)écrit {@code V1__init.sql}.</li>
 *   <li>{@code mvn test -Dtest=FlywaySchemaTest -Dflyway.check=true} : échoue si {@code V1__init.sql} diverge des entités
 *       (à lancer juste avant l'activation de Flyway).</li>
 *   <li>Toujours exécuté : le script s'applique sur une base vide (H2 en mode MySQL, test de fumée).</li>
 * </ul>
 */
@DisplayName("Flyway (préparé) : V1 généré depuis les entités")
class FlywaySchemaTest {

    private static final String BASE_PACKAGE = "fr.ses10doigts.tradeIO5";
    private static final Path V1 = Path.of("src/main/resources/db/migration/V1__init.sql");
    private static final String HEADER = """
            -- V1 : schéma initial, GÉNÉRÉ depuis les entités JPA (FlywaySchemaTest, -Dflyway.generate=true).
            -- Ne pas éditer à la main avant l'activation de Flyway ; une fois appliquée, ne plus jamais la modifier
            -- (toute évolution = nouvelle migration V<n>__*.sql, cf. docs/operations/flyway.md).
            """;

    /** Script de création du schéma MySQL déduit des entités, sans connexion à une base. */
    static String generateSchema() throws IOException {
        Path tmp = Files.createTempFile("tradeio5-schema", ".sql");
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MySQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName())
                .applySetting("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName())
                .applySetting("hibernate.format_sql", "true")
                // Génération de script JPA standard : aucun accès base, seulement un fichier.
                .applySetting("jakarta.persistence.schema-generation.database.action", "none")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target", tmp.toString())
                .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            // Tri par nom : script déterministe quel que soit l'ordre du scan classpath.
            for (String className : scanMappedClasses()) {
                sources.addAnnotatedClassName(className);
            }
            sources.buildMetadata().buildSessionFactory().close();
            return Files.readString(tmp);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
            Files.deleteIfExists(tmp);
        }
    }

    /** Noms triés des classes {@code @Entity}, {@code @Embeddable}, {@code @MappedSuperclass} et {@code @Converter}. */
    private static List<String> scanMappedClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(bd -> bd.getBeanClassName())
                .sorted()
                .collect(Collectors.toList());
    }

    /** Retire les fins de ligne Windows et les lignes vides pour comparer des scripts. */
    private static String normalize(String script) {
        return script.replace("\r\n", "\n").lines()
                .filter(l -> !l.isBlank() && !l.startsWith("--"))
                .collect(Collectors.joining("\n")).strip();
    }

    @Test
    @EnabledIfSystemProperty(named = "flyway.generate", matches = "true")
    @DisplayName("Génère V1__init.sql depuis les entités (-Dflyway.generate=true)")
    void generateV1() throws IOException {
        Files.createDirectories(V1.getParent());
        Files.writeString(V1, HEADER + generateSchema());
    }

    @Test
    @EnabledIfSystemProperty(named = "flyway.check", matches = "true")
    @DisplayName("V1__init.sql identique au schéma déduit des entités (-Dflyway.check=true)")
    void v1MatchesEntities() throws IOException {
        assertEquals(normalize(generateSchema()), normalize(Files.readString(V1)),
                "V1__init.sql diverge des entités : régénérer (-Dflyway.generate=true) tant que Flyway n'est pas activé");
    }

    @Test
    @DisplayName("Le schéma généré s'applique sur une base vide (H2 en mode MySQL)")
    void generatedSchemaAppliesOnEmptyDatabase() throws Exception {
        String script = generateSchema();
        assertFalse(script.isBlank());
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:flyway_v1;MODE=MySQL;DB_CLOSE_DELAY=-1");
             Statement st = c.createStatement()) {
            int statements = 0;
            for (String sql : script.split(";\\s*(\\r?\\n|$)")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                    statements++;
                }
            }
            assertFalse(statements == 0, "aucune instruction exécutée");
        }
    }
}
