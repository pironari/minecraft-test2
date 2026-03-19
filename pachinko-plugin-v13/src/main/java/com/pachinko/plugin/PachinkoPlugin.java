package com.pachinko.plugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class PachinkoPlugin extends JavaPlugin {

    private static PachinkoPlugin instance;
    private static Economy economy;
    private PachinkoMachine machine;
    private EconomyManager economyManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Vaultが見つかりません！プラグインを無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        economyManager = new EconomyManager(this, economy);
        machine = new PachinkoMachine(this, economyManager);

        getCommand("pachinko").setExecutor(new PachinkoCommand(this, machine));
        getCommand("pstart").setExecutor(new PachinkoCommand(this, machine));
        getCommand("pstop").setExecutor(new PachinkoCommand(this, machine));

        getServer().getPluginManager().registerEvents(machine, this);

        getLogger().info("PachinkoPlugin が有効になりました！");
    }

    @Override
    public void onDisable() {
        if (machine != null) {
            machine.shutdown();
        }
        getLogger().info("PachinkoPlugin が無効になりました。");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public static PachinkoPlugin getInstance() { return instance; }
    public static Economy getEconomy() { return economy; }
    public PachinkoMachine getMachine() { return machine; }
    public EconomyManager getEconomyManager() { return economyManager; }
}
