package cn.jiujiu.gsit.carry;

import static org.mockito.Mockito.*;
import com.destroystokyo.paper.event.player.PlayerUseUnknownEntityEvent;
import java.util.function.LongSupplier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;

/** Exercise the actual unknown-entity event -> validation -> existing throw -> cleanup chain. */
final class CarryInputRegressionTest {
    static int checks;
    static void check(boolean value,String name) { checks++;if(!value)throw new AssertionError(name); }
    static void put(CarryModule module,String name,Object value) throws Exception {
        var field=CarryModule.class.getDeclaredField(name);field.setAccessible(true);field.set(module,value);
    }
    static int run() throws Exception {
        checks=0;
        var f=new MergeRegressionTest.CarryFixture();
        var input=mock(CarryInput.class);when(input.entityId()).thenReturn(12000);
        when(f.plugin.createInput(f.actor)).thenReturn(input);
        for(var e:java.util.Map.<String,Object>of("active",true,"scheduler",f.scheduler,"service",f.service,
                "settings",f.settings,"inputs",new InteractionGate(),"clock",(LongSupplier)f.clock::get).entrySet()) put(f.plugin,e.getKey(),e.getValue());
        doCallRealMethod().when(f.plugin).clientInput(any());
        doCallRealMethod().when(f.plugin).pickupConsumed(any());
        when(f.actor.getTicksLived()).thenReturn(10);
        check(f.service.mountPlayers(f.actor,f.target),"carry creates client input");
        f.clock.addAndGet(500_000_000L);when(f.actor.getTicksLived()).thenReturn(20);
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12001,false,EquipmentSlot.HAND,new Vector()));
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,true,EquipmentSlot.HAND,null));
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,false,EquipmentSlot.OFF_HAND,new Vector()));
        check(f.vehicle.get()==f.actor,"foreign ID, left click and offhand do not throw");
        when(f.scheduler.owns(f.actor)).thenReturn(false);
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,false,EquipmentSlot.HAND,new Vector()));
        check(f.vehicle.get()==f.actor,"foreign region never accesses held entity");
        when(f.scheduler.owns(f.actor)).thenReturn(true);
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,false,EquipmentSlot.HAND,new Vector()));
        check(f.vehicle.get()==null,"empty-hand client target event starts actual throw");
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,false,EquipmentSlot.HAND,new Vector()));
        f.runTasks();verify(f.target,times(1)).setVelocity(any());verify(input,times(1)).remove();
        check(!f.service.held(f.actor),"duplicate event launches once and removes input");
        f.plugin.clientInput(new PlayerUseUnknownEntityEvent(f.actor,12000,false,EquipmentSlot.HAND,new Vector()));
        verify(f.target,times(1)).setVelocity(any());check(true,"stale client target cannot throw again");

        f=new MergeRegressionTest.CarryFixture();input=mock(CarryInput.class);when(f.plugin.createInput(f.actor)).thenReturn(input);
        check(f.service.mountPlayers(f.actor,f.target),"cleanup fixture created");
        var s=f.service.carriers.get(f.actor.getUniqueId());f.service.close();f.service.forget(s);
        verify(input,times(1)).remove();check(true,"disable cleanup removes client target exactly once");
        var late=mock(CarryInput.class);s.installInput(late);verify(late).remove();
        check(s.input.get()==null,"target created after terminal cleanup is immediately removed");
        f=new MergeRegressionTest.CarryFixture();when(f.plugin.accepts(f.actor)).thenReturn(false);
        check(f.service.mountPlayers(f.actor,f.target),"own incoming opt-out does not prohibit carrying others");
        f.service.close();
        // Reproduce the user's failure at the optional adapter boundary: native carry must survive.
        var broken=new MergeRegressionTest.CarryFixture();
        var parent=mock(dev.geco.gsit.GSitMain.class);
        var adapter=mock(dev.geco.gsit.util.EntityUtil.class);
        when(parent.getEntityUtil()).thenReturn(adapter);
        when(adapter.createCarryInput(broken.actor)).thenThrow(new IllegalStateException("owning region check"));
        put(broken.plugin,"plugin",parent);put(broken.plugin,"settings",broken.settings);
        put(broken.plugin,"inputDisabled",new java.util.concurrent.atomic.AtomicBoolean());
        doCallRealMethod().when(broken.plugin).createInput(any());
        check(broken.service.pickup(broken.actor,broken.mob),"adapter exception no longer rolls back mob carry");
        check(broken.vehicle.get()==broken.actor,"native passenger relation preserved on helper failure");
        broken.service.drop(broken.actor,"put-down");
        broken.clock.addAndGet(2_000_000_000L);
        check(broken.service.mountPlayers(broken.actor,broken.target),"player carry remains usable after helper disabled");
        verify(adapter,times(1)).createCarryInput(any());
        broken.service.drop(broken.actor,"put-down");broken.runTasks();
        check(!broken.service.held(broken.actor),"fallback carry still cleans up normally");
        switches();
        return checks;
    }
    static void switches() throws Exception {
        var x=new MenuRegressionTest.Fixture();
        var slot=CarryModule.class.getDeclaredField("instance");slot.setAccessible(true);Object previous=slot.get(null);
        var marker=org.bukkit.NamespacedKey.fromString("gsit:jiujiu-interaction-migrated");
        when(x.f.actor.getPersistentDataContainer().has(marker)).thenReturn(true);
        var pickup=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(x.toggles.trySetPlayerCanUsePlayerSit(eq(x.f.actor.getUniqueId()),anyBoolean())).thenAnswer(i->{x.accepting.set(i.getArgument(1));return true;});
        when(x.toggles.canPlayerBeCarried(x.f.actor.getUniqueId())).thenAnswer(i->pickup.get());
        when(x.toggles.trySetPlayerCanBeCarried(eq(x.f.actor.getUniqueId()),anyBoolean())).thenAnswer(i->{pickup.set(i.getArgument(1));return true;});
        slot.set(null,x.f.plugin);
        try {
            check(CarryModule.setIncoming(x.f.actor,true,false),"menu pickup switch accepted");
            check(!CarryModule.incoming(x.f.actor,true)&&CarryModule.incoming(x.f.actor,false),"menu pickup-off keeps ride enabled");
            verify(x.sit,never()).stopPlayerSit(any(),any(),anyBoolean(),anyBoolean(),anyBoolean());
            check(CarryModule.setIncoming(x.f.actor,false,false),"menu ride switch accepted");
            check(CarryModule.setIncoming(x.f.actor,true,true),"menu pickup-on accepted");
            check(CarryModule.incoming(x.f.actor,true)&&!CarryModule.incoming(x.f.actor,false),"menu pickup-on keeps ride disabled");
            verify(x.sit).stopPlayerSit(x.f.actor,dev.geco.gsit.model.StopReason.PLUGIN,true,false,true);
            when(x.toggles.trySetPlayerCanBeCarried(any(),anyBoolean())).thenReturn(false);
            check(!CarryModule.setIncoming(x.f.actor,true,false)&&pickup.get(),"failed storage leaves menu setting unchanged");
        } finally {slot.set(null,previous);}
    }
}
