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
    private int debugTick = 0;

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
        ItemStack referenceStack = null;

        // Inventar-Check
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                totalPearls += stack.getCount();
                if (referenceStack == null) referenceStack = stack;
            }
        }

        // Debug-Log alle 100 Frames (damit die Konsole nicht überflutet wird)
        if (debugTick++ % 100 == 0) {
            System.out.println("[SmartPearl] Perlen gefunden: " + totalPearls);
        }

        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 22;

        // 1. Icon
        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

        // 2. Anzahl (Wir erzwingen die Farbe und Position)
        String text = "x" + totalPearls;
        TextRenderer tr = client.textRenderer;

        // Wir zeichnen den Text ETWAS weiter weg vom Icon, um Überlappung zu vermeiden
        context.drawTextWithShadow(tr, text, x + 20, y + 5, 0xFFFFFF);

        // 3. Cooldown
        if (referenceStack != null) {
            float progress = client.player.getItemCooldownManager().getCooldownProgress(referenceStack, 0.0f);
            if (progress > 0.0f) {
                String cd = String.format("%.1fs", progress);
                context.drawTextWithShadow(tr, cd, x, y - 12, 0xFF5555);
            }
        }
    }

    private void executeSmartPearl(MinecraftClient client) {
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
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
                client.player.getInventory().setSelectedSlot(oldSlot);
            } else {
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
                client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, pearlSlot, oldSlot, SlotActionType.SWAP, client.player);
            }
        }
    }
}