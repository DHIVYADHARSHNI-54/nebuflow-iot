package nebuflow;

import java.util.Random;

/** Fake ESP32 so you can test the website before the hardware is connected (run with --demo). */
public class Simulator implements Runnable {
    private final Store store;
    private final Random rnd = new Random();

    public Simulator(Store store) { this.store = store; }

    @Override
    public void run() {
        long start = System.currentTimeMillis();
        long cycleStart = start;
        double peakTarget = 700;
        boolean valveOpen = false;
        while (!Thread.currentThread().isInterrupted()) {
            long now = System.currentTimeMillis();
            long inCycle = now - cycleStart;
            if (inCycle > 6000) {                  // new breath every 6 s
                cycleStart = now; inCycle = 0;
                peakTarget = 150 + rnd.nextInt(900);
            }
            double p;
            if (inCycle < 2000) {                  // 2 s inhalation (sine bump)
                p = peakTarget * Math.sin(Math.PI * inCycle / 2000.0);
            } else {                               // exhale / idle
                p = rnd.nextGaussian() * 4;
            }
            p = Math.max(0, p + rnd.nextGaussian() * 3);

            String mode = store.cmdMode();
            int thr = Store.thresholdFor(mode);
            boolean enabled = store.cmdEnabled();
            if (!enabled) valveOpen = false;
            else if (p > thr) valveOpen = true;
            else if (p < thr * 0.5) valveOpen = false;

            store.telemetry("SIM-ESP32", p, 28.5 + rnd.nextGaussian() * 0.1, valveOpen ? 1 : 0,
                    mode, thr, -55, (now - start) / 1000, 101250 + rnd.nextGaussian() * 2);
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
        }
    }
}
