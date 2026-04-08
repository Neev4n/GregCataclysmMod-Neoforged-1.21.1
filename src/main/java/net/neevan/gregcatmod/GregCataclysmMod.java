package net.neevan.gregcatmod;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neevan.gregcatmod.data.GregSavedData;
import net.neevan.gregcatmod.entity.ModEntities;
import net.neevan.gregcatmod.entity.custom.GregEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(GregCataclysmMod.MODID)
public class GregCataclysmMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "gregcatmod";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public GregCataclysmMod(IEventBus modEventBus, ModContainer modContainer) {
        // Register entity types so the DeferredRegister is bound before EntityAttributeCreationEvent fires
        ModEntities.register(modEventBus);

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {

    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

    }

    @SubscribeEvent
    public void onEntityJoinLevelEvent(EntityJoinLevelEvent event){
        Entity entity = event.getEntity();

        if (entity instanceof GregEntity greg){
            greg.setCustomName(Component.literal("Greg"));
            LOGGER.info("[Greg] Named 'Greg' on join at pos={}", greg.blockPosition());

            // Update saved data on the server side only to avoid duplicate writes
            if (!event.getLevel().isClientSide()) {
                GregSavedData.get(((ServerLevel) event.getLevel()).getServer())
                        .setGregUUID(greg.getUUID());
            }
        }
    }
}
