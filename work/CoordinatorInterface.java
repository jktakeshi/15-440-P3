import java.rmi.Remote;
import java.rmi.RemoteException;

public interface CoordinatorInterface extends Remote {
	String getRole(int vmId) throws RemoteException;
	void submitRequest(Cloud.FrontEndOps.Request r) throws RemoteException;
	Cloud.FrontEndOps.Request getNextRequest() throws RemoteException;
	int getQueueSize() throws RemoteException;
	boolean shouldShutdown(int vmId) throws RemoteException;

	void registerAppReady(int vmId) throws RemoteException;
	void registerAppStopped(int vmId) throws RemoteException;
	void registerFeReady(int vmId) throws RemoteException;
	void registerFeStopped(int vmId) throws RemoteException;
	void reportFeQueue(int vmId, int queueLen) throws RemoteException;

	int getBootingAppCount() throws RemoteException;
	int getRunningAppCount() throws RemoteException;
	int getBootingFeCount() throws RemoteException;
	int getRunningFeCount() throws RemoteException;
	int getTotalFeReportedQueue() throws RemoteException;
	long getOldestRequestAgeMs() throws RemoteException;
	int getTotalSubmitted() throws RemoteException;
}
