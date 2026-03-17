package me.smart_pearl.smart_pearl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class Smart_pearlClient implements ClientModInitializer {

    private static KeyBinding pearlKey;

    @Override
    public void onInitializeClient() {
        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smart_pearl.throw",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (pearlKey.wasPressed()) {
                executeSmartPearl(client);
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;
            renderPearlHud(drawContext, client);
        });
    }

    private void renderPearlHud(DrawContext context, MinecraftClient client) {
        int totalPearls = 0;

        // Zählen der Perlen im Inventar
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                totalPearls += stack.getCount();
            }
        }

        // Koordinaten (etwas höher angesetzt, um Z-Ebenen-Probleme mit der Hotbar zu meiden)
        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 35; // <- Vorher -22, jetzt -35 (weiter oben)

        ItemStack pearlIcon = new ItemStack(Items.ENDER_PEARL);

        // 1. Icon zeichnen
        context.drawItem(pearlIcon, x, y);

        // 2. Text zeichnen (WICHTIG: 0xFFFFFFFF statt 0xFFFFFF für 100% Deckkraft!)
        TextRenderer tr = client.textRenderer;
        String countStr = "x" + totalPearls;

        // Nutze 0xFFFFFFFF für strahlendes Weiß mit vollem Alpha-Kanal
        context.drawTextWithShadow(tr, countStr, x + 18, y + 6, 0xFFFFFFFF);

        // 3. Cooldown abfragen
        float progress = client.player.getItemCooldownManager().getCooldownProgress(pearlIcon, 0.0f);
        if (progress > 0.0f) {
            String cdText = String.format("%.1fs", progress);
            // Nutze 0xFFFF5555 für Rot mit vollem Alpha-Kanal
            context.drawTextWithShadow(tr, cdText, x, y - 10, 0xFFFF5555);
        }
    }

    private void executeSmartPearl(MinecraftClient client) {
        int pearlSlot = -1;
        ItemStack pearlStack = null;

        // Suchen nach der Perle zum Werfen
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                pearlSlot = i;
                pearlStack = stack; // Den gefundenen ItemStack speichern
                break;
            }
        }

        if (pearlSlot != -1 && pearlStack != null) {
            // Hier übergeben wir den gefundenen ItemStack an den CooldownManager
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            int oldSlot = client.player.getInventory().getSelectedSlot();
            if (pearlSlot < 9) {
                client.player.getInventory().setSelectedSlot(pearlSlot);
                sendInteractPacket(client);
                client.player.getInventory().setSelectedSlot(oldSlot);
            } else {
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
                sendInteractPacket(client);
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
            }
        }
    }

    private void sendInteractPacket(MinecraftClient client) {
        client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
    }
}