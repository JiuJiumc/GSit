package dev.geco.gsit.service;

import dev.geco.gsit.GSitMain;
import org.bukkit.command.CommandSender;
import dev.geco.gsit.service.message.PaperMessageService;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

/** Maintained replacement for GSit's update checker. Network work never owns player state. */
public class UpdateService implements AutoCloseable {
    private static final Pattern VERSION=Pattern.compile("[vV]?([0-9]{1,9}(?:\\.[0-9]{1,9}){0,5})(?:[-+][a-zA-Z0-9._+-]{1,48})?");
    private static final int MAX_WAITERS=256;
    private final GSitMain plugin;
    private final UpdateOptions options;
    private final UpdateHttp.Fetcher fetcher;
    private final LongSupplier clock;
    private final String source, current, website;
    private final Object state=new Object();
    // All fields below are guarded by state; callbacks only receive immutable snapshots.
    private final Set<UUID> waiters=new HashSet<>();
    private boolean closed, checking, broadcast, attempted;
    private long nextCheck;
    private Result cached;
    private record Result(String version, boolean newer) {}

    public UpdateService(GSitMain plugin) {
        this(plugin,UpdateOptions.load(plugin),new UpdateHttp()::fetch,System::nanoTime);
    }
    UpdateService(GSitMain plugin,UpdateOptions options,UpdateHttp.Fetcher fetcher,LongSupplier clock) {
        this.plugin=plugin; this.options=options; this.fetcher=fetcher; this.clock=clock;
        current=plugin.getDescription().getVersion();
        website=Objects.requireNonNullElse(plugin.getDescription().getWebsite(),"https://github.com/Gecolay/GSit");
        source=UpdateOptions.source(plugin);
    }
    private boolean alive() { synchronized(state) { return !closed && plugin.isEnabled(); } }
    private boolean enabled() { return alive() && plugin.getConfigService().CHECK_FOR_UPDATE; }
    public void checkForUpdates() { if (enabled()) request(null); }
    public void checkForUpdates(Player player) {
        if (!enabled()) return;
        // onLoad constructs this service before GSit finishes its one-time server detection.
        // Read GSit's cached flag here; capturing it in the constructor would freeze false.
        if (!(plugin.isFoliaServer() ? Bukkit.isOwnedByCurrentRegion(player) : Bukkit.isPrimaryThread())) {
            try { plugin.getTaskService().runDelayed(()->checkForUpdates(player),player,1); }
            catch (IllegalPluginAccessException | RejectedExecutionException stopped) { /* Server is stopping. */ }
            return;
        }
        if (player.isOnline() && plugin.getPermissionService().hasPermission(player,"Update")) request(player.getUniqueId());
    }
    private void request(UUID player) {
        Result ready=null; boolean start=false;
        synchronized(state) {
            if (closed) return;
            long now=clock.getAsLong();
            if (attempted && !checking && now-nextCheck<0) ready=cached;
            else {
                if (player==null) broadcast=true;
                else if (waiters.size()<MAX_WAITERS) waiters.add(player);
                if (!checking) { checking=true; start=true; }
            }
        }
        if (ready!=null) notifyResult(ready,player==null,player==null ? Set.of() : Set.of(player));
        if (!start) return;
        try { plugin.getTaskService().run(this::fetch,false); }
        catch (RuntimeException rejected) { finish(null,"检查无法排队"); }
    }
    private void fetch() {
        if (!enabled()) { finish(null,null); return; }
        Result result=null; String failure=null;
        try {
            String version=fetcher.fetch(source,current,options);
            if (version==null || !VERSION.matcher(version).matches()) throw new UpdateHttp.Failure("版本信息无效");
            result=new Result(version,compare(version,current)>0);
        } catch (SocketTimeoutException timeout) { failure="请求超时"; }
        catch (UpdateHttp.Failure expected) { failure=expected.getMessage(); }
        catch (Exception error) { failure="网络或版本信息异常"; }
        finish(result,failure);
    }
    private void finish(Result result,String failure) {
        Set<UUID> recipients; boolean all;
        synchronized(state) {
            checking=false;
            if (closed) { waiters.clear();broadcast=false; return; }
            cached=result; attempted=true;
            nextCheck=clock.getAsLong()+(result==null ? options.failureSeconds() : options.successSeconds())*1_000_000_000L;
            recipients=Set.copyOf(waiters); all=broadcast; waiters.clear(); broadcast=false;
        }
        if (!enabled()) return;
        if (failure!=null && options.logFailure() && !options.failureMessage().isBlank())
            plugin.getLogger().warning(options.failureMessage().replace("{source}",sourceName()).replace("{reason}",failure));
        if (result!=null) notifyResult(result,all,recipients);
    }
    private String sourceName() { return switch(source) {case "github"->"GitHub";case "modrinth"->"Modrinth";case "spigot"->"Spigot";case "paper"->"Hangar";default->"未知来源";}; }
    private void notifyResult(Result result,boolean all,Set<UUID> recipients) {
        if (!result.newer || !enabled()) return;
        // TaskService routes this callback to the global scheduler, never to the HTTP worker.
        try { plugin.getTaskService().run(()->{
            if (!enabled()) return;
            if (all) {
                plugin.getMessageService().sendMessage(Bukkit.getConsoleSender(),"Plugin.plugin-update",arguments(result,Bukkit.getConsoleSender()));
                for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) queuePlayer(player,result);
            } else for (UUID id : recipients) {
                Player player=Bukkit.getPlayer(id);
                if (player!=null) queuePlayer(player,result);
            }
        },true); }
        catch (IllegalPluginAccessException | RejectedExecutionException stopped) { /* Server is stopping. */ }
    }
    private void queuePlayer(Player player,Result result) {
        if (!enabled()) return;
        try { plugin.getTaskService().runDelayed(()->{
            if (enabled() && player.isOnline() && plugin.getPermissionService().hasPermission(player,"Update"))
                plugin.getMessageService().sendMessage(player,"Plugin.plugin-update",arguments(result,player));
        },player,1); }
        catch (IllegalPluginAccessException | RejectedExecutionException stopped) { /* Server is stopping. */ }
    }
    private Object[] arguments(Result result,CommandSender target) {
        Object path=target instanceof Player && plugin.getMessageService() instanceof PaperMessageService
                ? new MessageService.RawReplacement("<click:open_url:'"+website+"'>"+website+"</click>") : website;
        return new Object[]{"%Name%","GSit","%NewVersion%",result.version,"%Version%",current,"%Path%",path};
    }
    static int compare(String left,String right) throws UpdateHttp.Failure {
        var a=VERSION.matcher(left);var b=VERSION.matcher(right);
        if (!a.matches() || !b.matches()) throw new UpdateHttp.Failure("版本信息无效");
        String[] x=a.group(1).split("\\.");String[] y=b.group(1).split("\\.");
        for (int i=0;i<Math.max(x.length,y.length);i++) {
            int order=Integer.compare(i<x.length ? Integer.parseInt(x[i]) : 0,i<y.length ? Integer.parseInt(y[i]) : 0);
            if (order!=0) return order;
        }
        return 0;
    }
    @Override public void close() {
        // Never wait on a socket from a region/shutdown thread. The bounded IO worker closes it in finally.
        synchronized(state) { closed=true;waiters.clear();broadcast=false;cached=null; }
    }
}
