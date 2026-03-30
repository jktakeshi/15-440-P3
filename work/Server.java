import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Server {

    public static void main(String args[]) throws Exception {
        if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");

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

            for (int i = 0; i < 2; i++) {
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
                SL.register_frontend();
                while (true) {
                    ServerLib.Handle h = SL.acceptConnection();
                    Cloud.FrontEndOps.Request r = SL.parseRequest(h);
                    coordinator.submitRequest(r);
                }
            } else if ("APP".equals(role)) {
                try {
                    while (true) {
                        if (coordinator.shouldShutdown(myVMid)) {
                            break;
                        }
                        Cloud.FrontEndOps.Request r = coordinator.getNextRequest();
                        if (r != null) {
                            SL.processRequest(r);
                        }
                    }
                } catch (java.rmi.RemoteException e) {
                }
                SL.shutDown();
            }
        }
    }

    private static void scaleLoop(ServerLib SL, Coordinator coordinator,
            List<Integer> appVMs, List<Integer> feVMs) throws Exception {
        int minApps = 1;
        int maxApps = 6;
        long lastScaleUp = 0;
        long lastFeScaleUp = 0;
        int prevSubmitted = 0;
        int consecutiveLowUtil = 0;
        boolean addedExtraFE = false;

        while (coordinator.getReadyAppCount() < 1) {
            Thread.sleep(200);
        }

        while (true) {
            Thread.sleep(500);

            int appQueueSize = coordinator.getLocalQueueSize();
            int feQueueLength = SL.getQueueLength();
            int readyApps = coordinator.getReadyAppCount();
            long now = System.currentTimeMillis();

            int nowSubmitted = coordinator.getTotalSubmitted();
            int delta = nowSubmitted - prevSubmitted;
            prevSubmitted = nowSubmitted;
            double ratePerSecond = delta * 2.0;

            int totalPending = appQueueSize + feQueueLength;

            if (!addedExtraFE && feQueueLength > 2 && (now - lastFeScaleUp > 2000)) {
                int feId = SL.startVM();
                coordinator.assignRole(feId, "FRONTEND");
                feVMs.add(feId);
                addedExtraFE = true;
                lastFeScaleUp = now;
            }

            if (totalPending > 2 && appVMs.size() < maxApps && (now - lastScaleUp > 500)) {
                int targetApps = appVMs.size() + 1;
                if (ratePerSecond > 0) {
                    targetApps = Math.max(targetApps, (int)(ratePerSecond / 3.0) + 1);
                }
                targetApps = Math.min(targetApps, maxApps);

                int appsToAdd = Math.max(1, targetApps - appVMs.size());
                appsToAdd = Math.min(appsToAdd, 3);

                for (int i = 0; i < appsToAdd; i++) {
                    int newId = SL.startVM();
                    coordinator.assignRole(newId, "APP");
                    appVMs.add(newId);
                }
                lastScaleUp = now;
                consecutiveLowUtil = 0;
            } else if (nowSubmitted > 0 && appQueueSize == 0 && feQueueLength == 0
                    && appVMs.size() > minApps && readyApps > minApps) {
                double capacity = readyApps * 3.0;
                if (capacity > 0 && ratePerSecond < capacity * 0.5) {
                    consecutiveLowUtil++;
                    if (consecutiveLowUtil > 4) {
                        int numToRemove = Math.min(2, appVMs.size() - minApps);
                        for (int i = 0; i < numToRemove; i++) {
                            int vmToRemove = appVMs.remove(appVMs.size() - 1);
                            coordinator.markForShutdown(vmToRemove);
                            coordinator.decrementReadyApps();
                            SL.endVM(vmToRemove);
                        }
                        consecutiveLowUtil = 0;
                    }
                } else if (capacity > 0 && ratePerSecond < capacity * 0.7) {
                    consecutiveLowUtil++;
                    if (consecutiveLowUtil > 8) {
                        int vmToRemove = appVMs.remove(appVMs.size() - 1);
                        coordinator.markForShutdown(vmToRemove);
                        coordinator.decrementReadyApps();
                        SL.endVM(vmToRemove);
                        consecutiveLowUtil = 0;
                    }
                } else {
                    consecutiveLowUtil = 0;
                }
            } else {
                consecutiveLowUtil = 0;
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
