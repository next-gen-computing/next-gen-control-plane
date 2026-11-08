package com.nextgen.controlplane.spring;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway configuration.
 *
 * <p>Spring Boot's {@link org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration}
 * is excluded from {@link ControlPlaneSpringApp} to avoid conflicts with H2 AUTO_INCREMENT syntax
 * when running in Postgres mode — we configure Flyway explicitly here with the right settings
 * for each environment.
 *
 * <p>In dev/CI (H2 mode), Flyway runs with {@code placeholderReplacement=false} and no
 * Postgres-specific flags, letting the H2 MySQL-compat mode handle the SQL as-is.
 */
@Configuration
@EnableConfigurationProperties(FlywayProperties.class)
public class FlywayConfig {
    private static final Logger LOG = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        LOG.info("Running Flyway migrations...");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .placeholderReplacement(false)
                .load();

        var result = flyway.migrate();
        LOG.info("Flyway: {} migrations applied, {} pending",
                result.migrationsExecuted, 0);
        return flyway;
    }
}
