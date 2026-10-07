package edu.swu.fcj.my12306.biz.ticketservice.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.ticketservice.dao.entity.SeatDO;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.SeatMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.seat.SeatAllocator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in via seat-allocation-810.py; no business tables are written here. */
@EnabledIfEnvironmentVariable(named = "MY12306_SEAT_BENCH", matches = "true")
class SeatAllocatorBenchmark {
    private static final String DB = "12306_seat_allocator_bench_20261007";
    private static final String FILTER = "train_id=1 AND seat_type=2 AND seat_status=0 "
            + "AND start_station='北京南' AND end_station='宁波' AND del_flag=0";
    private static final String FULL = "id,train_id,carriage_number,seat_number,seat_type,start_station,"
            + "end_station,price,seat_status,create_time,update_time,del_flag";
    private static final String INDEX_SQL = "(train_id,seat_type,seat_status,start_station,end_station,"
            + "del_flag,carriage_number,seat_number)";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path out = Path.of(System.getenv("MY12306_SEAT_BENCH_RESULTS"));
    private final Map<String, Object> result = new LinkedHashMap<>();

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    public static class TestIndexHint implements Interceptor {
        String index;
        int queries;
        @Override public Object intercept(Invocation call) throws Throwable {
            BoundSql bound = ((StatementHandler) call.getTarget()).getBoundSql();
            if (bound.getSql().contains("FROM t_seat")) {
                queries++;
                if (index != null) {
                    if (!Set.of("idx_train_id", "idx_seat_query", "idx_seat_allocate").contains(index))
                        throw new IllegalArgumentException("Unexpected benchmark index");
                    SystemMetaObject.forObject(bound).setValue("sql", bound.getSql()
                            .replace("FROM t_seat", "FROM t_seat FORCE INDEX (" + index + ")"));
                }
            }
            return call.proceed();
        }
    }

