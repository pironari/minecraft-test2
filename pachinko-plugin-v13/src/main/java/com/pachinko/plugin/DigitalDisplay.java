package com.pachinko.plugin;

import org.bukkit.*;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.BlockFace;

import java.util.*;

/**
 * 液晶ルーレット + カラー保留ランプ
 *
 * 保留色レベル: 1=白 2=青 3=緑 4=赤
 * 色が高いほど実際に当たりやすく、演出も豪華になる
 *
 * spin() に ReachType を渡すことで演出を切り替え:
 *   NONE       → 普通（白保留相当）
 *   NORMAL     → 通常リーチ（青相当）
 *   SUPER      → スーパーリーチ（緑相当、長め）
 *   SUPER_RED  → 赤保留リーチ（最長・派手）
 */
public class DigitalDisplay {

    public enum ReachType { NONE, NORMAL, SUPER, SUPER_RED }

    private final PachinkoPlugin plugin;
    private final Location origin;
    private final PachinkoMachine machine;

    private final ItemFrame[] frames = new ItemFrame[3];
    private int[] currentValues      = {0, 0, 0};
    private boolean spinning         = false;

    // 保留: 0=空 1=白 2=青 3=緑 4=赤
    private final int[] holdColors   = {0, 0, 0, 0};

    private static final Material[] HOLD_MATS = {
        Material.GRAY_STAINED_GLASS,
        Material.WHITE_CONCRETE,
        Material.BLUE_CONCRETE,
        Material.GREEN_CONCRETE,
        Material.RED_CONCRETE,
    };

    // 当たり/ハズレ別の色重み (index 0=白,1=青,2=緑,3=赤)
    // 当たり時の保留色重み (白/青/緑/赤)
    private static final int[] HIT_WEIGHTS  = {  3,  8, 19, 70}; // 赤70%
    // ハズレ時の保留色重み: 赤は1%程度しか出ない
    private static final int[] MISS_WEIGHTS = { 80, 13,  6,  1}; // 白80%

    private static final Material[] NUMBER_MATS = {
        Material.WHITE_DYE, Material.ORANGE_DYE, Material.MAGENTA_DYE,
        Material.LIGHT_BLUE_DYE, Material.YELLOW_DYE, Material.LIME_DYE,
        Material.PINK_DYE, Material.GRAY_DYE, Material.CYAN_DYE, Material.PURPLE_DYE,
    };
    private static final String[] NUMBERS = {"0","1","2","3","4","5","6","7","★","9"};

    private final Random random = new Random();

    public DigitalDisplay(PachinkoPlugin plugin, Location origin, PachinkoMachine machine) {
        this.plugin  = plugin;
        this.origin  = origin;
        this.machine = machine;
        spawnFrames();
        renderAllHoldBlocks();
    }

    // ─── ルーレット額縁 ──────────────────────────
    // プレイヤーが手動で置いた額縁3つを台の範囲内から探して使う
    private void spawnFrames() {
        World w = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int found = 0;

        // 台の正確な範囲内(X: ox~ox+WIDTH, Y: oy~oy+HEIGHT, Z: oz~oz+1)の額縁だけ拾う
        Location center = origin.clone().add(MachineBuilder.WIDTH / 2.0, MachineBuilder.HEIGHT / 2.0, 0.5);
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(center, MachineBuilder.WIDTH / 2.0 + 1, MachineBuilder.HEIGHT / 2.0 + 1, 2)) {
            if (!(e instanceof ItemFrame f)) continue;
            if (found >= 3) break;
            int ex = f.getLocation().getBlockX();
            int ey = f.getLocation().getBlockY();
            int ez = f.getLocation().getBlockZ();
            // 台の範囲内チェック
            if (ex < ox || ex > ox + MachineBuilder.WIDTH) continue;
            if (ey < oy || ey > oy + MachineBuilder.HEIGHT) continue;
            if (ez < oz || ez > oz + 1) continue;

            f.setFixed(true);
            f.setVisible(false);
            f.setInvulnerable(true);
            setFrameNumber(f, 0, found);
            frames[found] = f;
            found++;
        }

