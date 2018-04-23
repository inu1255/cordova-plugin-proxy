package cn.inu1255.cordova.proxy.tunnel;

import java.net.InetSocketAddress;

public abstract class Config {
    public InetSocketAddress ServerAddress;
    public IEncryptor Encryptor;
}
