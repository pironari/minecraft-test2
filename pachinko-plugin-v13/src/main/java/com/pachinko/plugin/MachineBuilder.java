package com.pachinko.plugin;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.ItemFrame;

/**
 * パチンコ台の自動生成
 * oz=0  : 正面（プレイヤー側）- 釘・入賞口の穴・レバー
 * oz+1  : 背面 - 盤面ガラス背景・入賞口の色ガラス
 */
public class MachineBuilder {

    public static final int WIDTH  = 21;
    public static final int HEIGHT = 28;
    public static final int DEPTH  = 2;

    // ヘソ（中央）
    public static final int HESO_X = 10;
    public static final int HESO_Y = 7;

    // チャッカー（右寄り）
    public static final int CHAKKA_X = 15;
    public static final int CHAKKA_Y = 12;

    // 電動チューリップ（左寄り）
    public static final int DENCH_X = 5;
    public static final int DENCH_Y = 7;

    // 一般入賞口
    public static final int[][] PRIZE_HOLES_LEFT  = {{13,16},{18,16}};
    public static final int[][] PRIZE_HOLES_RIGHT = {{ 3,16},{ 8,16}};

    // アウトロ（9番 中央底）
    public static final int[][] HAZURE_HOLES = {{10, 2}};
    public static final int HESO_UNDER_X = 10;
    public static final int HESO_UNDER_Y = 4;

    // アタッカー（左側）
    public static final int ATTACKER_Y       = 5;
    public static final int ATTACKER_X_START = 2;
    public static final int ATTACKER_X_END   = 8;

    // ルーレット
    public static final int[] ROULETTE_X = {8, 9, 10};
    public static final int   ROULETTE_Y = 14;

    // 保留ランプ（右下）
    public static final int HOLD_LAMP_BASE_X = 15;
    public static final int HOLD_LAMP_Y      = 5;

    // レバー
    public static final int LEVER_LEFT_X  = WIDTH - 2; // 右端（左打ち用）
    public static final int LEVER_RIGHT_X = 1;          // 左端（右打ち用）
    public static final int LEVER_X       = LEVER_LEFT_X;

    // ─── 台の形（上下が斜め）────────────────────
    private static int leftBound(int y) {
        int top = HEIGHT - 1;
        if (y >= top)     return 4;
        if (y == top - 1) return 3;
        if (y == top - 2) return 2;
        if (y <= 0)       return 5;
        if (y == 1)       return 4;
        if (y == 2)       return 3;
        if (y == 3)       return 2;
        return 1;
    }
    private static int rightBound(int y) {
        int top = HEIGHT - 1;
        if (y >= top)     return WIDTH - 5;
        if (y == top - 1) return WIDTH - 4;
        if (y == top - 2) return WIDTH - 3;
        if (y <= 0)       return WIDTH - 6;
        if (y == 1)       return WIDTH - 5;
        if (y == 2)       return WIDTH - 4;
        if (y == 3)       return WIDTH - 3;
        return WIDTH - 2;
    }

    // ─── メインビルド ────────────────────────────
    public static void build(Location origin) {
        World w  = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        buildFrame(w, ox, oy, oz);
        buildBoard(w, ox, oy, oz);
        buildLaunchLane(w, ox, oy, oz);
        buildTenpins(w, ox, oy, oz);
        buildLeftArea(w, ox, oy, oz);
        buildCenterDisplay(w, ox, oy, oz);
        buildRightArea(w, ox, oy, oz);
        buildOutro(w, ox, oy, oz);
        buildTray(w, ox, oy, oz);
        buildLever(w, ox, oy, oz);
    }

