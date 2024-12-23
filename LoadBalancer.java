package edu.wisc.cs.sdn.apps.loadbalancer;
import java.util.*;
import org.openflow.protocol.*;
import org.openflow.protocol.action.*;
import org.openflow.protocol.instruction.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import edu.wisc.cs.sdn.apps.sps.InterfaceShortestPathSwitching;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.module.*;
import net.floodlightcontroller.devicemanager.*;
import net.floodlightcontroller.packet.*;

/**
 * 负载均衡器模块，用于分发流量到多个后端服务器。
 */
public class LoadBalancer implements IFloodlightModule, IOFSwitchListener, IOFMessageListener {

    // 模块名称，用于日志和标识
    private static final String MODULE_NAME = "LoadBalancer";
    // TCP SYN 和 RST 标志位，用于识别TCP连接
    private static final byte SYN_FLAG = 0x02;
    private static final byte RST_FLAG = 0x04;
    // 空闲超时时间（秒），用于规则失效
    private static final short IDLE_TIMEOUT = 20;

    // 日志记录器
    private static final Logger LOGGER = LoggerFactory.getLogger(MODULE_NAME);

    // Floodlight 服务和模块依赖
    private IFloodlightProviderService floodlightProvider;
    private IDeviceService deviceManager;
    private InterfaceShortestPathSwitching routingModule;

    // 用于保存流表索引
    private byte flowTable;
    // 虚拟 IP 实例的映射表，键为虚拟 IP 地址
    private Map<Integer, LoadBalancerInstance> vipInstances;

    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        // 初始化模块时调用
        LOGGER.info("Initializing {}...", MODULE_NAME);

        // 获取配置参数中的流表号
        this.flowTable = Byte.parseByte(context.getConfigParams(this).get("table"));
        // 解析配置，初始化虚拟 IP 实例
        this.vipInstances = parseConfig(context.getConfigParams(this).get("instances"));

