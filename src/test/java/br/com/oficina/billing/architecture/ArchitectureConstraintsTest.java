package br.com.oficina.billing.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ArchitectureConstraintsTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final String BASE_PACKAGE = "br.com.oficina.billing";
    private static final Set<String> REQUIRED_LAYERS = Set.of("core", "interfaces", "framework");
    private static final Set<String> LEGACY_CORE_FRAMEWORK_IMPORT_EXCEPTIONS = Set.of();
    private static final Set<String> LEGACY_INTERFACE_FRAMEWORK_IMPORT_EXCEPTIONS = Set.of();

    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);");
    private static final Pattern FORBIDDEN_FRAMEWORK_IMPORT = Pattern.compile(
            "(?m)^import\\s+(io\\.quarkus|io\\.smallrye|jakarta\\.(annotation\\.security|enterprise|inject|persistence|ws\\.rs)|javax\\.sql|org\\.eclipse\\.microprofile|org\\.jboss)\\.");
    private static final Pattern FORBIDDEN_FRAMEWORK_ANNOTATION = Pattern.compile(
            "@(ApplicationScoped|RequestScoped|Inject|Path|GET|POST|PUT|DELETE|PATCH|Produces|Consumes|RolesAllowed|PermitAll|WithSession|WithTransaction|Entity|Table)\\b");
    private static final Pattern FRAMEWORK_IMPORT = Pattern.compile(
            "(?m)^import\\s+br\\.com\\.oficina\\.billing\\.framework\\.");
    private static final Pattern PUBLIC_INSTANCE_METHOD = Pattern.compile(
            "(?m)^\\s+public\\s+(?!static\\b|record\\b|class\\b|interface\\b|enum\\b)([^\\s(]+(?:<[^\\n{;()]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");
    private static final Pattern PUBLIC_RESOURCE_METHOD = Pattern.compile(
            "(?m)^\\s+public\\s+(?!record\\b|class\\b|interface\\b|enum\\b)([^\\s(]+(?:<[^\\n{;()]*>)?)\\s+([a-zA-Z_$][\\w$]*)\\s*\\(");

    @Test
    void deveManterLayoutComCoreInterfacesEFrameworkNaRaizDoServico() {
        var violations = new ArrayList<String>();
        var layers = new TreeSet<String>();

        for (SourceFile source : javaSources()) {
            var packageName = packageName(source);
            if (!packageName.startsWith(BASE_PACKAGE + ".")) {
                violations.add(source.relativePath() + " declara pacote fora de " + BASE_PACKAGE + ": " + packageName);
                continue;
            }

            var packageSuffix = packageName.substring((BASE_PACKAGE + ".").length());
            var packageParts = packageSuffix.split("\\.");
            var layerName = packageParts[0];
            if (!REQUIRED_LAYERS.contains(layerName)) {
                violations.add(source.relativePath() + " deve estar em "
                        + BASE_PACKAGE + ".<core|interfaces|framework>: " + packageName);
                continue;
            }
            layers.add(layerName);
        }

        if (!layers.containsAll(REQUIRED_LAYERS)) {
            violations.add("Servico deve manter as camadas " + REQUIRED_LAYERS + ", mas possui " + layers);
        }

        assertNoViolations(violations);
    }

    @Test
    void coreNaoPodeDependerDeFrameworkHttpPersistenciaOuCdi() {
        var coreSources = javaSources().stream()
                .filter(source -> isLayer(source, "core"))
                .toList();

        assertFalse(coreSources.isEmpty(), "Nenhum fonte em core foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : coreSources) {
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_IMPORT, violations,
                    "core nao deve importar framework HTTP, CDI, persistencia, MicroProfile, JDBC/JPA ou logs tecnicos");
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_ANNOTATION, violations,
                    "core nao deve declarar anotacoes de framework");
            if (FRAMEWORK_IMPORT.matcher(source.content()).find()
                    && !LEGACY_CORE_FRAMEWORK_IMPORT_EXCEPTIONS.contains(source.relativePath())) {
                violations.add(source.relativePath()
                        + ": core nao deve importar framework; mova o contrato para core/interfaces");
            }
            if (source.content().contains("Uni<") || source.content().contains("subscribeAsCompletionStage()")) {
                violations.add(source.relativePath() + ": core deve expor CompletableFuture, nao Uni/Mutiny");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void interfacesDevemSerClassesPurasSemAnotacoesDeFramework() {
        var interfaceSources = javaSources().stream()
                .filter(source -> isLayer(source, "interfaces"))
                .toList();

        assertFalse(interfaceSources.isEmpty(), "Nenhum fonte em interfaces foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : interfaceSources) {
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_IMPORT, violations,
                    "interfaces nao devem importar framework HTTP, CDI, persistencia ou MicroProfile");
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_ANNOTATION, violations,
                    "interfaces nao devem declarar anotacoes de framework");

            if (FRAMEWORK_IMPORT.matcher(source.content()).find()
                    && !LEGACY_INTERFACE_FRAMEWORK_IMPORT_EXCEPTIONS.contains(source.relativePath())) {
                violations.add(source.relativePath()
                        + ": interfaces nao devem importar framework; mova o tipo compartilhado para interfaces ou core");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void controllersDevemMapearRequestsParaUseCasesSemConhecerHttp() {
        var controllerSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/interfaces/controllers/"))
                .filter(source -> source.relativePath().endsWith("Controller.java"))
                .toList();

        assertFalse(controllerSources.isEmpty(), "Nenhum controller foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : controllerSources) {
            if (!packageName(source).endsWith(".interfaces.controllers")) {
                violations.add(source.relativePath() + " deve estar em pacote .interfaces.controllers");
            }
            if (!source.content().contains("import java.util.concurrent.CompletableFuture;")) {
                violations.add(source.relativePath() + " deve expor operacoes assincronas com CompletableFuture");
            }

            var matcher = PUBLIC_INSTANCE_METHOD.matcher(source.content());
            while (matcher.find()) {
                var returnType = matcher.group(1);
                var methodName = matcher.group(2);
                if (!returnType.startsWith("CompletableFuture<")) {
                    violations.add(source.relativePath() + ": metodo publico de instancia " + methodName
                            + " deve retornar CompletableFuture, mas retorna " + returnType);
                }
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void useCasesDevemUsarMetodoExecutarECompletableFuture() {
        var useCaseSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/core/usecases/"))
                .filter(source -> source.relativePath().endsWith("UseCase.java"))
                .toList();

        assertFalse(useCaseSources.isEmpty(), "Nenhum use case foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : useCaseSources) {
            if (!source.content().contains("CompletableFuture<")) {
                violations.add(source.relativePath() + " deve retornar CompletableFuture");
            }
            if (!source.content().contains(" executar(")) {
                violations.add(source.relativePath() + " deve ter metodo principal executar(...)");
            }
            if (source.content().contains("Command command") && !source.content().contains("record Command")) {
                violations.add(source.relativePath() + " usa Command mas nao declara record Command interno");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void resourcesDevemConterSomenteBordaHttpReativa() {
        var resourceSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/framework/web/"))
                .filter(source -> source.relativePath().endsWith("Resource.java"))
                .toList();

        assertFalse(resourceSources.isEmpty(), "Nenhum resource foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : resourceSources) {
            if (!packageName(source).contains(".framework.web")) {
                violations.add(source.relativePath() + " deve estar abaixo de .framework.web");
            }
            if (!source.content().contains("@Path(")) {
                violations.add(source.relativePath() + " deve declarar @Path na borda HTTP");
            }
            if (!source.content().contains("import io.smallrye.mutiny.Uni;")) {
                violations.add(source.relativePath() + " deve adaptar CompletableFuture para Uni");
            }

            var matcher = PUBLIC_RESOURCE_METHOD.matcher(source.content());
            while (matcher.find()) {
                var returnType = matcher.group(1);
                var methodName = matcher.group(2);
                if (!returnType.startsWith("Uni<")) {
                    violations.add(source.relativePath() + ": metodo publico " + methodName
                            + " deve retornar Uni, mas retorna " + returnType);
                }
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void dataSourceAdaptersDevemImplementarGatewayComApplicationScopedECompletableFuture() {
        var adapterSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/framework/"))
                .filter(source -> source.relativePath().endsWith("DataSourceAdapter.java"))
                .toList();

        assertFalse(adapterSources.isEmpty(), "Nenhum DataSourceAdapter foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : adapterSources) {
            if (!source.content().contains("@ApplicationScoped")) {
                violations.add(source.relativePath() + " deve ser @ApplicationScoped");
            }
            if (!source.content().contains(" implements ") || !source.content().contains("Gateway")) {
                violations.add(source.relativePath() + " deve implementar um Gateway do core");
            }
            if (!source.content().contains("CompletableFuture<")) {
                violations.add(source.relativePath() + " deve expor CompletableFuture");
            }
        }

        assertNoViolations(violations);
    }

    @Test
    void presentersDevemSerAdaptersStatefulSemCdiDireto() {
        var presenterSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/interfaces/presenters/"))
                .filter(source -> source.relativePath().endsWith("PresenterAdapter.java"))
                .toList();

        assertFalse(presenterSources.isEmpty(), "Nenhum presenter adapter foi encontrado");

        var violations = new ArrayList<String>();
        for (SourceFile source : presenterSources) {
            if (!source.content().contains("private ")) {
                violations.add(source.relativePath() + " deve armazenar estado da request em campo privado");
            }
            if (!source.content().contains("viewModel()") && !source.content().contains("viewModels()")) {
                violations.add(source.relativePath() + " deve expor viewModel() ou viewModels()");
            }
            assertDoesNotMatch(source, FORBIDDEN_FRAMEWORK_ANNOTATION, violations,
                    "presenters devem ser produzidos pela configuracao CDI, nao anotados diretamente");
        }

        assertNoViolations(violations);
    }

    @Test
    void configuracoesDevemComporUseCasesControllersEPresenters() {
        var configurationSources = javaSources().stream()
                .filter(source -> source.relativePath().contains("/framework/web/"))
                .filter(source -> source.relativePath().endsWith("Configuration.java"))
                .toList();

        assertFalse(configurationSources.isEmpty(), "Nenhuma configuracao CDI foi encontrada");

        var violations = new ArrayList<String>();
        for (SourceFile source : configurationSources) {
            if (!source.content().contains("@ApplicationScoped")) {
                violations.add(source.relativePath() + " deve ser @ApplicationScoped");
            }
            if (!source.content().contains("@Produces")) {
                violations.add(source.relativePath() + " deve declarar producers de composicao");
            }
            if (source.content().contains("PresenterAdapter") && !source.content().contains("@RequestScoped")) {
                violations.add(source.relativePath() + " deve produzir presenters stateful como @RequestScoped");
            }
        }

        assertNoViolations(violations);
    }

    private static List<SourceFile> javaSources() {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(SourceFile::read)
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String packageName(SourceFile source) {
        var matcher = PACKAGE_DECLARATION.matcher(source.content());
        assertTrue(matcher.find(), () -> source.relativePath() + " nao declara package");
        return matcher.group(1);
    }

    private static boolean isLayer(SourceFile source, String layerName) {
        var packageName = packageName(source);
        if (!packageName.startsWith(BASE_PACKAGE + ".")) {
            return false;
        }

        var packageParts = packageName.substring((BASE_PACKAGE + ".").length()).split("\\.");
        return packageParts.length > 0 && layerName.equals(packageParts[0]);
    }

    private static void assertDoesNotMatch(SourceFile source,
                                           Pattern pattern,
                                           List<String> violations,
                                           String message) {
        if (pattern.matcher(source.content()).find()) {
            violations.add(source.relativePath() + ": " + message);
        }
    }

    private static void assertNoViolations(List<String> violations) {
        assertTrue(violations.isEmpty(), () -> "Violacoes arquiteturais encontradas:\n- "
                + String.join("\n- ", violations));
    }

    private record SourceFile(Path path, String content) {
        static SourceFile read(Path path) {
            try {
                return new SourceFile(path, Files.readString(path));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        String relativePath() {
            return path.toString().replace('\\', '/');
        }
    }
}
