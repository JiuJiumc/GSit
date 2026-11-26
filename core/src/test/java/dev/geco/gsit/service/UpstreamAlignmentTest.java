package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import java.sql.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import static org.mockito.Mockito.*;

/** Regression coverage for the upstream 3.8 changes retained with the custom services. */
public final class UpstreamAlignmentTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void connectionProbe() throws Exception {
        GSitMain plugin=mock(GSitMain.class);
        DataService service=new DataService(plugin);
        Connection connection=mock(Connection.class);
        PreparedStatement statement=mock(PreparedStatement.class);
        ResultSet rows=mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);
        var field=DataService.class.getDeclaredField("connection");field.setAccessible(true);field.set(service,connection);
        var last=DataService.class.getDeclaredField("lastValidCheck");last.setAccessible(true);last.setLong(service,System.nanoTime());
        check(service.exists("SELECT uuid FROM gsit_player_toggle WHERE uuid = ?","player"),"new scoped existence query returns result");
        verify(statement).setObject(1,"player");verify(statement).close();verify(rows).close();
        verify(connection,never()).isValid(anyInt());check(true,"recent connection validation is reused and JDBC resources close");
    }
    static void schedulerProbe() {
        GSitMain plugin=mock(GSitMain.class);
        when(plugin.isEnabled()).thenReturn(true);when(plugin.isFoliaServer()).thenReturn(true);
        when(plugin.supportsTaskFeature()).thenReturn(true);
        Entity entity=mock(Entity.class);EntityScheduler scheduler=mock(EntityScheduler.class);
        when(entity.getScheduler()).thenReturn(scheduler);
        TaskService service=spy(new TaskService(plugin));
        try(var bukkit=mockStatic(Bukkit.class)){
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(true);
            service.removeEntity(entity);verify(entity).remove();check(true,"owned entity is removed directly");
            clearInvocations(entity);
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(entity)).thenReturn(false);
            doNothing().when(service).run(any(Runnable.class),eq(entity));
            service.removeEntity(entity);verify(entity,never()).remove();
            verify(service).run(any(Runnable.class),eq(entity));check(true,"foreign entity removal is scheduled on its owner");
            service.runDelayed(()->{},entity,0);
            verify(scheduler).runDelayed(eq(plugin),any(),isNull(),eq(1L));
            check(true,"zero delay is clamped to a legal entity tick");
            service.cancel(null);check(true,"nullable task handle can be cancelled safely");
        }
    }
    public static int run() throws Exception {
        checks=0;connectionProbe();schedulerProbe();return checks;
    }
}
