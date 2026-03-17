package me.smart_pearl.smart_pearl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
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
            if (client.player == null || client.interactionManager == null) return;
            while (pearlKey.wasPressed()) {
                executeSmartPearl(client);
            }
        });

        // HUD-Zähler
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;
            renderPearlHud(drawContext, client);
        });
    }

    private void renderPearlHud(DrawContext context, MinecraftClient client) {
        TextRenderer renderer = client.textRenderer;
        int totalPearls = 0;
        ItemStack pearlStackFromInv = null;

        // Wir gehen durch das Inventar und suchen echte Perlen-Stacks
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack != null && stack.isOf(Items.ENDER_PEARL)) {
                totalPearls += stack.getCount();
                if (pearlStackFromInv == null) pearlStackFromInv = stack;
            }
        }

        // Position: Rechts neben der Hotbar
        int x = (context.getScaledWindowWidth() / 2) + 95;
        int y = context.getScaledWindowHeight() - 22;

        // 1. Das Icon (Wir nehmen einfach ein neues, das ist egal)
        context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

        // 2. Die Zahl (Wir erzwingen die Darstellung)
        String countText = String.valueOf(totalPearls);
        context.drawTextWithShadow(renderer, "x" + countText, x + 18, y + 6, 0xFFFFFF);

        // 3. Cooldown (Nur wenn wir eine echte Perle im Inventar gefunden haben)
        if (pearlStackFromInv != null) {
            // 0.0f als Delta-Ersatz für Loom 1.15
            float progress = client.player.getItemCooldownManager().getCooldownProgress(pearlStackFromInv, 0.0f);

            if (progress > 0.0f) {
                String cdText = String.format("%.1fs", progress * 1.0f);
                context.drawTextWithShadow(renderer, cdText, x, y - 10, 0xFF5555);
            }
        } else {
            // Falls keine Perlen da sind, zeigen wir "x0" an damit du siehst, dass es geht
            context.drawTextWithShadow(renderer, "x0", x + 18, y + 6, 0xAAAAAA);
        }
    }

    private void executeSmartPearl(MinecraftClient client) {
        int pearlSlotIndex = -1;
        ItemStack foundStack = null;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack != null && stack.isOf(Items.ENDER_PEARL)) {
                pearlSlotIndex = i;
                foundStack = stack;
                break;
            }
        }

        if (pearlSlotIndex != -1 && foundStack != null) {
            // Hier nutzen wir den ItemStack für den Check
            if (client.player.getItemCooldownManager().isCoolingDown(foundStack)) return;

            int originalSlot = client.player.getInventory().getSelectedSlot();
            float yaw = client.player.getYaw();
            float pitch = client.player.getPitch();

            if (pearlSlotIndex < 9) {
                client.player.getInventory().setSelectedSlot(pearlSlotIndex);
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, yaw, pitch));
                client.player.getInventory().setSelectedSlot(originalSlot);
            } else {
                int syncId = client.player.currentScreenHandler.syncId;
                client.interactionManager.clickSlot(syncId, pearlSlotIndex, originalSlot, SlotActionType.SWAP, client.player);
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, yaw, pitch));
                client.interactionManager.clickSlot(syncId, pearlSlotIndex, originalSlot, SlotActionType.SWAP, client.player);
            }
        }
    }
}