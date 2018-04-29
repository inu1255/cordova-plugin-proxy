var exec = require('cordova/exec');
var start_list = [];
var apps_list;

function onStatusChange(flag) {
    cordova.fireWindowEvent('proxy.status', { flag });
    if (flag) {
        for (let item of start_list) {
            item();
        }
        start_list = [];
    }
}
exports.appList = function(force) {
    return new Promise((resolve, reject) => {
        if (apps_list && !force) resolve(apps_list);
        else exec(function(apps) {
            apps_list = apps;
            resolve(apps);
        }, reject, 'proxy', 'appList', []);
    });
};
exports.start = function(agent) {
    return new Promise((resolve, reject) => {
        exec(function() {
            start_list.push(resolve);
            agent = agent || null;
            exec(onStatusChange, reject, 'proxy', 'start', [agent]);
        }, reject, 'proxy', 'stop', []);
    });
};
exports.stop = function() {
    return new Promise((resolve, reject) => {
        exec(resolve, reject, 'proxy', 'stop', []);
    });
};
exports.isRunning = function() {
    return new Promise((resolve, reject) => {
        exec(x => resolve(x && x.running), reject, 'proxy', 'status', []);
    });
};
exports.status = function() {
    return new Promise((resolve, reject) => {
        exec(resolve, reject, 'proxy', 'status', []);
    });
};
exports.cert = function(key, name) {
    return new Promise((resolve, reject) => {
		key = key || "ca.crt"
		name = name || "代理证书"
        exec(resolve, reject, 'proxy', 'cert', [key, name]);
    });
};
exports.setDomainProxy = function(domains, status) {
    return new Promise((resolve, reject) => {
        if (domains instanceof Array);
        else if (domains == null) domains = [];
        else domains = [domains + ""];
        status = Boolean(status);
        exec(resolve, reject, 'proxy', 'setDomainProxy', [domains, status]);
    });
};
