package dev.geco.gsit.service;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import org.mockito.MockedConstruction;

public final class ToggleStrictTest {
    static int checks;
    static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static GSitMain main() {
        GSitMain main = mock(GSitMain.class);
        when(main.getLogger()).thenReturn(Logger.getLogger("strict-preferences"));
        ConfigService config = mock(ConfigService.class); config.PS_DEFAULT_SIT_MODE = true;
        when(main.getConfigService()).thenReturn(config);
        return main;
    }
    static void put(ToggleService service, String field, Object value) throws Exception {
        Field slot = ToggleService.class.getDeclaredField(field); slot.setAccessible(true); slot.set(service, value);
    }
    static void stopWorker(ToggleService service) throws Exception {
        Field slot = ToggleService.class.getDeclaredField("writer"); slot.setAccessible(true);
        ((ExecutorService) slot.get(service)).shutdownNow();
    }
    static void queries(boolean nullRow) throws Exception {
        ToggleService service = new ToggleService(main());
        UUID id = UUID.randomUUID(); List<Statement> statements = new ArrayList<>();
        try (MockedConstruction<DataService> construction = mockConstruction(DataService.class, (database, context) -> {
            when(database.connect()).thenReturn(true);
            when(database.executeAndGet(anyString(), any(Object[].class))).thenAnswer(invocation -> {
                ResultSet rows = mock(ResultSet.class); Statement statement = mock(Statement.class);
                statements.add(statement); when(rows.getStatement()).thenReturn(statement);
                if (nullRow) {
                    when(rows.next()).thenReturn(true, true, false);
                    when(rows.getString(1)).thenReturn(null, id.toString());
                } else when(rows.next()).thenReturn(false);
                return rows;
            });
        })) {
            Method initialize = ToggleService.class.getDeclaredMethod("initialize"); initialize.setAccessible(true);
            try { initialize.invoke(service); }
            catch (InvocationTargetException failure) { throw new AssertionError("NULL UUID must not abort preference loading", failure.getCause()); }
            check(service.isReady(), "valid rows load despite invalid data");
            if (nullRow) check(!service.canPlayerUsePlayerSit(id), "valid opt-out row still loaded");
            for (Statement statement : statements) verify(statement).close();
            check(statements.size() == 4, "all four preference queries close statements");
        } finally { stopWorker(service); }
    }
    static void closeOutsideLock() throws Exception {
        ToggleService service = new ToggleService(main()); DataService database = mock(DataService.class);
        CountDownLatch closing = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            put(service, "database", database); put(service, "initialized", true); put(service, "ready", true);
            doAnswer(invocation -> { closing.countDown(); release.await(3, TimeUnit.SECONDS); return null; }).when(database).close();
            service.close(); check(closing.await(2, TimeUnit.SECONDS), "background connection close started");
            Future<Boolean> request = reader.submit(() -> service.trySetPlayerCanUsePlayerSit(UUID.randomUUID(), false));
            boolean blocked = false;
            try { check(!request.get(500, TimeUnit.MILLISECONDS), "closing rejects new changes"); }
            catch (TimeoutException failure) { blocked = true; }
            check(!blocked, "region-facing setter never waits on JDBC close");
        } finally { release.countDown(); reader.shutdownNow(); stopWorker(service); }
    }
    static void mysqlConnection() throws Exception {
        GSitMain main=main();
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.set("Database.type","mysql");when(main.getConfig()).thenReturn(config);
        java.nio.file.Path folder=java.nio.file.Path.of("build/strict-mysql");
        java.nio.file.Files.createDirectories(folder.resolve("data"));config.save(folder.resolve("data/data.yml").toFile());
        when(main.getDataFolder()).thenReturn(folder.toFile());
        Connection serverConnection=mock(Connection.class), schemaConnection=mock(Connection.class);
        try(var driver=mockStatic(DriverManager.class)) {
            driver.when(()->DriverManager.getConnection(anyString(),anyString(),anyString())).thenReturn(serverConnection,schemaConnection);
            DataService database=new DataService(main);
            check(database.connect(),"mock MySQL connection opens");
            verify(serverConnection).close();
            database.close();verify(schemaConnection).close();
            check(true,"both temporary and final MySQL connections close");
        }
    }
    public static int run() throws Exception {
        checks = 0; List<Throwable> failures = new ArrayList<>();
        try { queries(false); } catch (Throwable failure) { failures.add(failure); }
        try { queries(true); } catch (Throwable failure) { failures.add(failure); }
        try { closeOutsideLock(); } catch (Throwable failure) { failures.add(failure); }
        try { mysqlConnection(); } catch (Throwable failure) { failures.add(failure); }
        for (Throwable failure : failures) System.out.println("STRICT DB FAIL " + failure);
        if (!failures.isEmpty()) throw new AssertionError("Strict database regressions: " + failures.size());
        return checks;
    }
}
