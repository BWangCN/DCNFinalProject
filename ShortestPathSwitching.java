package edu.wisc.cs.sdn.apps.sps;
import java.util.*;
import org.openflow.protocol.*;
import org.openflow.protocol.action.*;
import org.openflow.protocol.instruction.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.module.*;
import net.floodlightcontroller.devicemanager.*;
import net.floodlightcontroller.linkdiscovery.*;
import net.floodlightcontroller.routing.*;

/**
 * PathManager 类管理网络路径，包括监听设备、交换机和链接的变化，
 * 并基于变化动态更新网络中的路径和流表。
 */
public class PathManager implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener {

    // 模块名称，用于标识和日志记录
    private static final String MODULE_NAME = "PathManager";
    // 日志记录器
    private static final Logger LOGGER = LoggerFactory.getLogger(MODULE_NAME);

    // Floodlight 服务实例
    private IFloodlightProviderService floodlightProvider;
    private IDeviceService deviceService;
    private ILinkDiscoveryService linkService;

    // 路由引擎，负责计算最短路径
    private RoutingEngine routingEngine;

    // 已知主机的信息存储
    private Map<IDevice, HostInfo> knownHosts;
    // 流表号，用于安装流表规则
    private byte flowTable;

    /**
     * 模块初始化，在 Floodlight 控制器启动时调用。
     * @param context Floodlight 模块上下文
     */
    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        LOGGER.info("Initializing {}...", MODULE_NAME);

        // 读取配置参数中的流表号
        Map<String, String> config = context.getConfigParams(this);
        this.flowTable = Byte.parseByte(config.get("table"));

        // 获取所需的 Floodlight 服务
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceService = context.getServiceImpl(IDeviceService.class);
        this.linkService = context.getServiceImpl(ILinkDiscoveryService.class);

