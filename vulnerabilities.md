### **Vulnerabilities and Challenges**

#### **General Issues**:
- **Host Detection**: FloodLight may not detect all hosts in the network, leading to routing issues for undetected hosts. This could cause some `pingall` commands to fail.

---

#### **Part 3: Shortest Path Switching**:
1. **Topology Incompleteness**: Hosts connected to downed switches may result in `null` values for their associated switches, causing unexpected exceptions.
2. **Efficiency Concerns**: Recomputing the entire adjacency table and shortest paths for every topology change is time-consuming. Incremental updates could improve performance.

---

#### **Part 4: Load Balancer**:
- **TCP SYN Exceptions**: Adding rules for handling TCP SYN packets sporadically causes `null` exceptions. This issue might stem from a FloodLight bug but occurs inconsistently.
