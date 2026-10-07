package com.rikumi.colorosmod.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 仅生成显示列表与坐标；不修改控制中心保存的数据。 */
final class IdleMediaGeometry {
    static final int CARD = 1, MEDIA = 3, VERTICAL_ONLY = 4;
    static final class Cell {
        final int index, page, col, row, cols, rows, kind;
        // 补位磁贴在一个原生双宽格中的左右半格；普通卡片为 -1。
        final int half;
        Cell(int index, int page, int col, int row, int cols, int rows, int kind) {
            this(index, page, col, row, cols, rows, kind, -1);
        }
        Cell(int index, int page, int col, int row, int cols, int rows, int kind, int half) {
            this.half = half;
            this.index = index; this.page = page; this.col = col; this.row = row;
            this.cols = cols; this.rows = rows; this.kind = kind;
        }
    }
    static final class Plan {
        final List<Cell> upper = new ArrayList<>(), lower = new ArrayList<>();
        final Set<Integer> promoted = new java.util.HashSet<>();
        int upperRows;
    }
    static Plan arrange(List<Cell> upper, List<Cell> lower, int columns, int capacityRows,
                        int lowerColumns, int lowerRows, Set<Integer> hidden, boolean fill) {
        if (columns <= 0 || capacityRows < 0 || lowerColumns <= 0 || lowerRows <= 0) return null;
        Plan plan = new Plan();
        int extent = capacityRows;
        for (Cell cell : upper) {
            if (cell.page != 0 || cell.cols <= 0 || cell.cols > columns || cell.rows <= 0) return null;
            extent += cell.rows;
        }
        boolean[][] occupied = new boolean[Math.max(1, extent)][columns];
        int cursor = 0;
        for (Cell cell : upper) {
            if (hidden.contains(cell.index)) continue;
            int position = hidden.isEmpty() ? cell.row * columns + cell.col
                    : find(occupied, columns, cell.cols, cell.rows, cursor, occupied.length);
            if (!hidden.isEmpty() && cell.kind == VERTICAL_ONLY) {
                position = -1;
                for (int row = 0; row < occupied.length; row++) {
                    if (fits(occupied, row, cell.col, cell.cols, cell.rows)) {
                        position = row * columns + cell.col;
                        break;
                    }
                }
            }
            if (position < 0 || !fits(occupied, position / columns, position % columns, cell.cols, cell.rows)) return null;
            Cell placed = new Cell(cell.index, 0, position % columns, position / columns, cell.cols, cell.rows, cell.kind);
            plan.upper.add(placed);
            occupy(occupied, placed);
            plan.upperRows = Math.max(plan.upperRows, placed.row + placed.rows);
            if (cell.kind != VERTICAL_ONLY) cursor = position + 1;
        }
        if (fill) {
            List<Cell> candidates = new ArrayList<>();
            for (Cell cell : lower) if (cell.cols == 1 && cell.rows == 1 && cell.kind == CARD)
                candidates.add(cell);
            int next = 0;
            // 每个空双宽格放两个无文字的 1×1 磁贴，每行一对，最多两行。
            for (int row = 0; row < capacityRows && next + 1 < candidates.size() && next < 4; row++) {
                for (int col = 0; col < columns; col++) {
                    if (!fits(occupied, row, col, 1, 1)) continue;
                    for (int half = 0; half < 2; half++) {
                        Cell cell = candidates.get(next++);
                        plan.upper.add(new Cell(cell.index, 0, col, row, 1, 1, CARD, half));
                        plan.promoted.add(cell.index);
                    }
                    occupied[row][col] = true;
                    plan.upperRows = Math.max(plan.upperRows, row + 1);
                    break;
                }
            }
        }
        // 下方原生列表为分页磁贴，补位后按原顺序从第一页重新填充。
        int count = lower.size() * Math.max(1, lowerRows) + 1;
        boolean[][] lowerGrid = new boolean[count][lowerColumns];
        cursor = 0;
        for (Cell cell : lower) {
            if (plan.promoted.contains(cell.index)) continue;
            if (cell.cols <= 0 || cell.cols > lowerColumns || cell.rows <= 0 || cell.rows > lowerRows) return null;
            int position = cursor;
            while (position < count * lowerColumns) {
                int row = position / lowerColumns;
                if (row % lowerRows + cell.rows <= lowerRows
                        && fits(lowerGrid, row, position % lowerColumns, cell.cols, cell.rows)) break;
                position++;
            }
            if (position >= count * lowerColumns) return null;
            int row = position / lowerColumns;
            Cell placed = new Cell(cell.index, row / lowerRows, position % lowerColumns,
                    row % lowerRows, cell.cols, cell.rows, cell.kind);
            plan.lower.add(placed);
            occupy(lowerGrid, new Cell(cell.index, 0, placed.col, row, cell.cols, cell.rows, cell.kind));
            cursor = position + 1;
        }
        return plan;
    }
    private static int find(boolean[][] grid, int columns, int width, int height, int cursor, int rows) {
        for (int position = cursor; position < rows * columns; position++) {
            if (position / columns + height <= rows && fits(grid, position / columns, position % columns, width, height)) return position;
        }
        return -1;
    }
    private static boolean fits(boolean[][] grid, int row, int col, int width, int height) {
        if (row < 0 || col < 0 || row + height > grid.length || col + width > grid[0].length) return false;
        for (int y = row; y < row + height; y++) for (int x = col; x < col + width; x++) if (grid[y][x]) return false;
        return true;
    }
    private static void occupy(boolean[][] grid, Cell cell) {
        for (int y = cell.row; y < cell.row + cell.rows; y++)
            for (int x = cell.col; x < cell.col + cell.cols; x++) grid[y][x] = true;
    }
}
