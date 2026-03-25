import java.rmi.Remote;
import java.rmi.RemoteException;

public interface CoordinatorInterface extends Remote {
    String getRole(int vmId) throws RemoteException;
    void submitRequest(Cloud.FrontEndOps.Request r) throws RemoteException;
    Cloud.FrontEndOps.Request getNextRequest() throws RemoteException;
    int getQueueSize() throws RemoteException;
    boolean shouldShutdown(int vmId) throws RemoteException;
}
