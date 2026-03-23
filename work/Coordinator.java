import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Coordinator extends UnicastRemoteObject implements CoordinatorInterface {

    // queue holds requests in between FE and APP
    private final LinkedBlockingQueue<Cloud.FrontEndOps.Request> reqQueue;
    private final ConcurrentHashMap<Integer, String> roles;
    private final AtomicBoolean hasReadyApp;

    public Coordinator() throws RemoteException {
        super();
        this.reqQueue = new LinkedBlockingQueue<>();
        this.roles = new ConcurrentHashMap<>();
        this.hasReadyApp = new AtomicBoolean(false);
    }

    @Override
    public String getRole(int vmId) throws RemoteException {
        String role = roles.get(vmId);
        if ("APP".equals(role)) {
            hasReadyApp.set(true);
        }
        return role;
    }

    @Override
    public void submitRequest(Cloud.FrontEndOps.Request r) throws RemoteException {
        reqQueue.offer(r);
    }

    @Override
    public Cloud.FrontEndOps.Request getNextRequest() throws RemoteException {
        try {
            return reqQueue.take();
        }
        catch (InterruptedException e) {
            return null;
        }
    }
    

    public void assignRole(int vmId, String role) {
        roles.put(vmId, role);
    }

    public boolean hasReadyApp() {
        return hasReadyApp.get();
    }
}