    // ─── 外枠 ────────────────────────────────────
    private static void buildFrame(World w, int ox, int oy, int oz) {
        Material frame = Material.BLACK_STAINED_GLASS;
        for (int y = 0; y < HEIGHT; y++) {
            int lx = leftBound(y);
            int rx = rightBound(y);
            for (int x = 0; x < lx; x++) {
                setBlock(w, ox+x, oy+y, oz,   Material.AIR);
                setBlock(w, ox+x, oy+y, oz+1, Material.AIR);
            }
            for (int x = rx + 1; x < WIDTH; x++) {
                setBlock(w, ox+x, oy+y, oz,   Material.AIR);
                setBlock(w, ox+x, oy+y, oz+1, Material.AIR);
            }
            setBlock(w, ox+lx, oy+y, oz,   frame);
            setBlock(w, ox+lx, oy+y, oz+1, frame);
            setBlock(w, ox+rx, oy+y, oz,   frame);
            setBlock(w, ox+rx, oy+y, oz+1, frame);
        }
        for (int y = 0; y < HEIGHT; y++) {
            int lx = leftBound(y);
            int rx = rightBound(y);
            for (int x = lx; x <= rx; x++) {
                if (y == 0 || y == HEIGHT-1) {
                    setBlock(w, ox+x, oy+y, oz,   frame);
                    setBlock(w, ox+x, oy+y, oz+1, frame);
                }
            }
        }
    }

    // ─── 盤面背景（背面oz+1）─────────────────────
    private static void buildBoard(World w, int ox, int oy, int oz) {
        for (int y = 1; y < HEIGHT-1; y++) {
            int lx = leftBound(y) + 1;
            int rx = rightBound(y) - 1;
            for (int x = lx; x <= rx; x++)
                setBlock(w, ox+x, oy+y, oz+1, Material.BLUE_STAINED_GLASS);
        }
    }

    // ─── 発射レーン（正面oz=0）───────────────────
    private static void buildLaunchLane(World w, int ox, int oy, int oz) {
        for (int y = 2; y < HEIGHT - 4; y++)
            setBlock(w, ox + LEVER_LEFT_X + 1, oy+y, oz, Material.DARK_OAK_PLANKS);
        for (int y = 2; y < HEIGHT - 4; y++)
            setBlock(w, ox + LEVER_RIGHT_X - 1, oy+y, oz, Material.DARK_OAK_PLANKS);
    }

    // ─── 天釘（正面oz=0）────────────────────────
    private static void buildTenpins(World w, int ox, int oy, int oz) {
        int[][] outer = {{4,25},{6,26},{8,26},{10,26},{12,26},{14,26},{16,26},{17,25}};
        int[][] inner = {{5,24},{7,24},{9,24},{11,24},{13,24},{15,24},{6,23},{10,23},{14,23}};
        for (int[] n : outer) setBlock(w, ox+n[0], oy+n[1], oz, Material.END_ROD);
        for (int[] n : inner) setBlock(w, ox+n[0], oy+n[1], oz, Material.END_ROD);
    }

