### **Part 3 Testing Instructions**:
1. **Start the Floodlight Controller**:  
   Run the following command to initialize the Floodlight Controller with Shortest Path Switching enabled:  
   `java -jar FloodlightWithApps.jar -cf spsConfig.properties`  
   This ensures that the controller is configured to handle shortest path switching without interference from other modules.

2. **Launch Mininet with Specific Topology**:  
   Execute the command:  
   `sudo python3 start_mininet.py --topo=<topo_type>`  
   Replace `<topo_type>` with one of the supported values: single, tree, ring, linear, or custom.  
   This step sets up the network emulation environment with the desired topology.

3. **Validate Network Connectivity**:  
   Use the Mininet CLI and enter the command:  
   `pingall`  
   This command tests whether all nodes in the topology can reach each other. If every host responds without packet loss, both the topology and forwarding rules are functioning as expected.

4. **Dynamic Topology Change Test**:  
   To simulate a topology update, execute:  
   `link <device1> <device2> down`  
   Then use `pingall` again to verify that the network successfully recalculates paths and maintains connectivity. Repeat this process by bringing the link back up using:  
   `link <device1> <device2> up`.

---

### **Part 4 Testing Instructions**:
1. **Start the Floodlight Controller with Load Balancer**:  
   Launch the Floodlight Controller configured with both the load balancer and shortest path switching modules:  
   `java -jar FloodlightWithApps.jar -cf lbConfig.properties`  

2. **Basic Connectivity Test**:  
   Follow the same steps as Part 3 to ensure that all nodes can communicate. This verifies that the default behavior for forwarding (via the shortest path table) is intact.

3. **Validate Load Balancer Functionality**:  
   Use any Mininet host to initiate an HTTP request to the virtual IP address. For example:  
   `h1 curl http://<virtualIP>`  
   Replace `<virtualIP>` with the virtual IP specified in the `lbConfig.properties` file.  
   If the response is successfully returned, it confirms the ARP and TCP handling by the load balancer is operational.

4. **Extended Testing with Traffic Simulation**:  
   Create continuous traffic to the virtual IP using multiple hosts:  
   ```
   h1 curl -s http://<virtualIP> &  
   h2 curl -s http://<virtualIP> &  
   h3 curl -s http://<virtualIP> &
   ```
   Monitor the load balancer’s behavior to confirm whether traffic is evenly distributed among backend servers.

5. **Edge Case Tests**:  
   - Attempt to access a non-existent virtual IP and ensure the load balancer rejects it gracefully.  
   - Simulate a server failure by disconnecting one backend server (`link <serverSwitch> <serverPort> down`) and confirm traffic is redirected to remaining servers.
