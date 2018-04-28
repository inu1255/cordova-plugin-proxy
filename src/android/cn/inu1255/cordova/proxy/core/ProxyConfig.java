package cn.inu1255.cordova.proxy.core;

import org.json.JSONArray;
import org.json.JSONException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import cn.inu1255.cordova.proxy.tcpip.CommonMethods;
import cn.inu1255.cordova.proxy.tunnel.Config;
import cn.inu1255.cordova.proxy.tunnel.httpconnect.HttpConnectConfig;
import cn.inu1255.cordova.proxy.tunnel.shadowsocks.ShadowsocksConfig;

public class ProxyConfig {
    public static final ProxyConfig Instance = new ProxyConfig();
    public final static int FAKE_NETWORK_MASK = CommonMethods.ipStringToInt("255.255.0.0");
    public final static int FAKE_NETWORK_IP = CommonMethods.ipStringToInt("26.25.0.0");
    public static String AppInstallID;
    public static String AppVersion;
    public static boolean IS_DEBUG = false;

    ArrayList<IPAddress> m_IpList;
    ArrayList<IPAddress> m_DnsList;
    Config m_Proxy = null;
    HashMap<String, Boolean> m_DomainMap;

    int m_dns_ttl = 10;
    String m_session_name = Constant.TAG;
    String m_user_agent = System.getProperty("http.agent");
    int m_mtu = 1500;

    boolean m_isolate_http_host_header = true;
    public boolean m_all_proxy = false;

    Timer m_Timer;

    public ProxyConfig() {
        m_IpList = new ArrayList<IPAddress>();
        m_DnsList = new ArrayList<IPAddress>();
        m_DomainMap = new HashMap<String, Boolean>();

        m_IpList.add(new IPAddress("26.26.26.2", 32));
        m_DnsList.add(new IPAddress("119.29.29.29"));
        m_DnsList.add(new IPAddress("223.5.5.5"));
        m_DnsList.add(new IPAddress("8.8.8.8"));

        m_Timer = new Timer();
        m_Timer.schedule(m_Task, 120000, 120000);//每两分钟刷新一次。
    }

    public static boolean isFakeIP(int ip) {
        return (ip & ProxyConfig.FAKE_NETWORK_MASK) == ProxyConfig.FAKE_NETWORK_IP;
    }


    TimerTask m_Task = new TimerTask() {
        @Override
        public void run() {
            refreshProxyServer();//定时更新dns缓存
        }

        //定时更新dns缓存
        void refreshProxyServer() {
            if (null != m_Proxy) {
                Config config = m_Proxy;
                try {
                    InetAddress address = InetAddress.getByName(config.ServerAddress.getHostName());
                    if (address != null && !address.equals(config.ServerAddress.getAddress())) {
                        config.ServerAddress = new InetSocketAddress(address, config.ServerAddress.getPort());
                    }
                } catch (UnknownHostException e) {
                }
            }
        }
    };

    public Config getDefaultProxy() {
        if (null == m_Proxy) {
            return HttpConnectConfig.parse("http://127.0.0.1:8787");
        }
        return m_Proxy;
    }

    public Config getDefaultTunnelConfig(InetSocketAddress destAddress) {
        return getDefaultProxy();
    }

    public IPAddress getDefaultLocalIP() {
        return m_IpList.get(0);
    }

    public ArrayList<IPAddress> getDnsList() {
        return m_DnsList;
    }

    public int getDnsTTL() {
        if (m_dns_ttl < 30) {
            m_dns_ttl = 30;
        }
        return m_dns_ttl;
    }

    public String getSessionName() {
        if (m_session_name == null) {
            m_session_name = getDefaultProxy().ServerAddress.getHostName();
        }
        return m_session_name;
    }

    public String getUserAgent() {
        if (m_user_agent == null || m_user_agent.isEmpty()) {
            m_user_agent = System.getProperty("http.agent");
        }
        return m_user_agent;
    }

    public int getMTU() {
        if (m_mtu > 1400 && m_mtu <= 20000) {
            return m_mtu;
        } else {
            return 20000;
        }
    }

    private Boolean getDomainState(String domain) {
        domain = domain.toLowerCase();
        while (domain.length() > 0) {
            Boolean stateBoolean = m_DomainMap.get(domain);
            if (stateBoolean != null) {
                return stateBoolean;
            } else {
                int start = domain.indexOf('.') + 1;
                if (start > 0 && start < domain.length()) {
                    domain = domain.substring(start);
                } else {
                    return null;
                }
            }
        }
        return null;
    }

    public boolean needProxy(String host, int ip) {
        if (host != null) {
            Boolean stateBoolean = getDomainState(host);
            if (stateBoolean != null) {
                return stateBoolean.booleanValue();
            }
            return m_all_proxy;
        }
        return false;
    }

    public boolean isIsolateHttpHostHeader() {
        return m_isolate_http_host_header;
    }

    public void addProxy(String proxyString) throws Exception {
        Config config = null;
        if (proxyString.startsWith("ss://")) {
            config = ShadowsocksConfig.parse(proxyString);
        } else {
            if (!proxyString.toLowerCase().startsWith("http://")) {
                proxyString = "http://" + proxyString;
            }
            config = HttpConnectConfig.parse(proxyString);
        }
        m_Proxy = config;
        m_DomainMap.put(config.ServerAddress.getHostName(), false);
    }

    public void setDomainProxy(String domainString, Boolean state) {
        if (domainString.charAt(0) == '.') {
            domainString = domainString.substring(1);
        }
        m_DomainMap.put(domainString, state);
    }

    public JSONArray setDomainsProxy(JSONArray domains, boolean status) {
        m_DomainMap.clear();
        ProxyConfig.Instance.m_all_proxy = status;
        status = !status;

        JSONArray data = new JSONArray();
        int n = domains.length();
        for (int i = 0; i < n; i++) {
            try {
                ProxyConfig.Instance.setDomainProxy(domains.getString(0), status);
                data.put(true);
            } catch (JSONException e) {
                data.put(false);
            }
        }
        return data;
    }

    public class IPAddress {
        public final String Address;
        public final int PrefixLength;

        public IPAddress(String address, int prefixLength) {
            this.Address = address;
            this.PrefixLength = prefixLength;
        }

        public IPAddress(String ipAddressString) {
            String[] arrStrings = ipAddressString.split("/");
            String address = arrStrings[0];
            int prefixLength = 32;
            if (arrStrings.length > 1) {
                prefixLength = Integer.parseInt(arrStrings[1]);
            }
            this.Address = address;
            this.PrefixLength = prefixLength;
        }

        @Override
        public String toString() {
            return String.format(Locale.ENGLISH, "%s/%d", Address, PrefixLength);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            } else {
                return this.toString().equals(o.toString());
            }
        }
    }

}