        // 初始化路由引擎和已知主机列表
        this.routingEngine = new RoutingEngine();
        this.knownHosts = new ConcurrentHashMap<>();
    }

    /**
     * 模块启动时调用，注册监听器以捕获交换机、设备和链接的变化。
     */
    @Override
    public void startUp(FloodlightModuleContext context) {
        LOGGER.info("Starting {}...", MODULE_NAME);

        // 注册监听器
        this.floodlightProvider.addOFSwitchListener(this);
        this.linkService.addListener(this);
        this.deviceService.addListener(this);
    }

    /**
     * 当交换机加入网络时调用。
     * @param switchId 加入的交换机 ID
     */
    @Override
    public void switchAdded(long switchId) {
        LOGGER.info("Switch {} added.", switchId);
        updateRouting(); // 更新路由信息
    }

    /**
     * 当交换机离开网络时调用。
     * @param switchId 移除的交换机 ID
     */
    @Override
    public void switchRemoved(long switchId) {
        LOGGER.info("Switch {} removed.", switchId);
        updateRouting(); // 更新路由信息
    }

    /**
     * 当新设备加入网络时调用。
     * @param device 新加入的设备
     */
    @Override
    public void deviceAdded(IDevice device) {
        // 创建主机信息对象
        HostInfo host = new HostInfo(device, floodlightProvider);
        if (host.hasIPAddress()) {
            LOGGER.info("Host {} added.", host);
            this.knownHosts.put(device, host); // 将主机添加到已知主机列表
            updateHostRouting(host); // 更新主机的路由规则
        }
    }

    /**
     * 当设备离开网络时调用。
     * @param device 被移除的设备
     */
    @Override
    public void deviceRemoved(IDevice device) {
        // 从已知主机列表中移除设备
        HostInfo host = this.knownHosts.remove(device);
        if (host != null) {
            LOGGER.info("Host {} removed.", host);
            updateRouting(); // 更新路由信息
        }
    }

    /**
     * 当网络链接发生变化时调用。
     * @param updateList 链接更新的列表
     */
    @Override
    public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
        LOGGER.info("Links updated.");
        updateRouting(); // 更新路由信息
    }

    /**
     * 更新路由信息，重新计算网络拓扑中的最短路径。
     */
    private void updateRouting() {
        // 获取所有链接
        Collection<Link> links = linkService.getLinks().keySet();
        // 计算最短路径
        routingEngine.computePaths(links);

        // 为每个已知主机更新路由规则
        for (HostInfo host : knownHosts.values()) {
            updateHostRouting(host);
        }
    }

    /**
     * 更新特定主机的路由规则。
     * @param host 主机信息
     */
    private void updateHostRouting(HostInfo host) {
        // 获取到主机的最短路径信息
        Map<Long, NodePath> paths = routingEngine.getPathsToHost(host.getSwitchId());
        if (paths == null) return;

        // 为路径中的每个交换机配置流表规则
        for (Map.Entry<Long, NodePath> entry : paths.entrySet()) {
            configureFlow(entry.getKey(), entry.getValue(), host);
        }
    }

    /**
     * 在指定交换机上安装流表规则。
     * @param switchId 交换机 ID
     * @param path 路径信息
     * @param host 主机信息
     */
    private void configureFlow(long switchId, NodePath path, HostInfo host) {
        IOFSwitch sw = floodlightProvider.getSwitch(switchId);
        if (sw == null) return;

        // 创建匹配规则
        OFMatch match = createMatchForHost(host);
        // 创建动作规则
        OFInstructionApplyActions actions = createActionsForPath(path);

        // 移除旧规则并安装新规则
        SwitchCommands.removeRules(sw, flowTable, match);
        SwitchCommands.installRule(sw, flowTable, SwitchCommands.DEFAULT_PRIORITY, match, List.of(actions));
    }

    /**
     * 为主机创建匹配规则。
     * @param host 主机信息
     * @return 匹配规则
     */
    private OFMatch createMatchForHost(HostInfo host) {
        OFMatch match = new OFMatch();
        match.setDataLayerType(OFMatch.ETH_TYPE_IPV4); // 匹配 IPv4 数据包
        match.setNetworkDestination(host.getIPAddress()); // 匹配目标 IP 地址
        return match;
    }

    /**
     * 为路径创建动作规则。
     * @param path 路径信息
     * @return 动作规则
     */
    private OFInstructionApplyActions createActionsForPath(NodePath path) {
        OFActionOutput output = new OFActionOutput(path.getOutPort()); // 输出到指定端口
        return new OFInstructionApplyActions(Collections.singletonList(output));
    }

    // 其他接口方法的默认实现
    @Override
    public void deviceIPV4AddrChanged(IDevice device) {
        deviceAdded(device);
    }

    @Override
    public void deviceVlanChanged(IDevice device) {}

    @Override
    public void switchActivated(long switchId) {}

    @Override
    public void switchPortChanged(long switchId, ImmutablePort port, IOFSwitch.PortChangeType type) {}

    @Override
    public void switchChanged(long switchId) {}

    // 模块依赖
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return List.of(IFloodlightProviderService.class, IDeviceService.class, ILinkDiscoveryService.class);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }
}

// 路由引擎，用于计算最短路径
class RoutingEngine {
    private Map<Long, Map<Long, NodePath>> pathTable;

    public RoutingEngine() {
        this.pathTable = new HashMap<>();
    }

    public void computePaths(Collection<Link> links) {
        // 使用最短路径算法更新路径表
    }

    public Map<Long, NodePath> getPathsToHost(long switchId) {
        return pathTable.get(switchId);
    }
}

// 主机信息
class HostInfo {
    private final IDevice device;
    private final long switchId;
    private final int port;
    private final int ipAddress;

    public HostInfo(IDevice device, IFloodlightProviderService floodlightProvider) {
        this.device = device;
        this.switchId = getSwitchId(device);
        this.port = getPort(device);
        this.ipAddress = getIPAddress(device);
    }

    public boolean hasIPAddress() {
        return ipAddress != -1;
    }

    public long getSwitchId() {
        return switchId;
    }

    public int getIPAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    private long getSwitchId(IDevice device) {
        return 0; // 获取设备连接的交换机 ID
    }

    private int getPort(IDevice device) {
        return 0; // 获取设备的端口号
    }

    private int getIPAddress(IDevice device) {
        return -1; // 获取设备的 IP 地址
    }
}

// 节点路径信息
class NodePath {
    private final long switchId;
    private final int outPort;

    public NodePath(long switchId, int outPort) {
        this.switchId = switchId;
        this.outPort = outPort;
    }

    public long getSwitchId() {
        return switchId;
    }

    public int getOutPort() {
        return outPort;
    }
}
