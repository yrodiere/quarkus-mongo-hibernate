package io.quarkiverse.mongohibernate.deployment;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;

import com.mongodb.client.MongoClient;

import io.quarkiverse.mongohibernate.runtime.MongoDbHibernateConfig;
import io.quarkiverse.mongohibernate.runtime.MongoDbHibernatePersistenceUnitConfig;
import io.quarkiverse.mongohibernate.runtime.MongoDbHibernateRecorder;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.hibernate.orm.deployment.component.PersistenceUnitDefinitionBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.HibernateOrmIntegrationRuntimeConfiguredBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.HibernateOrmIntegrationStaticConfiguredBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.client.HibernateOrmClientDefinedBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.client.HibernateOrmClientHandlerBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.client.HibernateOrmClientRequestBuildItem;
import io.quarkus.hibernate.orm.deployment.spi.component.PersistenceUnitRequestBuildItem;
import io.quarkus.mongodb.MongoClientName;
import io.quarkus.mongodb.deployment.spi.MongoClientBuildItem;
import io.quarkus.mongodb.deployment.spi.MongoClientsBuildItem;
import io.quarkus.mongodb.runtime.MongoConfig;
import io.quarkus.runtime.configuration.ConfigUtils;
import io.quarkus.runtime.util.ProgrammingParadigm;
import io.quarkus.runtime.util.Reason;

class MongoDbHibernateProcessor {

    private static final String FEATURE = "mongodb-hibernate";

    private static final DotName MONGO_CLIENT_NAME_ANNOTATION = DotName.createSimple(MongoClientName.class.getName());

    private static final Map<String, String> CLIENT_PROPERTIES = Map.of(
            "hibernate.connection.provider_class", ClassNames.JTA_AWARE_MONGO_CONNECTION_PROVIDER);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void indexMongoDbHibernateDependency(BuildProducer<IndexDependencyBuildItem> index) {
        index.produce(new IndexDependencyBuildItem("org.mongodb", "mongodb-hibernate"));
    }

    @BuildStep
    void contributeConfiguredPersistenceUnits(
            MongoDbHibernateConfig mongoDbHibernateConfig,
            BuildProducer<PersistenceUnitRequestBuildItem> persistenceUnitRequests) {
        for (String puName : mongoDbHibernateConfig.persistenceUnits().keySet()) {
            persistenceUnitRequests.produce(new PersistenceUnitRequestBuildItem(
                    puName, ProgrammingParadigm.BLOCKING, "MongoDB Hibernate configuration detected"));
        }
    }

    // Mirrors the client discovery logic from MongoClientProcessor#mongoClients:
    // the default client is always available, and named clients are discovered
    // from @MongoClientName annotations in the application index.
    @BuildStep
    void registerClientLookup(CombinedIndexBuildItem indexBuildItem,
            BuildProducer<HibernateOrmClientHandlerBuildItem> clientLookup) {
        Set<String> knownClientNames = discoverClientNames(indexBuildItem.getIndex());
        clientLookup.produce(new HibernateOrmClientHandlerBuildItem((name, paradigm) -> {
            if (paradigm == ProgrammingParadigm.REACTIVE) {
                return List.of(new Reason(
                        "Persistence units using an external client do not support Hibernate Reactive"));
            }
            if (!knownClientNames.contains(name)) {
                return List.of(new Reason(String.format(java.util.Locale.ROOT,
                        "No MongoDB client '%s' is configured."
                                + " Named MongoDB clients are discovered from @MongoClientName(\"%s\")"
                                + " annotations in your application."
                                + " Add an injection point annotated with @MongoClientName(\"%s\")"
                                + " to make the client available.",
                        name, name, name)));
            }
            return List.of();
        }));
    }

    // Producing HibernateOrmClientDefinedBuildItem from MongoClientsBuildItem would be ideal,
    // but creates a build cycle: HibernateOrmClientDefinedBuildItem is consumed during
    // PU definition, which feeds into PersistenceUnitDescriptorBuildItem, then into
    // AdditionalBeanBuildItem, BeanRegistrationPhaseBuildItem, and back to MongoClientsBuildItem.
    // Instead, we replicate the MongoDB extension's discovery logic.
    @BuildStep
    void defineAvailableClients(CombinedIndexBuildItem indexBuildItem,
            BuildProducer<HibernateOrmClientDefinedBuildItem> definedClients) {
        for (String clientName : discoverClientNames(indexBuildItem.getIndex())) {
            definedClients.produce(new HibernateOrmClientDefinedBuildItem(
                    clientName, Set.of(ProgrammingParadigm.BLOCKING),
                    ClassNames.MONGO_DIALECT, CLIENT_PROPERTIES,
                    isMongoDevServicesEnabled(clientName)));
        }
    }

