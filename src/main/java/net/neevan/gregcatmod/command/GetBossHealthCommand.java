package net.neevan.gregcatmod.command;


import com.github.L_Ender.cataclysm.entity.AnimationMonster.BossMonsters.LLibrary_Boss_Monster;
import com.github.L_Ender.cataclysm.entity.InternalAnimationMonster.IABossMonsters.IABoss_monster;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public class GetBossHealthCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registers the /getBossHealth command with the dispatcher. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("getBossHealth")
                        .requires(source -> source.hasPermission(2))
                        .executes(GetBossHealthCommand::listBossHealth)
        );
    }

    /**
     * Iterates over every loaded level on the server, finds all BaseTFBoss entities,
     * and reports their current and maximum health. If no bosses are found, notifies the caller.
     */
    private static int listBossHealth(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        List<LivingEntity> bosses = new ArrayList<>();

        // Collect all loaded BaseTFBoss instances across all dimensions
        for (var level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof LLibrary_Boss_Monster|| entity instanceof IABoss_monster) {
                    LivingEntity boss = (LivingEntity) entity;
                    bosses.add(boss);
                }
            }
        }

        if (bosses.isEmpty()) {
            LOGGER.info("getBossHealth: no Twilight Forest bosses found");
            context.getSource().sendSuccess(
                    () -> Component.literal("There are no Twilight Forest bosses loaded."), false
            );
            return 0;
        }

        LOGGER.info("getBossHealth: found {} boss(es)", bosses.size());

        // Report each boss's name, current health, and max health
        for (LivingEntity boss : bosses) {
            String name = boss.getDisplayName().getString();
            float current = boss.getHealth();
            float max = boss.getMaxHealth();
            String line = String.format("%s — %.1f / %.1f HP", name, current, max);

            LOGGER.info("getBossHealth: {}", line);
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }

        return bosses.size();
    }
}
