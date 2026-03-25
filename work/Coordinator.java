import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Coordinator extends UnicastRemoteObject implements CoordinatorInterface {

    private static final long MAX_REQUEST_AGE_MS = 3000;

    private static class TimestampedRequest {
        final Cloud.FrontEndOps.Request request;
        final long submitTime;
        TimestampedRequest(Cloud.FrontEndOps.Request r) {
            this.request = r;
            this.submitTime = System.currentTimeMillis();
        }
    }

    private final LinkedBlockingQueue<TimestampedRequest> reqQueue;
    private final ConcurrentHashMap<Integer, String> roles;
    private final AtomicInteger readyAppCount;
    private final AtomicInteger totalSubmitted;
    private final Set<Integer> shutdownSet;
    private ServerLib sl;

    public Coordinator() throws RemoteException {
        super();
        this.reqQueue = new LinkedBlockingQueue<>();
        this.roles = new ConcurrentHashMap<>();
        this.readyAppCount = new AtomicInteger(0);
        this.totalSubmitted = new AtomicInteger(0);
        this.shutdownSet = ConcurrentHashMap.newKeySet();
    }

    public void setServerLib(ServerLib sl) {
        this.sl = sl;
    }

    @Override
    public String getRole(int vmId) throws RemoteException {
        String role = roles.get(vmId);
        if ("APP".equals(role)) {
            readyAppCount.incrementAndGet();
        }
        return role;
    }

    @Override
    public void submitRequest(Cloud.FrontEndOps.Request r) throws RemoteException {
        totalSubmitted.incrementAndGet();
        reqQueue.offer(new TimestampedRequest(r));
    }

    @Override
    public Cloud.FrontEndOps.Request getNextRequest() throws RemoteException {
        try {
            while (true) {
                TimestampedRequest tr = reqQueue.poll(50, TimeUnit.MILLISECONDS);
                if (tr == null) return null;
                long age = System.currentTimeMillis() - tr.submitTime;
                if (age > MAX_REQUEST_AGE_MS) {
                    if (sl != null) sl.drop(tr.request);
                    continue;
                }
                return tr.request;
            }
        } catch (InterruptedException e) {
            return null;
        }
    }

    @Override
    public int getQueueSize() throws RemoteException {
        return reqQueue.size();
    }

    @Override
    public boolean shouldShutdown(int vmId) throws RemoteException {
        return shutdownSet.contains(vmId);
    }

    public void assignRole(int vmId, String role) {
        roles.put(vmId, role);
    }

    public int getReadyAppCount() {
        return readyAppCount.get();
    }

    public int getLocalQueueSize() {
        return reqQueue.size();
    }

    public void markForShutdown(int vmId) {
        shutdownSet.add(vmId);
    }

    public void decrementReadyApps() {
        readyAppCount.decrementAndGet();
    }

    public int getTotalSubmitted() {
        return totalSubmitted.get();
    }
}