    // Validates that clients advertised in defineAvailableClients actually exist
    // in the MongoDB extension.
    @BuildStep
    @Produce(ArtifactResultBuildItem.class)
    void validateDefinedClients(MongoClientsBuildItem mongoClients,
            List<HibernateOrmClientDefinedBuildItem> definedClients) {
        Set<String> actualClientNames = new HashSet<>();
        for (MongoClientBuildItem mongoClient : mongoClients.getMongoClients()) {
            actualClientNames.add(mongoClient.getName());
        }
        for (HibernateOrmClientDefinedBuildItem defined : definedClients) {
            if (!actualClientNames.contains(defined.getName())) {
                throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                        "MongoDB Hibernate extension advertised client '%s' but the MongoDB extension"
                                + " did not create it. This is a bug in the MongoDB Hibernate extension.",
                        defined.getName()));
            }
        }
    }

    @BuildStep
    void handleClientRequests(List<HibernateOrmClientRequestBuildItem> requests,
            BuildProducer<MongoClientBuildItem> mongoClientBuildItems) {
        for (HibernateOrmClientRequestBuildItem request : requests) {
            mongoClientBuildItems.produce(MongoClientBuildItem.ofUnremovable(request.getName()));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void configureMongoDbPersistenceUnitsStaticInit(
            MongoDbHibernateConfig mongoDbHibernateConfig,
            List<PersistenceUnitDefinitionBuildItem> puDefinitions,
            MongoDbHibernateRecorder recorder,
            BuildProducer<HibernateOrmIntegrationStaticConfiguredBuildItem> staticConfigured) {
        for (PersistenceUnitDefinitionBuildItem puDefinition : puDefinitions) {
            if (puDefinition.getClientName().isEmpty()) {
                continue;
            }
            String puName = puDefinition.getPersistenceUnitName();
            MongoDbHibernatePersistenceUnitConfig puConfig = mongoDbHibernateConfig.persistenceUnits().get(puName);
            staticConfigured.produce(
                    HibernateOrmIntegrationStaticConfiguredBuildItem.builder(FEATURE, puName)
                            .initListener(recorder.createStaticInitListener(puConfig.query().nullSemantics()))
                            .build());
        }
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void configureMongoDbPersistenceUnitsRuntimeInit(
            List<PersistenceUnitDefinitionBuildItem> puDefinitions,
            MongoDbHibernateRecorder recorder,
            BuildProducer<HibernateOrmIntegrationRuntimeConfiguredBuildItem> runtimeConfigured) {
        for (PersistenceUnitDefinitionBuildItem puDefinition : puDefinitions) {
            if (puDefinition.getClientName().isEmpty()) {
                continue;
            }
            String clientName = puDefinition.getClientName().get();
            runtimeConfigured.produce(
                    HibernateOrmIntegrationRuntimeConfiguredBuildItem.builder(FEATURE, puDefinition.getPersistenceUnitName())
                            .initListener(recorder.createRuntimeInitListener(clientName))
                            .build());
        }
    }

    @BuildStep
    void registerMongoDbHibernateReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClasses) {
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                ClassNames.MONGO_DIALECT,
                ClassNames.JTA_AWARE_MONGO_CONNECTION_PROVIDER,
                ClassNames.MONGO_NAMED_STRATEGY_CONTRIBUTOR,
                ClassNames.MONGO_ADDITIONAL_MAPPING_CONTRIBUTOR,
                ClassNames.MONGO_SERVICE_REGISTRY_SCOPED_STATE,
                ClassNames.MONGO_CONFIGURATION)
                .constructors(true).methods(true).fields(true).build());
        // Hibernate ORM needs to reflectively instantiate ObjectId[] for ID array operations;
        // the core extension only registers standard JDBC types (HHH-16809 workaround).
        reflectiveClasses.produce(ReflectiveClassBuildItem.builder(
                "org.bson.types.ObjectId",
                "org.bson.types.ObjectId[]")
                .build());
    }

    /**
     * Discovers MongoDB client names by replicating the logic from
     * {@code MongoClientProcessor#mongoClients}: the default client is always
     * present, and named clients are discovered from {@link MongoClientName}
     * annotations in the application index.
     */
    private static Set<String> discoverClientNames(IndexView index) {
        Set<String> clientNames = new HashSet<>();
        clientNames.add(MongoConfig.DEFAULT_CLIENT_NAME);
        Collection<AnnotationInstance> annotations = index.getAnnotations(MONGO_CLIENT_NAME_ANNOTATION);
        for (AnnotationInstance annotation : annotations) {
            clientNames.add(annotation.value().asString());
        }
        return clientNames;
    }

    private static boolean isMongoDevServicesEnabled(String clientName) {
        boolean explicitlyDisabled = ConfigProvider.getConfig()
                .getOptionalValue(MongoConfig.getPropertyName(clientName, "devservices.enabled"), Boolean.class)
                .map(enabled -> !enabled)
                .orElse(false);
        if (explicitlyDisabled) {
            return false;
        }
        return !ConfigUtils.isPropertyNonEmpty(MongoConfig.getPropertyName(clientName, "connection-string"))
                && !ConfigUtils.isPropertyNonEmpty(MongoConfig.getPropertyName(clientName, "hosts"));
    }
}
