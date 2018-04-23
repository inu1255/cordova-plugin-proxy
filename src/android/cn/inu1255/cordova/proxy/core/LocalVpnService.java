package cn.inu1255.cordova.proxy.core;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.apache.cordova.CordovaActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cn.inu1255.cordova.proxy.core.ProxyConfig.IPAddress;
import cn.inu1255.cordova.proxy.dns.DnsPacket;
import cn.inu1255.cordova.proxy.tcpip.CommonMethods;
import cn.inu1255.cordova.proxy.tcpip.IPHeader;
import cn.inu1255.cordova.proxy.tcpip.TCPHeader;
import cn.inu1255.cordova.proxy.tcpip.UDPHeader;

public class LocalVpnService extends VpnService implements Runnable {

    public static LocalVpnService Instance;
    public static boolean IsRunning = false;

    private static int ID;
    private static int LOCAL_IP;
    private static ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<onStatusChangedListener, Object>();

    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    private byte[] m_Packet;
    private IPHeader m_IPHeader;
    private TCPHeader m_TCPHeader;
    private UDPHeader m_UDPHeader;
    private ByteBuffer m_DNSBuffer;
    private Handler m_Handler;
    public static long m_SentBytes;
    public static long m_ReceivedBytes;

    public LocalVpnService() {
        ID++;
        m_Handler = new Handler();
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        Log.d("BaoLianDeng", "New VPNService" + ID);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        if (m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.remove(listener);
        }
    }

    @Override
    public void onCreate() {
        try {
            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            writeLog("LocalTcpServer started.");

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();
            writeLog("LocalDnsProxy started.");
        } catch (Exception e) {
            writeLog("Failed to start TCP/DNS Proxy");
        }

        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        IsRunning = true;
        // Start a new session by creating a new thread.
        m_VPNThread = new Thread(this, "VPNServiceThread");
        m_VPNThread.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action.equals(VpnService.SERVICE_INTERFACE)) {
            return super.onBind(intent);
        }
        return null;
    }

    private void onStatusChanged(final String status, final boolean isRunning) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onStatusChanged(status, isRunning);
                }
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        Log.d(Constant.TAG, logString);
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences(Constant.TAG, MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.commit();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            // getPackageName()是你当前类的包名，0代表是获取版本信息
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            String version = packInfo.versionName;
            return version;
        } catch (Exception e) {
            return "0.0";
        }
    }

    @Override
    public synchronized void run() {
        try {
            Log.d(Constant.TAG, "VPNService work thread is running... " + ID);

            ProxyConfig.AppInstallID = getAppInstallID();//获取安装ID
            ProxyConfig.AppVersion = getVersionName();//获取版本号
            writeLog("Android version: %s", Build.VERSION.RELEASE);
            writeLog("App version: %s", ProxyConfig.AppVersion);

            waitUntilPrepared();//检查是否准备完毕。

            ProxyConfig.Instance.addProxy(Constant.proxy_ip);

            runVPN();

        } catch (InterruptedException e) {
            Log.e(Constant.TAG, "Exception", e);
        } catch (Exception e) {
            e.printStackTrace();
            writeLog("Fatal error: %s", e.toString());
        }
        writeLog("SmartProxy terminated.");
        dispose();
    }

    private void runVPN() throws Exception {
        this.m_VPNInterface = establishVPN();
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor());
        try {
            while (IsRunning) {
                boolean idle = true;
                int size = in.read(m_Packet);
                if (size > 0) {
                    if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                        in.close();
                        throw new Exception("LocalServer stopped.");
                    }
                    try {
                        onIPPacketReceived(m_IPHeader, size);
                        idle = false;
                    } catch (IOException ex) {
                        Log.e(Constant.TAG, "IOException when processing IP packet", ex);
                    }
                }
                if (idle) {
                    Thread.sleep(100);
                }
            }
        } finally {
            in.close();
        }
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {// 收到本地TCP服务器数据
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            if (ProxyConfig.IS_DEBUG)
                                Log.d(Constant.TAG, "NoSession: " +
                                        ipHeader.toString() + " " +
                                        tcpHeader.toString());
                        }
                    } else {

                        // 添加端口映射
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;//注意顺序

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;//丢弃tcp握手的第二个ACK报文。因为客户端发数据的时候也会带上ACK，这样可以在服务器Accept之前分析出HOST信息。
                        }

                        //分析数据，找到host
                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            }
                        }

                        // 转发给本地TCP服务器
                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;//注意顺序
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                // 转发DNS数据包：
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPrepared() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    private ParcelFileDescriptor establishVPN() throws Exception {

        NatSessionManager.clearAllSessions();

        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());

        IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);

        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        if (ProxyConfig.IS_DEBUG)
            Log.d(Constant.TAG, String.format("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength));

        for (ProxyConfig.IPAddress dns : ProxyConfig.Instance.getDnsList()) {
            builder.addDnsServer(dns.Address);
            if (ProxyConfig.IS_DEBUG)
                System.out.printf("addDnsServer: %s\n", dns.Address);
        }

        for (String routeAddress : Constant.private_route) {
            String[] addr = routeAddress.split("/");
            builder.addRoute(addr[0], Integer.parseInt(addr[1]));
        }

        builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && null != Constant.package_name) {
//            int n = Constant.package_name.length();
//            if (n > 0) {
//                PackageManager packageManager = getPackageManager();
//                List<PackageInfo> list = packageManager.getInstalledPackages(0);
//                HashSet<String> packageSet = new HashSet<>();
//
//                for (int i = 0; i < n; i++) {
//                    packageSet.add(Constant.package_name.getString(i));
//                }
//
//                for (int i = 0; i < list.size(); i++) {
//                    PackageInfo info = list.get(i);
//                    if (!packageSet.contains(info.packageName))
//                        builder.addDisallowedApplication(info.packageName);
//                }
//            }
//        }

        Intent intent = new Intent(this, CordovaActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setConfigureIntent(pendingIntent);

        builder.setSession(ProxyConfig.Instance.getSessionName());
        ParcelFileDescriptor pfdDescriptor = builder.establish();
        onStatusChanged(ProxyConfig.Instance.getSessionName() + " connected", true);
        return pfdDescriptor;
    }

    private synchronized void dispose() {

        onStatusChanged(ProxyConfig.Instance.getSessionName() + " disconnected", false);

        IsRunning = false;

        // 断开VPN
        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            if (m_VPNOutputStream != null) {
                m_VPNOutputStream.close();
                m_VPNOutputStream = null;
            }
        } catch (Exception e) {
            // ignore
        }

        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
            m_VPNThread = null;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(Constant.TAG, "VPNService(%s) destroyed: " + ID);
        if (IsRunning) dispose();
        try {
            // 停止TcpServer
            if (m_TcpProxyServer != null) {
                m_TcpProxyServer.stop();
                m_TcpProxyServer = null;
                // writeLog("LocalTcpServer stopped.");
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            // 停止DNS解析器
            if (m_DnsProxy != null) {
                m_DnsProxy.stop();
                m_DnsProxy = null;
                // writeLog("LocalDnsProxy stopped.");
            }
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    public interface onStatusChangedListener {
        void onStatusChanged(String status, Boolean isRunning);
    }

}
