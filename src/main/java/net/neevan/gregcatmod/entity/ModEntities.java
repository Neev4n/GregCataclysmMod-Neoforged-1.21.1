package net.neevan.gregcatmod.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neevan.gregcatmod.GregCataclysmMod;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, GregCataclysmMod.MODID);

    public static final Supplier<EntityType<GregEntity>> GREG =
            ENTITY_TYPES.register("greg", () -> EntityType.Builder.of(GregEntity::new, MobCategory.CREATURE).sized(
                    0.4f, 0.7f).build("greg"));

    public static void register(IEventBus eventBus){
        ENTITY_TYPES.register(eventBus);
    }
}