    // ─── 左エリア（ヘソ・チャッカー）───────────
    private static void buildLeftArea(World w, int ox, int oy, int oz) {
        int[][] nails = {
            {18,22},{16,22},{14,22},{12,22},
            {17,21},{15,21},{13,21},
            {18,20},{16,20},{14,20},{12,20},
            {17,19},{15,19},{13,19},
            {18,18},{16,18},{17,17},{15,17},
            {18,16},{16,16},{17,15},{15,15},
            {18,14},{16,14},{17,13},{15,13},
            {15,12},{12,12},{16,11},{13,11},
            {18,10},{16,10},{14,10},{12,10},
            {17, 9},{15, 9},{13, 9},
            {18, 8},{16, 8},{14, 8},{12, 8},
            {17, 7},{15, 7},{13, 7},{12, 7},
        };
        for (int[] n : nails) setBlock(w, ox+n[0], oy+n[1], oz, Material.END_ROD);

        // チャッカー（背面oz+1に色、正面oz=0に穴）
        setBlock(w, ox+CHAKKA_X-1, oy+CHAKKA_Y, oz+1, Material.GREEN_STAINED_GLASS);
        setBlock(w, ox+CHAKKA_X,   oy+CHAKKA_Y, oz+1, Material.GREEN_STAINED_GLASS);
        setBlock(w, ox+CHAKKA_X+1, oy+CHAKKA_Y, oz+1, Material.GREEN_STAINED_GLASS);
        setBlock(w, ox+CHAKKA_X,   oy+CHAKKA_Y, oz,   Material.AIR);

        // ヘソ
        setBlock(w, ox+HESO_X-1, oy+HESO_Y, oz+1, Material.YELLOW_STAINED_GLASS);
        setBlock(w, ox+HESO_X,   oy+HESO_Y, oz+1, Material.YELLOW_STAINED_GLASS);
        setBlock(w, ox+HESO_X+1, oy+HESO_Y, oz+1, Material.YELLOW_STAINED_GLASS);
        setBlock(w, ox+HESO_X,   oy+HESO_Y, oz,   Material.AIR);

        // ヘソ直下アウトロ
        setBlock(w, ox+HESO_UNDER_X-1, oy+HESO_UNDER_Y, oz+1, Material.RED_STAINED_GLASS);
        setBlock(w, ox+HESO_UNDER_X,   oy+HESO_UNDER_Y, oz+1, Material.RED_STAINED_GLASS);
        setBlock(w, ox+HESO_UNDER_X+1, oy+HESO_UNDER_Y, oz+1, Material.RED_STAINED_GLASS);
        setBlock(w, ox+HESO_UNDER_X,   oy+HESO_UNDER_Y, oz,   Material.AIR);

        // 左側一般入賞口
        for (int[] p : PRIZE_HOLES_LEFT) {
            setBlock(w, ox+p[0]-1, oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0],   oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0]+1, oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0],   oy+p[1], oz,   Material.AIR);
        }
    }

    // ─── 中央液晶 ────────────────────────────────
    private static void buildCenterDisplay(World w, int ox, int oy, int oz) {
        for (int x = 8; x <= 12; x++) {
            setBlock(w, ox+x, oy+11, oz+1, Material.NETHER_BRICKS);
            setBlock(w, ox+x, oy+20, oz+1, Material.NETHER_BRICKS);
        }
        for (int y = 12; y <= 19; y++) {
            setBlock(w, ox+8,  oy+y, oz+1, Material.NETHER_BRICKS);
            setBlock(w, ox+12, oy+y, oz+1, Material.NETHER_BRICKS);
        }
        // 保留ランプ枠
        for (int i = -1; i <= 4; i++) {
            setBlock(w, ox+HOLD_LAMP_BASE_X+i, oy+HOLD_LAMP_Y-1, oz+1, Material.NETHER_BRICKS);
            setBlock(w, ox+HOLD_LAMP_BASE_X+i, oy+HOLD_LAMP_Y+1, oz+1, Material.NETHER_BRICKS);
        }
        setBlock(w, ox+HOLD_LAMP_BASE_X-1, oy+HOLD_LAMP_Y, oz+1, Material.NETHER_BRICKS);
        setBlock(w, ox+HOLD_LAMP_BASE_X+4, oy+HOLD_LAMP_Y, oz+1, Material.NETHER_BRICKS);
        for (int i = 0; i < 4; i++)
            setBlock(w, ox+HOLD_LAMP_BASE_X+i, oy+HOLD_LAMP_Y, oz+1, Material.GRAY_STAINED_GLASS);
        // ルーレット背面ブロック（oz+1）
        for (int rx : ROULETTE_X) {
            setBlock(w, ox+rx, oy+ROULETTE_Y,   oz+1, Material.OAK_PLANKS);
            setBlock(w, ox+rx, oy+ROULETTE_Y+1, oz+1, Material.OAK_PLANKS);
        }
    }

    // ─── 右エリア（電チュー・アタッカー）────────
    private static void buildRightArea(World w, int ox, int oy, int oz) {
        int[][] nails = {
            {8,22},{6,22},{4,22},{2,22},
            {7,21},{5,21},{3,21},
            {8,20},{6,20},{4,20},{2,20},
            {7,19},{5,19},{3,19},
            {6,18},{4,18},{2,18},{5,17},{3,17},
            {6,16},{4,16},{2,16},{5,15},{3,15},
            {6,14},{4,14},{2,14},{5,13},{3,13},
            {8,12},{6,12},{4,12},{2,12},
            {7,11},{5,11},{3,11},
            {8,10},{6,10},{4,10},{2,10},
            {7, 9},{5, 9},{3, 9},
            {7, 8},{5, 8},{3, 8},
        };
        for (int[] n : nails) setBlock(w, ox+n[0], oy+n[1], oz, Material.END_ROD);

        // 電チュー
        setBlock(w, ox+DENCH_X-1, oy+DENCH_Y, oz+1, Material.CYAN_STAINED_GLASS);
        setBlock(w, ox+DENCH_X,   oy+DENCH_Y, oz+1, Material.CYAN_STAINED_GLASS);
        setBlock(w, ox+DENCH_X+1, oy+DENCH_Y, oz+1, Material.CYAN_STAINED_GLASS);
        setBlock(w, ox+DENCH_X,   oy+DENCH_Y, oz,   Material.AIR);

        // アタッカー（閉）
        for (int x = ATTACKER_X_START; x <= ATTACKER_X_END; x++)
            setBlock(w, ox+x, oy+ATTACKER_Y, oz, Material.IRON_BLOCK);
        setBlock(w, ox+ATTACKER_X_START-1, oy+ATTACKER_Y, oz, Material.RED_STAINED_GLASS);
        setBlock(w, ox+ATTACKER_X_END+1,   oy+ATTACKER_Y, oz, Material.RED_STAINED_GLASS);

        // 右側一般入賞口
        for (int[] p : PRIZE_HOLES_RIGHT) {
            setBlock(w, ox+p[0]-1, oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0],   oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0]+1, oy+p[1], oz+1, Material.LIME_STAINED_GLASS);
            setBlock(w, ox+p[0],   oy+p[1], oz,   Material.AIR);
        }
    }

    // ─── アウトロ ────────────────────────────────
    private static void buildOutro(World w, int ox, int oy, int oz) {
        // 9番排出口（中央底部）
        setBlock(w, ox+9,  oy+2, oz+1, Material.DARK_OAK_PLANKS);
        setBlock(w, ox+10, oy+2, oz+1, Material.DARK_OAK_PLANKS);
        setBlock(w, ox+11, oy+2, oz+1, Material.DARK_OAK_PLANKS);
        setBlock(w, ox+10, oy+2, oz,   Material.AIR);
        int[][] leftRail  = {{3,3},{4,3},{5,2}};
        int[][] rightRail = {{17,3},{16,3},{15,2}};
        for (int[] b : leftRail)  setBlock(w, ox+b[0], oy+b[1], oz+1, Material.DARK_OAK_PLANKS);
        for (int[] b : rightRail) setBlock(w, ox+b[0], oy+b[1], oz+1, Material.DARK_OAK_PLANKS);
    }

    // ─── トレイ ───────────────────────────────────
    private static void buildTray(World w, int ox, int oy, int oz) {
        for (int y = 0; y <= 1; y++) {
            int lx = leftBound(y) + 1;
            int rx = rightBound(y) - 1;
            for (int x = lx; x <= rx; x++) {
                setBlock(w, ox+x, oy+y, oz,   Material.GOLD_BLOCK);
                setBlock(w, ox+x, oy+y, oz+1, Material.GOLD_BLOCK);
            }
        }
        setBlock(w, ox+3, oy+2, oz+1, Material.GLOWSTONE);
    }

    // ─── レバー ───────────────────────────────────
    private static void buildLever(World w, int ox, int oy, int oz) {
        // 左打ちレバー（右端）
        setBlock(w, ox+LEVER_LEFT_X, oy+1, oz, Material.GLOWSTONE);
        setBlock(w, ox+LEVER_LEFT_X, oy+2, oz, Material.STONE);
        setBlock(w, ox+LEVER_LEFT_X, oy+3, oz, Material.STONE);
        Block leftLever = w.getBlockAt(ox+LEVER_LEFT_X, oy+4, oz);
        leftLever.setType(Material.LEVER, false);
        Switch swL = (Switch) leftLever.getBlockData();
        swL.setFace(Switch.Face.WALL);
        swL.setFacing(BlockFace.WEST);
        leftLever.setBlockData(swL, false);

        // 右打ちレバー（左端）
        setBlock(w, ox+LEVER_RIGHT_X, oy+1, oz, Material.GLOWSTONE);
        setBlock(w, ox+LEVER_RIGHT_X, oy+2, oz, Material.STONE);
        setBlock(w, ox+LEVER_RIGHT_X, oy+3, oz, Material.STONE);
        Block rightLever = w.getBlockAt(ox+LEVER_RIGHT_X, oy+4, oz);
        rightLever.setType(Material.LEVER, false);
        Switch swR = (Switch) rightLever.getBlockData();
        swR.setFace(Switch.Face.WALL);
        swR.setFacing(BlockFace.EAST);
        rightLever.setBlockData(swR, false);
    }

    // ─── 台の除去 ────────────────────────────────
    public static void remove(Location origin) {
        World w  = origin.getWorld();
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int rx : ROULETTE_X) {
            for (int dy = 0; dy <= 1; dy++) {
                Location loc = origin.clone().add(rx, ROULETTE_Y + dy, 1);
                for (org.bukkit.entity.Entity e : w.getNearbyEntities(loc, 1, 1, 1))
                    if (e instanceof ItemFrame) e.remove();
            }
        }
        for (int y = 0; y < HEIGHT; y++)
            for (int x = 0; x < WIDTH; x++)
                for (int z = 0; z < DEPTH; z++)
                    w.getBlockAt(ox+x, oy+y, oz+z).setType(Material.AIR, false);
    }

    private static void setBlock(World w, int x, int y, int z, Material mat) {
        w.getBlockAt(x, y, z).setType(mat, false);
    }

    // ─── ヘルパー ────────────────────────────────
    public static Location getHesoLocation(Location o)   { return o.clone().add(HESO_X, HESO_Y, 0); }
    public static Location getChakkaLocation(Location o) { return o.clone().add(CHAKKA_X, CHAKKA_Y, 0); }
    public static Location getDenchLocation(Location o)  { return o.clone().add(DENCH_X, DENCH_Y, 0); }
    public static Location getAttackerCenter(Location o) {
        return o.clone().add((ATTACKER_X_START + ATTACKER_X_END) / 2.0, ATTACKER_Y, 0);
    }
    public static Location[] getHazureLocations(Location o) {
        Location[] locs = new Location[HAZURE_HOLES.length];
        for (int i = 0; i < HAZURE_HOLES.length; i++)
            locs[i] = o.clone().add(HAZURE_HOLES[i][0], HAZURE_HOLES[i][1], 0);
        return locs;
    }
    public static Location[] getRouletteLocations(Location o) {
        Location[] locs = new Location[3];
        for (int i = 0; i < 3; i++)
            locs[i] = o.clone().add(ROULETTE_X[i], ROULETTE_Y, 1); // 背面oz+1
        return locs;
    }
    public static Location[] getHoldLampLocations(Location o) {
        Location[] locs = new Location[4];
        for (int i = 0; i < 4; i++)
            locs[i] = o.clone().add(HOLD_LAMP_BASE_X + i, HOLD_LAMP_Y, 1); // 背面oz+1
        return locs;
    }
}
