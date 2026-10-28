package com.futuresriskops.collector.store;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 테스트에서 쓰는 임시 PostgreSQL 16. collector 와 같은 db 모듈 마이그레이션(V1, V2)을 적용한다. */
public final class PostgresTestSupport {

    private PostgresTestSupport() {
    }

    public static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>("postgres:16");
    }

    public static JdbcTemplate migrated(PostgreSQLContainer<?> pg) {
        DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(ds);
    }
}
