package me.smart_pearl.smart_pearl.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class Smart_pearlClient implements ClientModInitializer {

    // Key binding for throwing the pearl
    private static KeyBinding pearlKey;
    // Time when the inventory was opened
    private long invOpenTime = 0;
    // Flag to check if the inventory was open in the previous tick
    private boolean wasInvOpen = false;

    // Current step in the pearl throwing sequence
    private int step = 0;
    // The player's selected slot before throwing the pearl
    private int oldSlot = -1;
    // The slot containing the ender pearl
    private int pearlSlot = -1;

    // Configuration data for the mod
    public static ConfigData config = new ConfigData();
    // The configuration file
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("smart_pearl.json").toFile();
    // Gson instance for handling JSON serialization
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Class to hold configuration data
    public static class ConfigData {
        // The time window in seconds to automatically refill pearls after closing the inventory
        public float refillWindow = 0.30f;
    }

    @Override
    public void onInitializeClient() {
        // Load the configuration from the file
        loadConfig();

        // Register the key binding for throwing the pearl
        pearlKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.smart_pearl.throw", // Translation key for the key binding name
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V, // Default key
                KeyBinding.Category.MISC // Key binding category
        ));

        // Register a client command to open the configuration screen
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("smartpearl").executes(context -> {
                MinecraftClient client = MinecraftClient.getInstance();
                // Execute on the main client thread to avoid concurrency issues
                client.execute(() -> client.setScreen(new ConfigScreen()));
                return 1; // Command executed successfully
            }));
        });

        // Register a HUD render callback to display pearl count and cooldown
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.options.hudHidden) return;

            ItemStack pearlStack = null;
            int totalPearls = 0;

            // Count all ender pearls in the player's inventory
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isOf(Items.ENDER_PEARL)) {
                    if (pearlStack == null) pearlStack = stack;
                    totalPearls += stack.getCount();
                }
            }

            // If there are pearls, render the HUD element
            if (totalPearls > 0 && pearlStack != null) {
                int x = context.getScaledWindowWidth() / 2 + 95;
                int y = context.getScaledWindowHeight() - 20;

                // Draw the ender pearl item
                context.drawItem(new ItemStack(Items.ENDER_PEARL), x, y);

                // Draw the pearl count, shifted to the left
                String countText = "x" + totalPearls;
                float scale = 0.7f;

                context.getMatrices().pushMatrix();
                context.getMatrices().translate(x + 12, y + 11); // Position the text relative to the item
                context.getMatrices().scale(scale, scale);

                context.drawTextWithShadow(client.textRenderer, countText, 0, 0, 0xFFFFFFFF);

                context.getMatrices().popMatrix();

                // Draw the cooldown timer if the item is on cooldown
                float cooldown = client.player.getItemCooldownManager().getCooldownProgress(pearlStack, 0f);
                if (cooldown > 0) {
                    String cdText = String.format("%.1fs", cooldown * 1.5);
                    int cdWidth = client.textRenderer.getWidth(cdText);
                    context.drawTextWithShadow(client.textRenderer, "§c" + cdText, x + 8 - (cdWidth / 2), y - 12, 0xFFFFFFFF);
                }
            }
        });

        // Register a client tick event to handle key presses and sequences
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Start the pearl throwing sequence if the key is pressed
            while (pearlKey.wasPressed() && step == 0) startPearlSequence(client);
            // Continue the sequence if it's already in progress
            if (step > 0) runSequence(client);

            boolean isInv = client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen;

            // Check for inventory closing to trigger auto-refill
            if (isInv && !wasInvOpen) invOpenTime = System.currentTimeMillis();
            else if (!isInv && wasInvOpen) {
                if (System.currentTimeMillis() - invOpenTime <= (config.refillWindow * 1000L)) {
                    tryRefill(client);
                }
            }
            wasInvOpen = isInv;
        });
    }

    // Starts the sequence of throwing an ender pearl
    private void startPearlSequence(MinecraftClient client) {
        int found = -1;
        ItemStack pearlStack = null;

        // Find an ender pearl in the hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(Items.ENDER_PEARL)) {
                found = i;
                pearlStack = stack;
                break;
            }
        }

        // If a pearl is found and not on cooldown, start the sequence
        if (found != -1 && pearlStack != null) {
            if (client.player.getItemCooldownManager().isCoolingDown(pearlStack)) return;

            this.oldSlot = client.player.getInventory().getSelectedSlot();
            this.pearlSlot = found;
            this.step = 1;
        }
    }

    // Executes the steps of the pearl throwing sequence
    private void runSequence(MinecraftClient client) {
        switch (step) {
            case 1 -> { // Switch to the pearl slot
                client.player.getInventory().setSelectedSlot(pearlSlot);
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));
                step = 2;
            }
            case 2 -> { // Use the item (throw the pearl)
                client.getNetworkHandler().sendPacket(
                        new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch())
                );
                step = 3;
            }
            case 3 -> { // Switch back to the original slot
                client.player.getInventory().setSelectedSlot(oldSlot);
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(oldSlot));
                step = 0; // End of sequence
            }
        }
    }

    // Tries to refill the hotbar with ender pearls from the main inventory
    private void tryRefill(MinecraftClient client) {
        int invPearl = -1;

        // Find an ender pearl in the main inventory
        for (int i = 9; i < 36; i++) {
            if (client.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                invPearl = i;
                break;
            }
        }

        // If a pearl is found in the inventory, move it to the hotbar
        if (invPearl != -1) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                // Find an empty slot or a non-full stack of ender pearls in the hotbar
                if (stack.isEmpty() || (stack.isOf(Items.ENDER_PEARL) && stack.getCount() < 16)) {
                    // Use slot action to swap the items
                    client.interactionManager.clickSlot(
                            client.player.currentScreenHandler.syncId,
                            invPearl,
                            i,
                            SlotActionType.SWAP,
                            client.player
                    );
                    break;
                }
            }
        }
    }

    // Saves the current configuration to the config file
    public static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Loads the configuration from the config file
    private void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                config = GSON.fromJson(reader, ConfigData.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // The configuration screen GUI
    public static class ConfigScreen extends Screen {

        public ConfigScreen() {
            super(Text.literal("Smart Pearl Settings"));
        }

        @Override
        protected void init() {
            int x = this.width / 2 - 100;

            // Add a slider to configure the refill timer
            this.addDrawableChild(new SliderWidget(
                    x, 60, 200, 20,
                    Text.literal(String.format("Refill Timer: %.2fs", config.refillWindow)),
                    (config.refillWindow - 0.01) / 0.99
            ) {
                @Override
                protected void updateMessage() {
                    this.setMessage(Text.literal(String.format("Refill Timer: %.2fs", config.refillWindow)));
                }

                @Override
                protected void applyValue() {
                    config.refillWindow = 0.01f + (float) this.value * 0.99f;
                }
            });

            // Add a "Done" button to save the configuration and close the screen
            this.addDrawableChild(
                    ButtonWidget.builder(Text.literal("Done"), b -> {
                        saveConfig();
                        this.client.setScreen(null);
                    }).dimensions(x, 100, 200, 20).build()
            );
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            this.renderInGameBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 25, 0xFFFFFFFF);
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean shouldPause() {
            return false; // Do not pause the game when this screen is open
        }
    }
}