package io.quarkiverse.mongohibernate.runtime;

import java.util.List;
import java.util.function.BiConsumer;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.registry.StandardServiceInitiator;
import org.hibernate.boot.spi.BootstrapContext;

import com.mongodb.client.MongoClient;

import io.quarkus.arc.ClientProxy;
import io.quarkus.hibernate.orm.runtime.spi.HibernateOrmIntegrationRuntimeInitListener;
import io.quarkus.hibernate.orm.runtime.spi.HibernateOrmIntegrationStaticInitListener;
import io.quarkus.mongodb.runtime.MongoClientBeanUtil;
import io.quarkus.mongodb.runtime.MongoConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MongoDbHibernateRecorder {

    private final RuntimeValue<MongoConfig> mongoConfig;

    public MongoDbHibernateRecorder(RuntimeValue<MongoConfig> mongoConfig) {
        this.mongoConfig = mongoConfig;
    }

    public HibernateOrmIntegrationStaticInitListener createStaticInitListener(String nullSemantics) {
        return new HibernateOrmIntegrationStaticInitListener() {
            @Override
            public void contributeBootProperties(BiConsumer<String, Object> propertyCollector) {
                // mongo-hibernate parses the JDBC URL as a MongoDB connection string;
                // the real value is set at runtime init once config is available.
                propertyCollector.accept("jakarta.persistence.jdbc.url", "mongodb://build-time-placeholder:27017/placeholder");
                // mongo-hibernate does not support InArrayPredicate (batch fetching)
                propertyCollector.accept("hibernate.default_batch_fetch_size", "0");
                propertyCollector.accept("com.mongodb.hibernate.semantics.nulls", nullSemantics);
            }

            @Override
            public void onMetadataInitialized(Metadata metadata, BootstrapContext bootstrapContext,
                    BiConsumer<String, Object> propertyCollector) {
            }
        };
    }

    public HibernateOrmIntegrationRuntimeInitListener createRuntimeInitListener(String clientName) {
        return new HibernateOrmIntegrationRuntimeInitListener() {
            @Override
            public void contributeRuntimeProperties(BiConsumer<String, Object> propertyCollector) {
                io.quarkus.mongodb.runtime.MongoClientConfig clientConfig = mongoConfig.getValue().clients()
                        .get(MongoConfig.nameOrDefault(clientName));
                String connectionStringValue = clientConfig.connectionString().orElseThrow();
                propertyCollector.accept("jakarta.persistence.jdbc.url", connectionStringValue);

                String databaseName = clientConfig.database().orElse(null);
                if (databaseName == null || databaseName.isEmpty()) {
                    databaseName = new com.mongodb.ConnectionString(connectionStringValue).getDatabase();
                }
                if (databaseName == null || databaseName.isEmpty()) {
                    throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                            "MongoDB client '%s' must have a database name,"
                                    + " either through 'quarkus.mongodb.database'"
                                    + " or in the connection string",
                            clientName));
                }
                propertyCollector.accept(MongoServiceRegistryScopedStateInitiator.DATABASE_NAME_PROPERTY, databaseName);

                MongoClient mongoClient = ClientProxy.unwrap(lookupMongoClient(clientName));
                propertyCollector.accept(MongoServiceRegistryScopedStateInitiator.MONGO_CLIENT_PROPERTY, mongoClient);
            }

            @Override
            public List<StandardServiceInitiator<?>> contributeServiceInitiators() {
                return List.of(MongoServiceRegistryScopedStateInitiator.INSTANCE);
            }
        };
    }

    private static MongoClient lookupMongoClient(String clientName) {
        return MongoClientBeanUtil.mongoClient(clientName);
    }
}
