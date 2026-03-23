import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class Server {

	// private static int hourToServers(int hour) {
	// 	if (hour < 1) return 3;
	// 	if (hour < 5) return 2;   
	// 	if (hour < 8) return 3;   
	// 	if (hour < 15) return 5;     
	// 	if (hour < 18) return 4; 
	// 	if (hour < 19) return 5;
	// 	if (hour < 20) return 6;
	// 	if (hour < 22) return 7;
	// 	if (hour < 23) return 5;  
    // 	return 4;  
	// }



	public static void main ( String args[] ) throws Exception {
		// Cloud class will start one instance of this Server intially [runs as separate process]
		// It starts another for every startVM call [each a seperate process]
		// Server will be provided 3 command line arguments
		if (args.length != 3) throw new Exception("Need 3 args: <cloud_ip> <cloud_port> <VM id>");
		
		// Initialize ServerLib.  Almost all server and cloud operations are done 
		// through the methods of this class.  Please refer to the html documentation in ../doc
		String cloudIp = args[0];
		int cloudPort = Integer.parseInt(args[1]);
		ServerLib SL = new ServerLib(cloudIp, cloudPort);
		int myVMid = Integer.parseInt(args[2]);

		Registry registry = LocateRegistry.getRegistry(cloudIp, cloudPort);
		
		// register with load balancer so client connections are sent to this server
		// SL.register_frontend();

		if (myVMid == 1) {
			Coordinator coordinator = new Coordinator();
			registry.rebind("Coordinator", coordinator);

			SL.register_frontend();

			// int appId1 = SL.startVM();
            // coordinator.assignRole(appId1, "FRONTEND");

            int appId2 = SL.startVM();
            coordinator.assignRole(appId2, "APP");

			int appId3 = SL.startVM();
            coordinator.assignRole(appId3, "APP");

			int appId4 = SL.startVM();
            coordinator.assignRole(appId4, "FRONTEND");

			// int appId5 = SL.startVM();
            // coordinator.assignRole(appId5, "APP");

			while (true) {
				// wait for and accept next client connection, returns a connection handle
				ServerLib.Handle h = SL.acceptConnection();
				// read and parse request from client connection at the given handle
				Cloud.FrontEndOps.Request r = SL.parseRequest( h );

				if (coordinator.hasReadyApp()) {
					coordinator.submitRequest(r);
				}
				else {
					SL.processRequest( r );
				}
			}
		}
		else {
			CoordinatorInterface coordinator = lookupCoordinator(registry);
			String role = coordinator.getRole(myVMid);

			if ("FRONTEND".equals(role)) {
				SL.register_frontend();
				
				while (true) {
                    ServerLib.Handle h = SL.acceptConnection();
                    Cloud.FrontEndOps.Request r = SL.parseRequest(h);
                    coordinator.submitRequest(r);
                }
			}
			else if ("APP".equals(role)) {
				while (true) {
                    Cloud.FrontEndOps.Request r = coordinator.getNextRequest();
                    if (r != null) {
                        SL.processRequest(r);
                    }
				}
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

