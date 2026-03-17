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
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

public class Smart_pearlClient implements ClientModInitializer {

    private static KeyBinding pearlKey;
    private int throwDelay = -1; // -1 heißt: nichts zu tun

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

            // Startet den Prozess
            while (pearlKey.wasPressed()) {
                startPearlThrow(client);
            }

            // Der eigentliche Wurf-Verzögerer
            if (throwDelay > 0) {
                throwDelay--;
            } else if (throwDelay == 0) {
                executeFinalThrow(client);
                throwDelay = -1;
            }
        });

        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            renderPearlHud(drawContext, MinecraftClient.getInstance());
        });
    }

    private void startPearlThrow(MinecraftClient client) {
        // Sofort Sprint stoppen
        if (client.player.isSprinting()) {
            client.player.setSprinting(false);
            client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(client.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
        // Warte 2 Ticks, damit der Server den Stopp verarbeitet
        throwDelay = 2;
    }

    private void executeFinalThrow(MinecraftClient client) {
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

            // Nach dem Wurf: Sprint wieder an, wenn Taste gedrückt
            if (client.options.sprintKey.isPressed()) {
                client.player.setSprinting(true);
            }
        }
    }

    private void renderPearlHud(DrawContext context, MinecraftClient client) {
        if (client.player == null || client.options.hudHidden) return;
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

        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);
        context.drawTextWithShadow(client.textRenderer, "x" + totalPearls, x + 18, y + 6, 0xFFFFFFFF);

        if (displayStack != null) {
            float progress = client.player.getItemCooldownManager().getCooldownProgress(displayStack, 0.0f);
            if (progress > 0.0f) {
                context.drawTextWithShadow(client.textRenderer, String.format("%.1fs", progress), x, y - 10, 0xFFFF5555);
            }
        }
    }

    private void sendInteractPacket(MinecraftClient client) {
        client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
    }
}