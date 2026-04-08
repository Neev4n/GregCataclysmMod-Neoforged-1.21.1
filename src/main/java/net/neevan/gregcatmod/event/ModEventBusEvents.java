package net.neevan.gregcatmod.event;

import net.neevan.gregcatmod.GregCataclysmMod;
import net.neevan.gregcatmod.entity.ModEntities;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@EventBusSubscriber(modid = GregCataclysmMod.MODID)
public class ModEventBusEvents {

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event){
        event.put(ModEntities.GREG.get(), GregEntity.createAttributes().build());
    }
}
