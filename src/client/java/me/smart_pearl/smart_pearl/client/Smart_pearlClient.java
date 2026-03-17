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
        ItemStack displayStack = null;

        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                totalPearls += stack.getCount();
                if (displayStack == null) displayStack = stack;
            }
        }

        if (totalPearls <= 0) return;

        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 20;

        // 1. Icon zeichnen
        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

        // 2. Text zeichnen (mit vollem Alpha 0xFFFFFFFF)
        TextRenderer tr = client.textRenderer;
        context.drawTextWithShadow(tr, "x" + totalPearls, x + 18, y + 6, 0xFFFFFFFF);

        // 3. Cooldown
        if (displayStack != null) {
            float progress = client.player.getItemCooldownManager().getCooldownProgress(displayStack, 0.0f);
            if (progress > 0.0f) {
                String cdText = String.format("%.1fs", progress);
                context.drawTextWithShadow(tr, cdText, x, y - 10, 0xFFFF5555);
            }
        }
    }

    private void executeSmartPearl(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) return;

        int pearlSlot = -1;
        ItemStack pearlStack = null;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                pearlSlot = i;
                pearlStack = stack;
                break;
            }
        }

        if (pearlSlot != -1 && pearlStack != null) {
            // FIX: Wir übergeben den konkreten ItemStack für die Cooldown-Prüfung
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            // Anti-Cheat Fix: Sprinten kurz stoppen
            boolean wasSprinting = client.player.isSprinting();
            if (wasSprinting) client.player.setSprinting(false);

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

            if (wasSprinting) client.player.setSprinting(true);
        }
    }

    private void sendInteractPacket(MinecraftClient client) {
        client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
    }
}