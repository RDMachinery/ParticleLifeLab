/*
 * ParticleLifeLab.java
 *
 * A single-file Java Swing laboratory for "Particle Life" — the artificial-life
 * toy in which a few species of coloured particles attract and repel one another
 * according to a small, asymmetric interaction matrix. Out of nothing but those
 * pairwise rules come cells, membranes, worms, chasers and little ecosystems.
 *
 * The model follows the now-standard formulation popularised by Jeffrey Ventrella
 * ("Clusters") and Tom Mohr ("particle-life"):
 *
 *   - Every particle feels every other particle within a radius rMax.
 *   - Below a fraction beta of rMax, all particles repel (they cannot overlap).
 *   - Between beta and 1, species i feels species j with strength a[i][j] in [-1, 1],
 *     shaped as a triangular "tent" that peaks half-way out.
 *   - a[i][j] need not equal a[j][i]. That asymmetry breaks Newton's third law,
 *     which is precisely what lets the system keep moving, chasing and organising.
 *   - Velocities decay by friction; the world is a torus (or a walled box).
 *
 * Run it with:   java ParticleLifeLab.java
 * Self-test:     java ParticleLifeLab.java --selftest
 *
 * Controls (in the world view):
 *   Mouse wheel           zoom in / out around the cursor
 *   Left-drag             pan the camera
 *   Right-drag            stir: push particles away from the cursor
 *   Shift + right-drag    gather: pull particles towards the cursor
 *   Space                 pause / resume
 *
 * Controls (in the matrix editor):
 *   Left-click / wheel up     increase a[row][col] by 0.1   (Shift: 0.5)
 *   Right-click / wheel down  decrease a[row][col] by 0.1   (Shift: 0.5)
 *   Middle-click              set the cell to zero
 *   Row = the species that feels the force; column = the species it reacts to.
 *
 * Author: Mario Gianota, with Claude. MIT licence.
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Random;
import java.util.stream.IntStream;

public class ParticleLifeLab extends JFrame {

    // ------------------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------------------

    static final int MAX_SPECIES = 8;
    static final double DT = 0.02;

    static final Color BG = new Color(8, 9, 14);
    static final Color PANEL_BG = new Color(22, 24, 32);
    static final Color PANEL_FG = new Color(214, 218, 228);
    static final Color ACCENT = new Color(120, 190, 255);

    /** Species colours: chosen to be distinct on a dark background. */
    static final Color[] SPECIES = {
            new Color(255, 82, 82),    // red
            new Color(255, 176, 46),   // amber
            new Color(250, 240, 90),   // yellow
            new Color(80, 220, 110),   // green
            new Color(60, 210, 230),   // cyan
            new Color(80, 130, 255),   // blue
            new Color(190, 100, 255),  // violet
            new Color(255, 110, 200),  // pink
    };

    // ------------------------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------------------------

    /** All particle arrays live together so they can be swapped atomically. */
    static final class State {
        final int n;
        final float[] x, y, vx, vy;
        final int[] t;

        State(int n) {
            this.n = n;
            x = new float[n];
            y = new float[n];
            vx = new float[n];
            vy = new float[n];
            t = new int[n];
        }
    }

    static final class Sim {
        volatile State state;
        final double[][] matrix = new double[MAX_SPECIES][MAX_SPECIES];
        volatile int species = 6;

        // Parameters (edited live from the UI)
        volatile double rMax = 0.08;
        volatile double forceFactor = 10.0;
        volatile double frictionHalfLife = 0.04;
        volatile double beta = 0.3;
        volatile boolean wrap = true;

        // Mouse interaction, in world coordinates
        volatile boolean mouseActive = false;
        volatile double mouseX, mouseY, mouseStrength, mouseRadius = 0.12;

        // Statistics
        volatile double meanSpeed = 0;
        volatile double meanNeighbours = 0;
        volatile long steps = 0;

        long seed;
        Random rng;

        // Spatial grid scratch space
        private int[] cellStart = new int[0];
        private int[] cellCount = new int[0];
        private int[] order = new int[0];
        private int[] cellOf = new int[0];
        private int[] neighbourCount = new int[0];

        Sim(long seed) {
            setSeed(seed);
        }

        void setSeed(long seed) {
            this.seed = seed;
            this.rng = new Random(seed);
        }

        // ---- Setup -------------------------------------------------------------------

        synchronized void spawn(int n) {
            State s = new State(n);
            int k = species;
            for (int i = 0; i < n; i++) {
                s.x[i] = rng.nextFloat();
                s.y[i] = rng.nextFloat();
                s.t[i] = rng.nextInt(k);
            }
            state = s;
            steps = 0;
        }

        /** Change the species count without moving particles; types are reassigned. */
        synchronized void setSpecies(int k) {
            species = k;
            State s = state;
            if (s != null) {
                for (int i = 0; i < s.n; i++) s.t[i] = rng.nextInt(k);
            }
        }

        synchronized void randomMatrix() {
            for (int i = 0; i < MAX_SPECIES; i++)
                for (int j = 0; j < MAX_SPECIES; j++)
                    matrix[i][j] = round1(rng.nextDouble() * 2 - 1);
        }

        synchronized void mutateMatrix(double amount) {
            int k = species;
            for (int i = 0; i < k; i++)
                for (int j = 0; j < k; j++)
                    matrix[i][j] = clamp(round2(matrix[i][j] + rng.nextGaussian() * amount), -1, 1);
        }

        synchronized void symmetrize() {
            int k = species;
            for (int i = 0; i < k; i++)
                for (int j = i + 1; j < k; j++) {
                    double m = round2((matrix[i][j] + matrix[j][i]) / 2);
                    matrix[i][j] = m;
                    matrix[j][i] = m;
                }
        }

        synchronized void applyPreset(String name) {
            int k = species;
            for (double[] row : matrix) java.util.Arrays.fill(row, 0);
            switch (name) {
                case "Random":
                    randomMatrix();
                    break;
                case "Random symmetric":
                    randomMatrix();
                    symmetrize();
                    break;
                case "Snakes":
                    // Each species is strongly drawn to the next and mildly dislikes the rest:
                    // sinuous, writhing filaments.
                    for (int i = 0; i < k; i++) {
                        for (int j = 0; j < k; j++) matrix[i][j] = -0.3;
                        matrix[i][i] = 0.2;
                        matrix[i][(i + 1) % k] = 1.0;
                    }
                    break;
                case "Chains":
                    for (int i = 0; i < k; i++)
                        for (int j = 0; j < k; j++) {
                            int d = Math.floorMod(j - i, k);
                            matrix[i][j] = (d == 0) ? 1.0 : (d == 1 || d == k - 1) ? 0.2 : -1.0;
                        }
                    break;
                case "Chasers":
                    // Each species is drawn to the next and fled by the one behind it.
                    for (int i = 0; i < k; i++) {
                        matrix[i][i] = 0.5;
                        matrix[i][(i + 1) % k] = 0.5;
                        matrix[(i + 1) % k][i] = -0.9;
                    }
                    break;
                case "Cells":
                    // A sticky nucleus species wrapped by membrane species that repel outsiders.
                    for (int i = 0; i < k; i++)
                        for (int j = 0; j < k; j++)
                            matrix[i][j] = -0.2;
                    for (int i = 0; i < k; i++) matrix[i][i] = 0.6;
                    for (int i = 1; i < k; i++) {
                        matrix[0][i] = 0.4;
                        matrix[i][0] = 0.9;
                    }
                    break;
                case "Segregation":
                    for (int i = 0; i < k; i++)
                        for (int j = 0; j < k; j++)
                            matrix[i][j] = (i == j) ? 0.8 : -0.3;
                    break;
                case "Gas (all zero)":
                default:
                    break;
            }
        }

        // ---- The force law -----------------------------------------------------------

        /**
         * Force at normalised distance r in [0, 1), with attraction a.
         * Universal repulsion below beta; a tent-shaped response of height a above it.
         */
        static double force(double r, double a, double beta) {
            if (r < beta) return r / beta - 1.0;
            if (r < 1.0) return a * (1.0 - Math.abs(2.0 * r - 1.0 - beta) / (1.0 - beta));
            return 0.0;
        }

        // ---- One time step -----------------------------------------------------------

        synchronized void step() {
            final State s = state;
            final int n = s.n;
            final double rMax = this.rMax;
            final double rMax2 = rMax * rMax;
            final double beta = this.beta;
            final double ff = this.forceFactor;
            final double friction = Math.pow(0.5, DT / frictionHalfLife);
            final boolean wrap = this.wrap;
            final int k = species;

            // Copy the matrix so the parallel workers read a stable snapshot.
            final double[] m = new double[k * k];
            for (int i = 0; i < k; i++)
                for (int j = 0; j < k; j++)
                    m[i * k + j] = matrix[i][j];

            final boolean mouse = mouseActive;
            final double mx = mouseX, my = mouseY, ms = mouseStrength, mr = mouseRadius;

            // Build a uniform grid with cells at least rMax wide.
            final int g = Math.max(1, (int) Math.floor(1.0 / rMax));
            final boolean useGrid = g >= 3;
            final int cells = g * g;
            if (cellStart.length < cells + 1) {
                cellStart = new int[cells + 1];
                cellCount = new int[cells];
            }
            if (order.length < n) {
                order = new int[n];
                cellOf = new int[n];
                neighbourCount = new int[n];
            }
            java.util.Arrays.fill(cellCount, 0, cells, 0);
            for (int i = 0; i < n; i++) {
                int cx = Math.min(g - 1, Math.max(0, (int) (s.x[i] * g)));
                int cy = Math.min(g - 1, Math.max(0, (int) (s.y[i] * g)));
                int c = cy * g + cx;
                cellOf[i] = c;
                cellCount[c]++;
            }
            cellStart[0] = 0;
            for (int c = 0; c < cells; c++) cellStart[c + 1] = cellStart[c] + cellCount[c];
            int[] fill = new int[cells];
            for (int i = 0; i < n; i++) {
                int c = cellOf[i];
                order[cellStart[c] + fill[c]++] = i;
            }

            final int[] cellStartF = cellStart, orderF = order, nbF = neighbourCount;

            // Pass 1: forces -> velocities (reads positions only, so safe in parallel).
            int chunks = Math.max(1, Math.min(64, n / 256));
            IntStream.range(0, chunks).parallel().forEach(chunk -> {
                int from = (int) ((long) n * chunk / chunks);
                int to = (int) ((long) n * (chunk + 1) / chunks);
                for (int i = from; i < to; i++) {
                    float xi = s.x[i], yi = s.y[i];
                    int ti = s.t[i];
                    double fx = 0, fy = 0;
                    int count = 0;

                    if (useGrid) {
                        int cx = Math.min(g - 1, Math.max(0, (int) (xi * g)));
                        int cy = Math.min(g - 1, Math.max(0, (int) (yi * g)));
                        for (int oy = -1; oy <= 1; oy++) {
                            int ny = cy + oy;
                            if (ny < 0 || ny >= g) {
                                if (!wrap) continue;
                                ny = Math.floorMod(ny, g);
                            }
                            for (int ox = -1; ox <= 1; ox++) {
                                int nx = cx + ox;
                                if (nx < 0 || nx >= g) {
                                    if (!wrap) continue;
                                    nx = Math.floorMod(nx, g);
                                }
                                int c = ny * g + nx;
                                for (int p = cellStartF[c], end = cellStartF[c + 1]; p < end; p++) {
                                    int j = orderF[p];
                                    if (j == i) continue;
                                    double dx = s.x[j] - xi, dy = s.y[j] - yi;
                                    if (wrap) {
                                        dx -= Math.rint(dx);
                                        dy -= Math.rint(dy);
                                    }
                                    double r2 = dx * dx + dy * dy;
                                    if (r2 > 0 && r2 < rMax2) {
                                        double r = Math.sqrt(r2);
                                        double f = force(r / rMax, m[ti * k + s.t[j]], beta);
                                        fx += dx / r * f;
                                        fy += dy / r * f;
                                        count++;
                                    }
                                }
                            }
                        }
                    } else {
                        for (int j = 0; j < n; j++) {
                            if (j == i) continue;
                            double dx = s.x[j] - xi, dy = s.y[j] - yi;
                            if (wrap) {
                                dx -= Math.rint(dx);
                                dy -= Math.rint(dy);
                            }
                            double r2 = dx * dx + dy * dy;
                            if (r2 > 0 && r2 < rMax2) {
                                double r = Math.sqrt(r2);
                                double f = force(r / rMax, m[ti * k + s.t[j]], beta);
                                fx += dx / r * f;
                                fy += dy / r * f;
                                count++;
                            }
                        }
                    }

                    fx *= rMax * ff;
                    fy *= rMax * ff;

                    if (mouse) {
                        double dx = xi - mx, dy = yi - my;
                        if (wrap) {
                            dx -= Math.rint(dx);
                            dy -= Math.rint(dy);
                        }
                        double d = Math.sqrt(dx * dx + dy * dy);
                        if (d < mr && d > 1e-6) {
                            double fall = 1.0 - d / mr;
                            fx += dx / d * ms * fall;
                            fy += dy / d * ms * fall;
                        }
                    }

                    s.vx[i] = (float) (s.vx[i] * friction + fx * DT);
                    s.vy[i] = (float) (s.vy[i] * friction + fy * DT);
                    nbF[i] = count;
                }
            });

            // Pass 2: integrate positions and gather statistics.
            double speedSum = 0, nbSum = 0;
            for (int i = 0; i < n; i++) {
                float nx = (float) (s.x[i] + s.vx[i] * DT);
                float ny = (float) (s.y[i] + s.vy[i] * DT);
                if (wrap) {
                    nx -= (float) Math.floor(nx);
                    ny -= (float) Math.floor(ny);
                    if (nx >= 1f) nx = 0f;
                    if (ny >= 1f) ny = 0f;
                } else {
                    if (nx < 0f) { nx = -nx; s.vx[i] = -s.vx[i]; }
                    if (nx > 1f) { nx = 2f - nx; s.vx[i] = -s.vx[i]; }
                    if (ny < 0f) { ny = -ny; s.vy[i] = -s.vy[i]; }
                    if (ny > 1f) { ny = 2f - ny; s.vy[i] = -s.vy[i]; }
                    nx = Math.min(0.99999f, Math.max(0f, nx));
                    ny = Math.min(0.99999f, Math.max(0f, ny));
                }
                s.x[i] = nx;
                s.y[i] = ny;
                speedSum += Math.sqrt(s.vx[i] * s.vx[i] + s.vy[i] * s.vy[i]);
                nbSum += nbF[i];
            }
            meanSpeed = n > 0 ? speedSum / n : 0;
            meanNeighbours = n > 0 ? nbSum / n : 0;
            steps++;
        }

        // ---- Matrix text I/O ---------------------------------------------------------

        synchronized String matrixToText() {
            StringBuilder sb = new StringBuilder();
            int k = species;
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    if (j > 0) sb.append(' ');
                    sb.append(String.format(Locale.ROOT, "%5.2f", matrix[i][j]));
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        /** Parses a square matrix of numbers; returns its size, or -1 on failure. */
        synchronized int matrixFromText(String text) {
            String[] lines = text.trim().split("\\R+");
            int k = lines.length;
            if (k < 1 || k > MAX_SPECIES) return -1;
            double[][] tmp = new double[k][k];
            for (int i = 0; i < k; i++) {
                String[] parts = lines[i].trim().split("[\\s,;]+");
                if (parts.length != k) return -1;
                for (int j = 0; j < k; j++) {
                    try {
                        tmp[i][j] = clamp(Double.parseDouble(parts[j]), -1, 1);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
            for (int i = 0; i < k; i++) System.arraycopy(tmp[i], 0, matrix[i], 0, k);
            return k;
        }
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    // ------------------------------------------------------------------------------------
    // World view
    // ------------------------------------------------------------------------------------

    final class WorldView extends JPanel {
        double camX = 0.5, camY = 0.5, zoom = 1.0;
        boolean trails = false;
        int dotSize = 3;
        private BufferedImage canvas;
        private Point dragStart;
        private double dragCamX, dragCamY;

        WorldView() {
            setBackground(BG);
            setPreferredSize(new Dimension(780, 780));
            setFocusable(true);

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dragStart = e.getPoint();
                        dragCamX = camX;
                        dragCamY = camY;
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        updateMouse(e, true);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart != null && SwingUtilities.isLeftMouseButton(e)) {
                        double scale = scale();
                        camX = dragCamX - (e.getX() - dragStart.x) / scale;
                        camY = dragCamY - (e.getY() - dragStart.y) / scale;
                        normaliseCamera();
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        updateMouse(e, true);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                    sim.mouseActive = false;
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    double[] before = toWorld(e.getX(), e.getY());
                    double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                    zoom = clamp(zoom * factor, 1.0, 20.0);
                    double[] after = toWorld(e.getX(), e.getY());
                    camX += before[0] - after[0];
                    camY += before[1] - after[1];
                    normaliseCamera();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        private void updateMouse(MouseEvent e, boolean active) {
            double[] w = toWorld(e.getX(), e.getY());
            sim.mouseX = sim.wrap ? w[0] - Math.floor(w[0]) : w[0];
            sim.mouseY = sim.wrap ? w[1] - Math.floor(w[1]) : w[1];
            sim.mouseStrength = e.isShiftDown() ? -2.0 : 2.0;
            sim.mouseRadius = 0.12 / Math.sqrt(zoom);
            sim.mouseActive = active;
        }

        void normaliseCamera() {
            if (sim.wrap) {
                camX -= Math.floor(camX);
                camY -= Math.floor(camY);
            } else {
                double half = 0.5 / zoom;
                camX = clamp(camX, half, 1 - half);
                camY = clamp(camY, half, 1 - half);
            }
        }

        void resetCamera() {
            camX = 0.5;
            camY = 0.5;
            zoom = 1.0;
        }

        double scale() {
            return Math.min(getWidth(), getHeight()) * zoom;
        }

        double[] toWorld(int sx, int sy) {
            double s = scale();
            return new double[]{camX + (sx - getWidth() / 2.0) / s, camY + (sy - getHeight() / 2.0) / s};
        }

        @Override
        protected void paintComponent(Graphics g0) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (canvas == null || canvas.getWidth() != w || canvas.getHeight() != h) {
                canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D cg = canvas.createGraphics();
                cg.setColor(BG);
                cg.fillRect(0, 0, w, h);
                cg.dispose();
            }
            renderInto(canvas, trails);
            g0.drawImage(canvas, 0, 0, null);
        }

        void renderInto(BufferedImage img, boolean fade) {
            render(sim, img, camX, camY, zoom, dotSize, fade);
        }
    }

    /** Draws the simulation into an image. Static so the headless self-test can use it. */
    static void render(Sim sim, BufferedImage img, double camX, double camY, double zoom, int dotSize, boolean fade) {
        {
            int w = img.getWidth(), h = img.getHeight();
            Graphics2D g = img.createGraphics();
            if (fade) {
                g.setColor(new Color(BG.getRed(), BG.getGreen(), BG.getBlue(), 38));
            } else {
                g.setColor(BG);
            }
            g.fillRect(0, 0, w, h);

            double s = Math.min(w, h) * zoom;
            double ox = w / 2.0, oy = h / 2.0;
            boolean wrap = sim.wrap;

            // World border when not wrapping, or when the world is smaller than the view.
            if (!wrap || zoom < 1.0001) {
                double bx = ox + (0 - camX) * s, by = oy + (0 - camY) * s;
                if (!wrap) {
                    g.setColor(new Color(60, 66, 84));
                    g.drawRect((int) bx, (int) by, (int) s, (int) s);
                } else if (w != h) {
                    g.setColor(new Color(30, 33, 44));
                    g.drawRect((int) bx, (int) by, (int) s, (int) s);
                }
            }

            State st = sim.state;
            if (st != null) {
                int n = st.n;
                int d = Math.max(1, (int) Math.round(dotSize * Math.sqrt(zoom)));
                boolean round = d >= 3;
                if (round) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Ellipse2D.Double dot = new Ellipse2D.Double();
                int lastType = -1;
                double halfW = (w / 2.0) / s + 0.02, halfH = (h / 2.0) / s + 0.02;
                for (int i = 0; i < n; i++) {
                    double rx = st.x[i] - camX, ry = st.y[i] - camY;
                    if (wrap) {
                        rx -= Math.rint(rx);
                        ry -= Math.rint(ry);
                    }
                    if (Math.abs(rx) > halfW || Math.abs(ry) > halfH) continue;
                    double px = ox + rx * s, py = oy + ry * s;
                    int ti = st.t[i];
                    if (ti != lastType) {
                        g.setColor(SPECIES[ti % SPECIES.length]);
                        lastType = ti;
                    }
                    if (round) {
                        dot.setFrame(px - d / 2.0, py - d / 2.0, d, d);
                        g.fill(dot);
                    } else {
                        g.fillRect((int) px, (int) py, d, d);
                    }
                }
            }

            if (sim.mouseActive) {
                double rx = sim.mouseX - camX, ry = sim.mouseY - camY;
                if (wrap) {
                    rx -= Math.rint(rx);
                    ry -= Math.rint(ry);
                }
                double r = sim.mouseRadius * s;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(sim.mouseStrength > 0 ? new Color(255, 120, 120, 110) : new Color(120, 200, 255, 110));
                g.draw(new Ellipse2D.Double(ox + rx * s - r, oy + ry * s - r, 2 * r, 2 * r));
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------------------------
    // Matrix editor
    // ------------------------------------------------------------------------------------

    final class MatrixEditor extends JPanel {
        int selRow = 0, selCol = 0;

        MatrixEditor() {
            setBackground(PANEL_BG);
            setPreferredSize(new Dimension(300, 300));
            setToolTipText("");
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int[] rc = cellAt(e.getX(), e.getY());
                    if (rc == null) return;
                    double step = e.isShiftDown() ? 0.5 : 0.1;
                    synchronized (sim) {
                        double v = sim.matrix[rc[0]][rc[1]];
                        if (SwingUtilities.isLeftMouseButton(e)) v += step;
                        else if (SwingUtilities.isRightMouseButton(e)) v -= step;
                        else if (SwingUtilities.isMiddleMouseButton(e)) v = 0;
                        sim.matrix[rc[0]][rc[1]] = clamp(round2(v), -1, 1);
                    }
                    select(rc[0], rc[1]);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    int[] rc = cellAt(e.getX(), e.getY());
                    if (rc == null) return;
                    double step = (e.isShiftDown() ? 0.5 : 0.1) * -Math.signum(e.getPreciseWheelRotation());
                    synchronized (sim) {
                        sim.matrix[rc[0]][rc[1]] = clamp(round2(sim.matrix[rc[0]][rc[1]] + step), -1, 1);
                    }
                    select(rc[0], rc[1]);
                }
            };
            addMouseListener(ma);
            addMouseWheelListener(ma);
        }

        void select(int r, int c) {
            selRow = r;
            selCol = c;
            repaint();
            forceCurve.repaint();
        }

        private Rectangle grid() {
            int k = sim.species;
            int margin = 22;
            int size = Math.min(getWidth(), getHeight()) - margin - 6;
            int cell = Math.max(8, size / k);
            return new Rectangle(margin, margin, cell * k, cell * k);
        }

        int[] cellAt(int x, int y) {
            Rectangle r = grid();
            if (!r.contains(x, y)) return null;
            int k = sim.species;
            int cell = r.width / k;
            int c = (x - r.x) / cell, row = (y - r.y) / cell;
            if (row < 0 || row >= k || c < 0 || c >= k) return null;
            return new int[]{row, c};
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            int[] rc = cellAt(e.getX(), e.getY());
            if (rc == null) return null;
            return String.format(Locale.ROOT, "Species %d feels species %d with a = %+.2f",
                    rc[0] + 1, rc[1] + 1, sim.matrix[rc[0]][rc[1]]);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int k = sim.species;
            Rectangle r = grid();
            int cell = r.width / k;

            // Species swatches along the top (columns) and left (rows).
            for (int i = 0; i < k; i++) {
                g.setColor(SPECIES[i]);
                g.fillOval(r.x + i * cell + cell / 2 - 6, r.y - 18, 12, 12);
                g.fillOval(r.x - 18, r.y + i * cell + cell / 2 - 6, 12, 12);
            }

            Font f = getFont().deriveFont(Font.PLAIN, Math.max(9f, Math.min(13f, cell / 3.6f)));
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    double v = sim.matrix[i][j];
                    g.setColor(valueColour(v));
                    int x = r.x + j * cell, y = r.y + i * cell;
                    g.fillRect(x + 1, y + 1, cell - 2, cell - 2);
                    if (cell >= 26) {
                        String s = String.format(Locale.ROOT, "%.1f", v);
                        g.setColor(Math.abs(v) > 0.55 ? new Color(10, 10, 14) : new Color(200, 204, 214));
                        g.drawString(s, x + (cell - fm.stringWidth(s)) / 2, y + (cell + fm.getAscent()) / 2 - 2);
                    }
                }
            }
            if (selRow < k && selCol < k) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.drawRect(r.x + selCol * cell, r.y + selRow * cell, cell, cell);
            }
        }
    }

    static Color valueColour(double v) {
        v = clamp(v, -1, 1);
        Color neutral = new Color(40, 43, 54);
        Color pos = new Color(70, 220, 120);
        Color neg = new Color(240, 70, 80);
        Color target = v >= 0 ? pos : neg;
        double t = Math.abs(v);
        return new Color(
                (int) (neutral.getRed() + (target.getRed() - neutral.getRed()) * t),
                (int) (neutral.getGreen() + (target.getGreen() - neutral.getGreen()) * t),
                (int) (neutral.getBlue() + (target.getBlue() - neutral.getBlue()) * t));
    }

    // ------------------------------------------------------------------------------------
    // Force curve and energy plot
    // ------------------------------------------------------------------------------------

    final class ForceCurve extends JPanel {
        ForceCurve() {
            setBackground(PANEL_BG);
            setPreferredSize(new Dimension(300, 120));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int pad = 10, top = 20;
            int pw = w - 2 * pad, ph = h - top - pad;
            int zeroY = top + ph / 2;
            int r = matrixEditor.selRow, c = matrixEditor.selCol;
            double a = sim.matrix[r][c];
            double beta = sim.beta;

            g.setColor(new Color(60, 64, 80));
            g.drawLine(pad, zeroY, pad + pw, zeroY);
            int bx = pad + (int) (beta * pw);
            g.setColor(new Color(50, 54, 70));
            g.drawLine(bx, top, bx, top + ph);

            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i <= pw; i++) {
                double rr = i / (double) pw;
                double f = Sim.force(rr, a, beta);
                double y = zeroY - f * (ph / 2.0 - 2);
                if (i == 0) path.moveTo(pad + i, y);
                else path.lineTo(pad + i, y);
            }
            g.setStroke(new BasicStroke(2f));
            g.setColor(ACCENT);
            g.draw(path);

            g.setColor(SPECIES[r]);
            g.fillOval(pad, 5, 10, 10);
            g.setColor(SPECIES[c]);
            g.fillOval(pad + 46, 5, 10, 10);
            g.setColor(PANEL_FG);
            g.setFont(getFont().deriveFont(11f));
            g.drawString("feels", pad + 14, 14);
            g.drawString(String.format(Locale.ROOT, "a = %+.2f   (0 → rMax)", a), pad + 62, 14);
        }
    }

    final class EnergyPlot extends JPanel {
        final double[] history = new double[300];
        int head = 0, count = 0;

        EnergyPlot() {
            setBackground(PANEL_BG);
            setPreferredSize(new Dimension(300, 70));
        }

        void push(double v) {
            history[head] = v;
            head = (head + 1) % history.length;
            count = Math.min(count + 1, history.length);
            repaint();
        }

        void clear() {
            head = 0;
            count = 0;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (count < 2) return;
            double max = 1e-9;
            for (int i = 0; i < count; i++) max = Math.max(max, history[i]);
            Path2D.Double p = new Path2D.Double();
            for (int i = 0; i < count; i++) {
                int idx = (head - count + i + history.length) % history.length;
                double x = 4 + (w - 8) * i / (double) (history.length - 1);
                double y = h - 4 - (h - 8) * history[idx] / max;
                if (i == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            g.setColor(new Color(255, 190, 90));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(p);
            g.setColor(new Color(150, 154, 168));
            g.setFont(getFont().deriveFont(10f));
            g.drawString("mean speed", 6, 12);
        }
    }

    // ------------------------------------------------------------------------------------
    // Frame and controls
    // ------------------------------------------------------------------------------------

    final Sim sim;
    final WorldView world;
    MatrixEditor matrixEditor;
    ForceCurve forceCurve;
    EnergyPlot energyPlot;
    JLabel statsLabel;
    JButton pauseButton;
    volatile boolean paused = false;
    volatile boolean stepOnce = false;
    volatile int stepsPerFrame = 1;
    volatile boolean running = true;
    JCheckBox autoShuffle;
    long lastShuffle = System.currentTimeMillis();
    int particleCount = 3000;

    // Simple rate counters
    private long fpsWindowStart = System.nanoTime();
    private int framesInWindow = 0;
    private long stepsAtWindowStart = 0;
    private double fps = 0, sps = 0;

    ParticleLifeLab() {
        super("Particle Life Lab");
        sim = new Sim(System.nanoTime());
        sim.applyPreset("Random");
        sim.spawn(particleCount);
        world = new WorldView();
        buildUi();
        startLoops();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(PANEL_BG);
        side.setBorder(new EmptyBorder(8, 8, 8, 8));

        // ---- Run controls
        JPanel run = section("Run");
        run.setLayout(new GridLayout(0, 3, 4, 4));
        pauseButton = button("Pause", e -> togglePause());
        run.add(pauseButton);
        run.add(button("Step", e -> stepOnce = true));
        run.add(button("Respawn", e -> respawn()));
        run.add(button("Centre view", e -> world.resetCamera()));
        run.add(button("Help", e -> showHelp()));
        JCheckBox trails = check("Trails", false, e -> world.trails = ((JCheckBox) e.getSource()).isSelected());
        run.add(trails);
        side.add(run);

        // ---- Matrix
        JPanel mat = section("Interaction matrix  (row feels column)");
        mat.setLayout(new BorderLayout(4, 4));
        matrixEditor = new MatrixEditor();
        forceCurve = new ForceCurve();
        mat.add(matrixEditor, BorderLayout.CENTER);
        mat.add(forceCurve, BorderLayout.SOUTH);
        side.add(mat);

        JPanel matButtons = new JPanel(new GridLayout(0, 3, 4, 4));
        matButtons.setOpaque(false);
        matButtons.setAlignmentX(Component.LEFT_ALIGNMENT);
        matButtons.setBorder(new EmptyBorder(4, 0, 0, 0));
        String[] presets = {"Random", "Random symmetric", "Snakes", "Chains", "Chasers", "Cells", "Segregation", "Gas (all zero)"};
        JComboBox<String> presetBox = new JComboBox<>(presets);
        matButtons.add(presetBox);
        matButtons.add(button("Apply preset", e -> {
            sim.applyPreset((String) presetBox.getSelectedItem());
            matrixChanged();
        }));
        matButtons.add(button("Randomise", e -> {
            sim.applyPreset("Random");
            matrixChanged();
        }));
        matButtons.add(button("Mutate", e -> {
            sim.mutateMatrix(0.15);
            matrixChanged();
        }));
        matButtons.add(button("Symmetrise", e -> {
            sim.symmetrize();
            matrixChanged();
        }));
        matButtons.add(button("Invert", e -> {
            synchronized (sim) {
                for (int i = 0; i < MAX_SPECIES; i++)
                    for (int j = 0; j < MAX_SPECIES; j++) sim.matrix[i][j] = -sim.matrix[i][j];
            }
            matrixChanged();
        }));
        matButtons.add(button("Copy", e -> copyMatrix()));
        matButtons.add(button("Paste", e -> pasteMatrix()));
        autoShuffle = check("Wander", false, e -> lastShuffle = System.currentTimeMillis());
        autoShuffle.setToolTipText("Pick a fresh random matrix every 20 seconds");
        matButtons.add(autoShuffle);
        side.add(matButtons);

        // ---- Parameters
        JPanel params = section("Parameters");
        params.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(1, 2, 1, 2);
        gc.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        JSpinner speciesSpin = new JSpinner(new SpinnerNumberModel(sim.species, 1, MAX_SPECIES, 1));
        speciesSpin.addChangeListener(e -> {
            sim.setSpecies((Integer) speciesSpin.getValue());
            matrixEditor.select(Math.min(matrixEditor.selRow, sim.species - 1), Math.min(matrixEditor.selCol, sim.species - 1));
            matrixChanged();
        });
        addRow(params, gc, row++, "Species", speciesSpin, null);

        addSlider(params, gc, row++, "Particles", 200, 12000, particleCount, v -> String.valueOf(v), v -> {
            particleCount = v;
            sim.spawn(v);
            energyPlot.clear();
        }, true);
        addSlider(params, gc, row++, "Radius rMax", 20, 250, (int) (sim.rMax * 1000),
                v -> String.format(Locale.ROOT, "%.3f", v / 1000.0), v -> sim.rMax = v / 1000.0, false);
        addSlider(params, gc, row++, "Force", 1, 40, (int) sim.forceFactor,
                v -> String.valueOf(v), v -> sim.forceFactor = v, false);
        addSlider(params, gc, row++, "Friction ½-life", 5, 200, (int) (sim.frictionHalfLife * 1000),
                v -> String.format(Locale.ROOT, "%.3f", v / 1000.0), v -> sim.frictionHalfLife = v / 1000.0, false);
        addSlider(params, gc, row++, "Repulsion β", 5, 70, (int) (sim.beta * 100),
                v -> String.format(Locale.ROOT, "%.2f", v / 100.0), v -> {
                    sim.beta = v / 100.0;
                    forceCurve.repaint();
                }, false);
        addSlider(params, gc, row++, "Steps / frame", 1, 8, 1, v -> String.valueOf(v), v -> stepsPerFrame = v, false);
        addSlider(params, gc, row++, "Dot size", 1, 8, world.dotSize, v -> String.valueOf(v), v -> world.dotSize = v, false);

        JCheckBox wrapBox = check("Wrap edges (torus)", true, e -> {
            sim.wrap = ((JCheckBox) e.getSource()).isSelected();
            world.normaliseCamera();
        });
        gc.gridx = 0;
        gc.gridy = row++;
        gc.gridwidth = 3;
        params.add(wrapBox, gc);
        gc.gridwidth = 1;

        JTextField seedField = new JTextField(String.valueOf(sim.seed % 1_000_000), 8);
        JButton seedButton = button("Use seed", e -> {
            try {
                long s = Long.parseLong(seedField.getText().trim());
                sim.setSeed(s);
                sim.applyPreset("Random");
                sim.spawn(particleCount);
                energyPlot.clear();
                matrixChanged();
            } catch (NumberFormatException ex) {
                Toolkit.getDefaultToolkit().beep();
            }
        });
        seedButton.setToolTipText("Reseed: a random matrix and particle layout you can reproduce later");
        addRow(params, gc, row++, "Seed", seedField, seedButton);
        side.add(params);

        // ---- Stats
        JPanel stats = section("Observations");
        stats.setLayout(new BorderLayout(4, 4));
        statsLabel = new JLabel(" ");
        statsLabel.setForeground(PANEL_FG);
        statsLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        energyPlot = new EnergyPlot();
        stats.add(statsLabel, BorderLayout.NORTH);
        stats.add(energyPlot, BorderLayout.CENTER);
        side.add(stats);

        JPanel sideHolder = new ScrollablePanel();
        sideHolder.setBackground(PANEL_BG);
        sideHolder.add(side, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(sideHolder, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(400, 780));

        getContentPane().setBackground(BG);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(world, BorderLayout.CENTER);
        getContentPane().add(scroll, BorderLayout.EAST);

        // Space bar toggles pause wherever focus is.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "pause");
        getRootPane().getActionMap().put("pause", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                togglePause();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    /** A panel that always fits the scroll pane's width, scrolling only vertically. */
    static final class ScrollablePanel extends JPanel implements Scrollable {
        ScrollablePanel() {
            super(new BorderLayout());
        }

        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 32; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private JPanel section(String title) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        TitledBorder tb = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(new Color(55, 60, 76)), title);
        tb.setTitleColor(ACCENT);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(4, 4, 4, 4)));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JButton button(String text, ActionListener l) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(2, 4, 2, 4));
        b.setFocusable(false);
        b.addActionListener(l);
        return b;
    }

    private JCheckBox check(String text, boolean selected, ActionListener l) {
        JCheckBox c = new JCheckBox(text, selected);
        c.setOpaque(false);
        c.setForeground(PANEL_FG);
        c.setFocusable(false);
        c.addActionListener(l);
        return c;
    }

    private void addRow(JPanel p, GridBagConstraints gc, int row, String label, JComponent comp, JComponent extra) {
        JLabel l = new JLabel(label);
        l.setForeground(PANEL_FG);
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0;
        p.add(l, gc);
        gc.gridx = 1;
        gc.weightx = 1;
        p.add(comp, gc);
        if (extra != null) {
            gc.gridx = 2;
            gc.weightx = 0;
            p.add(extra, gc);
        }
    }

    interface IntFormat { String fmt(int v); }
    interface IntAction { void apply(int v); }

    /** A labelled slider; heavy actions fire only when the user lets go of the knob. */
    private void addSlider(JPanel p, GridBagConstraints gc, int row, String label, int min, int max, int value,
                           IntFormat fmt, IntAction action, boolean onRelease) {
        JSlider s = new JSlider(min, max, value);
        s.setOpaque(false);
        s.setFocusable(false);
        JLabel v = new JLabel(fmt.fmt(value));
        v.setForeground(PANEL_FG);
        v.setPreferredSize(new Dimension(48, 16));
        s.addChangeListener(e -> {
            v.setText(fmt.fmt(s.getValue()));
            if (!onRelease || !s.getValueIsAdjusting()) action.apply(s.getValue());
        });
        addRow(p, gc, row, label, s, v);
    }

    private void togglePause() {
        paused = !paused;
        if (pauseButton != null) pauseButton.setText(paused ? "Play" : "Pause");
    }

    private void respawn() {
        sim.spawn(particleCount);
        energyPlot.clear();
    }

    private void matrixChanged() {
        matrixEditor.repaint();
        forceCurve.repaint();
    }

    private void copyMatrix() {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sim.matrixToText()), null);
    }

    private void pasteMatrix() {
        try {
            String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            int k = sim.matrixFromText(text);
            if (k < 0) {
                JOptionPane.showMessageDialog(this,
                        "The clipboard should hold a square matrix of numbers (one row per line, up to 8×8).",
                        "Paste matrix", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (k != sim.species) sim.setSpecies(k);
            matrixEditor.select(0, 0);
            matrixChanged();
        } catch (Exception ex) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private void showHelp() {
        String html = "<html><body style='width:440px; font-family:sans-serif'>"
                + "<h2>Particle Life Lab</h2>"
                + "<p>Each dot belongs to one of up to eight species. Every dot looks at its neighbours within "
                + "<b>rMax</b> and is pushed or pulled by them according to the <b>interaction matrix</b>: "
                + "row <i>i</i>, column <i>j</i> says how strongly species <i>i</i> is attracted (green) "
                + "or repelled (red) by species <i>j</i>.</p>"
                + "<p>Very close up, everything repels (the region below <b>&beta;</b>), so dots never collapse "
                + "onto each other. The force curve under the matrix shows the selected cell's rule.</p>"
                + "<p>The trick is that the matrix needn't be symmetric. If red chases blue but blue flees red, "
                + "momentum is not conserved, and the system can keep moving indefinitely. That's where the "
                + "life-like behaviour comes from: membranes, cells, worms, predators and prey.</p>"
                + "<h3>Things to try</h3><ul>"
                + "<li>Press <b>Randomise</b> a few times. Most matrices make blobs; some make creatures.</li>"
                + "<li>Try the <b>Chasers</b> preset with 3 species, then 6.</li>"
                + "<li>Hit <b>Symmetrise</b> on something lively and watch it freeze into a crystal.</li>"
                + "<li>Lower the friction half-life for calm structures; raise it for hot, turbulent ones.</li>"
                + "<li>Right-drag to stir. Shift + right-drag to gather. Wheel to zoom, left-drag to pan.</li>"
                + "<li>Found a good one? <b>Copy</b> the matrix and keep it as text.</li></ul>"
                + "<p style='color:gray'>After Jeffrey Ventrella's <i>Clusters</i> and Tom Mohr's <i>particle-life</i>.</p>"
                + "</body></html>";
        JOptionPane.showMessageDialog(this, new JLabel(html), "About Particle Life", JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------------------------
    // Loops
    // ------------------------------------------------------------------------------------

    private void startLoops() {
        Thread simThread = new Thread(() -> {
            while (running) {
                long t0 = System.nanoTime();
                if (!paused || stepOnce) {
                    int n = paused ? 1 : stepsPerFrame;
                    for (int i = 0; i < n; i++) sim.step();
                    stepOnce = false;
                }
                if (autoShuffle != null && autoShuffle.isSelected()
                        && System.currentTimeMillis() - lastShuffle > 20_000) {
                    lastShuffle = System.currentTimeMillis();
                    sim.applyPreset("Random");
                    SwingUtilities.invokeLater(this::matrixChanged);
                }
                long elapsed = (System.nanoTime() - t0) / 1_000_000;
                long sleep = 16 - elapsed;
                try {
                    Thread.sleep(Math.max(1, sleep));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "particle-life-sim");
        simThread.setDaemon(true);
        simThread.start();

        new Timer(16, e -> {
            world.repaint();
            framesInWindow++;
            long now = System.nanoTime();
            if (now - fpsWindowStart > 500_000_000L) {
                double secs = (now - fpsWindowStart) / 1e9;
                fps = framesInWindow / secs;
                sps = (sim.steps - stepsAtWindowStart) / secs;
                framesInWindow = 0;
                stepsAtWindowStart = sim.steps;
                fpsWindowStart = now;
                updateStats();
            }
        }).start();
    }

    private void updateStats() {
        State s = sim.state;
        statsLabel.setText(String.format(Locale.ROOT,
                "<html>Particles %d &nbsp; Species %d<br>Steps %d &nbsp; %.0f steps/s &nbsp; %.0f fps<br>"
                        + "Mean speed %.4f<br>Mean neighbours %.1f</html>",
                s.n, sim.species, sim.steps, sps, fps, sim.meanSpeed, sim.meanNeighbours));
        if (!paused) energyPlot.push(sim.meanSpeed);
    }

    // ------------------------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--selftest")) {
            selfTest(args.length > 1 ? args[1] : null);
            return;
        }
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new ParticleLifeLab().setVisible(true);
        });
    }

    /** Runs the simulation without a window and reports what happened. */
    static void selfTest(String pngPath) {
        System.setProperty("java.awt.headless", "true");
        Sim sim = new Sim(42);
        String[] presets = {"Random", "Snakes", "Chains", "Chasers", "Cells", "Segregation", "Random symmetric", "Gas (all zero)"};
        for (String p : presets) {
            sim.setSpecies(6);
            sim.applyPreset(p);
            sim.spawn(3000);
            long t0 = System.nanoTime();
            for (int i = 0; i < 400; i++) sim.step();
            double ms = (System.nanoTime() - t0) / 1e6 / 400;
            State s = sim.state;
            boolean ok = true;
            for (int i = 0; i < s.n; i++)
                if (!(s.x[i] >= 0 && s.x[i] < 1 && s.y[i] >= 0 && s.y[i] < 1) || Float.isNaN(s.vx[i])) ok = false;
            System.out.printf(Locale.ROOT, "%-18s %6.2f ms/step  speed %.4f  neighbours %5.1f  %s%n",
                    p, ms, sim.meanSpeed, sim.meanNeighbours, ok ? "ok" : "OUT OF BOUNDS / NaN");
        }
        sim.wrap = false;
        sim.applyPreset("Chasers");
        sim.spawn(2000);
        for (int i = 0; i < 300; i++) sim.step();
        System.out.printf(Locale.ROOT, "Walls/Chasers      speed %.4f  neighbours %5.1f%n", sim.meanSpeed, sim.meanNeighbours);
        sim.wrap = true;

        if (pngPath != null) {
            try {
                String[] shots = {"Random", "Chasers", "Cells", "Snakes"};
                BufferedImage sheet = new BufferedImage(1200, 1200, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = sheet.createGraphics();
                for (int q = 0; q < 4; q++) {
                    sim.setSeed(7 + q);
                    sim.setSpecies(q == 2 ? 4 : 6);
                    sim.applyPreset(shots[q]);
                    sim.spawn(3000);
                    for (int i = 0; i < 1200; i++) sim.step();
                    BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
                    render(sim, img, 0.5, 0.5, 1.0, 3, false);
                    g.drawImage(img, (q % 2) * 600, (q / 2) * 600, null);
                    g.setColor(Color.WHITE);
                    g.drawString(shots[q], (q % 2) * 600 + 10, (q / 2) * 600 + 20);
                }
                g.dispose();
                javax.imageio.ImageIO.write(sheet, "png", new java.io.File(pngPath));
                System.out.println("Wrote " + pngPath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
