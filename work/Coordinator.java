import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator: central APP queue, per-role assignment, explicit VM lifecycle
 * (booting vs running for APP and extra FE), FE queue aggregation, shutdown flags.
 */
public class Coordinator extends UnicastRemoteObject implements CoordinatorInterface {

	private static final long MAX_REQUEST_AGE_MS = 3000;
	private static final long FE_REPORT_STALE_MS = 5000;

	private static class TimestampedRequest {
		final Cloud.FrontEndOps.Request request;
		final long submitTime;
		TimestampedRequest(Cloud.FrontEndOps.Request r) {
			this.request = r;
			this.submitTime = System.currentTimeMillis();
		}
	}

	private static class FeReport {
		final int len;
		final long timeMs;
		FeReport(int len, long timeMs) {
			this.len = len;
			this.timeMs = timeMs;
		}
	}

	private final LinkedBlockingQueue<TimestampedRequest> reqQueue;
	private final ConcurrentHashMap<Integer, String> roles;
	private final AtomicInteger totalSubmitted;
	private final Set<Integer> shutdownSet;

	/** APP: assigned via assignRole("APP") until registerAppReady. */
	private final Set<Integer> bootingAppIds;
	private final Set<Integer> runningAppVmIds;

	/** Extra FE only (VM 1 is not tracked here). */
	private final Set<Integer> bootingFeIds;
	private final Set<Integer> runningFeVmIds;

	private final ConcurrentHashMap<Integer, FeReport> feQueueReports;
	private ServerLib sl;

	public Coordinator() throws RemoteException {
		super();
		this.reqQueue = new LinkedBlockingQueue<>();
		this.roles = new ConcurrentHashMap<>();
		this.totalSubmitted = new AtomicInteger(0);
		this.shutdownSet = ConcurrentHashMap.newKeySet();
		this.bootingAppIds = ConcurrentHashMap.newKeySet();
		this.runningAppVmIds = ConcurrentHashMap.newKeySet();
		this.bootingFeIds = ConcurrentHashMap.newKeySet();
		this.runningFeVmIds = ConcurrentHashMap.newKeySet();
		this.feQueueReports = new ConcurrentHashMap<>();
	}

	public void setServerLib(ServerLib sl) {
		this.sl = sl;
	}

	/**
	 * Called on VM 1 when scaler (or bootstrap) creates a worker. Updates roles
	 * and marks APP or FRONTEND as booting until register*Ready from that VM.
	 */
	public void assignRole(int vmId, String role) {
		roles.put(vmId, role);
		if ("APP".equals(role)) {
			bootingAppIds.add(vmId);
		} else if ("FRONTEND".equals(role)) {
			bootingFeIds.add(vmId);
		}
	}

	@Override
	public String getRole(int vmId) throws RemoteException {
		return roles.get(vmId);
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
				if (tr == null) {
					return null;
				}
				long age = System.currentTimeMillis() - tr.submitTime;
				if (age > MAX_REQUEST_AGE_MS) {
					if (sl != null) {
						sl.drop(tr.request);
					}
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

	@Override
	public void registerAppReady(int vmId) throws RemoteException {
		bootingAppIds.remove(vmId);
		runningAppVmIds.add(vmId);
	}

	@Override
	public void registerAppStopped(int vmId) throws RemoteException {
		runningAppVmIds.remove(vmId);
		bootingAppIds.remove(vmId);
		shutdownSet.remove(vmId);
	}

	@Override
	public void registerFeReady(int vmId) throws RemoteException {
		bootingFeIds.remove(vmId);
		runningFeVmIds.add(vmId);
	}

	@Override
	public void registerFeStopped(int vmId) throws RemoteException {
		runningFeVmIds.remove(vmId);
		bootingFeIds.remove(vmId);
		shutdownSet.remove(vmId);
		feQueueReports.remove(vmId);
	}

	@Override
	public void reportFeQueue(int vmId, int queueLen) throws RemoteException {
		feQueueReports.put(vmId, new FeReport(queueLen, System.currentTimeMillis()));
	}

	@Override
	public int getBootingAppCount() throws RemoteException {
		return bootingAppIds.size();
	}

	@Override
	public int getRunningAppCount() throws RemoteException {
		return runningAppVmIds.size();
	}

	@Override
	public int getBootingFeCount() throws RemoteException {
		return bootingFeIds.size();
	}

	@Override
	public int getRunningFeCount() throws RemoteException {
		return runningFeVmIds.size();
	}

	@Override
	public int getTotalFeReportedQueue() throws RemoteException {
		long now = System.currentTimeMillis();
		int sum = 0;
		for (FeReport fr : feQueueReports.values()) {
			if (now - fr.timeMs < FE_REPORT_STALE_MS) {
				sum += fr.len;
			}
		}
		return sum;
	}

	@Override
	public long getOldestRequestAgeMs() throws RemoteException {
		TimestampedRequest head = reqQueue.peek();
		if (head == null) {
			return 0;
		}
		return System.currentTimeMillis() - head.submitTime;
	}

	@Override
	public int getTotalSubmitted() throws RemoteException {
		return totalSubmitted.get();
	}

	public int getLocalQueueSize() {
		return reqQueue.size();
	}

	public void markForShutdown(int vmId) {
		shutdownSet.add(vmId);
	}
}
