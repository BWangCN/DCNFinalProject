
 * 通过拓扑信息构建邻接表，并使用 Dijkstra 算法计算最短路径。
 */
public class RoutingCalculator {

    // 用于记录日志的 Logger 对象
    private static final Logger LOGGER = LoggerFactory.getLogger(RoutingCalculator.class);

    // 邻接表，存储每个节点及其相邻节点的链接信息
    private final Map<Long, List<LinkInfo>> adjacencyList;
    // 最短路径表，存储每个节点到其他节点的最短路径信息
    private final Map<Long, Map<Long, PathInfo>> shortestPaths;

    /**
     * 构造函数，初始化邻接表和最短路径表。
     */
    public RoutingCalculator() {
        this.adjacencyList = new HashMap<>();
        this.shortestPaths = new HashMap<>();
    }

    /**
     * 根据链接集合构建网络拓扑。
     * @param links 链接集合，描述网络中的所有连接
     */
    public void buildTopology(Collection<Link> links) {
        adjacencyList.clear(); // 清空之前的拓扑数据

        // 遍历所有链接，更新邻接表
        for (Link link : links) {
            adjacencyList.computeIfAbsent(link.getSource(), k -> new ArrayList<>())
                    .add(new LinkInfo(link.getDestination(), link.getSourcePort()));
        }

        // 打印日志，说明拓扑更新完成
        LOGGER.info("Topology updated with {} links.", links.size());
    }

    /**
     * 使用 Dijkstra 算法计算每个节点的最短路径。
     */
    public void computeShortestPaths() {
        shortestPaths.clear(); // 清空之前的最短路径数据

        // 遍历邻接表中的每个节点作为源节点
        for (long source : adjacencyList.keySet()) {
            Map<Long, PathInfo> paths = calculatePathsFromSource(source);
            shortestPaths.put(source, paths); // 将源节点的最短路径信息存储到 shortestPaths 中
        }

        // 打印日志，说明最短路径计算完成
        LOGGER.info("Shortest paths recomputed for {} nodes.", adjacencyList.size());
    }

    /**
     * 从给定的源节点计算到所有其他节点的最短路径。
     * 使用优先队列实现 Dijkstra 算法。
     * @param source 源节点
     * @return 从源节点到每个节点的最短路径信息
     */
    private Map<Long, PathInfo> calculatePathsFromSource(long source) {
        // 用于存储到每个节点的最短距离
        Map<Long, Integer> distances = new HashMap<>();
        // 用于存储到每个节点的路径信息
        Map<Long, PathInfo> paths = new HashMap<>();
        // 优先队列，用于按距离排序未处理的节点
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(Comparator.comparingInt(NodeDistance::getDistance));

        // 初始化距离表和路径表
        for (long node : adjacencyList.keySet()) {
            distances.put(node, Integer.MAX_VALUE); // 初始距离设为无穷大
            paths.put(node, null); // 初始路径为空
        }
        distances.put(source, 0); // 源节点的距离设为 0
        queue.add(new NodeDistance(source, 0)); // 将源节点加入优先队列

        // 处理队列中的节点
        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll(); // 获取距离最小的节点
            long currentNode = current.getNode();

            // 如果当前节点的距离已被更新，则跳过
            if (current.getDistance() > distances.get(currentNode)) {
                continue;
            }

            // 遍历当前节点的所有邻居
            for (LinkInfo link : adjacencyList.getOrDefault(currentNode, Collections.emptyList())) {
                long neighbor = link.getDestination(); // 邻居节点
                int newDist = distances.get(currentNode) + 1; // 新的距离（假设每条边的权重为 1）

                // 如果找到更短的路径，更新距离和路径信息
                if (newDist < distances.get(neighbor)) {
                    distances.put(neighbor, newDist);
                    paths.put(neighbor, new PathInfo(currentNode, link.getPort())); // 更新路径信息
                    queue.add(new NodeDistance(neighbor, newDist)); // 将邻居加入队列
                }
            }
        }

        return paths; // 返回从源节点到所有节点的路径信息
    }

    /**
     * 获取从指定源节点到所有其他节点的最短路径。
     * @param source 源节点
     * @return 最短路径信息的映射表
     */
    public Map<Long, PathInfo> getPathsFrom(long source) {
        return shortestPaths.getOrDefault(source, Collections.emptyMap());
    }

    /**
     * 打印当前网络拓扑的邻接表表示。
     */
    public void printTopology() {
        for (Map.Entry<Long, List<LinkInfo>> entry : adjacencyList.entrySet()) {
            long node = entry.getKey();
            LOGGER.info("Node {} connects to:", node);
            for (LinkInfo link : entry.getValue()) {
                LOGGER.info(" - Node {} via port {}", link.getDestination(), link.getPort());
            }
        }
    }

    /**
     * 打印所有节点的最短路径信息。
     */
    public void printShortestPaths() {
        for (Map.Entry<Long, Map<Long, PathInfo>> entry : shortestPaths.entrySet()) {
            long source = entry.getKey();
            LOGGER.info("Shortest paths from node {}:", source);
            for (Map.Entry<Long, PathInfo> pathEntry : entry.getValue().entrySet()) {
                long destination = pathEntry.getKey();
                PathInfo path = pathEntry.getValue();
                LOGGER.info(" - To node {} via port {}", destination, path.getPort());
            }
        }
    }

    // 内部类 LinkInfo，用于描述节点之间的链接信息
    private static class LinkInfo {
        private final long destination; // 链接的目标节点
        private final int port; // 使用的端口号

        public LinkInfo(long destination, int port) {
            this.destination = destination;
            this.port = port;
        }

        public long getDestination() {
            return destination;
        }

        public int getPort() {
            return port;
        }
    }

    // 内部类 NodeDistance，用于表示节点及其距离
    private static class NodeDistance {
        private final long node; // 节点
        private final int distance; // 距离

        public NodeDistance(long node, int distance) {
            this.node = node;
            this.distance = distance;
        }

        public long getNode() {
            return node;
        }

        public int getDistance() {
            return distance;
        }
    }

    // 静态类 PathInfo，用于描述最短路径信息
    public static class PathInfo {
        private final long previousNode; // 前一个节点
        private final int port; // 使用的端口号

        public PathInfo(long previousNode, int port) {
            this.previousNode = previousNode;
            this.port = port;
        }

        public long getPreviousNode() {
            return previousNode;
        }

        public int getPort() {
            return port;
        }
    }

    // 静态类 Link，用于表示网络中的链接
    public static class Link {
        private final long source; // 链接的源节点
        private final long destination; // 链接的目标节点
        private final int sourcePort; // 使用的源端口号

        public Link(long source, long destination, int sourcePort) {
            this.source = source;
            this.destination = destination;
            this.sourcePort = sourcePort;
        }

        public long getSource() {
            return source;
        }

        public long getDestination() {
            return destination;
        }

        public int getSourcePort() {
            return sourcePort;
        }
    }
}
