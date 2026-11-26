package dev.geco.gsit.service;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Real SQLite migration + restart test, not mocked persistence. */
public final class ReceivePreferenceTest {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static void initialize(ToggleService service) throws Exception {
        var m=ToggleService.class.getDeclaredMethod("initialize");m.setAccessible(true);m.invoke(service);
    }
    static void finish(ToggleService service) throws Exception {
        service.close();var f=ToggleService.class.getDeclaredField("writer");f.setAccessible(true);
        if(!((ExecutorService)f.get(service)).awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("writer did not close");
    }
    public static int run() throws Exception {
        checks=0;Path folder=Files.createTempDirectory(Path.of("build"),"receive-db-");Files.createDirectories(folder.resolve("data"));
        Files.writeString(folder.resolve("data/data.yml"),"Database:\n  type: sqlite\n");
        GSitMain main=mock(GSitMain.class);when(main.getDataFolder()).thenReturn(folder.toFile());when(main.getLogger()).thenReturn(Logger.getLogger("receive-db"));
        ConfigService config=mock(ConfigService.class);config.PS_DEFAULT_SIT_MODE=true;when(main.getConfigService()).thenReturn(config);
        UUID oldOff=UUID.randomUUID(),newPlayer=UUID.randomUUID();
        String url="jdbc:sqlite:"+folder.resolve("data/data.db");
        Class.forName("org.sqlite.JDBC");
        try(Connection db=DriverManager.getConnection(url);Statement sql=db.createStatement()) {
            sql.executeUpdate("CREATE TABLE gsit_player_toggle (uuid TEXT)");
            sql.executeUpdate("INSERT INTO gsit_player_toggle VALUES ('"+oldOff+"')");
        }
        ToggleService a=new ToggleService(main);
        try {
            initialize(a);
            check(!a.canPlayerUsePlayerSit(oldOff)&&!a.canPlayerBeCarried(oldOff),"legacy off migrates to both settings");
            check(a.canPlayerUsePlayerSit(newPlayer)&&a.canPlayerBeCarried(newPlayer),"new player retains existing default");
            check(a.trySetPlayerCanUsePlayerSit(oldOff,true),"ride enabled independently");
            check(a.canPlayerUsePlayerSit(oldOff)&&!a.canPlayerBeCarried(oldOff),"ride does not enable pickup");
            check(a.trySetPlayerCanBeCarried(newPlayer,false),"pickup disabled independently");
            check(a.canPlayerUsePlayerSit(newPlayer)&&!a.canPlayerBeCarried(newPlayer),"pickup does not disable ride");
        } finally {finish(a);}
        ToggleService b=new ToggleService(main);
        try {
            initialize(b);
            check(b.canPlayerUsePlayerSit(oldOff)&&!b.canPlayerBeCarried(oldOff),"old player's split choices survive restart");
            check(b.canPlayerUsePlayerSit(newPlayer)&&!b.canPlayerBeCarried(newPlayer),"new player's split choices survive restart");
            check(b.trySetPlayerInteraction(oldOff,false),"old combined command still writes both");
            check(!b.canPlayerUsePlayerSit(oldOff)&&!b.canPlayerBeCarried(oldOff),"combined off stays atomic");
        } finally {finish(b);}
        return checks;
    }
}
