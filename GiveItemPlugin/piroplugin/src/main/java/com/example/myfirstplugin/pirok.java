package com.example.myfirstplugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class pirok extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ぴろプラグイン起動");
        Bukkit.broadcastMessage("ぴろプラグイン起動");
        //サーバー起動時に起動する
    }
    @Override
    public void onDisable(){
        //サーバー停止の時に止まる
    }
}