        if (found < 3) {
            // 見つからない場合はROULETTE_X位置に自動生成（フォールバック）
            Location[] locs = MachineBuilder.getRouletteLocations(origin);
            for (int i = found; i < 3; i++) {
                Location loc = locs[i].clone().add(0.5, 0.5, 0.5);
                ItemFrame frame = w.spawn(loc, ItemFrame.class, ff -> {
                    ff.setFacingDirection(BlockFace.SOUTH, true);
                    ff.setFixed(true);
                    ff.setVisible(false);
                    ff.setInvulnerable(true);
                });
                setFrameNumber(frame, 0, i);
                frames[i] = frame;
            }
        }
    }

    // ─── 保留ブロック ────────────────────────────
    private void updateHoldBlock(int i) {
        MachineBuilder.getHoldLampLocations(origin)[i]
            .getBlock().setType(HOLD_MATS[holdColors[i]], false);
    }

    private void renderAllHoldBlocks() {
        for (int i = 0; i < 4; i++) updateHoldBlock(i);
    }

    /** 保留追加。色は isHit で重み決定。色レベルを返す */
    public int addHold(boolean isHit) {
        for (int i = 0; i < 4; i++) {
            if (holdColors[i] == 0) {
                holdColors[i] = pickHoldColor(isHit);
                updateHoldBlock(i);
                // 25%で後から保留変化
                if (random.nextInt(4) == 0) scheduleHoldChange(i, isHit);
                return holdColors[i]; // 色レベルを返す
            }
        }
        return -1;
    }

    /** 先頭保留の色レベルを取得（消化前に演出決定に使う） */
    public int peekFirstHoldColor() {
        return holdColors[0]; // 0なら空
    }

    /** 保留を先頭から消化してシフト */
    public void consumeHold() {
        for (int i = 0; i < 3; i++) holdColors[i] = holdColors[i + 1];
        holdColors[3] = 0;
        renderAllHoldBlocks();
    }

    public int getHoldCount() {
        int n = 0;
        for (int c : holdColors) if (c > 0) n++;
        return n;
    }

    // ─── 保留変化演出 ────────────────────────────
    private void scheduleHoldChange(int slot, boolean isHit) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (holdColors[slot] == 0) return;
            int cur = holdColors[slot];
            int next = isHit
                ? Math.min(4, cur + 1 + (random.nextInt(3) == 0 ? 1 : 0))
                : Math.min(3, cur + 1);
            if (next > cur) {
                holdColors[slot] = next;
                updateHoldBlock(slot);
                playHoldChangeEffect(slot, next);
            }
        }, 40 + random.nextInt(100));
    }

    private void playHoldChangeEffect(int slot, int newColor) {
        Location loc = MachineBuilder.getHoldLampLocations(origin)[slot]
            .clone().add(0.5, 0.5, 0.5);
        Particle p = switch (newColor) {
            case 2 -> Particle.SPLASH;
            case 3 -> Particle.COMPOSTER;
            case 4 -> Particle.FLAME;
            default -> Particle.SMOKE;
        };
        loc.getWorld().spawnParticle(p, loc, 25, 0.3, 0.3, 0.1, 0.05);
        loc.getWorld().playSound(loc, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.8f + newColor * 0.2f);

        if (machine.getCurrentPlayer() != null) {
            String msg = switch (newColor) {
                case 2 -> ChatColor.AQUA  + "💙 保留変化！【青保留】";
                case 3 -> ChatColor.GREEN + "💚 保留変化！【緑保留】チャンス！";
                case 4 -> ChatColor.RED   + "❤ 保留変化！【赤保留】大チャンス！！";
                default -> "";
            };
            if (!msg.isEmpty()) machine.getCurrentPlayer().sendMessage(msg);
        }
    }

    private int pickHoldColor(boolean isHit) {
        int[] w = isHit ? HIT_WEIGHTS : MISS_WEIGHTS;
        int total = 0; for (int v : w) total += v;
        int r = random.nextInt(total), sum = 0;
        for (int i = 0; i < w.length; i++) { sum += w[i]; if (r < sum) return i + 1; }
        return 1;
    }

    // ─── スピン（保留色で演出決定） ───────────────
    /**
     * @param targetValues 停止値
     * @param reachType    演出タイプ（保留色から決定）
     * @param callback     停止後コールバック
     */
    public void spin(int[] targetValues, ReachType reachType, Runnable callback) {
        if (spinning) return;
        // フレームが存在しなければスキップしてコールバックだけ呼ぶ
        if (frames[0] == null || frames[0].isDead()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, callback::run, 5L);
            return;
        }
        spinning = true;

        // 演出タイプ別の停止タイミング
        int stopL, stopM, stopR;
        switch (reachType) {
            case SUPER_RED -> { stopL = 40; stopM = 75; stopR = 220; }
            case SUPER     -> { stopL = 40; stopM = 75; stopR = 180; }
            case NORMAL    -> { stopL = 35; stopM = 65; stopR = 130; }
            default        -> { stopL = 25; stopM = 45; stopR = 70;  }
        }

        // スピン開始音
        Sound startSound = switch (reachType) {
            case SUPER_RED -> Sound.ENTITY_FIREWORK_ROCKET_BLAST;
            case SUPER     -> Sound.ENTITY_PLAYER_LEVELUP;
            default        -> Sound.BLOCK_NOTE_BLOCK_HARP;
        };
        origin.getWorld().playSound(origin.clone().add(10, 15, 0), startSound, 1f, 1.2f);

        // 予告演出（スーパー以上）
        if (reachType == ReachType.SUPER || reachType == ReachType.SUPER_RED) {
            playPreviewEffect(reachType);
        }

        new BukkitRunnable() {
            int tick = 0;
            boolean reachAnnounced = false;
            @Override
            public void run() {
                tick++;

                // 左リール
                if (tick < stopL)        setFrameNumber(frames[0], random.nextInt(10), 0);
                else if (tick == stopL) { setFrameNumber(frames[0], targetValues[0], 0); playStop(0, reachType); }

                // 中リール
                if (tick < stopM)        setFrameNumber(frames[1], random.nextInt(10), 1);
                else if (tick == stopM) { setFrameNumber(frames[1], targetValues[1], 1); playStop(1, reachType); }

                // リーチ成立時の演出
                boolean isReach = (targetValues[0] == targetValues[2]) && reachType != ReachType.NONE;
                if (isReach && tick == stopM + 5 && !reachAnnounced) {
                    reachAnnounced = true;
                    playReachEffect(reachType);
                }

                // 右リール（スーパーリーチ中は点滅させる）
                if (tick < stopR) {
                    if (reachType == ReachType.SUPER_RED && tick > stopM + 5) {
                        // 赤保留：右リールが激しく点滅
                        if (tick % 3 == 0) setFrameNumber(frames[2], random.nextInt(10), 2);
                    } else if (reachType == ReachType.SUPER && tick > stopM + 5) {
                        if (tick % 5 == 0) setFrameNumber(frames[2], random.nextInt(10), 2);
                    } else {
                        setFrameNumber(frames[2], random.nextInt(10), 2);
                    }
                } else if (tick == stopR) {
                    setFrameNumber(frames[2], targetValues[2], 2);
                    playStop(2, reachType);
                    currentValues = targetValues;
                    spinning = false;
                    plugin.getServer().getScheduler().runTaskLater(plugin, callback::run, 15L);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ─── 予告演出 ────────────────────────────────
    private void playPreviewEffect(ReachType type) {
        Location center = origin.clone().add(10, 14, 0);

        if (type == ReachType.SUPER_RED) {
            // 赤保留予告：画面フラッシュ＋タイトル
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, 20, 20, 20)) {
                    if (e instanceof org.bukkit.entity.Player p) {
                        p.sendTitle(
                            ChatColor.RED + "🔥 赤保留 🔥",
                            ChatColor.YELLOW + "大チャンス！",
                            3, 30, 8);
                    }
                }
                center.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
                center.getWorld().spawnParticle(Particle.FLAME, center, 60, 4, 4, 0.5, 0.05);
            }, 5L);
        } else {
            // 緑保留予告
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, 20, 20, 20)) {
                    if (e instanceof org.bukkit.entity.Player p) {
                        p.sendTitle(
                            ChatColor.GREEN + "💚 スーパーリーチ",
                            ChatColor.WHITE + "チャンス！",
                            3, 25, 8);
                    }
                }
                center.getWorld().playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.7f);
                center.getWorld().spawnParticle(Particle.COMPOSTER, center, 40, 3, 3, 0.5, 0.05);
            }, 5L);
        }
    }

    // ─── リーチ演出 ──────────────────────────────
    private void playReachEffect(ReachType type) {
        Location loc = origin.clone().add(10, 14, 0);

        switch (type) {
            case SUPER_RED -> {
                // 赤保留：派手な演出
                loc.getWorld().playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 0.6f);
                loc.getWorld().spawnParticle(Particle.FLAME,  loc, 80, 5, 5, 0.5, 0.08);
                loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 40, 3, 3, 0.3, 0.1);
                for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 20, 20, 20)) {
                    if (e instanceof org.bukkit.entity.Player p) {
                        p.sendTitle(
                            ChatColor.GOLD + "★ RED REACH ★",
                            ChatColor.RED + "大チャンス！！！",
                            5, 50, 12);
                    }
                }
            }
            case SUPER -> {
                // 緑保留：中程度の演出
                loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
                loc.getWorld().spawnParticle(Particle.WITCH,    loc, 50, 4, 4, 0.5, 0.08);
                loc.getWorld().spawnParticle(Particle.COMPOSTER, loc, 30, 3, 3, 0.3, 0.1);
                for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 20, 20, 20)) {
                    if (e instanceof org.bukkit.entity.Player p) {
                        p.sendTitle(
                            ChatColor.GREEN + "★ SUPER REACH ★",
                            ChatColor.YELLOW + "チャンス！",
                            5, 45, 10);
                    }
                }
            }
            case NORMAL -> {
                // 青保留：通常リーチ
                loc.getWorld().playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.9f);
                loc.getWorld().spawnParticle(Particle.SPLASH, loc, 30, 3, 3, 0.5, 0.1);
                for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 20, 20, 20)) {
                    if (e instanceof org.bukkit.entity.Player p) {
                        p.sendTitle(
                            ChatColor.AQUA + "★ REACH ★",
                            ChatColor.WHITE + "チャンス！",
                            5, 40, 10);
                    }
                }
            }
            default -> {} // NONE: 演出なし
        }
    }

    // ─── 大当たりアニメ ──────────────────────────
    public void playJackpotAnimation(Runnable callback) {
        origin.getWorld().playSound(origin.clone().add(10, 14, 0),
            Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1f);
        Location center = origin.clone().add(10, 14, 0);
        for (org.bukkit.entity.Entity e : center.getWorld().getNearbyEntities(center, 20, 20, 20)) {
            if (e instanceof org.bukkit.entity.Player p) {
                p.sendTitle(ChatColor.GOLD + "★ 大当たり ★",
                    ChatColor.YELLOW + NUMBERS[currentValues[0]] + " "
                    + NUMBERS[currentValues[1]] + " " + NUMBERS[currentValues[2]], 5, 60, 15);
            }
        }
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                t++;
                if (t % 4 < 2) for (int i = 0; i < 3; i++) setFrameNumber(frames[i], currentValues[i], i);
                else            for (ItemFrame f : frames) if (f != null) f.setItem(new ItemStack(Material.GOLD_INGOT));
                Location fw = origin.clone().add(random.nextInt(MachineBuilder.WIDTH),
                    random.nextInt(10) + MachineBuilder.HEIGHT - 12, 0);
                fw.getWorld().spawnParticle(Particle.FIREWORK, fw, 20, 0.5, 0.5, 0.1, 0.1);
                if (t >= 50) {
                    for (int i = 0; i < 3; i++) setFrameNumber(frames[i], currentValues[i], i);
                    cancel(); callback.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ─── 確変モード背景 ──────────────────────────
    public void setKakuhenMode(boolean kakuhen) {
        Material mat = kakuhen ? Material.RED_STAINED_GLASS : Material.BLUE_STAINED_GLASS;
        for (int y = 1; y < MachineBuilder.HEIGHT-1; y++) {
            for (int x = 1; x < MachineBuilder.WIDTH-1; x++) {
                Location loc = origin.clone().add(x, y, 1);
                Material cur = loc.getBlock().getType();
                if (cur == Material.BLUE_STAINED_GLASS || cur == Material.RED_STAINED_GLASS)
                    loc.getBlock().setType(mat, false);
            }
        }
    }

    public void cleanup() {
        for (ItemFrame f : frames) { if (f != null && !f.isDead()) f.remove(); }
        Arrays.fill(holdColors, 0);
        renderAllHoldBlocks();
    }

    private void setFrameNumber(ItemFrame frame, int num, int col) {
        if (frame == null || frame.isDead()) return;
        ItemStack item = new ItemStack(NUMBER_MATS[num % NUMBER_MATS.length]);
        ItemMeta meta  = item.getItemMeta();
        ChatColor[] colors = {ChatColor.RED, ChatColor.AQUA, ChatColor.YELLOW};
        meta.setDisplayName(colors[col] + NUMBERS[num % NUMBERS.length]);
        item.setItemMeta(meta);
        frame.setItem(item, false);
        frame.setRotation(org.bukkit.Rotation.NONE);
    }

    private void playStop(int col, ReachType type) {
        float pitch = (type == ReachType.SUPER_RED) ? 0.6f + col * 0.1f : 0.9f + col * 0.15f;
        origin.getWorld().playSound(origin.clone().add(10, 14, 0),
            Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
    }

    public boolean isSpinning()       { return spinning; }
    public int[]   getCurrentValues() { return currentValues; }
}
