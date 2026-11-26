/* GSit 3.7.0 maintenance replacement: original table names and public API retained.
 * UUID-only preference sets; JDBC work runs on one bounded worker, never a region tick. */
package dev.geco.gsit.service;

import cn.jiujiu.gsit.ActionMenu;
import dev.geco.gsit.GSitMain;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class ToggleService {
    private static final String[] TABLES = {"gsit_sit_toggle", "gsit_player_toggle", "gsit_crawl_toggle", "gsit_carry_toggle"};
    private final GSitMain main;
    private final List<Set<UUID>> exceptions = List.of(ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
    private record Change(int kind, UUID id) {}
    private final Map<Change, Boolean> pending = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final ThreadPoolExecutor writer;
    private DataService database;
    private boolean scheduled, closing, initialized;
    private volatile boolean ready;
    public ToggleService(GSitMain main) {
        this.main = main;
        writer = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r -> {
            Thread thread = new Thread(r,"GSit-preferences"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public void createDataTables() {
        synchronized(lock) { if (!closing) trigger(); }
    }
    private void initialize() throws SQLException {
        database = new DataService(main);
        if (!database.connect()) throw new SQLException("Could not connect GSit preference storage");
        // Copy old opt-outs exactly once before accepting any changes; later switches are independent.
        database.execute("CREATE TABLE IF NOT EXISTS gsit_player_toggle (uuid TEXT);");
        database.execute("CREATE TABLE IF NOT EXISTS gsit_carry_toggle (uuid TEXT);");
        database.execute("CREATE TABLE IF NOT EXISTS gsit_jiujiu_migrations (uuid TEXT);");
        if (!database.exists("SELECT uuid FROM gsit_jiujiu_migrations WHERE uuid = ?", "split-receive-v1")) {
            database.execute("INSERT INTO gsit_carry_toggle (uuid) SELECT uuid FROM gsit_player_toggle WHERE uuid NOT IN (SELECT uuid FROM gsit_carry_toggle WHERE uuid IS NOT NULL)");
            database.execute("INSERT INTO gsit_jiujiu_migrations (uuid) VALUES (?)", "split-receive-v1");
        }
        for(int i=0;i<TABLES.length;i++) {
            String table=TABLES[i];
            database.execute("CREATE TABLE IF NOT EXISTS "+table+" (uuid TEXT);");
            try(ResultSet rows=database.executeAndGet("SELECT uuid FROM "+table); Statement statement=rows.getStatement()) {
                while(rows.next()) {
                    try { exceptions.get(i).add(UUID.fromString(rows.getString(1))); }
                    catch(IllegalArgumentException | NullPointerException ignored) { main.getLogger().warning("跳过无效互动UUID："+table); }
                }
            }
        }
        initialized=true;
        synchronized(lock) { ready=!closing; }
    }
    public boolean isReady() { return ready; }
    private void trigger() {
        if(scheduled)return;
        scheduled=true;
        writer.execute(this::drain);
    }
    private void drain() {
        try {
            if(!initialized)initialize();
            while(true) {
                Map<Change,Boolean> batch;
                boolean closeConnection;
                synchronized(lock) {
                    closeConnection=pending.isEmpty()&&closing;
                    if(pending.isEmpty()) { scheduled=false;if(closing)ready=false;batch=null; }
                    else { batch=Map.copyOf(pending);pending.clear(); }
                }
                // Never hold the region-facing lock during JDBC work, including connection close.
                if(batch==null) { if(closeConnection)database.close();return; }
                for(var change:batch.entrySet()) {
                    Change key=change.getKey();
                    if(!change.getValue()) database.execute("DELETE FROM "+TABLES[key.kind]+" WHERE uuid = ?",key.id.toString());
                    else {
                        boolean exists=database.exists("SELECT uuid FROM "+TABLES[key.kind]+" WHERE uuid = ?",key.id.toString());
                        if(!exists) database.execute("INSERT INTO "+TABLES[key.kind]+" (uuid) VALUES (?)",key.id.toString());
                    }
                }
            }
        } catch(Throwable failure) {
            ready=false;
            main.getLogger().log(Level.SEVERE,"互动偏好存储失败，已暂停互动；请检查数据库后完整重启。",failure);
            synchronized(lock){pending.clear();scheduled=false;}
            if(database!=null)database.close();
        }
    }
    public void close() {
        synchronized(lock) {
            if(closing)return;
            closing=true;ready=false;
            // The bounded worker finishes immutable UUID changes using its own connection.
            trigger(); writer.shutdown();
        }
    }
    public void clearEntitySitToggleCache(UUID id) { /* Entries here are persistent preferences, not online-player references. */ }
    private boolean defaultValue(int kind) {
        ConfigService c=main.getConfigService();
        return switch(kind){case 0 -> c.S_DEFAULT_SIT_MODE;case 1, 3 -> c.PS_DEFAULT_SIT_MODE;default -> c.C_DEFAULT_CRAWL_MODE;};
    }
    private boolean get(int kind,UUID id) { return ready && (defaultValue(kind) != exceptions.get(kind).contains(id)); }
    public boolean canEntityUseSit(UUID id) { Boolean auto=ActionMenu.autoOverride(id);return auto!=null?auto:get(0,id); }
    public boolean canPlayerUsePlayerSit(UUID id) { return get(1,id); }
    public boolean canPlayerBeCarried(UUID id) { return get(3,id); }
    public boolean trySetPlayerCanBeCarried(UUID id,boolean value) { return set(3,id,value); }
    /** Legacy combined controls remain atomic and update both incoming preferences. */
    public boolean trySetPlayerInteraction(UUID id,boolean value) {
        synchronized(lock) {
            int needed = (pending.containsKey(new Change(1,id)) ? 0 : 1)
                    + (pending.containsKey(new Change(3,id)) ? 0 : 1);
            if(!ready || closing || pending.size()+needed>512) return false;
            return set(1,id,value) && set(3,id,value);
        }
    }
    public boolean canPlayerUseCrawl(UUID id) { Boolean auto=ActionMenu.autoOverride(id);return auto!=null?auto:get(2,id); }
    private boolean set(int kind,UUID id,boolean value) {
        boolean exception=value!=defaultValue(kind);Change key=new Change(kind,id);
        synchronized(lock) {
            if(!ready||closing||pending.size()>=512&&!pending.containsKey(key)) {
                main.getLogger().warning("偏好存储尚未就绪或队列已满，本次设置未生效。");return false;
            }
            pending.put(key,exception);
            if(exception)exceptions.get(kind).add(id);else exceptions.get(kind).remove(id);
            trigger();
            return true;
        }
    }
    public void setEntityCanUseSit(UUID id,boolean value) { set(0,id,value); }
    public void setPlayerCanUsePlayerSit(UUID id,boolean value) { set(1,id,value); }
    public boolean trySetPlayerCanUsePlayerSit(UUID id,boolean value) { return set(1,id,value); }
    public void setPlayerCanUseCrawl(UUID id,boolean value) { set(2,id,value); }
}
