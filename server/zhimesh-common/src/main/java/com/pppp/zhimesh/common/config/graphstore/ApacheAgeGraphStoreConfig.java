package com.pppp.zhimesh.common.config.graphstore;

import com.pppp.zhimesh.common.config.ZhiMeshProperties;
import com.pppp.zhimesh.common.rag.ApacheAgeGraphStore;
import com.pppp.zhimesh.common.rag.GraphStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.age.jdbc.base.Agtype;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.PgConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

@Slf4j
@Configuration
@ConditionalOnProperty(value = "zhimesh.graph-database", havingValue = "apache-age")
public class ApacheAgeGraphStoreConfig {

    private final ZhiMeshProperties zhiMeshProperties;

    @Value("${spring.datasource.url}")
    private String dataBaseUrl;

    @Value("${spring.datasource.username}")
    private String dataBaseUserName;

    @Value("${spring.datasource.password}")
    private String dataBasePassword;

    public ApacheAgeGraphStoreConfig(ZhiMeshProperties zhiMeshProperties) {
        this.zhiMeshProperties = zhiMeshProperties;
    }

    @Bean(name = "kbGraphStore", destroyMethod = "close")
    @Primary
    public GraphStore initGraphStore() {
        String regex = "jdbc:postgresql://([^:/]+):(\\d+)/(\\w+).*";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(dataBaseUrl);

        String host = "";
        String port = "";
        String databaseName = "";
        if (matcher.matches()) {
            host = matcher.group(1);
            port = matcher.group(2);
            databaseName = matcher.group(3);

            log.info("Host: " + host);
            log.info("Port: " + port);
            log.info("Database: " + databaseName);
        } else {
            throw new RuntimeException("parse url error");
        }
        DataSource dataSource = null;
        if (zhiMeshProperties.getGraphStore().isPoolEnabled()) {
            dataSource = buildAgePool(host, Integer.parseInt(port), databaseName);
        } else {
            log.info("AGE graph store pool disabled; using per-statement driver connections");
        }
        return ApacheAgeGraphStore.builder()
                .host(host)
                .port(Integer.parseInt(port))
                .database(databaseName)
                .user(dataBaseUserName)
                .password(dataBasePassword)
                .createGraph(true)
                .dropGraphFirst(false)
                .graphName("adi_knowledge_base_graph")
                .dataSource(dataSource)
                .build();
    }

    /**
     * Builds the dedicated Apache AGE pool. It must stay separate from the
     * primary Spring/MyBatis Hikari pool: the AGE session sets
     * {@code search_path = ag_catalog, ...} and registers the agtype data
     * type on its connections, neither of which may leak into ordinary
     * relational sessions (or vice versa).
     */
    private HikariDataSource buildAgePool(String host, int port, String databaseName) {
        ZhiMeshProperties.GraphStore settings = zhiMeshProperties.getGraphStore();
        AgeGraphDataSource pgDataSource = new AgeGraphDataSource();
        pgDataSource.setURL(String.format("jdbc:postgresql://%s:%s/%s", host, port, databaseName));
        pgDataSource.setUser(dataBaseUserName);
        pgDataSource.setPassword(dataBasePassword);

        HikariConfig config = new HikariConfig();
        config.setPoolName("age-graph-pool");
        config.setDataSource(pgDataSource);
        config.setMaximumPoolSize(settings.getPoolMaxSize());
        config.setMinimumIdle(settings.getPoolMinIdle());
        config.setConnectionTimeout(settings.getPoolConnectionTimeoutMs());
        config.setMaxLifetime(settings.getPoolMaxLifetimeMs());
        log.info("AGE graph store pool enabled: maxPoolSize={}, minIdle={}",
                settings.getPoolMaxSize(), settings.getPoolMinIdle());
        return new HikariDataSource(config);
    }

    /**
     * pgjdbc data source whose physical connections are initialized exactly
     * once with the AGE session setup (agtype type registration, LOAD 'age',
     * search_path) that the legacy per-statement path used to repeat before
     * every Cypher statement. Session-level state is safe to keep for pooled
     * reuse: the store only runs autocommit single statements and never
     * issues SET LOCAL.
     */
    static class AgeGraphDataSource extends PGSimpleDataSource {

        @Override
        public Connection getConnection() throws SQLException {
            return prepare(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return prepare(super.getConnection(username, password));
        }

        // Package-private so the session-setup contract can be asserted in unit tests.
        static Connection prepare(Connection rawConnection) throws SQLException {
            PgConnection connection = rawConnection.unwrap(PgConnection.class);
            try (Statement stmt = connection.createStatement()) {
                connection.addDataType("agtype", Agtype.class);
                stmt.execute("LOAD 'age'");
                stmt.execute("SET search_path = ag_catalog, \"$user\", public;");
            }
            return connection;
        }
    }
}
