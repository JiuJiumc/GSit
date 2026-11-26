package dev.geco.gsit.service;

import static org.mockito.Mockito.*;
import dev.geco.gsit.GSitMain;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;
import java.util.logging.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.mockito.MockedStatic;

/** Deterministic HTTP failures and scheduler-boundary tests; never contacts external services. */
public final class UpdateRegressionTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static UpdateOptions defaults(){return UpdateOptions.parse(new YamlConfiguration(),s->{});}
    interface IO {void run()throws Exception;}
    static void fails(IO action,String text)throws Exception {
        Exception seen=null;try{action.run();}catch(Exception e){seen=e;}
        check(seen!=null&&seen.getMessage().contains(text),"expected short failure "+text);
    }
    static void http()throws Exception {
        for(int status:List.of(403,429,500,302)) {
            HttpURLConnection connection=mock(HttpURLConnection.class);when(connection.getResponseCode()).thenReturn(status);
            UpdateHttp http=new UpdateHttp(uri->connection);
            fails(()->http.fetch("github","3.7.0-jiujiu.6",defaults()),"HTTP "+status);
            verify(connection).disconnect();verify(connection,never()).getInputStream();verify(connection,never()).getErrorStream();
            verify(connection).setConnectTimeout(3000);verify(connection).setReadTimeout(3000);
            verify(connection).setRequestProperty(eq("User-Agent"),contains("GSit/3.7.0-jiujiu.6"));
            verify(connection).setRequestProperty("Accept","application/vnd.github+json");
            check(true,"HTTP "+status+" bounded request closed, error body never read");
        }
        HttpURLConnection connection=mock(HttpURLConnection.class);when(connection.getResponseCode()).thenReturn(200);
        AtomicBoolean closed=new AtomicBoolean();
        InputStream body=new ByteArrayInputStream("{ \"tag_name\" : \"v3.8.0\" }".getBytes(java.nio.charset.StandardCharsets.UTF_8)){
            @Override public void close(){closed.set(true);}
        };
        when(connection.getInputStream()).thenReturn(body);
        HttpURLConnection successConnection=connection;
        check(new UpdateHttp(uri->successConnection).fetch("github","3.7.0-jiujiu.6",defaults()).equals("v3.8.0"),"whitespace JSON accepted");
        verify(connection).disconnect();check(closed.get(),"successful response stream closed");
        check(UpdateHttp.parse("modrinth","[{\"files\":[{}],\"version_number\":\"3.8.0\"}]").equals("3.8.0"),"nested Modrinth objects parsed");
        for(String malformed:List.of("{}","[]","null","{broken}","{\"tag_name\":null}","{\"tag_name\":true}"))
            fails(()->UpdateHttp.parse("github",malformed),"版本信息无效");
        check(UpdateHttp.parse("spigot"," 3.8.0\n").equals("3.8.0"),"text update sources retained");
        connection=mock(HttpURLConnection.class);when(connection.getResponseCode()).thenThrow(new SocketTimeoutException());
        HttpURLConnection timed=connection;
        boolean timeout=false;try{new UpdateHttp(uri->timed).fetch("github","3.7.0",defaults());}catch(SocketTimeoutException e){timeout=true;}
        check(timeout,"socket timeout propagates without null-message handling");verify(connection).disconnect();
        var small=new UpdateOptions(500,500,1000,1024,60,60,true,"{reason}");
        connection=mock(HttpURLConnection.class);when(connection.getResponseCode()).thenReturn(200);when(connection.getContentLengthLong()).thenReturn(2048L);
        HttpURLConnection large=connection;fails(()->new UpdateHttp(uri->large).fetch("github","3.7.0",small),"响应过大");verify(connection).disconnect();
        connection=mock(HttpURLConnection.class);when(connection.getResponseCode()).thenReturn(200);when(connection.getContentLengthLong()).thenReturn(-1L);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[2048]));
        HttpURLConnection unknown=connection;fails(()->new UpdateHttp(uri->unknown).fetch("github","3.7.0",small),"响应过大");verify(connection).disconnect();
    }
    static final class Fixture implements AutoCloseable {
        final GSitMain plugin=mock(GSitMain.class);
        final ConfigService config=mock(ConfigService.class);
        final TaskService tasks=mock(TaskService.class);
        final MessageService messages=mock(MessageService.class);
        final PermissionService permissions=mock(PermissionService.class);
        final AtomicLong clock=new AtomicLong(1_000_000_000L);
        final Queue<Runnable> async=new ConcurrentLinkedQueue<>(),global=new ConcurrentLinkedQueue<>(),entity=new ConcurrentLinkedQueue<>();
        final AtomicInteger requests=new AtomicInteger();
        final List<LogRecord> logs=new CopyOnWriteArrayList<>();
        final Logger logger=Logger.getLogger("update-test-"+UUID.randomUUID());
        final AtomicReference<String> response=new AtomicReference<>("3.8.0");
        final AtomicReference<Exception> failure=new AtomicReference<>();
        final MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        final AtomicBoolean owner=new AtomicBoolean(true);
        final Player player=mock(Player.class);
        final UpdateService service;
        Fixture(){this(defaults());}
        Fixture(UpdateOptions options){
            config.CHECK_FOR_UPDATE=true;when(plugin.isEnabled()).thenReturn(true);when(plugin.isFoliaServer()).thenReturn(true);
            when(plugin.getConfigService()).thenReturn(config);when(plugin.getTaskService()).thenReturn(tasks);
            when(plugin.getMessageService()).thenReturn(messages);when(plugin.getPermissionService()).thenReturn(permissions);
            when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("GSit","3.7.0-jiujiu.6","dev.geco.gsit.GSitMain"));
            when(plugin.getResource("plugin.yml")).thenAnswer(i->new ByteArrayInputStream("source: github\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));when(plugin.getLogger()).thenReturn(logger);
            logger.setUseParentHandlers(false);logger.addHandler(new Handler(){public void publish(LogRecord r){logs.add(r);}public void flush(){}public void close(){}});
            doAnswer(i->{(i.<Boolean>getArgument(1)?global:async).add(i.getArgument(0));return null;}).when(tasks).run(any(Runnable.class),anyBoolean());
            doAnswer(i->{check(i.<Long>getArgument(2)>=1,"entity notification delay at least one tick");entity.add(i.getArgument(0));return null;}).when(tasks).runDelayed(any(Runnable.class),any(org.bukkit.entity.Entity.class),anyLong());
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);
            when(permissions.hasPermission(any(),any(String[].class))).thenReturn(true);
            bukkit.when(()->Bukkit.isOwnedByCurrentRegion(any(org.bukkit.entity.Entity.class))).thenAnswer(i->owner.get());
            bukkit.when(()->Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bukkit.when(Bukkit::getConsoleSender).thenReturn(mock(org.bukkit.command.ConsoleCommandSender.class));
            service=new UpdateService(plugin,options,(source,current,settings)->{
                requests.incrementAndGet();if(failure.get()!=null)throw failure.get();return response.get();
            },clock::get);
        }
        void drain(Queue<Runnable> queue){Runnable job;while((job=queue.poll())!=null)job.run();}
        public void close(){service.close();bukkit.close();for(Handler h:logger.getHandlers()){logger.removeHandler(h);h.close();}}
    }
    static void service()throws Exception {
        try(var f=new Fixture()) {
            f.failure.set(new UpdateHttp.Failure("HTTP 403"));
            f.service.checkForUpdates();for(int i=0;i<12;i++)f.service.checkForUpdates(f.player);
            check(f.async.size()==1&&f.requests.get()==0,"requests coalesce before async execution");f.drain(f.async);
            check(f.requests.get()==1&&f.logs.size()==1,"403 logged once");
            check(f.logs.getFirst().getThrown()==null&&f.logs.getFirst().getMessage().contains("GitHub HTTP 403"),"no stack trace on 403");
            f.service.checkForUpdates();f.service.checkForUpdates(f.player);check(f.async.isEmpty(),"failure cached without retry flood");
            verifyNoInteractions(f.messages);
            f.clock.addAndGet(86401_000_000_000L);f.service.checkForUpdates();f.drain(f.async);
            check(f.requests.get()==2&&f.logs.size()==2,"one new attempt after backoff");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates();f.service.checkForUpdates(f.player);clearInvocations(f.player,f.permissions);
            f.drain(f.async);verify(f.player,never()).isOnline();verifyNoInteractions(f.messages);verifyNoInteractions(f.permissions);
            check(f.global.size()==1&&f.entity.isEmpty(),"HTTP thread queues global result only");
            f.drain(f.global);verify(f.player,never()).isOnline();verifyNoInteractions(f.permissions);
            check(f.entity.size()==1,"broadcast queues recipient on entity scheduler once");f.drain(f.entity);
            verify(f.messages).sendMessage(eq(f.player),eq("Plugin.plugin-update"),any(Object[].class));
            verify(f.permissions).hasPermission(f.player,"Update");check(f.logs.isEmpty(),"successful new version no warning");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates(f.player);f.drain(f.async);f.drain(f.global);
            when(f.player.isOnline()).thenReturn(false);f.drain(f.entity);
            verifyNoInteractions(f.messages);check(true,"offline recipient not notified");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates(f.player);f.drain(f.async);f.drain(f.global);
            when(f.permissions.hasPermission(any(),any(String[].class))).thenReturn(false);f.drain(f.entity);
            verifyNoInteractions(f.messages);check(true,"notification rechecks permission in owner context");
        }
        try(var f=new Fixture()) {
            f.config.CHECK_FOR_UPDATE=false;f.service.checkForUpdates();f.service.checkForUpdates(f.player);
            check(f.async.isEmpty()&&f.requests.get()==0,"existing check-for-update false respected");
        }
        try(var f=new Fixture()) {
            f.response.set("v3.7.0");f.service.checkForUpdates();f.drain(f.async);
            check(f.global.isEmpty()&&f.logs.isEmpty(),"same upstream version with custom suffix is silent");
        }
        try(var f=new Fixture()) {
            f.failure.set(new SocketTimeoutException());f.service.checkForUpdates();f.drain(f.async);
            check(f.logs.size()==1&&f.logs.getFirst().getMessage().contains("请求超时")&&f.logs.getFirst().getThrown()==null,"null-message timeout produces one line");
        }
        try(var f=new Fixture()) {
            f.response.set("not-a-version");f.service.checkForUpdates();f.drain(f.async);
            check(f.logs.size()==1&&f.global.isEmpty(),"invalid version never announces update");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates();f.service.close();f.drain(f.async);
            check(f.requests.get()==0&&f.global.isEmpty()&&f.logs.isEmpty(),"closed checker drops queued HTTP task");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates();f.drain(f.async);f.service.close();f.drain(f.global);f.drain(f.entity);
            verifyNoInteractions(f.messages);check(true,"closed checker drops queued global callback");
        }
        try(var f=new Fixture()) {
            f.service.checkForUpdates(f.player);f.drain(f.async);f.drain(f.global);f.service.close();f.drain(f.entity);
            verifyNoInteractions(f.messages);check(true,"closed checker drops queued entity callback");
        }
        try(var f=new Fixture()) {
            f.owner.set(false);f.service.checkForUpdates(f.player);
            verify(f.permissions,never()).hasPermission(any(),any(String[].class));check(f.async.isEmpty()&&f.entity.size()==1,"foreign-owner entry defers permission check");
            f.owner.set(true);f.drain(f.entity);check(f.async.size()==1,"owner entry starts request after defer");
        }
        try(var f=new Fixture()) {
            // Real GSit constructs UpdateService before loadFeatures() sets its Folia flag.
            when(f.plugin.isFoliaServer()).thenReturn(false);
            var service=new UpdateService(f.plugin,defaults(),(source,current,settings)->"3.8.0",f.clock::get);
            when(f.plugin.isFoliaServer()).thenReturn(true);
            service.checkForUpdates(f.player);
            check(f.entity.isEmpty()&&f.async.size()==1,"post-onLoad cached Folia detection used without extra deferral");
            service.close();
        }
        try(var f=new Fixture()) {
            doThrow(new org.bukkit.plugin.IllegalPluginAccessException("stopping")).when(f.tasks).run(any(Runnable.class),eq(true));
            f.service.checkForUpdates();f.drain(f.async);
            check(f.logs.isEmpty(),"global scheduler shutdown race produces no stack trace");
        }
        try(var f=new Fixture()) {
            doThrow(new RejectedExecutionException("stopping")).when(f.tasks).runDelayed(any(Runnable.class),any(org.bukkit.entity.Entity.class),anyLong());
            f.service.checkForUpdates(f.player);f.drain(f.async);f.drain(f.global);
            check(f.logs.isEmpty(),"entity scheduler shutdown race produces no stack trace");
            f.owner.set(false);f.service.checkForUpdates(f.player);
            check(f.logs.isEmpty(),"foreign-owner shutdown race is silent");
        }
        try(var f=new Fixture()) {
            var callers=Executors.newFixedThreadPool(8);
            try{var jobs=new ArrayList<Callable<Void>>();for(int i=0;i<64;i++)jobs.add(()->{f.service.checkForUpdates();return null;});
                for(var result:callers.invokeAll(jobs))result.get(2,TimeUnit.SECONDS);
                check(f.async.size()==1,"concurrent regions schedule a single HTTP request");
            }finally{callers.shutdownNow();}
        }
        var quiet=new UpdateOptions(3000,3000,8000,1048576,86400,86400,false,"{reason}");
        try(var f=new Fixture(quiet)){f.failure.set(new UpdateHttp.Failure("HTTP 403"));f.service.checkForUpdates();f.drain(f.async);check(f.logs.isEmpty(),"failure log can be disabled");}
    }
    public static int run()throws Exception {
        checks=0;http();service();
        try(var f=new Fixture()) {
            var paper=mock(dev.geco.gsit.service.message.PaperMessageService.class);
            when(f.plugin.getMessageService()).thenReturn(paper);
            f.service.checkForUpdates(f.player);f.drain(f.async);f.drain(f.global);f.drain(f.entity);
            var captured=org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(paper).sendMessage(eq(f.player),eq("Plugin.plugin-update"),captured.capture());
            Object[] values=captured.getValue();
            check(values[values.length-1] instanceof MessageService.RawReplacement raw
                    && raw.text().contains("<click:open_url:"),"upstream Paper update notification retains a clickable URL");
        }
        check(UpdateService.compare("3.8.0","3.8.0-jiujiu.1")==0,"aligned 3.8 custom version is current");
        check(UpdateService.compare("v3.8.1","3.8.0-jiujiu.1")>0,"future upstream patch is detected");
        check(UpdateService.compare("3.7.0","3.7.0-jiujiu.6")==0,"custom suffix ignored safely");
        check(UpdateService.compare("v3.8.0","3.7.0-jiujiu.6")>0,"v-prefix and newer minor supported");
        check(UpdateService.compare("3.7.1","3.7.0-jiujiu.6")>0,"new patch supported");
        check(UpdateService.compare("3.6.9","3.7.0-jiujiu.6")<0,"older release not an update");
        var bad=new YamlConfiguration();bad.set("connect-timeout-ms",0);bad.set("read-timeout-ms","3000");bad.set("failure-cache-seconds",-1);bad.set("failure-message","a\nb");
        List<String> warnings=new ArrayList<>();var parsed=UpdateOptions.parse(bad,warnings::add);
        check(parsed.connectMs()==3000&&parsed.readMs()==3000&&parsed.failureSeconds()==86400&&warnings.size()==4,"invalid settings use validated defaults");
        bad=new YamlConfiguration();bad.set("failure-message",false);warnings.clear();
        check(UpdateOptions.parse(bad,warnings::add).failureMessage().contains("更新检查")&&warnings.size()==1,"failure message validates text type");
        GSitMain resourcePlugin=mock(GSitMain.class);AtomicBoolean resourceClosed=new AtomicBoolean();
        when(resourcePlugin.getResource("plugin.yml")).thenReturn(new ByteArrayInputStream("source: GitHub\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)){
            @Override public void close(){resourceClosed.set(true);}
        });
        check(UpdateOptions.source(resourcePlugin).equals("github")&&resourceClosed.get(),"descriptor read closes stream and normalizes source at startup");
        return checks;
    }
}
