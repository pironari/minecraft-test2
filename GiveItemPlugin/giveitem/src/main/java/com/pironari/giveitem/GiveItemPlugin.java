package com.pironari.giveitem;

import org.bukkit.plugin.java.JavaPlugin;

public class GiveItemPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("GiveItemPlugin が有効になりました！");
        getCommand("giveitem").setExecutor(new GiveItemCommand());
    }

    @Override
    public void onDisable() {
        getLogger().info("GiveItemPlugin が無効になりました！");
    }
}