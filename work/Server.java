import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Server {

	private static final int TICK_MS = 500;
	private static final double RATE_SMOOTH_ALPHA = 0.35;
	/** FE A/B: was 2500; faster FE scale-up vs APP 1500ms (test FE-lag on heavy trace). */
	private static final long FE_SCALE_UP_COOLDOWN_MS = 1800;
	private static final long FE_SCALE_DOWN_COOLDOWN_MS = 4500;
	private static final long APP_SCALE_UP_COOLDOWN_MS = 1500;
	private static final long APP_SCALE_DOWN_COOLDOWN_MS = 5000;

	/** FE A/B: was 4; +1 extra FE (baseline ~median 164 timeouts @ ~901k VM on c-150-123). */
	private static final int MAX_EXTRA_FE = 5;
	private static final int APP_QUEUE_SCALE_UP = 3;
	private static final long APP_OLDEST_AGE_SCALE_UP_MS = 900;
	/** Scale FE when aggregate reported FE queue reaches this (absolute; no APP comparison gate). */
	private static final int FE_TOTAL_QUEUE_SCALE_UP = 4;

	private static final int CONSECUTIVE_FE_LOW_FOR_SCALE_DOWN = 10;
	private static final int CONSECUTIVE_APP_LOW_FOR_SCALE_DOWN = 9;

	/**
	 * Expected APP throughput for scale-up target and starvation check
	 * (ceil(smoothedRate / this); starve when rate exceeds committedApps * this).
	 */
	private static final double APP_REQ_PER_SEC_SCALE_UP_TARGET = 2.8;

	/**
	 * Assumed service capacity per running APP for scale-down hysteresis only.
	 * Kept separate from {@link #APP_REQ_PER_SEC_SCALE_UP_TARGET}; do not lower casually
	 * or scale-down becomes more aggressive.
	 */
	private static final double APP_PROC_CAPACITY_FOR_SCALE_DOWN = 3.0;

	private static final long FE_REPORT_INTERVAL_MS = 400;

	/** From scaler start: short-window burst scale-up for cold-start / Black Friday-style spikes. */
	private static final long BURST_BOOTSTRAP_MS = 4000;
	private static final long BURST_FE_SCALE_UP_COOLDOWN_MS = 1000;
	private static final long BURST_APP_SCALE_UP_COOLDOWN_MS = 900;
	private static final int BURST_APP_BATCH_CAP = 2;
	/** Extra FE ceiling during burst (vs {@link #MAX_EXTRA_FE} steady-state). */
	private static final int BURST_MAX_EXTRA_FE_CAP = 5;

	/** Set false for one A/B run to test whether scale-down causes timeout cascades. */
	private static final boolean ENABLE_SCALE_DOWN = true;

	/** Recitation 10: fine for local runs; set false before submission if perf matters. */
	private static final boolean SCALER_DEBUG_LOG = false;

	public static void main(String args[]) throws Exception {
		if (args.length != 3) {
			throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		}

		String cloudIp = args[0];
		int cloudPort = Integer.parseInt(args[1]);
		ServerLib SL = new ServerLib(cloudIp, cloudPort);
		int myVMid = Integer.parseInt(args[2]);

		Registry registry = LocateRegistry.getRegistry(cloudIp, cloudPort);

		if (myVMid == 1) {
			Coordinator coordinator = new Coordinator();
			coordinator.setServerLib(SL);
			registry.rebind("Coordinator", coordinator);
			SL.register_frontend();

			List<Integer> appVMs = Collections.synchronizedList(new ArrayList<>());
			List<Integer> feVMs = Collections.synchronizedList(new ArrayList<>());

			for (int i = 0; i < 3; i++) {
				int id = SL.startVM();
				coordinator.assignRole(id, "APP");
				appVMs.add(id);
			}

			Thread scaler = new Thread(() -> {
				try {
					scaleLoop(SL, coordinator, appVMs, feVMs);
				} catch (Exception e) {
				}
			});
			scaler.setDaemon(true);
			scaler.start();

			while (true) {
				ServerLib.Handle h = SL.acceptConnection();
				Cloud.FrontEndOps.Request r = SL.parseRequest(h);
				coordinator.submitRequest(r);
			}
		} else {
			CoordinatorInterface coordinator = lookupCoordinator(registry);
			String role = coordinator.getRole(myVMid);

			if ("FRONTEND".equals(role)) {
				runFrontendWorker(SL, coordinator, myVMid);
			} else if ("APP".equals(role)) {
				runAppWorker(SL, coordinator, myVMid);
			} else {
				SL.shutDown();
			}
		}
	}

	/**
	 * Extra frontend: register, report queue periodically, accept/parse/submit until
	 * shutdown; then unregisterFrontend and drain acceptConnection until null (handout).
	 */
	private static void runFrontendWorker(ServerLib SL, CoordinatorInterface coord, int myVMid) {
		SL.register_frontend();
		try {
			coord.registerFeReady(myVMid);
		} catch (java.rmi.RemoteException e) {
			SL.shutDown();
			return;
		}

		Thread reporter = new Thread(() -> {
			try {
				while (!Thread.interrupted()) {
					Thread.sleep(FE_REPORT_INTERVAL_MS);
					try {
						coord.reportFeQueue(myVMid, SL.getQueueLength());
					} catch (java.rmi.RemoteException e) {
						break;
					}
				}
			} catch (InterruptedException e) {
			}
		});
		reporter.setDaemon(true);
		reporter.start();

		try {
			while (true) {
				boolean shutdown = false;
				try {
					shutdown = coord.shouldShutdown(myVMid);
				} catch (java.rmi.RemoteException e) {
					break;
				}

				if (shutdown) {
					SL.unregisterFrontend();
					reporter.interrupt();
					try {
						reporter.join(2000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					ServerLib.Handle h;
					while ((h = SL.acceptConnection()) != null) {
						Cloud.FrontEndOps.Request r = SL.parseRequest(h);
						try {
							coord.submitRequest(r);
						} catch (java.rmi.RemoteException e) {
							break;
						}
					}
					break;
				}

				ServerLib.Handle h = SL.acceptConnection();
				Cloud.FrontEndOps.Request r = SL.parseRequest(h);
				try {
					coord.submitRequest(r);
				} catch (java.rmi.RemoteException e) {
					break;
				}
			}
		} finally {
			reporter.interrupt();
			try {
				coord.registerFeStopped(myVMid);
			} catch (java.rmi.RemoteException e) {
			}
			SL.shutDown();
		}
	}

	/**
	 * APP: announce ready, then process requests; when idle and shutdown flagged, exit.
	 * registerAppStopped keeps coordinator counts consistent with graceful scale-down.
	 */
	private static void runAppWorker(ServerLib SL, CoordinatorInterface coord, int myVMid) {
		try {
			coord.registerAppReady(myVMid);
		} catch (java.rmi.RemoteException e) {
			SL.shutDown();
			return;
		}
		try {
			while (true) {
				Cloud.FrontEndOps.Request r = coord.getNextRequest();
				if (r != null) {
					SL.processRequest(r);
					continue;
				}
				if (coord.shouldShutdown(myVMid)) {
					break;
				}
			}
		} catch (java.rmi.RemoteException e) {
		} finally {
			try {
				coord.registerAppStopped(myVMid);
			} catch (java.rmi.RemoteException e) {
			}
			SL.shutDown();
		}
	}

	private static void scaleLoop(ServerLib SL, Coordinator coordinator,
			List<Integer> appVMs, List<Integer> feVMs) throws Exception {
		final int minApps = 1;
		final int maxApps = 10;
		long lastAppScaleUp = 0;
		long lastAppScaleDown = 0;
		long lastFeScaleUp = 0;
		long lastFeScaleDown = 0;
		int prevSubmitted = 0;
		double smoothedRate = 0;
		boolean rateInitialized = false;
		int consecutiveFeLow = 0;
		int consecutiveAppLowRaw = 0;
		int consecutiveAppLowStricter = 0;

		final long scaleLoopStart = System.currentTimeMillis();

		while (true) {
			coordinator.reportFeQueue(1, SL.getQueueLength());
			Thread.sleep(TICK_MS);

			long now = System.currentTimeMillis();
			boolean inBurstBootstrap = (now - scaleLoopStart) < BURST_BOOTSTRAP_MS;
			long appUpCooldown = inBurstBootstrap ? BURST_APP_SCALE_UP_COOLDOWN_MS : APP_SCALE_UP_COOLDOWN_MS;
			long feUpCooldown = inBurstBootstrap ? BURST_FE_SCALE_UP_COOLDOWN_MS : FE_SCALE_UP_COOLDOWN_MS;
			int extraFeCap = inBurstBootstrap ? Math.max(MAX_EXTRA_FE, BURST_MAX_EXTRA_FE_CAP) : MAX_EXTRA_FE;
			int appBatchCap = inBurstBootstrap ? BURST_APP_BATCH_CAP : 2;

			int appQueueSize = coordinator.getLocalQueueSize();
			long oldestAgeMs = coordinator.getOldestRequestAgeMs();
			int totalFeQueue = coordinator.getTotalFeReportedQueue();
			int runningApps = coordinator.getRunningAppCount();
			int bootingApps = coordinator.getBootingAppCount();
			int committedApps = runningApps + bootingApps;
			int runningExtraFe = coordinator.getRunningFeCount();
			int bootingFe = coordinator.getBootingFeCount();
			int committedExtraFe = runningExtraFe + bootingFe;

			int nowSubmitted = coordinator.getTotalSubmitted();
			int delta = nowSubmitted - prevSubmitted;
			prevSubmitted = nowSubmitted;
			double instantRatePerSec = delta * (1000.0 / TICK_MS);
			if (!rateInitialized) {
				smoothedRate = instantRatePerSec;
				rateInitialized = true;
			} else {
				smoothedRate = (1.0 - RATE_SMOOTH_ALPHA) * smoothedRate
						+ RATE_SMOOTH_ALPHA * instantRatePerSec;
			}

			boolean feIsBottleneck = totalFeQueue >= FE_TOTAL_QUEUE_SCALE_UP;
			boolean appsReady = runningApps > 0;
			boolean appNeedsCapacity = appsReady && (appQueueSize >= APP_QUEUE_SCALE_UP
					|| oldestAgeMs >= APP_OLDEST_AGE_SCALE_UP_MS);
			boolean appStarvedByRate = committedApps > 0 && appQueueSize > 0
					&& smoothedRate > committedApps * APP_REQ_PER_SEC_SCALE_UP_TARGET;
			boolean needMoreApp = (appNeedsCapacity || appStarvedByRate)
					&& committedApps < maxApps
					&& (now - lastAppScaleUp > appUpCooldown);

			boolean didScaleUp = false;

			if (needMoreApp) {
				int targetApps = Math.min(maxApps,
						Math.max(committedApps + 1,
								(int) Math.ceil(smoothedRate / APP_REQ_PER_SEC_SCALE_UP_TARGET)));
				int want = targetApps - committedApps;
				want = Math.max(1, Math.min(appBatchCap, want));
				want = Math.min(want, maxApps - committedApps);
				for (int i = 0; i < want; i++) {
					int newId = SL.startVM();
					coordinator.assignRole(newId, "APP");
					synchronized (appVMs) {
						appVMs.add(newId);
					}
				}
				lastAppScaleUp = now;
				consecutiveFeLow = 0;
				consecutiveAppLowRaw = 0;
				consecutiveAppLowStricter = 0;
				didScaleUp = true;
			}

			if (feIsBottleneck && committedExtraFe < extraFeCap
					&& (now - lastFeScaleUp > feUpCooldown)) {
				int feId = SL.startVM();
				coordinator.assignRole(feId, "FRONTEND");
				synchronized (feVMs) {
					feVMs.add(feId);
				}
				lastFeScaleUp = now;
				consecutiveFeLow = 0;
				didScaleUp = true;
			}

			if (!didScaleUp && ENABLE_SCALE_DOWN) {
				boolean appQuiet = appQueueSize == 0 && oldestAgeMs < 150;
				boolean feQuiet = totalFeQueue <= 1;
				double procCapacity = runningApps * APP_PROC_CAPACITY_FOR_SCALE_DOWN;

				if (appQuiet && feQuiet && nowSubmitted > 0 && procCapacity > 0) {
					if (smoothedRate < procCapacity * 0.5) {
						consecutiveAppLowRaw++;
					} else {
						consecutiveAppLowRaw = 0;
					}
					if (smoothedRate < procCapacity * 0.38) {
						consecutiveAppLowStricter++;
					} else {
						consecutiveAppLowStricter = 0;
					}
					if (totalFeQueue <= 1) {
						consecutiveFeLow++;
					} else {
						consecutiveFeLow = 0;
					}
				} else {
					consecutiveAppLowRaw = 0;
					consecutiveAppLowStricter = 0;
					consecutiveFeLow = 0;
				}

				boolean canScaleDownApp = runningApps > minApps && bootingApps == 0
						&& appQuiet && feQuiet
						&& (now - lastAppScaleDown > APP_SCALE_DOWN_COOLDOWN_MS);
				if (canScaleDownApp) {
					if (consecutiveAppLowStricter >= CONSECUTIVE_APP_LOW_FOR_SCALE_DOWN) {
						synchronized (appVMs) {
							if (appVMs.size() > minApps) {
								int vmToRemove = appVMs.remove(appVMs.size() - 1);
								coordinator.markForShutdown(vmToRemove);
								lastAppScaleDown = now;
								consecutiveAppLowRaw = 0;
								consecutiveAppLowStricter = 0;
								if (SCALER_DEBUG_LOG) {
									System.err.println("scaler: APP scale-down 1 vm=" + vmToRemove);
								}
							}
						}
					} else if (consecutiveAppLowRaw >= CONSECUTIVE_APP_LOW_FOR_SCALE_DOWN + 4) {
						synchronized (appVMs) {
							if (appVMs.size() > minApps + 1) {
								int n = Math.min(2, appVMs.size() - minApps);
								for (int k = 0; k < n; k++) {
									int vmToRemove = appVMs.remove(appVMs.size() - 1);
									coordinator.markForShutdown(vmToRemove);
								}
								lastAppScaleDown = now;
								consecutiveAppLowRaw = 0;
								consecutiveAppLowStricter = 0;
								if (SCALER_DEBUG_LOG) {
									System.err.println("scaler: APP scale-down n=" + n);
								}
							}
						}
					}
				}

				boolean canScaleDownFe = appQuiet && totalFeQueue <= 1 && !feVMs.isEmpty()
						&& (now - lastFeScaleDown > FE_SCALE_DOWN_COOLDOWN_MS)
						&& consecutiveFeLow >= CONSECUTIVE_FE_LOW_FOR_SCALE_DOWN;
				if (canScaleDownFe) {
					synchronized (feVMs) {
						if (!feVMs.isEmpty()) {
							int feRemove = feVMs.remove(feVMs.size() - 1);
							coordinator.markForShutdown(feRemove);
							lastFeScaleDown = now;
							consecutiveFeLow = 0;
							if (SCALER_DEBUG_LOG) {
								System.err.println("scaler: FE scale-down vm=" + feRemove);
							}
						}
					}
				}
			} else if (!didScaleUp) {
				consecutiveAppLowRaw = 0;
				consecutiveAppLowStricter = 0;
				consecutiveFeLow = 0;
			}

			if (SCALER_DEBUG_LOG) {
				System.err.printf(
						"scaler appQ=%d oldest=%d feQ=%d rate=%.1f rApp=%d bApp=%d rFe=%d bFe=%d up=%b%n",
						appQueueSize, oldestAgeMs, totalFeQueue, smoothedRate,
						runningApps, bootingApps, runningExtraFe, bootingFe, didScaleUp);
			}
		}
	}

	private static CoordinatorInterface lookupCoordinator(Registry registry) throws Exception {
		while (true) {
			try {
				return (CoordinatorInterface) registry.lookup("Coordinator");
			} catch (Exception e) {
				Thread.sleep(50);
			}
		}
	}
}