        // 获取 Floodlight 提供的服务
        this.floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
        this.deviceManager = context.getServiceImpl(IDeviceService.class);
        this.routingModule = context.getServiceImpl(InterfaceShortestPathSwitching.class);
    }

    /**
     * 解析配置文件中的虚拟 IP 实例信息。
     * @param instanceConfig 配置字符串
     * @return 虚拟 IP 实例的映射表
     */
    private Map<Integer, LoadBalancerInstance> parseConfig(String instanceConfig) {
        Map<Integer, LoadBalancerInstance> instances = new HashMap<>();
        if (instanceConfig == null || instanceConfig.isEmpty()) return instances;

        // 配置格式: "虚拟IP 虚拟MAC 后端服务器IP1,后端服务器IP2"
        String[] configs = instanceConfig.split(";");
        for (String config : configs) {
            String[] parts = config.split(" ");
            if (parts.length != 3) {
                LOGGER.error("Invalid configuration: {}", config);
                continue;
            }
            // 创建 LoadBalancerInstance 对象并存储到映射表
            LoadBalancerInstance instance = new LoadBalancerInstance(parts[0], parts[1], parts[2].split(","));
            instances.put(instance.getVirtualIP(), instance);
            LOGGER.info("Added load balancer instance: {}", instance);
        }
        return instances;
    }

    @Override
    public void startUp(FloodlightModuleContext context) {
        // 启动模块时调用
        LOGGER.info("Starting {}...", MODULE_NAME);
        this.floodlightProvider.addOFSwitchListener(this);
        this.floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
    }

    @Override
    public void switchAdded(long switchId) {
        // 新交换机接入时调用
        LOGGER.info("Switch s{} added", switchId);
        IOFSwitch sw = this.floodlightProvider.getSwitch(switchId);

        // 为每个虚拟 IP 配置规则
        vipInstances.forEach((vip, instance) -> configureVirtualIPRules(sw, vip));
        // 配置默认转发规则
        configureDefaultForwarding(sw);
    }

    /**
     * 配置与虚拟 IP 相关的规则。
     * @param sw 交换机
     * @param virtualIP 虚拟 IP 地址
     */
    private void configureVirtualIPRules(IOFSwitch sw, int virtualIP) {
        // 创建动作：将流量发送到控制器
        OFInstructionApplyActions arpActions = createControllerOutputInstruction();
        OFInstructionApplyActions tcpActions = createControllerOutputInstruction();

        // 安装规则：匹配 ARP 请求
        installRule(sw, createArpMatch(virtualIP), arpActions, SwitchCommands.DEFAULT_PRIORITY + 1);
        // 安装规则：匹配 TCP 流量
        installRule(sw, createTcpMatch(virtualIP), tcpActions, SwitchCommands.DEFAULT_PRIORITY + 1);
    }

    /**
     * 创建匹配 ARP 请求的匹配条件。
     * @param virtualIP 虚拟 IP 地址
     * @return 匹配条件
     */
    private OFMatch createArpMatch(int virtualIP) {
        OFMatch match = new OFMatch();
        match.setDataLayerType(OFMatch.ETH_TYPE_ARP);
        match.setField(OFOXMFieldType.ARP_TPA, virtualIP); // 匹配目标协议地址
        return match;
    }

    /**
     * 创建匹配 TCP 流量的匹配条件。
     * @param virtualIP 虚拟 IP 地址
     * @return 匹配条件
     */
    private OFMatch createTcpMatch(int virtualIP) {
        OFMatch match = new OFMatch();
        match.setDataLayerType(OFMatch.ETH_TYPE_IPV4); // 匹配 IPv4 数据包
        match.setNetworkDestination(virtualIP); // 匹配目的地址
        return match;
    }

    /**
     * 创建一个输出到控制器的动作。
     * @return 动作指令
     */
    private OFInstructionApplyActions createControllerOutputInstruction() {
        OFActionOutput output = new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue());
        return new OFInstructionApplyActions(Collections.singletonList(output));
    }

    /**
     * 在交换机上安装规则。
     * @param sw 交换机
     * @param match 匹配条件
     * @param actions 动作
     * @param priority 优先级
     */
    private void installRule(IOFSwitch sw, OFMatch match, OFInstructionApplyActions actions, int priority) {
        // 移除旧规则，避免冲突
        SwitchCommands.removeRules(sw, flowTable, match);
        // 安装新规则
        SwitchCommands.installRule(sw, flowTable, (short) priority, match, Collections.singletonList(actions));
    }

    /**
     * 配置默认转发规则，将未匹配流量转发到路由模块处理。
     * @param sw 交换机
     */
    private void configureDefaultForwarding(IOFSwitch sw) {
        OFInstructionGotoTable gotoTable = new OFInstructionGotoTable(routingModule.getTable());
        SwitchCommands.installRule(sw, flowTable, SwitchCommands.DEFAULT_PRIORITY, new OFMatch(), Collections.singletonList(gotoTable));
    }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
        // 处理接收到的数据包
        if (msg.getType() != OFType.PACKET_IN) return Command.CONTINUE;

        OFPacketIn pktIn = (OFPacketIn) msg;
        Ethernet eth = new Ethernet();
        eth.deserialize(pktIn.getPacketData(), 0, pktIn.getPacketData().length);

        if (eth.getEtherType() == Ethernet.TYPE_ARP) {
            // 处理 ARP 请求
            handleArpRequest(sw, pktIn, eth);
        } else if (eth.getEtherType() == Ethernet.TYPE_IPv4) {
            // 处理 TCP 数据包
            handleTcpPacket(sw, pktIn, eth);
        }
        return Command.CONTINUE;
    }

    /**
     * 处理 ARP 请求。
     * @param sw 当前交换机
     * @param pktIn 接收到的包
     * @param eth 以太网帧
     */
    private void handleArpRequest(IOFSwitch sw, OFPacketIn pktIn, Ethernet eth) {
        ARP arp = (ARP) eth.getPayload();
        int vip = IPv4.toIPv4Address(arp.getTargetProtocolAddress());
        if (arp.getOpCode() != ARP.OP_REQUEST || !vipInstances.containsKey(vip)) return;

        LoadBalancerInstance instance = vipInstances.get(vip);

        // 构建 ARP 响应
        arp.setOpCode(ARP.OP_REPLY);
        arp.setTargetHardwareAddress(arp.getSenderHardwareAddress());
        arp.setSenderHardwareAddress(instance.getVirtualMAC());
        arp.setTargetProtocolAddress(arp.getSenderProtocolAddress());
        arp.setSenderProtocolAddress(vip);

        eth.setDestinationMACAddress(eth.getSourceMACAddress());
        eth.setSourceMACAddress(instance.getVirtualMAC());

        // 发送 ARP 响应回去
        SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), eth);
    }

    /**
     * 处理 TCP 数据包。
     * @param sw 当前交换机
     * @param pktIn 接收到的包
     * @param eth 以太网帧
     */
    private void handleTcpPacket(IOFSwitch sw, OFPacketIn pktIn, Ethernet eth) {
        IPv4 ip = (IPv4) eth.getPayload();
        int vip = ip.getDestinationAddress();
        if (ip.getProtocol() != IPv4.PROTOCOL_TCP || !vipInstances.containsKey(vip)) return;

        TCP tcp = (TCP) ip.getPayload();
        if ((tcp.getFlags() & SYN_FLAG) != 0) {
            // 处理 TCP SYN 包
            processTcpSyn(sw, vip, ip, tcp);
        } else {
            // 发送 TCP 重置包
            sendTcpReset(sw, vip, pktIn, eth);
        }
    }

    /**
     * 处理 TCP SYN 包。
     * @param sw 当前交换机
     * @param vip 虚拟 IP
     * @param ip IPv4 数据包
     * @param tcp TCP 数据包
     */
    private void processTcpSyn(IOFSwitch sw, int vip, IPv4 ip, TCP tcp) {
        LoadBalancerInstance instance = vipInstances.get(vip);
        int clientIP = ip.getSourceAddress();
        short clientPort = tcp.getSourcePort();

        int serverIP = instance.getNextHostIP();
        byte[] serverMAC = getMacAddress(serverIP);
        short serverPort = tcp.getDestinationPort();

        // 安装规则，将客户端流量转发到服务器
        installInboundRule(sw, clientIP, clientPort, vip, serverIP, serverMAC, serverPort);
        // 安装规则，将服务器流量转发回客户端
        installOutboundRule(sw, clientIP, clientPort, vip, serverIP, serverMAC, serverPort);
    }

    /**
     * 安装入站规则，将流量从客户端转发到服务器。
     */
    private void installInboundRule(IOFSwitch sw, int clientIP, short clientPort, int vip, int serverIP, byte[] serverMAC, short serverPort) {
        OFMatch match = new OFMatch();
        match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
        match.setNetworkSource(clientIP);
        match.setTransportSource(clientPort);
        match.setNetworkDestination(vip);
        match.setTransportDestination(serverPort);

        OFActionSetField setMAC = new OFActionSetField(new OFOXMField(OFOXMFieldType.ETH_DST, serverMAC));
        OFActionSetField setIP = new OFActionSetField(new OFOXMField(OFOXMFieldType.IPV4_DST, serverIP));
        OFInstructionApplyActions actions = new OFInstructionApplyActions(Arrays.asList(setMAC, setIP));
        SwitchCommands.installRule(sw, flowTable, SwitchCommands.MAX_PRIORITY, match, Collections.singletonList(actions), (short) 0, IDLE_TIMEOUT);
    }

    /**
     * 安装出站规则，将流量从服务器转发回客户端。
     */
    private void installOutboundRule(IOFSwitch sw, int clientIP, short clientPort, int vip, int serverIP, byte[] serverMAC, short serverPort) {
        OFMatch match = new OFMatch();
        match.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
        match.setNetworkSource(serverIP);
        match.setTransportSource(serverPort);
        match.setNetworkDestination(clientIP);
        match.setTransportDestination(clientPort);

        OFActionSetField setMAC = new OFActionSetField(new OFOXMField(OFOXMFieldType.ETH_SRC, vipInstances.get(vip).getVirtualMAC()));
        OFActionSetField setIP = new OFActionSetField(new OFOXMField(OFOXMFieldType.IPV4_SRC, vip));
        OFInstructionApplyActions actions = new OFInstructionApplyActions(Arrays.asList(setMAC, setIP));
        SwitchCommands.installRule(sw, flowTable, SwitchCommands.MAX_PRIORITY, match, Collections.singletonList(actions), (short) 0, IDLE_TIMEOUT);
    }

    /**
     * 发送 TCP 重置包，终止连接。
     */
    private void sendTcpReset(IOFSwitch sw, int vip, OFPacketIn pktIn, Ethernet eth) {
        TCP tcp = new TCP();
        tcp.setFlags(RST_FLAG);
        tcp.setSourcePort(((TCP) ((IPv4) eth.getPayload()).getPayload()).getDestinationPort());

        IPv4 ip = new IPv4();
        ip.setProtocol(IPv4.PROTOCOL_TCP);
        ip.setSourceAddress(vip);
        ip.setDestinationAddress(((IPv4) eth.getPayload()).getSourceAddress());
        ip.setPayload(tcp);

        Ethernet resetPacket = new Ethernet();
        resetPacket.setEtherType(Ethernet.TYPE_IPv4);
        resetPacket.setSourceMACAddress(vipInstances.get(vip).getVirtualMAC());
        resetPacket.setDestinationMACAddress(eth.getSourceMACAddress());
        resetPacket.setPayload(ip);

        SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), resetPacket);
    }

    /**
     * 获取指定 IP 的 MAC 地址。
     * @param ipAddress IP 地址
     * @return MAC 地址
     */
    private byte[] getMacAddress(int ipAddress) {
        Iterator<? extends IDevice> devices = deviceManager.queryDevices(null, null, ipAddress, null, null);
        return devices.hasNext() ? devices.next().getMACAddress().toBytes() : null;
    }

    // 以下为必要的接口实现，确保模块能够与 FloodLight 框架正常交互
    @Override
    public void switchRemoved(long switchId) {}

    @Override
    public void switchActivated(long switchId) {}

    @Override
    public void switchPortChanged(long switchId, ImmutablePort port, IOFSwitch.PortChangeType type) {}

    @Override
    public void switchChanged(long switchId) {}

    @Override
    public boolean isCallbackOrderingPrereq(OFType type, String name) {
        return type == OFType.PACKET_IN && (name.equals("ArpServer") || name.equals("DeviceManager"));
    }

    @Override
    public boolean isCallbackOrderingPostreq(OFType type, String name) {
        return false;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return null;
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return null;
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
        return Arrays.asList(IFloodlightProviderService.class, IDeviceService.class);
    }

    @Override
    public String getName() {
        return MODULE_NAME;
    }
}