    private void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }

    private String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next()); return rs.getString(1);
        }
    }

    private String fingerprint(List<List<String>> rows) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(rows)));
    }

    private List<List<String>> tableRows(Connection c) throws SQLException {
        var rows = new ArrayList<List<String>>();
        String columns = "id,train_id,carriage_number,seat_number,seat_type,start_station,end_station,price,"
                + "seat_status,DATE_FORMAT(create_time,'%Y-%m-%d %H:%i:%s'),"
                + "DATE_FORMAT(update_time,'%Y-%m-%d %H:%i:%s'),del_flag";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT " + columns + " FROM t_seat ORDER BY id")) {
            while (rs.next()) {
                var row = new ArrayList<String>();
                for (int i = 1; i <= 12; i++) row.add(rs.getString(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private void restore(Connection c) throws Exception {
        JsonNode input = JSON.readTree(out.resolve("source-seats.json").toFile());
        var expected = new ArrayList<List<String>>();
        for (JsonNode r : input) {
            var row = new ArrayList<String>();
            for (JsonNode v : r) row.add(v.isNull() ? null : v.asText());
            expected.add(row);
        }
        expected.sort(Comparator.comparingLong(r -> Long.parseLong(r.getFirst())));
        String sourceFingerprint = fingerprint(expected);
        boolean exists = "1".equals(scalar(c, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + DB + "'"));
        if (!exists) {
            exec(c, "CREATE DATABASE " + DB + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            exec(c, "USE " + DB);
            exec(c, "CREATE TABLE t_seat (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "train_id BIGINT,carriage_number VARCHAR(64),seat_number VARCHAR(64),seat_type INT,"
                    + "start_station VARCHAR(256),end_station VARCHAR(256),price INT,seat_status INT,"
                    + "create_time DATETIME,update_time DATETIME,del_flag TINYINT(1),INDEX idx_train_id(train_id)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO t_seat (" + FULL + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int count = 0;
                for (JsonNode row : input) {
                    for (int i = 0; i < 12; i++) {
                        JsonNode v = row.get(i);
                        if (v.isNull()) ps.setNull(i + 1, Types.NULL);
                        else if (v.isNumber()) ps.setLong(i + 1, v.asLong());
                        else ps.setString(i + 1, v.asText());
                    }
                    ps.addBatch();
                    if (++count % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch(); c.commit();
            } catch (Exception ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        } else {
            exec(c, "USE " + DB);
        }
        assertEquals(sourceFingerprint, fingerprint(tableRows(c)), "Existing benchmark data differs; not overwriting");
        assertEquals("27480", scalar(c, "SELECT COUNT(*) FROM t_seat"));
        assertEquals("9600", scalar(c, "SELECT COUNT(*) FROM t_seat WHERE train_id=1"));
        assertEquals("810", scalar(c, "SELECT COUNT(*) FROM t_seat WHERE " + FILTER));
        assertEquals("810", scalar(c, "SELECT COUNT(DISTINCT carriage_number,seat_number) FROM t_seat WHERE " + FILTER));
        result.put("sourceSha256", System.getenv("MY12306_SEAT_SOURCE_SHA256"));
        result.put("dataFingerprint", sourceFingerprint);
        result.put("mysqlVersion", scalar(c, "SELECT VERSION()"));
        result.put("database", DB);
        result.put("rows", 27480);
        result.put("train1Rows", 9600);
        result.put("available", 810);
    }

    private boolean indexExists(Connection c, String name) throws SQLException {
        return !"0".equals(scalar(c, "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='"
                + DB + "' AND table_name='t_seat' AND index_name='" + name + "'"));
    }

    private static String index(String group) {
        return switch (group) { case "A" -> "idx_train_id"; case "B", "C" -> "idx_seat_query"; default -> "idx_seat_allocate"; };
    }

    private String query(String group, int n, boolean hinted) {
        return "SELECT " + (Set.of("A", "B").contains(group) ? FULL : "id,carriage_number,seat_number")
                + " FROM t_seat" + (hinted ? " FORCE INDEX (" + index(group) + ")" : "")
                + " WHERE " + FILTER + " ORDER BY carriage_number,seat_number"
                + (Set.of("C", "D").contains(group) ? " LIMIT " + n : "");
    }

    private String explain(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("EXPLAIN ANALYZE " + sql)) {
            assertTrue(rs.next()); return rs.getString(1);
        }
    }

    private double elapsed(String tree) {
        var matcher = java.util.regex.Pattern.compile("actual time=[0-9.]+\\.\\.([0-9.]+)").matcher(tree);
        assertTrue(matcher.find(), tree); return Double.parseDouble(matcher.group(1));
    }

    private Map<String, Object> stats(List<Double> samples) {
        var sorted = samples.stream().sorted().toList();
        int n = sorted.size();
        return Map.of("count", n, "median", (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2,
                "min", sorted.getFirst(), "max", sorted.getLast(),
                "p95", sorted.get((int) Math.ceil(n * .95) - 1), "p99", sorted.get((int) Math.ceil(n * .99) - 1));
    }

    private Map<String, List<Double>> samples() {
        var values = new LinkedHashMap<String, List<Double>>();
        for (String group : List.of("A", "B", "C", "D")) values.put(group, new ArrayList<>());
        return values;
    }

    private List<String> order(int round) {
        var groups = new ArrayList<>(List.of("A", "B", "C", "D"));
        Collections.rotate(groups, -(round % 4)); return groups;
    }

    private Map<String, Object> readBenchmark(Connection c, SeatMapper mapper, SeatAllocator allocator,
                                              TestIndexHint hint, int n) throws Exception {
        var historical = samples();
        var robust = samples();
        var calls = samples();
        var trees = new LinkedHashMap<String, List<String>>();
        for (String group : historical.keySet()) {
            explain(c, query(group, n, true));
            var groupTrees = new ArrayList<String>();
            for (int i = 0; i < 5; i++) {
                String tree = explain(c, query(group, n, true));
                historical.get(group).add(elapsed(tree)); groupTrees.add(tree);
            }
            trees.put(group, groupTrees);
        }
        for (String group : robust.keySet()) for (int i = 0; i < 30; i++) explain(c, query(group, n, true));
        for (int r = 0; r < 20; r++) for (String group : order(r)) robust.get(group).add(elapsed(explain(c, query(group, n, true))));
        for (String group : calls.keySet()) {
            hint.index = index(group);
            for (int i = 0; i < 100; i++) allocate(group, mapper, allocator, n);
        }
        for (int r = 0; r < 30; r++) {
            for (String group : order(r)) {
                hint.index = index(group);
                for (int i = 0; i < 20; i++) {
                    int before = hint.queries;
                    long start = System.nanoTime();
                    List<SeatDO> seats = allocate(group, mapper, allocator, n);
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    assertEquals(n, seats.size()); assertEquals(1, hint.queries - before);
                    calls.get(group).add(ms);
                }
            }
        }
        var groups = new LinkedHashMap<String, Object>();
        for (String group : calls.keySet()) {
            groups.put(group, Map.of("sql", query(group, n, true), "historicalExplain", stats(historical.get(group)),
                    "robustExplain", stats(robust.get(group)), "allocation", stats(calls.get(group)),
                    "trees", trees.get(group), "historicalRawMs", historical.get(group),
                    "robustRawMs", robust.get(group), "allocationRawMs", calls.get(group)));
            System.out.println("810-seat n=" + n + " " + group + " explain=" + stats(robust.get(group))
                    + " allocation=" + stats(calls.get(group)));
        }
        String natural = explain(c, query("D", n, false));
        assertTrue(natural.contains("idx_seat_allocate"), natural);
        assertFalse(natural.contains("-> Sort:"), natural);
        return Map.of("groups", groups, "naturalPlan", natural);
    }

    private List<SeatDO> legacy(SeatMapper mapper, int n) {
        List<SeatDO> all = mapper.selectList(Wrappers.lambdaQuery(SeatDO.class)
                .eq(SeatDO::getTrainId, 1L).eq(SeatDO::getSeatType, 2).eq(SeatDO::getSeatStatus, 0)
                .eq(SeatDO::getDelFlag, 0)
                .eq(SeatDO::getStartStation, "北京南").eq(SeatDO::getEndStation, "宁波")
                .orderByAsc(SeatDO::getCarriageNumber).orderByAsc(SeatDO::getSeatNumber));
        if (all.size() < n) return List.of();
        Map<String, List<SeatDO>> groups = new LinkedHashMap<>();
        for (SeatDO seat : all) groups.computeIfAbsent(seat.getCarriageNumber(), key -> new ArrayList<>()).add(seat);
        for (var seats : groups.values()) if (seats.size() >= n) return new ArrayList<>(seats.subList(0, n));
        return new ArrayList<>(all.subList(0, n));
    }

    private List<SeatDO> allocate(String group, SeatMapper mapper, SeatAllocator allocator, int n) {
        return Set.of("A", "B").contains(group) ? legacy(mapper, n) : allocator.allocate(1L, "北京南", "宁波", 2, n);
    }

    private Map<String, Object> writeBenchmark(Connection c) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        for (int n : List.of(1, 5)) {
            String ids;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id FROM t_seat WHERE " + FILTER
                    + " ORDER BY carriage_number,seat_number LIMIT " + n)) {
                var values = new ArrayList<String>(); while (rs.next()) values.add(rs.getString(1)); ids = String.join(",", values);
            }
            var values = new ArrayList<Double>();
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < 350; i++) {
                    long start = System.nanoTime();
                    try (Statement s = c.createStatement()) {
                        assertEquals(n, s.executeUpdate("UPDATE t_seat SET seat_status=1 WHERE id IN (" + ids + ") AND seat_status=0"));
                    }
                    c.commit();
                    double ms = (System.nanoTime() - start) / 1_000_000.0;
                    if (i >= 50) values.add(ms);
                    exec(c, "UPDATE t_seat SET seat_status=0 WHERE id IN (" + ids + ") AND seat_status=1"); c.commit();
                }
            } finally { c.rollback(); c.setAutoCommit(true); }
            result.put(String.valueOf(n), Map.of("stats", stats(values), "rawMs", values,
                    "roundMedians", List.of(stats(values.subList(0, 100)), stats(values.subList(100, 200)), stats(values.subList(200, 300)))));
        }
        return result;
    }

    private void fragmentChecks(Connection c, SeatMapper mapper, SeatAllocator allocator, TestIndexHint hint) throws Exception {
        int[][] fixtures = {{5, 5}, {2, 6}, {2, 2, 2, 2, 6}, {2, 2, 2}, {2, 2}, {0}};
        for (int[] counts : fixtures) {
            c.setAutoCommit(false);
            try {
                exec(c, "UPDATE t_seat SET seat_status=2 WHERE " + FILTER);
                for (int i = 0; i < counts.length; i++) if (counts[i] > 0) {
                    exec(c, "UPDATE t_seat SET seat_status=0 WHERE train_id=1 AND seat_type=2 AND start_station='北京南' "
                            + "AND end_station='宁波' AND carriage_number='" + String.format("%02d", i + 7)
                            + "' ORDER BY seat_number LIMIT " + counts[i]);
                }
                hint.index = "idx_seat_query";
                var expected = legacy(mapper, 5).stream().map(SeatDO::getId).toList();
                hint.index = "idx_seat_allocate";
                assertEquals(expected, allocator.allocate(1L, "北京南", "宁波", 2, 5).stream().map(SeatDO::getId).toList());
                // Custom @Select must keep the logical-deletion predicate explicitly.
                exec(c, "UPDATE t_seat SET del_flag=1 WHERE train_id=1 AND seat_type=2 AND start_station='北京南' "
                        + "AND end_station='宁波' AND carriage_number='07' AND seat_number='01A'");
                assertEquals(legacy(mapper, 1).stream().map(SeatDO::getId).toList(),
                        allocator.allocate(1L, "北京南", "宁波", 2, 1).stream().map(SeatDO::getId).toList());
            } finally { c.rollback(); c.setAutoCommit(true); }
        }
        hint.index = null;
    }

    @Test void runExperiment() throws Exception {
        String password = Objects.requireNonNull(System.getenv("MY12306_SEAT_DB_PASSWORD"));
        try (Connection c = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true", "root", password)) {
            restore(c);
            String before = fingerprint(tableRows(c));
            if ("true".equals(System.getenv("MY12306_SEAT_WRITE_ONLY"))) {
                JsonNode previous = JSON.readTree(out.resolve("benchmark.json").toFile());
                var paired = new ArrayList<Map<String, Object>>();
                // ABBA maintenance check: DDL and warmup remain outside each timed update.
                for (String mode : List.of("with", "without", "without", "with")) {
                    boolean present = indexExists(c, "idx_seat_allocate");
                    if (mode.equals("with") && !present) exec(c, "ALTER TABLE t_seat ADD INDEX idx_seat_allocate" + INDEX_SQL);
                    if (mode.equals("without") && present) exec(c, "ALTER TABLE t_seat DROP INDEX idx_seat_allocate");
                    paired.add(Map.of("mode", mode, "measurements", writeBenchmark(c)));
                }
                assertEquals(before, fingerprint(tableRows(c)));
                ((com.fasterxml.jackson.databind.node.ObjectNode) previous).set("pairedWrite", JSON.valueToTree(paired));
                JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("benchmark.json").toFile(), previous);
                return;
            }
            if (!indexExists(c, "idx_seat_query")) exec(c, "ALTER TABLE t_seat ADD INDEX idx_seat_query(train_id,seat_type,seat_status,start_station,end_station)");
            if (indexExists(c, "idx_seat_allocate")) exec(c, "ALTER TABLE t_seat DROP INDEX idx_seat_allocate");
            result.put("writeBefore", writeBenchmark(c));
            exec(c, "ALTER TABLE t_seat ADD INDEX idx_seat_allocate" + INDEX_SQL);
            exec(c, "ANALYZE TABLE t_seat");
            result.put("tableDdl", scalar(c, "SELECT CONCAT('row_format=',ROW_FORMAT,', rows=',TABLE_ROWS) FROM information_schema.tables WHERE table_schema='" + DB + "' AND table_name='t_seat'"));
            result.put("innodbFlushLogAtTrxCommit", scalar(c, "SELECT @@innodb_flush_log_at_trx_commit"));
            result.put("connectionId", scalar(c, "SELECT CONNECTION_ID()"));
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            configuration.setLogImpl(NoLoggingImpl.class);
            var hint = new TestIndexHint();
            var bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(new SingleConnectionDataSource(c, true));
            bean.setConfiguration(configuration);
            bean.setPlugins(hint);
            var factory = Objects.requireNonNull(bean.getObject());
            factory.getConfiguration().addMapper(SeatMapper.class);
            try (var session = factory.openSession(true)) {
                SeatMapper mapper = session.getMapper(SeatMapper.class);
                var allocator = new SeatAllocator(mapper);
                var measurements = new LinkedHashMap<String, Object>();
                for (int n : List.of(1, 2, 5)) measurements.put(String.valueOf(n), readBenchmark(c, mapper, allocator, hint, n));
                result.put("read", measurements);
                fragmentChecks(c, mapper, allocator, hint);
            }
            result.put("writeAfter", writeBenchmark(c));
            assertEquals(before, fingerprint(tableRows(c)), "Benchmark fixture not restored");
            assertEquals("810", scalar(c, "SELECT COUNT(*) FROM t_seat WHERE " + FILTER));
            result.put("indexPages", scalar(c, "SELECT stat_value FROM mysql.innodb_index_stats WHERE database_name='" + DB + "' AND table_name='t_seat' AND index_name='idx_seat_allocate' AND stat_name='size'"));
            result.put("pageSize", scalar(c, "SELECT @@innodb_page_size"));
            result.put("passed", true);
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("benchmark.json").toFile(), result);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW CREATE TABLE t_seat")) {
                assertTrue(rs.next());
                Files.writeString(out.resolve("schema.sql"), rs.getString(2), StandardCharsets.UTF_8);
            }
        }
    }
}
