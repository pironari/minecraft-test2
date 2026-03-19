package com.pachinko.plugin;

import org.bukkit.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Random;

/**
 * アタッカー（大入賞口）の開閉を管理するシステム
 */
public class AttackerSystem {

    private final PachinkoPlugin plugin;
    private final Location origin;
    private final PachinkoMachine machine;

    private boolean isOpen = false;
    private int currentRound = 0;
    private int countInRound = 0;
    private int totalBallsWon = 0;
    private BukkitTask closeTask = null;

    private final int maxRound;
    private final int maxCount;
    private final int openTicks;
    private int currentMaxRound = 15; // 図柄によって変わる実効ラウンド数

    private static final int ATTACKER_START_X = MachineBuilder.ATTACKER_X_START;
    private static final int ATTACKER_END_X   = MachineBuilder.ATTACKER_X_END;
    private static final int ATTACKER_Y        = MachineBuilder.ATTACKER_Y;

    public AttackerSystem(PachinkoPlugin plugin, Location origin, PachinkoMachine machine) {
        this.plugin   = plugin;
        this.origin   = origin;
        this.machine  = machine;
        this.maxRound  = plugin.getConfig().getInt("pachinko.max_round", 15);
        this.maxCount  = plugin.getConfig().getInt("pachinko.max_count", 10);
        this.openTicks = plugin.getConfig().getInt("pachinko.atacker_open_time", 200); // 約10秒
    }


    public void startJackpot(boolean isKakuhen, int figureValue) {
        currentRound     = 0;
        countInRound     = 0;
        totalBallsWon    = 0;
        // 777=15R、奇数=10R、偶数=7R
        currentMaxRound  = (figureValue == 7) ? 15 : (figureValue % 2 != 0 ? 10 : 7);
        // 1ラウンドあたりの払い出し（777=200玉、奇数=150玉、偶数=120玉）
        broadcastNearby(ChatColor.GOLD + "【" + currentMaxRound + "R大当たり！】1入賞10玉×10カウント！");
        startNextRound(isKakuhen);
    }

    private void startNextRound(boolean isKakuhen) {
        currentRound++;
        countInRound = 0;

        if (currentRound > currentMaxRound) {
            endJackpot(isKakuhen);
            return;
        }

        broadcastNearby(ChatColor.GOLD + "【" + currentRound + "R/" + currentMaxRound + "R】アタッカー OPEN！玉を入れろ！");
        openAttacker();

        if (closeTask != null) closeTask.cancel();
        closeTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isOpen) {
                closeAttacker();
                plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    startNextRound(isKakuhen), 10L);
            }
        }, openTicks);
    }

    public void onBallEnter() {
        if (!isOpen) return;
        countInRound++;
        totalBallsWon++;
        // 1入賞 = 100玉（10カウントで1000玉/ラウンド）
        machine.addBallsToPlayer(10);
        origin.getWorld().playSound(origin.clone().add(5, 2, 0),
            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.8f);
        broadcastNearby(ChatColor.GREEN + "  入賞！ +10玉  カウント: " + countInRound + "/" + maxCount
            + ChatColor.YELLOW + "  合計: " + (totalBallsWon * 10) + "玉");

        if (countInRound >= maxCount) {
            if (closeTask != null) closeTask.cancel();
            closeAttacker();
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                startNextRound(machine.isKakuhen()), 10L);
        }
    }

    public void startSmallHit() {
        openAttacker();
        if (closeTask != null) closeTask.cancel();
        closeTask = plugin.getServer().getScheduler().runTaskLater(plugin, () ->
            closeAttacker(), 8L);
    }

    // ─── 電チューリップ制御 ──────────────────────
    /**
     * 時短/確変中の電チュー開放
     * rounds回だけ開閉を繰り返す
     */
    public void startDenchCycle(int rounds) {
        openDench();
        doDenchCycle(rounds);
    }

    private void doDenchCycle(int remaining) {
        if (remaining <= 0) {
            machine.setDenchOpen(false);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            closeDench();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                openDench();
                doDenchCycle(remaining - 1);
            }, 15L);
        }, 40L); // 2秒開放
    }

    private void openDench() {
        machine.setDenchOpen(true);
        Location loc = origin.clone().add(MachineBuilder.DENCH_X, MachineBuilder.DENCH_Y, 1);
        loc.getBlock().setType(Material.CYAN_CONCRETE, false);
        loc.getWorld().playSound(loc, Sound.BLOCK_PISTON_EXTEND, 0.6f, 1.4f);
    }

    private void closeDench() {
        machine.setDenchOpen(false);
        Location loc = origin.clone().add(MachineBuilder.DENCH_X, MachineBuilder.DENCH_Y, 1);
        loc.getBlock().setType(Material.CYAN_STAINED_GLASS, false);
    }

    private void openAttacker() {
        isOpen = true;
        for (int x = ATTACKER_START_X; x <= ATTACKER_END_X; x++) {
            origin.clone().add(x, ATTACKER_Y, 0).getBlock().setType(Material.AIR);
        }
        Location center = origin.clone().add(5, ATTACKER_Y, 0);
        center.getWorld().spawnParticle(Particle.FLAME, center, 20, 1, 0.1, 0, 0.05);
        center.getWorld().playSound(center, Sound.BLOCK_PISTON_EXTEND, 1.0f, 0.8f);
    }

    private void closeAttacker() {
        isOpen = false;
        for (int x = ATTACKER_START_X; x <= ATTACKER_END_X; x++) {
            origin.clone().add(x, ATTACKER_Y, 0).getBlock().setType(Material.IRON_BLOCK);
        }
        Location center = origin.clone().add(5, ATTACKER_Y, 0);
        center.getWorld().playSound(center, Sound.BLOCK_PISTON_CONTRACT, 1.0f, 0.8f);
    }

    private void endJackpot(boolean wasKakuhen) {
        broadcastNearby(ChatColor.GOLD + "════════════════════════");
        broadcastNearby(ChatColor.YELLOW + "  大当たり終了！");
        broadcastNearby(ChatColor.WHITE + "  獲得玉数: " + ChatColor.AQUA + totalBallsWon + "玉");
        broadcastNearby(ChatColor.GOLD + "════════════════════════");

        new BukkitRunnable() {
            int t = 0;
            final Random rand = new Random();
            @Override
            public void run() {
                t++;
                Location fwLoc = origin.clone().add(
                    rand.nextInt(MachineBuilder.WIDTH),
                    rand.nextInt(5) + MachineBuilder.HEIGHT - 5,
                    0
                );
                fwLoc.getWorld().spawnParticle(Particle.FIREWORK, fwLoc, 20, 0.5, 0.5, 0.5, 0.1);
                if (t >= 20) {
                    cancel();
                    machine.onJackpotEnd(wasKakuhen);
                    // 確変/時短中は電チューも開放
                    if (wasKakuhen) startDenchCycle(10);
                }
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    public void reset() {
        if (isOpen) closeAttacker();
        closeDench();
        if (closeTask != null) { closeTask.cancel(); closeTask = null; }
        currentRound = 0;
        countInRound = 0;
    }

    private void broadcastNearby(String message) {
        Location center = origin.clone().add(5, 10, 0);
        // getNearbyEntitiesByType は存在しないので getNearbyEntities + instanceof で代替
        for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, 20, 20, 20)) {
            if (e instanceof org.bukkit.entity.Player p) {
                p.sendMessage(message);
            }
        }
    }

    public boolean isOpen()          { return isOpen; }
    public int getCurrentRound()     { return currentRound; }
    public int getTotalBallsWon()    { return totalBallsWon; }
}
