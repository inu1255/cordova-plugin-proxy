package cn.inu1255.cordova.proxy;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AdaptiveIconDrawable;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedList;
import java.util.List;

import cn.inu1255.cordova.proxy.core.Constant;
import cn.inu1255.cordova.proxy.core.LocalVpnService;
import cn.inu1255.cordova.proxy.core.Utils;

import static android.app.Activity.RESULT_OK;

/**
 * This class echoes a string called from JavaScript.
 */
public class Proxy extends CordovaPlugin implements LocalVpnService.onStatusChangedListener {
    private static final int START_VPN_SERVICE_REQUEST_CODE = 1985;
    private static String[] FILE_PERMISION = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    private CallbackContext statusCallback;
    private List<CallbackContext> stopCallback = new LinkedList<>();

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        LocalVpnService.addOnStatusChangedListener(this);
        cordova.requestPermissions(this, 0, FILE_PERMISION);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_DENIED) {
                return;
            }
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("appList")) {
            this.appList(callbackContext);
            return true;
        }
        if (action.equals("start")) {
            String agent = args.getString(0);
            JSONArray package_name = args.getJSONArray(1);
            this.start(agent, package_name, callbackContext);
            return true;
        }
        if (action.equals("stop")) {
            this.stop(callbackContext);
            return true;
        }
        if (action.equals("status")) {
            this.status(callbackContext);
            return true;
        }
        return false;
    }

    private void appList(CallbackContext callbackContext) {
        JSONArray list = new JSONArray();
        Context ctx = this.cordova.getContext();
        PackageManager pm = ctx.getPackageManager();
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<PackageInfo> packageInfos = pm.getInstalledPackages(0);
                    for (int i = 0; i < packageInfos.size(); i++) {
                        PackageInfo packageInfo = packageInfos.get(i);
                        JSONObject item = new JSONObject();
                        item.put("package", packageInfo.packageName);
                        item.put("isSys", (ApplicationInfo.FLAG_SYSTEM & packageInfo.applicationInfo.flags) != 0);
                        item.put("name", packageInfo.applicationInfo.loadLabel(pm));
                        list.put(item);
                    }
                    callbackContext.success(list);
                } catch (JSONException e) {
                    callbackContext.error(e.getMessage());
                }
            }
        });
    }

    /**
     * 启动代理
     *
     * @param package_name
     * @param callbackContext
     */
    private void start(String agent, JSONArray package_name, CallbackContext callbackContext) {
        Context ctx = this.cordova.getContext();
        this.statusCallback = callbackContext;
        PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
        pluginResult.setKeepCallback(true);
        callbackContext.sendPluginResult(pluginResult);

        Constant.proxy_ip = agent;
        Constant.package_name = package_name;
        Intent intent = LocalVpnService.prepare(ctx);
        if (intent == null) {
            ctx.startService(new Intent(ctx, LocalVpnService.class));
        } else {
            this.cordova.getActivity().startActivityForResult(intent, START_VPN_SERVICE_REQUEST_CODE);
        }
    }

    private void status(CallbackContext callbackContext) {
        JSONObject data = new JSONObject();
        try {
            data.put("running", LocalVpnService.IsRunning);
            data.put("send", LocalVpnService.m_SentBytes);
            data.put("recv", LocalVpnService.m_ReceivedBytes);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        callbackContext.success(data);
    }

    private void stop(CallbackContext callbackContext) {
        if (LocalVpnService.IsRunning) {
            synchronized (stopCallback) {
                stopCallback.add(callbackContext);
            }
            LocalVpnService.IsRunning = false;
        } else {
            callbackContext.success(2);
        }
    }

    @Override
    public void onStatusChanged(String status, Boolean isRunning) {
        if (this.statusCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, isRunning);
            result.setKeepCallback(true);
            this.statusCallback.sendPluginResult(result);
        }
        if (!isRunning && null != this.stopCallback) {
            for (CallbackContext callbackContext : stopCallback) {
                callbackContext.success(1);
            }
            synchronized (stopCallback) {
                stopCallback.clear();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == START_VPN_SERVICE_REQUEST_CODE) {
            Context ctx = this.cordova.getContext();
            if (resultCode == RESULT_OK) {
                ctx.startService(new Intent(ctx, LocalVpnService.class));
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, intent);
    }

    @Override
    public void onDestroy() {
        LocalVpnService.removeOnStatusChangedListener(this);
        super.onDestroy();
    }

}
